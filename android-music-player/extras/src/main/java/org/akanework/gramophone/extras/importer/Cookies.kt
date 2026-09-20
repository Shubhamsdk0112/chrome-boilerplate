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

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * The user's YouTube cookies, for the days YouTube decides a whole network is
 * a bot.
 *
 * "Sign in to confirm you're not a bot" is YouTube flagging an IP address —
 * shared home broadband and every mobile carrier get it regularly — and it
 * blocks a signed-out yt-dlp completely. A logged-in session walks straight
 * past it. The user exports the cookies from a browser (a Cookie-Editor JSON
 * export, or a Netscape `cookies.txt`), pastes or picks the file here, and
 * every yt-dlp call from then on carries them via `--cookies`.
 *
 * The jar lives in app-private storage and is never logged. yt-dlp rewrites
 * it after each run (YouTube rotates a few values), which is why it must be
 * a real writable file rather than a resource.
 *
 * Whoever's account this is gets tied to every download, so the settings
 * text tells the user to use a spare Google account, not their main one.
 */
object Cookies {

    private const val FILE_NAME = "extras_yt_cookies.txt"

    /** What the jar contains, for the settings dialog. */
    data class Summary(
        val count: Int,
        /** A Google login cookie is present, not just visitor/consent noise. */
        val loggedIn: Boolean,
        /** Earliest expiry among the login cookies, epoch seconds; 0 = session-only. */
        val loginExpiresAt: Long,
    )

    /** One parsed Netscape line. */
    data class Cookie(
        val domain: String,
        val path: String,
        val secure: Boolean,
        val expiresAt: Long,
        val name: String,
        val value: String,
    )

    class InvalidExport(message: String) : IllegalArgumentException(message)

    fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    fun isSet(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0 }

    /** Absolute path for `--cookies`, or null when no jar is saved. */
    fun path(context: Context): String? = file(context).takeIf { it.isFile && it.length() > 0 }?.absolutePath

    fun summary(context: Context): Summary? {
        val f = file(context)
        if (!f.isFile || f.length() == 0L) return null
        return runCatching { summarize(parseNetscape(f.readText())) }.getOrNull()
    }

    /**
     * Saves [text] — a Cookie-Editor JSON export or a Netscape cookies.txt —
     * as the jar. Throws [InvalidExport] with a message fit for a dialog.
     */
    fun import(context: Context, text: String): Summary {
        val cookies = parse(text)
        val summary = summarize(cookies)
        file(context).writeText(toNetscape(cookies))
        return summary
    }

    fun clear(context: Context) {
        file(context).delete()
    }

    // ------------------------------------------------------------------
    // Pure parsing, unit-tested
    // ------------------------------------------------------------------

    /** Accepts either export format and keeps only cookies YouTube will read. */
    fun parse(text: String): List<Cookie> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw InvalidExport("Nothing was pasted.")
        val all = if (trimmed.startsWith("[")) parseJson(trimmed) else parseNetscape(trimmed)
        val relevant = all.filter { it.domain.trimStart('.').let { d -> d == "youtube.com" || d.endsWith(".youtube.com") || d == "google.com" || d.endsWith(".google.com") } }
        if (relevant.isEmpty()) {
            throw InvalidExport(
                if (all.isEmpty()) "That does not look like a cookie export. Use the Cookie-Editor extension's JSON export, or a Netscape cookies.txt."
                else "No youtube.com cookies in that export. Export while on youtube.com."
            )
        }
        return relevant
    }

    /** Cookie-Editor / EditThisCookie style: an array of objects. */
    fun parseJson(text: String): List<Cookie> {
        val array = try {
            JSONArray(text)
        } catch (e: Exception) {
            throw InvalidExport("That JSON could not be read: ${e.message}")
        }
        val out = ArrayList<Cookie>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isBlank()) continue
            val domain = o.optString("domain")
            if (domain.isBlank()) continue
            // Cookie-Editor writes seconds as a double; session cookies have none.
            val expires = if (o.has("expirationDate")) o.optDouble("expirationDate", 0.0).toLong() else 0L
            out += Cookie(
                domain = domain,
                path = o.optString("path").ifBlank { "/" },
                secure = o.optBoolean("secure", false),
                expiresAt = expires,
                name = name,
                value = o.optString("value"),
            )
        }
        return out
    }

    /** The Netscape format yt-dlp reads; `#HttpOnly_` prefixes are accepted. */
    fun parseNetscape(text: String): List<Cookie> {
        val out = ArrayList<Cookie>()
        for (raw in text.lineSequence()) {
            var line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#HttpOnly_")) line = line.removePrefix("#HttpOnly_")
            else if (line.startsWith("#")) continue
            val f = line.split('\t')
            if (f.size < 7) continue
            out += Cookie(
                domain = f[0],
                path = f[2].ifBlank { "/" },
                secure = f[3].equals("TRUE", ignoreCase = true),
                expiresAt = f[4].toLongOrNull() ?: 0L,
                name = f[5],
                value = f.subList(6, f.size).joinToString("\t"),
            )
        }
        return out
    }

    fun toNetscape(cookies: List<Cookie>): String = buildString {
        append("# Netscape HTTP Cookie File\n")
        append("# Written by Gramophone extras from a browser export. Do not share.\n\n")
        for (c in cookies.sortedWith(compareBy({ it.domain }, { it.name }))) {
            append(c.domain).append('\t')
            append(if (c.domain.startsWith(".")) "TRUE" else "FALSE").append('\t')
            append(c.path).append('\t')
            append(if (c.secure) "TRUE" else "FALSE").append('\t')
            append(c.expiresAt).append('\t')
            append(c.name).append('\t')
            append(c.value).append('\n')
        }
    }

    fun summarize(cookies: List<Cookie>): Summary {
        val login = cookies.filter { it.name in LOGIN_COOKIES }
        return Summary(
            count = cookies.size,
            loggedIn = login.isNotEmpty(),
            loginExpiresAt = login.map { it.expiresAt }.filter { it > 0 }.minOrNull() ?: 0L,
        )
    }

    /** Google's session cookies; any one of them means a signed-in export. */
    private val LOGIN_COOKIES = setOf("SID", "__Secure-1PSID", "__Secure-3PSID", "LOGIN_INFO", "SAPISID")
}
