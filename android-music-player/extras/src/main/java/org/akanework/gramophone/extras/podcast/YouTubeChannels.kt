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

import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * A YouTube channel treated as a podcast show.
 *
 * New uploads come from the channel's public Atom feed
 * (`/feeds/videos.xml?channel_id=…`): one small HTTP request, no yt-dlp, no
 * rate-limit exposure. They are listed as not-yet-downloaded episodes; the
 * Download button then goes through yt-dlp like any YouTube link. Shorts
 * are left out — the feed marks them with a /shorts/ link.
 *
 * The show's cover is the channel avatar, which only yt-dlp can see
 * (one --flat-playlist request with zero entries). Until it is fetched the
 * show uses its first video's thumbnail.
 */
object YouTubeChannels {

    private const val TAG = "YouTubeChannels"

    fun channelId(feedUrl: String): String? =
        feedUrl.removePrefix(PodcastStore.YOUTUBE_FEED_PREFIX)
            .takeIf { feedUrl.startsWith(PodcastStore.YOUTUBE_FEED_PREFIX) && it.startsWith("UC") && it.length == 24 }

    /** Whether [imageUrl] is still a video thumbnail rather than a channel avatar. */
    fun needsAvatar(imageUrl: String?): Boolean =
        imageUrl == null || imageUrl.contains("i.ytimg.com") || imageUrl.contains("/vi/")

    /**
     * Brings a channel show up to date: new uploads added, avatar set.
     * Returns the updated show, or null if nothing could be fetched.
     */
    suspend fun refresh(context: android.content.Context, show: Podcast): Podcast? =
        withContext(Dispatchers.IO) {
            val store = PodcastStore.get(context)
            val id = channelId(show.feedUrl) ?: return@withContext null
            val latest = PodcastSearch.Http.text("https://www.youtube.com/feeds/videos.xml?channel_id=$id")
                ?.let { runCatching { parseFeed(show.feedUrl, it) }.getOrNull() }
            val avatar = if (needsAvatar(show.imageUrl) && avatarAttemptDue(id)) {
                runCatching {
                    org.akanework.gramophone.extras.importer.YtDlp.ensureInitialized(context)
                    avatar(id, org.akanework.gramophone.extras.importer.Cookies.path(context))
                }.getOrNull()
            } else null
            if (latest == null && avatar == null) return@withContext null
            val current = store.podcast(show.feedUrl) ?: show
            val known = current.episodes.map { it.guid }.toSet()
            val merged = current.copy(
                imageUrl = avatar ?: current.imageUrl,
                episodes = (current.episodes + latest.orEmpty().filter { it.guid !in known })
                    .sortedByDescending { it.publishedAt },
                refreshedAt = System.currentTimeMillis(),
            )
            store.subscribe(merged)
            merged
        }

    private val avatarTried = HashMap<String, Long>()

    /** One yt-dlp call per channel per hour at most, so a failing lookup is not retried on every screen. */
    @Synchronized
    private fun avatarAttemptDue(channelId: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - (avatarTried[channelId] ?: 0L) < 60 * 60_000L) return false
        avatarTried[channelId] = now
        return true
    }

    /** The channel avatar through yt-dlp, or null. Square beats banner. */
    suspend fun avatar(channelId: String, cookiesPath: String? = null): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = YoutubeDLRequest("https://www.youtube.com/channel/$channelId")
                .addOption("--flat-playlist")
                .addOption("--playlist-items", "0")
                .addOption("--no-warnings")
                .addOption("--ignore-config")
                // playlist: — printed once for the channel itself; a plain
                // template prints per entry, and there are zero entries.
                .addOption("--print", "playlist:%(thumbnails)j")
                .apply { if (cookiesPath != null) addOption("--cookies", cookiesPath) }
            val response = YoutubeDL.getInstance().execute(request)
            pickAvatar(response.out).also {
                Log.i(TAG, "avatar for $channelId: ${if (it != null) "found" else "none in ${response.out.length} chars: ${response.err.take(300)}"}")
            }
        }.onFailure { Log.w(TAG, "no avatar for $channelId: ${it.message?.take(300)}") }.getOrNull()
    }

    /** Pure: the square thumbnail (largest), else the one yt-dlp calls avatar_uncropped. */
    fun pickAvatar(thumbnailsJson: String): String? {
        val start = thumbnailsJson.indexOf('[')
        if (start < 0) return null
        // The array, and nothing after it (yt-dlp may print more lines).
        val array = org.json.JSONTokener(thumbnailsJson.substring(start)).nextValue() as? JSONArray ?: return null
        var best: Pair<Int, String>? = null
        var uncropped: String? = null
        for (i in 0 until array.length()) {
            val t = array.optJSONObject(i) ?: continue
            val url = t.optString("url").takeIf { it.startsWith("http") } ?: continue
            if (t.optString("id") == "avatar_uncropped") uncropped = url
            val w = t.optInt("width", 0)
            val h = t.optInt("height", 0)
            if (w > 0 && w == h && (best == null || w > best.first)) best = w to url
        }
        return best?.second ?: uncropped
    }

    /** Pure: YouTube's channel Atom feed as episodes. Shorts dropped. */
    fun parseFeed(feedUrl: String, xml: String): List<Episode> {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(xml))
        val out = ArrayList<Episode>()
        var inEntry = false
        var videoId: String? = null
        var title: String? = null
        var published = 0L
        var link: String? = null
        var thumb: String? = null
        var description: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "entry" -> {
                        inEntry = true
                        videoId = null; title = null; published = 0L; link = null; thumb = null; description = null
                    }
                    "videoId" -> if (inEntry) videoId = parser.nextText().trim()
                    "title" -> if (inEntry && parser.namespace == ATOM) title = parser.nextText().trim()
                    "published" -> if (inEntry) published = parseDate(parser.nextText().trim())
                    "link" -> if (inEntry && parser.getAttributeValue(null, "rel") == "alternate") {
                        link = parser.getAttributeValue(null, "href")
                    }
                    "thumbnail" -> if (inEntry) thumb = parser.getAttributeValue(null, "url")
                    "description" -> if (inEntry) description = parser.nextText().trim()
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "entry") {
                inEntry = false
                val id = videoId
                val isShort = link?.contains("/shorts/") == true
                if (id != null && !title.isNullOrBlank() && !isShort) {
                    out += Episode(
                        guid = "yt:$id",
                        feedUrl = feedUrl,
                        title = title!!,
                        audioUrl = "https://www.youtube.com/watch?v=$id",
                        publishedAt = published,
                        durationSeconds = 0,
                        imageUrl = thumb,
                        description = description?.take(600),
                        source = EpisodeSource.YOUTUBE,
                    )
                }
            }
            event = parser.next()
        }
        return out
    }

    private const val ATOM = "http://www.w3.org/2005/Atom"

    fun parseDate(text: String): Long {
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        for (p in patterns) {
            runCatching {
                return SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(text)!!.time
            }
        }
        return 0L
    }
}
