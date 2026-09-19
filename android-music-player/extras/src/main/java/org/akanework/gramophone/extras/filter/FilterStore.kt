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

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Persistence for the filter, split deliberately across two preference files.
 *
 * [KEY_JUNK_PATHS] lives in Gramophone's **default** preferences because that is
 * where its application class reads the library blacklist from, and writing
 * there is what makes the library refresh. Everything else — settings and the
 * verdict cache — lives in a private file, so re-running a scan does not fire
 * Gramophone's preference listener thousands of times.
 */
class FilterStore(context: Context) {

    private val appContext = context.applicationContext

    /** Gramophone's own preferences. Only [KEY_JUNK_PATHS] is written here. */
    private val shared: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(appContext)

    /** Our own settings and cache. */
    private val own: SharedPreferences =
        appContext.getSharedPreferences("extras_filter", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    var enabled: Boolean
        get() = own.getBoolean("enabled", false)
        set(value) = own.edit { putBoolean("enabled", value) }

    var aiEnabled: Boolean
        get() = own.getBoolean("ai_enabled", false)
        set(value) = own.edit { putBoolean("ai_enabled", value) }

    /**
     * The OpenRouter key.
     *
     * Supplied by the user at runtime and never compiled in. It stays in the
     * app's private preferences, which are not world-readable, and is sent only
     * to OpenRouter's completions endpoint.
     */
    var apiKey: String
        get() = own.getString("api_key", "").orEmpty()
        set(value) = own.edit { putString("api_key", value.trim()) }

    var model: String
        get() = own.getString("model", AiClassifier.DEFAULT_MODEL)
            .orEmpty()
            .ifBlank { AiClassifier.DEFAULT_MODEL }
        set(value) = own.edit { putString("model", value.trim()) }

    var options: FilterOptions
        get() = FilterOptions(
            hideVoiceNotes = own.getBoolean("hide_voice_notes", true),
            hideRingtonesAndAlarms = own.getBoolean("hide_ringtones", true),
            hideVideoFiles = own.getBoolean("hide_video", true),
            hidePodcastsAndAudiobooks = own.getBoolean("hide_podcasts", false),
            hideShortClips = own.getBoolean("hide_short", true),
            shortClipSeconds = own.getInt("short_seconds", 30),
        )
        set(value) = own.edit {
            putBoolean("hide_voice_notes", value.hideVoiceNotes)
            putBoolean("hide_ringtones", value.hideRingtonesAndAlarms)
            putBoolean("hide_video", value.hideVideoFiles)
            putBoolean("hide_podcasts", value.hidePodcastsAndAudiobooks)
            putBoolean("hide_short", value.hideShortClips)
            putInt("short_seconds", value.shortClipSeconds)
        }

    // ------------------------------------------------------------------
    // Verdict cache
    // ------------------------------------------------------------------

    /**
     * Cached verdicts, keyed by a fingerprint that changes when the file does,
     * so an edited or replaced file is judged again.
     *
     * Its main job is to make the AI pass a one-off cost: a file that has been
     * classified once is never sent anywhere again.
     */
    fun cachedVerdict(fingerprint: String): Verdict? {
        val raw = own.getString("v:$fingerprint", null) ?: return null
        val parts = raw.split('\u001f')
        val judgement = runCatching { Judgement.valueOf(parts[0]) }.getOrNull() ?: return null
        val source = parts.getOrNull(2)
            ?.let { runCatching { Verdict.Source.valueOf(it) }.getOrNull() }
            ?: Verdict.Source.HEURISTIC
        return Verdict(judgement, parts.getOrNull(1).orEmpty(), source)
    }

    fun putVerdicts(verdicts: Map<String, Verdict>) {
        if (verdicts.isEmpty()) return
        own.edit {
            for ((fingerprint, verdict) in verdicts) {
                putString(
                    "v:$fingerprint",
                    listOf(
                        verdict.judgement.name,
                        verdict.reason.replace('\u001f', ' '),
                        verdict.source.name,
                    ).joinToString("\u001f"),
                )
            }
        }
    }

    /** Forgets every cached verdict, so the next scan re-evaluates from scratch. */
    fun clearCache() {
        own.edit {
            own.all.keys.filter { it.startsWith("v:") }.forEach { remove(it) }
        }
    }

    // ------------------------------------------------------------------
    // Manual overrides — these always win over both stages
    // ------------------------------------------------------------------

    private fun overrideKey(path: String) = "o:$path"

    fun manualOverride(path: String): Judgement? =
        own.getString(overrideKey(path), null)
            ?.let { runCatching { Judgement.valueOf(it) }.getOrNull() }

    fun setManualOverride(path: String, judgement: Judgement?) {
        own.edit {
            if (judgement == null) remove(overrideKey(path))
            else putString(overrideKey(path), judgement.name)
        }
    }

    fun clearManualOverrides() {
        own.edit {
            own.all.keys.filter { it.startsWith("o:") }.forEach { remove(it) }
        }
    }

    // ------------------------------------------------------------------
    // The hidden set that Gramophone reads
    // ------------------------------------------------------------------

    var hiddenPaths: Set<String>
        get() = shared.getStringSet(KEY_JUNK_PATHS, emptySet()) ?: emptySet()
        set(value) {
            // A no-op write would still fire Gramophone's listener and trigger a
            // full library re-read, which is expensive on a large library.
            if (value == hiddenPaths) return
            shared.edit { putStringSet(KEY_JUNK_PATHS, value) }
        }

    /** Un-hides everything. The player's own folder blacklist is untouched. */
    fun unhideAll() {
        hiddenPaths = emptySet()
    }

    companion object {
        /**
         * Read by the patched `GramophoneApplication`, which unions it with the
         * user's folder blacklist before handing it to the library reader.
         */
        const val KEY_JUNK_PATHS = "junkFilterPaths"

        /** Cache key for a file; changes when the file's size or mtime changes. */
        fun fingerprint(path: String, sizeBytes: Long, dateModified: Long): String =
            "$path|$sizeBytes|$dateModified"
    }
}
