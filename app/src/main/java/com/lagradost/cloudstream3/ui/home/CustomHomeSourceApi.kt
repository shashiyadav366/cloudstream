package com.lagradost.cloudstream3.ui.home

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.setSourcePlugin
import com.lagradost.cloudstream3.ui.APIRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * A virtual provider representing one [CustomHomeSources.Source].
 *
 * Each declared main-page entry maps back to the category that produced it, so
 * the normal home loader, pagination and category filtering keep working. Items
 * keep the source plugin of the real provider, which is required for opening a
 * result with the correct extension. Categories hidden on their source plugin
 * and fully hidden plugins are skipped before any request is made.
 */
class CustomHomeSourceApi private constructor(
    private val source: CustomHomeSources.Source,
    private val entries: List<Pair<CustomHomeSources.CategoryRef, MainAPI>>,
) : MainAPI() {
    companion object {
        /**
         * The categories of [source] that resolve to a loadable provider right now,
         * applying the same blocking/hidden rules as [create]. Changes as plugins
         * finish loading, which is what makes a custom entry grow on a cold start.
         */
        fun resolveEntries(
            source: CustomHomeSources.Source,
            context: Context,
        ): List<Pair<CustomHomeSources.CategoryRef, MainAPI>> {
            return source.categories.mapNotNull { ref ->
                val api = CustomHomeSources.resolveApi(ref)
                    ?.takeUnless { CustomHomeSources.isBlockedProvider(it) }
                    ?: return@mapNotNull null
                if (HomeCategorySettings.isProviderFullyHidden(context, api)) {
                    return@mapNotNull null
                }
                if (
                    HomeCategorySettings.isHidden(
                        context,
                        HomeCategorySettings.providerKey(api),
                        ref.category
                    )
                ) {
                    return@mapNotNull null
                }
                ref to api
            }
        }

        fun create(source: CustomHomeSources.Source, context: Context): CustomHomeSourceApi? {
            val entries = resolveEntries(source, context)
            if (entries.isEmpty()) return null
            return CustomHomeSourceApi(source, entries)
        }

        /**
         * How many categories the entry would show if it were built right now.
         * Used to notice that the page on screen was built before every plugin
         * behind it had loaded.
         */
        fun resolvableCount(source: CustomHomeSources.Source, context: Context): Int =
            resolveEntries(source, context).size
    }

    override var name: String = source.name
    override val hasMainPage: Boolean = true
    override val supportedTypes = entries.flatMap { it.second.supportedTypes }.toSet()
    override var lang: String = entries.firstOrNull()?.second?.lang ?: "en"

    private val normalizedNames = entries.map {
        HomeCategorySettings.normalize(it.first.category)
    }

    /**
     * A merged provider mixes categories from several plugins, so every card has to
     * be traced back to the plugin that actually served it — only that plugin can
     * load its own item URLs. Populated when the categories are loaded.
     */
    private val pluginByUrl = ConcurrentHashMap<String, String>()

    /**
     * Only the categories the user actually picked are ever requested: declared
     * categories map to exactly one row request each. A category that is *not*
     * declared in the plugin metadata can only be found by loading that provider's
     * homepage, so that result is remembered per provider and page: several
     * categories from the same plugin cost one fetch instead of one each, and two
     * categories of the same plugin never fetch it at the same time.
     */
    private val providerPages = ConcurrentHashMap<String, List<HomePageResponse?>>()
    private val providerPageLocks = ConcurrentHashMap<String, Mutex>()

    private suspend fun providerHomepage(api: MainAPI, page: Int): List<HomePageResponse?> {
        val key = "${HomeCategorySettings.providerKey(api)}|$page"
        providerPages[key]?.let { return it }
        val lock = providerPageLocks.computeIfAbsent(key) { Mutex() }
        return lock.withLock {
            providerPages[key]?.let { return it }
            val pages = (APIRepository(api).getMainPage(page) as? Resource.Success)?.value
                ?: emptyList()
            providerPages[key] = pages
            pages
        }
    }

    override val mainPage: List<MainPageData> = entries.mapIndexed { index, (ref, _) ->
        // Keep section names short, but disambiguate identical category names
        // coming from different plugins.
        val duplicate = normalizedNames.count {
            it == HomeCategorySettings.normalize(ref.category)
        } > 1
        val label = if (duplicate) "${ref.category} · ${ref.providerName}" else ref.category
        MainPageData(name = label, data = index.toString(), horizontalImages = false)
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse? {
        val index = request.data.toIntOrNull() ?: return null
        val (ref, api) = entries.getOrNull(index) ?: return null

        val declared = api.mainPage.firstOrNull {
            it.name.isNotBlank() &&
                HomeCategorySettings.normalize(it.name) ==
                HomeCategorySettings.normalize(ref.category)
        }

        val response = if (declared != null) {
            api.getMainPage(
                page,
                MainPageRequest(declared.name, declared.data, declared.horizontalImages)
            )
        } else {
            // A category discovered on a dynamic homepage is not declared in
            // plugin metadata. That provider's homepage is loaded once and shared
            // by every dynamic category of the same plugin, then the matching row
            // is picked, so nothing is fetched twice.
            val row = providerHomepage(api, page)
                .asSequence()
                .filterNotNull()
                .flatMap { it.items.asSequence() }
                .firstOrNull {
                    HomeCategorySettings.normalize(it.name) ==
                        HomeCategorySettings.normalize(ref.category)
                }
            row?.let { newHomePageResponse(it, false) }
        }

        return response?.copy(
            items = response.items.map { homeList ->
                homeList.copy(
                    name = request.name,
                    list = homeList.list.map { item ->
                        api.sourcePlugin?.let { pluginByUrl[item.url] = it }
                        item.also { it.setSourcePlugin(api.sourcePlugin) }
                    }
                )
            }
        )
    }

    /**
     * Loads a card through the plugin that produced it, so the merged provider can
     * show a preview hero like any other provider. Falls back to matching the item
     * URL against the installed plugins for cards from an earlier session.
     */
    override suspend fun load(url: String): LoadResponse? {
        val api = pluginByUrl[url]
            ?.let { path -> APIHolder.apis.firstOrNull { it.sourcePlugin == path } }
            ?: APIHolder.getApiFromUrlNull(url)
            ?: return null
        return api.load(url)
    }
}
