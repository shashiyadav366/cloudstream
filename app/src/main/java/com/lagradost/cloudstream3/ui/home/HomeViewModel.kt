package com.lagradost.cloudstream3.ui.home

import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.APIRepository.Companion.noneApi
import com.lagradost.cloudstream3.ui.APIRepository.Companion.randomApi
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
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
import kotlinx.coroutines.withContext
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList

class HomeViewModel : ViewModel() {
    companion object {
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

    private fun rememberHomeCards(api: MainAPI, data: List<HomePageResponse?>) {
        val cards = data.flatMap { it?.items.orEmpty() }.flatMap { it.list }
        loadedHomeCards = loadedHomeCards + (homeCardsKey(api) to LoadedHomeCards(
            urls = cards.mapNotNull { it.url.takeIf(String::isNotBlank) }.toSet(),
            ids = cards.mapNotNull { it.id }.toSet(),
            names = cards.mapNotNull {
                it.name.takeIf(String::isNotBlank)?.let(::normalizeCardName)
            }.toSet(),
            hasLiveCards = cards.any { it.type?.isLiveStream() == true },
        ))
    }

    /**
     * Drops live stream entries that the current plugin is no longer serving. Only the
     * provider that just loaded its original cards is checked, and only when those cards
     * actually contain live streams, so a failed or non-live home page never removes
     * anything. The check is immediate: an entry not served by its own provider anymore
     * is removed as soon as that provider's home page loads.
     */
    private suspend fun applyLiveCardCleanup(
        entries: List<DataStoreHelper.ResumeWatchingResult>
    ): List<DataStoreHelper.ResumeWatchingResult> {
        val api = repo?.api ?: return entries
        val cards = loadedHomeCards[homeCardsKey(api)] ?: return entries
        if (!cards.hasLiveCards) return entries

        val goneEntries = entries.filter { entry ->
            entry.type?.isLiveStream() == true &&
                entry.belongsTo(api) &&
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
        publishResumeWatching(applyLiveCardCleanup(resumeWatchingResult))
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

        when (val data = repo?.getMainPage(1, null)) {
            is Resource.Success -> {
                try {
                    repo?.api?.let { rememberHomeCards(it, data.value) }
                    expandable.clear()
                    data.value.forEach { home ->
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

                    val items = data.value.mapNotNull { it?.items }.flatten()


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
        if (callback.action != SEARCH_ACTION_FOCUSED) {
            SearchHelper.handleSearchClickCallback(callback)
        }
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
        loadAndCancel(DataStoreHelper.currentHomePage, forceReload)
        // Live cards can only be validated once the plugins that own them are loaded.
        loadResumeWatching()
    }

    private fun afterMainPluginsLoaded(unused: Boolean = false) {
        loadAndCancel(DataStoreHelper.currentHomePage, false)
        loadResumeWatching()
    }

    private fun reloadHome(unused: Boolean = false) {
        loadAndCancel(DataStoreHelper.currentHomePage, true)
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
    }

    override fun onCleared() {
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
            sourcePlugin = repo?.api?.sourcePlugin,
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
                val validAPIs = context?.filterProviderByPreferredMedia()
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