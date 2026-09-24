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

package org.akanework.gramophone.extras.importer

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A YouTube playlist imported as a real playlist: one tagged file per song
 * in the library, plus an `.m3u` in Music/ that lists them in the
 * playlist's order. Gramophone reads `.m3u` files from MediaStore as
 * playlists, so it shows up under Playlists with nothing else wired in.
 *
 * The file is created as soon as the import starts and rewritten every time
 * another song finishes, so it fills up while the (paced) downloads run.
 * Songs that were already in the library join it immediately.
 */
object PlaylistImports {

    private const val TAG = "PlaylistImports"
    private const val FILE = "extras_playlists.json"

    data class Track(val videoId: String, val title: String, val channel: String?, val durationSeconds: Long)

    data class Import(
        val id: String,
        val name: String,
        val sourceUrl: String,
        /** The `.m3u` file's MediaStore uri (Q+) or file uri (older). */
        val uri: String?,
        val createdAt: Long,
        val tracks: List<Track>,
    )

    private val lock = Mutex()

    suspend fun create(
        context: Context,
        name: String,
        sourceUrl: String,
        entries: List<YouTubeSearch.Entry>,
    ): Import = withContext(Dispatchers.IO) {
        lock.withLock {
            val cleanName = name.trim().ifEmpty { "YouTube playlist" }
            val import = Import(
                id = UUID.randomUUID().toString(),
                name = cleanName,
                sourceUrl = sourceUrl,
                uri = createPlaylistFile(context, cleanName)?.toString(),
                createdAt = System.currentTimeMillis(),
                tracks = entries.map { Track(it.videoId, it.title, it.channel, it.durationSeconds) },
            )
            save(context, load(context) + import)
            import
        }
    }

    fun find(context: Context, id: String): Import? = load(context).firstOrNull { it.id == id }

    /**
     * Rewrites the `.m3u` with every track that is in the library now, in
     * playlist order. Returns how many it listed.
     */
    suspend fun rebuild(context: Context, id: String): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            val import = load(context).firstOrNull { it.id == id } ?: return@withLock 0
            val uri = import.uri?.let(Uri::parse) ?: return@withLock 0
            val present = import.tracks.mapNotNull { track ->
                val song = ImportIndex.find(context, track.videoId) ?: return@mapNotNull null
                val path = pathOf(context, song) ?: return@mapNotNull null
                track to path
            }
            val text = m3u(import.name, present, musicDirectory())
            try {
                if (uri.scheme == "file") {
                    File(uri.path!!).writeText(text)
                    MediaScannerConnection.scanFile(context, arrayOf(uri.path), arrayOf(M3U_MIME), null)
                } else {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                        ?: return@withLock 0
                }
            } catch (e: Exception) {
                // Deleted from the Playlists tab while downloads were still
                // running: that is the user's decision, not ours to undo.
                Log.w(TAG, "could not rewrite playlist ${import.name}", e)
                return@withLock 0
            }
            present.size
        }
    }

    // ------------------------------------------------------------------

    /** Pure: the file Gramophone will read. Paths relative to Music/ when possible. */
    fun m3u(name: String, tracks: List<Pair<Track, String>>, musicDir: String): String = buildString {
        append("#EXTM3U\n")
        append("#PLAYLIST:").append(name.replace('\n', ' ')).append('\n')
        val prefix = musicDir.trimEnd('/') + "/"
        for ((track, path) in tracks) {
            val label = listOfNotNull(track.channel, track.title).joinToString(" - ").replace('\n', ' ')
            append("#EXTINF:").append(if (track.durationSeconds > 0) track.durationSeconds else -1)
                .append(',').append(label).append('\n')
            append(if (path.startsWith(prefix)) path.removePrefix(prefix) else path).append('\n')
        }
    }

    private const val M3U_MIME = "audio/x-mpegurl"

    @Suppress("DEPRECATION")
    private fun musicDirectory(): String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath

    private fun createPlaylistFile(context: Context, name: String): Uri? {
        val displayName = safeFileName(name) + ".m3u"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, M3U_MIME)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values,
                ) ?: return null
                context.contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write("#EXTM3U\n#PLAYLIST:$name\n".toByteArray())
                }
                uri
            } else {
                val dir = File(musicDirectory()).apply { mkdirs() }
                var target = File(dir, displayName)
                var n = 1
                while (target.exists()) target = File(dir, safeFileName(name) + " ($n).m3u").also { n++ }
                target.writeText("#EXTM3U\n#PLAYLIST:$name\n")
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(M3U_MIME), null)
                Uri.fromFile(target)
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not create playlist file for $name", e)
            null
        }
    }

    fun safeFileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ").replace(Regex("\\s+"), " ").trim()
            .take(80).ifEmpty { "YouTube playlist" }

    @Suppress("DEPRECATION")
    private fun pathOf(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun load(context: Context): List<Import> {
        val f = File(context.filesDir, FILE)
        if (!f.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(f.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val tracks = o.getJSONArray("tracks")
                Import(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    sourceUrl = o.optString("sourceUrl"),
                    uri = o.optString("uri").ifEmpty { null },
                    createdAt = o.optLong("createdAt"),
                    tracks = (0 until tracks.length()).map { j ->
                        val t = tracks.getJSONObject(j)
                        Track(
                            t.getString("videoId"), t.optString("title"),
                            t.optString("channel").ifEmpty { null }, t.optLong("duration"),
                        )
                    },
                )
            }
        }.getOrElse {
            Log.w(TAG, "playlist store unreadable, starting fresh", it)
            emptyList()
        }
    }

    private fun save(context: Context, imports: List<Import>) {
        val array = JSONArray()
        imports.takeLast(50).forEach { i ->
            array.put(JSONObject().apply {
                put("id", i.id); put("name", i.name); put("sourceUrl", i.sourceUrl)
                put("uri", i.uri ?: ""); put("createdAt", i.createdAt)
                put("tracks", JSONArray().apply {
                    i.tracks.forEach { t ->
                        put(JSONObject().apply {
                            put("videoId", t.videoId); put("title", t.title)
                            put("channel", t.channel ?: ""); put("duration", t.durationSeconds)
                        })
                    }
                })
            })
        }
        val f = File(context.filesDir, FILE)
        val tmp = File(context.filesDir, "$FILE.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
