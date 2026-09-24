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

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Everything the extras remember, in one JSON file: followed shows and
 * their episodes, where you are in each, listening history, imported
 * playlists, and the settings (filter, pacing, podcast speed, the
 * already-imported index). Songs are real files in Music/ and survive
 * on their own; downloaded episode audio is not included (too big).
 * YouTube cookies are deliberately left out.
 *
 * A copy is written to Download/Gramophone/ once a day, so an uninstall
 * no longer loses the lot: reinstall, Restore, pick that file.
 */
object Backup {

    private const val TAG = "Backup"
    private const val FORMAT = 1
    private const val APP = "gramophone-extras"
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    const val AUTO_FILE_NAME = "extras-backup.json"

    /** Private files that make up the state. */
    private val FILES = listOf("extras_podcasts.json", "extras_history.json", "extras_playlists.json")

    /** SharedPreferences files owned by the extras. */
    private val PREFS = listOf("extras_filter", "extras_importer", "extras_podcasts", "extras_imports", "extras_app")

    /** Keys the extras own in Gramophone's default preferences. */
    private val DEFAULT_PREF_KEYS = listOf("extras_resume_after_interruption")

    data class Summary(val shows: Int, val historyEntries: Int, val createdAt: Long, val version: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun export(context: Context): String = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("app", APP)
        root.put("version", ExtrasVersion.NAME)
        root.put("createdAt", System.currentTimeMillis())
        root.put("files", JSONObject().apply {
            FILES.forEach { name ->
                val f = File(app.filesDir, name)
                if (f.isFile) put(name, f.readText())
            }
        })
        root.put("prefs", JSONObject().apply {
            PREFS.forEach { name -> put(name, prefsToJson(app.getSharedPreferences(name, Context.MODE_PRIVATE).all)) }
            val defaults = androidx.preference.PreferenceManager.getDefaultSharedPreferences(app).all
                .filterKeys { it in DEFAULT_PREF_KEYS }
            put("default", prefsToJson(defaults))
        })
        root.toString()
    }

    /** What a backup holds, without restoring it. Throws with a readable message if it is not one. */
    fun inspect(text: String): Summary {
        val root = parse(text)
        val files = root.optJSONObject("files") ?: JSONObject()
        val shows = files.optString("extras_podcasts.json").takeIf { it.isNotEmpty() }
            ?.let { runCatching { JSONObject(it).optJSONArray("podcasts")?.length() ?: 0 }.getOrDefault(0) } ?: 0
        val history = files.optString("extras_history.json").takeIf { it.isNotEmpty() }
            ?.let { runCatching { countHistory(it) }.getOrDefault(0) } ?: 0
        return Summary(shows, history, root.optLong("createdAt"), root.optString("version"))
    }

    /**
     * Writes the backup over the current state. The in-memory stores still
     * hold the old state, so the caller restarts the app right after.
     */
    suspend fun restore(context: Context, text: String): Summary = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val root = parse(text)
        val summary = inspect(text)
        val files = root.optJSONObject("files") ?: JSONObject()
        FILES.forEach { name ->
            val content = files.optString(name).takeIf { files.has(name) } ?: return@forEach
            val target = File(app.filesDir, name)
            val tmp = File(app.filesDir, "$name.restore")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        }
        val prefs = root.optJSONObject("prefs") ?: JSONObject()
        PREFS.forEach { name ->
            val saved = prefs.optJSONObject(name) ?: return@forEach
            val editor = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            jsonToPrefs(saved, editor)
            editor.commit()
        }
        prefs.optJSONObject("default")?.let { saved ->
            val editor = androidx.preference.PreferenceManager.getDefaultSharedPreferences(app).edit()
            jsonToPrefs(saved, editor)
            editor.commit()
        }
        summary
    }

    /** Relaunches the app so every store reads the restored files. */
    fun restartApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }

    // ------------------------------------------------------------------
    // Daily copy in Download/Gramophone

    fun autoBackupEnabled(context: Context) = appPrefs(context).getBoolean("auto_backup", true)
    fun setAutoBackup(context: Context, on: Boolean) = appPrefs(context).edit().putBoolean("auto_backup", on).apply()
    fun lastAutoBackup(context: Context) = appPrefs(context).getLong("last_auto_backup", 0L)

    fun autoBackupInBackground(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val p = appPrefs(app)
                if (!p.getBoolean("auto_backup", true)) return@launch
                if (System.currentTimeMillis() - p.getLong("last_auto_backup", 0L) < DAY_MS) return@launch
                // Nothing worth keeping yet: do not litter Download/ on a fresh install.
                if (FILES.none { File(app.filesDir, it).let { f -> f.isFile && f.length() > 2 } }) return@launch
                writeAutoBackup(app, export(app))
            }.onFailure { Log.w(TAG, "automatic backup failed", it) }
        }
    }

    private fun writeAutoBackup(app: Context, text: String) {
        val p = appPrefs(app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = app.contentResolver
            val existing = p.getString("auto_backup_uri", null)?.let(Uri::parse)
            val written = existing?.let { uri ->
                runCatching { resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null }.getOrDefault(false)
            } ?: false
            if (!written) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, AUTO_FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Gramophone")
                }
                val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: return
                resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: return
                p.edit().putString("auto_backup_uri", uri.toString()).apply()
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Gramophone")
            dir.mkdirs()
            File(dir, AUTO_FILE_NAME).writeText(text)
        }
        p.edit().putLong("last_auto_backup", System.currentTimeMillis()).apply()
        Log.i(TAG, "automatic backup written")
    }

    // ------------------------------------------------------------------
    // Pure helpers, unit-tested

    fun parse(text: String): JSONObject {
        val root = runCatching { JSONObject(text.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("That file is not a backup from this app.")
        if (root.optString("app") != APP) throw IllegalArgumentException("That file is not a backup from this app.")
        if (root.optInt("format") > FORMAT) throw IllegalArgumentException("That backup is from a newer version. Update the app first.")
        return root
    }

    private fun countHistory(text: String): Int {
        val trimmed = text.trim()
        return if (trimmed.startsWith("[")) JSONArray(trimmed).length()
        else JSONObject(trimmed).let { o -> o.optJSONArray("entries")?.length() ?: o.length() }
    }

    /** Typed, so a restored Int is still an Int (SharedPreferences cares). */
    fun prefsToJson(map: Map<String, *>): JSONObject = JSONObject().apply {
        map.forEach { (key, value) ->
            val typed = when (value) {
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is String -> JSONObject().put("t", "s").put("v", value)
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                else -> null
            }
            if (typed != null) put(key, typed)
        }
    }

    fun jsonToPrefs(json: JSONObject, editor: SharedPreferences.Editor) {
        json.keys().forEach { key ->
            val typed = json.optJSONObject(key) ?: return@forEach
            when (typed.optString("t")) {
                "b" -> editor.putBoolean(key, typed.optBoolean("v"))
                "i" -> editor.putInt(key, typed.optInt("v"))
                "l" -> editor.putLong(key, typed.optLong("v"))
                "f" -> editor.putFloat(key, typed.optDouble("v").toFloat())
                "s" -> editor.putString(key, typed.optString("v"))
                "ss" -> {
                    val arr = typed.optJSONArray("v") ?: JSONArray()
                    editor.putStringSet(key, (0 until arr.length()).map { arr.optString(it) }.toSet())
                }
            }
        }
    }

    private fun appPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("extras_app", Context.MODE_PRIVATE)
}
