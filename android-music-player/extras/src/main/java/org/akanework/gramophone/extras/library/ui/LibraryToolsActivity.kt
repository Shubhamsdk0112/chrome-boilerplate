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

package org.akanework.gramophone.extras.library.ui

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.extras.R
import org.akanework.gramophone.extras.library.CoverFixer
import org.akanework.gramophone.extras.library.SmartPlaylists
import org.akanework.gramophone.extras.ui.ExtrasTheme

/** Settings › Library tools: missing covers and smart playlists. */
class LibraryToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ExtrasTheme { ToolsScreen() } }
    }
}

private sealed interface CoverState {
    data object Idle : CoverState
    data class Scanning(val done: Int, val total: Int) : CoverState
    data class LookingUp(val done: Int, val total: Int) : CoverState
    data class Review(val found: List<CoverFixer.Found>, val missing: Int, val notFound: Int, val unsupported: Int) : CoverState
    data class Writing(val done: Int, val total: Int) : CoverState
    data class Finished(val written: Int) : CoverState
    data object NoneMissing : CoverState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    var covers by remember { mutableStateOf<CoverState>(CoverState.Idle) }
    val unticked = remember { mutableStateListOf<Long>() }

    var smartOn by remember { mutableStateOf(true) }
    var smartLast by remember { mutableStateOf(0L) }
    var smartCounts by remember { mutableStateOf<Map<SmartPlaylists.Kind, Int>?>(null) }
    var smartBusy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            smartOn = SmartPlaylists.enabled(context)
            smartLast = SmartPlaylists.lastRefresh(context)
            smartCounts = SmartPlaylists.lastCounts(context)
        }
    }

    fun startScan() {
        unticked.clear()
        scope.launch {
            covers = CoverState.Scanning(0, 0)
            val missing = runCatching { CoverFixer.scan(context) { d, t -> covers = CoverState.Scanning(d, t) } }
                .getOrElse { snackbars.showSnackbar(it.message ?: "Could not read the library"); covers = CoverState.Idle; return@launch }
            if (missing.isEmpty()) { covers = CoverState.NoneMissing; return@launch }
            val embeddable = missing.filter { it.canEmbed }
            covers = CoverState.LookingUp(0, embeddable.size)
            val found = CoverFixer.lookUp(context, embeddable) { d, t -> covers = CoverState.LookingUp(d, t) }
            covers = CoverState.Review(found, missing.size, embeddable.size - found.size, missing.size - embeddable.size)
        }
    }

    fun write(found: List<CoverFixer.Found>) {
        scope.launch {
            covers = CoverState.Writing(0, found.size)
            val n = CoverFixer.apply(context, found) { d, t -> covers = CoverState.Writing(d, t) }
            // The library keeps decoded covers in memory, keyed by the song;
            // without this the old blank stays until the app restarts.
            if (n > 0) runCatching { coil3.SingletonImageLoader.get(context).memoryCache?.clear() }
            covers = CoverState.Finished(n)
        }
    }

    // Android 11+: one system dialog to allow changing all the chosen files.
    var pending by remember { mutableStateOf<List<CoverFixer.Found>>(emptyList()) }
    val writeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) write(pending)
        else scope.launch { snackbars.showSnackbar(context.getString(R.string.tools_covers_denied)) }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) write(pending) else scope.launch { snackbars.showSnackbar(context.getString(R.string.tools_covers_denied)) }
    }
    fun requestAndWrite(found: List<CoverFixer.Found>) {
        pending = found
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = MediaStore.createWriteRequest(context.contentResolver, found.map { it.song.uri })
            writeRequest.launch(IntentSenderRequest.Builder(intent.intentSender).build())
        } else {
            @Suppress("DEPRECATION")
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // The song lists only redraw a row when its text changes, so a new cover
    // would stay hidden until the app restarts. Once covers were written,
    // leaving this screen reopens the library fresh instead.
    val wroteCovers = (covers as? CoverState.Finished)?.written?.let { it > 0 } == true
    fun leave() {
        val activity = context as? Activity ?: return
        if (wroteCovers) {
            activity.packageManager.getLaunchIntentForPackage(activity.packageName)?.let { launch ->
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                activity.startActivity(launch)
            }
        }
        activity.finish()
    }
    androidx.activity.compose.BackHandler(enabled = wroteCovers) { leave() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tools_title)) },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.tools_covers_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.tools_covers_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            when (val s = covers) {
                CoverState.Idle -> item { Button(onClick = { startScan() }) { Text(stringResource(R.string.tools_covers_find)) } }
                is CoverState.Scanning -> item { Progress(stringResource(R.string.tools_covers_scanning, s.done, s.total), s.done, s.total) }
                is CoverState.LookingUp -> item { Progress(stringResource(R.string.tools_covers_looking, s.done, s.total), s.done, s.total) }
                is CoverState.Writing -> item { Progress(stringResource(R.string.tools_covers_writing, s.done, s.total), s.done, s.total) }
                CoverState.NoneMissing -> item {
                    Text(stringResource(R.string.tools_covers_none_missing), color = MaterialTheme.colorScheme.primary)
                }
                is CoverState.Finished -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            pluralStringResource(R.plurals.tools_covers_done, s.written, s.written),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        OutlinedButton(onClick = { startScan() }) { Text(stringResource(R.string.tools_covers_again)) }
                    }
                }
                is CoverState.Review -> {
                    item {
                        Text(
                            if (s.found.isEmpty()) stringResource(R.string.tools_covers_summary_none, s.missing)
                            else stringResource(R.string.tools_covers_summary, s.missing, s.found.size),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (s.found.isNotEmpty() && s.notFound > 0 || s.unsupported > 0) {
                            Text(
                                listOfNotNull(
                                    if (s.notFound > 0) stringResource(R.string.tools_covers_no_match, s.notFound) else null,
                                    if (s.unsupported > 0) stringResource(R.string.tools_covers_unsupported, s.unsupported) else null,
                                ).joinToString(" "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (s.found.isNotEmpty()) {
                        val chosen = s.found.filter { it.song.id !in unticked }
                        item {
                            Button(onClick = { requestAndWrite(chosen) }, enabled = chosen.isNotEmpty()) {
                                Text(pluralStringResource(R.plurals.tools_covers_apply, chosen.size, chosen.size))
                            }
                        }
                        items(s.found, key = { it.song.id }) { f ->
                            val ticked = f.song.id !in unticked
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { if (ticked) unticked += f.song.id else unticked -= f.song.id },
                            ) {
                                AsyncImage(
                                    model = f.cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(f.song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(f.song.artist, f.source).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Checkbox(checked = ticked, onCheckedChange = null)
                            }
                        }
                    }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.tools_smart_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.tools_smart_explainer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = smartOn, onCheckedChange = {
                        smartOn = it
                        smartCounts = null
                        SmartPlaylists.setEnabled(context, it)
                    })
                }
            }
            if (smartOn) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SmartPlaylists.Kind.entries.forEach { kind ->
                                Row {
                                    Text(kind.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                    smartCounts?.get(kind)?.let {
                                        Text(
                                            pluralStringResource(R.plurals.tools_smart_count, it, it),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Text(
                                if (smartLast > 0) stringResource(
                                    R.string.tools_smart_last,
                                    if (System.currentTimeMillis() - smartLast < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.tools_smart_just_now)
                                    else DateUtils.getRelativeTimeSpanString(smartLast, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                                ) else stringResource(R.string.tools_smart_never),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                enabled = !smartBusy,
                                onClick = {
                                    smartBusy = true
                                    scope.launch {
                                        smartCounts = runCatching { SmartPlaylists.refresh(context) }.getOrNull()
                                        smartLast = System.currentTimeMillis()
                                        smartBusy = false
                                    }
                                },
                            ) { Text(stringResource(R.string.tools_smart_update)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Progress(label: String, done: Int, total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}
