package com.lagradost.cloudstream3.ui.download

import android.content.DialogInterface
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.player.DownloadFileGenerator
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.utils.AppContextUtils.getNameFull
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DOWNLOAD_HEADER_CACHE
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.SnackbarHelper.showSnackbar
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import kotlinx.coroutines.MainScope

object DownloadButtonSetup {
    fun handleDownloadClick(click: DownloadClickEvent) {
        val id = click.data.id
        when (click.action) {
            DOWNLOAD_ACTION_DELETE_FILE -> {
                activity?.let { ctx ->
                    val builder: AlertDialog.Builder = AlertDialog.Builder(ctx)
                    val dialogClickListener =
                        DialogInterface.OnClickListener { _, which ->
                            when (which) {
                                DialogInterface.BUTTON_POSITIVE -> {
                                    VideoDownloadManager.deleteFilesAndUpdateSettings(
                                        ctx,
                                        setOf(id),
                                        MainScope()
                                    )
                                }

                                DialogInterface.BUTTON_NEGATIVE -> {
                                    // Do nothing on cancel
                                }
                            }
                        }

                    try {
                        builder.setTitle(R.string.delete_file)
                            .setMessage(
                                ctx.getString(R.string.delete_message).format(
                                    ctx.getNameFull(
                                        click.data.name,
                                        click.data.episode,
                                        click.data.season
                                    )
                                )
                            )
                            .setPositiveButton(R.string.delete, dialogClickListener)
                            .setNegativeButton(R.string.cancel, dialogClickListener)
                            .show().setDefaultFocus()
                    } catch (e: Exception) {
                        logError(e)
                        // ye you somehow fucked up formatting did you?
                    }
                }
            }

            DOWNLOAD_ACTION_PAUSE_DOWNLOAD -> {
                VideoDownloadManager.downloadEvent.invoke(
                    Pair(click.data.id, VideoDownloadManager.DownloadActionType.Pause)
                )
            }

            DOWNLOAD_ACTION_RESUME_DOWNLOAD -> {
                activity?.let { ctx ->
                    if (VideoDownloadManager.downloadStatus.containsKey(id) && VideoDownloadManager.downloadStatus[id] == VideoDownloadManager.DownloadType.IsPaused) {
                        VideoDownloadManager.downloadEvent.invoke(
                            Pair(click.data.id, VideoDownloadManager.DownloadActionType.Resume)
                        )
                    } else {
                        val pkg = VideoDownloadManager.getDownloadResumePackage(ctx, id)
                        if (pkg != null) {
                            DownloadQueueManager.addToQueue(pkg.toWrapper())
                        } else {
                            VideoDownloadManager.downloadEvent.invoke(
                                Pair(click.data.id, VideoDownloadManager.DownloadActionType.Resume)
                            )
                        }
                    }
                }
            }

            DOWNLOAD_ACTION_LONG_CLICK -> {
                activity?.let { act ->
                    val length =
                        VideoDownloadManager.getDownloadFileInfo(
                            act,
                            click.data.id
                        )?.fileLength
                            ?: 0
                    if (length > 0) {
                        showSnackbar(
                            act,
                            R.string.offline_file,
                            Snackbar.LENGTH_LONG
                        )
                    }
                }
            }

            DOWNLOAD_ACTION_CANCEL_PENDING -> {
                DownloadQueueManager.cancelDownload(id)
            }

            DOWNLOAD_ACTION_PLAY_FILE -> {
                playDownloadedEpisode(click.data, sourcePlugin = click.data.sourcePlugin)
            }
        }
    }

    /**
     * True when the download exists and has finished writing. Mirrors the completion check
     * used by the download button, including its 1 KiB padding for servers that report a
     * slightly inaccurate content length.
     */
    fun isDownloadComplete(context: android.content.Context, id: Int): Boolean {
        return VideoDownloadManager.isDownloadComplete(context, id)
    }

    fun getDownloadHeader(
        parentId: Int,
        sourcePlugin: String? = null,
    ): DownloadObjects.DownloadHeaderCached? {
        if (sourcePlugin != null) {
            val scopedKey = DataStoreHelper.headerCacheKey(parentId, sourcePlugin)
            getKey<DownloadObjects.DownloadHeaderCached>(DOWNLOAD_HEADER_CACHE, scopedKey)?.let { return it }
            getKey<DownloadObjects.DownloadHeaderCached>(
                DOWNLOAD_HEADER_CACHE,
                parentId.toString()
            )?.takeIf { it.sourcePlugin == null || it.sourcePlugin == sourcePlugin }?.let { return it }
            return null
        }

        return getKey<DownloadObjects.DownloadHeaderCached>(
            DOWNLOAD_HEADER_CACHE,
            parentId.toString()
        )
            ?: getKeys(DOWNLOAD_HEADER_CACHE).orEmpty()
                .mapNotNull { getKey<DownloadObjects.DownloadHeaderCached>(it) }
                .filter { it.id == parentId }
                .maxByOrNull { it.cacheTime }
    }

