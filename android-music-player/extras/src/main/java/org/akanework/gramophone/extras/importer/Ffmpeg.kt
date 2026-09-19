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

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drives the ffmpeg binary that ships inside youtubedl-android.
 *
 * yt-dlp can embed *its own* thumbnail, but it has no way to be handed a cover
 * we fetched from somewhere else — so after the download we run one more copy
 * pass ourselves to attach the real artwork and correct the tags.
 *
 * We deliberately reuse the binary youtubedl-android already unpacked rather
 * than bundling a second ffmpeg: a second copy would add ~35 MB to the APK for
 * no benefit. The binary lives in the APK's native library directory and is
 * dynamically linked against shared objects in the unpacked package tree, so
 * it only starts when `LD_LIBRARY_PATH` points back at that tree.
 */
object Ffmpeg {
    private const val TAG = "Ffmpeg"
    private const val TIMEOUT_SECONDS = 180L

    /**
     * Rewrites [audio] with [cover] attached and [meta] applied.
     *
     * Returns the tagged file on success. On any failure the original file is
     * returned untouched: a song with yt-dlp's own tags and no artwork is a far
     * better outcome than a failed import, so this step never fails the job.
     */
    suspend fun applyCoverAndTags(
        context: Context,
        audio: File,
        cover: File?,
        meta: TrackMetadata,
        artwork: Artwork?,
    ): File = withContext(Dispatchers.IO) {
        val binary = YtDlp.ffmpegBinary(context)
        if (binary == null) {
            Log.w(TAG, "ffmpeg binary not present for this ABI; keeping yt-dlp's tags")
            return@withContext audio
        }

        val extension = audio.extension.lowercase()
        // Single source of truth: the same flag the format picker shows the user.
        val attachCover = cover != null && cover.length() > 0 &&
            AudioFormat.entries.firstOrNull { it.extension == extension }?.supportsCoverArt != false
        val output = File(audio.parentFile, "tagged_${audio.name}")

        val args = buildList {
            add(binary.absolutePath)
            add("-y")
            add("-loglevel"); add("error")
            add("-i"); add(audio.absolutePath)
            if (attachCover) {
                add("-i"); add(cover!!.absolutePath)
            }

            // Copy the audio untouched — this pass must never re-encode.
            add("-map"); add("0:a")
            if (attachCover) {
                add("-map"); add("1:v")
                add("-disposition:v:0"); add("attached_pic")
            }
            add("-c"); add("copy")

            if (extension == "mp3") {
                add("-id3v2_version"); add("3")
                if (attachCover) {
                    add("-metadata:s:v"); add("title=Album cover")
                    add("-metadata:s:v"); add("comment=Cover (front)")
                }
            }

            add("-metadata"); add("title=${meta.title}")
            meta.artist?.takeIf { it.isNotBlank() }?.let {
                add("-metadata"); add("artist=$it")
                // Without an album artist the library groups compilations oddly.
                add("-metadata"); add("album_artist=$it")
            }
            // Prefer what the catalogue told us: a YouTube video knows neither
            // its album nor its release year.
            (artwork?.album ?: meta.album)?.takeIf { it.isNotBlank() }?.let {
                add("-metadata"); add("album=$it")
            }
            (meta.year ?: artwork?.year)?.takeIf { it.isNotBlank() }?.let {
                add("-metadata"); add("date=$it")
            }
            add("-metadata"); add("comment=https://youtu.be/${meta.videoId}")

            add(output.absolutePath)
        }

        val ok = run(context, args)
        if (ok && output.length() > 0) {
            if (audio.delete() && output.renameTo(audio)) {
                audio
            } else {
                output
            }
        } else {
            output.delete()
            Log.w(TAG, "tagging pass failed; keeping yt-dlp's own tags")
            audio
        }
    }

    /** Runs ffmpeg to completion. Returns true on exit code 0. */
    private fun run(context: Context, args: List<String>): Boolean = try {
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] = YtDlp.nativeLibraryPath(context)
                environment()["TMPDIR"] = context.cacheDir.absolutePath
            }
            .start()

        // Drain the pipe: a full buffer would deadlock the child.
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)

        if (!finished) {
            process.destroyForcibly()
            Log.w(TAG, "ffmpeg timed out after $TIMEOUT_SECONDS s")
            false
        } else if (process.exitValue() != 0) {
            Log.w(TAG, "ffmpeg exited ${process.exitValue()}: ${output.takeLast(500)}")
            false
        } else {
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not run ffmpeg", e)
        false
    }
}
