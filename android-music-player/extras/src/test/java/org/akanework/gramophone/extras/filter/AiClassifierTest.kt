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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The network call is a thin shell; everything that can actually be wrong lives
 * in building the request and reading the reply, so that is what is tested.
 *
 * Small models are unreliable in predictable ways — fences, preamble, missing
 * entries, invented indices — and every one of those must degrade to "leave the
 * file visible" rather than to a wrong verdict.
 */
class AiClassifierTest {

    private fun candidate(name: String, folder: String = "/storage/emulated/0/Download") =
        AudioCandidate(
            id = 1,
            path = "$folder/$name",
            displayName = name,
            durationMs = 240_000,
            sizeBytes = 5_760_000,
        )

    private val items = listOf(
        candidate("some song.mp3"),
        candidate("AUD-20240102-WA0007.opus"),
        candidate("track03.m4a"),
    )

    // ---------------------------------------------------------------
    // Request
    // ---------------------------------------------------------------

    @Test
    fun `request body is valid json with the model and both messages`() {
        val body = AiClassifier.buildRequestBody("some/model", items)
        val json = JSONObject(body)
        assertEquals("some/model", json.getString("model"))
        assertEquals(0, json.getInt("temperature"))
        val messages = json.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun `request carries the index, name and folder of every item`() {
        val body = AiClassifier.buildRequestBody("m", items)
        val user = JSONObject(body).getJSONArray("messages")
            .getJSONObject(1).getString("content")
        for ((index, item) in items.withIndex()) {
            assertTrue("missing i=$index", user.contains("i=$index"))
            assertTrue("missing ${item.displayName}", user.contains(item.displayName))
        }
        assertTrue(user.contains("/storage/emulated/0/Download"))
    }

    @Test
    fun `request contains only metadata, never file contents`() {
        // A regression guard on the privacy claim in the README: the body is
        // built purely from fields a file listing already shows.
        val body = AiClassifier.buildRequestBody("m", items)
        assertFalse(body.contains("base64"))
        assertFalse(body.contains("audio/"))
        assertFalse(body.contains("data:"))
    }

    @Test
    fun `optional tags are omitted when absent and included when present`() {
        val bare = AiClassifier.buildRequestBody("m", listOf(candidate("x.mp3")))
        assertFalse(bare.contains("artist="))

        val tagged = AiClassifier.buildRequestBody(
            "m",
            listOf(candidate("x.mp3").copy(artist = "Daft Punk", album = "RAM")),
        )
        assertTrue(tagged.contains("artist=Daft Punk"))
        assertTrue(tagged.contains("album=RAM"))
    }

    // ---------------------------------------------------------------
    // Cost cap
    // ---------------------------------------------------------------

    @Test
    fun `a small batch is sent whole`() {
        val small = List(10) { candidate("f$it.mp3") }
        val (toAsk, deferred) = AiClassifier.capBatch(small)
        assertEquals(10, toAsk.size)
        assertEquals(0, deferred)
    }

    @Test
    fun `an oversized batch is capped and the rest deferred`() {
        // A first run on a big untagged library must not quietly push
        // thousands of items through a paid API.
        val huge = List(AiClassifier.MAX_PER_SCAN + 250) { candidate("f$it.mp3") }
        val (toAsk, deferred) = AiClassifier.capBatch(huge)
        assertEquals(AiClassifier.MAX_PER_SCAN, toAsk.size)
        assertEquals(250, deferred)
        // Deferring must be lossless: nothing is dropped, it is just later.
        assertEquals(huge.size, toAsk.size + deferred)
        assertEquals(huge.take(AiClassifier.MAX_PER_SCAN), toAsk)
    }

    @Test
    fun `an exactly full batch is not deferred`() {
        val exact = List(AiClassifier.MAX_PER_SCAN) { candidate("f$it.mp3") }
        assertEquals(0, AiClassifier.capBatch(exact).second)
    }

    @Test
    fun `an empty batch is handled`() {
        val (toAsk, deferred) = AiClassifier.capBatch(emptyList())
        assertTrue(toAsk.isEmpty())
        assertEquals(0, deferred)
    }

    // ---------------------------------------------------------------
    // Response
    // ---------------------------------------------------------------

    @Test
    fun `content is pulled out of a chat completion`() {
        val raw = """{"choices":[{"message":{"role":"assistant","content":"hello"}}]}"""
        assertEquals("hello", AiClassifier.extractContent(raw))
    }

    @Test
    fun `malformed completions yield null rather than throwing`() {
        assertNull(AiClassifier.extractContent("not json at all"))
        assertNull(AiClassifier.extractContent("""{"error":{"message":"no credits"}}"""))
        assertNull(AiClassifier.extractContent(""))
    }

    @Test
    fun `clean verdicts parse`() {
        val content = """{"verdicts":[
            {"i":0,"v":"music","why":"looks like a song"},
            {"i":1,"v":"junk","why":"whatsapp voice note"}
        ]}"""
        val result = AiClassifier.parseVerdicts(content, 3)
        assertEquals(2, result.size)
        assertEquals(Judgement.MUSIC, result[0]!!.judgement)
        assertEquals(Judgement.JUNK, result[1]!!.judgement)
        assertEquals("whatsapp voice note", result[1]!!.reason)
        assertEquals(Verdict.Source.AI, result[0]!!.source)
    }

    @Test
    fun `verdicts wrapped in a markdown fence parse`() {
        val content = "```json\n{\"verdicts\":[{\"i\":0,\"v\":\"junk\",\"why\":\"ringtone\"}]}\n```"
        val result = AiClassifier.parseVerdicts(content, 1)
        assertEquals(Judgement.JUNK, result[0]!!.judgement)
    }

    @Test
    fun `verdicts preceded by chatter parse`() {
        val content = """Sure! Here are the classifications:
            {"verdicts":[{"i":0,"v":"music","why":"song"}]}"""
        assertEquals(Judgement.MUSIC, AiClassifier.parseVerdicts(content, 1)[0]!!.judgement)
    }

    @Test
    fun `garbage yields no verdicts instead of throwing`() {
        // Every one of these must leave files visible, not hide them.
        for (junk in listOf("", "no json here", "{", "}", "{{{", "[1,2,3]", "{\"nope\":1}")) {
            assertTrue("should be empty for: $junk", AiClassifier.parseVerdicts(junk, 3).isEmpty())
        }
    }

    @Test
    fun `out of range indices are dropped`() {
        val content = """{"verdicts":[
            {"i":-1,"v":"junk"},{"i":99,"v":"junk"},{"i":0,"v":"junk"}
        ]}"""
        val result = AiClassifier.parseVerdicts(content, 3)
        assertEquals(setOf(0), result.keys)
    }

    @Test
    fun `unknown verdict values are dropped`() {
        val content = """{"verdicts":[
            {"i":0,"v":"maybe"},{"i":1,"v":""},{"i":2,"v":"MUSIC"}
        ]}"""
        val result = AiClassifier.parseVerdicts(content, 3)
        // Only index 2 is usable, and casing must not matter.
        assertEquals(setOf(2), result.keys)
        assertEquals(Judgement.MUSIC, result[2]!!.judgement)
    }

    @Test
    fun `a missing reason still produces a usable verdict`() {
        val result = AiClassifier.parseVerdicts("""{"verdicts":[{"i":0,"v":"junk"}]}""", 1)
        assertTrue(result[0]!!.reason.isNotBlank())
    }

    @Test
    fun `a partial answer only decides the entries it covers`() {
        // The model answered for one of three; the other two stay visible.
        val result = AiClassifier.parseVerdicts("""{"verdicts":[{"i":1,"v":"junk"}]}""", 3)
        assertEquals(setOf(1), result.keys)
    }
}
