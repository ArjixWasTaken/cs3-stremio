package com.lagradost.cloudstream3.ui.search

import android.app.Activity
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup.handleDownloadClick
import com.lagradost.cloudstream3.ui.download.DownloadClickEvent
import com.lagradost.cloudstream3.ui.result.START_ACTION_LOAD_EP
import com.lagradost.cloudstream3.utils.AppUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.VideoDownloadHelper

object SearchHelper {
    fun handleSearchClickCallback(activity: Activity?, callback: SearchClickCallback) {
        val card = callback.card
        when (callback.action) {
            SEARCH_ACTION_LOAD -> {
                activity.loadSearchResult(card)
            }
            SEARCH_ACTION_PLAY_FILE -> {
                if (card is DataStoreHelper.ResumeWatchingResult && card.id != null) {
                    if (card.isFromDownload) {
                        handleDownloadClick(
                            activity, card.name, DownloadClickEvent(
                                DOWNLOAD_ACTION_PLAY_FILE,
                                VideoDownloadHelper.DownloadEpisodeCached(
                                    card.name,
                                    card.posterUrl,
                                    card.episode ?: 0,
                                    card.season,
                                    card.id!!,
                                    card.parentId ?: return,
                                    null,
                                    null,
                                    System.currentTimeMillis()
                                )
                            )
                        )
                    } else {
                        activity.loadSearchResult(card, START_ACTION_LOAD_EP, card.id!!)
                    }
                } else {
                    handleSearchClickCallback(
                        activity,
                        SearchClickCallback(SEARCH_ACTION_LOAD, callback.view, -1, callback.card)
                    )
                }
            }
            SEARCH_ACTION_SHOW_METADATA -> {
                showToast(activity, callback.card.name, Toast.LENGTH_SHORT)
            }
        }
    }
}