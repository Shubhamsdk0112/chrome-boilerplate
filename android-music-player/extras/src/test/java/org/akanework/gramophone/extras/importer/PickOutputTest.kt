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

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Choosing which file in the work directory is the song.
 *
 * Getting this wrong imports the wrong thing entirely — a video file, or a
 * half-finished download that plays as a truncated track.
 */
class PickOutputTest {

    private val dir: File = Files.createTempDirectory("pick-output").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun file(name: String, bytes: Int): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun `the file matching the requested format wins over a larger one`() {
        // The real case: `best` fell back to a full video, yt-dlp extracted the
        // audio, and for a moment both exist with the video much larger.
        val video = file("song.webm", 9_000)
        val audio = file("song.m4a", 1_000)
        assertEquals(audio, DownloadRepository.pickOutput(listOf(video, audio), "m4a"))
    }

    @Test
    fun `partial downloads are never chosen`() {
        val partial = file("song.m4a.part", 9_000)
        val done = file("song.m4a", 1_000)
        assertEquals(done, DownloadRepository.pickOutput(listOf(partial, done), "m4a"))
    }

    @Test
    fun `a lone partial download yields nothing rather than a truncated song`() {
        val partial = file("song.m4a.part", 5_000)
        assertNull(DownloadRepository.pickOutput(listOf(partial), "m4a"))
        val resume = file("song.ytdl", 100)
        assertNull(DownloadRepository.pickOutput(listOf(partial, resume), "m4a"))
    }

    @Test
    fun `with no extension match the largest complete file is used`() {
        // yt-dlp sometimes lands on a different container than requested;
        // better to import it than to fail outright.
        val small = file("song.opus", 1_000)
        val big = file("song.ogg", 5_000)
        assertEquals(big, DownloadRepository.pickOutput(listOf(small, big), "m4a"))
    }

    @Test
    fun `empty files are ignored`() {
        val empty = file("song.m4a", 0)
        val real = file("other.opus", 2_000)
        assertEquals(real, DownloadRepository.pickOutput(listOf(empty, real), "m4a"))
    }

    @Test
    fun `an empty directory yields null`() {
        assertNull(DownloadRepository.pickOutput(emptyList(), "m4a"))
    }

    @Test
    fun `extension matching ignores case`() {
        val audio = file("song.M4A", 1_000)
        val other = file("song.webm", 9_000)
        assertEquals(audio, DownloadRepository.pickOutput(listOf(other, audio), "m4a"))
    }
}
