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

package org.akanework.gramophone.extras.importer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.akanework.gramophone.extras.R

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

    /** Every job that was in flight during this run: the "of N" in "3 of 12". */
    private val batch = LinkedHashSet<String>()
    private var coverFor: String? = null
    private var cover: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        repository = DownloadRepository.get(this)
        createChannel()
        startForegroundCompat(buildNotification(getString(R.string.ytdlp_notification_title), getString(R.string.ytdlp_preparing), null, null, null))

        scope.launch {
            // The list is empty until the persisted queue has been read; acting
            // on that would stop the service the moment the system restarts it.
            repository.restored.first { it }
            repository.jobs.collectLatest { jobs ->
                val active = jobs.filterNot { it.stage.isTerminal }
                active.forEach { batch += it.id }
                if (active.isEmpty()) {
                    postSummary(jobs.filter { it.id in batch })
                    batch.clear()
                    stopSelf()
                    return@collectLatest
                }
                val current = active.first()
                val finished = jobs.count { it.id in batch && it.stage.isTerminal }
                val title = if (batch.size > 1) {
                    getString(R.string.ytdlp_notification_count, (finished + 1).coerceAtMost(batch.size), batch.size)
                } else getString(R.string.ytdlp_notification_title)
                val next = active.getOrNull(1)?.label
                notificationManager?.notify(
                    NOTIFICATION_ID,
                    buildNotification(
                        title = title,
                        text = describe(current),
                        next = next,
                        progress = (current.stage as? JobStage.Downloading)?.progress,
                        art = coverOf(current),
                    ),
                )
            }
        }
    }

    /** The card's poster as the notification's large icon, decoded once per job. */
    private fun coverOf(job: DownloadJob): Bitmap? {
        val file = job.thumbnail ?: return null
        if (coverFor != file.path) {
            coverFor = file.path
            cover = runCatching {
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 })
            }.getOrNull()
        }
        return cover
    }

    /** One quiet notification when the queue empties: what landed, what did not. */
    private fun postSummary(jobs: List<DownloadJob>) {
        if (jobs.isEmpty()) return
        val done = jobs.filter { it.stage is JobStage.Done }
        val failed = jobs.count { it.stage is JobStage.Failed }
        if (done.isEmpty() && failed == 0) return // all cancelled: the user knows
        val title = when {
            jobs.size == 1 && done.size == 1 -> {
                val stage = done[0].stage as JobStage.Done
                getString(
                    if (stage.podcast) R.string.ytdlp_notification_done_one_podcast else R.string.ytdlp_notification_done_one,
                    done[0].label,
                )
            }
            else -> getString(R.string.ytdlp_notification_done_many, done.size, jobs.size)
        }
        val text = if (failed > 0) getString(R.string.ytdlp_notification_done_failed, failed)
        else getString(R.string.ytdlp_notification_done_all)
        val notification = NotificationCompat.Builder(this, DONE_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(openDownloader())
            .setAutoCancel(true)
            .build()
        notificationManager?.notify(DONE_NOTIFICATION_ID, notification)
    }

    private fun openDownloader(): PendingIntent = PendingIntent.getActivity(
        this, 1,
        Intent(this, org.akanework.gramophone.extras.importer.ui.DownloaderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // Sticky: if the system kills the process under memory pressure, it
    // recreates the service, whose onCreate brings the repository back up,
    // and the repository re-queues whatever was interrupted.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            repository.jobs.value.filterNot { it.stage.isTerminal }.forEach { repository.cancel(it.id) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun describe(job: DownloadJob): String {
        val stage = when (val s = job.stage) {
            is JobStage.Reading -> getString(R.string.ytdlp_reading)
            is JobStage.Updating -> getString(R.string.ytdlp_updating)
            is JobStage.Retrying -> getString(R.string.ytdlp_retrying, s.inSeconds, s.attempt)
            is JobStage.Pacing -> getString(R.string.ytdlp_pacing, s.inSeconds)
            is JobStage.Downloading -> "${s.progress.toInt()}%"
            is JobStage.FindingArtwork -> getString(R.string.ytdlp_finding_artwork)
            is JobStage.Tagging -> getString(R.string.ytdlp_tagging)
            is JobStage.Importing -> getString(R.string.ytdlp_importing)
            else -> getString(R.string.ytdlp_preparing)
        }
        return "${job.label} — $stage"
    }

    private fun buildNotification(title: String, text: String, next: String?, progress: Float?, art: Bitmap?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (next != null) text + "\n" + getString(R.string.ytdlp_notification_next, next) else text,
                ),
            )
            .setLargeIcon(art)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openDownloader())
            .addAction(
                0, getString(R.string.ytdlp_notification_cancel_all),
                PendingIntent.getService(
                    this, 2,
                    Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
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
        notificationManager?.createNotificationChannel(
            NotificationChannel(
                DONE_CHANNEL_ID,
                getString(R.string.ytdlp_notification_done_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "ytdlp_import"
        private const val NOTIFICATION_ID = 0x7D19
        private const val DONE_CHANNEL_ID = "ytdlp_done"
        private const val DONE_NOTIFICATION_ID = 0x7D1A
        private const val ACTION_CANCEL_ALL = "org.akanework.gramophone.extras.CANCEL_ALL"

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
