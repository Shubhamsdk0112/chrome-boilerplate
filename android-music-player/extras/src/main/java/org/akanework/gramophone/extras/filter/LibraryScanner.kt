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

package org.akanework.gramophone.extras.filter

import android.content.Context
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Walks the device's audio, classifies it, and hands the junk to Gramophone's
 * blacklist.
 *
 * The order of authority is fixed and deliberate:
 *
 *   1. a manual override the user set in the review screen
 *   2. a cached verdict from a previous scan
 *   3. the local heuristics
 *   4. the AI pass, for whatever is still unsure
 *
 * Anything still unsure at the end stays **visible**. The filter's failure mode
 * is always "shows too much", never "silently hid your music".
 */
class LibraryScanner(context: Context) {

    private val appContext = context.applicationContext
    private val store = FilterStore(appContext)

    enum class Stage { QUERYING, CLASSIFYING, ASKING_AI, SAVING, DONE }

    data class Progress(val stage: Stage, val done: Int = 0, val total: Int = 0)

    data class Entry(val candidate: AudioCandidate, val verdict: Verdict)

    data class Result(
        val entries: List<Entry>,
        val askedAi: Int,
        val aiAnswered: Int,
    ) {
        val total get() = entries.size
        val hidden get() = entries.count { it.verdict.judgement == Judgement.JUNK }
        val kept get() = entries.count { it.verdict.judgement == Judgement.MUSIC }
        val unsure get() = entries.count { it.verdict.judgement == Judgement.UNSURE }
    }

    suspend fun scan(onProgress: (Progress) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        onProgress(Progress(Stage.QUERYING))
        val candidates = query()

        // --- stage one -------------------------------------------------
        onProgress(Progress(Stage.CLASSIFYING, 0, candidates.size))
        val options = store.options
        val verdicts = HashMap<Long, Verdict>(candidates.size)
        val toCache = HashMap<String, Verdict>()
        val unsure = mutableListOf<AudioCandidate>()

        candidates.forEachIndexed { index, candidate ->
            val manual = store.manualOverride(candidate.path)
            val verdict = when {
                manual != null -> Verdict(manual, "You set this manually", Verdict.Source.MANUAL)
                else -> store.cachedVerdict(candidate.fingerprint)
                    ?: JunkHeuristics.classify(candidate, options).also {
                        // Only settled verdicts are cached. Leaving unsure ones
                        // uncached is what lets a later AI pass pick them up.
                        if (it.judgement != Judgement.UNSURE) {
                            toCache[candidate.fingerprint] = it
                        }
                    }
            }
            verdicts[candidate.id] = verdict
            if (verdict.judgement == Judgement.UNSURE) unsure += candidate
            if (index % 200 == 0) {
                onProgress(Progress(Stage.CLASSIFYING, index, candidates.size))
            }
        }

        // --- stage two -------------------------------------------------
        var aiAnswered = 0
        val askedAi = if (store.aiEnabled && store.apiKey.isNotBlank()) unsure.size else 0
        if (askedAi > 0) {
            val classifier = AiClassifier(store.apiKey, store.model)
            var done = 0
            for (batch in unsure.chunked(AiClassifier.BATCH_SIZE)) {
                onProgress(Progress(Stage.ASKING_AI, done, unsure.size))
                val answers = classifier.classify(batch)
                answers.forEach { (indexInBatch, verdict) ->
                    val candidate = batch[indexInBatch]
                    verdicts[candidate.id] = verdict
                    toCache[candidate.fingerprint] = verdict
                    aiAnswered++
                }
                done += batch.size
            }
        }

        // --- persist ---------------------------------------------------
        onProgress(Progress(Stage.SAVING))
        store.putVerdicts(toCache)

        val entries = candidates.map { Entry(it, verdicts[it.id] ?: UNKNOWN) }
        // This assignment is what makes songs disappear from the library: the
        // patched application class unions it with the user's folder blacklist.
        store.hiddenPaths = entries
            .filter { it.verdict.judgement == Judgement.JUNK }
            .map { it.candidate.path }
            .toSet()

        onProgress(Progress(Stage.DONE, entries.size, entries.size))
        Result(entries, askedAi, aiAnswered)
    }

