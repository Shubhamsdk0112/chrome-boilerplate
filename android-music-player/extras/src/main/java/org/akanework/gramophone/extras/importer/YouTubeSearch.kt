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

package org.akanework.gramophone.extras.importer

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Search and playlist listing, both through yt-dlp's `--flat-playlist`: one
 * request for the whole page, no per-video lookups, which is what keeps a
 * search at a couple of seconds and a 50-song playlist at a few.
 */
object YouTubeSearch {

    data class Entry(
        val videoId: String,
        val title: String,
        val channel: String?,
        val durationSeconds: Long,
        val views: Long?,
    ) {
        val url get() = "https://www.youtube.com/watch?v=$videoId"
        /** YouTube serves these without the API; ImageCache fetches them. */
        val thumbnailUrl get() = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
    }

    data class PlaylistInfo(val title: String, val entries: List<Entry>)

    private const val SEP = "\u001F"
    private val FIELDS = listOf(
        "%(id)s", "%(title)s", "%(channel,uploader|)s", "%(duration|0)s", "%(view_count|)s",
    ).joinToString(SEP)

    suspend fun search(query: String, limit: Int = 20, cookiesPath: String? = null): List<Entry> =
        withContext(Dispatchers.IO) {
            val lines = run("ytsearch$limit:${query.trim()}", FIELDS, cookiesPath)
            parseEntries(lines)
        }

    suspend fun playlist(url: String, cookiesPath: String? = null): PlaylistInfo =
        withContext(Dispatchers.IO) {
            val lines = run(url, "%(playlist_title|)s$SEP$FIELDS", cookiesPath)
            val title = lines.firstNotNullOfOrNull { it.substringBefore(SEP).takeIf { t -> t.isNotBlank() && t != "NA" } }
            PlaylistInfo(
                title = title ?: "YouTube playlist",
                entries = parseEntries(lines.map { it.substringAfter(SEP) }),
            )
        }

    private fun run(target: String, template: String, cookiesPath: String?): List<String> {
        val request = YoutubeDLRequest(target)
            .addOption("--flat-playlist")
            .addOption("--no-warnings")
            .addOption("--ignore-config")
            .addOption("--print", template)
            .apply { if (cookiesPath != null) addOption("--cookies", cookiesPath) }
        val response = try {
            YoutubeDL.getInstance().execute(request)
        } catch (e: YoutubeDLException) {
            throw YtDlpFailure(DownloadError.humanize(e.message, fallback = "YouTube did not answer"), e)
        }
        return response.out.lines().filter { it.isNotBlank() }
    }

    /** One `--print` line per entry; private and deleted videos are dropped. */
    fun parseEntries(lines: List<String>): List<Entry> = lines.mapNotNull { line ->
        val f = line.split(SEP)
        if (f.size < 5) return@mapNotNull null
        val id = f[0].trim()
        val title = f[1].trim()
        if (id.length != 11 || title.isEmpty() || title == "NA") return@mapNotNull null
        if (title == "[Private video]" || title == "[Deleted video]") return@mapNotNull null
        Entry(
            videoId = id,
            title = title,
            channel = f[2].trim().takeIf { it.isNotEmpty() && it != "NA" },
            durationSeconds = f[3].trim().toDoubleOrNull()?.toLong() ?: 0L,
            views = f[4].trim().toLongOrNull(),
        )
    }.distinctBy { it.videoId }

    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun formatViews(views: Long?): String? = when {
        views == null -> null
        views >= 1_000_000_000 -> String.format(Locale.US, "%.1fB views", views / 1e9)
        views >= 1_000_000 -> String.format(Locale.US, "%.1fM views", views / 1e6)
        views >= 1_000 -> String.format(Locale.US, "%.0fK views", views / 1e3)
        else -> "$views views"
    }?.replace(".0B", "B")?.replace(".0M", "M")
}
