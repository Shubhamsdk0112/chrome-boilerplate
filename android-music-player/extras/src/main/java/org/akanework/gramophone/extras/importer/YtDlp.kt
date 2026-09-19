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
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the lifecycle of the bundled yt-dlp runtime.
 *
 * The native payload is ~115 MB once unpacked (CPython ~40 MB, ffmpeg ~74 MB),
 * and [YoutubeDL.init] does that unpacking synchronously on first call. That is
 * far too expensive to do from `Application.onCreate` — it would add seconds to
 * the first cold start for every user, including the ones who never download
 * anything. So initialisation is deferred until something actually needs it,
 * and callers await [ensureInitialized] instead.
 */
object YtDlp {
    private const val TAG = "YtDlp"

    /** Guards the one-time unpack; concurrent callers await the same work. */
    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

    /**
     * Unpacks CPython, ffmpeg and yt-dlp if they are not already on disk.
     * Safe to call repeatedly and from several coroutines at once.
     *
     * @throws com.yausername.youtubedl_android.YoutubeDLException if the payload
     *         cannot be unpacked (most often: the device is out of storage).
     */
    suspend fun ensureInitialized(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        initMutex.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                val started = System.currentTimeMillis()
                YoutubeDL.getInstance().init(app)
                // Registers the ffmpeg/ffprobe binaries with yt-dlp; without this
                // `-x` (audio extraction) and every embedding step fails.
                FFmpeg.getInstance().init(app)
                Log.i(TAG, "yt-dlp runtime ready in ${System.currentTimeMillis() - started} ms")
            }
            initialized = true
        }
    }

    /** yt-dlp's own version string, e.g. `2026.08.19`, or null before first init. */
    suspend fun version(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching { YoutubeDL.getInstance().version(context.applicationContext) }.getOrNull()
    }

    /**
     * Pulls a newer yt-dlp into place.
     *
     * This is the single most important maintenance action in the whole app:
     * YouTube changes its player regularly and a stale extractor is the usual
     * cause of "this suddenly stopped working". Updating swaps only the ~3 MB
     * Python payload, so it is cheap and needs no new APK.
     */
    suspend fun update(
        context: Context,
        channel: YoutubeDL.UpdateChannel = YoutubeDL.UpdateChannel._STABLE
    ): YoutubeDL.UpdateStatus? = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, channel)
    }

    /** Cancels a running yt-dlp process previously started with [processId]. */
    fun cancel(processId: String): Boolean =
        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }.getOrDefault(false)

    // ---------------------------------------------------------------------
    // ffmpeg location
    //
    // youtubedl-android ships ffmpeg as `libffmpeg.so` inside the APK's native
    // library directory and unpacks its shared objects into the app's
    // no-backup files dir. We reuse exactly those paths rather than shipping a
    // second copy of ffmpeg: see Ffmpeg.kt for why we invoke it directly.
    // ---------------------------------------------------------------------

    /** The ffmpeg executable, or null if this ABI did not ship one. */
    fun ffmpegBinary(context: Context): File? =
        File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").takeIf { it.canExecute() }

    /**
     * Directories that must be on `LD_LIBRARY_PATH` for [ffmpegBinary] to start.
     * The binary is dynamically linked against shared objects that live in the
     * unpacked package tree, not in the APK.
     */
    fun nativeLibraryPath(context: Context): String {
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        return listOf("ffmpeg", "python")
            .map { File(packages, "$it/usr/lib").absolutePath }
            .joinToString(File.pathSeparator)
    }
}
