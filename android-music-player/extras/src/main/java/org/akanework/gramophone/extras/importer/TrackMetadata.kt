/*
 *     Copyright (C) 2026 Gramophone yt-dlp importer contributors
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

/** Tags we will write onto the imported file. */
data class TrackMetadata(
    val videoId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val year: String?,
    val durationSeconds: Int,
    val thumbnailUrl: String?,
    /** The untouched video title, kept for display and for artwork searching. */
    val rawTitle: String,
) {
    /** `Artist - Title`, or just the title when the artist is unknown. */
    val displayName: String
        get() = artist?.takeIf { it.isNotBlank() }?.let { "$it - $title" } ?: title

    /** What we hand to the artwork providers. */
    val searchQuery: String
        get() = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title).joinToString(" ")
}

/**
 * Reads the tags yt-dlp can see for a URL, then makes them presentable.
 *
 * yt-dlp already does the hard part for anything that came from YouTube Music:
 * those entries carry real `track`/`artist`/`album` fields lifted from the
 * music metadata, and we use them verbatim. Ordinary YouTube uploads carry
 * nothing but a free-text title, so for those we fall back to the heuristics in
 * [cleanUp].
 */
object MetadataProbe {

    /** ASCII unit separator — will not occur in a YouTube title. */
    private const val SEP = "\u001f"

    private val PRINT_TEMPLATE = listOf(
        "%(id|)s",
        "%(track|)s",
        "%(artist|)s",
        "%(album|)s",
        "%(release_year|)s",
        "%(duration|0)s",
        "%(thumbnail|)s",
        "%(title|)s",
        "%(uploader|)s",
    ).joinToString(SEP)

    suspend fun probe(url: String): TrackMetadata = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(url)
            .addOption("--skip-download")
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            .addOption("--ignore-config")
            .addOption("--print", PRINT_TEMPLATE)

        // youtubedl-android throws on a non-zero exit with stderr as the
        // message; the exitCode check below is kept for the version that
        // returns instead.
        val response = try {
            YoutubeDL.getInstance().execute(request)
        } catch (e: YoutubeDLException) {
            throw YtDlpFailure(DownloadError.humanize(e.message,
                fallback = "Could not read video information"), e)
        }
        if (response.exitCode != 0) {
            throw YtDlpFailure(DownloadError.humanize(response.err,
                fallback = "Could not read video information"))
        }

        val line = response.out.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains(SEP) }
            ?: throw YtDlpFailure("Could not read video information")

        val f = line.split(SEP)
        fun field(i: Int) = f.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() && it != "NA" }

        val rawTitle = field(7) ?: "Unknown"
        val uploader = field(8)

        cleanUp(
            videoId = field(0) ?: "",
            track = field(1),
            artist = field(2),
            album = field(3),
            year = field(4),
            duration = field(5)?.toDoubleOrNull()?.toInt() ?: 0,
            thumbnail = field(6),
            rawTitle = rawTitle,
            uploader = uploader,
        )
    }

    // Junk that decorates almost every music upload but belongs in no tag.
    private val NOISE = Regex(
        """\s*[\(\[]\s*(?:officiell?e?l?|official\s+)?""" +
            """(?:music\s+)?(?:video|audio|visuali[sz]er|lyrics?|lyric\s+video|mv|hd|hq|4k|""" +
            """full\s+song|full\s+audio|color\s+coded[^\)\]]*|sub[^\)\]]*|remaster(?:ed)?[^\)\]]*)""" +
            """\s*[\)\]]""",
        RegexOption.IGNORE_CASE,
    )

    // Trailing "| Official Video", "| NCS Release" and friends.
    private val TRAILING_PIPE = Regex("""\s*\|\s*[^|]{0,40}$""")

    /** Separators used between artist and title, longest first. */
    private val DASHES = listOf(" — ", " – ", " - ", " -- ")

    internal fun cleanUp(
        videoId: String,
        track: String?,
        artist: String?,
        album: String?,
        year: String?,
        duration: Int,
        thumbnail: String?,
        rawTitle: String,
        uploader: String?,
    ): TrackMetadata {
        // YouTube auto-generates an "<Artist> - Topic" channel for licensed
        // music; that channel name is a far better artist than anything we
        // could parse out of the title.
        val topicArtist = uploader?.removeSuffix(" - Topic")?.takeIf { it != uploader }

        if (!track.isNullOrBlank() && !artist.isNullOrBlank()) {
            // Real music metadata — trust it and stop guessing.
            return TrackMetadata(
                videoId = videoId,
                title = track.trim(),
                artist = artist.trim(),
                album = album?.trim(),
                year = year,
                durationSeconds = duration,
                thumbnailUrl = thumbnail,
                rawTitle = rawTitle,
            )
        }

        var working = rawTitle
        working = NOISE.replace(working, "")
        working = TRAILING_PIPE.replace(working, "")
        working = working.trim().trim('-', '–', '—', '·').trim()

        var guessedArtist = topicArtist ?: artist?.trim()
        var guessedTitle = track?.trim() ?: working

        if (track.isNullOrBlank()) {
            val dash = DASHES.firstNotNullOfOrNull { sep ->
                val idx = working.indexOf(sep)
                if (idx > 0 && idx < working.length - sep.length) idx to sep else null
            }
            if (dash != null) {
                val (idx, sep) = dash
                val left = working.substring(0, idx).trim()
                val right = working.substring(idx + sep.length).trim()
                // Only treat the left side as an artist if it looks like a name
                // rather than the first half of a sentence.
                if (left.isNotEmpty() && right.isNotEmpty() && left.length <= 60) {
                    if (guessedArtist.isNullOrBlank()) guessedArtist = left
                    guessedTitle = right
                }
            }
        }

        return TrackMetadata(
            videoId = videoId,
            title = guessedTitle.ifBlank { rawTitle },
            artist = guessedArtist?.ifBlank { null } ?: uploader,
            album = album?.trim(),
            year = year,
            durationSeconds = duration,
            thumbnailUrl = thumbnail,
            rawTitle = rawTitle,
        )
    }
}

/**
 * A yt-dlp invocation that failed.
 *
 * The message is always already user-facing — callers run stderr through
 * [DownloadError.humanize] first — so the UI can show it verbatim.
 */
class YtDlpFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** yt-dlp's raw stderr, when this wraps a [YoutubeDLException]. */
    val stderr: String? get() = (cause as? YoutubeDLException)?.message
}
