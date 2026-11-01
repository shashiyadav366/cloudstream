package com.lagradost.cloudstream3.ui.home

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.MainAPI
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Stores which home-page categories ("shelves") a viewer hid, per provider.
 *
 * The provider key uses the plugin's stable `Extensions/` relative path when
 * available, so two plugins that expose a provider with the same display name
 * keep independent rules while the key stays portable between devices. Older
 * full-path keys are still matched.
 * Matching is done on a normalized name because provider shelf names are scraped
 * text and may change casing or pick up stray whitespace between loads.
 */
object HomeCategorySettings {
    private const val KEY_HIDDEN = "home_hidden_categories_v2"
    private const val KEY_KNOWN = "home_known_categories_v1"
    private const val PROVIDER_SEPARATOR = "\u001F"

    /**
     * Preference keys that must always survive a backup. They live in the normal
     * default-preferences file, which [com.lagradost.cloudstream3.utils.BackupUtils]
     * copies as its settings section; keeping the list explicit prevents future
     * backup filters from silently dropping category customisation.
     */
    val backupKeys: Set<String> = setOf(KEY_HIDDEN, KEY_KNOWN)

    private class RuleCache(
        val hidden: MutableMap<String, MutableList<String>>,
        val known: MutableMap<String, MutableList<String>>,
    )

    private val cacheLock = Any()

    @Volatile
    private var ruleCache: RuleCache? = null

    /** Drops the in-memory snapshot after a backup restore or external write. */
    fun invalidate() {
        synchronized(cacheLock) { ruleCache = null }
    }

    private fun getCache(context: Context): RuleCache {
        ruleCache?.let { return it }
        synchronized(cacheLock) {
            ruleCache?.let { return it }
            return RuleCache(
                readMap(context, KEY_HIDDEN),
                readMap(context, KEY_KNOWN),
            ).also { ruleCache = it }
        }
    }

    private fun mapFor(
        cache: RuleCache,
        key: String
    ): MutableMap<String, MutableList<String>> =
        if (key == KEY_HIDDEN) cache.hidden else cache.known

    fun providerKey(api: MainAPI): String =
        providerKey(api.name, api.sourcePlugin)

    fun providerKey(apiName: String?, sourcePlugin: String?): String {
        val name = apiName.orEmpty()
        val portableSource = portableSource(sourcePlugin)
        return if (portableSource == null) {
            name
        } else {
            "$name$PROVIDER_SEPARATOR$portableSource"
        }
    }

    fun normalize(name: String): String = name.trim().lowercase(Locale.ROOT)

    /**
     * Plugin paths can differ between devices and app installs (`/data/user/0/...`
     * vs another package directory). Online plugins live under `Extensions/`, so the
     * path after that marker is stable across installs and build flavours. That is
     * what travels in a backup. The full path is still accepted as a legacy key.
     */
    private fun portableSource(sourcePlugin: String?): String? {
        val source = sourcePlugin?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val marker = "Extensions/"
        val markerIndex = source.lastIndexOf(marker)
        return if (markerIndex >= 0) {
            source.substring(markerIndex + marker.length).takeIf { it.isNotBlank() }
        } else {
            source.substringAfterLast('/').substringAfterLast('\\').takeIf { it.isNotBlank() }
        }
    }

    private fun splitProviderKey(key: String): Pair<String, String?> {
        val separator = key.indexOf(PROVIDER_SEPARATOR)
        return if (separator < 0) {
            key to null
        } else {
            key.substring(0, separator) to key.substring(separator + 1)
        }
    }

    /**
     * Finds every key that belongs to the same provider. Old backups may contain
     * the full plugin path or only the provider name, so those are matched by the
     * stable provider name + plugin file name.
     */
    private fun matchingKeys(
        map: Map<String, List<String>>,
        providerKey: String
    ): List<String> {
        val (targetName, targetSource) = splitProviderKey(providerKey)
        val targetPortable = portableSource(targetSource)

        return map.keys.filter { candidate ->
            if (candidate == providerKey) return@filter true
            val (candidateName, candidateSource) = splitProviderKey(candidate)
            if (candidateName != targetName) return@filter false

            val candidatePortable = portableSource(candidateSource)
            when {
                targetPortable == null -> candidatePortable == null
                candidatePortable == null -> true // legacy name-only entry
                else -> candidatePortable == targetPortable
            }
        }
    }

    private fun mergedValues(
        map: Map<String, List<String>>,
        providerKey: String
    ): List<String> = matchingKeys(map, providerKey)
        .flatMap { map[it].orEmpty() }
        .distinctBy { normalize(it) }

