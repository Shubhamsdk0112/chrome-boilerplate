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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The job list on disk, so that killing the app does not lose the queue.
 *
 * Written as one small JSON file after every change (debounced by the
 * repository). On load, anything that was mid-flight comes back as [JobStage.Queued]
 * and is run again; yt-dlp picks up its own `.part` file in the job's work
 * directory, so a download interrupted at 80% does not start from zero.
 *
 * Only ever touched from the repository's IO scope.
 */
internal object JobStore {

    private const val TAG = "JobStore"
    private const val FILE = "extras_jobs.json"
    private const val KEEP_FINISHED = 50

    fun load(context: Context): List<DownloadJob> {
        val file = File(context.filesDir, FILE)
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i -> fromJson(array.getJSONObject(i)) }
        }.onFailure { Log.w(TAG, "could not read $FILE, starting empty", it) }
            .getOrDefault(emptyList())
    }

    fun save(context: Context, jobs: List<DownloadJob>) {
        // Keep the whole active queue and a bounded tail of history.
        val finished = jobs.filter { it.stage.isTerminal }.takeLast(KEEP_FINISHED)
        val active = jobs.filterNot { it.stage.isTerminal }
        val array = JSONArray()
        (finished + active).forEach { array.put(toJson(it)) }
        val file = File(context.filesDir, FILE)
        val tmp = File(context.filesDir, "$FILE.tmp")
        runCatching {
            tmp.writeText(array.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "could not write $FILE", it) }
    }

    private fun toJson(job: DownloadJob): JSONObject = JSONObject().apply {
        put("id", job.id)
        put("url", job.url)
        put("format", job.format.id)
        put("title", job.title)
        put("artist", job.artist)
        put("videoId", job.videoId)
        put("thumbnail", job.thumbnail?.absolutePath)
        put("kind", job.kind.name)
        put("feedUrl", job.feedUrl)
        put("episodeGuid", job.episodeGuid)
        put("attempts", job.attempts)
        put("asPodcast", job.asPodcast)
        when (val s = job.stage) {
            is JobStage.Done -> {
                put("stage", "done")
                put("uri", s.uri?.toString())
                put("artworkSource", s.artworkSource)
                put("alreadyImported", s.alreadyImported)
                put("podcast", s.podcast)
                put("chapters", s.chapters)
                put("doneEpisodeGuid", s.episodeGuid)
            }
            is JobStage.Failed -> {
                put("stage", "failed")
                put("message", s.message)
            }
            is JobStage.Cancelled -> put("stage", "cancelled")
            // Anything in flight is persisted as queued: on restore it runs
            // again from the top, and the work directory carries the .part.
            else -> put("stage", "queued")
        }
    }

    private fun fromJson(o: JSONObject): DownloadJob? {
        val id = o.optString("id").ifBlank { return null }
        val url = o.optString("url").ifBlank { return null }
        val stage = when (o.optString("stage")) {
            "done" -> JobStage.Done(
                uri = o.optString("uri").takeIf { it.isNotBlank() }?.let(Uri::parse),
                artworkSource = o.optString("artworkSource").takeIf { it.isNotBlank() },
                alreadyImported = o.optBoolean("alreadyImported", false),
                podcast = o.optBoolean("podcast", false),
                chapters = o.optInt("chapters", 0),
                episodeGuid = o.optString("doneEpisodeGuid").takeIf { it.isNotBlank() },
            )
            "failed" -> JobStage.Failed(o.optString("message").ifBlank { "The download failed." })
            "cancelled" -> JobStage.Cancelled
            else -> JobStage.Queued
        }
        return DownloadJob(
            id = id,
            url = url,
            format = AudioFormat.fromId(o.optString("format")),
            stage = stage,
            title = o.optString("title").takeIf { it.isNotBlank() },
            artist = o.optString("artist").takeIf { it.isNotBlank() },
            videoId = o.optString("videoId").takeIf { it.isNotBlank() },
            thumbnail = o.optString("thumbnail").takeIf { it.isNotBlank() }
                ?.let(::File)?.takeIf { it.isFile },
            kind = runCatching { JobKind.valueOf(o.optString("kind")) }.getOrDefault(JobKind.YOUTUBE),
            feedUrl = o.optString("feedUrl").takeIf { it.isNotBlank() },
            episodeGuid = o.optString("episodeGuid").takeIf { it.isNotBlank() },
            attempts = o.optInt("attempts", 0),
            asPodcast = o.optBoolean("asPodcast", false),
        )
    }
}
