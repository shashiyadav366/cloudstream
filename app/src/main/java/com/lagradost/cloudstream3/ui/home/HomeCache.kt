package com.lagradost.cloudstream3.ui.home

import android.content.Context
import android.system.Os
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TorrentSearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.getSourcePlugin
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.setSourcePlugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJsonLiteral
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Remembers the last successful homepage of every provider on disk.
 *
 * A cold start can show the previous rows immediately and then let the provider
 * refresh them in the background. The cache is per provider key, so switching to
 * another plugin never shows the rows of the plugin that was open before it.
 *
 * Only the card fields the home rows display/need for opening are stored. Plugin
 * specific [SearchResponse] subclasses are rebuilt as one of the built-in response
 * types on read, which also keeps the cache independent of extension versions.
 */
object HomeCache {
    private const val CACHE_VERSION = 1
    private const val CACHE_FOLDER = "home_pages"

    /**
     * A user can install many extensions. Keep the cache bounded so old plugins
     * cannot fill the disk: at most [MAX_CACHED_PROVIDERS] files or
     * [MAX_CACHE_BYTES] in total; the least recently written entries go first.
     */
    private const val MAX_CACHED_PROVIDERS = 16
    private const val MAX_CACHE_BYTES = 8L * 1024 * 1024

    private val memory = ConcurrentHashMap<String, CachedHome>()

    /**
     * Last access time per memory entry. [CachedHome.savedAt] only says when it
     * was written, so a separate map is needed for real access-order eviction.
     */
    private val memoryAccess = ConcurrentHashMap<String, Long>()

    /**
     * Serializes reads with the memory/disk side of commits and removals. Without
     * this, a read could load an old disk entry and then re-publish it to memory
     * after a newer write or remove had already completed.
     */
    private val diskLock = Mutex()

    /**
     * Shared gate for request invalidation and the final cache commit.
     *
     * HomeViewModel increments its request generation under this same lock, and
     * the final shouldCommit() check plus rename/delete run inside it. Once a
     * newer generation has been assigned, an older request can no longer commit.
     * A commit that already holds the gate may finish first; that is ordered
     * before the newer generation, which then owns any subsequent write.
     *
     * It is a plain JVM lock because the generation bump in loadAndCancel() must
     * happen synchronously, without suspension.
     */
    private val commitGate = ReentrantLock()

    /** Runs [block] under the same gate used for the final cache commit. */
    fun <T> withCommitGate(block: () -> T): T = commitGate.withLock { block() }

    private fun touch(context: Context, providerKey: String) {
        runCatching {
            fileFor(context, providerKey).setLastModified(System.currentTimeMillis())
        }
    }

    /**
     * Freshness buckets used by the home screen:
     * - under [FRESH_MS]: serve normally from cache
     * - [FRESH_MS]..[EXPIRED_MS]: serve immediately, refresh behind
     * - over [EXPIRED_MS]: do not show it unless the refresh fails
     *
     * Starting values, not universal values; tune from real usage.
     */
    private const val FRESH_MS = 6 * 60 * 60 * 1000L
    private const val EXPIRED_MS = 24 * 60 * 60 * 1000L

    data class CachedHomeResult(
        val pages: List<HomePageResponse?>,
        val savedAt: Long,
    ) {
        val ageMs: Long get() = (System.currentTimeMillis() - savedAt).coerceAtLeast(0L)
        val isFresh: Boolean get() = ageMs < FRESH_MS
        val isExpired: Boolean get() = ageMs >= EXPIRED_MS
    }

    /**
     * Returns the cached home page for [providerKey], or null when this provider
     * has never completed a successful load.
     */
    suspend fun read(context: Context, providerKey: String): CachedHomeResult? =
        withContext(Dispatchers.IO) {
            diskLock.withLock {
                val cached = memory[providerKey] ?: readFromDisk(context, providerKey)
                cached?.let { entry ->
                    memory[providerKey] = entry
                    memoryAccess[providerKey] = System.currentTimeMillis()
                    trimMemory()
                    // Reading is an access: keep the eviction policy close to LRU
                    // instead of expiring by the last time the provider was loaded.
                    touch(context, providerKey)
                    CachedHomeResult(
                        pages = entry.toHomePageResponses(),
                        savedAt = entry.savedAt,
                    )
                }
            }
        }

