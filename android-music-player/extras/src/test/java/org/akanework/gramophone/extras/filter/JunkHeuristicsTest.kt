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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classifier decides what disappears from someone's library, so the cost of
 * a false JUNK is much higher than a false UNSURE. These cases are drawn from
 * what actually accumulates on a phone.
 */
class JunkHeuristicsTest {

    private fun candidate(
        path: String,
        durationMs: Long = 240_000,
        sizeBytes: Long = 5_760_000, // ~192kbps at 4 minutes
        artist: String? = null,
        album: String? = null,
        title: String? = null,
        isMusicFlag: Boolean = true,
        isRingtone: Boolean = false,
        isNotification: Boolean = false,
        isAlarm: Boolean = false,
        isRecording: Boolean = false,
        isPodcast: Boolean = false,
        isAudiobook: Boolean = false,
    ) = AudioCandidate(
        id = 1,
        path = path,
        displayName = path.substringAfterLast('/'),
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        isMusicFlag = isMusicFlag,
        isRingtone = isRingtone,
        isNotification = isNotification,
        isAlarm = isAlarm,
        isRecording = isRecording,
        isPodcast = isPodcast,
        isAudiobook = isAudiobook,
    )

    private fun judge(c: AudioCandidate, o: FilterOptions = FilterOptions()) =
        JunkHeuristics.classify(c, o).judgement

    // ---------------------------------------------------------------
    // The things the user actually wants gone
    // ---------------------------------------------------------------

    @Test
    fun `whatsapp voice notes are junk`() {
        val paths = listOf(
            "/storage/emulated/0/WhatsApp/Media/WhatsApp Audio/AUD-20240102-WA0007.opus",
            "/storage/emulated/0/WhatsApp/Media/WhatsApp Voice Notes/202401/PTT-20240102-WA0003.opus",
            "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/AUD-20231225-WA0001.m4a",
        )
        for (p in paths) {
            assertEquals("should be junk: $p", Judgement.JUNK, judge(candidate(p)))
        }
    }

    @Test
    fun `a whatsapp audio keeps being junk even with a plausible duration and size`() {
        // The decisive rules must not be out-voted by song-like weighted signals.
        val c = candidate(
            "/storage/emulated/0/WhatsApp/Media/WhatsApp Audio/AUD-20240102-WA0007.opus",
            durationMs = 240_000,
            sizeBytes = 6_000_000,
            artist = "Unknown",
            album = "WhatsApp",
        )
        assertEquals(Judgement.JUNK, judge(c))
    }

    @Test
    fun `telegram and signal audio are junk`() {
        assertEquals(
            Judgement.JUNK,
            judge(candidate("/storage/emulated/0/Telegram/Telegram Audio/audio_2024-01-02_12-00-00.ogg")),
        )
        assertEquals(
            Judgement.JUNK,
            judge(candidate("/storage/emulated/0/Signal/Media/signal-2024-01-02-120000.aac")),
        )
    }

    @Test
    fun `voice recorder output is junk`() {
        val names = listOf(
            "Recording_001.m4a", "New Recording 12.m4a", "REC_0042.mp3",
            "Voice 003.m4a", "VN_20240102.amr", "My Recording 5.wav",
            "Call recording Mum.m4a",
        )
        for (n in names) {
            val p = "/storage/emulated/0/Sounds/$n"
            assertEquals("should be junk: $n", Judgement.JUNK, judge(candidate(p)))
        }
    }

    @Test
    fun `speech codecs are junk`() {
        assertEquals(Judgement.JUNK, judge(candidate("/storage/emulated/0/Music/whatever.amr")))
        assertEquals(Judgement.JUNK, judge(candidate("/storage/emulated/0/Music/whatever.3gp")))
    }

    @Test
    fun `video files are junk`() {
        for (ext in listOf("mp4", "mkv", "webm", "avi", "mov")) {
            assertEquals(
                "should be junk: .$ext",
                Judgement.JUNK,
                judge(candidate("/storage/emulated/0/Movies/Holiday.$ext")),
            )
        }
    }

    @Test
    fun `m4a is audio and must not be caught by the video rule`() {
        val c = candidate(
            "/storage/emulated/0/Music/Gramophone/Daft Punk - Get Lucky.m4a",
            artist = "Daft Punk",
            album = "Random Access Memories",
        )
        assertEquals(Judgement.MUSIC, judge(c))
    }

    @Test
    fun `system sounds are junk`() {
        assertEquals(
            Judgement.JUNK,
            judge(candidate("/storage/emulated/0/Ringtones/Marimba.ogg", isRingtone = true)),
        )
        assertEquals(
            Judgement.JUNK,
            judge(candidate("/storage/emulated/0/Notifications/Ding.ogg", isNotification = true)),
        )
        assertEquals(
            Judgement.JUNK,
            judge(candidate("/storage/emulated/0/Alarms/Wake.ogg", isAlarm = true)),
        )
        // Even without the MediaStore flag, the folder is enough.
        assertEquals(Judgement.JUNK, judge(candidate("/storage/emulated/0/Ringtones/custom.mp3")))
    }

