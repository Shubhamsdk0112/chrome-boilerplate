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

package org.akanework.gramophone.extras.home

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.akanework.gramophone.extras.filter.FilterStore
import org.akanework.gramophone.extras.history.HistoryEntry
import org.akanework.gramophone.extras.podcast.PodcastStore

/**
 * The newest songs in the library, straight from MediaStore, with the same
 * exclusions the library applies: files the filter hid, and long audio
 * (that lives under Podcasts).
 *
 * Returned as [HistoryEntry] so the Home screen plays them exactly the way
 * it plays history: same MediaItem shape Gramophone's own reader builds
 * (`MediaStore:<id>` + the media content URI), same artwork provider.
 */
object RecentlyAdded {

    private const val TAG = "RecentlyAdded"

    suspend fun load(context: Context, limit: Int = 20): List<HistoryEntry> = withContext(Dispatchers.IO) {
        val hidden = runCatching { FilterStore(context).hiddenPaths }.getOrDefault(emptySet())
        val maxMs = PodcastStore.LONG_AUDIO_MINUTES * 60_000L
        val out = ArrayList<HistoryEntry>(limit)
        @Suppress("DEPRECATION")
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                null, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val id = c.getLong(0)
                    val path = c.getString(1) ?: continue
                    val duration = c.getLong(5)
                    if (path in hidden || duration <= 0 || duration >= maxMs) continue
                    val artist = c.getString(3)?.takeIf { it != MediaStore.UNKNOWN_STRING }
                    out += HistoryEntry(
                        mediaId = "MediaStore:$id",
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
                        title = c.getString(2) ?: path.substringAfterLast('/'),
                        artist = artist,
                        album = c.getString(4),
                        artworkUri = artworkUri(context, id, path),
                        durationMs = duration,
                        playCount = 0,
                        lastPlayedAt = c.getLong(6) * 1000,
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not read recently added", e)
        }
        out
    }

    /** Gramophone's own cover provider, the same URI its library rows use. */
    fun artworkUri(context: Context, id: Long, path: String): String =
        "content://${context.packageName}.albumart/song/$id?songFile=${Uri.encode(path)}"
}
