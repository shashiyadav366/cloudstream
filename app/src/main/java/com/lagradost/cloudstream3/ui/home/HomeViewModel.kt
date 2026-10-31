package com.lagradost.cloudstream3.ui.home

import android.content.SharedPreferences
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.setSourcePlugin
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.isLiveStream
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.services.LiveEventScheduleWorker
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi
import com.lagradost.cloudstream3.ui.APIRepository.Companion.randomApi
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.ui.result.ResultViewModel2
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_FOCUSED
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.addProgramsToContinueWatching
import com.lagradost.cloudstream3.utils.AppContextUtils.filterHomePageListByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.AppContextUtils.filterSearchResultByFilmQuality
import com.lagradost.cloudstream3.utils.AppContextUtils.loadResult
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE_BACKUP
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.getAllWatchStateIds
import com.lagradost.cloudstream3.utils.DataStoreHelper.getBookmarkedData
import com.lagradost.cloudstream3.utils.DataStoreHelper.getCurrentAccount
import com.lagradost.cloudstream3.utils.DataStoreHelper.getResultWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList

class HomeViewModel : ViewModel() {
    companion object {
        private const val CRICKET_LIVE_CACHE_MS = 2 * 60 * 1000L
        private const val CRICKET_LIVE_PREF_KEY = LIVE_CRICKET_EVENTS_PREF_KEY
        // Plugin lists and the first live-event request can still be warming up when the
        // home screen is created. Retry briefly instead of caching an empty result, which
        // used to require reopening home before live cards appeared. The delay backs off
        // (750 ms, 1.5 s, 3 s, then every 6 s) so a failing request is not retried ~20 times
        // in 15 seconds while the user is looking at the home screen.
        private const val CRICKET_LIVE_RETRY_MS = 750L
        private const val CRICKET_LIVE_MAX_RETRIES = 10
        // Let plugin batches settle, then rebuild a custom home entry once so every
        // category shows without a manual refresh.
        private const val CUSTOM_HOME_REFRESH_DELAY_MS = 1500L

        suspend fun getResumeWatching(): List<DataStoreHelper.ResumeWatchingResult>? {
            val resumeWatching = withContext(Dispatchers.IO) {
                DataStoreHelper.getAllResumeStates()?.sortedBy { -it.updateTime }
            }
            val resumeWatchingResult =
                withContext<List<DataStoreHelper.ResumeWatchingResult>?>(Dispatchers.IO) {
                val entries = resumeWatching?.mapNotNull { resume ->
                    // Each plugin keeps its own cached header so two plugins that play the
                    // same movie resolve to their own card instead of the last one loaded.
                    val headerKey = DataStoreHelper.headerCacheKey(
                        resume.parentId,
                        resume.sourcePlugin
                    )
                    val headerCache = getKey<DownloadObjects.DownloadHeaderCached>(
                        DOWNLOAD_HEADER_CACHE,
                        headerKey
                    )

                    val data = if (headerCache == null) {
                        // We store resume watching data in download header cache
                        // Because downloads automatically pruned outdated download headers we
                        // removed resume watching data. We should restore the data for affected users.
                        val oldData = getKey<DownloadObjects.DownloadHeaderCached>(
                            DOWNLOAD_HEADER_CACHE_BACKUP,
                            headerKey
                        ) ?: getKey<DownloadObjects.DownloadHeaderCached>(
                            // Entries saved before per-plugin headers existed live under the
                            // plain id.
                            DOWNLOAD_HEADER_CACHE_BACKUP,
                            resume.parentId.toString()
                        ) ?: return@mapNotNull null

                        // Restore data
                        setKey(DOWNLOAD_HEADER_CACHE, headerKey, oldData)
                        oldData
                    } else {
                        headerCache
                    }
                    resume to data
                } ?: return@withContext null

                entries.map { (resume, data) ->
                    val watchPos = getViewPos(resume.episodeId, resume.sourcePlugin)

                    DataStoreHelper.ResumeWatchingResult(
                        data.name,
                        data.url,
                        data.apiName,
                        data.type,
                        data.poster,
                        watchPos,
                        resume.episodeId,
                        resume.parentId,
                        resume.episode,
                        resume.season,
                        resume.isFromDownload,
                        updateTime = resume.updateTime,
                        episodeData = resume.episodeData,
                    ).apply {
                        sourcePlugin = resume.sourcePlugin
                    }
                }
            }
            // Collapse duplicates: the same movie/show can end up saved under multiple ids
            // (e.g. provider id changes). Entries of different plugins are never merged:
            // each plugin's play stays its own card, so it never redirects to another one.
            val scopedParentIds = resumeWatchingResult
                ?.filter { it.sourcePlugin != null }
                ?.mapNotNull { it.parentId }
                ?.toSet()
                .orEmpty()
            return resumeWatchingResult
                // Once a title has a per-plugin card, hide the legacy shared card for the
                // same title so it does not linger as a duplicate.
                ?.filterNot { it.sourcePlugin == null && it.parentId in scopedParentIds }
                ?.distinctBy {
                    "${it.sourcePlugin.orEmpty()}|" +
                        it.url.ifBlank { "${it.apiName}|${it.name}" }
                }
        }
    }

    fun deleteResumeWatching() {
        DataStoreHelper.deleteAllWatchHistory()
        loadResumeWatching()
    }

