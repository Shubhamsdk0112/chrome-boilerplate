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

/**
 * Everything the filter is allowed to know about one audio file.
 *
 * Deliberately a plain data class with no Android types: the classifier is the
 * part most likely to be wrong, so it stays testable on a normal JVM. Only
 * [LibraryScanner] talks to MediaStore.
 *
 * Note what is *not* here: no audio, no file contents, no waveform. The whole
 * pipeline — including the optional AI pass — judges a file purely by its name,
 * location and tags.
 */
data class AudioCandidate(
    val id: Long,
    /** Absolute path, which is also the key Gramophone's blacklist matches on. */
    val path: String,
    val displayName: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val mimeType: String? = null,
    /** MediaStore's own classification flags. */
    val isMusicFlag: Boolean = true,
    val isRingtone: Boolean = false,
    val isNotification: Boolean = false,
    val isAlarm: Boolean = false,
    val isPodcast: Boolean = false,
    val isAudiobook: Boolean = false,
    val isRecording: Boolean = false,
) {
    val extension: String
        get() = displayName.substringAfterLast('.', "").lowercase()

    /** The containing folder, lowercased, with no trailing slash. */
    val folder: String
        get() = path.substringBeforeLast('/', "").lowercase()

    val nameWithoutExtension: String
        get() = displayName.substringBeforeLast('.', displayName)

    /**
     * Rough encoded bitrate in kbps.
     *
     * `bytes * 8 / milliseconds` lands directly in kbps because the factors of
     * 1000 for "kilo" and for "ms to s" cancel. It separates speech from music
     * remarkably well: voice notes sit near 16-32 kbps, music rarely below 96.
     * Null when the duration is unknown, which is common for broken files.
     */
    val approximateBitrateKbps: Int?
        get() = if (durationMs > 1000 && sizeBytes > 0) {
            ((sizeBytes * 8) / durationMs).toInt()
        } else {
            null
        }

    /** True when MediaStore has no real tags — just a filename standing in. */
    val hasNoUsefulTags: Boolean
        get() = artist.isNullOrBlank() && album.isNullOrBlank() &&
            (title.isNullOrBlank() || title.equals(nameWithoutExtension, ignoreCase = true))
}

/** What the filter decided, and why. */
enum class Judgement {
    /** Keep it in the library. */
    MUSIC,

    /** Hide it from the library. Never deletes anything. */
    JUNK,

    /** The heuristics could not tell; eligible for the AI pass. */
    UNSURE,
}

data class Verdict(
    val judgement: Judgement,
    /** Short human-readable justification, shown in the review screen. */
    val reason: String,
    /** Which stage produced this, for the review UI and for cache invalidation. */
    val source: Source = Source.HEURISTIC,
) {
    enum class Source { HEURISTIC, AI, MANUAL }
}
