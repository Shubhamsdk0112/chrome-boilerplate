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
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Re-runs the filter when new audio appears, so junk never accumulates again.
 *
 * Without this the filter is a one-off cleanup: the next batch of voice notes
 * shows up in the library and stays until the user remembers to open settings
 * and scan. Messaging apps produce those continuously, which is exactly the
 * problem the filter exists to solve.
 *
 * Three things keep it from being expensive:
 *
 *  - It is **opt-in** and off by default.
 *  - Verdicts are cached, so a re-scan only does real work for files it has
 *    not seen. The common case is a handful of new files.
 *  - Changes are **debounced**. Copying an album fires a burst of notifications
 *    and we want one scan at the end, not one per track.
 */
object FilterWatcher {

    private const val TAG = "FilterWatcher"

    /** Quiet period before acting, so a burst of changes collapses into one scan. */
    private const val DEBOUNCE_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null
    private var pending: Job? = null

    /**
     * Starts watching if the user has asked for it. Safe to call repeatedly —
     * the intended caller is `Application.onCreate`.
     *
     * The preference read is a disk read, and Gramophone's debug builds run
     * `Application.onCreate` under a StrictMode policy that turns a main-thread
     * disk read into a dialog. So the decision is made on the IO dispatcher;
     * `registerContentObserver` is thread-agnostic and callbacks still land on
     * the main looper through the handler.
     */
    fun ensureStarted(context: Context) {
        val appContext = context.applicationContext
        scope.launch { applyPreference(appContext) }
    }

    @Synchronized
    private fun applyPreference(appContext: Context) {
        val store = FilterStore(appContext)
        if (!store.enabled || !store.autoRescan) {
            stop(appContext)
            return
        }
        if (observer != null) return

        val handler = Handler(Looper.getMainLooper())
        val created = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = schedule(appContext)
        }

        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        try {
            appContext.contentResolver.registerContentObserver(uri, true, created)
            observer = created
            Log.i(TAG, "watching for new audio")
        } catch (e: Exception) {
            // Losing the watcher must never take the app down with it.
            Log.w(TAG, "could not register observer", e)
        }
    }

    @Synchronized
    fun stop(context: Context) {
        pending?.cancel()
        pending = null
        observer?.let {
            runCatching { context.applicationContext.contentResolver.unregisterContentObserver(it) }
        }
        observer = null
    }

    private fun schedule(context: Context) {
        // Restarting the timer on every notification is what collapses a burst.
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            val store = FilterStore(context)
            if (!store.enabled || !store.autoRescan) return@launch
            runCatching { LibraryScanner(context).scan() }
                .onSuccess { Log.i(TAG, "re-scan hid ${it.hidden} of ${it.total}") }
                .onFailure { Log.w(TAG, "re-scan failed", it) }
        }
    }
}
