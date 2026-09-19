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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Cover art, plus anything else the lookup taught us about the release.
 *
 * The album name is worth keeping: a YouTube video knows nothing about which
 * album a song belongs to, so without this every import would land in the
 * library as its own one-track album.
 */
class Artwork(
    val jpeg: ByteArray,
    val album: String? = null,
    val year: String? = null,
    val source: String,
)

/**
 * Finds a square cover for a track.
 *
 * A YouTube thumbnail is 16:9 and usually has a face, a logo and some text on
 * it — it makes a poor album cover in a grid. So we first ask the two music
 * catalogues that answer without an API key (iTunes, then Deezer) for the real
 * artwork, and only crop the video thumbnail when neither recognises the song.
 */
object ArtworkFinder {
    private const val TAG = "ArtworkFinder"
    private const val TIMEOUT_MS = 12_000
    private const val UA = "Gramophone-Importer/1.0 (+https://github.com/AkaneTan/Gramophone)"

    /** Title overlap needed when the artist corroborates the match. */
    private const val TITLE_THRESHOLD = 0.5

    /** Title overlap needed when the artist is unknown and nothing else does. */
    private const val TITLE_ONLY_THRESHOLD = 0.9

    private const val ARTIST_THRESHOLD = 0.34

    suspend fun find(meta: TrackMetadata): Artwork? = withContext(Dispatchers.IO) {
        fromITunes(meta)
            ?: fromDeezer(meta)
            ?: fromYouTubeThumbnail(meta)
    }

    // ------------------------------------------------------------------
    // Catalogue lookups
    // ------------------------------------------------------------------

