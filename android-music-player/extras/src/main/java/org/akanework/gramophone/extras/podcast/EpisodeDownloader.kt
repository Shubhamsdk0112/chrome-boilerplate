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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Plain HTTP download of one episode, resumable.
 *
 * Writes to `<target>.part` and asks the server for the remainder with a
 * `Range` header, so a download cut off at 80% — network drop, process
 * death — carries on from 80%. Only when the file is complete is it renamed
 * into place, so a half file is never mistaken for an episode.
 *
 * Cancellation is cooperative: the caller cancels the coroutine, the loop
 * notices between chunks, the `.part` stays for next time.
 */
object EpisodeDownloader {

    private const val TAG = "EpisodeDownloader"
    private const val CHUNK = 64 * 1024
    private const val MAX_ATTEMPTS = 4

    class Progress(val bytes: Long, val total: Long) {
        val percent: Float get() = if (total > 0) (bytes * 100f / total).coerceIn(0f, 100f) else 0f
    }

    suspend fun download(
        url: String,
        target: File,
        onProgress: (Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        var attempt = 0
        var lastError: IOException? = null
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            ensureActive()
            try {
                if (fetch(url, part, onProgress)) {
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) throw IOException("could not move ${part.name} into place")
                    return@withContext target
                }
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "attempt $attempt failed for $url: ${e.message}")
            }
        }
        throw lastError ?: IOException("download failed")
    }

    /** One pass. Returns true when the file is complete. */
    private suspend fun fetch(url: String, part: File, onProgress: (Progress) -> Unit): Boolean {
        val have = if (part.isFile) part.length() else 0L
        PodcastSearch.Http.open(url).use { c ->
            if (have > 0) c.setHeader("Range", "bytes=$have-")
            val code = c.responseCode
            val resuming = code == 206
            when {
                code == 200 -> Unit
                resuming -> Unit
                code == 416 -> {
                    // Asked for bytes past the end: the .part is already whole,
                    // or the server changed the file. Trust a HEAD-less check:
                    // treat as complete only if the server's total matches.
                    val total = contentRangeTotal(c.header("Content-Range"))
                    return total != null && total == have
                }
                else -> throw IOException("HTTP $code")
            }
            val total = if (resuming) {
                contentRangeTotal(c.header("Content-Range")) ?: (have + c.contentLength)
            } else {
                c.contentLength.takeIf { it > 0 } ?: -1L
            }
            // A 200 to a Range request means the server ignored it: start over.
            val offset = if (resuming) have else 0L
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(offset)
                out.seek(offset)
                var written = offset
                val buffer = ByteArray(CHUNK)
                c.inputStream.use { input ->
                    while (true) {
                        currentCoroutineContextEnsureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        onProgress(Progress(written, total))
                    }
                }
                if (total > 0 && written < total) {
                    throw IOException("connection closed at $written of $total")
                }
                return true
            }
        }
    }

    private suspend fun currentCoroutineContextEnsureActive() =
        kotlin.coroutines.coroutineContext.ensureActive()

    /** "bytes 100-999/1000" → 1000. */
    internal fun contentRangeTotal(header: String?): Long? =
        header?.substringAfterLast('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 }
}
