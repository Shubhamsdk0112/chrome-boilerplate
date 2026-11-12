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
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One counted play (heard for 30 s, or half the song). */
data class Play(
    val at: Long,
    val mediaId: String,
    val title: String,
    val artist: String?,
    val album: String?,
)

/**
 * Every counted play with its time, plus minutes listened per day — what
 * "Your stats" and the smart playlists are made from. [ListeningHistory]
 * keeps only a running count per song; this keeps the timeline.
 *
 * Plays are appended one JSON line each to extras_plays.jsonl (about 100
 * bytes a play: a heavy listener writes well under a megabyte a year).
 * Listening time is a small day → milliseconds map, saved every half minute.
 */
object PlayLog {

    private const val TAG = "PlayLog"
    private const val PLAYS = "extras_plays.jsonl"
    private const val LISTENED = "extras_listened.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val listened = HashMap<String, Long>()
    private var listenedLoaded = false
    private var saveJob: Job? = null

    fun append(context: Context, play: Play) {
        val app = context.applicationContext
        scope.launch {
            lock.withLock {
                runCatching {
                    File(app.filesDir, PLAYS).appendText(toJson(play) + "\n")
                }.onFailure { Log.w(TAG, "could not log play", it) }
            }
        }
    }

    /** Adds [ms] of music heard right now to today's total. */
    fun addListened(context: Context, ms: Long) {
        val app = context.applicationContext
        scope.launch {
            lock.withLock {
                loadListened(app)
                val day = dayKey(System.currentTimeMillis())
                listened[day] = (listened[day] ?: 0L) + ms
            }
            if (saveJob?.isActive != true) {
                saveJob = scope.launch {
                    delay(30_000)
                    lock.withLock { saveListened(app) }
                }
            }
        }
    }

    suspend fun plays(context: Context): List<Play> = withContext(Dispatchers.IO) {
        lock.withLock {
            val f = File(context.applicationContext.filesDir, PLAYS)
            if (!f.isFile) return@withLock emptyList()
            f.readLines().mapNotNull { line -> runCatching { fromJson(line) }.getOrNull() }
        }
    }

    suspend fun listenedByDay(context: Context): Map<String, Long> = withContext(Dispatchers.IO) {
        lock.withLock {
            loadListened(context.applicationContext)
            HashMap(listened)
        }
    }

    fun clear(context: Context) {
        val app = context.applicationContext
        scope.launch {
            lock.withLock {
                File(app.filesDir, PLAYS).delete()
                File(app.filesDir, LISTENED).delete()
                listened.clear()
            }
        }
    }

    // ------------------------------------------------------------------

    private fun loadListened(app: Context) {
        if (listenedLoaded) return
        listenedLoaded = true
        val f = File(app.filesDir, LISTENED)
        if (!f.isFile) return
        runCatching {
            val o = JSONObject(f.readText())
            o.keys().forEach { listened[it] = o.optLong(it) }
        }.onFailure { Log.w(TAG, "could not read $LISTENED", it) }
    }

    private fun saveListened(app: Context) {
        runCatching {
            val tmp = File(app.filesDir, "$LISTENED.tmp")
            tmp.writeText(JSONObject(listened as Map<*, *>).toString())
            val f = File(app.filesDir, LISTENED)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w(TAG, "could not write $LISTENED", it) }
    }

    fun toJson(p: Play): String = JSONObject().apply {
        put("t", p.at); put("id", p.mediaId); put("title", p.title)
        p.artist?.let { put("artist", it) }; p.album?.let { put("album", it) }
    }.toString()

    fun fromJson(line: String): Play? {
        if (line.isBlank()) return null
        val o = JSONObject(line)
        return Play(
            at = o.optLong("t"),
            mediaId = o.optString("id").ifBlank { return null },
            title = o.optString("title").ifBlank { "Unknown" },
            artist = o.optString("artist").takeIf { it.isNotBlank() },
            album = o.optString("album").takeIf { it.isNotBlank() },
        )
    }

    fun dayKey(at: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))
}

/** Pure maths for "Your stats", unit-tested. */
object Stats {

    data class Ranked(val title: String, val subtitle: String?, val count: Int, val mediaId: String? = null)

    data class Summary(
        val minutes: Long,
        val plays: Int,
        val distinctSongs: Int,
        val distinctArtists: Int,
        val topSongs: List<Ranked>,
        val topArtists: List<Ranked>,
        val topAlbums: List<Ranked>,
        /** Plays per hour of the day, 0..23. */
        val byHour: IntArray,
        /** Plays per weekday, Monday first. */
        val byWeekday: IntArray,
        /** Longest run of consecutive days with at least one play. */
        val longestStreak: Int,
    )

