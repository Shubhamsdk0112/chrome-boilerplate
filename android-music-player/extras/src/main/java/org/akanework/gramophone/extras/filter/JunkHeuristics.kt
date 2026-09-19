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

/** User-adjustable policy for what counts as junk. */
data class FilterOptions(
    val hideVoiceNotes: Boolean = true,
    val hideRingtonesAndAlarms: Boolean = true,
    val hideVideoFiles: Boolean = true,
    /** Podcasts and audiobooks are legitimate audio, so off by default. */
    val hidePodcastsAndAudiobooks: Boolean = false,
    val hideShortClips: Boolean = true,
    val shortClipSeconds: Int = 30,
)

/**
 * Stage one: decide what a file is from its name, folder and tags alone.
 *
 * Two kinds of rule, in order:
 *
 *  1. **Decisive** rules, for things that are simply not music — a file named
 *     `AUD-20240102-WA0007.opus` is a WhatsApp voice note and no amount of
 *     counter-evidence changes that. These short-circuit.
 *  2. **Weighted** signals for everything else, summed into a score. Anything
 *     that lands in the middle is returned as [Judgement.UNSURE] and is what
 *     the optional AI pass looks at — which keeps that pass small and cheap,
 *     because the overwhelming majority of a real library never reaches it.
 *
 * Nothing here deletes or modifies a file. A JUNK verdict only means the path
 * gets added to Gramophone's blacklist so the library stops showing it.
 */
object JunkHeuristics {

    // ---------------------------------------------------------------
    // Decisive patterns
    // ---------------------------------------------------------------

    /** Messaging apps name their audio predictably. */
    private val MESSENGER_FILENAMES = listOf(
        // WhatsApp: AUD-20240102-WA0007, PTT-20240102-WA0007 (push-to-talk)
        Regex("""^(aud|ptt|vid|img|doc)-\d{8}-wa\d+""", RegexOption.IGNORE_CASE),
        // Telegram: audio_2024-01-02_12-00-00
        Regex("""^audio_\d{4}-\d{2}-\d{2}""", RegexOption.IGNORE_CASE),
        // Signal
        Regex("""^signal-\d{4}-\d{2}-\d{2}""", RegexOption.IGNORE_CASE),
    )

    /** Screen recorders and meeting apps, which produce audio nobody filed. */
    private val CAPTURE_FILENAMES = listOf(
        Regex("""^screen[\s_-]?record""", RegexOption.IGNORE_CASE),
        Regex("""^screenrec""", RegexOption.IGNORE_CASE),
        Regex("""^(zoom|gmt)[\s_-]?\d""", RegexOption.IGNORE_CASE),
        Regex("""^audio_only""", RegexOption.IGNORE_CASE),
        Regex("""^(vid|img|mvimg|pxl)[\s_-]?\d{8}""", RegexOption.IGNORE_CASE),
    )

    /** Voice recorder and call recorder output. */
    private val RECORDER_FILENAMES = listOf(
        Regex("""^(new\s+)?recording[\s_-]*\d*""", RegexOption.IGNORE_CASE),
        Regex("""^(rec|vn|voice|memo|snd|sound)[\s_-]*\d""", RegexOption.IGNORE_CASE),
        Regex("""^voice[\s_-]?(note|memo|recorder)""", RegexOption.IGNORE_CASE),
        Regex("""call[\s_-]?recording""", RegexOption.IGNORE_CASE),
        Regex("""^my[\s_-]?recording""", RegexOption.IGNORE_CASE),
    )

    /** Folder fragments that never contain a music library. */
    private val JUNK_FOLDERS = listOf(
        "/whatsapp/media/whatsapp audio",
        "/whatsapp/media/whatsapp voice notes",
        "/whatsapp voice notes",
        "/whatsapp audio",
        "/com.whatsapp/",
        "/telegram audio",
        "/telegram/",
        "/org.telegram",
        "/signal/",
        "/recordings",
        "/voice recorder",
        "/voicerecorder",
        "/soundrecorder",
        "/call recordings",
        "/callrecordings",
        "/screenrecorder",
        "/screen recorder",
        // OEM recorder folders, which upstream's folder defaults do not cover
        // because they are vendor-specific rather than standard Android ones.
        "/miui/sound_recorder",
        "/miui/sound_recorder/call_rec",
        "/sounds/voice_record",
        "/record/",
        "/audiorecorder",
        "/easy voice recorder",
        "/smart voice recorder",
    )