    fun deleteBookmarks(list: List<SearchResponse>) {
        list.forEach { DataStoreHelper.deleteBookmarkedData(it.id) }
        loadStoredData()
    }

    var repo: APIRepository? = null

    private val _apiName = MutableLiveData<String>()
    val apiName: LiveData<String> = _apiName

    private val _currentAccount = MutableLiveData<DataStoreHelper.Account?>()
    val currentAccount: MutableLiveData<DataStoreHelper.Account?> = _currentAccount

    private val _randomItems = MutableLiveData<List<SearchResponse>?>(null)
    val randomItems: LiveData<List<SearchResponse>?> = _randomItems

    private var currentShuffledList: List<SearchResponse> = listOf()

    private fun autoloadRepo(): APIRepository {
        return APIRepository(apis.withLock { apis.first { it.hasMainPage } })
    }

    private val _availableWatchStatusTypes =
        MutableLiveData<Pair<Set<WatchType>, Set<WatchType>>>()
    val availableWatchStatusTypes: LiveData<Pair<Set<WatchType>, Set<WatchType>>> =
        _availableWatchStatusTypes
    private val _bookmarks = MutableLiveData<Pair<Boolean, List<SearchResponse>>>()
    val bookmarks: LiveData<Pair<Boolean, List<SearchResponse>>> = _bookmarks

    private val _resumeWatching = MutableLiveData<List<SearchResponse>>()
    private val _preview = MutableLiveData<Resource<Pair<Boolean, List<LoadResponse>>>>()
    private val previewResponses = CopyOnWriteArrayList<LoadResponse>()
    private val previewResponsesAdded = mutableSetOf<String>()

    val resumeWatching: LiveData<List<SearchResponse>> = _resumeWatching
    val preview: LiveData<Resource<Pair<Boolean, List<LoadResponse>>>> = _preview

    private val _cricketLive = MutableLiveData<List<SearchResponse>>(emptyList())
    val cricketLive: LiveData<List<SearchResponse>> = _cricketLive
    private var cricketLiveJob: Job? = null

    @Volatile
    private var cricketLiveCachedAt = 0L

    /** The row is opt-in, so no live-event requests are made while it is off. */
    private fun isCricketLiveEnabled(): Boolean {
        val ctx = context ?: return false
        return LiveEventNotifier.isEnabled(ctx)
    }

    private fun clearCricketLive() {
        cricketLiveJob?.cancel()
        cricketLiveCachedAt = 0L
        context?.let {
            LiveEventNotifier.cancelScheduledEvents(it)
            LiveEventScheduleWorker.cancelPeriodicWork(it)
        }
        if (_cricketLive.value?.isNotEmpty() == true) {
            _cricketLive.postValue(emptyList())
        }
    }