    /** Saves a successful, non-empty homepage for [providerKey]. */
    suspend fun write(
        context: Context,
        providerKey: String,
        pages: List<HomePageResponse?>,
        shouldCommit: () -> Boolean = { true },
    ) = withContext(Dispatchers.IO) {
        if (pages.none { it?.items?.isNotEmpty() == true }) {
            remove(context, providerKey, shouldCommit)
            return@withContext
        }

        val entry = CachedHome(
            version = CACHE_VERSION,
            savedAt = System.currentTimeMillis(),
            pages = pages.mapNotNull { it?.toCachedPage() },
        )

        val file = fileFor(context, providerKey)
        val temp = try {
            file.parentFile?.mkdirs()
            File.createTempFile("${file.name}.", ".tmp", file.parentFile)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            logError(t)
            return@withContext
        }

        try {
            // Write outside the commit gate so a large homepage does not block
            // request invalidation. The temporary file has a unique name.
            temp.writeText(entry.toJsonLiteral())
        } catch (t: Throwable) {
            runCatching { temp.delete() }
            if (t is CancellationException) throw t
            logError(t)
            return@withContext
        }

        diskLock.withLock {
            commitGate.withLock {
                // A newer request may have started while this response was in
                // flight or while the temp file was written. The generation is
                // bumped under this same gate, so once a newer generation exists
                // this stale response cannot replace a newer cache entry.
                if (!shouldCommit()) {
                    runCatching { temp.delete() }
                } else {
                    val renamed = try {
                        // Atomic replacement: the previous valid cache is
                        // untouched until this rename succeeds.
                        Os.rename(temp.absolutePath, file.absolutePath)
                        true
                    } catch (t: Throwable) {
                        runCatching { temp.delete() }
                        if (t is CancellationException) throw t
                        logError(t)
                        false
                    }

                    if (renamed) {
                        // The temp file's mtime reflects when it was written, not
                        // when it won the commit. Stamp the committed file so the
                        // LRU trim treats it as the newest entry.
                        runCatching { file.setLastModified(System.currentTimeMillis()) }

                        // Only update memory after the disk commit succeeded, so
                        // memory and disk never disagree about which request won.
                        memory[providerKey] = entry
                        memoryAccess[providerKey] = System.currentTimeMillis()
                        trimMemory()
                    }
                }
            }

            // Trim under diskLock so it cannot race with a read or delete a file
            // that another write has just committed. Temp files are not .json and
            // are ignored by trimDisk.
            trimDisk(context)
        }
    }

    /** Drops the cached page of [providerKey], if any. */
    suspend fun remove(
        context: Context,
        providerKey: String,
        shouldCommit: () -> Boolean = { true },
    ) = withContext(Dispatchers.IO) {
        diskLock.withLock {
            commitGate.withLock {
                if (shouldCommit()) {
                    memory.remove(providerKey)
                    memoryAccess.remove(providerKey)
                    runCatching { fileFor(context, providerKey).delete() }
                        .onFailure { logError(it) }
                }
            }
        }
        Unit
    }

    private fun trimMemory() {
        if (memory.size <= MAX_CACHED_PROVIDERS) return
        memory.entries
            .sortedBy { entry ->
                memoryAccess[entry.key] ?: entry.value.savedAt
            }
            .take(memory.size - MAX_CACHED_PROVIDERS)
            .forEach { entry ->
                memory.remove(entry.key)
                memoryAccess.remove(entry.key)
            }
    }

    private fun trimDisk(context: Context) {
        runCatching {
            val files = File(context.cacheDir, CACHE_FOLDER)
                .listFiles { file -> file.isFile && file.name.endsWith(".json") }
                ?.toList()
                .orEmpty()
            if (files.isEmpty()) return

            var count = files.size
            var totalBytes = files.sumOf { it.length() }
            files.sortedBy { it.lastModified() }.forEach { file ->
                if (count <= MAX_CACHED_PROVIDERS && totalBytes <= MAX_CACHE_BYTES) {
                    return@forEach
                }
                val length = file.length()
                if (file.delete()) {
                    count--
                    totalBytes -= length
                }
            }
        }.onFailure { logError(it) }
    }


    private fun fileFor(context: Context, providerKey: String): File {
        // Keep the name readable while the hash guarantees uniqueness when two
        // provider names differ only by characters that are not file safe.
        val safeName = providerKey
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(80)
        val suffix = Integer.toHexString(providerKey.hashCode())
        return File(File(context.cacheDir, CACHE_FOLDER), "${safeName}_$suffix.json")
    }

    private fun readFromDisk(context: Context, providerKey: String): CachedHome? {
        return runCatching {
            val file = fileFor(context, providerKey)
            if (!file.isFile) return null
            val entry = parseJson<CachedHome>(file.readText())
            // A format change must never break the home screen; just refetch.
            entry.takeIf { it.version == CACHE_VERSION }
        }.onFailure { logError(it) }.getOrNull()
    }

    private fun HomePageResponse.toCachedPage() = CachedHomePage(
        items = items.map { list ->
            CachedHomeSection(
                name = list.name,
                isHorizontalImages = list.isHorizontalImages,
                list = list.list.mapNotNull { it.toCachedCard() },
            )
        },
        hasNext = hasNext,
    )

