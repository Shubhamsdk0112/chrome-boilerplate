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

/** Real links as the YouTube and YouTube Music apps share them. */
class YouTubeLinksTest {

    @Test
    fun `a song opened from a mix is just the song`() {
        // Exactly what the phone shared when the bot check hit.
        val link = YouTubeLinks.classify(
            "https://m.youtube.com/watch?v=Jjfad18Rwl0&list=RDJjfad18Rwl0&start_radio=1&pp=ygUWc3R1cGlkIG5ldmVyIGRpZXMgc29uZ6AHAQ%3D%3D"
        )
        assertEquals(YouTubeLink.Video::class, link::class)
        assertEquals("Jjfad18Rwl0", (link as YouTubeLink.Video).videoId)
    }

    @Test
    fun `a song opened from a real playlist offers the playlist`() {
        val link = YouTubeLinks.classify("https://www.youtube.com/watch?v=p8oGJ55A88I&list=PLD3LS5Z2bqoFChIzb5H0CY-XwmUB02buA&index=5")
        link as YouTubeLink.VideoInPlaylist
        assertEquals("p8oGJ55A88I", link.videoId)
        assertEquals("https://www.youtube.com/playlist?list=PLD3LS5Z2bqoFChIzb5H0CY-XwmUB02buA", link.playlistUrl)
        assertEquals("https://www.youtube.com/watch?v=p8oGJ55A88I", link.videoUrl)
    }

    @Test
    fun `playlist pages and albums are playlists`() {
        val page = YouTubeLinks.classify("https://www.youtube.com/playlist?list=PLxzSZG7g8c8x6GYz_FcNr-3zPQ7npP6WF&si=abc")
        assertEquals("PLxzSZG7g8c8x6GYz_FcNr-3zPQ7npP6WF", (page as YouTubeLink.Playlist).listId)
        val album = YouTubeLinks.classify("https://music.youtube.com/playlist?list=OLAK5uy_nMr9h2VlS-2PULNz3M3XVXQj_P3C2bqaY")
        assertTrue(album is YouTubeLink.Playlist)
    }

    @Test
    fun `short links shorts and music links are videos`() {
        assertEquals("dQw4w9WgXcQ", (YouTubeLinks.classify("https://youtu.be/dQw4w9WgXcQ?si=xyz") as YouTubeLink.Video).videoId)
        assertEquals("abcdefghijk", (YouTubeLinks.classify("https://www.youtube.com/shorts/abcdefghijk") as YouTubeLink.Video).videoId)
        assertEquals("ZVgHPSyEIqk", (YouTubeLinks.classify("https://music.youtube.com/watch?v=ZVgHPSyEIqk&feature=share") as YouTubeLink.Video).videoId)
    }

    @Test
    fun `other sites and personal lists pass through`() {
        assertTrue(YouTubeLinks.classify("https://soundcloud.com/artist/track") is YouTubeLink.Other)
        assertTrue(YouTubeLinks.classify("https://www.youtube.com/playlist?list=WL") is YouTubeLink.Other)
        assertTrue(YouTubeLinks.classify("https://notyoutube.com/watch?v=dQw4w9WgXcQ") is YouTubeLink.Other)
    }

    @Test
    fun `links are pulled out of shared text and pasted lists`() {
        val text = """
            Check this: https://youtu.be/dQw4w9WgXcQ?si=1.
            and (https://www.youtube.com/watch?v=ZVgHPSyEIqk)
            https://youtu.be/dQw4w9WgXcQ?si=1
        """.trimIndent()
        assertEquals(
            listOf("https://youtu.be/dQw4w9WgXcQ?si=1", "https://www.youtube.com/watch?v=ZVgHPSyEIqk"),
            YouTubeLinks.extractUrls(text),
        )
        assertTrue(YouTubeLinks.isSearch("radiohead let down"))
        assertFalse(YouTubeLinks.isSearch("https://youtu.be/dQw4w9WgXcQ"))
        assertFalse(YouTubeLinks.isSearch("   "))
    }

    @Test
    fun `flat search output is parsed and junk dropped`() {
        val sep = "\u001F"
        val lines = listOf(
            listOf("ZVgHPSyEIqk", "Let Down (Remastered)", "Radiohead", "300", "144792088").joinToString(sep),
            listOf("Ge4EUrjZ3DE", "Let Down - Radiohead - Lyrics", "NA", "300.0", "NA").joinToString(sep),
            listOf("xxxxxxxxxxx", "[Private video]", "NA", "0", "NA").joinToString(sep),
            listOf("ZVgHPSyEIqk", "duplicate", "x", "1", "1").joinToString(sep),
            "garbage",
        )
        val entries = YouTubeSearch.parseEntries(lines)
        assertEquals(2, entries.size)
        assertEquals("Radiohead", entries[0].channel)
        assertEquals(300L, entries[0].durationSeconds)
        assertEquals(null, entries[1].channel)
        assertEquals(null, entries[1].views)
        assertEquals("https://i.ytimg.com/vi/ZVgHPSyEIqk/mqdefault.jpg", entries[0].thumbnailUrl)
    }

    @Test
    fun `durations and views read like YouTube's`() {
        assertEquals("5:00", YouTubeSearch.formatDuration(300))
        assertEquals("1:02:03", YouTubeSearch.formatDuration(3723))
        assertEquals("", YouTubeSearch.formatDuration(0))
        assertEquals("144.8M views", YouTubeSearch.formatViews(144_792_088))
        assertEquals("6K views", YouTubeSearch.formatViews(6_278))
        assertEquals("2B views", YouTubeSearch.formatViews(2_000_000_000))
    }

    @Test
    fun `the playlist file lists songs relative to Music in order`() {
        val tracks = listOf(
            PlaylistImports.Track("a", "Airbag", "Radiohead", 285) to "/storage/emulated/0/Music/Gramophone/Radiohead - Airbag.m4a",
            PlaylistImports.Track("b", "Lucky", null, 0) to "/sdcard/Other/Lucky.m4a",
        )
        val text = PlaylistImports.m3u("OK Computer", tracks, "/storage/emulated/0/Music/")
        assertEquals(
            """
            #EXTM3U
            #PLAYLIST:OK Computer
            #EXTINF:285,Radiohead - Airbag
            Gramophone/Radiohead - Airbag.m4a
            #EXTINF:-1,Lucky
            /sdcard/Other/Lucky.m4a
            """.trimIndent(),
            text.trimEnd(),
        )
        assertEquals("AC DC Back In Black", PlaylistImports.safeFileName("AC/DC: Back In Black?"))
    }
}
