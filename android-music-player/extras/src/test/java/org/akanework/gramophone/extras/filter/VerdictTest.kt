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
import org.junit.Assert.assertNull
import org.junit.Test

/** Cache serialisation and the precedence rule that keeps the filter safe. */
class VerdictTest {

    // ---------------------------------------------------------------
    // encode / decode
    // ---------------------------------------------------------------

    @Test
    fun `a verdict survives a round trip`() {
        for (verdict in listOf(
            Verdict(Judgement.MUSIC, "has artist and album tags", Verdict.Source.HEURISTIC),
            Verdict(Judgement.JUNK, "whatsapp voice note", Verdict.Source.AI),
            Verdict(Judgement.UNSURE, "", Verdict.Source.MANUAL),
        )) {
            assertEquals(verdict, Verdict.decode(verdict.encode()))
        }
    }

    @Test
    fun `a reason containing the separator does not corrupt the record`() {
        val verdict = Verdict(Judgement.JUNK, "a\u001fb\u001fc", Verdict.Source.AI)
        val decoded = Verdict.decode(verdict.encode())!!
        assertEquals(Judgement.JUNK, decoded.judgement)
        assertEquals(Verdict.Source.AI, decoded.source)
        assertEquals("a b c", decoded.reason)
    }

    @Test
    fun `unreadable cache entries decode to null rather than throwing`() {
        // Anything unreadable must simply cause the file to be classified
        // again, never crash a scan.
        for (raw in listOf(null, "", "NONSENSE", "\u001f\u001f", "MUSIC\u001fok\u001fWAT")) {
            val decoded = Verdict.decode(raw)
            if (raw == "MUSIC\u001fok\u001fWAT") {
                // A known judgement with an unknown source still decodes,
                // falling back to HEURISTIC.
                assertEquals(Verdict.Source.HEURISTIC, decoded!!.source)
            } else {
                assertNull("should be null for: $raw", decoded)
            }
        }
    }

    @Test
    fun `a record written without a source still decodes`() {
        val decoded = Verdict.decode("JUNK\u001fsome reason")!!
        assertEquals(Judgement.JUNK, decoded.judgement)
        assertEquals(Verdict.Source.HEURISTIC, decoded.source)
    }

    // ---------------------------------------------------------------
    // precedence
    // ---------------------------------------------------------------

    @Test
    fun `a manual choice beats everything`() {
        var derived = false
        val result = resolveVerdict(
            manual = Judgement.MUSIC,
            cached = Verdict(Judgement.JUNK, "cached"),
        ) { derived = true; Verdict(Judgement.JUNK, "derived") }

        assertEquals(Judgement.MUSIC, result.judgement)
        assertEquals(Verdict.Source.MANUAL, result.source)
        assertEquals("the derive step must not even run", false, derived)
    }

    @Test
    fun `a cached verdict beats re-deriving`() {
        var derived = false
        val result = resolveVerdict(
            manual = null,
            cached = Verdict(Judgement.JUNK, "cached", Verdict.Source.AI),
        ) { derived = true; Verdict(Judgement.MUSIC, "derived") }

        assertEquals("cached", result.reason)
        assertEquals(Verdict.Source.AI, result.source)
        // This is what stops the AI being asked about the same file twice.
        assertEquals(false, derived)
    }

    @Test
    fun `with nothing stored the file is classified fresh`() {
        val result = resolveVerdict(manual = null, cached = null) {
            Verdict(Judgement.MUSIC, "derived")
        }
        assertEquals("derived", result.reason)
        assertEquals(Verdict.Source.HEURISTIC, result.source)
    }
}
