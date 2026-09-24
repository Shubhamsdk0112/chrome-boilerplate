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

/** The two export formats users actually produce, and the mistakes they make. */
class CookiesTest {

    private val cookieEditorJson = """
        [
          {"domain": ".youtube.com", "expirationDate": 1823180335.9, "httpOnly": true,
           "name": "__Secure-1PSID", "path": "/", "secure": true, "value": "g.a000abc"},
          {"domain": ".youtube.com", "expirationDate": 1824423721.9, "httpOnly": true,
           "name": "LOGIN_INFO", "path": "/", "secure": true, "value": "AFmm:QUQ3"},
          {"domain": ".youtube.com", "name": "PREF", "path": "/", "secure": true,
           "value": "tz=Asia.Calcutta&f7=100"},
          {"domain": ".example.com", "name": "unrelated", "path": "/", "value": "x"}
        ]
    """.trimIndent()

    @Test
    fun `json export keeps youtube cookies and drops the rest`() {
        val cookies = Cookies.parse(cookieEditorJson)
        assertEquals(listOf("__Secure-1PSID", "LOGIN_INFO", "PREF"), cookies.map { it.name })
        val pref = cookies.first { it.name == "PREF" }
        assertEquals(0L, pref.expiresAt) // session cookie: no expirationDate
        assertTrue(pref.secure)
    }

    @Test
    fun `json export becomes a netscape jar yt-dlp can read back`() {
        val jar = Cookies.toNetscape(Cookies.parse(cookieEditorJson))
        assertTrue(jar.startsWith("# Netscape HTTP Cookie File"))
        val again = Cookies.parseNetscape(jar)
        assertEquals(3, again.size)
        val sid = again.first { it.name == "__Secure-1PSID" }
        assertEquals(".youtube.com", sid.domain)
        assertEquals(1823180335L, sid.expiresAt)
        assertEquals("g.a000abc", sid.value)
        // Fields in the order the format defines them.
        val line = jar.lines().first { "\tLOGIN_INFO\t" in it }
        assertEquals(listOf(".youtube.com", "TRUE", "/", "TRUE", "1824423721", "LOGIN_INFO", "AFmm:QUQ3"), line.split("\t"))
    }

    @Test
    fun `netscape input with HttpOnly prefixes and comments`() {
        val text = """
            # Netscape HTTP Cookie File
            # comment

            #HttpOnly_.youtube.com	TRUE	/	TRUE	1823180335	SID	value1
            .youtube.com	TRUE	/	FALSE	0	PREF	tz=UTC
            .google.com	TRUE	/	TRUE	1823180335	SAPISID	abc/def
        """.trimIndent()
        val cookies = Cookies.parse(text)
        assertEquals(listOf("SID", "PREF", "SAPISID"), cookies.map { it.name })
        assertEquals(".youtube.com", cookies[0].domain)
        assertFalse(cookies[1].secure)
    }

    @Test
    fun `summary tells a login from visitor noise`() {
        val loggedIn = Cookies.summarize(Cookies.parse(cookieEditorJson))
        assertTrue(loggedIn.loggedIn)
        assertEquals(3, loggedIn.count)
        assertEquals(1823180335L, loggedIn.loginExpiresAt) // the earlier of the two login expiries

        val anon = Cookies.summarize(Cookies.parse(
            """[{"domain": ".youtube.com", "name": "VISITOR_INFO1_LIVE", "path": "/", "value": "x"}]"""
        ))
        assertFalse(anon.loggedIn)
    }

    @Test
    fun `wrong pastes get a message not a crash`() {
        val cases = listOf(
            "" to "Nothing",
            "hello world" to "does not look like",
            "[not json" to "could not be read",
            """[{"domain": ".example.com", "name": "a", "value": "b"}]""" to "No youtube.com",
        )
        for ((input, expected) in cases) {
            try {
                Cookies.parse(input)
                throw AssertionError("expected InvalidExport for ${input.take(20)}")
            } catch (e: Cookies.InvalidExport) {
                assertTrue("$expected in ${e.message}", e.message!!.contains(expected))
            }
        }
    }
}