    /**
     * Writes a provider's values under the current portable key and removes the
     * matching old keys, so a restored backup is consolidated on first change.
     */
    private fun writeProviderValues(
        context: Context,
        key: String,
        providerKey: String,
        values: Collection<String>
    ) {
        synchronized(cacheLock) {
            val currentCache = getCache(context)
            val map = mapFor(currentCache, key)
            matchingKeys(map, providerKey).forEach { map.remove(it) }
            val distinct = values.filter { it.isNotBlank() }.distinctBy { normalize(it) }
            if (distinct.isNotEmpty()) {
                map[providerKey] = distinct.toMutableList()
            }
            writeMap(context, key, map)
        }
    }

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context)

    private fun readMap(context: Context, key: String): MutableMap<String, MutableList<String>> {
        val raw = prefs(context).getString(key, null) ?: return mutableMapOf()
        return runCatching {
            val root = JSONObject(raw)
            val map = mutableMapOf<String, MutableList<String>>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val provider = keys.next()
                val array = root.optJSONArray(provider) ?: continue
                val values = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    val value = array.optString(i, "")
                    if (value.isNotBlank()) values += value
                }
                if (values.isNotEmpty()) map[provider] = values
            }
            map
        }.getOrElse { mutableMapOf() }
    }

    private fun writeMap(
        context: Context,
        key: String,
        map: Map<String, List<String>>
    ) {
        val root = JSONObject()
        map.forEach { (provider, values) ->
            if (values.isEmpty()) return@forEach
            val array = JSONArray()
            values.forEach { array.put(it) }
            root.put(provider, array)
        }
        prefs(context).edit().putString(key, root.toString()).apply()
    }

    /** Hidden category names exactly as they were hidden (used by restore dialogs). */
    fun hiddenFor(context: Context, providerKey: String): List<String> =
        mergedValues(getCache(context).hidden, providerKey)

    fun isHidden(context: Context, providerKey: String, categoryName: String): Boolean {
        val normalized = normalize(categoryName)
        return hiddenFor(context, providerKey).any { normalize(it) == normalized }
    }

    fun isHidden(hidden: Collection<String>, categoryName: String): Boolean {
        val normalized = normalize(categoryName)
        return hidden.any { normalize(it) == normalized }
    }

    fun setHidden(
        context: Context,
        providerKey: String,
        categoryName: String,
        hidden: Boolean
    ) {
        if (providerKey.isBlank() || categoryName.isBlank()) return
        val current = mergedValues(getCache(context).hidden, providerKey).toMutableList()
        val normalized = normalize(categoryName)
        current.removeAll { normalize(it) == normalized }
        if (hidden) current += categoryName.trim()
        writeProviderValues(context, KEY_HIDDEN, providerKey, current)
    }

    fun setAllHidden(
        context: Context,
        providerKey: String,
        categoryNames: Collection<String>,
        hidden: Boolean
    ) {
        if (providerKey.isBlank()) return
        val current = mergedValues(getCache(context).hidden, providerKey).toMutableList()
        val normalized = categoryNames.map { normalize(it) }.toSet()
        current.removeAll { normalize(it) in normalized }
        if (hidden) {
            categoryNames.filter { it.isNotBlank() }
                .distinctBy { normalize(it) }
                .forEach { current += it.trim() }
        }
        writeProviderValues(context, KEY_HIDDEN, providerKey, current)
    }

    fun clearHidden(context: Context) {
        synchronized(cacheLock) {
            val cache = getCache(context)
            cache.hidden.clear()
            writeMap(context, KEY_HIDDEN, cache.hidden)
        }
    }

    /**
     * Remembers every category name a provider actually served, so providers
     * that build their categories dynamically still show up in the manager.
     */
    fun rememberCategories(
        context: Context,
        providerKey: String,
        categoryNames: Collection<String>
    ) {
        if (providerKey.isBlank()) return
        val incoming = categoryNames.filter { it.isNotBlank() }
        if (incoming.isEmpty()) return
        val current = mergedValues(getCache(context).known, providerKey).toMutableList()
        val existing = current.map { normalize(it) }.toSet()
        val additions = incoming.filter { normalize(it) !in existing }
        if (additions.isEmpty()) return
        current += additions
        writeProviderValues(context, KEY_KNOWN, providerKey, current)
    }

    fun knownFor(context: Context, providerKey: String): List<String> =
        mergedValues(getCache(context).known, providerKey)

    /**
     * True when every category this provider can show on home is hidden. Such a
     * provider is left out of the home selector so clubbed/umbrella plugins don't
     * leave the original plugins visible as individual choices.
     */
    fun isProviderFullyHidden(context: Context, api: MainAPI): Boolean {
        val providerKey = providerKey(api)
        val categories = (
            api.mainPage.map { it.name }.filter { it.isNotBlank() } +
                knownFor(context, providerKey)
            ).distinctBy { normalize(it) }
        if (categories.isEmpty()) return false
        val hidden = hiddenFor(context, providerKey)
            .mapTo(HashSet()) { normalize(it) }
        return categories.all { normalize(it) in hidden }
    }

    /**
     * Rewrites category rules restored from an older device/backup so they use the
     * portable provider key (provider name + stable plugin path). Without this, a
     * restore could keep hidden categories under another device's full plugin path
     * and they would never match the freshly installed plugin.
     */
    fun migrateProviderKeys(context: Context) {
        // A restore wrote the preferences directly, so the cached snapshot is stale.
        invalidate()
        migrateMap(context, KEY_HIDDEN)
        migrateMap(context, KEY_KNOWN)
        invalidate()
    }

    private fun migrateMap(context: Context, key: String) {
        val map = readMap(context, key)
        if (map.isEmpty()) return

        val migrated = mutableMapOf<String, MutableList<String>>()
        map.forEach { (storedKey, values) ->
            val (name, source) = splitProviderKey(storedKey)
            val portableKey = providerKey(name, source)
            val destination = migrated.getOrPut(portableKey) { mutableListOf() }
            values.forEach { value ->
                if (destination.none { normalize(it) == normalize(value) }) {
                    destination += value
                }
            }
        }
        writeMap(context, key, migrated)
    }
}
