package com.lagradost.cloudstream3.ui.home

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginManager
import java.io.File
import java.lang.reflect.Field

/**
 * Bridges the app's home customisation with plugins that expose several child
 * providers (Cricify, PlayFy and similar).
 *
 * Those plugins keep their own checklist in a private preference file and only
 * register the children that were ticked there. The list of possible children is
 * kept in an `iptvProviders` field on the plugin instance, so read it through
 * reflection and let the manager write the same boolean keys the plugin uses.
 */
data class ChildProviderInfo(
    val title: String,
    val enabled: Boolean,
)

data class ChildProviderGroup(
    val pluginPath: String,
    val pluginName: String,
    val prefsName: String,
    private val prefs: SharedPreferences,
    val children: List<ChildProviderInfo>,
) {
    fun setEnabled(title: String, enabled: Boolean) {
        // The plugin reads keys with a false default, so writing false is the
        // same as removing the key while keeping the stored checklist intact.
        prefs.edit().putBoolean(title, enabled).apply()
    }
}

object ChildProviderSettings {
    private const val PROVIDER_LIST_FIELD = "iptvProviders"
    private val PREF_NAME_REGEX = Regex("[A-Za-z0-9_.-]+")

    /** Groups of child providers for every loaded plugin that exposes a list. */
    fun groups(context: Context): List<ChildProviderGroup> {
        val appContext = context.applicationContext
        return PluginManager.plugins.mapNotNull { (path, basePlugin) ->
            val plugin = basePlugin as? Plugin ?: return@mapNotNull null
            val titles = readChildTitles(plugin) ?: return@mapNotNull null
            if (titles.size <= 1) return@mapNotNull null
            val prefsName = prefsName(plugin) ?: return@mapNotNull null
            val prefs = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            ChildProviderGroup(
                pluginPath = path,
                pluginName = pluginName(plugin),
                prefsName = prefsName,
                prefs = prefs,
                children = titles
                    .distinct()
                    .map { ChildProviderInfo(it, prefs.getBoolean(it, false)) },
            )
        }.sortedBy { it.pluginName.lowercase() }
    }

    /**
     * Child-provider selections for the backup file, keyed by the plugin's
     * preference file name. All known titles are included, even disabled ones,
     * so restoring on another device also clears selections that are not in the
     * backup.
     */
    fun backup(context: Context): Map<String, Map<String, Boolean>> =
        groups(context).associate { group ->
            group.prefsName to group.children.associate { it.title to it.enabled }
        }

    /** Applies child-provider selections from a backup file. */
    fun restore(context: Context, backup: Map<String, Map<String, Boolean>>) {
        val appContext = context.applicationContext
        backup.forEach { (name, values) ->
            if (!PREF_NAME_REGEX.matches(name)) return@forEach
            val prefs = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            // Only touch boolean entries; the same file may contain other
            // plugin settings that this feature should not erase.
            prefs.all.filterValues { it is Boolean }.keys.forEach(editor::remove)
            values.forEach { (key, value) -> editor.putBoolean(key, value) }
            editor.apply()
        }
    }

    /**
     * Re-runs the plugin so a newly enabled/disabled child is registered or
     * removed without requiring a full app restart.
     */
    suspend fun reload(context: Context, group: ChildProviderGroup): Boolean {
        val path = group.pluginPath
        val data = (
            PluginManager.getPluginsOnline().asList() +
                PluginManager.getPluginsLocal().asList()
            ).firstOrNull { it.filePath == path } ?: return false
        PluginManager.unloadPlugin(path)
        return PluginManager.loadPlugin(context.applicationContext, File(path), data)
    }

    private fun prefsName(plugin: Plugin): String? =
        plugin::class.java.simpleName
            .removeSuffix("Plugin")
            .takeIf { it.isNotBlank() }

    private fun pluginName(plugin: Plugin): String =
        plugin::class.java.simpleName.removeSuffix("Plugin")

    private fun readChildTitles(plugin: Plugin): List<String>? {
        val field = findProviderField(plugin) ?: return null
        val value = runCatching {
            field.isAccessible = true
            field.get(plugin)
        }.getOrNull() ?: return null
        val list = value as? List<*> ?: return null
        return list.mapNotNull { item ->
            (item as? Map<*, *>)?.get("title") as? String
        }.filter { it.isNotBlank() }
    }

    /**
     * Prefer the field the known Cricify/PlayFy/LIVETV-style plugins use, then
     * fall back to any field that looks like a list of provider maps.
     */
    private fun findProviderField(plugin: Plugin): Field? {
        var current: Class<*>? = plugin::class.java
        while (current != null) {
            runCatching { current.getDeclaredField(PROVIDER_LIST_FIELD) }
                .getOrNull()
                ?.let { return it }
            current = current.superclass
        }

        current = plugin::class.java
        while (current != null) {
            val field = current.declaredFields.firstOrNull { candidate ->
                List::class.java.isAssignableFrom(candidate.type) &&
                    runCatching {
                        candidate.isAccessible = true
                        val list = candidate.get(plugin) as? List<*> ?: return@runCatching false
                        list.any { (it as? Map<*, *>)?.get("title") is String }
                    }.getOrDefault(false)
            }
            if (field != null) return field
            current = current.superclass
        }
        return null
    }
}
