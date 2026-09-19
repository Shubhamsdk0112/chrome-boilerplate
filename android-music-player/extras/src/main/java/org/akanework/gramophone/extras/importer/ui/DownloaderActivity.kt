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

package org.akanework.gramophone.extras.importer.ui

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.util.Consumer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.akanework.gramophone.extras.R
import org.akanework.gramophone.extras.importer.AudioFormat
import org.akanework.gramophone.extras.importer.DownloadJob
import org.akanework.gramophone.extras.importer.DownloadRepository
import org.akanework.gramophone.extras.importer.DownloadService
import org.akanework.gramophone.extras.importer.JobStage
import org.akanework.gramophone.extras.importer.YtDlp
import org.akanework.gramophone.extras.ui.ExtrasTheme

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
        // Only a fresh launch carries a share to act on. After a rotation the
        // activity is recreated with saved state and the same intent, and the
        // song must not be queued a second time.
        val initial = if (savedInstanceState == null) {
            extractUrl(intent)?.let(::ShareRequest)
        } else {
            null
        }
        setContent {
            // A second share arrives through onNewIntent rather than a fresh
            // activity. Each delivery is a new ShareRequest, even for a link
            // shared before: re-sharing a failed one is how you retry it.
            var share by remember { mutableStateOf(initial) }
            DisposableEffect(Unit) {
                val listener = Consumer<Intent> { intent ->
                    extractUrl(intent)?.let { share = ShareRequest(it) }
                }
                addOnNewIntentListener(listener)
                onDispose { removeOnNewIntentListener(listener) }
            }
            ExtrasTheme {
                DownloaderScreen(share = share, onShareHandled = { share = null })
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

/**
 * One delivery of a shared link. Deliberately not a data class: two shares of
 * the same URL are two requests, and identity is what tells them apart.
 */
private class ShareRequest(val url: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloaderScreen(share: ShareRequest?, onShareHandled: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { DownloadRepository.get(context) }
    val jobs by repository.jobs.collectAsStateWithLifecycle()
    val restored by repository.restored.collectAsStateWithLifecycle()
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

    // A link arriving via Share should not need a second tap. The activity
    // hands over one ShareRequest per delivery and none after a rotation, so
    // acting on every request queues each share exactly once.
    LaunchedEffect(share) {
        if (share != null) {
            submit(share.url)
            onShareHandled()
        }
    }

    fun pasteFromClipboard() {
        val clip = context.getSystemService<ClipboardManager>()
            ?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
        val found = clip?.let { Regex("""https?://\S+""").find(it)?.value }
        if (found != null) {
            url = found
        } else {
            scope.launch { snackbars.showSnackbar(context.getString(R.string.ytdlp_nothing_to_paste)) }
        }
    }

    val active = jobs.filterNot { it.stage.isTerminal }
    val finished = jobs.filter { it.stage.isTerminal }.asReversed()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ytdlp_title)) },
                navigationIcon = {
                    IconButton(onClick = { (context as? Activity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
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
                        if (jobs.any { it.stage is JobStage.Failed }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ytdlp_retry_all)) },
                                onClick = {
                                    menuOpen = false
                                    repository.retryAllFailed()
                                },
                            )
                        }
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.ytdlp_url_hint)) },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { pasteFromClipboard() }) {
                            Icon(
                                Icons.Default.ContentPaste,
                                contentDescription = stringResource(R.string.ytdlp_paste),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit(url) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
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
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item {
                Button(
                    onClick = { submit(url) },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.ytdlp_add))
                }
            }

            if (restored && jobs.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.ytdlp_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                }
            }

            if (active.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.ytdlp_section_active), active.size) }
                items(active, key = { it.id }) { job ->
                    JobCard(
                        job = job,
                        onCancel = { repository.cancel(job.id) },
                        onRetry = { repository.retry(job.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (finished.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.ytdlp_section_done), finished.size) }
                items(finished, key = { it.id }) { job ->
                    JobCard(
                        job = job,
                        onCancel = { repository.cancel(job.id) },
                        onRetry = { repository.retry(job.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One import: poster, what it is, where it has got to, and the one action
 * that makes sense for its state (cancel, retry, or play).
 */
@Composable
private fun JobCard(
    job: DownloadJob,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stage = job.stage
    val done = stage as? JobStage.Done
    val failed = stage is JobStage.Failed || stage is JobStage.Cancelled

    val cardColors = when {
        failed -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        done != null -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        else -> CardDefaults.cardColors()
    }

    // A finished song opens in the player; nothing else has a tap. A disabled
    // clickable Card would dim its content, so the others are plain Cards.
    val playable = done?.uri
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Poster(job)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = job.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = listOfNotNull(job.artist, job.format.label.substringBefore(" /"))
                        .joinToString(" · ")
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StatusLine(stage)
                }
                when {
                    !stage.isTerminal -> IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ytdlp_cancel))
                    }
                    failed -> IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.ytdlp_retry))
                    }
                    done?.uri != null -> Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.ytdlp_play),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }

            when (stage) {
                is JobStage.Downloading -> LinearProgressIndicator(
                    progress = { stage.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                is JobStage.Queued, is JobStage.Reading, is JobStage.Updating, is JobStage.Retrying,
                is JobStage.FindingArtwork, is JobStage.Tagging, is JobStage.Importing ->
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else -> Unit
            }
        }
    }
    if (playable != null) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = cardColors,
            onClick = {
                // Off the main thread on purpose: starting an activity with a
                // content:// URI makes the system ask MediaProvider to check
                // it, and that SQLite read is reported back over binder to the
                // calling thread, which Gramophone's debug StrictMode policy
                // turns into a dialog. A binder call is fine from IO.
                scope.launch(Dispatchers.IO) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(playable, "audio/*")
                            .setPackage(context.packageName),
                    )
                }
            },
        ) { content() }
    } else {
        Card(modifier = modifier.fillMaxWidth(), colors = cardColors) { content() }
    }
}

