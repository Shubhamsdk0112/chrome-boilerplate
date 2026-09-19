/*
 *     Copyright (C) 2026 Gramophone yt-dlp importer contributors
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

package org.akanework.gramophone.ytdlp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the import queue running while the user is somewhere else.
 *
 * A download plus an ffmpeg pass can take a couple of minutes on a slow
 * connection, and without a foreground service Android is entitled to kill the
 * process the moment the activity goes away — leaving a half-imported song.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: DownloadRepository

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repository = DownloadRepository.get(this)
        createChannel()
        startForegroundCompat(buildNotification(getString(R.string.ytdlp_preparing), null))

        scope.launch {
            repository.jobs.collectLatest { jobs ->
                val active = jobs.filterNot { it.stage.isTerminal }
                if (active.isEmpty()) {
                    stopSelf()
                    return@collectLatest
                }
                val current = active.first()
                val progress = (current.stage as? JobStage.Downloading)?.progress
                notificationManager?.notify(
                    NOTIFICATION_ID,
                    buildNotification(
                        text = describe(current, active.size),
                        progress = progress,
                    ),
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun describe(job: DownloadJob, queued: Int): String {
        val suffix = if (queued > 1) " (+${queued - 1} queued)" else ""
        val stage = when (val s = job.stage) {
            is JobStage.Reading -> getString(R.string.ytdlp_reading)
            is JobStage.Downloading -> "${s.progress.toInt()}%"
            is JobStage.FindingArtwork -> getString(R.string.ytdlp_finding_artwork)
            is JobStage.Tagging -> getString(R.string.ytdlp_tagging)
            is JobStage.Importing -> getString(R.string.ytdlp_importing)
            else -> getString(R.string.ytdlp_preparing)
        }
        return "${job.label} — $stage$suffix"
    }

    private fun buildNotification(text: String, progress: Float?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.ytdlp_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (progress != null) {
                    setProgress(100, progress.toInt(), false)
                } else {
                    setProgress(0, 0, true)
                }
            }
            .build()

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private val notificationManager: NotificationManager?
        get() = getSystemService()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ytdlp_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        notificationManager?.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "ytdlp_import"
        private const val NOTIFICATION_ID = 0x7D19

        /** Starts the service if the queue has work and it is not already up. */
        fun ensureRunning(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
