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

package org.akanework.gramophone.ytdlp.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.util.Consumer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.coroutines.launch
import org.akanework.gramophone.ytdlp.AudioFormat
import org.akanework.gramophone.ytdlp.DownloadJob
import org.akanework.gramophone.ytdlp.DownloadRepository
import org.akanework.gramophone.ytdlp.DownloadService
import org.akanework.gramophone.ytdlp.JobStage
import org.akanework.gramophone.ytdlp.R
import org.akanework.gramophone.ytdlp.YtDlp

/**
 * The importer screen.
 *
 * Reachable two ways: from Settings, and — the one that actually gets used —
 * by hitting Share in the YouTube app and picking Gramophone, which lands the
 * link straight in the queue.
 */
class DownloaderActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Held as state so that a second share, which arrives through
            // onNewIntent rather than a fresh activity, still reaches the UI.
            var shared by remember { mutableStateOf(extractUrl(intent)) }
            DisposableEffect(Unit) {
                val listener = Consumer<Intent> { shared = extractUrl(it) }
                addOnNewIntentListener(listener)
                onDispose { removeOnNewIntentListener(listener) }
            }
            ImporterTheme {
                DownloaderScreen(sharedUrl = shared)
            }
        }
    }

    /**
     * Share payloads from the YouTube app are usually "Video title\nhttps://…",
     * so pull the first URL out rather than trusting the whole string.
     */
    private fun extractUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return Regex("""https?://\S+""").find(text)?.value
    }
}

@Composable
private fun ImporterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloaderScreen(sharedUrl: String?) {
    val context = LocalContext.current
    val repository = remember { DownloadRepository.get(context) }
    val jobs by repository.jobs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }

    var url by rememberSaveable { mutableStateOf("") }
    var formatId by rememberSaveable { mutableStateOf(AudioFormat.M4A.id) }
    val format = AudioFormat.fromId(formatId)
    var menuOpen by remember { mutableStateOf(false) }

    // Notifications carry the download progress; without the grant the
    // foreground service still runs, it is just invisible.
    val notificationPermission = rememberLauncherForNotifications()
    LaunchedEffect(Unit) { notificationPermission() }

    fun submit(target: String) {
        val trimmed = target.trim()
        if (trimmed.isBlank()) return
        repository.enqueue(trimmed, format)
        DownloadService.ensureRunning(context)
        url = ""
    }

    // A link arriving via Share should not need a second tap — but it must be
    // enqueued exactly once. The activity is recreated on rotation, so without
    // remembering that we already handled this URL a rotation would queue the
    // same song a second time.
    var autoSubmitted by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank() && autoSubmitted != sharedUrl) {
            autoSubmitted = sharedUrl
            submit(sharedUrl)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ytdlp_title)) },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ytdlp_update)) },
                            onClick = {
                                menuOpen = false
                                scope.launch {
                                    snackbars.showSnackbar(context.getString(R.string.ytdlp_updating))
                                    val result = runCatching { YtDlp.update(context) }
                                    snackbars.showSnackbar(
                                        result.fold(
                                            onSuccess = { status ->
                                                context.getString(
                                                    R.string.ytdlp_updated,
                                                    YtDlp.version(context)
                                                        ?: status?.name
                                                        ?: "up to date",
                                                )
                                            },
                                            onFailure = {
                                                context.getString(R.string.ytdlp_update_failed)
                                            },
                                        )
                                    )
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ytdlp_clear_finished)) },
                            onClick = {
                                menuOpen = false
                                repository.clearFinished()
                            },
                        )
                    }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.ytdlp_url_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AudioFormat.entries.forEach { option ->
                    FilterChip(
                        selected = format == option,
                        onClick = { formatId = option.id },
                        label = { Text(option.label) },
                    )
                }
            }
            Text(
                text = format.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = { submit(url) },
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.ytdlp_add))
            }

            if (jobs.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.ytdlp_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(jobs, key = { it.id }) { job ->
                    JobCard(job = job, onCancel = { repository.cancel(job.id) })
                }
            }
        }
    }
}

@Composable
private fun JobCard(job: DownloadJob, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = job.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    job.artist?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!job.stage.isTerminal) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
            }

            when (val stage = job.stage) {
                is JobStage.Downloading -> {
                    LinearProgressIndicator(
                        progress = { stage.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.ytdlp_downloading, stage.progress.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is JobStage.Done -> Text(
                    text = stage.artworkSource
                        ?.let { stringResource(R.string.ytdlp_done_with_art, it) }
                        ?: stringResource(R.string.ytdlp_done),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                is JobStage.Failed -> Text(
                    text = stage.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                is JobStage.Cancelled -> Text(
                    text = stringResource(R.string.ytdlp_cancelled),
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(stage.labelRes()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun JobStage.labelRes(): Int = when (this) {
    is JobStage.Queued -> R.string.ytdlp_queued
    is JobStage.Reading -> R.string.ytdlp_reading
    is JobStage.FindingArtwork -> R.string.ytdlp_finding_artwork
    is JobStage.Tagging -> R.string.ytdlp_tagging
    is JobStage.Importing -> R.string.ytdlp_importing
    else -> R.string.ytdlp_preparing
}

/**
 * Asks for POST_NOTIFICATIONS once on Android 13+, and for the legacy storage
 * write permission on Android 9 and older, where publishing to the shared music
 * folder still needs it.
 */
@Composable
private fun rememberLauncherForNotifications(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    return remember {
        {
            val wanted = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.filter {
                ContextCompat.checkSelfPermission(context, it) !=
                    PackageManager.PERMISSION_GRANTED
            }
            if (wanted.isNotEmpty()) launcher.launch(wanted.toTypedArray())
        }
    }
}