    /**
     * iTunes Search: no key, no quota worth worrying about, and the artwork URL
     * is resizable — the `100x100bb` segment can be rewritten to any size, so we
     * ask for 600x600 rather than the thumbnail the search result advertises.
     */
    private fun fromITunes(meta: TrackMetadata): Artwork? = runCatching {
        val url = "https://itunes.apple.com/search" +
            "?term=${enc(meta.searchQuery)}&media=music&entity=song&limit=8"
        val root = JSONObject(httpText(url) ?: return null)
        val results = root.optJSONArray("results") ?: return null

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val trackName = item.optString("trackName")
            val artistName = item.optString("artistName")
            if (!matches(meta, trackName, artistName)) continue

            val art = item.optString("artworkUrl100").takeIf { it.isNotBlank() } ?: continue
            val big = art.replace(Regex("""/\d+x\d+bb\."""), "/600x600bb.")
            val bytes = httpBytes(big) ?: httpBytes(art) ?: continue

            return Artwork(
                jpeg = bytes,
                album = item.optString("collectionName").takeIf { it.isNotBlank() },
                year = item.optString("releaseDate").takeIf { it.length >= 4 }?.substring(0, 4),
                source = "iTunes",
            )
        }
        null
    }.onFailure { Log.d(TAG, "iTunes lookup failed", it) }.getOrNull()

    /** Deezer also answers unauthenticated, and serves a 1000px cover. */
    private fun fromDeezer(meta: TrackMetadata): Artwork? = runCatching {
        val url = "https://api.deezer.com/search?q=${enc(meta.searchQuery)}&limit=8"
        val root = JSONObject(httpText(url) ?: return null)
        val data = root.optJSONArray("data") ?: return null

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val trackName = item.optString("title")
            val artistName = item.optJSONObject("artist")?.optString("name").orEmpty()
            if (!matches(meta, trackName, artistName)) continue

            val album = item.optJSONObject("album") ?: continue
            val cover = listOf("cover_xl", "cover_big", "cover_medium")
                .firstNotNullOfOrNull { album.optString(it).takeIf { s -> s.isNotBlank() } }
                ?: continue
            val bytes = httpBytes(cover) ?: continue

            return Artwork(
                jpeg = bytes,
                album = album.optString("title").takeIf { it.isNotBlank() },
                source = "Deezer",
            )
        }
        null
    }.onFailure { Log.d(TAG, "Deezer lookup failed", it) }.getOrNull()

    /**
     * Last resort: the video's own thumbnail, centre-cropped to a square so it
     * at least sits correctly in the library grid.
     */
    private fun fromYouTubeThumbnail(meta: TrackMetadata): Artwork? = runCatching {
        val candidates = buildList {
            if (meta.videoId.isNotBlank()) {
                add("https://i.ytimg.com/vi/${meta.videoId}/maxresdefault.jpg")
                add("https://i.ytimg.com/vi/${meta.videoId}/hqdefault.jpg")
            }
            meta.thumbnailUrl?.let { add(it) }
        }
        val raw = candidates.firstNotNullOfOrNull { httpBytes(it) } ?: return null
        Artwork(jpeg = centreCropSquare(raw) ?: raw, source = "YouTube thumbnail")
    }.onFailure { Log.d(TAG, "thumbnail fallback failed", it) }.getOrNull()

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    /**
     * Guards against confidently tagging a song with someone else's cover.
     *
     * A catalogue will happily return *something* for any query, so a result is
     * accepted only on a strong match. The artist is what makes that judgement
     * safe, so when we do not know it the bar on the title rises sharply:
     * plenty of different songs share a title, and a wrong cover is worse than
     * the cropped video thumbnail we fall back to.
     */
    internal fun matches(
        meta: TrackMetadata,
        candidateTitle: String,
        candidateArtist: String,
    ): Boolean {
        if (candidateTitle.isBlank()) return false
        val titleScore = similarity(meta.title, candidateTitle)
        val artist = meta.artist?.takeIf { it.isNotBlank() }
            ?: return titleScore >= TITLE_ONLY_THRESHOLD
        if (titleScore < TITLE_THRESHOLD) return false
        return similarity(artist, candidateArtist) >= ARTIST_THRESHOLD
    }

    /**
     * Token overlap, divided by the *larger* set.
     *
     * Dividing by the smaller one makes a subset score a perfect 1.0, so
     * "Lucky" would match "Get Lucky" exactly — precisely the confusion this is
     * meant to prevent. Against the larger set, a genuine match with an extra
     * qualifier like "(Radio Edit)" still scores well enough to pass when the
     * artist confirms it, and fails when nothing else corroborates it.
     */
    internal fun similarity(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val intersection = ta.intersect(tb).size.toDouble()
        return intersection / maxOf(ta.size, tb.size)
    }

    private fun tokens(s: String): Set<String> = s.lowercase()
        .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
        .split(Regex("""\s+"""))
        .filter { it.isNotBlank() }
        .toSet()

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    private fun centreCropSquare(bytes: ByteArray): ByteArray? = runCatching {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = minOf(src.width, src.height)
        val cropped = Bitmap.createBitmap(
            src, (src.width - side) / 2, (src.height - side) / 2, side, side
        )
        ByteArrayOutputStream().use { out ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
            if (cropped !== src) cropped.recycle()
            src.recycle()
            out.toByteArray()
        }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpText(url: String): String? =
        httpBytes(url)?.toString(Charsets.UTF_8)

    /**
     * The video's own thumbnail as cover art, and nothing else. For podcasts
     * the catalogue search is wrong by construction: an episode title would
     * match some unrelated album.
     */
    fun thumbnailOnly(meta: TrackMetadata): Artwork? = fromYouTubeThumbnail(meta)

    /**
     * A small poster for the job card while the download runs. Never the
     * maxres image: this is decoded into a list row, not embedded in a file.
     */
    fun fetchThumbnail(meta: TrackMetadata): ByteArray? = runCatching {
        val candidates = buildList {
            if (meta.videoId.isNotBlank()) add("https://i.ytimg.com/vi/${meta.videoId}/mqdefault.jpg")
            meta.thumbnailUrl?.let { add(it) }
        }
        candidates.firstNotNullOfOrNull { httpBytes(it) }
    }.getOrNull()

    private fun httpBytes(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
            }
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.d(TAG, "GET $url failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}
