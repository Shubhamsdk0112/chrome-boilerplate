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
import android.net.Uri
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.extras.podcast.Episode
import org.akanework.gramophone.extras.podcast.EpisodeDownloader
import org.akanework.gramophone.extras.podcast.Podcast
import org.akanework.gramophone.extras.podcast.PodcastSearch
import org.akanework.gramophone.extras.podcast.PodcastStore
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
    /**
     * Whether a cover image can be embedded in this container.
     *
     * Ogg/Opus stores artwork as a base64 METADATA_BLOCK_PICTURE in the comment
     * header, which ffmpeg cannot write from an image input, so those downloads
     * arrive with tags but no cover. The picker says so rather than letting
     * someone pick Opus and quietly get an artless library.
     */
    val supportsCoverArt: Boolean,
) {
    M4A(
        id = "m4a", extension = "m4a",
        selector = "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio/best",
        label = "M4A / AAC",
        summary = "Recommended — no re-encode, best quality, album art",
        supportsCoverArt = true,
    ),
    OPUS(
        id = "opus", extension = "opus",
        selector = "bestaudio[ext=webm][acodec=opus]/bestaudio[acodec=opus]/bestaudio/best",
        label = "Opus",
        summary = "Smallest files, no re-encode — but no embedded album art",
        supportsCoverArt = false,
    ),
    MP3(
        id = "mp3", extension = "mp3",
        selector = "bestaudio/best",
        label = "MP3",
        summary = "Re-encoded — only for players that need it",
        supportsCoverArt = true,
    );

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: M4A
    }
}

/** Where a job has got to. Ordered roughly by the pipeline it walks through. */
sealed interface JobStage {
    data object Queued : JobStage
    data object Reading : JobStage
    /** yt-dlp was too old for YouTube and is being replaced before a retry. */
    data object Updating : JobStage
    data class Downloading(val progress: Float, val etaSeconds: Long) : JobStage
    data object FindingArtwork : JobStage
    data object Tagging : JobStage
    data object Importing : JobStage
    /** [alreadyImported]: the song was in the library before this job ran. */
    data class Done(
        val uri: Uri?,
        val artworkSource: String?,
        val alreadyImported: Boolean = false,
    ) : JobStage
    data class Failed(val message: String) : JobStage
    data object Cancelled : JobStage

    val isTerminal: Boolean
        get() = this is Done || this is Failed || this is Cancelled
}

/** What a job downloads. Podcast episodes share the queue, service and cards. */
enum class JobKind { YOUTUBE, PODCAST }