    private val cricketLivePreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == CRICKET_LIVE_PREF_KEY) {
                if (isCricketLiveEnabled()) {
                    loadCricketLive(force = true)
                } else {
                    clearCricketLive()
                }
            }
        }

    /**
     * Fills the Cricket Live header row with the plugin's cricket cards.
     * PlayFy is used when available, otherwise Cricify. Independent of the selected
     * home provider.
     */
    fun loadCricketLive(force: Boolean = false) {
        if (!isCricketLiveEnabled()) {
            clearCricketLive()
            return
        }

        if (!force) {
            if (System.currentTimeMillis() - cricketLiveCachedAt < CRICKET_LIVE_CACHE_MS) return
            if (cricketLiveJob?.isActive == true) return
        } else {
            cricketLiveJob?.cancel()
        }

        cricketLiveJob = viewModelScope.launchSafe {
            var attempts = 0
            while (true) {
                // PlayFy is tried first. Cricify is only called when PlayFy has no
                // cards, so the normal case makes one API call for the row and slider.
                val fetched = LiveEventSchedule.fetchFirstAvailable()

                // Extensions are typically loaded shortly after the home screen starts.
                // Keep retrying for a few seconds so the first visit gets live cards
                // without the user having to leave and reopen home.
                if (fetched == null) {
                    if (attempts++ >= CRICKET_LIVE_MAX_RETRIES) {
                        cricketLiveCachedAt = 0L
                        return@launchSafe
                    }
                    delay(CRICKET_LIVE_RETRY_MS shl minOf(attempts - 1, 3))
                    if (!isCricketLiveEnabled()) return@launchSafe
                    continue
                }

                // Date parsing for every card, alarm scheduling and work enqueueing used to
                // run on the main thread while the row appeared. Keep it off the UI thread.
                val cards = withContext(Dispatchers.IO) {
                    fetched.cards.filter { LiveEventNotifier.isLiveOrToday(it) }
                }
                // Remember exactly what the Cricket Live row is serving. If a match is
                // removed or ends, the next fetch can also drop it from Continue Watching.
                rememberCards(fetched.api, cards)

                if (!isCricketLiveEnabled()) return@launchSafe

                cricketLiveCachedAt = System.currentTimeMillis()
                _cricketLive.postValue(cards)
                cleanupLiveCards(listOf(fetched.api to cards))
                context?.let { ctx ->
                    withContext(Dispatchers.IO) {
                        LiveEventNotifier.scheduleUpcomingEvents(ctx, fetched.cards)
                    }
                }
                return@launchSafe
            }
        }
    }

    /**
     * The original cards last loaded for each plugin's first home page. Live stream
     * Continue Watching entries are checked against this instead of asking every plugin
     * again: if the plugin no longer returns the card, the original card is gone and the
     * saved entry can be dropped without a single extra request.
     */
    private data class LoadedHomeCards(
        val urls: Set<String>,
        val ids: Set<Int>,
        val names: Set<String>,
        val hasLiveCards: Boolean,
        /** URL each card is currently served under, keyed by stable card id. */
        val urlById: Map<Int, String> = emptyMap(),
        /** Same as [urlById], for cards whose id the provider changed. */
        val urlByName: Map<String, String> = emptyMap(),
    )

    private var loadedHomeCards: Map<String, LoadedHomeCards> = emptyMap()

    private fun homeCardsKey(api: MainAPI): String =
        "${api.sourcePlugin.orEmpty()}|${api.name}"

    /** Card titles are not unique across providers, so compare them case/space-insensitively. */
    private fun normalizeCardName(name: String): String =
        name.trim().lowercase().replace(Regex("\\s+"), " ")

    /**
     * Whether a saved entry is still one of the cards the plugin is currently serving.
     * Matching only by URL is not enough: live providers embed tokens, timestamps and
     * live scores in the card URL, so a still-running stream can get a different URL
     * between two loads. The id and the title stay stable, so they are checked too.
     */
    private fun LoadedHomeCards.contains(
        entry: DataStoreHelper.ResumeWatchingResult
    ): Boolean {
        if (entry.url.isNotBlank() && entry.url in urls) return true
        if (entry.parentId != null && entry.parentId in ids) return true
        return entry.name.isNotBlank() && normalizeCardName(entry.name) in names
    }

    /**
     * A multi-provider extension (Cricify exports SonyLiv and others as their own
     * providers) shares one extension file with its siblings. Matching by file alone
     * would check a SonyLiv card against Cricify's home page, which does not list it,
     * so the provider name has to match as well.
     */
    private fun DataStoreHelper.ResumeWatchingResult.belongsTo(api: MainAPI): Boolean {
        val sameExtension = sourcePlugin == null || sourcePlugin == api.sourcePlugin
        return sameExtension && apiName == api.name
    }

    private fun toLoadedHomeCards(cards: List<SearchResponse>): LoadedHomeCards {
        val withUrl = cards.filter { it.url.isNotBlank() }
        return LoadedHomeCards(
            urls = withUrl.map { it.url }.toSet(),
            ids = cards.mapNotNull { it.id }.toSet(),
            names = cards.mapNotNull {
                it.name.takeIf(String::isNotBlank)?.let(::normalizeCardName)
            }.toSet(),
            hasLiveCards = cards.any { it.type?.isLiveStream() == true },
            urlById = withUrl.mapNotNull { card -> card.id?.let { it to card.url } }.toMap(),
            urlByName = withUrl.mapNotNull { card ->
                card.name.takeIf(String::isNotBlank)?.let { normalizeCardName(it) to card.url }
            }.toMap(),
        )
    }

    /** The cards of the plugin a saved entry came from, when that plugin was loaded. */
    private fun cardsFor(
        entry: DataStoreHelper.ResumeWatchingResult
    ): List<LoadedHomeCards> = loadedHomeCards.entries
        .filter { (key, _) ->
            key.substringAfterLast('|') == entry.apiName &&
                (entry.sourcePlugin == null ||
                    key.substringBeforeLast('|') == entry.sourcePlugin)
        }
        .map { it.value }

    /**
     * Live providers put the session or event id in the card URL, so the same still
     * running event can be served under a new URL between two loads. A Continue
     * Watching entry keeps the URL it was opened with, which then points at an event
     * page that no longer resolves any stream. The id and title stay stable, so they
     * are used to find the URL the provider is serving right now.
     */
    private fun freshLiveCardUrl(
        entry: DataStoreHelper.ResumeWatchingResult
    ): String? {
        // Only live entries are moved: a static title keeps its saved URL.
        if (entry.type?.isLiveStream() != true) return null
        val cards = cardsFor(entry)
        if (cards.isEmpty()) return null

        val freshUrl = entry.parentId?.let { id ->
            cards.firstNotNullOfOrNull { it.urlById[id] }
        } ?: entry.name.takeIf(String::isNotBlank)?.let { name ->
            val normalized = normalizeCardName(name)
            cards.firstNotNullOfOrNull { it.urlByName[normalized] }
        }

        return freshUrl?.takeIf { it.isNotBlank() }
    }

    /**
     * Moves live Continue Watching entries to the URL their provider serves them
     * under now, and persists it in the cached header so opening the card later
     * loads the current event page instead of an expired one.
     */
    private fun refreshLiveCardUrls(
        entries: List<DataStoreHelper.ResumeWatchingResult>
    ): List<DataStoreHelper.ResumeWatchingResult> {
        var changed = false
        val refreshed = entries.map { entry ->
            val freshUrl = freshLiveCardUrl(entry) ?: return@map entry
            val headerKey = DataStoreHelper.headerCacheKey(entry.parentId, entry.sourcePlugin)
            getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE, headerKey)
                ?.let { header ->
                    setKey(DOWNLOAD_HEADER_CACHE, headerKey, header.copy(url = freshUrl))
                }
            changed = true
            // sourcePlugin is a transient, non-constructor property, so copy drops it.
            entry.copy(url = freshUrl).apply { sourcePlugin = entry.sourcePlugin }
        }
        return if (changed) refreshed else entries
    }

    @Synchronized
    private fun rememberCards(api: MainAPI, cards: List<SearchResponse>) {
        loadedHomeCards = loadedHomeCards + (homeCardsKey(api) to toLoadedHomeCards(cards))
    }

    private fun rememberHomeCards(api: MainAPI, data: List<HomePageResponse?>) {
        rememberCards(api, data.flatMap { it?.items.orEmpty() }.flatMap { it.list })
    }

    /**
     * Drops live stream entries that the current plugin is no longer serving. Only the
     * provider that just loaded its original cards is checked, and only when those cards
     * actually contain live streams, so a failed or non-live home page never removes
     * anything. The check is immediate: an entry not served by its own provider anymore
     * is removed as soon as that provider's home page loads.
     */
    private suspend fun applyLiveCardCleanup(
        entries: List<DataStoreHelper.ResumeWatchingResult>,
        api: MainAPI? = repo?.api,
        cardsOverride: List<SearchResponse>? = null,
        requireLiveCards: Boolean = true,
    ): List<DataStoreHelper.ResumeWatchingResult> {
        val currentApi = api ?: return entries
        val cards = cardsOverride?.let { toLoadedHomeCards(it) }
            ?: loadedHomeCards[homeCardsKey(currentApi)]
            ?: return entries
        if (requireLiveCards && !cards.hasLiveCards) return entries

        val goneEntries = entries.filter { entry ->
            entry.type?.isLiveStream() == true &&
                entry.belongsTo(currentApi) &&
                !cards.contains(entry)
        }
        if (goneEntries.isEmpty()) return entries

        withContext(Dispatchers.IO) {
            goneEntries.forEach { entry ->
                DataStoreHelper.removeResumeWatching(
                    entry.parentId,
                    entry.id,
                    entry.url,
                    entry.sourcePlugin
                )
            }
        }

        val goneKeys = goneEntries.map {
            Triple(it.parentId, it.sourcePlugin, it.url)
        }.toSet()
        return entries.filterNot {
            Triple(it.parentId, it.sourcePlugin, it.url) in goneKeys
        }
    }

    /**
     * Prunes Continue Watching after a Cricket Live refresh. This only touches live
     * entries from PlayFy/Cricify, so other providers and normal live-event cleanup
     * keep their existing behavior.
     */
    private suspend fun cleanupLiveCards(
        fetchedApis: List<Pair<MainAPI, List<SearchResponse>>>
    ) {
        if (fetchedApis.isEmpty()) return
        val entries = getResumeWatching() ?: return
        var cleaned = entries
        for ((api, cards) in fetchedApis) {
            cleaned = applyLiveCardCleanup(
                entries = cleaned,
                api = api,
                cardsOverride = cards,
                requireLiveCards = false,
            )
        }
        // The refresh also moves still served events to their new event page URL.
        cleaned = withContext(Dispatchers.IO) { refreshLiveCardUrls(cleaned) }
        if (cleaned != entries) {
            publishResumeWatching(cleaned)
        }
    }

    private fun publishResumeWatching(resumeWatchingResult: List<DataStoreHelper.ResumeWatchingResult>) {
        if (isLayout(TV) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ioSafe {
                // this WILL crash on non tvs, so keep this inside a try catch
                activity?.addProgramsToContinueWatching(resumeWatchingResult)
            }
        }
        _resumeWatching.postValue(resumeWatchingResult)
    }

    private fun loadResumeWatching() = viewModelScope.launchSafe {
        val resumeWatchingResult = getResumeWatching() ?: return@launchSafe
        // Nothing is requested here: the entry is compared with the original home cards
        // that are already loaded, so Continue Watching is never held up.
        val cleaned = applyLiveCardCleanup(resumeWatchingResult)
        publishResumeWatching(
            withContext(Dispatchers.IO) { refreshLiveCardUrls(cleaned) }
        )
    }

    fun loadStoredData(preferredWatchStatus: Set<WatchType>?) = viewModelScope.launchSafe {
        val watchStatusIds = withContext(Dispatchers.IO) {
            getAllWatchStateIds()?.map { id ->
                Pair(id, getResultWatchState(id))
            }
        }?.distinctBy { it.first } ?: return@launchSafe

        val length = WatchType.entries.size
        val currentWatchTypes = mutableSetOf<WatchType>()

        for (watch in watchStatusIds) {
            currentWatchTypes.add(watch.second)
            if (currentWatchTypes.size >= length) {
                break
            }
        }

        currentWatchTypes.remove(WatchType.NONE)

        if (currentWatchTypes.size <= 0) {
            DataStoreHelper.homeBookmarkedList = intArrayOf()
            _availableWatchStatusTypes.postValue(setOf<WatchType>() to setOf())
            _bookmarks.postValue(Pair(false, ArrayList()))
            return@launchSafe
        }

        val watchPrefNotNull = preferredWatchStatus ?: EnumSet.of(currentWatchTypes.first())
        //if (currentWatchTypes.any { watchPrefNotNull.contains(it) }) watchPrefNotNull else listOf(currentWatchTypes.first())

        DataStoreHelper.homeBookmarkedList = watchPrefNotNull.map { it.internalId }.toIntArray()
        _availableWatchStatusTypes.postValue(

            watchPrefNotNull to
                    currentWatchTypes,

            )

        val list = withContext(Dispatchers.IO) {
            watchStatusIds.filter { watchPrefNotNull.contains(it.second) }
                .mapNotNull { getBookmarkedData(it.first) }
                .sortedBy { -it.latestUpdatedTime }
        }
        _bookmarks.postValue(Pair(true, list))
    }

    private var onGoingLoad: Job? = null
    private var isCurrentlyLoadingName: String? = null
    private fun loadAndCancel(api: MainAPI) {
        //println("loaded ${api.name}")
        onGoingLoad?.cancel()
        isCurrentlyLoadingName = api.name
        onGoingLoad = load(api)
    }

    data class ExpandableHomepageList(
        var list: HomePageList,
        var currentPage: Int,
        var hasNext: Boolean,
    )

    private val expandable: MutableMap<String, ExpandableHomepageList> = mutableMapOf()
    private val _page =
        MutableLiveData<Resource<Map<String, ExpandableHomepageList>>>(Resource.Loading())
    val page: LiveData<Resource<Map<String, ExpandableHomepageList>>> = _page

    val lock: MutableSet<String> = mutableSetOf()

    suspend fun expandAndReturn(name: String): ExpandableHomepageList? {
        if (lock.contains(name)) return null
        lock += name

        repo?.apply {
            waitForHomeDelay()

            expandable[name]?.let { current ->
                debugAssert({ !current.hasNext }) {
                    "Expand called when not needed"
                }

                val nextPage = current.currentPage + 1
                val next = getMainPage(nextPage, mainPage.indexOfFirst { it.name == name })
                if (next is Resource.Success) {
                    next.value.filterNotNull().forEach { main ->
                        main.items.forEach { newList ->
                            val key = newList.name
                            expandable[key]?.apply {
                                hasNext = main.hasNext
                                currentPage = nextPage

                                debugWarning({ newList.list.any { outer -> this.list.list.any { it.url == outer.url } } }) {
                                    "Expanded contained an item that was previously already in the list\n${list.name} = ${this.list.list}\n${newList.name} = ${newList.list}"
                                }

                                this.list.list += newList.list
                                this.list.list.distinctBy { it.url } // just to be sure we are not adding the same shit for some reason
                            } ?: debugWarning {
                                "Expanded an item not in main load named $key, current list is ${expandable.keys}"
                            }
                        }
                    }
                } else {
                    current.hasNext = false
                }
            }
            _page.postValue(Resource.Success(expandable))
        }

        lock -= name

        return expandable[name]
    }

    // this is soo over engineered, but idk how I can make it clean without making the main api harder to use :pensive:
    fun expand(name: String) = viewModelScope.launchSafe {
        expandAndReturn(name)
    }

    // returns the amount of items added and modifies current
    private suspend fun updatePreviewResponses(
        current: MutableList<LoadResponse>,
        alreadyAdded: MutableSet<String>,
        shuffledList: List<SearchResponse>,
        size: Int
    ): Int {
        var count = 0

        val addItems = arrayListOf<SearchResponse>()
        for (searchResponse in shuffledList) {
            if (!alreadyAdded.contains(searchResponse.url)) {
                addItems.add(searchResponse)
                previewResponsesAdded.add(searchResponse.url)
                if (++count >= size) {
                    break
                }
            }
        }

        val add = addItems.amap { searchResponse ->
            repo?.load(searchResponse.url)
        }.mapNotNull { if (it != null && it is Resource.Success) it.value else null }
        current.addAll(add)
        return add.size
    }

    private var addJob: Job? = null
    fun loadMoreHomeScrollResponses() {
        addJob = ioSafe {
            updatePreviewResponses(previewResponses, previewResponsesAdded, currentShuffledList, 1)
            _preview.postValue(Resource.Success((previewResponsesAdded.size < currentShuffledList.size) to previewResponses))
        }
    }

    private fun load(api: MainAPI): Job = ioSafe {
        repo = //if (api != null) {
            APIRepository(api)
        //} else {
        //    autoloadRepo()
        //}

        _apiName.postValue(repo?.name)
        _randomItems.postValue(listOf())

        if (repo?.hasMainPage != true) {
            _page.postValue(Resource.Success(emptyMap()))
            _preview.postValue(Resource.Failure(false, "No homepage"))
            return@ioSafe
        }


        _page.postValue(Resource.Loading())
        _preview.postValue(Resource.Loading())
        // cancel the current preview expand as that is no longer relevant
        addJob?.cancel()

        // Hidden categories are skipped before the provider request when its
        // main page is declared as named entries. This saves both network
        // requests and homepage processing time; dynamic homepages are
        // filtered after loading below.
        val ctx = context
        val providerKey = HomeCategorySettings.providerKey(api)
        val hiddenCategories = ctx?.let {
            HomeCategorySettings.hiddenFor(it, providerKey)
        }.orEmpty()
        val hiddenNormalized = hiddenCategories
            .mapTo(HashSet(hiddenCategories.size)) { HomeCategorySettings.normalize(it) }
        val skipCategory: ((String) -> Boolean)? =
            if (hiddenNormalized.isEmpty()) null
            else { name -> HomeCategorySettings.normalize(name) in hiddenNormalized }

        when (val data = repo?.getMainPage(1, null, skipCategory)) {
            is Resource.Success -> {
                try {
                    val rawValue = data.value
                    if (ctx != null) {
                        HomeCategorySettings.rememberCategories(
                            ctx,
                            providerKey,
                            rawValue.filterNotNull()
                                .flatMap { it.items }
                                .map { it.name }
                        )
                    }
                    val loadedValue = if (hiddenNormalized.isEmpty()) {
                        rawValue
                    } else {
                        rawValue.mapNotNull { response ->
                            val items = response?.items?.filterNot {
                                HomeCategorySettings.normalize(it.name) in hiddenNormalized
                            }.orEmpty()
                            if (response == null || items.isEmpty()) null
                            else response.copy(items = items)
                        }
                    }
                    repo?.api?.let { rememberHomeCards(it, loadedValue) }
                    expandable.clear()
                    loadedValue.forEach { home ->
                        home?.items?.forEach { list ->
                            val filteredList =
                                context?.filterHomePageListByFilmQuality(list) ?: list
                            // Remember which plugin produced these items so opening one
                            // resolves that exact plugin (two plugins may share a name).
                            val homeSourcePlugin = repo?.api?.sourcePlugin
                            if (homeSourcePlugin != null) {
                                filteredList.list.forEach { it.setSourcePlugin(homeSourcePlugin) }
                            }
                            expandable[list.name] =
                                ExpandableHomepageList(
                                    filteredList.copy(
                                        list = CopyOnWriteArrayList(
                                            filteredList.list
                                        )
                                    ), 1, home.hasNext
                                )
                        }
                    }

                    val items = loadedValue.mapNotNull { it?.items }.flatten()


                    previewResponses.clear()
                    previewResponsesAdded.clear()

                    //val home = data.value
                    if (items.isNotEmpty()) {
                        val currentList =
                            items.shuffled().filter { it.list.isNotEmpty() }
                                .flatMap { it.list }
                                .distinctBy { it.url }.toList()

                        if (currentList.isNotEmpty()) {
                            val randomItems =
                                context?.filterSearchResultByFilmQuality(currentList.shuffled())
                                    ?: currentList.shuffled()

                            updatePreviewResponses(
                                previewResponses,
                                previewResponsesAdded,
                                randomItems,
                                3
                            )

                            _randomItems.postValue(randomItems)
                            currentShuffledList = randomItems
                        }
                    }
                    if (previewResponses.isEmpty()) {
                        _preview.postValue(
                            Resource.Failure(
                                false,
                                "No homepage responses"
                            )
                        )
                    } else {
                        _preview.postValue(Resource.Success((previewResponsesAdded.size < currentShuffledList.size) to previewResponses))
                    }
                    _page.postValue(Resource.Success(expandable))
                    // The original cards are now known: prune live entries from this
                    // plugin that it no longer serves.
                    loadResumeWatching()
                } catch (e: Exception) {
                    _randomItems.postValue(emptyList())
                    logError(e)
                }
            }

            is Resource.Failure -> {
                @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                _page.postValue(data!!)
                @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
                _preview.postValue(data!!)
            }

            else -> Unit
        }
        isCurrentlyLoadingName = null
    }

    fun click(callback: SearchClickCallback) {
        if (callback.action == SEARCH_ACTION_FOCUSED) return
        val entry = callback.card as? DataStoreHelper.ResumeWatchingResult
        if (entry != null && entry.type?.isLiveStream() == true) {
            // Live Continue Watching always loads fresh, exactly like opening the original
            // card from home: find the current URL from loaded home pages. If home pages
            // aren't loaded yet, fall through to the saved URL — never load the provider's
            // main page, which some providers (PlayFy) cannot serve as a link source.
            val freshUrl = freshLiveCardUrl(entry)
            if (freshUrl != null) {
                SearchHelper.handleSearchClickCallback(
                    SearchClickCallback(
                        callback.action,
                        callback.view,
                        callback.position,
                        entry.copy(url = freshUrl).apply { sourcePlugin = entry.sourcePlugin },
                    )
                )
                return
            }
        }
        val freshUrl = entry?.let { freshLiveCardUrl(it) }
        if (entry == null || freshUrl == null) {
            SearchHelper.handleSearchClickCallback(callback)
            return
        }
        SearchHelper.handleSearchClickCallback(
            SearchClickCallback(
                callback.action,
                callback.view,
                callback.position,
                entry.copy(url = freshUrl).apply { sourcePlugin = entry.sourcePlugin },
            )
        )
    }

    private val _popup = MutableLiveData<Pair<ExpandableHomepageList, (() -> Unit)?>?>(null)
    val popup: LiveData<Pair<ExpandableHomepageList, (() -> Unit)?>?> = _popup

    fun popup(list: ExpandableHomepageList?, deleteCallback: (() -> Unit)? = null) {
        if (list == null)
            _popup.postValue(null)
        else
            _popup.postValue(list to deleteCallback)
    }

    private fun bookmarksUpdated(unused: Boolean) {
        reloadStored()
    }

    private fun afterPluginsLoaded(forceReload: Boolean) {
        scheduleCustomHomeRefresh(CUSTOM_HOME_REFRESH_DELAY_MS)
        // Don't force reload on plugin-loaded events — the page already loads with
        // available plugins, and forcing reposts Resource.Loading() ~7× per cold start,
        // causing black screen jitter. The early-return guard skips if content exists.
        loadAndCancel(DataStoreHelper.currentHomePage, false)
        // Live cards can only be validated once the plugins that own them are loaded.
        loadResumeWatching()
        // Do not force: this event fires several times per cold start, and forcing cancels
        // and refetches the whole schedule every time. The 2 minute cache is only advanced
        // on success, so a first attempt that ran before the live plugins were loaded still
        // retries here, while a successful one is reused.
        loadCricketLive()
    }

    private fun afterMainPluginsLoaded(unused: Boolean = false) {
        scheduleCustomHomeRefresh(CUSTOM_HOME_REFRESH_DELAY_MS)
        loadAndCancel(DataStoreHelper.currentHomePage, false)
        loadResumeWatching()
        loadCricketLive()
    }

    private var customHomeRefreshJob: Job? = null

    /** True when the selected homepage is one of the user's own custom entries. */
    private fun isCustomHomeSelected(): Boolean {
        val ctx = context ?: return false
        val name = DataStoreHelper.currentHomePage ?: return false
        if (name == noneApi.name || name == randomApi.name) return false
        return CustomHomeSources.findByName(ctx, name) != null
    }

    /**
     * True when the custom entry on screen was built from fewer plugins than it can
     * resolve now — the cold-start case where its plugins finished loading after the
     * page was rendered. Purely in-memory state: no network, no provider request.
     */
    private fun customHomeIsStale(): Boolean {
        val ctx = context ?: return false
        val name = DataStoreHelper.currentHomePage ?: return false
        if (name == noneApi.name || name == randomApi.name) return false
        val source = CustomHomeSources.findByName(ctx, name) ?: return false
        val expected = CustomHomeSourceApi.resolvableCount(source, ctx)
        val current = repo?.api
        if (current == null || current.name != name) return expected > 0
        return current.mainPage.size != expected
    }

    /**
     * Rebuilds a custom homepage once, after the plugin batches of a cold start have
     * settled: one scheduled load instead of one fetch per plugin-loaded event. Never
     * waits — shows what is available now, fills in the rest after plugins load.
     */
    private fun scheduleCustomHomeRefresh(delayMs: Long) {
        if (!isCustomHomeSelected()) return
        customHomeRefreshJob?.cancel()
        customHomeRefreshJob = viewModelScope.launchSafe {
            delay(delayMs)
            val stale = customHomeIsStale()
            val waiting = page.value !is Resource.Success
            if (stale || (waiting && isCurrentlyLoadingName == null)) {
                loadAndCancel(DataStoreHelper.currentHomePage, true)
            }
        }
    }

    private fun reloadHome(unused: Boolean = false) {
        loadAndCancel(DataStoreHelper.currentHomePage, true)
        loadCricketLive(force = true)
    }

    /**
     * Refresh button path: reload the selected home provider and fetch a fresh
     * cricket schedule/cards too, without leaving and reopening Home.
     */
    fun reloadHomeFromUI() {
        loadAndCancel(
            apiName.value ?: noneApi.name,
            forceReload = true,
            fromUI = true
        )
        loadCricketLive(force = true)
    }

    private fun reloadAccount(unused: Boolean = false) {
        _currentAccount.postValue(
            getCurrentAccount()
        )
    }

    init {
        MainActivity.bookmarksUpdatedEvent += ::bookmarksUpdated
        MainActivity.afterPluginsLoadedEvent += ::afterPluginsLoaded
        MainActivity.mainPluginsLoadedEvent += ::afterMainPluginsLoaded
        MainActivity.reloadHomeEvent += ::reloadHome
        MainActivity.reloadAccountEvent += ::reloadAccount
        context?.let {
            PreferenceManager.getDefaultSharedPreferences(it)
                .registerOnSharedPreferenceChangeListener(cricketLivePreferenceListener)
        }
        loadCricketLive()
    }

    override fun onCleared() {
        context?.let {
            PreferenceManager.getDefaultSharedPreferences(it)
                .unregisterOnSharedPreferenceChangeListener(cricketLivePreferenceListener)
        }
        MainActivity.bookmarksUpdatedEvent -= ::bookmarksUpdated
        MainActivity.afterPluginsLoadedEvent -= ::afterPluginsLoaded
        MainActivity.mainPluginsLoadedEvent -= ::afterMainPluginsLoaded
        MainActivity.reloadHomeEvent -= ::reloadHome
        MainActivity.reloadAccountEvent -= ::reloadAccount
        super.onCleared()
    }

    fun queryTextSubmit(query: String) {
        QuickSearchFragment.pushSearch(
            query,
            repo?.name?.let { arrayOf(it) })
    }

    fun queryTextChange(newText: String) {
        // do nothing
    }

    fun loadStoredData() {
        val list = EnumSet.noneOf(WatchType::class.java)
        DataStoreHelper.homeBookmarkedList.map { WatchType.fromInternalId(it) }.let {
            list.addAll(it)
        }
        loadStoredData(list)
    }

    fun reloadStored() {
        loadResumeWatching()
        loadStoredData()
    }

    fun click(load: LoadClickCallback) {
        // The preview hero carries plain LoadResponses, which have no plugin of their own.
        // The home provider that produced them does, so pass it along: without it both
        // extensions playing the same video would share one progress entry.
        loadResult(
            load.response.url,
            load.response.apiName,
            load.response.name,
            load.action,
            sourcePlugin = (load.response as? ResultViewModel2.LoadResponseFromSearch)?.sourcePlugin
                ?: repo?.api?.sourcePlugin,
        )
    }

    // only save the key if it is from UI, as we don't want internal functions changing the setting
    fun loadAndCancel(
        preferredApi: MainAPI,
        forceReload: Boolean = true,
        fromUI: Boolean = false
    ) =
        ioSafe {
            if (fromUI) {
                DataStoreHelper.currentHomePage = preferredApi.name
                DataStoreHelper.currentHomeSource = preferredApi.sourcePlugin
            }
            loadAndCancel(preferredApi)
            reloadAccount()
        }

    // only save the key if it is from UI, as we don't want internal functions changing the setting
    fun loadAndCancel(
        preferredApiName: String?,
        forceReload: Boolean = true,
        fromUI: Boolean = false
    ) =
        ioSafe {
            //println("trying to load $preferredApiName")
            // Since plugins are loaded in stages this function can get called multiple times.
            // The issue with this is that the homepage may be fetched multiple times while the first request is loading
            // api?.let { expandable[it.name]?.list?.list?.isNotEmpty() } == true
            val currentPage = page.value

            // if we don't need to reload and we have a valid homepage or currently loading the same thing then return
            val currentLoading = isCurrentlyLoadingName
            if (!forceReload && (currentPage is Resource.Success && currentPage.value.isNotEmpty() || (currentLoading != null && currentLoading == preferredApiName))) {
                return@ioSafe
            }

            val customSource = if (
                preferredApiName != noneApi.name && preferredApiName != randomApi.name
            ) {
                context?.let { CustomHomeSources.findByName(it, preferredApiName) }
            } else {
                null
            }
            if (customSource != null) {
                // Never fall back to "None" for a custom entry — keep the custom name
                // on screen with loading state, load what plugins are ready now, and
                // let the debounced refresh fill in the rest after they settle.
                val customApi = context?.let {
                    CustomHomeSourceApi.create(customSource, it)
                }
                _page.postValue(Resource.Loading())
                _apiName.postValue(customSource.name)
                if (customApi == null) {
                    // Plugins behind this entry aren't loaded yet — show loading
                    // immediately, rebuild once they settle (1.5 s debounce).
                    if (!fromUI) {
                        scheduleCustomHomeRefresh(CUSTOM_HOME_REFRESH_DELAY_MS)
                    }
                } else {
                    if (fromUI) {
                        DataStoreHelper.currentHomePage = customApi.name
                        DataStoreHelper.currentHomeSource = null
                    }
                    loadAndCancel(customApi)
                }
                reloadAccount()
                return@ioSafe
            }

            val api =
                if (DataStoreHelper.currentHomeSource == null) {
                    getApiFromNameNull(preferredApiName)
                } else {
                    APIHolder.getApiFromSourceAndNameNull(
                        preferredApiName,
                        DataStoreHelper.currentHomeSource
                    ) ?: getApiFromNameNull(preferredApiName)
                }
            if (preferredApiName == noneApi.name) {
                // just set to random
                if (fromUI) {
                    DataStoreHelper.currentHomePage = noneApi.name
                    DataStoreHelper.currentHomeSource = null
                }
                loadAndCancel(noneApi)
            } else if (preferredApiName == randomApi.name) {
                // randomize the api, if none exist like if not loaded or not installed
                // then use nothing
                val ctx = context
                val validAPIs = ctx
                    ?.filterProviderByPreferredMedia()
                    ?.filterNot {
                        HomeCategorySettings.isProviderFullyHidden(ctx, it) ||
                            CustomHomeSources.isBlockedProvider(it)
                    }
                if (validAPIs.isNullOrEmpty()) {
                    loadAndCancel(noneApi)
                } else {
                    val apiRandom = validAPIs.random()
                    loadAndCancel(apiRandom)
                    if (fromUI) {
                        DataStoreHelper.currentHomePage = apiRandom.name
                        DataStoreHelper.currentHomeSource = apiRandom.sourcePlugin
                    }
                }
            } else if (api == null) {
                // API is not found aka not loaded or removed, post the loading
                // progress if waiting for plugins, otherwise nothing
                if (PluginManager.loadedOnlinePlugins || PluginManager.isSafeMode()) {
                    loadAndCancel(noneApi)
                } else {
                    _page.postValue(Resource.Loading())
                    if (preferredApiName != null)
                        _apiName.postValue(preferredApiName)
                }
            } else {
                // if the api is found, then set it to it and save key
                if (fromUI) {
                    DataStoreHelper.currentHomePage = api.name
                    DataStoreHelper.currentHomeSource = api.sourcePlugin
                }
                loadAndCancel(api)
            }
            reloadAccount()
        }
}