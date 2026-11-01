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
        /** How many of this source's categories can resolve right now from loaded plugins. */
        fun resolvableCount(source: CustomHomeSources.Source, ctx: Context): Int {
            return source.categories.count { ref ->
                CustomHomeSources.resolveApi(ref)?.let { api ->
                    !CustomHomeSources.isBlockedProvider(api) &&
                        !HomeCategorySettings.isProviderFullyHidden(ctx, api) &&
                        !HomeCategorySettings.isHidden(
                            ctx,
                            HomeCategorySettings.providerKey(api),
                            ref.category
                        )
                } == true
            }
        }

        fun create(source: CustomHomeSources.Source, context: Context): CustomHomeSourceApi? {
            val entries = source.categories.mapNotNull { ref ->
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
            if (entries.isEmpty()) return null
            return CustomHomeSourceApi(source, entries)
        }
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
            // plugin metadata. Load that homepage once and pick the matching
            // row, so it can still be merged into a custom provider.
            val result = APIRepository(api).getMainPage(page)
            val row = (result as? Resource.Success)
                ?.value
                ?.asSequence()
                ?.filterNotNull()
                ?.flatMap { it.items.asSequence() }
                ?.firstOrNull {
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
