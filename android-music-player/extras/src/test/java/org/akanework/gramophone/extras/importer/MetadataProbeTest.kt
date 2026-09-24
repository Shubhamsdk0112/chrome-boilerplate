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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The title heuristics decide what every imported song ends up called, and they
 * run against text no schema constrains, so they are worth pinning down.
 */
class MetadataProbeTest {

    private fun clean(
        rawTitle: String,
        uploader: String? = null,
        track: String? = null,
        artist: String? = null,
        album: String? = null,
    ) = MetadataProbe.cleanUp(
        videoId = "abc123",
        track = track,
        artist = artist,
        album = album,
        year = null,
        duration = 210,
        thumbnail = null,
        rawTitle = rawTitle,
        uploader = uploader,
    )

    @Test
    fun `real music metadata is trusted verbatim`() {
        // YouTube Music entries carry proper tags; we must not "improve" them.
        val result = clean(
            rawTitle = "Never Gonna Give You Up (Official Video)",
            track = "Never Gonna Give You Up",
            artist = "Rick Astley",
            album = "Whenever You Need Somebody",
        )
        assertEquals("Never Gonna Give You Up", result.title)
        assertEquals("Rick Astley", result.artist)
        assertEquals("Whenever You Need Somebody", result.album)
    }

    @Test
    fun `artist and title are split on a hyphen`() {
        val result = clean("Rick Astley - Never Gonna Give You Up")
        assertEquals("Rick Astley", result.artist)
        assertEquals("Never Gonna Give You Up", result.title)
    }

    @Test
    fun `promotional noise is stripped`() {
        val cases = listOf(
            "Daft Punk - Get Lucky (Official Music Video)",
            "Daft Punk - Get Lucky [Official Video]",
            "Daft Punk - Get Lucky (Lyrics)",
            "Daft Punk - Get Lucky (Official Audio)",
            "Daft Punk - Get Lucky (HD)",
            "Daft Punk - Get Lucky (Visualizer)",
        )
        for (case in cases) {
            val result = clean(case)
            assertEquals("failed on: $case", "Get Lucky", result.title)
            assertEquals("failed on: $case", "Daft Punk", result.artist)
        }
    }

    @Test
    fun `en dash and em dash separators work`() {
        assertEquals("Get Lucky", clean("Daft Punk – Get Lucky").title)
        assertEquals("Daft Punk", clean("Daft Punk – Get Lucky").artist)
        assertEquals("Get Lucky", clean("Daft Punk — Get Lucky").title)
    }

    @Test
    fun `a Topic channel supplies the artist`() {
        // Auto-generated "<Artist> - Topic" channels are the most reliable
        // artist signal available for licensed music.
        val result = clean(rawTitle = "Get Lucky", uploader = "Daft Punk - Topic")
        assertEquals("Daft Punk", result.artist)
        assertEquals("Get Lucky", result.title)
    }

    @Test
    fun `trailing pipe suffix is removed`() {
        assertEquals("Invincible", clean("Invincible | NCS Release").title)
    }

    @Test
    fun `a title with no separator falls back to the uploader`() {
        val result = clean(rawTitle = "Weightless", uploader = "Marconi Union")
        assertEquals("Weightless", result.title)
        assertEquals("Marconi Union", result.artist)
    }

    @Test
    fun `a hyphen inside the song title is not mistaken for a separator`() {
        // Only the first separator splits, so the remainder stays in the title.
        val result = clean("Pink Floyd - Shine On You Crazy Diamond - Parts I-V")
        assertEquals("Pink Floyd", result.artist)
        assertEquals("Shine On You Crazy Diamond - Parts I-V", result.title)
    }

    @Test
    fun `an unknown uploader leaves the artist unset`() {
        assertNull(clean(rawTitle = "Untitled Track").artist)
    }

    @Test
    fun `display name and search query are built from the parts`() {
        val result = clean("Daft Punk - Get Lucky")
        assertEquals("Daft Punk - Get Lucky", result.displayName)
        assertEquals("Daft Punk Get Lucky", result.searchQuery)
    }

    @Test
    fun `youtube chapters json becomes sorted chapters, junk becomes none`() {
        val chapters = MetadataProbe.parseChapters(
            """[{"start_time":61.5,"end_time":300,"title":"Guest intro"},{"start_time":0,"end_time":61.5,"title":"Cold open"},{"start_time":300,"end_time":900,"title":""}]"""
        )
        assertEquals(listOf("Cold open", "Guest intro", "Chapter 3"), chapters.map { it.title })
        assertEquals(61_500L, chapters[1].startMs)
        assertEquals(300_000L, chapters[1].endMs)
        assertTrue(MetadataProbe.parseChapters("NA").isEmpty())
        assertTrue(MetadataProbe.parseChapters(null).isEmpty())
        assertTrue(MetadataProbe.parseChapters("null").isEmpty())
    }

    @Test
    fun `upload dates are YYYYMMDD or nothing`() {
        assertTrue(MetadataProbe.parseUploadDate("20260918") > 0)
        assertEquals(0L, MetadataProbe.parseUploadDate("2026-09-18"))
        assertEquals(0L, MetadataProbe.parseUploadDate(null))
    }
}
