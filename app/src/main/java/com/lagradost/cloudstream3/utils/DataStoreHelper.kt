package com.lagradost.cloudstream3.utils

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.unixTimeMS
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeyClass
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKeyClass
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.EpisodeResponse
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SourcePluginAware
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.NEXT_WATCH_EPISODE_PERCENTAGE
import com.lagradost.cloudstream3.ui.result.EpisodeSortType
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.isLiveStream
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.serializers.WriteOnlySerializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

const val VIDEO_POS_DUR = "video_pos_dur"
const val VIDEO_WATCH_STATE = "video_watch_state"
const val RESULT_WATCH_STATE = "result_watch_state"
const val RESULT_WATCH_STATE_DATA = "result_watch_state_data"
const val RESULT_SUBSCRIBED_STATE_DATA = "result_subscribed_state_data"
const val RESULT_FAVORITES_STATE_DATA = "result_favorites_state_data"
const val RESULT_RESUME_WATCHING = "result_resume_watching_2" // Changed due to id changes
const val RESULT_RESUME_WATCHING_OLD = "result_resume_watching"
const val RESULT_RESUME_WATCHING_HAS_MIGRATED = "result_resume_watching_migrated"
const val RESULT_EPISODE = "result_episode"
const val RESULT_SEASON = "result_season"
const val RESULT_DUB = "result_dub"
const val KEY_RESULT_SORT = "result_sort"
const val USER_PINNED_PROVIDERS = "user_pinned_providers" // Key for pinned user set

class UserPreferenceDelegate<T : Any>(
    private val key: String,
    private val default: T,
) {
    private val klass: KClass<out T> = default::class
    private val realKey get() = "${DataStoreHelper.currentAccount}/$key"
    operator fun getValue(self: Any?, property: KProperty<*>) =
        getKeyClass(realKey, klass.java) ?: default

    operator fun setValue(
        self: Any?,
        property: KProperty<*>,
        t: T?,
    ) {
        if (t == null) {
            removeKey(realKey)
        } else {
            setKeyClass(realKey, t)
        }
    }
}

object DataStoreHelper {
    // be aware, don't change the index of these as Account uses the index for the art
    val profileImages = arrayOf(
        R.drawable.profile_bg_dark_blue,
        R.drawable.profile_bg_blue,
        R.drawable.profile_bg_orange,
        R.drawable.profile_bg_pink,
        R.drawable.profile_bg_purple,
        R.drawable.profile_bg_red,
        R.drawable.profile_bg_teal,
    )

    private var searchPreferenceProvidersStrings: List<String> by UserPreferenceDelegate(
        /** java moment right here, as listOf()::class.java != List(0) { "" }::class.java */
        "search_pref_providers", List(0) { "" }
    )

    private fun serializeTv(data: List<TvType>): List<String> = data.map { it.name }

    private fun deserializeTv(data: List<String>): List<TvType> {
        return data.mapNotNull { listName ->
            TvType.values().firstOrNull { it.name == listName }
        }
    }

    var searchPreferenceProviders: List<String>
        get() {
            val ret = searchPreferenceProvidersStrings
            return ret.ifEmpty {
                context?.filterProviderByPreferredMedia()?.map { it.name } ?: emptyList()
            }
        }
        set(value) {
            searchPreferenceProvidersStrings = value
        }

    private var searchPreferenceTagsStrings: List<String> by UserPreferenceDelegate(
        "search_pref_tags",
        listOf(TvType.Movie, TvType.TvSeries).map { it.name })

    var searchPreferenceTags: List<TvType>
        get() = deserializeTv(searchPreferenceTagsStrings)
        set(value) {
            searchPreferenceTagsStrings = serializeTv(value)
        }

    private var homePreferenceStrings: List<String> by UserPreferenceDelegate(
        "home_pref_homepage",
        listOf(TvType.Movie, TvType.TvSeries).map { it.name })

    var homePreference: List<TvType>
        get() = deserializeTv(homePreferenceStrings)
        set(value) {
            homePreferenceStrings = serializeTv(value)
        }

    var homeBookmarkedList: IntArray by UserPreferenceDelegate(
        "home_bookmarked_last_list",
        IntArray(0)
    )

    var playBackSpeed: Float by UserPreferenceDelegate("playback_speed", 1.0f)
    var resizeMode: Int by UserPreferenceDelegate("resize_mode", 0)
    var librarySortingMode: Int by UserPreferenceDelegate(
        "library_sorting_mode",
        ListSorting.AlphabeticalA.ordinal
    )

    private var _resultsSortingMode: Int by UserPreferenceDelegate(
        "results_sorting_mode",
        EpisodeSortType.NUMBER_ASC.ordinal
    )

    var resultsSortingMode: EpisodeSortType
        get() = EpisodeSortType.entries.getOrNull(_resultsSortingMode) ?: EpisodeSortType.NUMBER_ASC
        set(value) {
            _resultsSortingMode = value.ordinal
        }

