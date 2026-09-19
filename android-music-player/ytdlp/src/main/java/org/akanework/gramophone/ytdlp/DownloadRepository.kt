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

import android.content.Context
import android.net.Uri
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Output formats offered in the UI.
 *
 * The default is deliberately M4A. YouTube already serves AAC in an MP4
 * container, so asking for M4A lets yt-dlp *remux* — it rewrites the container
 * without touching the audio, which is fast and lossless. Choosing MP3 forces a
 * re-encode of already-lossy audio, which is slower and strictly worse quality;
 * it is offered only because some car stereos and older hardware need it.
 */
enum class AudioFormat(
    val id: String,
    val extension: String,
    /** Format selector biased towards a stream we can remux rather than re-encode. */
    val selector: String,
    val label: String,
    val summary: String,
) {
    M4A(
        id = "m4a", extension = "m4a",
        selector = "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio/best",
        label = "M4A / AAC",
        summary = "Recommended — no re-encode, best quality",
    ),
    OPUS(
        id = "opus", extension = "opus",
        selector = "bestaudio[ext=webm][acodec=opus]/bestaudio[acodec=opus]/bestaudio/best",
        label = "Opus",
        summary = "Smallest files, no re-encode",
    ),
    MP3(
        id = "mp3", extension = "mp3",
        selector = "bestaudio/best",
        label = "MP3",
        summary = "Re-encoded — only for players that need it",
    );

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: M4A
    }
}

/** Where a job has got to. Ordered roughly by the pipeline it walks through. */
sealed interface JobStage {
    data object Queued : JobStage
    data object Reading : JobStage
    data class Downloading(val progress: Float, val etaSeconds: Long) : JobStage
    data object FindingArtwork : JobStage
    data object Tagging : JobStage
    data object Importing : JobStage
    data class Done(val uri: Uri?, val artworkSource: String?) : JobStage
    data class Failed(val message: String) : JobStage
    data object Cancelled : JobStage

    val isTerminal: Boolean
        get() = this is Done || this is Failed || this is Cancelled
}

data class DownloadJob(
    val id: String,
    val url: String,
    val format: AudioFormat,
    val stage: JobStage = JobStage.Queued,
    val title: String? = null,
    val artist: String? = null,
) {
    val label: String get() = title ?: url
}

/**
 * The import queue.
 *
 * Jobs run strictly one at a time. yt-dlp spawns a full CPython process and
 * ffmpeg on top of it; running several at once on a phone mostly buys memory
 * pressure and thermal throttling rather than throughput.
 */
class DownloadRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<String>(Channel.UNLIMITED)

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    init {
        scope.launch {
            for (jobId in queue) {
                runCatching { process(jobId) }
                    .onFailure { Log.e(TAG, "job $jobId blew up", it) }
            }
        }
    }

    fun enqueue(url: String, format: AudioFormat): String {
        val id = UUID.randomUUID().toString()
        _jobs.value += DownloadJob(id = id, url = url.trim(), format = format)
        queue.trySend(id)
        return id
    }

    fun cancel(jobId: String) {
        YtDlp.cancel(jobId)
        update(jobId) { it.copy(stage = JobStage.Cancelled) }
    }

    fun clearFinished() {
        _jobs.value = _jobs.value.filterNot { it.stage.isTerminal }
    }

    // ------------------------------------------------------------------

    private suspend fun process(jobId: String) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        if (job.stage is JobStage.Cancelled) return

        val workDir = File(context.cacheDir, "ytdlp-work/$jobId").apply { mkdirs() }
        try {
            update(jobId) { it.copy(stage = JobStage.Reading) }
            YtDlp.ensureInitialized(context)

            val meta = MetadataProbe.probe(job.url)
            update(jobId) {
                it.copy(title = meta.title, artist = meta.artist)
            }

            // --- download -------------------------------------------------
            update(jobId) { it.copy(stage = JobStage.Downloading(0f, 0)) }
            val request = YoutubeDLRequest(job.url)
                .addOption("--no-playlist")
                .addOption("--ignore-config")
                .addOption("--no-mtime")
                .addOption("--newline")
                .addOption("-f", job.format.selector)
                .addOption("-x")
                .addOption("--audio-format", job.format.id)
                .addOption("--audio-quality", "0")
                // yt-dlp writes its own tags with its own ffmpeg here. If our
                // artwork pass later fails, the file still arrives tagged.
                .addOption("--embed-metadata")
                .addOption("-o", File(workDir, "%(id)s.%(ext)s").absolutePath)

            val response = YoutubeDL.getInstance().execute(request, jobId) { progress, eta, _ ->
                update(jobId) { current ->
                    if (current.stage is JobStage.Cancelled) current
                    else current.copy(stage = JobStage.Downloading(progress.coerceIn(0f, 100f), eta))
                }
            }
            if (response.exitCode != 0) {
                throw YtDlpFailure("Download failed", response.err)
            }
            if (currentStage(jobId) is JobStage.Cancelled) return

            val audio = workDir.listFiles()
                ?.filter { it.isFile && it.length() > 0 }
                ?.maxByOrNull { it.length() }
                ?: throw YtDlpFailure("yt-dlp reported success but produced no file")

            // --- artwork --------------------------------------------------
            update(jobId) { it.copy(stage = JobStage.FindingArtwork) }
            val artwork = ArtworkFinder.find(meta)
            val coverFile = artwork?.let {
                File(workDir, "cover.jpg").apply { writeBytes(it.jpeg) }
            }

            // --- tagging --------------------------------------------------
            update(jobId) { it.copy(stage = JobStage.Tagging) }
            val tagged = Ffmpeg.applyCoverAndTags(
                context = context,
                audio = audio,
                cover = coverFile,
                meta = meta,
                artwork = artwork,
            )

            // --- publish --------------------------------------------------
            update(jobId) { it.copy(stage = JobStage.Importing) }
            val renamed = renameForLibrary(tagged, meta, job.format)
            val uri = MusicImporter.publish(context, renamed, meta, artwork?.album)
                ?: throw YtDlpFailure("Could not add the file to your music library")

            update(jobId) { it.copy(stage = JobStage.Done(uri, artwork?.source)) }
        } catch (e: YoutubeDL.CanceledException) {
            update(jobId) { it.copy(stage = JobStage.Cancelled) }
        } catch (e: Exception) {
            Log.e(TAG, "import failed for ${job.url}", e)
            update(jobId) {
                it.copy(stage = JobStage.Failed(e.message ?: e::class.java.simpleName))
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** `Artist - Title.m4a`, sanitised for FAT-style filesystems. */
    private suspend fun renameForLibrary(
        file: File,
        meta: TrackMetadata,
        format: AudioFormat,
    ): File = withContext(Dispatchers.IO) {
        val safe = meta.displayName
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
            .trim()
            .take(120)
            .ifBlank { meta.videoId.ifBlank { "track" } }
        val target = File(file.parentFile, "$safe.${file.extension.ifBlank { format.extension }}")
        if (file.renameTo(target)) target else file
    }

    private fun currentStage(jobId: String) = _jobs.value.firstOrNull { it.id == jobId }?.stage

    private fun update(jobId: String, transform: (DownloadJob) -> DownloadJob) {
        _jobs.value = _jobs.value.map { if (it.id == jobId) transform(it) else it }
    }

    companion object {
        private const val TAG = "DownloadRepository"

        @Volatile
        private var instance: DownloadRepository? = null

        fun get(context: Context): DownloadRepository =
            instance ?: synchronized(this) {
                instance ?: DownloadRepository(context.applicationContext).also { instance = it }
            }
    }
}
