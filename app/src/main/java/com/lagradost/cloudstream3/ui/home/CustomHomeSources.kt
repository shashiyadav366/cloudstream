package com.lagradost.cloudstream3.ui.home

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.PluginManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * User-created homepage entries that merge categories from several plugins.
 *
 * The entries keep a portable [HomeCategorySettings.providerKey] instead of a
 * raw plugin path, so a backup restored on another device still resolves the
 * same providers.
 */
object CustomHomeSources {
    private const val KEY_SOURCES = "home_custom_sources_v1"
    private const val PROVIDER_SEPARATOR = "\u001F"

    /** Preferences that must survive a backup so custom home entries do too. */
    val backupKeys: Set<String> = setOf(KEY_SOURCES)

    data class CategoryRef(
        val providerKey: String,
        val providerName: String,
        val category: String,
    ) {
        fun stableKey(): String =
            "$providerKey$PROVIDER_SEPARATOR${HomeCategorySettings.normalize(category)}"
    }

    data class Source(
        val id: String,
        val name: String,
        val categories: List<CategoryRef>,
    )

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun all(context: Context): List<Source> {
        val raw = prefs(context).getString(KEY_SOURCES, null) ?: return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray("sources") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id", "").takeIf { it.isNotBlank() } ?: continue
                    val name = item.optString("name", "").trim().takeIf { it.isNotBlank() }
                        ?: continue
                    val categoryArray = item.optJSONArray("categories") ?: JSONArray()
                    val categories = buildList {
                        for (c in 0 until categoryArray.length()) {
                            val category = categoryArray.optJSONObject(c) ?: continue
                            val providerKey = category.optString("providerKey", "")
                                .takeIf { it.isNotBlank() } ?: continue
                            val categoryName = category.optString("category", "").trim()
                                .takeIf { it.isNotBlank() } ?: continue
                            add(
                                CategoryRef(
                                    providerKey = providerKey,
                                    providerName = category.optString(
                                        "providerName",
                                        providerKey.substringBefore(PROVIDER_SEPARATOR)
                                    ),
                                    category = categoryName,
                                )
                            )
                        }
                    }.distinctBy { it.stableKey() }
                    add(Source(id, name, categories))
                }
            }
        }.getOrElse { emptyList() }
    }

    fun findByName(context: Context, name: String?): Source? {
        val wanted = name?.trim().takeIf { it?.isNotBlank() == true } ?: return null
        val sources = all(context)
        return sources.firstOrNull { it.name == wanted }
            ?: sources.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
    }

    fun isNameUsed(context: Context, name: String, exceptId: String? = null): Boolean =
        all(context).any {
            it.id != exceptId && it.name.equals(name.trim(), ignoreCase = true)
        }

    fun create(
        context: Context,
        name: String,
        categories: List<CategoryRef> = emptyList(),
    ): Source? {
        val cleanName = name.trim()
        if (cleanName.isBlank() || isNameUsed(context, cleanName)) return null
        val source = Source(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            categories = categories.distinctBy { it.stableKey() },
        )
        write(context, all(context) + source)
        return source
    }

    fun update(context: Context, source: Source): Boolean {
        val cleanName = source.name.trim()
        if (cleanName.isBlank() || isNameUsed(context, cleanName, source.id)) return false
        val sources = all(context).toMutableList()
        val index = sources.indexOfFirst { it.id == source.id }
        if (index < 0) return false
        sources[index] = source.copy(
            name = cleanName,
            categories = source.categories.distinctBy { it.stableKey() },
        )
        write(context, sources)
        return true
    }

    fun delete(context: Context, id: String) {
        write(context, all(context).filterNot { it.id == id })
    }

    fun resolveApi(ref: CategoryRef): MainAPI? =
        APIHolder.apis.firstOrNull {
            HomeCategorySettings.providerKey(it) == ref.providerKey
        }

    /**
     * Blocked plugins are uninstalled, but a stale provider can briefly survive a
     * restore. Hide those providers from every customization list and selector.
     */
    fun isBlockedProvider(api: MainAPI): Boolean {
        val path = api.sourcePlugin ?: return false
        val data = (PluginManager.getPluginsOnline() + PluginManager.getPluginsLocal())
            .firstOrNull { it.filePath == path } ?: return false
        return PluginManager.isPluginIgnored(data.internalName, data.url)
    }

    /** Stored categories whose plugin is currently installed and not blocked. */
    fun activeCategories(source: Source): List<CategoryRef> =
        source.categories.filter { ref ->
            resolveApi(ref)?.let { !isBlockedProvider(it) } == true
        }

    private fun write(context: Context, sources: List<Source>) {
        val root = JSONObject()
        val array = JSONArray()
        sources.forEach { source ->
            val item = JSONObject()
            item.put("id", source.id)
            item.put("name", source.name)
            val categories = JSONArray()
            source.categories.forEach { category ->
                val entry = JSONObject()
                entry.put("providerKey", category.providerKey)
                entry.put("providerName", category.providerName)
                entry.put("category", category.category)
                categories.put(entry)
            }
            item.put("categories", categories)
            array.put(item)
        }
        root.put("sources", array)
        prefs(context).edit().putString(KEY_SOURCES, root.toString()).apply()
    }
}