    @Serializable
    @Immutable
    data class Account(
        @JsonProperty("keyIndex") @SerialName("keyIndex") val keyIndex: Int,
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("customImage") @SerialName("customImage") val customImage: String? = null,
        @JsonProperty("defaultImageIndex") @SerialName("defaultImageIndex") val defaultImageIndex: Int,
        @JsonProperty("lockPin") @SerialName("lockPin") val lockPin: String? = null,
    ) {
        @get:JsonIgnore
        val image get() = customImage?.let { UiImage.Image(it) } ?:
            profileImages.getOrNull(defaultImageIndex)?.let {
                UiImage.Drawable(it)
            } ?: UiImage.Drawable(profileImages.first())
    }

    const val TAG = "data_store_helper"
    var accounts by PreferenceDelegate("$TAG/account", arrayOf<Account>())
    var selectedKeyIndex by PreferenceDelegate("$TAG/account_key_index", 0)
    val currentAccount: String get() = selectedKeyIndex.toString()

    private val _selectedAccountNumberFlow = MutableStateFlow(0)
    /** What account instance we are on, this number changes whenever anything about local accounts changes */
    val selectedAccountNumberFlow : StateFlow<Int> = _selectedAccountNumberFlow

    /**
     * Get or set the current account homepage.
     * Setting this does not automatically reload the homepage.
     */
    var currentHomePage: String?
        get() = getKey<String>("$currentAccount/$USER_SELECTED_HOMEPAGE_API")
        set(value) {
            val key = "$currentAccount/$USER_SELECTED_HOMEPAGE_API"
            if (value == null) {
                removeKey(key)
            } else {
                setKey(key, value)
            }
        }

    /**
     * The plugin file path (sourcePlugin) the current homepage provider was selected from.
     * Needed to independently select same-named providers which live in different repositories,
     * since currentHomePage only stores the (possibly ambiguous) provider name.
     */
    var currentHomeSource: String?
        get() = getKey<String>("$currentAccount/$USER_SELECTED_HOMEPAGE_API/SOURCE")
        set(value) {
            val key = "$currentAccount/$USER_SELECTED_HOMEPAGE_API/SOURCE"
            if (value == null) {
                removeKey(key)
            } else {
                setKey(key, value)
            }
        }

    /**
     * After restoring a backup, the stored source is the other device's plugin path.
     * Resolve it to the matching plugin installed here so the selected default
     * homepage provider stays highlighted and keeps loading after the restore.
     */
    fun normalizeCurrentHomeSource() {
        val homepage = currentHomePage ?: return
        val source = currentHomeSource ?: return
        val api = com.lagradost.cloudstream3.APIHolder
            .getApiFromSourceAndNameNull(homepage, source) ?: return
        val localSource = api.sourcePlugin ?: return
        if (localSource != source) {
            currentHomeSource = localSource
        }
    }

    fun setAccount(account: Account, showToast : Boolean = true) {
        val homepage = currentHomePage
        selectedKeyIndex = account.keyIndex
        AccountManager.updateAccountIds()
        if(showToast) {
            showToast(context?.getString(R.string.logged_account, account.name) ?: account.name)
        }
        MainActivity.bookmarksUpdatedEvent(true)
        MainActivity.reloadLibraryEvent(true)
        val oldAccount = accounts.find { it.keyIndex == account.keyIndex }
        if (oldAccount != null && currentHomePage != homepage) {
            // This is not a new account, and the homepage has changed, reload it
            MainActivity.reloadHomeEvent(true)
        }
        _selectedAccountNumberFlow.value += 1
    }

    fun getDefaultAccount(context: Context): Account {
        return accounts.let { currentAccounts ->
            currentAccounts.getOrNull(currentAccounts.indexOfFirst { it.keyIndex == 0 }) ?: Account(
                keyIndex = 0,
                name = context.getString(R.string.default_account),
                defaultImageIndex = 0,
            )
        }
    }

    fun getAccounts(context: Context): List<Account> {
        return accounts.toMutableList().apply {
            val item = getDefaultAccount(context)
            remove(item)
            add(0, item)
        }
    }

    /** Gets the current selected account (or default), may return null if context is null and the user is using the default account */
    fun getCurrentAccount(): Account? {
        return (context?.let {
            getAccounts(it)
        } ?: accounts.toList()).firstNotNullOfOrNull { account ->
            if (account.keyIndex == selectedKeyIndex) {
                account
            } else {
                null
            }
        }
    }

    @Serializable
    data class PosDur(
        @JsonProperty("position") @SerialName("position") val position: Long,
        @JsonProperty("duration") @SerialName("duration") val duration: Long,
    )

    fun PosDur.fixVisual(): PosDur {
        if (duration <= 0) return PosDur(0, duration)
        val percentage = position * 100 / duration
        return when {
            percentage <= 1 -> PosDur(0, duration)
            percentage <= 5 -> PosDur(5 * duration / 100, duration)
            percentage >= 95 -> PosDur(duration, duration)
            else -> this
        }
    }

    fun Int.toYear(): Date =
        GregorianCalendar.getInstance().also { it.set(Calendar.YEAR, this) }.time

