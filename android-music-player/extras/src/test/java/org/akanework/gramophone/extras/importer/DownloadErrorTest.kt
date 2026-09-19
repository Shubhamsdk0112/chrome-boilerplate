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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Error text is the only thing a user sees when a download fails, and the most
 * likely failure by far — a stale extractor after YouTube changes its player —
 * has a one-tap fix they will never guess from a traceback.
 */
class DownloadErrorTest {

    private fun humanize(s: String?) = DownloadError.humanize(s)

    @Test
    fun `a stale extractor points at the update button`() {
        val samples = listOf(
            "ERROR: [youtube] dQw4w9WgXcQ: nsig extraction failed: Some formats may be missing",
            "ERROR: [youtube] dQw4w9WgXcQ: Unable to extract player response",
            "ERROR: unable to extract yt initial data",
        )
        for (s in samples) {
            assertTrue("should mention updating: $s", humanize(s).contains("Update yt-dlp"))
        }
    }

    @Test
    fun `the bot check is explained rather than dumped`() {
        val message = humanize(
            "ERROR: [youtube] abc: Sign in to confirm you're not a bot. Use --cookies-from-browser"
        )
        assertTrue(message.contains("not a bot"))
        // It must not leak yt-dlp flags the user cannot act on from a phone.
        assertTrue(!message.contains("--cookies-from-browser"))
    }

    @Test
    fun `common unavailability cases get plain messages`() {
        assertTrue(humanize("ERROR: [youtube] abc: Private video").contains("private"))
        assertTrue(humanize("ERROR: [youtube] abc: Video unavailable").contains("not available"))
        assertTrue(humanize("ERROR: This video is available to Music Premium members only")
            .contains("members only"))
        assertTrue(humanize("ERROR: [youtube] abc: Sign in to confirm your age")
            .contains("age-restricted"))
    }

    @Test
    fun `network and rate limit failures are distinguished`() {
        assertTrue(humanize("ERROR: Unable to download webpage: <urlopen error getaddrinfo failed>")
            .contains("Check your connection"))
        assertTrue(humanize("ERROR: HTTP Error 429: Too Many Requests").contains("rate-limiting"))
        assertTrue(humanize("ERROR: unable to download video data: HTTP Error 403: Forbidden")
            .contains("403"))
    }

    @Test
    fun `a full disk is reported as a full disk`() {
        assertTrue(humanize("OSError: [Errno 28] No space left on device").contains("storage"))
    }

    @Test
    fun `an unrecognised failure shows yt-dlp's own last error line`() {
        val stderr = """
            [youtube] Extracting URL
            [info] Downloading 1 format(s)
            ERROR: something nobody predicted went wrong
        """.trimIndent()
        assertEquals("something nobody predicted went wrong", humanize(stderr))
    }

    @Test
    fun `with several error lines the last one wins`() {
        val stderr = "ERROR: first problem\nsome noise\nERROR: the actual problem"
        assertEquals("the actual problem", humanize(stderr))
    }

    @Test
    fun `output with no ERROR line still shows something`() {
        val message = humanize("just some unstructured noise from python")
        assertTrue(message.isNotBlank())
        assertTrue(message.contains("unstructured noise"))
    }

    @Test
    fun `empty stderr falls back`() {
        assertEquals("The download failed.", humanize(null))
        assertEquals("The download failed.", humanize(""))
        assertEquals("The download failed.", humanize("   \n  "))
        assertEquals(
            "custom",
            DownloadError.humanize(null, fallback = "custom"),
        )
    }

    @Test
    fun `matching is case insensitive`() {
        assertTrue(humanize("error: PRIVATE VIDEO").contains("private"))
    }

    // Verbatim from a device running the bundled 2025.11.12 build: YouTube
    // moved that client to SABR, the download wrote nothing, and the only
    // ERROR lines were about a missing .part file. The rename error is a
    // symptom; the message has to talk about the cause.
    private val staleSabrOutput = """
        WARNING: Your yt-dlp version (2025.11.12) is older than 90 days!
                 It is strongly recommended to always use the latest version.
        WARNING: [youtube] jNQXAC9IVRw: Some web client https formats have been skipped as they are missing a url. YouTube is forcing SABR streaming for this client. See  https://github.com/yt-dlp/yt-dlp/issues/12482  for more details
        ERROR: Unable to rename file: [Errno 2] No such file or directory: '/data/user/0/x/cache/ytdlp-work/1/jNQXAC9IVRw.m4a.part' -> '/data/user/0/x/cache/ytdlp-work/1/jNQXAC9IVRw.m4a'
        ERROR: [Errno 2] No such file or directory: '/data/user/0/x/cache/ytdlp-work/1/jNQXAC9IVRw.m4a'
    """.trimIndent()

    @Test
    fun `a client YouTube has moved to SABR points at the update button`() {
        val message = humanize(staleSabrOutput)
        assertTrue(message, message.contains("Update yt-dlp"))
        assertTrue(message, !message.contains(".part"))
    }

    @Test
    fun `needsUpdate recognises the failures an update fixes`() {
        assertTrue(DownloadError.needsUpdate(staleSabrOutput))
        assertTrue(DownloadError.needsUpdate("ERROR: [youtube] x: nsig extraction failed"))
        assertTrue(DownloadError.needsUpdate("ERROR: unable to download video data: HTTP Error 403: Forbidden"))
    }

    @Test
    fun `needsUpdate stays quiet for failures an update cannot fix`() {
        assertTrue(!DownloadError.needsUpdate(null))
        assertTrue(!DownloadError.needsUpdate("ERROR: [youtube] abc: Private video"))
        assertTrue(!DownloadError.needsUpdate("ERROR: Unable to handle request: Unsupported url scheme: \"htttps\""))
        assertTrue(!DownloadError.needsUpdate("ERROR: unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>"))
    }

    @Test
    fun `a mistyped scheme is explained rather than dumped`() {
        // Also verbatim from a device.
        val message = humanize("ERROR: Unable to handle request: Unsupported url scheme: \"htttps\" (urllib)")
        assertTrue(message, message.contains("does not look like a link"))
    }
}
