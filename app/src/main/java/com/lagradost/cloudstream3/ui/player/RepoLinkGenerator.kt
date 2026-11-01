package com.lagradost.cloudstream3.ui.player

import android.util.Log
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.APIHolder.getApiFromSourceAndNameNull
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.isLiveStream
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class Cache(
    val linkCache: MutableSet<ExtractorLink>,
    val subtitleCache: MutableSet<SubtitleData>,
    /** When it was last updated */
    var lastCachedTimestamp: Long = unixTime,
    /** If it has fully loaded */
    var saturated: Boolean,
)

class RepoLinkGenerator(
    episodes: List<ResultEpisode>,
    val page: LoadResponse? = null,
) : VideoGenerator<ResultEpisode>(episodes) {
    companion object {
        const val TAG = "RepoLink"
        val cache: HashMap<Pair<String, Int>, Cache> =
            hashMapOf()

        /**
         * Builds a generator for a Continue Watching entry that already carries the plugin's
         * playable data, so playback can start without loading the provider page first.
         *
         * Returns null when the entry cannot be played this way (no data, no parent id, or
         * an unknown plugin), so callers fall back to the normal result page.
         */
        fun fromResume(
            episodeData: String,
            apiName: String,
            sourcePlugin: String?,
            id: Int?,
            parentId: Int?,
            episode: Int?,
            season: Int?,
            tvType: TvType,
            name: String?,
            poster: String?,
        ): RepoLinkGenerator? {
            if (episodeData.isBlank() || parentId == null) return null
            if (getApiFromSourceAndNameNull(apiName, sourcePlugin) == null &&
                getApiFromNameNull(apiName) == null
            ) {
                return null
            }

            val entry = ResultEpisode(
                headerName = name ?: apiName,
                name = name,
                poster = poster,
                episode = episode ?: 0,
                seasonIndex = null,
                season = season,
                data = episodeData,
                apiName = apiName,
                // Live entries have no episode id; -1 keeps resume lookups empty.
                id = id ?: -1,
                index = 0,
                position = 0L,
                duration = 0L,
                score = null,
                description = null,
                isFiller = null,
                tvType = tvType,
                parentId = parentId,
                videoWatchState = VideoWatchState.None,
                sourcePlugin = sourcePlugin,
            )

            return RepoLinkGenerator(listOf(entry), page = null)
        }
    }

    override val hasCache = true
    override val canSkipLoading = true
    override fun getId(index: Int): Int? = videos.getOrNull(index)?.id

    // this is a simple array that is used to instantly load links if they are already loaded
    //var linkCache = Array<Set<ExtractorLink>>(size = episodes.size, init = { setOf() })
    //var subsCache = Array<Set<SubtitleData>>(size = episodes.size, init = { setOf() })

    @Throws
    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean,
    ): Boolean {
        val current = videos.getOrNull(offset) ?: return false

        // Two plugins can expose the same provider name and result id; include the
        // plugin so their link caches never leak into each other.
        val cacheKey = "${current.apiName}|${current.sourcePlugin}" to current.id
        val currentCache = synchronized(cache) {
            cache[cacheKey] ?: Cache(
                mutableSetOf(),
                mutableSetOf(),
                unixTime,
                false
            ).also {
                cache[cacheKey] = it
            }
        }

        // These act as a general filter to prevent duplication of links or names
        // Avoid any possible ConcurrentModificationException
        val currentLinksUrls = ConcurrentHashMap.newKeySet<String>()
        val currentSubsUrls = ConcurrentHashMap.newKeySet<String>()
        // Use atomics as otherwise we get race conditions when incrementing, while rare it did actually happen!
        val lastCountedSuffix = ConcurrentHashMap<String, AtomicInteger>()

        synchronized(currentCache) {
            // Live links are short-lived and can point at a session that has already moved
            // on. Continue Watching reuses the same episode id, so a cached link from an
            // earlier play can otherwise be handed to the player even though the provider
            // would return a fresh working URL. Always resolve live links again, just like
            // opening the card from its provider; static media keeps the 20-minute cache.
            // The page type is checked as well: some live-event providers do not tag every
            // episode with TvType.Live, and their links expire just the same.
            val isLive = current.tvType.isLiveStream() || page?.type?.isLiveStream() == true
            val outdatedCache = isLive ||
                unixTime - currentCache.lastCachedTimestamp > 60 * 20 // 20 minutes

            if (outdatedCache || clearCache) {
                currentCache.linkCache.clear()
                currentCache.subtitleCache.clear()
                currentCache.saturated = false
            } else if (currentCache.linkCache.isNotEmpty()) {
                Log.d(
                    TAG,
                    "Resumed previous loading from ${unixTime - currentCache.lastCachedTimestamp}s ago"
                )
            }

            // call all callbacks
            currentCache.linkCache.forEach { link ->
                currentLinksUrls.add(link.url)
                if (sourceTypes.contains(link.type)) {
                    callback(link to null)
                }
            }

            currentCache.subtitleCache.forEach { sub ->
                currentSubsUrls.add(sub.url)
                lastCountedSuffix.getOrPut(sub.originalName) { AtomicInteger(0) }.incrementAndGet()
                subtitleCallback(sub)
            }

            // this stops all execution if links are cached
            // no extra get requests
            if (currentCache.saturated) {
                return true
            }
        }

        val result = APIRepository(
            getApiFromSourceAndNameNull(current.apiName, current.sourcePlugin)
                ?: getApiFromNameNull(current.apiName)
                ?: throw Exception("This provider does not exist")
        ).loadLinks(
            current.data,
            isCasting = isCasting,
            subtitleCallback = { file ->
                Log.d(TAG, "Loaded SubtitleFile lang=${file.lang}")
                val correctFile = PlayerSubtitleHelper.getSubtitleData(file)
                if (correctFile.url.isBlank() || !currentSubsUrls.add(correctFile.url)) {
                    return@loadLinks
                }

                // this part makes sure that all names are unique for UX
                val nameDecoded = correctFile.originalName.html().toString()
                    .trim() // `%3Ch1%3Esub%20name…` → `<h1>sub name…` → `sub name…`
                val suffixCount =
                    lastCountedSuffix.getOrPut(nameDecoded) { AtomicInteger(0) }.incrementAndGet()

                val updatedFile =
                    correctFile.copy(originalName = nameDecoded, nameSuffix = "$suffixCount")

                synchronized(currentCache) {
                    if (currentCache.subtitleCache.add(updatedFile)) {
                        subtitleCallback(updatedFile)
                        currentCache.lastCachedTimestamp = unixTime
                    }
                }
            },
            callback = { link ->
                Log.d(TAG, "Loaded ExtractorLink name=${link.name} target=${StreamRequestContext.safeTarget(link.url)}")
                if (link.url.isBlank() || !currentLinksUrls.add(link.url)) {
                    return@loadLinks
                }

                synchronized(currentCache) {
                    if (currentCache.linkCache.add(link)) {
                        if (sourceTypes.contains(link.type)) {
                            callback(Pair(link, null))
                        }

                        currentCache.linkCache.add(link)
                        currentCache.lastCachedTimestamp = unixTime
                    }
                }
            }
        )

        synchronized(currentCache) {
            currentCache.saturated = currentCache.linkCache.isNotEmpty()
            currentCache.lastCachedTimestamp = unixTime
        }

        return result
    }
}