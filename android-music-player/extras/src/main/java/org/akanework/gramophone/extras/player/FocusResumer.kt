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

package org.akanework.gramophone.extras.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.Player
import androidx.preference.PreferenceManager

/**
 * Picks playback back up after another app is done with the speaker.
 *
 * Android hands a permanent focus loss to whoever was playing when a video
 * in a feed, a game or another player starts, and ExoPlayer then does the
 * correct thing: pause and let go of focus. The system never tells us when
 * the other app finishes — we are out of the focus stack by then — so a
 * paused song stays paused until the user digs the app out again. That is
 * the "song stops and never comes back" complaint.
 *
 * This watches the system's list of active playbacks while we are paused for
 * that reason. Once nothing else has been playing for [QUIET_MS] the song
 * resumes. Three guards keep it from being annoying:
 *
 *  - Only a pause *caused by focus loss* is undone. A pause the user made,
 *    or the end of the queue, is left alone — the first non-focus change to
 *    playWhenReady cancels the watch.
 *  - The quiet period absorbs the gap between two short videos in a feed, so
 *    the music does not blip in and out while the user scrolls.
 *  - After [WINDOW_MS] the interruption is treated as the user having moved
 *    on. Music that comes back an hour later is a fright, not a feature.
 *
 * Transient losses (a phone call, a navigation prompt) are not our business:
 * ExoPlayer suppresses playback for those and resumes on its own.
 */
class FocusResumer(context: Context, private val player: Player) : Player.Listener {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)

    private var lostAt = 0L
    private var attempts = 0
    private var resuming = false
    private var callback: AudioManager.AudioPlaybackCallback? = null
    private val resume = Runnable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) resumeNow()
    }

    /** No-op below Android 8, which has no playback list to watch. */
    fun attach() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        player.addListener(this)
    }

    fun release() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        player.removeListener(this)
        stopWatching()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
            if (!prefs.getBoolean(PREF_KEY, true)) return
            // A resume that immediately loses focus again means someone else
            // still holds it in a way the playback list does not show. Give up
            // rather than fight.
            if (attempts >= MAX_ATTEMPTS) {
                Log.i(TAG, "lost focus again right after resuming, giving up")
                stopWatching()
                return
            }
            if (callback == null) lostAt = SystemClock.elapsedRealtime()
            startWatching()
        } else if (playWhenReady && resuming) {
            // Our own play() echoing back; not a reason to forget the count.
            resuming = false
            stopWatching()
        } else {
            // The user pressed play or pause, or the queue ended: whatever we
            // were waiting for no longer applies.
            attempts = 0
            stopWatching()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startWatching() {
        if (callback != null) return
        val created = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                evaluate(configs)
            }
        }
        try {
            audioManager.registerAudioPlaybackCallback(created, handler)
        } catch (e: Exception) {
            Log.w(TAG, "cannot watch playback", e)
            return
        }
        callback = created
        Log.i(TAG, "paused by another app, waiting for it to finish")
        evaluate(audioManager.activePlaybackConfigurations)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun stopWatching() {
        handler.removeCallbacks(resume)
        callback?.let { runCatching { audioManager.unregisterAudioPlaybackCallback(it) } }
        callback = null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun evaluate(configs: List<AudioPlaybackConfiguration>) {
        if (callback == null) return
        if (SystemClock.elapsedRealtime() - lostAt > WINDOW_MS) {
            Log.i(TAG, "interruption outlived the window, not resuming")
            stopWatching()
            return
        }
        handler.removeCallbacks(resume)
        // Our own player is paused, so anything active here is someone else.
        // Pings and alarms are transient and short; they only delay us.
        val othersPlaying = configs.any { it.audioAttributes.usage !in IGNORED_USAGES }
        if (!othersPlaying) handler.postDelayed(resume, QUIET_MS)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun resumeNow() {
        stopWatching()
        if (player.playWhenReady || player.mediaItemCount == 0) return
        if (player.playbackState == Player.STATE_IDLE) return
        attempts++
        resuming = true
        Log.i(TAG, "quiet again, resuming")
        player.play()
        // A successful resume that stays up for a while resets the counter.
        handler.postDelayed({ if (player.playWhenReady) attempts = 0 }, RESET_MS)
    }

    companion object {
        private const val TAG = "FocusResumer"

        /** Behaviour › "Resume after other apps finish playing". */
        const val PREF_KEY = "extras_resume_after_interruption"

        /** Silence needed before resuming; covers the gap between feed videos. */
        private const val QUIET_MS = 4_000L

        /** After this the interruption is treated as the user having moved on. */
        private const val WINDOW_MS = 20 * 60_000L

        private const val RESET_MS = 10_000L
        private const val MAX_ATTEMPTS = 2

        private val IGNORED_USAGES = setOf(
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
            AudioAttributes.USAGE_NOTIFICATION_EVENT,
            AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
        )
    }
}
