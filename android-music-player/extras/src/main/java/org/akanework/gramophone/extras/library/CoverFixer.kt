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

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.akanework.gramophone.extras.filter.FilterStore
import org.akanework.gramophone.extras.importer.ArtworkFinder
import org.akanework.gramophone.extras.importer.Ffmpeg
import org.akanework.gramophone.extras.importer.MetadataProbe
import org.akanework.gramophone.extras.importer.YtDlp
import org.akanework.gramophone.extras.podcast.PodcastStore
import java.io.File

/**
 * Finds songs with no album art and gives them some: the cover is looked up
 * in iTunes, then Deezer, by the song's own artist and title, and embedded
 * into the file with ffmpeg (audio and every other tag untouched).
 *
 * Three steps, so the user sees what will happen before any file changes:
 * [scan] lists songs without an embedded cover, [lookUp] finds covers,
 * [apply] writes the ones the user kept. Writing to files another app made
 * needs the user's yes — the screen asks Android for it in one dialog
 * (MediaStore.createWriteRequest) before calling [apply].
 */
object CoverFixer {

    private const val TAG = "CoverFixer"

    data class Song(
        val id: Long,
        val path: String,
        val title: String,
        val artist: String?,
        val album: String?,
        val durationSec: Int,
    ) {
        val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        /** Ogg and Opus cannot carry a cover written this way. */
        val canEmbed: Boolean get() = path.substringAfterLast('.').lowercase() in EMBEDDABLE
    }

    data class Found(val song: Song, val cover: File, val source: String)

    private val EMBEDDABLE = setOf("m4a", "mp4", "aac", "mp3", "flac", "alac")

    /** Songs with no embedded picture, from MediaStore; [onProgress] gets (done, total). */
    @Suppress("DEPRECATION")
    suspend fun scan(context: Context, onProgress: (Int, Int) -> Unit): List<Song> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val hidden = runCatching { FilterStore(app).hiddenPaths }.getOrDefault(emptySet())
        val longMs = PodcastStore.LONG_AUDIO_MINUTES * 60_000L
        val all = ArrayList<Song>()
        app.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
            ),
            null, null, MediaStore.Audio.Media.TITLE,
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(1) ?: continue
                val duration = c.getLong(5)
                if (path in hidden || duration <= 0 || duration >= longMs) continue
                all += Song(
                    id = c.getLong(0), path = path,
                    title = c.getString(2) ?: path.substringAfterLast('/').substringBeforeLast('.'),
                    artist = c.getString(3)?.takeIf { it != MediaStore.UNKNOWN_STRING && it.isNotBlank() },
                    album = c.getString(4)?.takeIf { it != MediaStore.UNKNOWN_STRING && it.isNotBlank() },
                    durationSec = (duration / 1000).toInt(),
                )
            }
        }
        val missing = ArrayList<Song>()
        val retriever = MediaMetadataRetriever()
        try {
            all.forEachIndexed { i, song ->
                if (i % 10 == 0) onProgress(i, all.size)
                val hasPicture = runCatching {
                    retriever.setDataSource(app, song.uri)
                    retriever.embeddedPicture != null
                }.getOrDefault(true) // unreadable: leave it alone
                if (!hasPicture) missing += song
            }
        } finally {
            runCatching { retriever.release() }
        }
        onProgress(all.size, all.size)
        missing
    }

    /** Looks a cover up for each song; [onProgress] gets (done, total). */
    suspend fun lookUp(context: Context, songs: List<Song>, onProgress: (Int, Int) -> Unit): List<Found> =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "covers").apply { mkdirs() }
            songs.filter { it.canEmbed }.mapIndexedNotNull { i, song ->
                onProgress(i, songs.size)
                // Files named "Artist - Title" with no tags: the same clean-up
                // the YouTube importer uses works on them.
                val meta = if (song.artist != null) {
                    MetadataProbe.cleanUp(
                        videoId = "", track = song.title, artist = song.artist, album = song.album, year = null,
                        duration = song.durationSec, thumbnail = null, rawTitle = song.title, uploader = null,
                    )
                } else {
                    MetadataProbe.cleanUp(
                        videoId = "", track = null, artist = null, album = null, year = null,
                        duration = song.durationSec, thumbnail = null, rawTitle = song.title, uploader = null,
                    )
                }
                val art = runCatching { ArtworkFinder.findInCatalogues(meta) }.getOrNull() ?: return@mapIndexedNotNull null
                val file = File(dir, "${song.id}.jpg").apply { writeBytes(art.jpeg) }
                Found(song, file, art.source)
            }.also { onProgress(songs.size, songs.size) }
        }

    /**
     * Embeds each cover. The caller has already got write access (Android 11+)
     * or the storage permission (older). Returns how many files changed.
     */
    suspend fun apply(context: Context, found: List<Found>, onProgress: (Int, Int) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            YtDlp.ensureInitialized(app) // unpacks ffmpeg on first use
            val work = File(app.cacheDir, "cover-work").apply { mkdirs() }
            var done = 0
            found.forEachIndexed { i, f ->
                onProgress(i, found.size)
                val ext = f.song.path.substringAfterLast('.')
                val input = File(work, "in.$ext")
                val output = File(work, "out.$ext")
                try {
                    app.contentResolver.openInputStream(f.song.uri)?.use { src ->
                        input.outputStream().use { src.copyTo(it) }
                    } ?: return@forEachIndexed
                    if (!Ffmpeg.attachCover(app, input, f.cover, output)) return@forEachIndexed
                    app.contentResolver.openOutputStream(f.song.uri, "wt")?.use { dst ->
                        output.inputStream().use { it.copyTo(dst) }
                    } ?: return@forEachIndexed
                    MediaScannerConnection.scanFile(app, arrayOf(f.song.path), null, null)
                    done++
                } catch (e: Exception) {
                    Log.w(TAG, "could not add a cover to ${f.song.path}", e)
                } finally {
                    input.delete(); output.delete()
                }
            }
            onProgress(found.size, found.size)
            done
        }
}
