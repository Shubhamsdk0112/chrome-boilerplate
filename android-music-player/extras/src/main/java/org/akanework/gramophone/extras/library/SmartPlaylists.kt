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

package org.akanework.gramophone.extras.library

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.akanework.gramophone.extras.filter.FilterStore
import org.akanework.gramophone.extras.history.HistoryEntry
import org.akanework.gramophone.extras.history.ListeningHistory
import org.akanework.gramophone.extras.history.Play
import org.akanework.gramophone.extras.history.PlayLog
import org.akanework.gramophone.extras.history.SmartPicks
import org.akanework.gramophone.extras.importer.PlaylistImports
import org.akanework.gramophone.extras.podcast.PodcastStore

/**
 * Playlists that keep themselves up to date, as real .m3u files in
 * Music/Smart playlists, so they sit in the Playlists tab next to the
 * user's own and play, shuffle and queue like any other:
 *
 *  - **Most played this month**: the last 30 days, two plays or more.
 *  - **Forgotten favourites**: played often (3+), not in the last six weeks.
 *  - **Added this week**: new in the library in the last 7 days.
 *
 * Rewritten at most every few hours (app start, Home), and on demand from
 * Library tools. A list with nothing in it is not created; one the user
 * deleted is created again next time unless they switch the feature off,
 * which also deletes the files.
 */
object SmartPlaylists {

    private const val TAG = "SmartPlaylists"
    private const val PREFS = "extras_app"
    private const val SUB_DIR = "Smart playlists"
    private const val EVERY_MS = 3 * 60 * 60 * 1000L
    private const val DAY = 24 * 60 * 60 * 1000L

    enum class Kind(val key: String, val title: String) {
        MOST_PLAYED("most_played", "Most played this month"),
        FORGOTTEN("forgotten", "Forgotten favourites"),
        ADDED("added", "Added this week"),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    fun enabled(context: Context) = prefs(context).getBoolean("smart_playlists", true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("smart_playlists", on).apply()
        val app = context.applicationContext
        scope.launch { if (on) refresh(app) else removeAll(app) }
    }

    fun lastRefresh(context: Context) = prefs(context).getLong("smart_last", 0L)

    /** Song counts from the last refresh (null before the first one). */
    fun lastCounts(context: Context): Map<Kind, Int>? {
        val p = prefs(context)
        if (!p.contains("smart_count_${Kind.MOST_PLAYED.name}")) return null
        return Kind.entries.associateWith { p.getInt("smart_count_${it.name}", 0) }
    }

    fun refreshIfDue(context: Context) {
        val app = context.applicationContext
        scope.launch {
            if (!enabled(app)) return@launch
            if (System.currentTimeMillis() - lastRefresh(app) < EVERY_MS) return@launch
            runCatching { refresh(app) }.onFailure { Log.w(TAG, "refresh failed", it) }
        }
    }

    /** Rebuilds all three; returns how many songs each holds. */
    suspend fun refresh(context: Context): Map<Kind, Int> = withContext(Dispatchers.IO) {
        lock.withLock {
            val app = context.applicationContext
            ListeningHistory.loaded.first { it }
            val now = System.currentTimeMillis()
            val plays = PlayLog.plays(app)
            val history = ListeningHistory.entries.value.values.toList()
            val hidden = runCatching { FilterStore(app).hiddenPaths }.getOrDefault(emptySet())

            val picks = mapOf(
                Kind.MOST_PLAYED to mostPlayed(plays, now),
                Kind.FORGOTTEN to forgotten(history, plays, now),
                Kind.ADDED to recentlyAdded(app, now),
            )
            val wanted = picks.values.flatten().toSet()
            val songs = songsById(app, wanted).filterValues { it.path !in hidden }

            val counts = HashMap<Kind, Int>()
            for ((kind, ids) in picks) {
                val tracks = ids.mapNotNull { id -> songs[id]?.let { s ->
                    PlaylistImports.Track("", s.title, s.artist, s.durationMs / 1000) to s.path
                } }
                counts[kind] = tracks.size
                write(app, kind, tracks)
            }
            prefs(app).edit().apply {
                putLong("smart_last", now)
                counts.forEach { (kind, n) -> putInt("smart_count_${kind.name}", n) }
            }.apply()
            Log.i(TAG, "refreshed: " + counts.entries.joinToString { "${it.key.title}=${it.value}" })
            counts
        }
    }

    // ------------------------------------------------------------------
    // Pure choices, unit-tested

    fun mostPlayed(plays: List<Play>, now: Long, limit: Int = 50) = SmartPicks.mostPlayed(plays, now, limit)

    fun forgotten(history: List<HistoryEntry>, plays: List<Play>, now: Long, limit: Int = 50) =
        SmartPicks.forgotten(history, plays, now, limit)

    // ------------------------------------------------------------------

    private data class Song(val path: String, val title: String, val artist: String?, val durationMs: Long)

    @Suppress("DEPRECATION")
    private fun recentlyAdded(app: Context, now: Long): List<String> {
        val out = ArrayList<String>()
        val since = (now - 7 * DAY) / 1000
        val longMs = PodcastStore.LONG_AUDIO_MINUTES * 60_000L
        runCatching {
            app.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DURATION),
                "${MediaStore.Audio.Media.DATE_ADDED} >= ?", arrayOf(since.toString()),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 100) {
                    val d = c.getLong(1)
                    if (d in 1 until longMs) out += "MediaStore:${c.getLong(0)}"
                }
            }
        }.onFailure { Log.w(TAG, "could not read recently added", it) }
        return out
    }

    @Suppress("DEPRECATION")
    private fun songsById(app: Context, ids: Set<String>): Map<String, Song> {
        val numbers = ids.mapNotNull { it.removePrefix("MediaStore:").toLongOrNull() }
        if (numbers.isEmpty()) return emptyMap()
        val out = HashMap<String, Song>()
        numbers.chunked(400).forEach { chunk ->
            runCatching {
                app.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(
                        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION,
                    ),
                    "${MediaStore.Audio.Media._ID} IN (${chunk.joinToString(",")})", null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val path = c.getString(1) ?: continue
                        out["MediaStore:${c.getLong(0)}"] = Song(
                            path = path,
                            title = c.getString(2) ?: path.substringAfterLast('/'),
                            artist = c.getString(3)?.takeIf { it != MediaStore.UNKNOWN_STRING },
                            durationMs = c.getLong(4),
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "could not look up songs", it) }
        }
        return out
    }

    private fun write(app: Context, kind: Kind, tracks: List<Pair<PlaylistImports.Track, String>>) {
        val key = "smart_uri_${kind.key}"
        val stored = prefs(app).getString(key, null)?.let(Uri::parse)
        if (tracks.isEmpty() && stored == null) return
        val text = PlaylistImports.m3u(kind.title, tracks, musicDir = null)
        if (stored != null && PlaylistImports.writePlaylistFile(app, stored, text)) return
        val created = PlaylistImports.createPlaylistFile(app, kind.title, SUB_DIR) ?: return
        if (PlaylistImports.writePlaylistFile(app, created, text)) {
            prefs(app).edit().putString(key, created.toString()).apply()
        }
    }

    private fun removeAll(app: Context) {
        Kind.entries.forEach { kind ->
            val key = "smart_uri_${kind.key}"
            prefs(app).getString(key, null)?.let(Uri::parse)?.let { uri ->
                runCatching {
                    if (uri.scheme == "file") java.io.File(uri.path!!).delete()
                    else app.contentResolver.delete(uri, null, null)
                }
            }
            prefs(app).edit().remove(key).apply()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