    /** Android's own non-music audio buckets. */
    private val SYSTEM_SOUND_FOLDERS = listOf(
        "/ringtones", "/notifications", "/alarms", "/ui", "/media/audio",
    )

    /** Codecs used for speech that are effectively never used for music. */
    private val SPEECH_ONLY_EXTENSIONS = setOf("amr", "awb", "qcp", "3gp", "3gpp", "gsm")

    /** Video containers. `m4a` is deliberately absent: it is an audio container. */
    private val VIDEO_EXTENSIONS =
        setOf("mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "mpg", "mpeg", "m4v", "ts")

    private val MUSIC_FOLDERS = listOf("/music", "/musica", "/songs", "/itunes", "/media/music")

    // ---------------------------------------------------------------

    fun classify(
        candidate: AudioCandidate,
        options: FilterOptions = FilterOptions(),
    ): Verdict {
        decisive(candidate, options)?.let { return it }

        val signals = weigh(candidate)
        val score = signals.sumOf { it.weight }

        return when {
            score >= MUSIC_THRESHOLD -> Verdict(Judgement.MUSIC, explain(signals, positive = true))
            score <= JUNK_THRESHOLD -> Verdict(Judgement.JUNK, explain(signals, positive = false))
            else -> Verdict(Judgement.UNSURE, "Not obviously music or junk")
        }
    }

    private fun decisive(c: AudioCandidate, options: FilterOptions): Verdict? {
        val name = c.nameWithoutExtension
        val folder = c.folder

        if (options.hideVideoFiles && c.extension in VIDEO_EXTENSIONS) {
            return junk("Video file (.${c.extension})")
        }

        if (options.hideVoiceNotes) {
            if (MESSENGER_FILENAMES.any { it.containsMatchIn(name) }) {
                return junk("Named like a messaging-app voice note")
            }
            if (JUNK_FOLDERS.any { folder.contains(it) }) {
                return junk("Stored in a messaging or recorder folder")
            }
            if (RECORDER_FILENAMES.any { it.containsMatchIn(name) }) {
                return junk("Named like a voice recording")
            }
            if (CAPTURE_FILENAMES.any { it.containsMatchIn(name) }) {
                return junk("Named like a screen or meeting recording")
            }
            // "some doesn't even have names" — a file with nothing but an
            // extension was never something anyone chose to keep as music.
            if (name.isBlank()) {
                return junk("File has no name")
            }
            if (c.isRecording) {
                return junk("Marked as a recording by Android")
            }
            if (c.extension in SPEECH_ONLY_EXTENSIONS) {
                return junk("Speech codec (.${c.extension})")
            }
        }

        if (options.hideRingtonesAndAlarms) {
            if (c.isRingtone) return junk("Marked as a ringtone")
            if (c.isNotification) return junk("Marked as a notification sound")
            if (c.isAlarm) return junk("Marked as an alarm sound")
            if (SYSTEM_SOUND_FOLDERS.any { folder.endsWith(it) || folder.contains("$it/") }) {
                return junk("Stored in a system sounds folder")
            }
        }

        // A very short clip that nobody ever tagged is a notification blip or a
        // stray recording. Tagged short tracks (intros, interludes, skits) are
        // deliberately spared by the tag check.
        if (options.hideShortClips && c.durationMs in 1 until options.shortClipSeconds * 1000L &&
            c.hasNoUsefulTags
        ) {
            return junk("Untagged clip under ${options.shortClipSeconds}s")
        }

        if (options.hidePodcastsAndAudiobooks) {
            if (c.isPodcast) return junk("Marked as a podcast")
            if (c.isAudiobook) return junk("Marked as an audiobook")
        }

        return null
    }

    private fun weigh(c: AudioCandidate): List<Signal> = buildList {
        // --- positive: it looks like a tagged song in a music folder ---
        val hasArtist = !c.artist.isNullOrBlank() && !c.artist.equals("<unknown>", true)
        val hasAlbum = !c.album.isNullOrBlank()
        when {
            hasArtist && hasAlbum -> add(Signal(45, "has artist and album tags"))
            hasArtist -> add(Signal(28, "has an artist tag"))
        }
        if (MUSIC_FOLDERS.any { c.folder.contains(it) }) {
            add(Signal(22, "stored in a music folder"))
        }

        // --- bitrate separates speech from music better than anything else ---
        c.approximateBitrateKbps?.let { kbps ->
            when {
                kbps >= 128 -> add(Signal(20, "${kbps}kbps, typical of music"))
                kbps >= 96 -> add(Signal(10, "${kbps}kbps"))
                kbps < 48 -> add(Signal(-32, "only ${kbps}kbps, typical of speech"))
                kbps < 64 -> add(Signal(-18, "low bitrate (${kbps}kbps)"))
            }
        }

        // --- duration ---
        val seconds = c.durationMs / 1000
        when {
            seconds in 90..900 -> add(Signal(12, "song-length"))
            seconds in 1..29 -> add(Signal(-30, "only ${seconds}s long"))
            seconds in 30..59 -> add(Signal(-14, "under a minute"))
            seconds > 3600 -> add(Signal(-12, "over an hour long"))
        }

        // --- naming and tagging ---
        if (c.hasNoUsefulTags) add(Signal(-26, "no artist or album tags"))
        if (looksMachineGenerated(c.nameWithoutExtension)) {
            add(Signal(-24, "filename is a timestamp or serial number"))
        }
        if (!c.isMusicFlag) add(Signal(-22, "Android does not classify it as music"))
        if (c.isPodcast) add(Signal(-14, "marked as a podcast"))
        if (c.isAudiobook) add(Signal(-14, "marked as an audiobook"))
        if (c.folder.endsWith("/download") || c.folder.endsWith("/downloads")) {
            add(Signal(-8, "sitting in Downloads"))
        }
        // Another app's private media directory. Weighted rather than decisive:
        // a few legitimate music apps do keep libraries under here.
        if (c.folder.contains("/android/media/") || c.folder.contains("/android/data/")) {
            add(Signal(-20, "inside another app's storage"))
        }
        // Camera and gallery folders hold captures, not music.
        if (listOf("/dcim", "/movies", "/pictures", "/screenshots")
                .any { c.folder.contains(it) }
        ) {
            add(Signal(-18, "in a camera or gallery folder"))
        }
    }

    /**
     * True for names like `20240102_120000`, `1704192000` or `audio001` — names
     * carrying no words a human chose. Real song files almost always contain
     * letters that form words.
     */
    internal fun looksMachineGenerated(name: String): Boolean {
        val letters = name.count { it.isLetter() }
        val digits = name.count { it.isDigit() }
        if (digits < 6) return false
        // Allow a short prefix like "AUD" or "audio" but not a real title.
        return letters <= 5 || digits >= letters * 2
    }

    private fun junk(reason: String) = Verdict(Judgement.JUNK, reason)

    private fun explain(signals: List<Signal>, positive: Boolean): String {
        val relevant = signals
            .filter { if (positive) it.weight > 0 else it.weight < 0 }
            .sortedByDescending { kotlin.math.abs(it.weight) }
            .take(2)
            .map { it.reason }
        return if (relevant.isEmpty()) {
            if (positive) "Looks like music" else "Does not look like music"
        } else {
            relevant.joinToString(", ").replaceFirstChar { it.uppercase() }
        }
    }

    private data class Signal(val weight: Int, val reason: String)

    private const val MUSIC_THRESHOLD = 35
    private const val JUNK_THRESHOLD = -35
}
