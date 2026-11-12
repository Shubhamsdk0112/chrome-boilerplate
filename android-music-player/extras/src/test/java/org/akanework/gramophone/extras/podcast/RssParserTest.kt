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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feeds in the wild are messy: iTunes namespaces, CDATA notes with HTML,
 * durations in three formats, dates in RFC 822 with named zones. The parser
 * has to shrug at all of it.
 */
class RssParserTest {

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"
             xmlns:content="http://purl.org/rss/1.0/modules/content/">
          <channel>
            <title>Example Show</title>
            <itunes:author>Example Author</itunes:author>
            <description><![CDATA[<p>A show about <b>things</b>.</p><p>Weekly.</p>]]></description>
            <itunes:image href="https://example.com/cover.jpg"/>
            <image><url>https://example.com/rss-cover.jpg</url><title>x</title></image>
            <item>
              <title>Episode 2: The Second One</title>
              <guid isPermaLink="false">ep-2</guid>
              <pubDate>Fri, 18 Sep 2026 10:00:00 GMT</pubDate>
              <itunes:duration>1:02:03</itunes:duration>
              <itunes:image href="https://example.com/ep2.jpg"/>
              <description><![CDATA[Notes for two.<br/>Second line.]]></description>
              <enclosure url="https://cdn.example.com/ep2.mp3?x=1" type="audio/mpeg" length="123"/>
            </item>
            <item>
              <title>Episode 1</title>
              <guid>ep-1</guid>
              <pubDate>Thu, 10 Sep 2026 08:30:00 -0400</pubDate>
              <itunes:duration>1800</itunes:duration>
              <itunes:summary>Summary one</itunes:summary>
              <enclosure url="https://cdn.example.com/ep1.m4a" type="audio/x-m4a" length="1"/>
            </item>
            <item>
              <title>Video only, should be dropped</title>
              <guid>ep-0</guid>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    private fun parse() = RssParser.parse("https://example.com/feed", feed.byteInputStream(), now = 1_000L)

    @Test
    fun `channel metadata is read, itunes image preferred`() {
        val p = parse()
        assertEquals("Example Show", p.title)
        assertEquals("Example Author", p.author)
        assertEquals("https://example.com/cover.jpg", p.imageUrl)
        assertEquals("A show about things.\n\nWeekly.", p.description)
        assertEquals(1_000L, p.refreshedAt)
    }

    @Test
    fun `items without audio are dropped and the rest are newest first`() {
        val p = parse()
        assertEquals(listOf("ep-2", "ep-1"), p.episodes.map { it.guid })
    }

    @Test
    fun `episode fields`() {
        val ep = parse().episodes.first()
        assertEquals("Episode 2: The Second One", ep.title)
        assertEquals("https://cdn.example.com/ep2.mp3?x=1", ep.audioUrl)
        assertEquals("mp3", ep.extension)
        assertEquals(3723, ep.durationSeconds)
        assertEquals("https://example.com/ep2.jpg", ep.imageUrl)
        assertEquals("Notes for two.\nSecond line.", ep.description)
        assertTrue(ep.publishedAt > 0)
    }

    @Test
    fun `summary is used when there is no description, and m4a keeps its extension`() {
        val ep = parse().episodes[1]
        assertEquals("Summary one", ep.description)
        assertEquals("m4a", ep.extension)
        assertEquals(1800, ep.durationSeconds)
    }

    @Test
    fun `durations in every common shape`() {
        assertEquals(3723, RssParser.parseDuration("1:02:03"))
        assertEquals(3723, RssParser.parseDuration("62:03"))
        assertEquals(3723, RssParser.parseDuration("3723"))
        assertEquals(3723, RssParser.parseDuration("3723.9"))
        assertEquals(0, RssParser.parseDuration(null))
        assertEquals(0, RssParser.parseDuration("soon"))
    }

    @Test
    fun `dates in RFC 822 and ISO shapes, unknown is zero not a crash`() {
        assertTrue(RssParser.parseDate("Fri, 18 Sep 2026 10:00:00 GMT") > 0)
        assertTrue(RssParser.parseDate("Thu, 10 Sep 2026 08:30:00 -0400") > 0)
        assertTrue(RssParser.parseDate("2026-09-18T10:00:00+00:00") > 0)
        assertEquals(0L, RssParser.parseDate("yesterday-ish"))
        assertEquals(0L, RssParser.parseDate(null))
    }

    @Test
    fun `html in notes becomes readable text`() {
        assertEquals("Hello & bye\n\nnext", RssParser.stripHtml("<p>Hello &amp; bye</p><p>next</p>"))
    }

    @Test
    fun `an audio url with no extension defaults to mp3`() {
        val ep = Episode("g", "f", "t", "https://cdn.example.com/stream?id=9", 0, 0, null, null)
        assertEquals("mp3", ep.extension)
    }

    @Test
    fun `content-range total is read for resumes`() {
        assertEquals(1000L, EpisodeDownloader.contentRangeTotal("bytes 100-999/1000"))
        assertNull(EpisodeDownloader.contentRangeTotal("bytes 100-999/*"))
        assertNull(EpisodeDownloader.contentRangeTotal(null))
    }

    @Test
    fun `file names are made safe`() {
        assertEquals("Ep 1 what now", PodcastStore.safe("Ep 1: what/now?"))
        assertEquals("untitled", PodcastStore.safe("???"))
        assertEquals(80, PodcastStore.safe("x".repeat(200)).length)
    }
}
