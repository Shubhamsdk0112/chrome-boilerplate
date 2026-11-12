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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** A show found by name, before subscribing. */
data class SearchResult(
    val title: String,
    val author: String?,
    val feedUrl: String,
    val imageUrl: String?,
    val episodeCount: Int,
)

/**
 * Finding shows and fetching feeds.
 *
 * Search goes through Apple's podcast directory, which is the de-facto
 * registry (every host submits there), needs no key, and returns the RSS
 * URL directly. Anything typed that already looks like a URL is fetched as a
 * feed instead.
 */
object PodcastSearch {

    private const val TAG = "PodcastSearch"
    private const val UA = "Gramophone/1.0 (Android; podcast)"
    private const val TIMEOUT_MS = 15_000

    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=25&term=$q"
        val body = Http.text(url) ?: return@withContext emptyList()
        runCatching {
            val results = JSONObject(body).optJSONArray("results") ?: return@withContext emptyList()
            (0 until results.length()).mapNotNull { i ->
                val r = results.getJSONObject(i)
                val feed = r.optString("feedUrl").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                SearchResult(
                    title = r.optString("collectionName").ifBlank { r.optString("trackName") },
                    author = r.optString("artistName").takeIf { it.isNotBlank() },
                    feedUrl = feed,
                    imageUrl = r.optString("artworkUrl600").ifBlank { r.optString("artworkUrl100") }
                        .takeIf { it.isNotBlank() },
                    episodeCount = r.optInt("trackCount", 0),
                )
            }
        }.onFailure { Log.w(TAG, "search parse failed", it) }.getOrDefault(emptyList())
    }

    /** Downloads and parses a feed. Throws on network or parse failure. */
    suspend fun fetchFeed(feedUrl: String): Podcast = withContext(Dispatchers.IO) {
        Http.open(feedUrl).use { connection ->
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code for $feedUrl")
            connection.inputStream.use { RssParser.parse(feedUrl, it) }
        }
    }

    /** Small HTTP helper shared by the podcast code. */
    internal object Http {
        fun open(url: String): Connection {
            val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
            }
            return Connection(connection)
        }

        fun text(url: String): String? = runCatching {
            open(url).use { c ->
                if (c.responseCode !in 200..299) return@use null
                c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }.onFailure { Log.d(TAG, "GET $url failed: ${it.message}") }.getOrNull()

        fun bytes(url: String): ByteArray? = runCatching {
            open(url).use { c ->
                if (c.responseCode !in 200..299) return@use null
                c.inputStream.use { it.readBytes() }
            }
        }.getOrNull()
    }

    /** HttpURLConnection with a `use {}`. */
    internal class Connection(private val c: HttpURLConnection) : AutoCloseable {
        val responseCode: Int get() = c.responseCode
        val inputStream get() = c.inputStream
        val contentLength: Long get() = c.contentLengthLong
        fun header(name: String): String? = c.getHeaderField(name)
        fun setHeader(name: String, value: String) = c.setRequestProperty(name, value)
        override fun close() = c.disconnect()
    }
}
