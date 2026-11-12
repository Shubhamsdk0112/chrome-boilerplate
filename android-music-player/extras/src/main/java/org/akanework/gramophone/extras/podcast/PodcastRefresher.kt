/*
 *     Copyright (C) 2026 Gramophone extras contributors
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.extras.podcast

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps followed shows current: RSS feeds re-read, YouTube channels asked
 * for new uploads and their avatar. The refresh button runs it for all;
 * Home and the Podcasts screen run it quietly for shows older than six
 * hours, so "New episodes" is fresh without anyone pressing anything.
 */
object PodcastRefresher {

    private const val TAG = "PodcastRefresher"
    const val STALE_MS = 6 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var background: Job? = null

    /** Fire-and-forget refresh of stale shows. */
    fun refreshIfStale(context: Context) {
        if (background?.isActive == true) return
        val app = context.applicationContext
        background = scope.launch { runCatching { refresh(app, onlyStale = true) } }
    }

    /** Refreshes shows; returns how many could not be refreshed. */
    suspend fun refresh(context: Context, onlyStale: Boolean = false): Int = lock.withLock {
        val app = context.applicationContext
        val store = PodcastStore.get(app)
        store.loaded.first { it }
        val now = System.currentTimeMillis()
        var failed = 0
        for (show in store.podcasts.value) {
            val channel = YouTubeChannels.channelId(show.feedUrl)
            val wantsAvatar = channel != null && YouTubeChannels.needsAvatar(show.imageUrl)
            if (onlyStale && now - show.refreshedAt < STALE_MS && !wantsAvatar) continue
            val ok = when {
                show.feedUrl.startsWith(PodcastStore.YOUTUBE_FEED_PREFIX) ->
                    // Shows filed under a channel name (no id from yt-dlp) have no feed to ask.
                    channel == null || YouTubeChannels.refresh(app, show) != null
                show.feedUrl.startsWith("http") ->
                    runCatching { PodcastSearch.fetchFeed(show.feedUrl) }
                        .onSuccess { store.subscribe(it) }
                        .isSuccess
                else -> true
            }
            if (!ok) {
                failed++
                Log.d(TAG, "could not refresh ${show.title}")
            }
        }
        failed
    }
}
