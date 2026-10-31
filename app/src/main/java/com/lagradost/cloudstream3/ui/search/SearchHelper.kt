package com.lagradost.cloudstream3.ui.search

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.isLiveStream
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup.handleDownloadClick
import com.lagradost.cloudstream3.ui.download.DownloadClickEvent
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.RepoLinkGenerator
import com.lagradost.cloudstream3.ui.result.START_ACTION_LOAD_EP
import com.lagradost.cloudstream3.ui.result.START_ACTION_RESUME_LATEST
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects

object SearchHelper {
    /**
     * Starts a Continue Watching entry directly in the player, skipping the provider page
     * load that used to happen first.
     *
     * Only movies and live events take this path. Series keep the result page because the
     * episode list is what advances Continue Watching to the next episode and enables the
     * next/previous episode controls inside the player.
     *
     * @return true when the player was opened, false when the caller should use the regular
     * result page path instead (missing playable data, unknown plugin, or downloads).
     */
    private fun playResumeCardDirectly(card: DataStoreHelper.ResumeWatchingResult): Boolean {
        val episodeData = card.episodeData?.takeIf { it.isNotBlank() } ?: return false
        val tvType = card.type ?: return false
        if (!tvType.isMovieType() && !tvType.isLiveStream()) return false
        // Live events must open the original card way: load the provider page fresh so the
        // playable URL/session is current. Reusing stored episodeData after it expires
        // (e.g. 20+ minutes later) ends in a long load and "no link found". Movies keep
        // the direct path since their data does not expire.
        if (tvType.isLiveStream()) return false

        val generator = RepoLinkGenerator.fromResume(
            episodeData = episodeData,
            apiName = card.apiName,
            sourcePlugin = card.sourcePlugin,
            id = card.id,
            parentId = card.parentId,
            episode = card.episode,
            season = card.season,
            tvType = tvType,
            name = card.name,
            poster = card.posterUrl,
        ) ?: return false

        val act = activity as? FragmentActivity ?: return false
        act.navigate(
            R.id.global_to_navigation_player,
            GeneratorPlayer.newInstance(generator, 0),
        )
        return true
    }

    fun handleSearchClickCallback(callback: SearchClickCallback) {
        val card = callback.card
        when (callback.action) {
            SEARCH_ACTION_LOAD -> {
                loadSearchResult(card)
            }

            SEARCH_ACTION_PLAY_FILE -> {
                if (card is DataStoreHelper.ResumeWatchingResult) {
                    // The entry still knows the plugin's playable data, so open the player
                    // straight away instead of loading the provider page and navigating
                    // through it first. Falls through to the normal path when unavailable.
                    if (!card.isFromDownload && playResumeCardDirectly(card)) return

                    val id = card.id
                    if (id == null && !card.isFromDownload) {
                        // Live streams (and migrated history) have no saved episode id,
                        // so resume from the latest entry to auto start playback
                        loadSearchResult(card, START_ACTION_RESUME_LATEST)
                    } else if (id == null) {
                        showToast(R.string.error_invalid_id, Toast.LENGTH_SHORT)
                    } else {
                        if (card.isFromDownload) {
                            handleDownloadClick(
                                DownloadClickEvent(
                                    DOWNLOAD_ACTION_PLAY_FILE,
                                    DownloadObjects.DownloadEpisodeCached(
                                        name = card.name,
                                        poster = card.posterUrl,
                                        episode = card.episode ?: 0,
                                        season = card.season,
                                        id = id,
                                        parentId = card.parentId ?: return,
                                        score = null,
                                        description = null,
                                        cacheTime = System.currentTimeMillis(),
                                    )
                                )
                            )
                        } else {
                            loadSearchResult(card, START_ACTION_LOAD_EP, id)
                        }
                    }
                } else {
                    handleSearchClickCallback(
                        SearchClickCallback(SEARCH_ACTION_LOAD, callback.view, -1, callback.card)
                    )
                }
            }

            SEARCH_ACTION_SHOW_METADATA -> {
                (activity as? MainActivity?)?.apply {
                    loadPopup(callback.card)
                } ?: kotlin.run {
                    showToast(callback.card.name, Toast.LENGTH_SHORT)
                }
            }
        }
    }
}