    /**
     * Used to display notifications on new episodes and posters in library.
     */
    @Serializable
    abstract class LibrarySearchResponse(
       /**
        * These fields are marked @Transient because this class is only ever serialized through
        * through its subclasses, which redeclare each property with their own @SerialName
        * annotations. Without @Transient here, kotlinx.serialization would try to
        * generate a serializer for the abstract base class itself (or double-serialize
        * these fields), which fails/conflicts since these are meant to be overridden,
        * not serialized directly from the parent.
        */
        @Transient override var id: Int? = null,
        @Transient open val latestUpdatedTime: Long = 0L,
        @Transient override val name: String = "",
        @Transient override val url: String = "",
        @Transient override val apiName: String = "",
        @Transient override var type: TvType? = null,
        @Transient override var posterUrl: String? = null,
        @Transient open val year: Int? = null,
        @Transient open val syncData: Map<String, String>? = null,
        @Transient override var quality: SearchQuality? = null,
        @Transient override var posterHeaders: Map<String, String>? = null,
        @Transient open val plot: String? = null,
        @Transient override var score: Score? = null,
        @Transient open val tags: List<String>? = null,
    ) : SearchResponse {
        @JsonProperty("rating", access = JsonProperty.Access.WRITE_ONLY)
        @SerialName("rating")
        @Deprecated(
            "`rating` is the old scoring system, use score instead",
            replaceWith = ReplaceWith("score"),
            level = DeprecationLevel.ERROR,
        )
        var rating: Int? = null
            set(value) {
                if (value != null) {
                    @Suppress("DEPRECATION_ERROR")
                    score = Score.fromOld(value)
                }
            }
    }

