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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFormatTest {

    @Test
    fun `the default is the one that does not re-encode`() {
        assertEquals(AudioFormat.M4A, AudioFormat.fromId(null))
        assertEquals(AudioFormat.M4A, AudioFormat.fromId("nonsense"))
        assertEquals(AudioFormat.M4A, AudioFormat.fromId(""))
    }

    @Test
    fun `every id round-trips`() {
        for (format in AudioFormat.entries) {
            assertEquals(format, AudioFormat.fromId(format.id))
        }
    }

    @Test
    fun `opus is marked as carrying no cover art`() {
        // Ogg/Opus stores artwork in a way ffmpeg cannot write from an image
        // input, so this must stay false or downloads silently lose their
        // covers while the UI claims otherwise.
        assertFalse(AudioFormat.OPUS.supportsCoverArt)
        assertTrue(AudioFormat.M4A.supportsCoverArt)
        assertTrue(AudioFormat.MP3.supportsCoverArt)
    }

    @Test
    fun `a format without cover art says so in the text the user reads`() {
        for (format in AudioFormat.entries) {
            if (!format.supportsCoverArt) {
                assertTrue(
                    "${format.label} must warn about missing art",
                    format.summary.contains("album art", ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `extensions are distinct so the ffmpeg lookup is unambiguous`() {
        val extensions = AudioFormat.entries.map { it.extension }
        assertEquals(extensions.size, extensions.toSet().size)
    }

    @Test
    fun `every format has a selector and a non-empty summary`() {
        for (format in AudioFormat.entries) {
            assertTrue(format.selector.isNotBlank())
            assertTrue(format.summary.isNotBlank())
            assertTrue(format.label.isNotBlank())
        }
    }
}
