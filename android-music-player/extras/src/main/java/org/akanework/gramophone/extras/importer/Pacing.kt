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
import androidx.core.content.edit
import kotlin.random.Random

/**
 * How hard the importer is allowed to hit YouTube.
 *
 * A phone that pulls twenty songs back to back at full speed looks like a
 * scraper, and YouTube answers with rate limits or "confirm you're not a
 * bot" for the whole IP. Two levers, both randomised so the pattern is not
 * mechanical:
 *
 *  - a pause between one YouTube job finishing and the next one starting;
 *  - yt-dlp's own `--sleep-interval`/`--max-sleep-interval`, a pause before
 *    each media download, and `--sleep-requests`, between metadata calls.
 *
 * Podcast downloads are plain HTTP to podcast hosts and are not paced.
 */
enum class Pacing(val id: String, val minGapSeconds: Int, val maxGapSeconds: Int, val requestSleep: Int) {
    /** No pauses at all. Fine for one or two songs. */
    OFF("off", 0, 0, 0),
    /** A short breather; a ten-song batch takes a few minutes longer. */
    QUICK("quick", 10, 30, 1),
    /** The default. Roughly a song a minute in a batch. */
    SAFE("safe", 30, 75, 2),
    /** For big batches on a connection you care about. */
    CAUTIOUS("cautious", 60, 150, 3);

    /** A random pause in this band, seconds. */
    fun gapSeconds(): Int = if (maxGapSeconds <= 0) 0 else Random.nextInt(minGapSeconds, maxGapSeconds + 1)

    companion object {
        private const val FILE = "extras_importer"
        private const val KEY = "pacing"
        private const val KEY_LONG_TO_PODCASTS = "long_to_podcasts"

        /**
         * Whether a YouTube video at least LONG_AUDIO_MINUTES long is saved as a
         * podcast episode (own section, chapters, never in the song library)
         * instead of a song. On by default: a two-hour video is not a song.
         */
        fun longVideosToPodcasts(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_LONG_TO_PODCASTS, true)

        fun setLongVideosToPodcasts(context: Context, value: Boolean) {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit { putBoolean(KEY_LONG_TO_PODCASTS, value) }
        }

        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: SAFE

        fun read(context: Context): Pacing =
            fromId(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null))

        fun write(context: Context, pacing: Pacing) {
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY, pacing.id) }
        }
    }
}
