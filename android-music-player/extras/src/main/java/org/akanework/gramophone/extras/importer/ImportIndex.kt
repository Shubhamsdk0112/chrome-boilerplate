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

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.edit

/**
 * Remembers which YouTube videos have already been imported, and where.
 *
 * MediaStore cannot be asked "do you have video X": the id only lives in a
 * comment tag it does not index. So the importer keeps its own map, and checks
 * that the row still exists before trusting it — the user may have deleted the
 * song from the library, in which case a re-import is exactly what they want.
 *
 * Only ever called from the download pipeline, which is already on IO.
 */
object ImportIndex {

    private const val FILE = "extras_imports"

    fun record(context: Context, videoId: String, uri: Uri?) {
        if (videoId.isBlank() || uri == null) return
        prefs(context).edit { putString(videoId, uri.toString()) }
    }

    /** The library entry for [videoId], if it was imported before and is still there. */
    fun find(context: Context, videoId: String): Uri? {
        if (videoId.isBlank()) return null
        val stored = prefs(context).getString(videoId, null) ?: return null
        val uri = Uri.parse(stored)
        val present = runCatching {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null,
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
        if (!present) prefs(context).edit { remove(videoId) }
        return uri.takeIf { present }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
