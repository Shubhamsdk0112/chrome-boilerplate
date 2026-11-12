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
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.Player
import androidx.preference.PreferenceManager

/**
 * What happens to the music when another app makes a sound — a reel, a
 * video, a voice note. Behaviour › "When another app plays sound":
 *
 *  - **Pause, then carry on** (default): Android's normal behaviour, plus
 *    picking the song back up once the other app has been quiet for a few
 *    seconds (within 20 minutes).
 *  - **Lower the volume**: keep playing underneath at the chosen level (10–80 %)
 *    and come back to full volume when the other app stops. To do that the
 *    player stops taking audio focus — holding focus is what makes Android
 *    pause it, and on Android 12+ fade it out if it keeps playing — and this
 *    class watches the system's list of playing apps instead.
 *  - **Pause**: plain Android behaviour, nothing comes back on its own.
 *
 * Calls and meetings always pause, whatever the setting: the phone's audio
 * mode says when a call or a VoIP meeting (Meet, Zoom, WhatsApp) is on, and
 * the song resumes when it ends if it was playing before.
 */
class FocusResumer(context: Context, private val player: Player) :
    Player.Listener, SharedPreferences.OnSharedPreferenceChangeListener {

    enum class Mode(val value: String) {
        PAUSE_RESUME("pause_resume"), DUCK("duck"), PAUSE("pause");

        companion object {
            fun from(value: String?) = entries.firstOrNull { it.value == value }
        }
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)

    private var mode = Mode.PAUSE_RESUME
    private var duckLevel = 0.3f

    // Pause-then-resume state
    private var lostAt = 0L
    private var attempts = 0
    private var resuming = false
    private val resume = Runnable { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) resumeNow() }

    // Duck state
    private var othersPlaying = false
    private var ducked = false
    private var fadeTarget = 1f
    // The player has one volume; the duck level and the song fades share it.
    private var duckGain = 1f
    private var fadeGain = 1f
    private fun applyVolume() {
        val v = (duckGain * fadeGain).coerceIn(0f, 1f)
        if (player.volume != v) player.volume = v
    }

    private val fadeStep = object : Runnable {
        override fun run() {
            val now = duckGain
            val next = if (now < fadeTarget) (now + 0.05f).coerceAtMost(fadeTarget)
            else (now - 0.08f).coerceAtLeast(fadeTarget)
            duckGain = next
            applyVolume()
            if (next != fadeTarget) handler.postDelayed(this, 40)
        }
    }

    // Fade between songs: the end of a song fades out, the next fades in.
    private var crossfadeMs = 0L
    private var fadingIn = false
    private val crossTick = object : Runnable {
        override fun run() {
            updateSongFade()
            if (crossfadeMs > 0 && player.isPlaying) handler.postDelayed(this, 50)
        }
    }
    private val applyDuck = Runnable { updateDuck() }

    // Calls
    private var pausedForCall = false
    private var callEndedAt = 0L
    private val pollMode = object : Runnable {
        override fun run() {
            checkCall()
            if (player.playWhenReady || pausedForCall) handler.postDelayed(this, 1_000)
        }
    }
    private var modeListener: Any? = null

    private var callback: AudioManager.AudioPlaybackCallback? = null

    /** No-op below Android 8, which has no playback list to watch. */
    fun attach() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        player.addListener(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        readPrefs()
        watchCalls()
    }

    fun release() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        player.removeListener(this)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        handler.removeCallbacksAndMessages(null)
        stopWatching()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (modeListener as? AudioManager.OnModeChangedListener)?.let { audioManager.removeOnModeChangedListener(it) }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == PREF_MODE || key == PREF_DUCK_PERCENT || key == PREF_LEGACY_RESUME || key == PREF_CROSSFADE) readPrefs()
    }

    private fun readPrefs() {
        val legacyResume = prefs.getBoolean(PREF_LEGACY_RESUME, true)
        mode = Mode.from(prefs.getString(PREF_MODE, null)) ?: if (legacyResume) Mode.PAUSE_RESUME else Mode.PAUSE
        duckLevel = (prefs.getInt(PREF_DUCK_PERCENT, 30).coerceIn(5, 90)) / 100f
        crossfadeMs = prefs.getInt(PREF_CROSSFADE, 0).coerceIn(0, 12) * 1000L
        handler.removeCallbacks(crossTick)
        if (crossfadeMs > 0 && player.isPlaying) handler.post(crossTick) else { fadeGain = 1f; applyVolume() }
        // Holding audio focus is what makes Android pause (and on 12+ fade
        // out) the player; in Lower-the-volume mode the player lets go of it.
        val wantsFocus = mode != Mode.DUCK
        runCatching { player.setAudioAttributes(player.audioAttributes, wantsFocus) }
            .onFailure { Log.w(TAG, "could not change focus handling", it) }
        if (mode == Mode.DUCK) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startWatching()
            if (ducked) fadeTo(duckLevel)
        } else {
            unduckNow()
            if (callback != null && lostAt == 0L) stopWatching()
        }
        Log.i(TAG, "mode $mode, duck ${(duckLevel * 100).toInt()}%")
    }

    // ------------------------------------------------------------------
    // Player events

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (playWhenReady) {
            handler.removeCallbacks(pollMode)
            handler.post(pollMode)
        }
        if (mode == Mode.DUCK) {
            if (!playWhenReady && !pausedForCall) unduckNow()
            else handler.post(applyDuck)
            return
        }
        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
            if (mode != Mode.PAUSE_RESUME) return
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
            resuming = false
            lostAt = 0L
            stopWatching()
        } else if (!pausedForCall) {
            attempts = 0
            lostAt = 0L
            stopWatching()
        }
    }

    // ------------------------------------------------------------------
    // Calls and meetings: always pause, resume after

    private fun watchCalls() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val l = AudioManager.OnModeChangedListener { checkCall() }
            audioManager.addOnModeChangedListener(appContext.mainExecutor, l)
            modeListener = l
        }
        // Older versions have no mode callback; poll while playing.
        handler.post(pollMode)
    }

    private fun inCall(): Boolean = when (audioManager.mode) {
        AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE -> true
        else -> audioManager.mode >= 4 // CALL_SCREENING, CALL_REDIRECT, COMMUNICATION_REDIRECT
    }

    private fun checkCall() {
        val call = inCall()
        if (call && player.playWhenReady && mode == Mode.DUCK) {
            // With focus off nothing else pauses us for a call; do it here.
            Log.i(TAG, "call or meeting started, pausing")
            pausedForCall = true
            player.pause()
            handler.removeCallbacks(pollMode)
            handler.post(pollMode)
        } else if (!call && pausedForCall) {
            if (callEndedAt == 0L) callEndedAt = SystemClock.elapsedRealtime()
            // A couple of seconds of grace: the in-call sound often lingers.
            if (SystemClock.elapsedRealtime() - callEndedAt > 2_000) {
                pausedForCall = false
                callEndedAt = 0L
                Log.i(TAG, "call ended, resuming")
                if (!player.playWhenReady && player.mediaItemCount > 0) player.play()
            }
        } else if (call) {
            callEndedAt = 0L
        }
    }

    // ------------------------------------------------------------------
    // Watching what else is playing

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
    }

    private fun stopWatching() {
        handler.removeCallbacks(resume)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            callback?.let { runCatching { audioManager.unregisterAudioPlaybackCallback(it) } }
        }
        callback = null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun evaluate(configs: List<AudioPlaybackConfiguration>) {
        if (callback == null) return
        // The callback lists players that are actually playing; ours is one
        // of them while it plays. Short sounds (notifications, key clicks)
        // are left out — they would only make the music flicker.
        val counted = configs.count { it.audioAttributes.usage !in IGNORED_USAGES }
        val mine = if (player.isPlaying || player.playWhenReady) 1 else 0
        val others = counted - mine > 0

        if (mode == Mode.DUCK) {
            othersPlaying = others
            handler.removeCallbacks(applyDuck)
            // Duck quickly, come back up only after a little quiet, so the
            // gap between two reels does not make the music jump.
            handler.postDelayed(applyDuck, if (others) 300 else 1_500)
            return
        }
        if (SystemClock.elapsedRealtime() - lostAt > WINDOW_MS) {
            Log.i(TAG, "interruption outlived the window, not resuming")
            stopWatching()
            return
        }
        handler.removeCallbacks(resume)
        if (!others) handler.postDelayed(resume, QUIET_MS)
    }

    private fun updateDuck() {
        if (mode != Mode.DUCK) return
        if (othersPlaying && player.playWhenReady) {
            if (!ducked) Log.i(TAG, "another app is playing, lowering to ${(duckLevel * 100).toInt()}%")
            ducked = true
            fadeTo(duckLevel)
        } else if (ducked) {
            Log.i(TAG, "quiet again, back to full volume")
            ducked = false
            fadeTo(1f)
        }
    }

    private fun fadeTo(target: Float) {
        fadeTarget = target
        handler.removeCallbacks(fadeStep)
        handler.post(fadeStep)
    }

    private fun unduckNow() {
        ducked = false
        handler.removeCallbacks(fadeStep)
        handler.removeCallbacks(applyDuck)
        duckGain = 1f
        applyVolume()
    }

    // ------------------------------------------------------------------
    // Fade between songs

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        handler.removeCallbacks(crossTick)
        if (isPlaying && crossfadeMs > 0) handler.post(crossTick)
    }

    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
        val isEpisode = mediaItem?.mediaId?.startsWith("podcast:") == true
        // Only a song that ran into the next one fades in; a skip starts at full volume.
        fadingIn = crossfadeMs > 0 && !isEpisode && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        updateSongFade()
    }

    private fun updateSongFade() {
        val fade = crossfadeMs
        val id = player.currentMediaItem?.mediaId
        if (fade <= 0 || id == null || id.startsWith("podcast:")) {
            fadeGain = 1f; applyVolume(); return
        }
        val pos = player.currentPosition
        val dur = player.duration
        // Fade out only into another song, and never inside a song too short to fade.
        val out = if (dur > fade * 3 && player.hasNextMediaItem() && player.repeatMode != Player.REPEAT_MODE_ONE) {
            ((dur - pos).toFloat() / fade).coerceIn(0f, 1f)
        } else 1f
        val inn = if (fadingIn) {
            if (pos >= fade) { fadingIn = false; 1f } else (pos.toFloat() / fade).coerceIn(0.05f, 1f)
        } else 1f
        // Ease the curve: a linear ramp sounds like it drops off a cliff at the end.
        val g = minOf(out, inn)
        fadeGain = g * g * (3 - 2 * g)
        applyVolume()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun resumeNow() {
        stopWatching()
        if (player.playWhenReady || player.mediaItemCount == 0) return
        if (player.playbackState == Player.STATE_IDLE) return
        if (inCall()) return
        attempts++
        resuming = true
        Log.i(TAG, "quiet again, resuming")
        player.play()
        handler.postDelayed({ if (player.playWhenReady) attempts = 0 }, RESET_MS)
    }

    companion object {
        private const val TAG = "FocusResumer"

        /** Behaviour › "When another app plays sound". */
        const val PREF_MODE = "extras_interruption_mode"
        /** Behaviour › "Music volume underneath", percent. */
        const val PREF_DUCK_PERCENT = "extras_duck_percent"
        /** Audio › "Fade between songs", seconds; 0 is off. */
        const val PREF_CROSSFADE = "extras_crossfade_seconds"
        /** The v0.2.1 on/off switch; read once for people who turned it off. */
        const val PREF_LEGACY_RESUME = "extras_resume_after_interruption"

        private const val QUIET_MS = 4_000L
        private const val WINDOW_MS = 20 * 60_000L
        private const val RESET_MS = 10_000L
        private const val MAX_ATTEMPTS = 2

        private val IGNORED_USAGES = setOf(
            AudioAttributes.USAGE_NOTIFICATION,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
            AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
            AudioAttributes.USAGE_NOTIFICATION_EVENT,
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
        )
    }
}
