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

/**
 * Turns yt-dlp's stderr into something worth showing a person.
 *
 * This matters more than it looks. The single most likely failure in this whole
 * app is YouTube changing its player and the bundled extractor going stale, and
 * the fix for that is one tap in the menu. A raw Python traceback tells nobody
 * that. Each message here names the actual next step.
 *
 * Anything unrecognised falls through to the raw text — a confusing error the
 * user can search for beats a friendly one that hides what happened.
 */
object DownloadError {

    private data class Rule(val needles: List<String>, val message: String)

    private val RULES = listOf(
        // Ordered deliberately. YouTube phrases both the age gate and the bot
        // check as "Sign in to confirm ...", so the narrower rule goes first
        // and the bot rule never matches on that prefix alone.
        Rule(
            listOf("age-restricted", "age restricted", "confirm your age",
                "inappropriate for some users"),
            "That video is age-restricted, which needs a signed-in account.",
        ),
        Rule(
            listOf("not a bot", "confirm you're not a bot", "confirm youre not a bot"),
            "YouTube asked this download to prove it is not a bot. Try " +
                "Update yt-dlp from the menu; if it keeps happening this video " +
                "needs you to be signed in, which this app cannot do.",
        ),
        Rule(
            listOf("nsig extraction failed", "unable to extract", "player response",
                "failed to extract any player response", "signature extraction failed"),
            "yt-dlp could not read YouTube's player. This is what happens when " +
                "YouTube changes it — tap Update yt-dlp from the menu and try again.",
        ),
        // Seen on a device with the bundled (ten-month-old) yt-dlp: YouTube
        // stopped handing that client direct audio URLs, the download wrote
        // nothing, and the only ERROR lines were about renaming a missing
        // .part file. The warning above them is the real story.
        Rule(
            listOf("forcing sabr", "sabr streaming", "formats have been skipped",
                "older than 90 days"),
            "This yt-dlp is too old for YouTube. Tap Update yt-dlp from the menu " +
                "and try again.",
        ),
        Rule(
            listOf("private video", "video is private"),
            "That video is private.",
        ),
        Rule(
            listOf("video unavailable", "video is unavailable", "this video is not available",
                "has been removed"),
            "That video is not available.",
        ),
        Rule(
            listOf("members-only", "music premium", "join this channel"),
            "That video is for paying members only.",
        ),
        Rule(
            listOf("requested format is not available", "no video formats found"),
            "No audio stream was offered for that video. Try a different format " +
                "in the picker.",
        ),
        Rule(
            listOf("unable to download webpage", "getaddrinfo", "name or service not known",
                "network is unreachable", "temporary failure in name resolution",
                "connection refused", "connection reset"),
            "Could not reach YouTube. Check your connection and try again.",
        ),
        Rule(
            listOf("http error 429", "too many requests"),
            "YouTube is rate-limiting this device. Wait a few minutes and try again.",
        ),
        Rule(
            listOf("http error 403", "forbidden"),
            "YouTube refused the download (403). Tap Update yt-dlp from the menu, " +
                "then try again.",
        ),
        Rule(
            listOf("no space left", "enospc"),
            "Your device is out of storage.",
        ),
        Rule(
            listOf("is not a valid url", "unsupported url"),
            "That does not look like a link yt-dlp can handle.",
        ),
    )

    /**
     * Whether [stderr] describes the extractor falling behind YouTube — the
     * failures an update of yt-dlp itself is the fix for. Used to update and
     * retry automatically before bothering the user with the message.
     */
    fun needsUpdate(stderr: String?): Boolean {
        val haystack = stderr?.lowercase().orEmpty()
        return UPDATE_NEEDLES.any { haystack.contains(it) }
    }

    private val UPDATE_NEEDLES = listOf(
        "nsig extraction failed", "unable to extract", "player response",
        "signature extraction failed", "forcing sabr", "sabr streaming",
        "formats have been skipped", "older than 90 days", "http error 403",
    )

    /**
     * @param stderr yt-dlp's error output
     * @param fallback used when [stderr] is empty
     */
    fun humanize(stderr: String?, fallback: String = "The download failed."): String {
        val text = stderr?.trim().orEmpty()
        if (text.isEmpty()) return fallback

        val haystack = text.lowercase()
        RULES.firstOrNull { rule -> rule.needles.any { haystack.contains(it) } }
            ?.let { return it.message }

        // Unrecognised: show yt-dlp's own last words rather than inventing a
        // summary. The final ERROR line is almost always the useful one.
        val errorLine = text.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("ERROR:", ignoreCase = true) }
        return errorLine?.removePrefix("ERROR:")?.removePrefix("ERROR")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: text.takeLast(300)
    }
}
