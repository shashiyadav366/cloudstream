package com.lagradost.cloudstream3.ui.home

import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.setSourcePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shared PlayFy/Cricify schedule access for Home and background notifications. */
object LiveEventSchedule {
    /**
     * PlayFy is preferred; Cricify is the fallback.
     *
     * Matches the provider name loosely (plugin name + "live") instead of the exact display
     * name, so renaming a provider inside the plugin cannot silently drop the live row. The
     * extra "live" requirement keeps the plugin's other providers (FanCode, SonyLiv, ...)
     * from being picked as the live source.
     */
    fun findApis(): List<MainAPI> = apis.withLock {
        fun findLiveApi(marker: String) = apis.firstOrNull { api ->
            api.name.contains(marker, ignoreCase = true) &&
                    api.name.contains("live", ignoreCase = true)
        }

        listOfNotNull(findLiveApi("playfy"), findLiveApi("cricify"))
    }

    /**
     * Returns all cricket cards from a plugin's 2-3 day schedule. The Home row
     * filters this down to live/today, while notifications use upcoming cards too.
     */
    suspend fun fetchCards(api: MainAPI): List<SearchResponse>? {
        val page = withContext(Dispatchers.IO) {
            runCatching {
                api.getMainPage(1, MainPageRequest("", "", true))
            }.getOrNull()
        } ?: return null

        val cards = page.items
            .filter { it.name.contains("cricket", ignoreCase = true) }
            .flatMap { it.list }
            .distinctBy { it.url }

        cards.forEach { it.setSourcePlugin(api.sourcePlugin) }
        return cards
    }

    data class FetchedSchedule(
        val api: MainAPI,
        val cards: List<SearchResponse>,
    )

    /**
     * Returns PlayFy's cards when it has any. Cricify is called only if PlayFy is
     * unavailable or returns no cricket cards, so a normal refresh makes one call.
     */
    suspend fun fetchFirstAvailable(): FetchedSchedule? {
        var emptyFallback: FetchedSchedule? = null
        for (api in findApis()) {
            val cards = fetchCards(api) ?: continue
            val result = FetchedSchedule(api, cards)
            if (cards.isNotEmpty()) return result
            if (emptyFallback == null) emptyFallback = result
        }
        return emptyFallback
    }
}