    @OptIn(ExperimentalSerializationApi::class) // KeepGeneratedSerializer is an experimental annotation for now
    @KeepGeneratedSerializer
    @Serializable(with = SubscribedData.Serializer::class)
    data class SubscribedData(
        @JsonProperty("subscribedTime") @SerialName("subscribedTime") val subscribedTime: Long,
        @JsonProperty("lastSeenEpisodeCount") @SerialName("lastSeenEpisodeCount") val lastSeenEpisodeCount: Map<DubStatus, Int?>,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("latestUpdatedTime") @SerialName("latestUpdatedTime") override val latestUpdatedTime: Long,
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType?,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("year") @SerialName("year") override val year: Int?,
        @JsonProperty("syncData") @SerialName("syncData") override val syncData: Map<String, String>? = null,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("plot") @SerialName("plot") override val plot: String? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
        @JsonProperty("tags") @SerialName("tags") override val tags: List<String>? = null,
    ) : LibrarySearchResponse(
        id,
        latestUpdatedTime,
        name,
        url,
        apiName,
        type,
        posterUrl,
        year,
        syncData,
        quality,
        posterHeaders,
        plot,
        score,
        tags,
    ) {
        object Serializer : WriteOnlySerializer<SubscribedData>(
            SubscribedData.generatedSerializer(),
            setOf("rating"),
        )

        fun toLibraryItem(): SyncAPI.LibraryItem? {
            return SyncAPI.LibraryItem(
                name,
                url,
                id?.toString() ?: return null,
                null,
                null,
                null,
                latestUpdatedTime,
                apiName,
                type,
                posterUrl,
                posterHeaders,
                quality,
                year?.toYear(),
                this.id,
                plot = this.plot,
                score = this.score,
                tags = this.tags,
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class) // KeepGeneratedSerializer is an experimental annotation for now
    @KeepGeneratedSerializer
    @Serializable(with = BookmarkedData.Serializer::class)
    data class BookmarkedData(
        @JsonProperty("bookmarkedTime") @SerialName("bookmarkedTime") val bookmarkedTime: Long,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("latestUpdatedTime") @SerialName("latestUpdatedTime") override val latestUpdatedTime: Long,
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType?,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("year") @SerialName("year") override val year: Int?,
        @JsonProperty("syncData") @SerialName("syncData") override val syncData: Map<String, String>? = null,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("plot") @SerialName("plot") override val plot: String? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
        @JsonProperty("tags") @SerialName("tags") override val tags: List<String>? = null,
    ) : LibrarySearchResponse(
        id,
        latestUpdatedTime,
        name,
        url,
        apiName,
        type,
        posterUrl,
        year,
        syncData,
        quality,
        posterHeaders,
        plot,
    ) {
        object Serializer : WriteOnlySerializer<BookmarkedData>(
            BookmarkedData.generatedSerializer(),
            setOf("rating"),
        )

        fun toLibraryItem(id: String): SyncAPI.LibraryItem {
            return SyncAPI.LibraryItem(
                name,
                url,
                id,
                null,
                null,
                null,
                latestUpdatedTime,
                apiName,
                type,
                posterUrl,
                posterHeaders,
                quality,
                year?.toYear(),
                this.id,
                plot = this.plot,
                score = this.score,
                tags = this.tags,
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class) // KeepGeneratedSerializer is an experimental annotation for now
    @KeepGeneratedSerializer
    @Serializable(with = FavoritesData.Serializer::class)
    data class FavoritesData(
        @JsonProperty("favoritesTime") @SerialName("favoritesTime") val favoritesTime: Long,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("latestUpdatedTime") @SerialName("latestUpdatedTime") override val latestUpdatedTime: Long,
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType?,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("year") @SerialName("year") override val year: Int?,
        @JsonProperty("syncData") @SerialName("syncData") override val syncData: Map<String, String>? = null,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("plot") @SerialName("plot") override val plot: String? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
        @JsonProperty("tags") @SerialName("tags") override val tags: List<String>? = null,
    ) : LibrarySearchResponse(
        id,
        latestUpdatedTime,
        name,
        url,
        apiName,
        type,
        posterUrl,
        year,
        syncData,
        quality,
        posterHeaders,
        plot,
    ) {
        object Serializer : WriteOnlySerializer<FavoritesData>(
            FavoritesData.generatedSerializer(),
            setOf("rating"),
        )

        fun toLibraryItem(): SyncAPI.LibraryItem? {
            return SyncAPI.LibraryItem(
                name,
                url,
                id?.toString() ?: return null,
                null,
                null,
                null,
                latestUpdatedTime,
                apiName,
                type,
                posterUrl,
                posterHeaders,
                quality,
                year?.toYear(),
                this.id,
                plot = this.plot,
                score = this.score,
                tags = this.tags,
            )
        }
    }

    @Serializable
    data class ResumeWatchingResult(
        @JsonProperty("name") @SerialName("name") override val name: String,
        @JsonProperty("url") @SerialName("url") override val url: String,
        @JsonProperty("apiName") @SerialName("apiName") override val apiName: String,
        @JsonProperty("type") @SerialName("type") override var type: TvType? = null,
        @JsonProperty("posterUrl") @SerialName("posterUrl") override var posterUrl: String?,
        @JsonProperty("watchPos") @SerialName("watchPos") val watchPos: PosDur?,
        @JsonProperty("id") @SerialName("id") override var id: Int?,
        @JsonProperty("parentId") @SerialName("parentId") val parentId: Int?,
        @JsonProperty("episode") @SerialName("episode") val episode: Int?,
        @JsonProperty("season") @SerialName("season") val season: Int?,
        @JsonProperty("isFromDownload") @SerialName("isFromDownload") val isFromDownload: Boolean,
        @JsonProperty("quality") @SerialName("quality") override var quality: SearchQuality? = null,
        @JsonProperty("posterHeaders") @SerialName("posterHeaders") override var posterHeaders: Map<String, String>? = null,
        @JsonProperty("score") @SerialName("score") override var score: Score? = null,
        /** Last time the entry was played, used to sort Continue Watching entries. */
        @Transient val updateTime: Long = 0L,
        /**
         * Provider data needed to resolve links without loading the provider page again.
         * Only used by the click handler, never persisted as part of this model.
         */
        @Transient val episodeData: String? = null,
    ) : SearchResponse, SourcePluginAware {
        /** Plugin that produced this entry, when known. Never serialized here. */
        @Transient
        override var sourcePlugin: String? = null
    }

    /**
     * A datastore wide account for future implementations of a multiple account system
     */

    fun getAllWatchStateIds(): List<Int>? {
        val folder = "$currentAccount/$RESULT_WATCH_STATE"
        return getKeys(folder)?.mapNotNull {
            it.removePrefix("$folder/").toIntOrNull()
        }
    }

    fun deleteAllResumeStateIds() {
        val folder = "$currentAccount/$RESULT_RESUME_WATCHING"
        removeKeys(folder)
    }

    /** Removes all Continue watching entries and their saved watch progress. */
    fun deleteAllWatchHistory() {
        removeKeys("$currentAccount/$RESULT_RESUME_WATCHING")
        removeKeys("$currentAccount/$VIDEO_POS_DUR")
    }

    fun deleteBookmarkedData(id: Int?) {
        if (id == null) return
        AccountManager.localListApi.requireLibraryRefresh = true
        removeKey("$currentAccount/$RESULT_WATCH_STATE", id.toString())
        removeKey("$currentAccount/$RESULT_WATCH_STATE_DATA", id.toString())
    }

    fun getAllResumeStateIds(): List<Int>? {
        val folder = "$currentAccount/$RESULT_RESUME_WATCHING"
        return getKeys(folder)?.mapNotNull {
            // Per-plugin entries are stored as "<parentId>|<pluginScope>".
            it.removePrefix("$folder/").substringBefore('|').toIntOrNull()
        }
    }

    /**
     * All Continue watching entries, including per-plugin ones. Entries carry the
     * [DownloadObjects.ResumeWatching.sourcePlugin] that created them (null for the
     * legacy shared entry), so callers can resolve the matching progress scope.
     */
    fun getAllResumeStates(): List<DownloadObjects.ResumeWatching>? {
        val folder = "$currentAccount/$RESULT_RESUME_WATCHING"
        return getKeys(folder)?.mapNotNull { getKey<DownloadObjects.ResumeWatching>(it) }
    }

    private fun getAllResumeStateIdsOld(): List<Int>? {
        val folder = "$currentAccount/$RESULT_RESUME_WATCHING_OLD"
        return getKeys(folder)?.mapNotNull {
            it.removePrefix("$folder/").toIntOrNull()
        }
    }

    fun migrateResumeWatching() {
        // if (getKey<Boolean>(RESULT_RESUME_WATCHING_HAS_MIGRATED, false) != true) {
        setKey(RESULT_RESUME_WATCHING_HAS_MIGRATED, true)
        getAllResumeStateIdsOld()?.forEach { id ->
            getLastWatchedOld(id)?.let {
                setLastWatched(
                    it.parentId,
                    null,
                    it.episode,
                    it.season,
                    it.isFromDownload,
                    it.updateTime,
                )
                removeLastWatchedOld(it.parentId)
            }
        }
        // }
    }

    /**
     * Short, stable scope id for a plugin, used to keep resume positions, Continue
     * watching entries and cached headers independent per plugin. Null only when the
     * plugin is unknown (e.g. built-in providers, downloads, external players).
     *
     * Uses the plugin path relative to the Extensions folder, so it survives package
     * renames and flavour changes, then hashes it to keep storage keys short.
     */
    fun pluginProgressScope(sourcePlugin: String?): String? {
        val plugin = sourcePlugin ?: return null
        return Integer.toHexString(stablePluginId(plugin).hashCode())
    }

    /** Storage key for a progress/Continue watching entry, scoped when [scope] is set. */
    fun resumeWatchingKey(id: String, scope: String?): String =
        if (scope == null) id else "$id|$scope"

    /**
     * Key of the cached header that belongs to a Continue watching entry of
     * [sourcePlugin]. Two plugins that play the same movie share one result id, so the
     * header cache has to be keyed per plugin as well: otherwise the last plugin loaded
     * would take over the other one's card and playing it would open the wrong video.
     */
    fun headerCacheKey(parentId: Int?, sourcePlugin: String?): String =
        resumeWatchingKey(
            parentId?.toString() ?: "",
            pluginProgressScope(sourcePlugin)
        )

    fun setLastWatched(
        parentId: Int?,
        episodeId: Int?,
        episode: Int?,
        season: Int?,
        isFromDownload: Boolean = false,
        updateTime: Long? = null,
        sourcePlugin: String? = null,
        episodeData: String? = null,
    ) {
        if (parentId == null) return
        val scope = pluginProgressScope(sourcePlugin)
        setKey(
            "$currentAccount/$RESULT_RESUME_WATCHING",
            resumeWatchingKey(parentId.toString(), scope),
            DownloadObjects.ResumeWatching(
                parentId,
                episodeId,
                episode,
                season,
                updateTime ?: System.currentTimeMillis(),
                isFromDownload,
                // Persist the plugin only for scoped entries; legacy entries stay null
                // so they keep resolving to the shared progress.
                if (scope != null) sourcePlugin else null,
                // Lets Continue Watching start playback without loading the provider page.
                episodeData,
            )
        )
    }

    private fun removeLastWatchedOld(parentId: Int?) {
        if (parentId == null) return
        removeKey("$currentAccount/$RESULT_RESUME_WATCHING_OLD", parentId.toString())
    }

    fun removeLastWatched(parentId: Int?, sourcePlugin: String? = null) {
        if (parentId == null) return
        val scope = pluginProgressScope(sourcePlugin)
        removeKey(
            "$currentAccount/$RESULT_RESUME_WATCHING",
            resumeWatchingKey(parentId.toString(), scope),
        )
    }

    fun getLastWatched(id: Int?, sourcePlugin: String? = null): DownloadObjects.ResumeWatching? {
        if (id == null) return null
        val scope = pluginProgressScope(sourcePlugin)
        return getKey<DownloadObjects.ResumeWatching>(
            "$currentAccount/$RESULT_RESUME_WATCHING",
            resumeWatchingKey(id.toString(), scope),
        )
    }

    private fun getLastWatchedOld(id: Int?): DownloadObjects.ResumeWatching? {
        if (id == null) return null
        return getKey<DownloadObjects.ResumeWatching>(
            "$currentAccount/$RESULT_RESUME_WATCHING_OLD",
            id.toString(),
        )
    }

    fun setBookmarkedData(id: Int?, data: BookmarkedData) {
        if (id == null) return
        setKey("$currentAccount/$RESULT_WATCH_STATE_DATA", id.toString(), data)
        AccountManager.localListApi.requireLibraryRefresh = true
    }

    fun getBookmarkedData(id: Int?): BookmarkedData? {
        if (id == null) return null
        return getKey<BookmarkedData>("$currentAccount/$RESULT_WATCH_STATE_DATA", id.toString())
    }

    fun getAllBookmarkedData(): List<BookmarkedData> {
        return getKeys("$currentAccount/$RESULT_WATCH_STATE_DATA")?.mapNotNull {
            getKey<BookmarkedData>(it)
        } ?: emptyList()
    }

    fun getAllSubscriptions(): List<SubscribedData> {
        return getKeys("$currentAccount/$RESULT_SUBSCRIBED_STATE_DATA")?.mapNotNull {
            getKey<SubscribedData>(it)
        } ?: emptyList()
    }

    fun removeSubscribedData(id: Int?) {
        if (id == null) return
        AccountManager.localListApi.requireLibraryRefresh = true
        removeKey("$currentAccount/$RESULT_SUBSCRIBED_STATE_DATA", id.toString())
    }

    /**
     * Set new seen episodes and update time
     */
    fun updateSubscribedData(id: Int?, data: SubscribedData?, episodeResponse: EpisodeResponse?) {
        if (id == null || data == null || episodeResponse == null) return
        val newData = data.copy(
            latestUpdatedTime = unixTimeMS,
            lastSeenEpisodeCount = episodeResponse.getLatestEpisodes(),
        )
        setKey("$currentAccount/$RESULT_SUBSCRIBED_STATE_DATA", id.toString(), newData)
    }

    fun setSubscribedData(id: Int?, data: SubscribedData) {
        if (id == null) return
        setKey("$currentAccount/$RESULT_SUBSCRIBED_STATE_DATA", id.toString(), data)
        AccountManager.localListApi.requireLibraryRefresh = true
    }

    fun getSubscribedData(id: Int?): SubscribedData? {
        if (id == null) return null
        return getKey<SubscribedData>("$currentAccount/$RESULT_SUBSCRIBED_STATE_DATA", id.toString())
    }

    fun getAllFavorites(): List<FavoritesData> {
        return getKeys("$currentAccount/$RESULT_FAVORITES_STATE_DATA")?.mapNotNull {
            getKey<FavoritesData>(it)
        } ?: emptyList()
    }

    fun removeFavoritesData(id: Int?) {
        if (id == null) return
        AccountManager.localListApi.requireLibraryRefresh = true
        removeKey("$currentAccount/$RESULT_FAVORITES_STATE_DATA", id.toString())
    }

    fun setFavoritesData(id: Int?, data: FavoritesData) {
        if (id == null) return
        setKey("$currentAccount/$RESULT_FAVORITES_STATE_DATA", id.toString(), data)
        AccountManager.localListApi.requireLibraryRefresh = true
    }

    fun getFavoritesData(id: Int?): FavoritesData? {
        if (id == null) return null
        return getKey<FavoritesData>("$currentAccount/$RESULT_FAVORITES_STATE_DATA", id.toString())
    }

    /** Whether watch history (resume position + Continue watching) should be saved. */
    fun isWatchHistoryEnabled(): Boolean {
        val ctx = context ?: return true
        return PreferenceManager.getDefaultSharedPreferences(ctx)
            .getBoolean(ctx.getString(R.string.watch_history_key), true)
    }

    fun setViewPos(id: Int?, pos: Long, dur: Long, sourcePlugin: String? = null) {
        if (!isWatchHistoryEnabled()) return
        if (id == null) return
        if (dur < 30_000) return // too short
        val scope = pluginProgressScope(sourcePlugin)
        if (scope == null) {
            // Unknown plugin (downloads, external players, legacy paths): shared entry.
            setKey("$currentAccount/$VIDEO_POS_DUR", id.toString(), PosDur(pos, dur))
        } else {
            // A known plugin only writes its own position, so two plugins playing the
            // same movie never overwrite each other's progress.
            setKey(
                "$currentAccount/$VIDEO_POS_DUR",
                resumeWatchingKey(id.toString(), scope),
                PosDur(pos, dur),
            )
        }
    }

    /**
     * Sets the position, duration, and resume data of an episode/movie,
     * If nextEpisode is not specified it will not be able to set the next episode as resumable if progress > NEXT_WATCH_EPISODE_PERCENTAGE
     */
    fun setViewPosAndResume(
        id: Int?,
        position: Long,
        duration: Long,
        currentEpisode: Any?,
        nextEpisode: Any?,
        sourcePlugin: String? = null,
    ) {
        if (!isWatchHistoryEnabled()) return

        // Live streams have no meaningful resume position. Keep the entry while the original
        // plugin still has the card it was saved from (HomeViewModel drops the entry once the
        // plugin does not have it anymore). Refresh its timestamp on every playback update so
        // replaying a live event moves it to the top of Continue Watching, just like static
        // videos. NSFW is only a content category, not a stream kind: NSFW static videos are
        // seekable and track progress like any other video.
        val isLive = when (val meta = currentEpisode) {
            is ResultEpisode -> meta.tvType.isLiveStream()
            else -> false
        }

        if (isLive) {
            val parentId = when (val meta = currentEpisode) {
                is ResultEpisode -> meta.parentId
                is ExtractorUri -> meta.parentId
                else -> null
            }
            if (parentId != null) {
                setLastWatched(
                    parentId,
                    episodeId = null,
                    episode = null,
                    season = null,
                    isFromDownload = currentEpisode is ExtractorUri,
                    sourcePlugin = sourcePlugin,
                    // Live events have no episode id, so the playable data is the only way to
                    // start them again without loading the provider page.
                    episodeData = (currentEpisode as? ResultEpisode)?.data,
                )
            }
            return
        }

        setViewPos(id, position, duration, sourcePlugin)
        if (id != null) {
            when (val meta = currentEpisode) {
                is ResultEpisode -> {
                    if (meta.videoWatchState == VideoWatchState.Watched) {
                        setVideoWatchState(id, VideoWatchState.None)
                    }
                }
            }
        }

        val percentage = position * 100L / duration
        val nextEp = percentage >= NEXT_WATCH_EPISODE_PERCENTAGE
        val resumeMeta = if (nextEp) nextEpisode else currentEpisode
        if (resumeMeta == null && nextEp) {
            // remove last watched as it is the last episode and you have watched too much
            when (val newMeta = currentEpisode) {
                is ResultEpisode -> {
                    removeLastWatched(newMeta.parentId, sourcePlugin)
                }

                is ExtractorUri -> {
                    removeLastWatched(newMeta.parentId, sourcePlugin)
                }
            }
        } else {
            // save resume
            when (resumeMeta) {
                is ResultEpisode -> {
                    setLastWatched(
                        resumeMeta.parentId,
                        resumeMeta.id,
                        resumeMeta.episode,
                        resumeMeta.season,
                        isFromDownload = false,
                        sourcePlugin = sourcePlugin,
                        episodeData = resumeMeta.data,
                    )
                }

                is ExtractorUri -> {
                    setLastWatched(
                        resumeMeta.parentId,
                        resumeMeta.id,
                        resumeMeta.episode,
                        resumeMeta.season,
                        isFromDownload = true,
                        sourcePlugin = sourcePlugin,
                    )
                }
            }
        }
    }

    fun getViewPos(id: Int?, sourcePlugin: String? = null): PosDur? {
        if (id == null) return null
        val folder = "$currentAccount/$VIDEO_POS_DUR"
        val scope = pluginProgressScope(sourcePlugin)
        if (scope == null) return getKey<PosDur>(folder, id.toString())
        // A known plugin only ever reads its own position, so another plugin playing the
        // same movie cannot show or overwrite this progress.
        getKey<PosDur>(folder, resumeWatchingKey(id.toString(), scope))?.let { return it }
        // The shared position holds downloads, external players and history written before
        // per-plugin progress existed, so it is a legitimate starting point. It is only used
        // while no plugin has its own position for this title though: once any extension has
        // played it, the shared value belongs to that other play and must not leak in here.
        val shared = getKey<PosDur>(folder, id.toString()) ?: return null
        if (hasPluginProgress(id)) return null
        return shared
    }

    /**
     * Whether any extension already saved its own watch position for [id], meaning the title
     * has been played per plugin and the shared position no longer describes it.
     */
    private fun hasPluginProgress(id: Int): Boolean {
        val prefix = "$currentAccount/$VIDEO_POS_DUR/$id|"
        return getKeys("$currentAccount/$VIDEO_POS_DUR")
            ?.any { it.startsWith(prefix) } == true
    }

    /** Removes the saved watch progress for a single episode/movie id. */
    fun removeViewPos(id: Int?, sourcePlugin: String? = null) {
        if (id == null) return
        removeKey("$currentAccount/$VIDEO_POS_DUR", id.toString())
        pluginProgressScope(sourcePlugin)?.let { scope ->
            removeKey(
                "$currentAccount/$VIDEO_POS_DUR",
                resumeWatchingKey(id.toString(), scope),
            )
        }
    }

    /**
     * Removes a Continue watching entry together with its saved watch progress.
     * Any duplicate entry that points to the same title under a different id is
     * removed as well, so hidden duplicates cannot resurface after removal.
     */
    fun removeResumeWatching(
        parentId: Int?,
        episodeId: Int?,
        url: String?,
        sourcePlugin: String? = null,
    ) {
        removeLastWatched(parentId, sourcePlugin)
        removeViewPos(episodeId, sourcePlugin)
        if (url.isNullOrBlank()) return
        getAllResumeStates()?.forEach { entry ->
            if (entry.parentId == parentId) return@forEach
            // Only drop entries of the same plugin: another plugin playing the same
            // movie is a separate card and must not be removed with this one.
            if (entry.sourcePlugin != sourcePlugin) return@forEach
            val headerKey = headerCacheKey(entry.parentId, entry.sourcePlugin)
            val header = getKey<DownloadObjects.DownloadHeaderCached>(
                DOWNLOAD_HEADER_CACHE, headerKey
            ) ?: getKey(DOWNLOAD_HEADER_CACHE_BACKUP, headerKey) ?: return@forEach
            if (header.url == url) {
                removeLastWatched(entry.parentId, entry.sourcePlugin)
                removeViewPos(entry.episodeId, entry.sourcePlugin)
            }
        }
    }

    fun getVideoWatchState(id: Int?): VideoWatchState? {
        if (id == null) return null
        return getKey<VideoWatchState>("$currentAccount/$VIDEO_WATCH_STATE", id.toString(), null)
    }

    fun setVideoWatchState(id: Int?, watchState: VideoWatchState) {
        if (id == null) return
        // None == No key
        if (watchState == VideoWatchState.None) {
            removeKey("$currentAccount/$VIDEO_WATCH_STATE", id.toString())
        } else {
            setKey("$currentAccount/$VIDEO_WATCH_STATE", id.toString(), watchState)
        }
    }

    fun getDub(id: Int): DubStatus? {
        return DubStatus.entries
            .getOrNull(getKey<Int>("$currentAccount/$RESULT_DUB", id.toString(), -1) ?: -1)
    }

    fun setDub(id: Int, status: DubStatus) {
        setKey("$currentAccount/$RESULT_DUB", id.toString(), status.ordinal)
    }

    fun setResultWatchState(id: Int?, status: Int) {
        if (id == null) return
        if (status == WatchType.NONE.internalId) {
            deleteBookmarkedData(id)
        } else {
            setKey("$currentAccount/$RESULT_WATCH_STATE", id.toString(), status)
        }
    }

    fun getResultWatchState(id: Int): WatchType {
        return WatchType.fromInternalId(
            getKey<Int>(
                "$currentAccount/$RESULT_WATCH_STATE",
                id.toString(),
                null,
            )
        )
    }

    fun getResultSeason(id: Int): Int? {
        return getKey<Int>("$currentAccount/$RESULT_SEASON", id.toString(), null)
    }

    fun setResultSeason(id: Int, value: Int?) {
        setKey("$currentAccount/$RESULT_SEASON", id.toString(), value)
    }

    fun getResultEpisode(id: Int): Int? {
        return getKey<Int>("$currentAccount/$RESULT_EPISODE", id.toString(), null)
    }

    fun setResultEpisode(id: Int, value: Int?) {
        setKey("$currentAccount/$RESULT_EPISODE", id.toString(), value)
    }

    fun addSync(id: Int, idPrefix: String, url: String) {
        setKey("${idPrefix}_sync", id.toString(), url)
    }

    fun getSync(id: Int, idPrefixes: List<String>): List<String?> {
        return idPrefixes.map { idPrefix ->
            getKey<String>("${idPrefix}_sync", id.toString())
        }
    }

    var pinnedProviders: Array<String>
        get() {
            val stored = getKey<Array<String>>(USER_PINNED_PROVIDERS) ?: emptyArray<String>()
            // Migrate pins that embed the absolute plugin path (which includes filesDir and
            // therefore the app package/flavour) to a portable path so backups keep working
            // when restored on a different install or build flavour.
            val normalized = stored.map { normalizeProviderPin(it) }
            if (normalized.size != stored.size ||
                normalized.indices.any { normalized[it] != stored[it] }
            ) {
                setKey(USER_PINNED_PROVIDERS, normalized.toTypedArray())
            }
            return normalized.toTypedArray()
        }
        set(value) = setKey(USER_PINNED_PROVIDERS, value)

    /**
     * Builds the storage key for a pinned provider.
     *
     * Built-in providers are stored by name, plugin providers by "name\u0000pluginId"
     * so two plugins exposing the same provider name (e.g. two different
     * repositories both having "Moviebox") can be pinned independently.
     * pluginId is the plugin path relative to the Extensions folder, which is
     * stable across installs/flavours (unlike the absolute path which contains
     * filesDir and the app package).
     */
    fun getProviderPinKey(name: String, sourcePlugin: String?): String {
        return if (sourcePlugin == null) name else "$name\u0000${stablePluginId(sourcePlugin)}"
    }

    /**
     * The app stores online plugin files under `<filesDir>/Extensions/<repo>/<plugin>.cs3`.
     * The part after `Extensions/` is derived from the repository URL and the plugin
     * internal name, so it does not change when the package name, user id or build
     * flavour (all of which change filesDir) differs.
     */
    private fun stablePluginId(sourcePlugin: String): String {
        val marker = "Extensions/"
        val index = sourcePlugin.lastIndexOf(marker)
        return if (index >= 0) sourcePlugin.substring(index + marker.length) else sourcePlugin
    }

    /** Converts a stored pin to the portable "name\u0000pluginId" format. */
    private fun normalizeProviderPin(pin: String): String {
        val separator = pin.indexOf('\u0000')
        if (separator < 0) return pin // legacy name-only pin
        val name = pin.substring(0, separator)
        val sourcePlugin = pin.substring(separator + 1)
        return getProviderPinKey(name, sourcePlugin)
    }

    /**
     * True when a pin entry made by [getProviderPinKey] matches this provider.
     * Matching is exact, so when several plugins expose the same provider name
     * (e.g. two repositories both having "Moviebox") only the actually pinned
     * one counts. Legacy name-only pins are upgraded to exact keys by the
     * provider selector, see HomeFragment.
     */
    fun isProviderPinned(pins: Collection<String>, name: String, sourcePlugin: String?): Boolean {
        return pins.contains(getProviderPinKey(name, sourcePlugin))
    }
}