data class DownloadJob(
    val id: String,
    val url: String,
    val format: AudioFormat,
    val stage: JobStage = JobStage.Queued,
    val title: String? = null,
    val artist: String? = null,
    val videoId: String? = null,
    /** A small poster for the card, cached on disk once metadata is known. */
    val thumbnail: File? = null,
    val kind: JobKind = JobKind.YOUTUBE,
    /** Podcast jobs: which episode of which feed. */
    val feedUrl: String? = null,
    val episodeGuid: String? = null,
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

    /** True once the persisted list has been read; the UI shows nothing before. */
    private val _restored = MutableStateFlow(false)
    val restored: StateFlow<Boolean> = _restored.asStateFlow()

    private var pendingSave: Job? = null

    init {
        scope.launch {
            // Whatever the last process left behind. Jobs that were mid-flight
            // come back queued and go straight into the channel; yt-dlp resumes
            // the .part in the work directory, which is not cleared on a kill.
            val saved = JobStore.load(context)
            _jobs.value = saved
            _restored.value = true
            val resumed = saved.filterNot { it.stage.isTerminal }
            if (resumed.isNotEmpty()) {
                Log.i(TAG, "resuming ${resumed.size} interrupted job(s)")
                resumed.forEach { queue.trySend(it.id) }
                DownloadService.ensureRunning(context)
            }
            for (jobId in queue) {
                runCatching { process(jobId) }
                    .onFailure { Log.e(TAG, "job $jobId blew up", it) }
            }
        }
    }

    /**
     * Writes the list shortly after the last change. Progress updates arrive
     * many times a second; one write per burst is plenty, and a kill in the
     * debounce window costs nothing worse than a re-run of the current job.
     */
    private fun scheduleSave() {
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(400)
            JobStore.save(context, _jobs.value)
        }
    }

    fun enqueue(url: String, format: AudioFormat): String {
        val trimmed = url.trim()
        // A link that is already in flight (a double tap on Share, or the
        // YouTube app resending it) must not queue the song twice. A finished
        // or failed job is different: sharing it again is how you retry.
        _jobs.value.firstOrNull { it.url == trimmed && !it.stage.isTerminal }
            ?.let { return it.id }
        val id = UUID.randomUUID().toString()
        _jobs.value += DownloadJob(id = id, url = trimmed, format = format)
        scheduleSave()
        queue.trySend(id)
        return id
    }

    /** Queues one podcast episode. Returns the existing job when it is already queued. */
    fun enqueueEpisode(podcast: Podcast, episode: Episode): String {
        _jobs.value.firstOrNull { it.episodeGuid == episode.guid && !it.stage.isTerminal }
            ?.let { return it.id }
        val id = UUID.randomUUID().toString()
        _jobs.value += DownloadJob(
            id = id,
            url = episode.audioUrl,
            format = AudioFormat.M4A,
            title = episode.title,
            artist = podcast.title,
            kind = JobKind.PODCAST,
            feedUrl = podcast.feedUrl,
            episodeGuid = episode.guid,
        )
        scheduleSave()
        queue.trySend(id)
        DownloadService.ensureRunning(context)
        return id
    }

    /** Coroutines of podcast downloads in flight, so cancel() can stop them. */
    private val running = java.util.concurrent.ConcurrentHashMap<String, Job>()

    fun cancel(jobId: String) {
        YtDlp.cancel(jobId)
        running[jobId]?.cancel()
        update(jobId) { it.copy(stage = JobStage.Cancelled) }
    }

    fun clearFinished() {
        _jobs.value = _jobs.value.filterNot { it.stage.isTerminal }
        scheduleSave()
    }

    /** Queues a failed or cancelled job again, in place of the old card. */
    fun retry(jobId: String) {
        val old = _jobs.value.firstOrNull { it.id == jobId } ?: return
        if (!old.stage.isTerminal) return
        _jobs.value = _jobs.value.filterNot { it.id == jobId }
        enqueue(old.url, old.format)
    }

    // ------------------------------------------------------------------

    private suspend fun process(jobId: String) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        if (job.stage is JobStage.Cancelled) return
        if (job.kind == JobKind.PODCAST) {
            processEpisode(job)
            return
        }

        val workDir = File(context.cacheDir, "ytdlp-work/$jobId").apply { mkdirs() }
        try {
            update(jobId) { it.copy(stage = JobStage.Reading) }
            YtDlp.ensureInitialized(context)

            // The bundled yt-dlp is as old as the APK, and YouTube changes
            // underneath it every few months. When the failure is that kind of
            // failure, update once and go again before showing anyone an error;
            // most of the time that is the whole fix.
            val (meta, audio) = try {
                fetch(job, workDir) ?: return
            } catch (e: Exception) {
                val stderr = when (e) {
                    is YoutubeDLException -> e.message
                    is YtDlpFailure -> e.stderr
                    else -> null
                }
                if (!DownloadError.needsUpdate(stderr)) throw e
                Log.i(TAG, "yt-dlp looks stale, updating before retrying ${job.url}")
                update(jobId) { it.copy(stage = JobStage.Updating) }
                val status = runCatching { YtDlp.update(context) }
                    .onFailure { Log.w(TAG, "yt-dlp update failed", it) }
                    .getOrNull()
                if (status != YoutubeDL.UpdateStatus.DONE) throw e
                if (currentStage(jobId) is JobStage.Cancelled) return
                workDir.listFiles()?.forEach { it.delete() }
                update(jobId) { it.copy(stage = JobStage.Reading) }
                fetch(job, workDir) ?: return
            }
            if (currentStage(jobId) is JobStage.Cancelled) return

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

            ImportIndex.record(context, meta.videoId, uri)
            update(jobId) { it.copy(stage = JobStage.Done(uri, artwork?.source)) }
        } catch (e: YoutubeDL.CanceledException) {
            update(jobId) { it.copy(stage = JobStage.Cancelled) }
        } catch (e: YoutubeDLException) {
            // youtubedl-android throws on a non-zero exit rather than returning
            // it, with yt-dlp's whole stderr as the message. Seen on a device:
            // without this branch the card shows a Python traceback.
            Log.e(TAG, "import failed for ${job.url}", e)
            update(jobId) { it.copy(stage = JobStage.Failed(DownloadError.humanize(e.message))) }
        } catch (e: Exception) {
            Log.e(TAG, "import failed for ${job.url}", e)
            update(jobId) {
                it.copy(stage = JobStage.Failed(e.message ?: e::class.java.simpleName))
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * A podcast episode: plain HTTP into the app's own Podcasts directory
     * (never MediaStore, so it stays out of the music library), resumable
     * through EpisodeDownloader's .part file.
     */
    private suspend fun processEpisode(job: DownloadJob) {
        val jobId = job.id
        val store = PodcastStore.get(context)
        val podcast = job.feedUrl?.let { store.podcast(it) }
        val episode = job.episodeGuid?.let { store.episode(it) }
        if (podcast == null || episode == null) {
            update(jobId) { it.copy(stage = JobStage.Failed("This episode is no longer in your subscriptions.")) }
            return
        }
        // Poster: the episode's own image if it has one, else the show's.
        val imageUrl = episode.imageUrl ?: podcast.imageUrl
        if (imageUrl != null) {
            val thumb = File(context.cacheDir, "ytdlp-thumbs/podcast-${imageUrl.hashCode()}.jpg")
            if (!thumb.isFile) {
                PodcastSearch.Http.bytes(imageUrl)?.let { bytes ->
                    runCatching { thumb.parentFile?.mkdirs(); thumb.writeBytes(bytes) }
                }
            }
            if (thumb.isFile) update(jobId) { it.copy(thumbnail = thumb) }
        }

        val target = store.targetFile(podcast, episode)
        if (target.isFile && target.length() > 0) {
            store.recordDownload(episode.guid, target)
            update(jobId) { it.copy(stage = JobStage.Done(Uri.fromFile(target), null, alreadyImported = true)) }
            return
        }

        update(jobId) { it.copy(stage = JobStage.Downloading(0f, 0)) }
        val started = System.currentTimeMillis()
        var lastPercent = -1
        val worker = scope.launch {
            try {
                EpisodeDownloader.download(episode.audioUrl, target) { p ->
                    val percent = p.percent.toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        val elapsed = (System.currentTimeMillis() - started) / 1000.0
                        val rate = if (elapsed > 0) p.bytes / elapsed else 0.0
                        val eta = if (rate > 0 && p.total > 0) ((p.total - p.bytes) / rate).toLong() else 0L
                        update(jobId) { current ->
                            if (current.stage is JobStage.Cancelled) current
                            else current.copy(stage = JobStage.Downloading(p.percent, eta))
                        }
                    }
                }
                store.recordDownload(episode.guid, target)
                update(jobId) { it.copy(stage = JobStage.Done(Uri.fromFile(target), null)) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                update(jobId) { it.copy(stage = JobStage.Cancelled) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "episode download failed for ${episode.audioUrl}", e)
                update(jobId) {
                    it.copy(stage = JobStage.Failed(e.message?.let { m -> "Download failed: $m" } ?: "Download failed."))
                }
            }
        }
        running[jobId] = worker
        try {
            worker.join()
        } finally {
            running.remove(jobId)
        }
    }

    /**
     * The yt-dlp half of a job: read the tags, then download the audio into
     * [workDir]. Everything after this (artwork, tagging, publishing) is ours
     * and does not depend on YouTube cooperating.
     */
    private suspend fun fetch(job: DownloadJob, workDir: File): Pair<TrackMetadata, File>? {
        val jobId = job.id
        val meta = MetadataProbe.probe(job.url)
        update(jobId) {
            it.copy(title = meta.title, artist = meta.artist, videoId = meta.videoId)
        }

        // The card's poster. Small, cached per video, and not worth failing
        // the job over.
        val thumb = File(context.cacheDir, "ytdlp-thumbs/${meta.videoId}.jpg")
        if (!thumb.isFile && meta.videoId.isNotBlank()) {
            ArtworkFinder.fetchThumbnail(meta)?.let { bytes ->
                runCatching {
                    thumb.parentFile?.mkdirs()
                    thumb.writeBytes(bytes)
                }
            }
        }
        if (thumb.isFile) update(jobId) { it.copy(thumbnail = thumb) }

        // Sharing the same video twice used to produce "Song (1).m4a". If a
        // previous import of it is still in the library, say so and stop.
        ImportIndex.find(context, meta.videoId)?.let { existing ->
            update(jobId) {
                it.copy(stage = JobStage.Done(existing, null, alreadyImported = true))
            }
            return null
        }

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

        var lastPublishedPercent = -1
        val response = YoutubeDL.getInstance().execute(request, jobId) { progress, eta, _ ->
            val clamped = progress.coerceIn(0f, 100f)
            // yt-dlp reports far more often than anything downstream can
            // use, and every emission re-renders the list and the
            // notification. Whole percent steps are plenty.
            if (clamped.toInt() != lastPublishedPercent) {
                lastPublishedPercent = clamped.toInt()
                update(jobId) { current ->
                    if (current.stage is JobStage.Cancelled) current
                    else current.copy(stage = JobStage.Downloading(clamped, eta))
                }
            }
        }
        if (response.exitCode != 0) {
            throw YtDlpFailure(DownloadError.humanize(response.err))
        }

        val audio = pickOutput(workDir.listFiles()?.toList().orEmpty(), job.format.extension)
            ?: throw YtDlpFailure("yt-dlp reported success but produced no file")
        return meta to audio
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

    /**
     * Atomically rewrites one job.
     *
     * yt-dlp delivers progress on its own reader thread while the queue worker
     * is also mutating the list, so a plain `value = value.map { }` would be a
     * read-modify-write race that silently loses updates. [update] retries on
     * conflict.
     */
    private fun update(jobId: String, transform: (DownloadJob) -> DownloadJob) {
        _jobs.update { jobs -> jobs.map { if (it.id == jobId) transform(it) else it } }
        scheduleSave()
    }

    companion object {
        private const val TAG = "DownloadRepository"

        /**
         * Picks the audio file yt-dlp produced.
         *
         * Taking simply the largest file was wrong: when the format selector falls
         * back to `best`, yt-dlp downloads a full video and extracts the audio from
         * it, and for a moment the video is both present and much larger. Matching
         * the extension we asked for avoids importing a video file. Partial
         * downloads are excluded outright — a `.part` left behind means the
         * transfer did not finish, and importing it would produce a truncated song.
         */
        internal fun pickOutput(files: List<File>, expectedExtension: String): File? {
            val usable = files.filter {
                it.isFile && it.length() > 0 &&
                    !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
            }
            return usable.firstOrNull { it.extension.equals(expectedExtension, ignoreCase = true) }
                ?: usable.maxByOrNull { it.length() }
        }

        @Volatile
        private var instance: DownloadRepository? = null

        fun get(context: Context): DownloadRepository =
            instance ?: synchronized(this) {
                instance ?: DownloadRepository(context.applicationContext).also { instance = it }
            }

        /**
         * Called from Application.onCreate. Cheap when nothing was
         * interrupted: one small file read on the IO dispatcher. When
         * something was, the repository comes up and carries on.
         */
        fun resumeOnStartup(context: Context) {
            val app = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                if (JobStore.load(app).any { !it.stage.isTerminal }) get(app)
            }
        }
    }
}
