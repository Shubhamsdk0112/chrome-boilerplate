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

package org.akanework.gramophone.extras.history

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.extras.player.SessionBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One remembered song: what it was, how often, and when last. */
data class HistoryEntry(
    val mediaId: String,
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val artworkUri: String?,
    val durationMs: Long,
    val playCount: Int,
    val lastPlayedAt: Long,
) {
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(Uri.parse(uri))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artworkUri?.let(Uri::parse))
                .setDurationMs(durationMs.takeIf { it > 0 })
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build(),
        )
        .build()
}

/**
 * What the player has actually played, so the app has a memory: recently
 * played and most played.
 *
 * Observes the session through [SessionBridge]. A song counts as played
 * once it has been heard for 30 seconds (or half its length, whichever is
 * shorter) in one sitting — skipping through the library does not inflate
 * the counts. Podcast episodes are left to their own store.
 *
 * Persisted as one JSON file, newest 500 songs, written on IO.
 */
object ListeningHistory {

    private const val TAG = "ListeningHistory"
    private const val FILE = "extras_history.json"
    private const val MAX_ENTRIES = 500
    private const val COUNT_AFTER_MS = 30_000L
    private const val POLL_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var app: Context? = null
    private var poll: Job? = null
    private var save: Job? = null

    private val _entries = MutableStateFlow<Map<String, HistoryEntry>>(emptyMap())
    val entries: StateFlow<Map<String, HistoryEntry>> = _entries.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    // The sitting in progress.
    private var currentId: String? = null
    private var heardMs = 0L
    private var counted = false

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentId = mediaItem?.mediaId?.takeIf { it.startsWith("MediaStore:") }
            heardMs = 0
            counted = false
        }
    }

    /** Called once from Application.onCreate; connects lazily and cheaply. */
    fun start(context: Context) {
        val application = context.applicationContext
        app = application
        scope.launch {
            withContext(Dispatchers.IO) { load(application) }
            _loaded.value = true
            SessionBridge.addListener(listener)
            runCatching { SessionBridge.controller(application) }
                .onFailure { Log.w(TAG, "no session yet", it) }
            startPolling(application)
        }
    }

    val recentlyPlayed: List<HistoryEntry>
        get() = _entries.value.values.sortedByDescending { it.lastPlayedAt }

    val mostPlayed: List<HistoryEntry>
        get() = _entries.value.values.filter { it.playCount > 0 }
            .sortedWith(compareByDescending<HistoryEntry> { it.playCount }.thenByDescending { it.lastPlayedAt })

    fun clear(context: Context) {
        _entries.value = emptyMap()
        scheduleSave(context.applicationContext)
    }

    /** Plays [items] from [startIndex] through the app's own session. */
    fun play(context: Context, items: List<HistoryEntry>, startIndex: Int) {
        val application = context.applicationContext
        scope.launch {
            val c = runCatching { SessionBridge.controller(application) }.getOrNull() ?: return@launch
            c.setMediaItems(items.map { it.toMediaItem() }, startIndex, 0)
            c.prepare()
            c.play()
        }
    }

    // ------------------------------------------------------------------

    private fun startPolling(application: Context) {
        poll?.cancel()
        poll = scope.launch {
            while (isActive) {
                delay(POLL_MS)
                val c = SessionBridge.current() ?: continue
                val item = c.currentMediaItem ?: continue
                val id = item.mediaId
                if (!id.startsWith("MediaStore:")) continue
                if (id != currentId) {
                    currentId = id; heardMs = 0; counted = false
                }
                if (!c.isPlaying || counted) continue
                heardMs += POLL_MS
                val duration = c.duration.takeIf { it > 0 } ?: item.mediaMetadata.durationMs ?: 0L
                val threshold = if (duration > 0) minOf(COUNT_AFTER_MS, duration / 2) else COUNT_AFTER_MS
                if (heardMs >= threshold) {
                    counted = true
                    record(application, item, duration)
                }
            }
        }
    }

    private fun record(application: Context, item: MediaItem, durationMs: Long) {
        val meta = item.mediaMetadata
        val now = System.currentTimeMillis()
        _entries.update { map ->
            val old = map[item.mediaId]
            val entry = HistoryEntry(
                mediaId = item.mediaId,
                uri = item.localConfiguration?.uri?.toString() ?: old?.uri ?: return@update map,
                title = meta.title?.toString() ?: old?.title ?: "Unknown",
                artist = meta.artist?.toString() ?: old?.artist,
                album = meta.albumTitle?.toString() ?: old?.album,
                artworkUri = meta.artworkUri?.toString() ?: old?.artworkUri,
                durationMs = durationMs.takeIf { it > 0 } ?: old?.durationMs ?: 0L,
                playCount = (old?.playCount ?: 0) + 1,
                lastPlayedAt = now,
            )
            val next = map + (item.mediaId to entry)
            if (next.size <= MAX_ENTRIES) next
            else next.values.sortedByDescending { it.lastPlayedAt }.take(MAX_ENTRIES).associateBy { it.mediaId }
        }
        scheduleSave(application)
    }

    private fun scheduleSave(application: Context) {
        save?.cancel()
        save = scope.launch(Dispatchers.IO) {
            delay(500)
            val array = JSONArray()
            _entries.value.values.forEach { e ->
                array.put(JSONObject().apply {
                    put("mediaId", e.mediaId); put("uri", e.uri); put("title", e.title)
                    put("artist", e.artist); put("album", e.album); put("artworkUri", e.artworkUri)
                    put("durationMs", e.durationMs); put("playCount", e.playCount); put("lastPlayedAt", e.lastPlayedAt)
                })
            }
            val file = File(application.filesDir, FILE)
            val tmp = File(application.filesDir, "$FILE.tmp")
            runCatching {
                tmp.writeText(array.toString())
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }.onFailure { Log.w(TAG, "could not write $FILE", it) }
        }
    }

    private fun load(application: Context) {
        val file = File(application.filesDir, FILE)
        if (!file.isFile) return
        runCatching {
            val array = JSONArray(file.readText())
            val map = (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val id = o.optString("mediaId").ifBlank { return@mapNotNull null }
                HistoryEntry(
                    mediaId = id,
                    uri = o.optString("uri").ifBlank { return@mapNotNull null },
                    title = o.optString("title").ifBlank { "Unknown" },
                    artist = o.optString("artist").takeIf { it.isNotBlank() },
                    album = o.optString("album").takeIf { it.isNotBlank() },
                    artworkUri = o.optString("artworkUri").takeIf { it.isNotBlank() },
                    durationMs = o.optLong("durationMs"),
                    playCount = o.optInt("playCount"),
                    lastPlayedAt = o.optLong("lastPlayedAt"),
                )
            }.associateBy { it.mediaId }
            _entries.value = map
        }.onFailure { Log.w(TAG, "could not read $FILE, starting empty", it) }
    }
}
