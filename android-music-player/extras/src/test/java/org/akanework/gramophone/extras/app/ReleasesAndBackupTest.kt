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

package org.akanework.gramophone.extras.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesAndBackupTest {

    /** Trimmed from the real GitHub API response for this repository. */
    private val releases = """
        [
          {"tag_name": "extras-v0.2.2", "draft": false, "prerelease": true,
           "html_url": "https://github.com/Shubhamsdk0112/chrome-boilerplate/releases/tag/extras-v0.2.2",
           "body": "Quick fix", "assets": [
             {"name": "Gramophone-1.1.2.e821733-arm64-v8a-release.apk", "browser_download_url": "https://x/arm64.apk"},
             {"name": "Gramophone-1.1.2.e821733-armeabi-v7a-release.apk", "browser_download_url": "https://x/v7a.apk"},
             {"name": "Gramophone-1.1.2.e821733-x86_64-release.apk", "browser_download_url": "https://x/x86_64.apk"}]},
          {"tag_name": "extras-v0.10.0", "draft": false, "html_url": "h", "body": "", "assets": []},
          {"tag_name": "extras-v0.9.0", "draft": true, "html_url": "h", "body": "", "assets": []},
          {"tag_name": "something-else-v9", "html_url": "h", "body": "", "assets": []},
          {"tag_name": "extras-vbeta", "html_url": "h", "body": "", "assets": []}
        ]
    """.trimIndent()

    @Test
    fun `only extras releases count and drafts are skipped`() {
        val parsed = Releases.parse(releases, abi = "arm64-v8a")
        assertEquals(listOf("0.2.2", "0.10.0"), parsed.map { it.version })
        assertEquals("https://x/arm64.apk", parsed[0].apkUrl)
        assertEquals(null, parsed[1].apkUrl)
    }

    @Test
    fun `the apk matches the phone's architecture`() {
        assertEquals("https://x/v7a.apk", Releases.parse(releases, abi = "armeabi-v7a")[0].apkUrl)
        assertEquals("https://x/x86_64.apk", Releases.parse(releases, abi = "x86_64")[0].apkUrl)
        // Unknown ABI: arm64 is what nearly every phone runs.
        assertEquals("https://x/arm64.apk", Releases.parse(releases, abi = "riscv64")[0].apkUrl)
    }

    @Test
    fun `versions compare as numbers`() {
        assertTrue(Releases.isNewer("0.10.0", "0.9.9"))
        assertTrue(Releases.isNewer("0.3.0", "0.2.2"))
        assertTrue(Releases.isNewer("1.0", "0.99.99"))
        assertFalse(Releases.isNewer("0.3.0", "0.3.0"))
        assertFalse(Releases.isNewer("0.2.10", "0.3"))
        assertEquals(0, Releases.compareVersions("0.3", "0.3.0"))
    }

    @Test
    fun `settings keep their types through a backup`() {
        val json = Backup.prefsToJson(
            mapOf(
                "enabled" to true, "short_seconds" to 30, "last" to 1790237746474L,
                "speed" to 1.5f, "model" to "google/gemini-2.5-flash-lite", "set" to setOf("a", "b"),
            ),
        )
        val back = JSONObject(json.toString())
        assertEquals("b", back.getJSONObject("enabled").getString("t"))
        assertEquals("i", back.getJSONObject("short_seconds").getString("t"))
        assertEquals("l", back.getJSONObject("last").getString("t"))
        assertEquals("f", back.getJSONObject("speed").getString("t"))
        assertEquals(1.5, back.getJSONObject("speed").getDouble("v"), 0.0001)
        assertEquals("ss", back.getJSONObject("set").getString("t"))
    }

    @Test
    fun `a file that is not a backup is refused with a readable message`() {
        for (bad in listOf("", "hello", "{}", """{"app": "other", "format": 1}""")) {
            try {
                Backup.parse(bad)
                throw AssertionError("accepted: $bad")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("not a backup"))
            }
        }
        try {
            Backup.parse("""{"app": "gramophone-extras", "format": 99}""")
            throw AssertionError("accepted a future format")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("newer version"))
        }
        val ok = Backup.inspect(
            """{"app": "gramophone-extras", "format": 1, "version": "0.3.0", "createdAt": 5,
                "files": {"extras_podcasts.json": "{\"podcasts\": [{}, {}]}"}}""",
        )
        assertEquals(2, ok.shows)
        assertEquals("0.3.0", ok.version)
    }
}
