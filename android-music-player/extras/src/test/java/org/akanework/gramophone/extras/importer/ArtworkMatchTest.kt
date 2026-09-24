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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding whether a catalogue result really is this song.
 *
 * Getting this wrong is worse than getting nothing: a wrong cover looks
 * deliberate and sticks around in the library, whereas rejecting a match just
 * falls back to the video's own thumbnail.
 */
class ArtworkMatchTest {

    private fun meta(title: String, artist: String? = null) = TrackMetadata(
        videoId = "abc",
        title = title,
        artist = artist,
        album = null,
        year = null,
        durationSeconds = 240,
        thumbnailUrl = null,
        rawTitle = title,
    )

    private fun matches(m: TrackMetadata, title: String, artist: String) =
        ArtworkFinder.matches(m, title, artist)

    @Test
    fun `an exact match with a matching artist is accepted`() {
        assertTrue(matches(meta("Get Lucky", "Daft Punk"), "Get Lucky", "Daft Punk"))
    }

    @Test
    fun `a qualifier is tolerated when the artist confirms it`() {
        assertTrue(matches(meta("Get Lucky", "Daft Punk"), "Get Lucky (Radio Edit)", "Daft Punk"))
    }

    @Test
    fun `the right title by the wrong artist is rejected`() {
        assertFalse(matches(meta("Get Lucky", "Daft Punk"), "Get Lucky", "Some Cover Band"))
    }

    @Test
    fun `a different song is rejected`() {
        assertFalse(matches(meta("Get Lucky", "Daft Punk"), "Instant Crush", "Daft Punk"))
    }

    @Test
    fun `a subset title no longer scores as a perfect match`() {
        // Regression: dividing overlap by the SMALLER token set made "Lucky"
        // score 1.0 against "Get Lucky", so an unknown-artist track called
        // Lucky could pick up Daft Punk's cover.
        assertTrue(ArtworkFinder.similarity("Lucky", "Get Lucky") < 0.9)
        assertFalse(matches(meta("Lucky"), "Get Lucky", "Daft Punk"))
    }

    @Test
    fun `with no artist only a near-exact title is accepted`() {
        // Nothing corroborates the match, so the bar is high.
        assertTrue(matches(meta("Instant Crush"), "Instant Crush", "Daft Punk"))
        assertFalse(matches(meta("Instant Crush"), "Instant Crush (Live at Wembley)", "Anyone"))
    }

    @Test
    fun `an empty candidate title is rejected`() {
        assertFalse(matches(meta("Get Lucky", "Daft Punk"), "", "Daft Punk"))
    }

    @Test
    fun `matching ignores case and punctuation`() {
        assertTrue(matches(meta("Get Lucky", "Daft Punk"), "GET LUCKY!", "daft punk"))
    }

    @Test
    fun `similarity is symmetric and bounded`() {
        val a = ArtworkFinder.similarity("Get Lucky", "Lucky Get")
        assertTrue(a == 1.0)
        assertTrue(ArtworkFinder.similarity("", "anything") == 0.0)
        assertTrue(ArtworkFinder.similarity("one two", "three four") == 0.0)
    }
}
