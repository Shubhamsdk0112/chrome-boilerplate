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

package org.akanework.gramophone.extras.podcast

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Show and episode artwork, fetched once and kept under cache/podcast-covers.
 *
 * Gramophone's image loader is deliberately offline (it has no network
 * fetcher), so remote covers are brought down to disk here and handed to it
 * as files. One in-flight fetch per URL; a miss is not retried within the
 * process to avoid hammering a dead host.
 */
object ImageCache {

    private val inFlight = Mutex()
    private val failed = mutableSetOf<String>()

    suspend fun file(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "podcast-covers").apply { mkdirs() }
        val target = File(dir, "${url.hashCode().toUInt()}.img")
        if (target.isFile && target.length() > 0) return@withContext target
        inFlight.withLock {
            if (target.isFile && target.length() > 0) return@withLock target
            if (url in failed) return@withLock null
            val bytes = PodcastSearch.Http.bytes(url)
            if (bytes == null || bytes.isEmpty()) {
                failed += url
                return@withLock null
            }
            runCatching { target.writeBytes(bytes) }.getOrNull()?.let { target }
        }
    }
}

/** A cover from a URL, with a placeholder while it loads or when it cannot. */
@Composable
fun Cover(url: String?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    val context = LocalContext.current
    val file by produceState<File?>(initialValue = null, url) {
        value = url?.let { ImageCache.file(context, it) }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (file != null) {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Default.Podcasts,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(0.45f),
            )
        }
    }
}
