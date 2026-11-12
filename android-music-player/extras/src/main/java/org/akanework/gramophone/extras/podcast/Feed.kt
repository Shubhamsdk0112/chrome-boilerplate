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

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

/** A show the user follows. [episodes] is the cached, newest-first list. */
data class Podcast(
    val feedUrl: String,
    val title: String,
    val author: String?,
    val imageUrl: String?,
    val description: String?,
    val episodes: List<Episode>,
    val refreshedAt: Long,
)

/** A named section of an episode, as YouTube or a feed describes it. */
data class Chapter(val title: String, val startMs: Long, val endMs: Long)

/** Where an episode came from; decides what Play and Download mean for it. */
object EpisodeSource {
    const val RSS = "rss"
    /** Downloaded from YouTube by yt-dlp; cannot be streamed, only re-downloaded. */
    const val YOUTUBE = "youtube"
    /** A long file already on the phone, read straight from MediaStore. */
    const val LOCAL = "local"
}

data class Episode(
    /** Stable id from the feed; falls back to the audio URL. */
    val guid: String,
    val feedUrl: String,
    val title: String,
    val audioUrl: String,
    val publishedAt: Long,
    val durationSeconds: Int,
    val imageUrl: String?,
    val description: String?,
    val chapters: List<Chapter> = emptyList(),
    val source: String = EpisodeSource.RSS,
) {
    /** Whether Play can start without a downloaded file. */
    val streamable: Boolean get() = source != EpisodeSource.YOUTUBE

    /** The chapter that contains [positionMs], if the episode has chapters. */
    fun chapterAt(positionMs: Long): Chapter? =
        chapters.lastOrNull { it.startMs <= positionMs }

    /** What the audio URL says the file is, for the download's extension. */
    val extension: String
        get() {
            val path = audioUrl.substringBefore('?').substringBefore('#')
            val ext = path.substringAfterLast('.', "")
            return if (ext.length in 2..4 && ext.all { it.isLetterOrDigit() }) ext.lowercase() else "mp3"
        }
}

/**
 * RSS 2.0 with the iTunes namespace, which is what every podcast host emits.
 *
 * Namespace-tolerant on purpose: matched on local names only, so
 * `itunes:duration` and a bare `duration` both count. Unknown elements are
 * skipped, and an item without an audio enclosure is dropped rather than
 * failing the feed.
 */
object RssParser {

    private val RFC822 = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm Z",
        "dd MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd",
    )

    fun parse(feedUrl: String, input: InputStream, now: Long = System.currentTimeMillis()): Podcast {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(input, null)
        return parse(feedUrl, parser, now)
    }

    fun parse(feedUrl: String, parser: XmlPullParser, now: Long): Podcast {
        var title: String? = null
        var author: String? = null
        var image: String? = null
        var description: String? = null
        val episodes = mutableListOf<Episode>()

        var event = parser.eventType
        var inItem = false
        var inChannelImage = false
        // Item fields.
        var iTitle: String? = null
        var iGuid: String? = null
        var iAudio: String? = null
        var iDate: String? = null
        var iDuration: String? = null
        var iImage: String? = null
        var iDescription: String? = null
        var iSummary: String? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name
                    when {
                        name == "item" -> {
                            inItem = true
                            iTitle = null; iGuid = null; iAudio = null; iDate = null
                            iDuration = null; iImage = null; iDescription = null; iSummary = null
                        }
                        inItem -> when (name) {
                            "title" -> iTitle = text(parser)
                            "guid" -> iGuid = text(parser)
                            "enclosure" -> {
                                val type = parser.getAttributeValue(null, "type").orEmpty()
                                val url = parser.getAttributeValue(null, "url")
                                if (url != null && (type.startsWith("audio") || type.isEmpty() || iAudio == null)) {
                                    if (iAudio == null || type.startsWith("audio")) iAudio = url
                                }
                            }
                            "pubDate", "published", "date" -> iDate = text(parser)
                            "duration" -> iDuration = text(parser)
                            "image" -> iImage = parser.getAttributeValue(null, "href") ?: iImage
                            "description" -> iDescription = text(parser)
                            "summary" -> iSummary = text(parser)
                            "encoded" -> if (iDescription == null) iDescription = text(parser)
                        }
                        name == "image" && !inChannelImage -> {
                            // itunes:image is self-closing with href; RSS <image> wraps <url>.
                            val href = parser.getAttributeValue(null, "href")
                            if (href != null) image = image ?: href else inChannelImage = true
                        }
                        inChannelImage && name == "url" -> image = image ?: text(parser)
                        name == "title" && title == null -> title = text(parser)
                        name == "author" && author == null -> author = text(parser)
                        name == "description" && description == null -> description = text(parser)
                        name == "summary" && description == null -> description = text(parser)
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "item" -> {
                        inItem = false
                        val audio = iAudio
                        if (audio != null) {
                            episodes += Episode(
                                guid = iGuid?.trim()?.takeIf { it.isNotEmpty() } ?: audio,
                                feedUrl = feedUrl,
                                title = iTitle?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled episode",
                                audioUrl = audio.trim(),
                                publishedAt = parseDate(iDate),
                                durationSeconds = parseDuration(iDuration),
                                imageUrl = iImage,
                                description = (iDescription ?: iSummary)?.let(::stripHtml),
                            )
                        }
                    }
                    "image" -> inChannelImage = false
                }
            }
            event = parser.next()
        }

        return Podcast(
            feedUrl = feedUrl,
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: feedUrl,
            author = author?.trim()?.takeIf { it.isNotEmpty() },
            imageUrl = image?.trim(),
            description = description?.let(::stripHtml),
            episodes = episodes.sortedByDescending { it.publishedAt },
            refreshedAt = now,
        )
    }

    private fun text(parser: XmlPullParser): String? {
        // nextText() requires the tag to contain only text; some feeds nest
        // CDATA plus whitespace, which it handles, but a nested element would
        // throw. Fall back to reading until the matching end tag.
        return runCatching { parser.nextText() }.getOrElse {
            val sb = StringBuilder()
            var depth = 1
            while (depth > 0) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> depth++
                    XmlPullParser.END_TAG -> depth--
                    XmlPullParser.TEXT -> sb.append(parser.text)
                    XmlPullParser.END_DOCUMENT -> return sb.toString()
                }
            }
            sb.toString()
        }
    }

    /** "1:02:03", "62:03", "3723", "3723.5" → seconds. Unknown → 0. */
    internal fun parseDuration(raw: String?): Int {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return 0
        if (':' in s) {
            val parts = s.split(':').map { it.trim().toDoubleOrNull() ?: return 0 }
            return parts.fold(0.0) { acc, p -> acc * 60 + p }.toInt()
        }
        return s.toDoubleOrNull()?.toInt() ?: 0
    }

    internal fun parseDate(raw: String?): Long {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return 0L
        for (pattern in RFC822) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = true }.parse(s)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0L
    }

    /** Show notes arrive as HTML; the list wants one plain paragraph. */
    internal fun stripHtml(html: String): String =
        html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p\\s*>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
