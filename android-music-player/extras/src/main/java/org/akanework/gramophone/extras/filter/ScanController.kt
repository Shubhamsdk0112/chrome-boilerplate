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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the running scan, outside the lifetime of any screen.
 *
 * A scan over a large library takes a while, and the AI pass costs real money.
 * Driving it from a composable's own scope meant a rotation — or the user
 * glancing at another app — cancelled it halfway, throwing away the wait and
 * any spend already incurred. Holding it here makes the screen a pure observer
 * that can come and go freely.
 *
 * Only one scan runs at a time; asking again while one is in flight is ignored
 * rather than queued, because a second concurrent pass would just race the
 * first to write the same hidden set.
 */
object ScanController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _progress = MutableStateFlow<LibraryScanner.Progress?>(null)
    val progress: StateFlow<LibraryScanner.Progress?> = _progress.asStateFlow()

    private val _result = MutableStateFlow<LibraryScanner.Result?>(null)
    val result: StateFlow<LibraryScanner.Result?> = _result.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val isRunning: Boolean get() = job?.isActive == true

    /** Starts a scan unless one is already running. */
    @Synchronized
    fun start(context: Context) {
        if (isRunning) return
        val appContext = context.applicationContext
        _error.value = null
        _progress.value = LibraryScanner.Progress(LibraryScanner.Stage.QUERYING)
        job = scope.launch {
            try {
                _result.value = LibraryScanner(appContext).scan { _progress.value = it }
            } catch (e: Exception) {
                _error.value = e.message ?: e::class.java.simpleName
            } finally {
                _progress.value = null
            }
        }
    }

    /** Drops the last result, e.g. after the user restores everything. */
    fun clearResult() {
        _result.value = null
        _error.value = null
    }
}
