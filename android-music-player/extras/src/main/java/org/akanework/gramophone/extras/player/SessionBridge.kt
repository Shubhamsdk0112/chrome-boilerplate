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

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One [MediaController] to Gramophone's playback service, shared by every
 * :extras feature that plays or observes playback (podcasts, history).
 *
 * Main-thread only, like the controller itself. Listeners added through
 * [addListener] are re-attached if the controller ever has to be rebuilt.
 */
object SessionBridge {

    private const val SERVICE = "org.akanework.gramophone.logic.GramophonePlaybackService"

    private val lock = Mutex()
    private var controller: MediaController? = null
    private val listeners = mutableListOf<Player.Listener>()

    /** The connected controller, building one if needed. Call on the main thread. */
    suspend fun controller(context: Context): MediaController {
        controller?.takeIf { it.isConnected }?.let { return it }
        return lock.withLock {
            controller?.takeIf { it.isConnected }?.let { return it }
            val app = context.applicationContext
            val token = SessionToken(app, ComponentName(app, SERVICE))
            val future = MediaController.Builder(app, token).buildAsync()
            val built = suspendCancellableCoroutine { cont ->
                future.addListener(
                    {
                        runCatching { future.get() }
                            .onSuccess { cont.resume(it) }
                            .onFailure { cont.resumeWithException(it) }
                    },
                    ContextCompat.getMainExecutor(app),
                )
                cont.invokeOnCancellation { future.cancel(true) }
            }
            listeners.forEach { built.addListener(it) }
            controller = built
            built
        }
    }

    /** The controller if one is connected right now, without building one. */
    fun current(): MediaController? = controller?.takeIf { it.isConnected }

    fun addListener(listener: Player.Listener) {
        if (listener in listeners) return
        listeners += listener
        controller?.takeIf { it.isConnected }?.addListener(listener)
    }
}