    @Test
    fun `androids own recording flag is junk`() {
        assertEquals(
            Judgement.JUNK,
            judge(candidate("/storage/emulated/0/Music/interview.m4a", isRecording = true)),
        )
    }

    // ---------------------------------------------------------------
    // The things that must survive
    // ---------------------------------------------------------------

    @Test
    fun `a properly tagged song is music`() {
        val c = candidate(
            "/storage/emulated/0/Music/Daft Punk/Random Access Memories/03 Get Lucky.mp3",
            artist = "Daft Punk",
            album = "Random Access Memories",
            title = "Get Lucky",
        )
        assertEquals(Judgement.MUSIC, judge(c))
    }

    @Test
    fun `a tagged song survives even sitting in Downloads`() {
        val c = candidate(
            "/storage/emulated/0/Download/Get Lucky.mp3",
            artist = "Daft Punk",
            album = "Random Access Memories",
        )
        assertEquals(Judgement.MUSIC, judge(c))
    }

    @Test
    fun `a long untagged file is not confidently junked`() {
        // No tags but clearly music-shaped: must not vanish silently. This is
        // exactly the case the AI pass exists to settle.
        val c = candidate(
            "/storage/emulated/0/Download/some song.mp3",
            durationMs = 240_000,
            sizeBytes = 5_760_000,
        )
        assertEquals(Judgement.UNSURE, judge(c))
    }

    @Test
    fun `podcasts are kept unless the user opts in`() {
        val c = candidate("/storage/emulated/0/Podcasts/Ep 12.mp3", isPodcast = true,
            durationMs = 2_400_000, sizeBytes = 28_800_000)
        assertTrue(judge(c) != Judgement.JUNK)
        assertEquals(
            Judgement.JUNK,
            judge(c, FilterOptions(hidePodcastsAndAudiobooks = true)),
        )
    }

    @Test
    fun `turning the voice-note rules off keeps whatsapp audio`() {
        val c = candidate(
            "/storage/emulated/0/WhatsApp/Media/WhatsApp Audio/AUD-20240102-WA0007.opus",
            artist = "Daft Punk",
            album = "Random Access Memories",
        )
        assertEquals(Judgement.MUSIC, judge(c, FilterOptions(hideVoiceNotes = false)))
    }

    // ---------------------------------------------------------------
    // Weighted signals
    // ---------------------------------------------------------------

    @Test
    fun `a short low-bitrate untagged clip is junk`() {
        val c = candidate(
            "/storage/emulated/0/Download/20240102_120000.opus",
            durationMs = 8_000,
            sizeBytes = 24_000, // ~24kbps
        )
        assertEquals(Judgement.JUNK, judge(c))
    }

    @Test
    fun `short untagged clips are junk but tagged short tracks survive`() {
        val blip = candidate("/storage/emulated/0/Music/blip.ogg",
            durationMs = 4_000, sizeBytes = 64_000)
        assertEquals(Judgement.JUNK, judge(blip))

        // A 20-second tagged interlude is a real track on plenty of albums.
        val interlude = candidate("/storage/emulated/0/Music/Interlude.mp3",
            durationMs = 20_000, sizeBytes = 480_000,
            artist = "Some Artist", album = "Some Album")
        assertTrue(judge(interlude) != Judgement.JUNK)

        // With the rule off, the blip is no longer decisively junk; the
        // weighted signals leave it unsure rather than hidden.
        assertEquals(
            Judgement.UNSURE,
            judge(blip, FilterOptions(hideShortClips = false)),
        )
    }

    @Test
    fun `bitrate is computed in kbps`() {
        // 4 minutes at 192kbps = 5.76 MB
        val c = candidate("/a/b.mp3", durationMs = 240_000, sizeBytes = 5_760_000)
        assertEquals(192, c.approximateBitrateKbps)
    }

    @Test
    fun `bitrate is null when duration is unknown`() {
        assertEquals(null, candidate("/a/b.mp3", durationMs = 0).approximateBitrateKbps)
    }

    @Test
    fun `machine generated names are recognised`() {
        val machine = listOf("20240102_120000", "1704192000", "AUD-20240102", "audio001234")
        for (n in machine) {
            assertTrue("should look machine generated: $n", JunkHeuristics.looksMachineGenerated(n))
        }
        val human = listOf("Get Lucky", "03 Get Lucky", "Daft Punk - Get Lucky", "Song 1")
        for (n in human) {
            assertTrue("should look human: $n", !JunkHeuristics.looksMachineGenerated(n))
        }
    }

    @Test
    fun `every verdict carries a reason`() {
        val samples = listOf(
            candidate("/storage/emulated/0/WhatsApp/Media/WhatsApp Audio/AUD-20240102-WA0007.opus"),
            candidate("/storage/emulated/0/Music/x.mp3", artist = "A", album = "B"),
            candidate("/storage/emulated/0/Download/some song.mp3"),
        )
        for (s in samples) {
            assertTrue(JunkHeuristics.classify(s).reason.isNotBlank())
        }
    }
}
