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

package org.akanework.gramophone.extras.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class StatsTest {

    private fun at(y: Int, m: Int, d: Int, h: Int = 12): Long = Calendar.getInstance().apply {
        clear(); set(y, m - 1, d, h, 0, 0)
    }.timeInMillis

    private fun play(t: Long, id: String, title: String, artist: String? = null, album: String? = null) =
        Play(t, id, title, artist, album)

    private fun entry(id: String, title: String, artist: String?, count: Int, last: Long, album: String? = null) =
        HistoryEntry(id, "content://x/$id", title, artist, album, null, 200_000, count, last)

    @Test fun `artists split on commas, ampersands and feat`() {
        assertEquals(listOf("A", "B", "C", "D"), Stats.splitArtists("A, B & C feat. D"))
        assertEquals(listOf("Daft Punk", "Pharrell"), Stats.splitArtists("Daft Punk ft Pharrell"))
        assertEquals(emptyList<String>(), Stats.splitArtists("<unknown>"))
        assertEquals(emptyList<String>(), Stats.splitArtists(null))
        // a word that merely contains "x" or "ft" is not split
        assertEquals(listOf("Lxst Ftoyou"), Stats.splitArtists("Lxst Ftoyou"))
    }

    @Test fun `longest streak counts consecutive days only`() {
        assertEquals(0, Stats.longestStreak(emptySet()))
        assertEquals(1, Stats.longestStreak(setOf("2026-11-01")))
        assertEquals(3, Stats.longestStreak(setOf("2026-10-30", "2026-10-31", "2026-11-01", "2026-11-03", "2026-11-04")))
    }

    @Test fun `month range covers the whole calendar month`() {
        val (from, to) = Stats.monthRange(at(2026, 11, 13), 0)
        assertEquals(at(2026, 11, 1, 0), from)
        assertEquals(at(2026, 12, 1, 0), to)
        val (pFrom, pTo) = Stats.monthRange(at(2026, 1, 20), -1)
        assertEquals(at(2025, 12, 1, 0), pFrom)
        assertEquals(at(2026, 1, 1, 0), pTo)
    }

    @Test fun `summary ranks songs artists albums and buckets times`() {
        val plays = listOf(
            play(at(2026, 11, 2, 8), "MediaStore:1", "One", "A & B", "Alb"),
            play(at(2026, 11, 2, 9), "MediaStore:1", "One", "A & B", "Alb"),
            play(at(2026, 11, 3, 8), "MediaStore:2", "Two", "A", "Alb"),
            play(at(2026, 11, 5, 22), "MediaStore:3", "Three", "C"),
            play(at(2026, 10, 31, 8), "MediaStore:9", "Old", "Z"), // previous month
        )
        val listened = mapOf("2026-11-02" to 600_000L, "2026-11-03" to 300_000L, "2026-10-31" to 999_000L)
        val (from, to) = Stats.monthRange(at(2026, 11, 13), 0)
        val s = Stats.compute(plays, listened, from, to)
        assertEquals(15L, s.minutes)
        assertEquals(4, s.plays)
        assertEquals(3, s.distinctSongs)
        assertEquals("One", s.topSongs.first().title)
        assertEquals(2, s.topSongs.first().count)
        assertEquals("A", s.topArtists.first().title)
        assertEquals(3, s.topArtists.first().count)
        assertEquals(3, s.distinctArtists)
        assertEquals("Alb", s.topAlbums.single().title)
        assertEquals(2, s.byHour[8])
        assertEquals(1, s.byHour[22])
        assertEquals(2, s.longestStreak)
        // 2 Nov 2026 is a Monday
        assertEquals(2, s.byWeekday[0])
        assertEquals(1, s.byWeekday[1])
    }

    @Test fun `all time rankings come from the per-song counts`() {
        val empty = Stats.compute(emptyList(), emptyMap(), 0, Long.MAX_VALUE)
        val history = listOf(
            entry("MediaStore:1", "Big", "A", 40, at(2026, 1, 1)),
            entry("MediaStore:2", "Small", "B feat. A", 5, at(2026, 1, 1)),
        )
        val s = Stats.withHistory(empty, history)
        assertEquals(45, s.plays)
        assertEquals("Big", s.topSongs.first().title)
        assertEquals("A", s.topArtists.first().title)
        assertEquals(45, s.topArtists.first().count)
    }

    @Test fun `most played this month needs two plays in 30 days`() {
        val now = at(2026, 11, 13)
        val plays = listOf(
            play(now - 1000, "MediaStore:1", "a"), play(now - 2000, "MediaStore:1", "a"), play(now - 3000, "MediaStore:1", "a"),
            play(now - 1000, "MediaStore:2", "b"), play(now - 2000, "MediaStore:2", "b"),
            play(now - 1000, "MediaStore:3", "c"),
            play(now - 40L * 86_400_000, "MediaStore:4", "d"), play(now - 41L * 86_400_000, "MediaStore:4", "d"),
            play(now - 1000, "podcast:x", "p"), play(now - 2000, "podcast:x", "p"),
        )
        assertEquals(listOf("MediaStore:1", "MediaStore:2"), SmartPicks.mostPlayed(plays, now))
    }

    @Test fun `forgotten favourites are loved songs not heard for six weeks`() {
        val now = at(2026, 11, 13)
        val day = 86_400_000L
        val history = listOf(
            entry("MediaStore:1", "loved, old", "A", 12, now - 60 * day),
            entry("MediaStore:2", "loved, recent", "A", 20, now - 3 * day),
            entry("MediaStore:3", "barely played", "A", 1, now - 90 * day),
            entry("MediaStore:4", "loved, older", "A", 30, now - 100 * day),
        )
        val got = SmartPicks.forgotten(history, emptyList(), now)
        assertEquals(listOf("MediaStore:4", "MediaStore:1"), got)
        // a play in the timeline within six weeks keeps it out
        val again = SmartPicks.forgotten(history, listOf(play(now - day, "MediaStore:4", "x")), now)
        assertTrue("MediaStore:4" !in again)
    }

    @Test fun `play log lines round-trip`() {
        val p = play(123L, "MediaStore:7", "Song", "Art", null)
        assertEquals(p, PlayLog.fromJson(PlayLog.toJson(p)))
        assertEquals(null, PlayLog.fromJson("  "))
    }
}
