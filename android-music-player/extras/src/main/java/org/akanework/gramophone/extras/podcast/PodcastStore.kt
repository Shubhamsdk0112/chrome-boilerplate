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

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * Adds one episode to a show, creating the show when it is new. Used for
     * YouTube downloads, where "the show" is the channel.
     */
    fun upsertEpisode(show: Podcast, episode: Episode) {
        _podcasts.update { list ->
            val existing = list.firstOrNull { it.feedUrl == show.feedUrl }
            val merged = if (existing == null) {
                show.copy(episodes = listOf(episode))
            } else {
                existing.copy(
                    imageUrl = existing.imageUrl ?: show.imageUrl,
                    episodes = (listOf(episode) + existing.episodes.filterNot { it.guid == episode.guid })
                        .sortedByDescending { it.publishedAt }
                        .take(MAX_EPISODES),
                    refreshedAt = System.currentTimeMillis(),
                )
            }
            list.filterNot { it.feedUrl == show.feedUrl } + merged
        }
        persist()
    }

    fun removeEpisode(feedUrl: String, guid: String) {
        _podcasts.update { list ->
            list.map { p ->
                if (p.feedUrl != feedUrl) p else p.copy(episodes = p.episodes.filterNot { it.guid == guid })
            }.filterNot { it.episodes.isEmpty() && it.feedUrl.startsWith(YOUTUBE_FEED_PREFIX) }
        }
        persist()
    }

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

    /**
     * Long audio already on the phone — audiobooks, mixes, recorded talks —
     * as a virtual show, read fresh from MediaStore. These play straight from
     * their content URI and are never persisted here.
     */
    suspend fun localLongAudio(minMinutes: Int = LONG_AUDIO_MINUTES): Podcast? =
        withContext(Dispatchers.IO) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val projection = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED,
            )
            val episodes = mutableListOf<Episode>()
            runCatching {
                context.contentResolver.query(
                    uri, projection, "${MediaStore.Audio.Media.DURATION} >= ?",
                    arrayOf((minMinutes * 60_000L).toString()), "${MediaStore.Audio.Media.DATE_ADDED} DESC",
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        val item = ContentUris.withAppendedId(uri, id)
                        episodes += Episode(
                            guid = "local:$id",
                            feedUrl = LOCAL_FEED,
                            title = c.getString(titleCol) ?: "Untitled",
                            audioUrl = item.toString(),
                            publishedAt = c.getLong(addedCol) * 1000,
                            durationSeconds = (c.getLong(durCol) / 1000).toInt(),
                            imageUrl = null,
                            description = c.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" },
                            source = EpisodeSource.LOCAL,
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "could not list long audio", it) }
            if (episodes.isEmpty()) null
            else Podcast(LOCAL_FEED, "On this phone", null, null, null, episodes, System.currentTimeMillis())
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
                    put("source", e.source)
                    if (e.chapters.isNotEmpty()) {
                        put("chapters", JSONArray().also { ch ->
                            e.chapters.forEach { c ->
                                ch.put(JSONObject().apply { put("title", c.title); put("startMs", c.startMs); put("endMs", c.endMs) })
                            }
                        })
                    }
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
                    source = e.optString("source").ifBlank { EpisodeSource.RSS },
                    chapters = e.optJSONArray("chapters")?.let { ch ->
                        (0 until ch.length()).map { j ->
                            val c = ch.getJSONObject(j)
                            Chapter(c.optString("title"), c.optLong("startMs"), c.optLong("endMs"))
                        }
                    }.orEmpty(),
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
        /** Anything this long is not a song; see the filter's long-audio rule. */
        const val LONG_AUDIO_MINUTES = 10
        const val LOCAL_FEED = "local:long-audio"
        const val YOUTUBE_FEED_PREFIX = "yt:channel:"

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
