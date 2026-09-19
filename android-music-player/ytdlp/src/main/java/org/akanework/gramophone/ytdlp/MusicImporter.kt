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

package org.akanework.gramophone.ytdlp

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Publishes a finished download into the shared music library.
 *
 * This is the whole integration with Gramophone, and it is deliberately tiny.
 * Gramophone's [uk.akane.libphonograph.reader.FlowReader] already watches
 * `MediaStore.Files`, so the moment a row lands here the library refreshes and
 * the song appears — no hook into the player, no reach into its internals, and
 * nothing to re-do when upstream changes.
 *
 * Downloads are staged in the app's own cache directory and only copied here
 * once they are complete and tagged, so a cancelled or failed job can never
 * leave a half-written file in the user's music folder.
 */
object MusicImporter {
    private const val TAG = "MusicImporter"

    /** Sub-folder of Music/ that imported songs live in. */
    const val SUB_DIRECTORY = "Gramophone"

    suspend fun publish(context: Context, file: File, meta: TrackMetadata, album: String?): Uri? =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishViaMediaStore(context, file, meta, album)
            } else {
                publishLegacy(context, file, meta, album)
            }
        }

    /**
     * Android 10+: insert a pending row, stream into it, then clear the pending
     * flag. Needs no storage permission at all — the file belongs to us because
     * we created it.
     */
    private fun publishViaMediaStore(
        context: Context,
        file: File,
        meta: TrackMetadata,
        album: String?,
    ): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media
            .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val values = baseValues(file, meta, album).apply {
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$SUB_DIRECTORY")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values) ?: run {
            Log.w(TAG, "MediaStore refused the insert")
            return null
        }

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("no output stream for $uri")

            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }, null, null)

            file.delete()
            uri
        } catch (e: Exception) {
            Log.e(TAG, "failed to publish ${file.name}", e)
            // Don't leave an orphaned pending row behind.
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /**
     * Android 9 and older: there is no pending flag, so write the file into the
     * public music folder and let the media scanner index it.
     * Requires WRITE_EXTERNAL_STORAGE, which the UI asks for beforehand.
     */
    @Suppress("DEPRECATION")
    private fun publishLegacy(
        context: Context,
        file: File,
        meta: TrackMetadata,
        album: String?,
    ): Uri? = try {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            SUB_DIRECTORY,
        ).apply { mkdirs() }

        val target = uniqueFile(directory, file.name)
        file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        file.delete()

        // Scanning is what actually creates the MediaStore row (and so what
        // wakes Gramophone's library observer).
        MediaScannerConnection.scanFile(
            context, arrayOf(target.absolutePath), arrayOf(mimeTypeOf(target.extension)), null
        )

        context.contentResolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            baseValues(target, meta, album).apply {
                put(MediaStore.Audio.Media.DATA, target.absolutePath)
            },
        )
    } catch (e: Exception) {
        Log.e(TAG, "legacy publish failed", e)
        null
    }

    private fun baseValues(file: File, meta: TrackMetadata, album: String?) = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
        put(MediaStore.Audio.Media.MIME_TYPE, mimeTypeOf(file.extension))
        put(MediaStore.Audio.Media.TITLE, meta.title)
        put(MediaStore.Audio.Media.IS_MUSIC, 1)
        meta.artist?.takeIf { it.isNotBlank() }?.let { put(MediaStore.Audio.Media.ARTIST, it) }
        (album ?: meta.album)?.takeIf { it.isNotBlank() }
            ?.let { put(MediaStore.Audio.Media.ALBUM, it) }
        meta.year?.toIntOrNull()?.let { put(MediaStore.Audio.Media.YEAR, it) }
        if (meta.durationSeconds > 0) {
            put(MediaStore.Audio.Media.DURATION, meta.durationSeconds * 1000)
        }
    }

    private fun uniqueFile(directory: File, name: String): File {
        val candidate = File(directory, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var n = 1
        while (true) {
            val suffix = if (extension.isEmpty()) "" else ".$extension"
            val next = File(directory, "$stem ($n)$suffix")
            if (!next.exists()) return next
            n++
        }
    }

    private fun mimeTypeOf(extension: String) = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "opus" -> "audio/opus"
        "ogg", "oga" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }
}