    /** Start (inclusive) and end (exclusive) of the calendar month [offset] months from [now]'s. */
    fun monthRange(now: Long, offset: Int): Pair<Long, Long> {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, offset)
        }
        val start = c.timeInMillis
        c.add(Calendar.MONTH, 1)
        return start to c.timeInMillis
    }

    fun compute(plays: List<Play>, listenedByDay: Map<String, Long>, from: Long, to: Long, top: Int = 5): Summary {
        val inRange = plays.filter { it.at in from until to }
        val minutes = listenedByDay.entries.sumOf { (day, ms) ->
            val dayStart = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day)!!.time }.getOrDefault(-1L)
            if (dayStart in from until to) ms else 0L
        } / 60_000L

        val songs = inRange.groupBy { it.mediaId }.map { (id, ps) ->
            Ranked(ps.last().title, ps.last().artist, ps.size, id)
        }.sortedWith(compareByDescending<Ranked> { it.count }.thenBy { it.title.lowercase() })
        val artists = inRange.mapNotNull { p -> splitArtists(p.artist).takeIf { it.isNotEmpty() }?.let { p to it } }
            .flatMap { (_, names) -> names }
            .groupingBy { it }.eachCount()
            .map { (name, n) -> Ranked(name, null, n) }
            .sortedWith(compareByDescending<Ranked> { it.count }.thenBy { it.title.lowercase() })
        val albums = inRange.filter { !it.album.isNullOrBlank() }
            .groupBy { it.album!!.trim() to splitArtists(it.artist).firstOrNull() }
            .map { (key, ps) -> Ranked(key.first, key.second, ps.size) }
            .sortedWith(compareByDescending<Ranked> { it.count }.thenBy { it.title.lowercase() })

        val byHour = IntArray(24)
        val byWeekday = IntArray(7)
        val cal = Calendar.getInstance()
        inRange.forEach {
            cal.timeInMillis = it.at
            byHour[cal.get(Calendar.HOUR_OF_DAY)]++
            byWeekday[(cal.get(Calendar.DAY_OF_WEEK) + 5) % 7]++
        }

        return Summary(
            minutes = minutes,
            plays = inRange.size,
            distinctSongs = songs.size,
            distinctArtists = artists.size,
            topSongs = songs.take(top),
            topArtists = artists.take(top),
            topAlbums = albums.take(top),
            byHour = byHour,
            byWeekday = byWeekday,
            longestStreak = longestStreak(inRange.map { PlayLog.dayKey(it.at) }.toSet()),
        )
    }

    /**
     * All time: the per-song play counts kept since v0.1 are more complete
     * than the timeline (which starts with v0.3.2), so the rankings come from
     * them; hours, weekdays, minutes and streaks still come from the timeline.
     */
    fun withHistory(summary: Summary, history: List<HistoryEntry>, top: Int = 5): Summary {
        val songs = history.filter { it.playCount > 0 }
            .map { Ranked(it.title, it.artist, it.playCount, it.mediaId) }
            .sortedWith(compareByDescending<Ranked> { it.count }.thenBy { it.title.lowercase() })
        if (songs.isEmpty()) return summary
        val artists = history.flatMap { e -> splitArtists(e.artist).map { it to e.playCount } }
            .groupBy({ it.first }, { it.second }).map { (name, counts) -> Ranked(name, null, counts.sum()) }
            .sortedWith(compareByDescending<Ranked> { it.count }.thenBy { it.title.lowercase() })
        val albums = history.filter { !it.album.isNullOrBlank() }
            .groupBy { it.album!!.trim() to splitArtists(it.artist).firstOrNull() }
            .map { (key, es) -> Ranked(key.first, key.second, es.sumOf { it.playCount }) }
            .sortedWith(compareByDescending<Ranked> { it.count }.thenBy { it.title.lowercase() })
        return summary.copy(
            plays = maxOf(summary.plays, songs.sumOf { it.count }),
            distinctSongs = maxOf(summary.distinctSongs, songs.size),
            distinctArtists = maxOf(summary.distinctArtists, artists.size),
            topSongs = songs.take(top),
            topArtists = artists.take(top),
            topAlbums = albums.take(top),
        )
    }

    /** "A, B & C feat. D" → [A, B, C, D]. */
    fun splitArtists(artist: String?): List<String> =
        artist?.split(Regex("""\s*(?:,|&|\bfeat\b\.?|\bft\b\.?|\bx\b|/|;)\s*""", RegexOption.IGNORE_CASE))
            ?.map { it.trim() }?.filter { it.isNotEmpty() && !it.equals("Unknown artist", true) && it != "<unknown>" }
            ?: emptyList()

    fun longestStreak(days: Set<String>): Int {
        if (days.isEmpty()) return 0
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val sorted = days.mapNotNull { runCatching { fmt.parse(it)!!.time }.getOrNull() }.sorted()
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            val gapDays = Math.round((sorted[i] - sorted[i - 1]) / 86_400_000.0)
            run = if (gapDays == 1L) run + 1 else 1
            best = maxOf(best, run)
        }
        return best
    }
}

/** What goes into the smart playlists; pure, unit-tested. */
object SmartPicks {
    private const val DAY = 86_400_000L

    fun mostPlayed(plays: List<Play>, now: Long, limit: Int = 50): List<String> =
        plays.filter { it.at >= now - 30 * DAY && it.mediaId.startsWith("MediaStore:") }
            .groupingBy { it.mediaId }.eachCount()
            .filter { it.value >= 2 }
            .entries.sortedByDescending { it.value }
            .take(limit).map { it.key }

    fun forgotten(history: List<HistoryEntry>, plays: List<Play>, now: Long, limit: Int = 50): List<String> {
        val recent = plays.filter { it.at >= now - 42 * DAY }.map { it.mediaId }.toSet()
        return history.filter {
            it.mediaId.startsWith("MediaStore:") && it.playCount >= 3 &&
                it.lastPlayedAt < now - 42 * DAY && it.mediaId !in recent
        }.sortedByDescending { it.playCount }.take(limit).map { it.mediaId }
    }
}
