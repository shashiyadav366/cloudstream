package com.lagradost.cloudstream3.ui.playlists

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.serialization.Serializable
import java.io.File

/** One playlist entry, stored exactly the way the M3U player plugin stores it. */
@Serializable
data class M3uPlaylist(
    val name: String,
    val url: String,
)

/**
 * App-side editor for the playlists of the M3UPlaylistPlayerProvider plugin.
 *
 * The plugin keeps its list in its own `M3UPlaylistPlayerPrefs` preference file
 * under the `playlists` key and registers one provider per entry while it loads.
 * Writing the same JSON is therefore all this needs to do: every playlist added
 * here is picked up by the plugin automatically, and because the same JSON is
 * part of the backup file, restoring on another device brings the names and
 * URLs along instead of retyping them.
 */
object M3uPlaylistSettings {
    private const val PREFS_NAME = "M3UPlaylistPlayerPrefs"
    private const val PLAYLISTS_KEY = "playlists"
    private const val PLUGIN_CLASS_NAME = "M3UPlaylistPlayerPlugin"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Playlists in the order the plugin registers them. Never throws. */
    fun list(context: Context): List<M3uPlaylist> {
        val raw = prefs(context).getString(PLAYLISTS_KEY, null) ?: return emptyList()
        val entries = runCatching { parseJson<List<M3uPlaylist>>(raw) }
            .getOrDefault(emptyList())
        return entries.filter { it.name.isNotBlank() && it.url.isNotBlank() }
    }

    private fun save(context: Context, entries: List<M3uPlaylist>) {
        prefs(context).edit().putString(PLAYLISTS_KEY, entries.toJson()).apply()
    }

    /**
     * Appends a playlist, replacing an existing entry with the same URL so a
     * retry does not register the same provider twice.
     *
     * @return false when the name or the URL is blank.
     */
    fun add(context: Context, name: String, url: String): Boolean {
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()
        if (trimmedName.isBlank() || trimmedUrl.isBlank()) return false
        val current = list(context).filterNot { it.url == trimmedUrl }
        save(context, current + M3uPlaylist(trimmedName, trimmedUrl))
        return true
    }

    /** Removes the first entry that matches [playlist]. */
    fun remove(context: Context, playlist: M3uPlaylist) {
        val current = list(context)
        val index = current.indexOfFirst { it.name == playlist.name && it.url == playlist.url }
        if (index < 0) return
        save(context, current.toMutableList().apply { removeAt(index) })
    }

    /** Path of the loaded plugin, or null when it is not installed on this device. */
    fun pluginPath(): String? = PluginManager.plugins.entries
        .firstOrNull { it.value.javaClass.simpleName == PLUGIN_CLASS_NAME }
        ?.key

    fun isPluginInstalled(): Boolean = pluginPath() != null

    /**
     * Re-runs the plugin so providers of added and removed playlists register
     * right away instead of waiting for the next app start. Returns false when
     * the plugin is not installed or could not be reloaded.
     */
    suspend fun reload(context: Context): Boolean {
        val path = pluginPath() ?: return false
        val data = (
            PluginManager.getPluginsOnline().asList() +
                PluginManager.getPluginsLocal().asList()
            ).firstOrNull { it.filePath == path } ?: return false
        PluginManager.unloadPlugin(path)
        return PluginManager.loadPlugin(context.applicationContext, File(path), data)
    }

    /**
     * Every playlist for the backup file, installed plugin or not, so a restore
     * on a device that adds the plugin later still has them.
     */
    fun backup(context: Context): List<M3uPlaylist> = list(context)

    /** Replaces the stored playlists with the ones from a backup file. */
    fun restore(context: Context, entries: List<M3uPlaylist>) {
        save(context, entries.filter { it.name.isNotBlank() && it.url.isNotBlank() })
    }
}
