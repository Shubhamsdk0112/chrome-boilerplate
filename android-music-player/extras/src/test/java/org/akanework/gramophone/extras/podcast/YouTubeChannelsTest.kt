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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shapes taken from YouTube's real channel feed and yt-dlp's thumbnail list. */
class YouTubeChannelsTest {

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
         <title>Blender</title>
         <entry>
          <id>yt:video:aqz-KE-bpKQ</id>
          <yt:videoId>aqz-KE-bpKQ</yt:videoId>
          <title>Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film</title>
          <link rel="alternate" href="https://www.youtube.com/watch?v=aqz-KE-bpKQ"/>
          <published>2026-09-20T15:00:03+00:00</published>
          <media:group>
           <media:title>Big Buck Bunny 60fps 4K - Official Blender Foundation Short Film</media:title>
           <media:thumbnail url="https://i2.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg" width="480" height="360"/>
           <media:description>The short film.</media:description>
          </media:group>
         </entry>
         <entry>
          <yt:videoId>shortshort1</yt:videoId>
          <title>A short #shorts</title>
          <link rel="alternate" href="https://www.youtube.com/shorts/shortshort1"/>
          <published>2026-09-21T15:00:03+00:00</published>
         </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `channel feed becomes downloadable episodes and shorts are dropped`() {
        val episodes = YouTubeChannels.parseFeed("yt:channel:UCSMOQeBJ2RAnuFungnQOxLg", feed)
        assertEquals(1, episodes.size)
        val e = episodes[0]
        assertEquals("yt:aqz-KE-bpKQ", e.guid) // same guid a download produces, so they merge
        assertEquals("https://www.youtube.com/watch?v=aqz-KE-bpKQ", e.audioUrl)
        assertEquals(EpisodeSource.YOUTUBE, e.source)
        assertFalse(e.streamable)
        assertEquals("The short film.", e.description)
        assertEquals("https://i2.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg", e.imageUrl)
        assertEquals(1789916403000L, e.publishedAt)
    }

    @Test
    fun `the square thumbnail is the avatar`() {
        val json = """[{"url": "https://yt3.googleusercontent.com/banner=w1060", "id": "0", "width": 1060, "height": 175},
            {"url": "https://yt3.googleusercontent.com/banner", "id": "banner_uncropped"},
            {"url": "https://yt3.googleusercontent.com/ytc/avatar=s900", "id": "7", "width": 900, "height": 900},
            {"url": "https://yt3.googleusercontent.com/ytc/avatar", "id": "avatar_uncropped"}]"""
        assertEquals("https://yt3.googleusercontent.com/ytc/avatar=s900", YouTubeChannels.pickAvatar(json))
        assertEquals(
            "https://yt3.googleusercontent.com/ytc/avatar",
            YouTubeChannels.pickAvatar("""[{"url": "https://yt3.googleusercontent.com/ytc/avatar", "id": "avatar_uncropped"}]"""),
        )
        assertNull(YouTubeChannels.pickAvatar("NA"))
    }

    @Test
    fun `only real channel ids can be refreshed`() {
        assertEquals("UCSMOQeBJ2RAnuFungnQOxLg", YouTubeChannels.channelId("yt:channel:UCSMOQeBJ2RAnuFungnQOxLg"))
        assertNull(YouTubeChannels.channelId("yt:channel:Some Channel Name"))
        assertNull(YouTubeChannels.channelId("https://feeds.example.com/rss"))
        assertTrue(YouTubeChannels.needsAvatar("https://i.ytimg.com/vi/aqz-KE-bpKQ/maxresdefault.jpg"))
        assertFalse(YouTubeChannels.needsAvatar("https://yt3.googleusercontent.com/ytc/avatar=s900"))
    }
}
