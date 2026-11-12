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

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.extras.R
import org.akanework.gramophone.extras.app.ui.AppActivity
import java.net.HttpURLConnection
import java.net.URI

/**
 * Tells the user when a newer build is on GitHub Releases.
 *
 * Unauthenticated GitHub API (60 requests an hour per IP; this makes one a
 * day). Pre-releases count — every build so far is one. The notification
 * opens the Updates screen, which links the APK for this phone's ABI.
 * Nothing is downloaded or installed on its own.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val PREFS = "extras_app"
    private const val CHANNEL = "extras_updates"
    private const val NOTIFICATION_ID = 7301
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun autoCheckEnabled(context: Context): Boolean =
        prefs(context).getBoolean("auto_check", true)

    fun setAutoCheck(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean("auto_check", on).apply()

    /** Once a day at most, from Application start; posts a notification when newer. */
    fun checkInBackground(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val p = prefs(app)
                if (!p.getBoolean("auto_check", true)) return@launch
                val now = System.currentTimeMillis()
                if (now - p.getLong("last_check", 0L) < DAY_MS) return@launch
                p.edit().putLong("last_check", now).apply()
                val latest = latest() ?: return@launch
                if (!Releases.isNewer(latest.version, ExtrasVersion.NAME)) return@launch
                if (p.getString("notified_version", null) == latest.version) return@launch
                if (notify(app, latest)) p.edit().putString("notified_version", latest.version).apply()
            }.onFailure { Log.d(TAG, "update check failed: ${it.message}") }
        }
    }

    /** The newest extras release on GitHub, or null when offline / none. */
    suspend fun latest(): Releases.Release? = withContext(Dispatchers.IO) {
        val json = get("https://api.github.com/repos/${ExtrasVersion.REPO}/releases?per_page=30") ?: return@withContext null
        Releases.parse(json, primaryAbi()).maxWithOrNull { a, b -> Releases.compareVersions(a.version, b.version) }
    }

    // ------------------------------------------------------------------

    private fun primaryAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun get(url: String): String? = runCatching {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "gramophone-extras/${ExtrasVersion.NAME}")
        try {
            if (c.responseCode !in 200..299) null else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun notify(context: Context, release: Releases.Release): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.app_updates_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, AppActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_ytdlp_download)
            .setContentTitle(context.getString(R.string.app_update_available, release.version))
            .setContentText(context.getString(R.string.app_update_tap))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return true
    }
}
