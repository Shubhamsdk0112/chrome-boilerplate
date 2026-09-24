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
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.extras.player.SessionBridge
import java.io.File

/**
 * Plays episodes through Gramophone's own playback service.
 *
 * A [MediaController] to the app's session, so an episode gets the real
 * player: notification, lock screen, headset buttons, speed, the full-screen
 * player sheet. Items carry a URI, which the service's onAddMediaItems passes
 * straight through.
 *
 * Playback position is written back to [PodcastStore] every few seconds
 * while an episode is the current item, and once more when it pauses, so a
 * 2-hour episode picks up where it left off.
 */
object PodcastPlayer {

    private const val TAG = "PodcastPlayer"
    private const val ID_PREFIX = "podcast:"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private var tracking: Job? = null

    /** guid of the episode the player currently holds, or null. */
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Podcast playback speed, remembered separately from music (which stays at 1×). */
    val SPEEDS = listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 0.8f)
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()
    private var speedLoaded = false
    /** We changed the player's speed for an episode and owe music a reset to 1×. */
    private var speedIsOurs = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("extras_podcasts", Context.MODE_PRIVATE)

    private suspend fun loadSpeed(app: Context) {
        if (speedLoaded) return
        _speed.value = withContext(Dispatchers.IO) { prefs(app).getFloat("speed", 1f) }
        speedLoaded = true
    }

    /** Next speed in [SPEEDS]; applied now if an episode is playing. */
    fun cycleSpeed(context: Context) {
        val app = context.applicationContext
        val next = SPEEDS[(SPEEDS.indexOf(_speed.value).coerceAtLeast(0) + 1) % SPEEDS.size]
        _speed.value = next
        scope.launch(Dispatchers.IO) { prefs(app).edit().putFloat("speed", next).apply() }
        val c = controller ?: return
        if (c.currentMediaItem?.mediaId?.startsWith(ID_PREFIX) == true) {
            c.setPlaybackSpeed(next)
            speedIsOurs = next != 1f
        }
    }

    /** The episode object behind [current], for chapter lookups. */
    private val _currentEpisode = MutableStateFlow<Episode?>(null)
    val currentEpisode: StateFlow<Episode?> = _currentEpisode.asStateFlow()

    /** Finds an episode by guid across subscriptions and plays it. */
    fun playGuid(context: Context, guid: String) {
        val app = context.applicationContext
        scope.launch {
            val store = PodcastStore.get(app)
            store.loaded.first { it }
            val episode = store.episode(guid) ?: return@launch
            val podcast = store.podcast(episode.feedUrl) ?: return@launch
            play(app, podcast, episode)
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
    }

    /** Jump to the previous/next chapter boundary of the current episode. */
    fun skipChapter(forward: Boolean) {
        val c = controller ?: return
        val episode = _currentEpisode.value ?: return
        if (episode.chapters.isEmpty()) return
        val pos = c.currentPosition
        val target = if (forward) {
            episode.chapters.firstOrNull { it.startMs > pos + 500 }?.startMs
        } else {
            // Back to the start of this chapter, or the previous one if we are
            // already near its start — the usual player convention.
            val current = episode.chapterAt(pos)
            if (current != null && pos - current.startMs > 3_000) current.startMs
            else episode.chapters.lastOrNull { it.startMs < (current?.startMs ?: pos) }?.startMs ?: 0L
        }
        if (target != null) c.seekTo(target)
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (!isPlaying) savePositionNow()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val isEpisode = mediaItem?.mediaId?.startsWith(ID_PREFIX) == true
            _current.value = mediaItem?.mediaId?.removePrefix(ID_PREFIX)?.takeIf { isEpisode }
            // Speed follows the item: episodes get the podcast speed, and a
            // song after an episode goes back to normal — but only if it was
            // us who changed it, so a speed set in the full player for music
            // is left alone.
            val c = controller ?: return
            if (isEpisode && _speed.value != 1f) {
                c.setPlaybackSpeed(_speed.value)
                speedIsOurs = true
            } else if (!isEpisode && speedIsOurs) {
                c.setPlaybackSpeed(1f)
                speedIsOurs = false
            }
        }
    }

    /**
     * Starts [episode] from its saved position. A downloaded file is used when
     * there is one; otherwise the episode streams from its feed URL.
     */
    fun play(context: Context, podcast: Podcast, episode: Episode, startMs: Long? = null) {
        val app = context.applicationContext
        scope.launch {
            val c = runCatching { connect(app) }
                .onFailure { Log.w(TAG, "could not connect to the player", it) }
                .getOrNull() ?: return@launch
            val store = PodcastStore.get(app)
            // Disk work off the main thread: the file check, and the cover,
            // which the player's image loader can only read from disk (it is
            // deliberately offline), so it gets the cached copy as a file.
            val (local, cover) = withContext(Dispatchers.IO) {
                val file = store.downloadedFile(episode.guid)
                val art = (episode.imageUrl ?: podcast.imageUrl)?.let { ImageCache.file(app, it) }
                file to art
            }
            val uri = local?.let(Uri::fromFile) ?: Uri.parse(episode.audioUrl)
            val item = MediaItem.Builder()
                .setMediaId(ID_PREFIX + episode.guid)
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(episode.title)
                        .setArtist(podcast.title)
                        .setAlbumTitle(podcast.title)
                        .setAlbumArtist(podcast.author)
                        .setArtworkUri(cover?.let(Uri::fromFile))
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build(),
                )
                .build()
            val position = startMs ?: store.position(episode.guid)
            _current.value = episode.guid
            _currentEpisode.value = episode
            c.setMediaItem(item, position)
            c.prepare()
            loadSpeed(app)
            c.setPlaybackSpeed(_speed.value)
            speedIsOurs = _speed.value != 1f
            c.play()
            startTracking(app)
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekBy(deltaMs: Long) {
        val c = controller ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceIn(0, c.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
    }

    /** Position of the current episode, ms, or null when nothing of ours is loaded. */
    fun currentPositionMs(): Long? {
        val c = controller ?: return null
        if (c.currentMediaItem?.mediaId?.startsWith(ID_PREFIX) != true) return null
        return c.currentPosition
    }

    fun currentDurationMs(): Long? = controller?.duration?.takeIf { it > 0 }

    // ------------------------------------------------------------------

    private suspend fun connect(app: Context): MediaController {
        SessionBridge.addListener(listener)
        val built = SessionBridge.controller(app)
        controller = built
        return built
    }

    private fun startTracking(app: Context) {
        tracking?.cancel()
        tracking = scope.launch {
            val store = PodcastStore.get(app)
            while (isActive) {
                delay(3_000)
                val c = controller ?: break
                val id = c.currentMediaItem?.mediaId ?: continue
                if (!id.startsWith(ID_PREFIX)) break
                if (c.isPlaying) store.savePosition(id.removePrefix(ID_PREFIX), c.currentPosition)
            }
        }
    }

    private fun savePositionNow() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId ?: return
        if (!id.startsWith(ID_PREFIX)) return
        val app = appContext ?: return
        PodcastStore.get(app).savePosition(id.removePrefix(ID_PREFIX), c.currentPosition)
    }

    private var appContext: Context? = null

    /** Called once from the podcast screen so pause events can be saved. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** Whether [file] is what the player is currently playing. */
    fun isPlayingFile(file: File): Boolean =
        controller?.currentMediaItem?.localConfiguration?.uri == Uri.fromFile(file)
}