    /**
     * Plays a completed download locally instead of streaming it. Returns false when there
     * is no completed file (or no usable header), so callers can fall back to streaming.
     */
    fun playDownloadedEpisode(
        episode: DownloadObjects.DownloadEpisodeCached,
        incognito: Boolean = false,
        sourcePlugin: String? = null,
    ): Boolean {
        val act = activity ?: return false
        if (!isDownloadComplete(act, episode.id)) return false

        val requestedSourcePlugin = sourcePlugin ?: episode.sourcePlugin
        val resolvedSourcePlugin = requestedSourcePlugin ?: resolveSourcePlugin(episode)
        val parent = getDownloadHeader(episode.parentId, resolvedSourcePlugin) ?: return false
        if (resolvedSourcePlugin != null &&
            parent.sourcePlugin != null &&
            parent.sourcePlugin != resolvedSourcePlugin
        ) {
            return false
        }

        val effectiveSourcePlugin = resolvedSourcePlugin ?: parent.sourcePlugin
        val episodes = getKeys(DOWNLOAD_EPISODE_CACHE).orEmpty()
            .mapNotNull { getKey<DownloadObjects.DownloadEpisodeCached>(it) }
            .filter { it.parentId == episode.parentId }
            .filter { cached ->
                when {
                    effectiveSourcePlugin != null -> cached.sourcePlugin == null || cached.sourcePlugin == effectiveSourcePlugin
                    else -> cached.sourcePlugin == null
                }
            }
            .sortedWith(compareBy<DownloadObjects.DownloadEpisodeCached> {
                it.season ?: 0
            }.thenBy { it.episode })

        val items = episodes.mapNotNull { cached ->
            // Download info/progress is keyed by episode id; when provider identity is unknown
            // for legacy metadata, avoid assuming global uniqueness across providers.
            if (effectiveSourcePlugin == null && cached.id != episode.id && cached.sourcePlugin == null) {
                return@mapNotNull null
            }
            val keyInfo = getKey<DownloadObjects.DownloadedFileInfo>(
                VideoDownloadManager.KEY_DOWNLOAD_INFO,
                cached.id.toString()
            ) ?: return@mapNotNull null

            val itemSourcePlugin = cached.sourcePlugin ?: effectiveSourcePlugin
            ExtractorUri(
                // We just use a temporary placeholder for the URI,
                // it will be updated in generateLinks().
                // We just do this for performance since getting
                // all paths at once can be quite expensive.
                uri = Uri.EMPTY,
                id = cached.id,
                parentId = cached.parentId,
                name = cached.name ?: act.getString(R.string.downloaded_file),
                season = cached.season,
                episode = cached.episode,
                headerName = parent.name,
                tvType = parent.type,
                basePath = keyInfo.basePath,
                displayName = keyInfo.displayName,
                relativePath = keyInfo.relativePath,
                sourcePlugin = itemSourcePlugin,
            )
        }

        val index = items.indexOfFirst { it.id == episode.id }
        if (index < 0) return false

        act.navigate(
            R.id.global_to_navigation_player, GeneratorPlayer.newInstance(
                DownloadFileGenerator(items),
                index,
                incognito = incognito,
            )
        )
        return true
    }

    private fun resolveSourcePlugin(episode: DownloadObjects.DownloadEpisodeCached): String? {
        val resumePlugin = DataStoreHelper.getAllResumeStates()
            ?.filter { it.parentId == episode.parentId && it.episodeId == episode.id }
            ?.sortedByDescending { it.updateTime }
            ?.firstOrNull()
            ?.sourcePlugin
        if (resumePlugin != null) return resumePlugin

        return getMatchingHeaderSourcePlugin(episode)
    }

    private fun getMatchingHeaderSourcePlugin(
        episode: DownloadObjects.DownloadEpisodeCached,
    ): String? {
        val headers = getKeys(DOWNLOAD_HEADER_CACHE).orEmpty()
            .mapNotNull { getKey<DownloadObjects.DownloadHeaderCached>(it) }
            .filter { it.id == episode.parentId }
        if (headers.isEmpty()) return null

        val withPlugin = headers.mapNotNull { it.sourcePlugin }.distinct()
        if (withPlugin.size == 1) return withPlugin.single()

        val resumePluginsForEpisode = DataStoreHelper.getAllResumeStates()
            ?.filter { it.parentId == episode.parentId && it.episodeId == episode.id }
            ?.mapNotNull { it.sourcePlugin }
            ?.distinct()
            .orEmpty()
        if (resumePluginsForEpisode.size == 1) {
            val candidate = resumePluginsForEpisode.single()
            if (headers.any { it.sourcePlugin == candidate }) return candidate
        }

        return null
    }
}