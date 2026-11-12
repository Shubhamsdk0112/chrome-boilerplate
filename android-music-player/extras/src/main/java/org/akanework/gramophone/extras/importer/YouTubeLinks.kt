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

/**
 * What a pasted or shared YouTube link points at.
 *
 * The YouTube app appends a playlist to almost every share: a song opened
 * from a playlist becomes `watch?v=…&list=PL…&index=4`, and anything started
 * from the "Mix" shelf becomes `watch?v=…&list=RD…&start_radio=1`. Only the
 * first is a list the user might want; a mix is an endless radio YouTube
 * generates on the fly, so it is treated as the single video.
 */
sealed interface YouTubeLink {
    val url: String

    data class Video(override val url: String, val videoId: String) : YouTubeLink

    /** A playlist page, an album (`OLAK5uy_…`), or a channel's uploads. */
    data class Playlist(override val url: String, val listId: String) : YouTubeLink

    /** A video opened from inside a playlist: ask which one the user means. */
    data class VideoInPlaylist(
        override val url: String,
        val videoId: String,
        val listId: String,
    ) : YouTubeLink {
        val videoUrl get() = "https://www.youtube.com/watch?v=$videoId"
        val playlistUrl get() = "https://www.youtube.com/playlist?list=$listId"
    }

    /** Anything else yt-dlp might still handle (another site, a Short, …). */
    data class Other(override val url: String) : YouTubeLink
}

object YouTubeLinks {

    private val URL = Regex("""https?://[^\s<>"']+""")
    private val VIDEO_ID = Regex("""^[A-Za-z0-9_-]{11}$""")

    /** Every http(s) link in [text], in order, without duplicates or trailing punctuation. */
    fun extractUrls(text: String): List<String> =
        URL.findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '!', '?', ';', ':') }
            .distinct()
            .toList()

    /** Whether [text] looks like a search rather than a link. */
    fun isSearch(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && extractUrls(t).isEmpty()
    }

    fun classify(url: String): YouTubeLink {
        val trimmed = url.trim()
        val host = hostOf(trimmed) ?: return YouTubeLink.Other(trimmed)
        val youtube = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        if (!youtube) return YouTubeLink.Other(trimmed)

        val params = queryOf(trimmed)
        val path = pathOf(trimmed)
        val videoId = when {
            host == "youtu.be" -> path.trim('/').substringBefore('/')
            path.startsWith("/shorts/") || path.startsWith("/live/") || path.startsWith("/embed/") ->
                path.split('/').getOrNull(2).orEmpty()
            else -> params["v"].orEmpty()
        }.takeIf { VIDEO_ID.matches(it) }
        val listId = params["list"]?.takeIf { it.isNotBlank() && isRealPlaylist(it) }

        return when {
            videoId != null && listId != null -> YouTubeLink.VideoInPlaylist(trimmed, videoId, listId)
            videoId != null -> YouTubeLink.Video(trimmed, videoId)
            listId != null -> YouTubeLink.Playlist(trimmed, listId)
            else -> YouTubeLink.Other(trimmed)
        }
    }

    /**
     * Mixes (`RD…`), the watch-later and liked lists (need a login and are
     * per-user) are not playlists in the sense of "import these songs".
     */
    fun isRealPlaylist(listId: String): Boolean =
        !listId.startsWith("RD") && listId != "WL" && listId != "LL" && listId != "LM"

    private fun hostOf(url: String): String? =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?')
            .substringBefore('#').substringAfter('@').substringBefore(':')
            .lowercase().ifEmpty { null }

    private fun pathOf(url: String): String =
        "/" + url.substringAfter("://", "").substringAfter('/', "")
            .substringBefore('?').substringBefore('#')

    private fun queryOf(url: String): Map<String, String> {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val key = part.substringBefore('=')
            if (key.isEmpty()) null else key to decode(part.substringAfter('=', ""))
        }.toMap()
    }

    private fun decode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
