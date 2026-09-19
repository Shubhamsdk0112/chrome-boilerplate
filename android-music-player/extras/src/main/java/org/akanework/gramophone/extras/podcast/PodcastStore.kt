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

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything the podcast feature remembers, in one JSON file.
 *
 *  - subscriptions, each with its cached episode list (newest 300);
 *  - where each downloaded episode lives on disk;
 *  - how far into each episode the user got.
 *
 * Episode files live under the app's external files dir, which MediaStore
 * never indexes, so they stay out of the music library. Small enough to
 * rewrite whole; every write goes through [persist] on IO.
 */
class PodcastStore private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    // Lazy: filesDir touches the disk, and the store is first created on the
    // main thread by a Compose screen. Every user of it runs on IO.
    private val file by lazy { File(context.filesDir, "extras_podcasts.json") }

    private val _podcasts = MutableStateFlow<List<Podcast>>(emptyList())
    val podcasts: StateFlow<List<Podcast>> = _podcasts.asStateFlow()

    /** guid → absolute path of the downloaded file. */
    private val _downloads = MutableStateFlow<Map<String, String>>(emptyMap())
    val downloads: StateFlow<Map<String, String>> = _downloads.asStateFlow()

    /** guid → last playback position, ms. */
    private val _positions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val positions: StateFlow<Map<String, Long>> = _positions.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch { lock.withLock { load() }; _loaded.value = true }
    }

    // ------------------------------------------------------------------

    fun subscribe(podcast: Podcast) {
        _podcasts.update { list ->
            list.filterNot { it.feedUrl == podcast.feedUrl } + podcast.copy(episodes = podcast.episodes.take(MAX_EPISODES))
        }
        persist()
    }

    fun unsubscribe(feedUrl: String) {
        _podcasts.update { list -> list.filterNot { it.feedUrl == feedUrl } }
        persist()
    }

    fun podcast(feedUrl: String): Podcast? = _podcasts.value.firstOrNull { it.feedUrl == feedUrl }

    fun episode(guid: String): Episode? =
        _podcasts.value.asSequence().flatMap { it.episodes.asSequence() }.firstOrNull { it.guid == guid }

    fun downloadedFile(guid: String): File? =
        _downloads.value[guid]?.let(::File)?.takeIf { it.isFile }

    fun recordDownload(guid: String, file: File) {
        _downloads.update { it + (guid to file.absolutePath) }
        persist()
    }

    fun forgetDownload(guid: String) {
        _downloads.update { it - guid }
        persist()
    }

    fun position(guid: String): Long = _positions.value[guid] ?: 0L

    fun savePosition(guid: String, positionMs: Long) {
        val rounded = (positionMs / 1000) * 1000
        if (_positions.value[guid] == rounded) return
        _positions.update { it + (guid to rounded) }
        persist()
    }

    /** Where an episode's file goes. Show and title become safe path segments. */
    fun targetFile(podcast: Podcast, episode: Episode): File {
        val root = context.getExternalFilesDir("Podcasts") ?: File(context.filesDir, "Podcasts")
        val show = File(root, safe(podcast.title))
        return File(show, "${safe(episode.title)}.${episode.extension}")
    }

    // ------------------------------------------------------------------

    private fun persist() {
        scope.launch {
            lock.withLock {
                val root = JSONObject()
                root.put("podcasts", JSONArray().also { arr ->
                    _podcasts.value.forEach { arr.put(podcastJson(it)) }
                })
                root.put("downloads", JSONObject(_downloads.value))
                root.put("positions", JSONObject(_positions.value))
                val tmp = File(file.parentFile, file.name + ".tmp")
                runCatching {
                    tmp.writeText(root.toString())
                    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                }.onFailure { Log.w(TAG, "could not write ${file.name}", it) }
            }
        }
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            val root = JSONObject(file.readText())
            val podcasts = root.optJSONArray("podcasts")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> podcastFrom(arr.getJSONObject(i)) }
            }.orEmpty()
            val downloads = root.optJSONObject("downloads")?.let { o ->
                o.keys().asSequence().associateWith { o.getString(it) }
            }.orEmpty()
            val positions = root.optJSONObject("positions")?.let { o ->
                o.keys().asSequence().associateWith { o.getLong(it) }
            }.orEmpty()
            _podcasts.value = podcasts
            _downloads.value = downloads
            _positions.value = positions
        }.onFailure { Log.w(TAG, "could not read ${file.name}, starting empty", it) }
    }

    private fun podcastJson(p: Podcast) = JSONObject().apply {
        put("feedUrl", p.feedUrl)
        put("title", p.title)
        put("author", p.author)
        put("imageUrl", p.imageUrl)
        put("description", p.description)
        put("refreshedAt", p.refreshedAt)
        put("episodes", JSONArray().also { arr ->
            p.episodes.forEach { e ->
                arr.put(JSONObject().apply {
                    put("guid", e.guid)
                    put("title", e.title)
                    put("audioUrl", e.audioUrl)
                    put("publishedAt", e.publishedAt)
                    put("durationSeconds", e.durationSeconds)
                    put("imageUrl", e.imageUrl)
                    put("description", e.description)
                })
            }
        })
    }

    private fun podcastFrom(o: JSONObject): Podcast? {
        val feedUrl = o.optString("feedUrl").ifBlank { return null }
        val episodes = o.optJSONArray("episodes")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.getJSONObject(i)
                val guid = e.optString("guid").ifBlank { return@mapNotNull null }
                Episode(
                    guid = guid,
                    feedUrl = feedUrl,
                    title = e.optString("title"),
                    audioUrl = e.optString("audioUrl"),
                    publishedAt = e.optLong("publishedAt"),
                    durationSeconds = e.optInt("durationSeconds"),
                    imageUrl = e.optString("imageUrl").takeIf { it.isNotBlank() },
                    description = e.optString("description").takeIf { it.isNotBlank() },
                )
            }
        }.orEmpty()
        return Podcast(
            feedUrl = feedUrl,
            title = o.optString("title").ifBlank { feedUrl },
            author = o.optString("author").takeIf { it.isNotBlank() },
            imageUrl = o.optString("imageUrl").takeIf { it.isNotBlank() },
            description = o.optString("description").takeIf { it.isNotBlank() },
            episodes = episodes,
            refreshedAt = o.optLong("refreshedAt"),
        )
    }

    companion object {
        private const val TAG = "PodcastStore"
        private const val MAX_EPISODES = 300

        @Volatile
        private var instance: PodcastStore? = null

        fun get(context: Context): PodcastStore =
            instance ?: synchronized(this) {
                instance ?: PodcastStore(context.applicationContext).also { instance = it }
            }

        /** FAT-safe, bounded, never empty. */
        internal fun safe(name: String): String {
            val cleaned = name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trimEnd('.')
            return cleaned.take(80).ifBlank { "untitled" }
        }
    }
}