@Composable
private fun Poster(job: DownloadJob) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (job.thumbnail != null) {
            AsyncImage(
                model = job.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusLine(stage: JobStage) {
    val (text, color) = when (stage) {
        is JobStage.Downloading -> {
            val pct = stage.progress.toInt()
            val eta = stage.etaSeconds
            val label = if (eta > 0) {
                stringResource(R.string.ytdlp_progress_eta, pct, formatEta(eta))
            } else {
                stringResource(R.string.ytdlp_progress_only, pct)
            }
            label to MaterialTheme.colorScheme.onSurfaceVariant
        }
        is JobStage.Done -> {
            val label = when {
                stage.alreadyImported -> stringResource(R.string.ytdlp_already_imported)
                stage.artworkSource != null ->
                    stringResource(R.string.ytdlp_done_with_art, stage.artworkSource)
                else -> stringResource(R.string.ytdlp_done)
            }
            label to MaterialTheme.colorScheme.primary
        }
        is JobStage.Failed -> stage.message to MaterialTheme.colorScheme.error
        is JobStage.Cancelled ->
            stringResource(R.string.ytdlp_cancelled) to MaterialTheme.colorScheme.onSurfaceVariant
        is JobStage.Retrying ->
            stringResource(R.string.ytdlp_retrying, stage.inSeconds, stage.attempt) to MaterialTheme.colorScheme.onSurfaceVariant
        else -> stringResource(stage.labelRes()) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = if (stage is JobStage.Failed) 6 else 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
    )
}

private fun formatEta(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "%d:%02d".format(m, s) else "${s}s"
}

private fun JobStage.labelRes(): Int = when (this) {
    is JobStage.Queued -> R.string.ytdlp_queued
    is JobStage.Reading -> R.string.ytdlp_reading
    is JobStage.Updating -> R.string.ytdlp_updating
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
