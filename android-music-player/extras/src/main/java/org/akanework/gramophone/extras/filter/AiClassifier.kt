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

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI

/**
 * Stage two: ask a small language model about the files the heuristics could
 * not place.
 *
 * Three things keep this cheap and safe:
 *
 *  - **It only sees leftovers.** [JunkHeuristics] settles the overwhelming
 *    majority of a real library for free; only [Judgement.UNSURE] items get
 *    here, so a 5,000-file library typically costs a handful of requests.
 *  - **It only sees metadata.** Filename, folder, duration, bitrate and tags.
 *    No audio ever leaves the device. There is nothing in the request that is
 *    not already visible in a file listing.
 *  - **Verdicts are cached.** A file is never classified twice.
 *
 * Failure is always safe: if the key is missing, the network is down, or the
 * response is unparseable, the items simply stay UNSURE and remain visible.
 * The filter never hides something because a request failed.
 */
class AiClassifier(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val endpoint: String = OPENROUTER_ENDPOINT,
) {

    /**
     * Classifies a batch. Returns verdicts by index; indices the model did not
     * answer for are simply absent, and their files stay visible.
     */
    suspend fun classify(items: List<AudioCandidate>): Map<Int, Verdict> {
        if (apiKey.isBlank() || items.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            try {
                val body = buildRequestBody(model, items)
                val raw = post(body)
                val content = extractContent(raw) ?: return@withContext emptyMap()
                parseVerdicts(content, items.size)
            } catch (e: Exception) {
                Log.w(TAG, "AI classification failed; leaving items visible", e)
                emptyMap()
            }
        }
    }

    private fun post(body: String): String {
        val connection = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            // OpenRouter uses these for its own attribution listings.
            setRequestProperty("X-Title", "Gramophone Library Filter")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("OpenRouter returned $code: ${text.take(300)}")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "AiClassifier"
        private const val TIMEOUT_MS = 45_000

        const val OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

        /**
         * Cheap and more than capable enough to tell a song filename from a
         * voice note. Editable in settings — any OpenRouter model id works.
         */
        const val DEFAULT_MODEL = "google/gemini-2.5-flash-lite"

        /** Items per request. Large enough to be cheap, small enough to stay reliable. */
        const val BATCH_SIZE = 40

        /**
         * Ceiling on how many items one scan will send.
         *
         * A first run against a large, badly tagged library could otherwise
         * push thousands of items through the API in one go and hand the user
         * a bill they never agreed to. Because verdicts are cached, stopping
         * early is not lossy: each subsequent scan picks up where this one left
         * off, and the library converges over a few runs.
         */
        const val MAX_PER_SCAN = 600

        /**
         * The slice of [unsure] that this scan will actually send, and how many
         * were held back for next time.
         */
        fun capBatch(unsure: List<AudioCandidate>): Pair<List<AudioCandidate>, Int> =
            if (unsure.size <= MAX_PER_SCAN) {
                unsure to 0
            } else {
                unsure.take(MAX_PER_SCAN) to (unsure.size - MAX_PER_SCAN)
            }

        private val SYSTEM_PROMPT = """
            You sort audio files into MUSIC or JUNK for a phone music player.

            MUSIC: songs, instrumentals, albums, live sets, remixes, DJ mixes.
            JUNK: voice notes, call and voice recordings, ringtones, alarms,
            notification sounds, app UI sounds, video files, sound effects,
            lectures, and messaging-app audio.

            You are given only filenames, folders and tags. Judge from those.
            When genuinely torn, answer music: wrongly hiding someone's song is
            much worse than leaving one stray file visible.

            Reply with JSON only, no prose and no code fences:
            {"verdicts":[{"i":0,"v":"music","why":"short reason"}]}
            Include exactly one entry for every item, using its given index.
        """.trimIndent()

        /**
         * Builds the request body. Pure and deterministic so it can be tested
         * without a network.
         */
        internal fun buildRequestBody(model: String, items: List<AudioCandidate>): String {
            val lines = items.mapIndexed { index, c ->
                val parts = buildList {
                    add("i=$index")
                    add("name=${c.displayName}")
                    add("folder=${c.path.substringBeforeLast('/', "")}")
                    if (c.durationMs > 0) add("seconds=${c.durationMs / 1000}")
                    c.approximateBitrateKbps?.let { add("kbps=$it") }
                    c.artist?.takeIf { it.isNotBlank() }?.let { add("artist=$it") }
                    c.album?.takeIf { it.isNotBlank() }?.let { add("album=$it") }
                    c.title?.takeIf { it.isNotBlank() }?.let { add("title=$it") }
                }
                parts.joinToString(" | ")
            }

            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                .put(
                    JSONObject().put("role", "user")
                        .put("content", "Classify these ${items.size} files:\n" + lines.joinToString("\n"))
                )

            return JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0)
                .toString()
        }

        /** Pulls the assistant text out of an OpenRouter chat completion. */
        internal fun extractContent(raw: String): String? = try {
            JSONObject(raw)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }

        /**
         * Parses the model's verdicts.
         *
         * Small models wrap JSON in code fences or add a sentence in front more
         * often than they should, so this reads the outermost JSON object
         * rather than trusting the response to be clean. Anything malformed or
         * out of range is dropped, which leaves that file visible.
         */
        internal fun parseVerdicts(content: String, itemCount: Int): Map<Int, Verdict> {
            val unfenced = content.substringAfter("```json", content).substringBeforeLast("```")
            val start = unfenced.indexOf('{')
            val end = unfenced.lastIndexOf('}')
            if (start < 0 || end <= start) return emptyMap()
            val json = unfenced.substring(start, end + 1)

            val verdicts = try {
                JSONObject(json).optJSONArray("verdicts")
            } catch (e: Exception) {
                null
            } ?: return emptyMap()

            val result = mutableMapOf<Int, Verdict>()
            for (n in 0 until verdicts.length()) {
                val entry = verdicts.optJSONObject(n) ?: continue
                val index = entry.optInt("i", -1)
                if (index !in 0 until itemCount) continue
                val judgement = when (entry.optString("v").lowercase().trim()) {
                    "music" -> Judgement.MUSIC
                    "junk" -> Judgement.JUNK
                    else -> continue
                }
                val why = entry.optString("why").takeIf { it.isNotBlank() } ?: "AI classified"
                result[index] = Verdict(judgement, why, Verdict.Source.AI)
            }
            return result
        }
    }
}