    // ------------------------------------------------------------------

    private fun query(): List<AudioCandidate> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            @Suppress("DEPRECATION")
            add(MediaStore.Audio.Media.DATA)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.IS_MUSIC)
            add(MediaStore.Audio.Media.IS_RINGTONE)
            add(MediaStore.Audio.Media.IS_NOTIFICATION)
            add(MediaStore.Audio.Media.IS_ALARM)
            add(MediaStore.Audio.Media.IS_PODCAST)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.IS_AUDIOBOOK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(MediaStore.Audio.Media.IS_RECORDING)
            }
        }.toTypedArray()

        val out = mutableListOf<AudioCandidate>()
        try {
            appContext.contentResolver.query(collection, projection, null, null, null)
                ?.use { cursor -> while (cursor.moveToNext()) read(cursor)?.let(out::add) }
        } catch (e: Exception) {
            // A missing column on an odd OEM build must not take the scan down.
            Log.e(TAG, "MediaStore query failed", e)
        }
        return out
    }

    private fun read(cursor: Cursor): AudioCandidate? {
        @Suppress("DEPRECATION")
        val path = cursor.stringOf(MediaStore.Audio.Media.DATA) ?: return null
        val name = cursor.stringOf(MediaStore.Audio.Media.DISPLAY_NAME)
            ?: path.substringAfterLast('/')
        return AudioCandidate(
            id = cursor.longOf(MediaStore.Audio.Media._ID) ?: return null,
            path = path,
            displayName = name,
            title = cursor.stringOf(MediaStore.Audio.Media.TITLE),
            artist = cursor.stringOf(MediaStore.Audio.Media.ARTIST),
            album = cursor.stringOf(MediaStore.Audio.Media.ALBUM),
            durationMs = cursor.longOf(MediaStore.Audio.Media.DURATION) ?: 0,
            sizeBytes = cursor.longOf(MediaStore.Audio.Media.SIZE) ?: 0,
            mimeType = cursor.stringOf(MediaStore.Audio.Media.MIME_TYPE),
            // MediaStore stores these as ints, and absence means "not flagged".
            isMusicFlag = cursor.flagOf(MediaStore.Audio.Media.IS_MUSIC, default = true),
            isRingtone = cursor.flagOf(MediaStore.Audio.Media.IS_RINGTONE),
            isNotification = cursor.flagOf(MediaStore.Audio.Media.IS_NOTIFICATION),
            isAlarm = cursor.flagOf(MediaStore.Audio.Media.IS_ALARM),
            isPodcast = cursor.flagOf(MediaStore.Audio.Media.IS_PODCAST),
            isAudiobook = cursor.flagOf("is_audiobook"),
            isRecording = cursor.flagOf("is_recording"),
        ).also {
            fingerprints[it.id] = FilterStore.fingerprint(
                path, it.sizeBytes, cursor.longOf(MediaStore.Audio.Media.DATE_MODIFIED) ?: 0
            )
        }
    }

    /** Fingerprints are computed while reading the cursor and looked up later. */
    private val fingerprints = HashMap<Long, String>()

    private val AudioCandidate.fingerprint: String
        get() = fingerprints[id] ?: FilterStore.fingerprint(path, sizeBytes, 0)

    private fun Cursor.stringOf(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)?.takeIf { it.isNotBlank() }
    }

    private fun Cursor.longOf(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }

    private fun Cursor.flagOf(column: String, default: Boolean = false): Boolean {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) default else getInt(index) != 0
    }

    companion object {
        private const val TAG = "LibraryScanner"
        private val UNKNOWN = Verdict(Judgement.UNSURE, "Not classified")
    }
}