    private fun CachedHome.toHomePageResponses(): List<HomePageResponse?> =
        pages.map { page ->
            newHomePageResponse(
                page.items.map { section ->
                    HomePageList(
                        name = section.name,
                        list = section.list.mapNotNull { it.toSearchResponse() },
                        isHorizontalImages = section.isHorizontalImages,
                    )
                },
                page.hasNext,
            )
        }

    private fun SearchResponse.toCachedCard() = CachedCard(
        name = name,
        url = url,
        apiName = apiName,
        type = type?.name,
        posterUrl = posterUrl,
        posterHeaders = posterHeaders,
        id = id,
        quality = quality?.name,
        score = score,
        sourcePlugin = getSourcePlugin(),
        year = (this as? MovieSearchResponse)?.year
            ?: (this as? TvSeriesSearchResponse)?.year,
        episodes = (this as? TvSeriesSearchResponse)?.episodes,
        lang = (this as? LiveSearchResponse)?.lang,
        dubStatus = (this as? AnimeSearchResponse)?.dubStatus?.map { it.name },
        dubEpisodes = (this as? AnimeSearchResponse)?.episodes
            ?.mapKeys { it.key.name }
            ?.mapValues { it.value },
    )

    @Suppress("DEPRECATION_ERROR")
    private fun CachedCard.toSearchResponse(): SearchResponse {
        val tvType = type?.let { runCatching { TvType.valueOf(it) }.getOrNull() }
        val searchQuality = quality?.let {
            runCatching { SearchQuality.valueOf(it) }.getOrNull()
        }
        val dubs = dubStatus?.mapNotNull {
            runCatching { DubStatus.valueOf(it) }.getOrNull()
        }?.toMutableSet()
        val dubEpisodesMap = dubEpisodes?.entries?.mapNotNull { (key, value) ->
            runCatching { DubStatus.valueOf(key) }.getOrNull()?.let { it to value }
        }?.toMap()?.toMutableMap()

        val response: SearchResponse = when {
            tvType == TvType.Live -> LiveSearchResponse(
                name = name,
                url = url,
                apiName = apiName,
                type = tvType,
                posterUrl = posterUrl,
                id = id,
                quality = searchQuality,
                posterHeaders = posterHeaders,
                lang = lang,
                score = score,
            )

            tvType == TvType.Torrent -> TorrentSearchResponse(
                name = name,
                url = url,
                apiName = apiName,
                type = tvType,
                posterUrl = posterUrl,
                id = id,
                quality = searchQuality,
                posterHeaders = posterHeaders,
                score = score,
            )

            dubs != null || dubEpisodesMap != null ->
                AnimeSearchResponse(
                    name = name,
                    url = url,
                    apiName = apiName,
                    type = tvType,
                    posterUrl = posterUrl,
                    year = year,
                    dubStatus = dubs,
                    episodes = dubEpisodesMap ?: mutableMapOf(),
                    id = id,
                    quality = searchQuality,
                    posterHeaders = posterHeaders,
                    score = score,
                )

            tvType?.isMovieType() == true -> MovieSearchResponse(
                name = name,
                url = url,
                apiName = apiName,
                type = tvType,
                posterUrl = posterUrl,
                year = year,
                id = id,
                quality = searchQuality,
                posterHeaders = posterHeaders,
                score = score,
            )

            else -> TvSeriesSearchResponse(
                name = name,
                url = url,
                apiName = apiName,
                type = tvType,
                posterUrl = posterUrl,
                year = year,
                episodes = episodes,
                id = id,
                quality = searchQuality,
                posterHeaders = posterHeaders,
                score = score,
            )
        }
        return response.apply { setSourcePlugin(sourcePlugin) }
    }
}

@Serializable
private data class CachedHome(
    val version: Int,
    val savedAt: Long,
    val pages: List<CachedHomePage>,
)

@Serializable
private data class CachedHomePage(
    val items: List<CachedHomeSection>,
    val hasNext: Boolean,
)

@Serializable
private data class CachedHomeSection(
    val name: String,
    val isHorizontalImages: Boolean,
    val list: List<CachedCard>,
)

@Serializable
private data class CachedCard(
    val name: String,
    val url: String,
    val apiName: String,
    val type: String? = null,
    val posterUrl: String? = null,
    val posterHeaders: Map<String, String>? = null,
    val id: Int? = null,
    val quality: String? = null,
    val score: Score? = null,
    val sourcePlugin: String? = null,
    val year: Int? = null,
    val episodes: Int? = null,
    val lang: String? = null,
    val dubStatus: List<String>? = null,
    val dubEpisodes: Map<String, Int>? = null,
)
