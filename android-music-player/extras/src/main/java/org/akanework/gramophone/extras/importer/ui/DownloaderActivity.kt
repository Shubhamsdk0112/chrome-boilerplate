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
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.pluralStringResource
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
import kotlinx.coroutines.withContext
import org.akanework.gramophone.extras.importer.Cookies
import java.text.DateFormat
import java.util.Date
import org.akanework.gramophone.extras.R
import org.akanework.gramophone.extras.importer.AudioFormat
import org.akanework.gramophone.extras.importer.DownloadJob
import org.akanework.gramophone.extras.importer.DownloadRepository
import org.akanework.gramophone.extras.importer.DownloadService
import org.akanework.gramophone.extras.importer.JobStage
import org.akanework.gramophone.extras.importer.Pacing
import org.akanework.gramophone.extras.importer.YtDlp
import org.akanework.gramophone.extras.importer.ImportIndex
import org.akanework.gramophone.extras.importer.PlaylistImports
import org.akanework.gramophone.extras.importer.YouTubeLink
import org.akanework.gramophone.extras.importer.YouTubeLinks
import org.akanework.gramophone.extras.importer.YouTubeSearch
import org.akanework.gramophone.extras.podcast.Cover
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.produceState
import org.akanework.gramophone.extras.podcast.PodcastPlayer
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
        // The links, if any (the YouTube app sends just one); otherwise the
        // text itself, which the screen treats as a search.
        return YouTubeLinks.extractUrls(text).joinToString("\n").ifEmpty { text.trim().ifEmpty { null } }
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
    var pacingOpen by remember { mutableStateOf(false) }
    var longToPodcasts by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { longToPodcasts = withContext(Dispatchers.IO) { Pacing.longVideosToPodcasts(context) } }
    // First touch of a preferences file is a disk read; keep it off main.
    var pacing by remember { mutableStateOf(Pacing.SAFE) }
    LaunchedEffect(Unit) { pacing = withContext(Dispatchers.IO) { Pacing.read(context) } }

    // Notifications carry the download progress; without the grant the
    // foreground service still runs, it is just invisible.
    val notificationPermission = rememberLauncherForNotifications()
    LaunchedEffect(Unit) { notificationPermission() }

    var results by remember { mutableStateOf<List<YouTubeSearch.Entry>?>(null) }
    var resultsFor by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var inLibrary by remember { mutableStateOf<Set<String>>(emptySet()) }
    var playlist by remember { mutableStateOf<PlaylistState?>(null) }

    fun runSearch(query: String) {
        searching = true
        searchError = null
        resultsFor = query.trim()
        scope.launch {
            runCatching {
                YtDlp.ensureInitialized(context)
                val cookies = withContext(Dispatchers.IO) { Cookies.path(context) }
                YouTubeSearch.search(query, limit = 20, cookiesPath = cookies)
            }.onSuccess { list ->
                results = list
                inLibrary = withContext(Dispatchers.IO) {
                    list.filter { ImportIndex.find(context, it.videoId) != null }.map { it.videoId }.toSet()
                }
            }.onFailure { searchError = it.message ?: it::class.java.simpleName }
            searching = false
        }
    }

    fun openPlaylist(link: String) {
        playlist = PlaylistState.Loading(link)
        scope.launch {
            runCatching {
                YtDlp.ensureInitialized(context)
                val cookies = withContext(Dispatchers.IO) { Cookies.path(context) }
                YouTubeSearch.playlist(link, cookies)
            }.onSuccess { info ->
                val have = withContext(Dispatchers.IO) {
                    info.entries.count { ImportIndex.find(context, it.videoId) != null }
                }
                if ((playlist as? PlaylistState.Loading)?.url == link) {
                    playlist = PlaylistState.Ready(link, info, have)
                }
            }.onFailure {
                if (playlist != null) playlist = PlaylistState.Failed(link, it.message ?: it::class.java.simpleName)
            }
        }
    }

    /**
     * One box for everything: words search, one link downloads, several
     * links download each, a playlist opens a preview, a song opened from
     * a playlist downloads the song and offers the playlist.
     */
    fun submit(target: String) {
        val text = target.trim()
        if (text.isBlank()) return
        val links = YouTubeLinks.extractUrls(text)
        when {
            links.isEmpty() -> {
                runSearch(text)
                return
            }
            links.size > 1 -> {
                repository.enqueueAll(links, format)
                DownloadService.ensureRunning(context)
                scope.launch { snackbars.showSnackbar(context.getString(R.string.ytdlp_added_links, links.size)) }
            }
            else -> when (val link = YouTubeLinks.classify(links[0])) {
                is YouTubeLink.Playlist -> openPlaylist(link.url)
                is YouTubeLink.VideoInPlaylist -> {
                    repository.enqueue(link.videoUrl, format)
                    DownloadService.ensureRunning(context)
                    scope.launch {
                        val result = snackbars.showSnackbar(
                            message = context.getString(R.string.ytdlp_part_of_playlist),
                            actionLabel = context.getString(R.string.ytdlp_import_playlist_action),
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) openPlaylist(link.playlistUrl)
                    }
                }
                else -> {
                    repository.enqueue(link.url, format)
                    DownloadService.ensureRunning(context)
                }
            }
        }
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
    var cookiesOpen by remember { mutableStateOf(false) }

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
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ytdlp_pacing_menu, pacingLabel(pacing))) },
                            onClick = { menuOpen = false; pacingOpen = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ytdlp_cookies_menu)) },
                            onClick = { menuOpen = false; cookiesOpen = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ytdlp_long_to_podcasts)) },
                            trailingIcon = {
                                Checkbox(checked = longToPodcasts, onCheckedChange = null)
                            },
                            onClick = {
                                longToPodcasts = !longToPodcasts
                                scope.launch(Dispatchers.IO) { Pacing.setLongVideosToPodcasts(context, longToPodcasts) }
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
        playlist?.let { state ->
            PlaylistDialog(
                state = state,
                pacing = pacing,
                onImport = { name, info ->
                    playlist = null
                    scope.launch {
                        repository.enqueuePlaylist(name, state.url, info.entries, format)
                        DownloadService.ensureRunning(context)
                        snackbars.showSnackbar(context.getString(R.string.ytdlp_playlist_started, name))
                    }
                },
                onDismiss = { playlist = null },
            )
        }
        if (cookiesOpen) {
            CookiesDialog(
                onSaved = { _ ->
                    cookiesOpen = false
                    val retried = repository.retryBotChecked()
                    scope.launch {
                        snackbars.showSnackbar(
                            if (retried == 0) context.getString(R.string.ytdlp_cookies_saved)
                            else context.resources.getQuantityString(
                                R.plurals.ytdlp_cookies_saved_retrying, retried, retried
                            )
                        )
                    }
                },
                onRemoved = {
                    cookiesOpen = false
                    scope.launch { snackbars.showSnackbar(context.getString(R.string.ytdlp_cookies_removed)) }
                },
                onDismiss = { cookiesOpen = false },
            )
        }
        if (pacingOpen) {
            PacingDialog(
                current = pacing,
                onPick = { choice ->
                    pacing = choice
                    pacingOpen = false
                    scope.launch(Dispatchers.IO) { Pacing.write(context, choice) }
                },
                onDismiss = { pacingOpen = false },
            )
        }
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
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
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
                    enabled = url.isNotBlank() && !searching,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (YouTubeLinks.isSearch(url)) R.string.ytdlp_search else R.string.ytdlp_add
                        )
                    )
                }
            }

            if (searching || results != null || searchError != null) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.ytdlp_results_for, resultsFor),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { results = null; searchError = null }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ytdlp_clear_results))
                        }
                    }
                }
                if (searching) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            if (results == null) {
                                Text(
                                    stringResource(R.string.ytdlp_searching_first),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                searchError?.let { message ->
                    item {
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                results?.let { list ->
                    if (list.isEmpty() && !searching) {
                        item { Text(stringResource(R.string.ytdlp_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(list, key = { "result-" + it.videoId }) { entry ->
                        val queued = jobs.any { !it.stage.isTerminal && (it.videoId == entry.videoId || it.url.contains(entry.videoId)) }
                        val done = entry.videoId in inLibrary || jobs.any {
                            (it.videoId == entry.videoId || it.url.contains(entry.videoId)) && it.stage is JobStage.Done
                        }
                        SearchResultRow(
                            entry = entry,
                            queued = queued,
                            done = done,
                            toPodcasts = longToPodcasts && entry.durationSeconds >= 600,
                            onAdd = {
                                repository.enqueue(entry.url, format, title = entry.title, artist = entry.channel)
                                DownloadService.ensureRunning(context)
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }

            // Playlist imports in flight, one line each.
            val importing = jobs.mapNotNull { it.playlistId }.distinct()
            if (importing.isNotEmpty()) {
                items(importing, key = { "playlist-$it" }) { id ->
                    val name by produceState<String?>(null, id) {
                        value = withContext(Dispatchers.IO) { PlaylistImports.find(context, id)?.name }
                    }
                    val mine = jobs.filter { it.playlistId == id }
                    val landed = mine.count { it.stage is JobStage.Done }
                    if (mine.any { !it.stage.isTerminal }) {
                        Text(
                            stringResource(R.string.ytdlp_playlist_progress, name ?: "…", landed, mine.size),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            if (restored && jobs.isEmpty() && results == null && !searching && searchError == null) {
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
private fun pacingLabel(p: Pacing): String = stringResource(
    when (p) {
        Pacing.OFF -> R.string.ytdlp_pacing_off
        Pacing.QUICK -> R.string.ytdlp_pacing_quick
        Pacing.SAFE -> R.string.ytdlp_pacing_safe
        Pacing.CAUTIOUS -> R.string.ytdlp_pacing_cautious
    },
)

private sealed interface PlaylistState {
    val url: String

    data class Loading(override val url: String) : PlaylistState
    data class Ready(override val url: String, val info: YouTubeSearch.PlaylistInfo, val inLibrary: Int) : PlaylistState
    data class Failed(override val url: String, val message: String) : PlaylistState
}

@Composable
private fun SearchResultRow(
    entry: YouTubeSearch.Entry,
    queued: Boolean,
    done: Boolean,
    toPodcasts: Boolean,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !queued && !done, onClick = onAdd)
            .padding(vertical = 4.dp),
    ) {
        Box(modifier = Modifier.width(120.dp).aspectRatio(16f / 9f)) {
            Cover(
                url = entry.thumbnailUrl,
                size = 120.dp,
                modifier = Modifier.fillMaxSize(),
                corner = 8.dp,
                placeholder = Icons.Default.MusicNote,
            )
            val length = YouTubeSearch.formatDuration(entry.durationSeconds)
            if (length.isNotEmpty()) {
                Text(
                    text = length,
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(entry.channel, YouTubeSearch.formatViews(entry.views)).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                done -> Text(stringResource(R.string.ytdlp_in_library), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                toPodcasts -> Text(stringResource(R.string.ytdlp_to_podcasts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            when {
                done -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                queued -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                else -> IconButton(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.ytdlp_add))
                }
            }
        }
    }
}

@Composable
private fun PlaylistDialog(
    state: PlaylistState,
    pacing: Pacing,
    onImport: (String, YouTubeSearch.PlaylistInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is PlaylistState.Loading -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.ytdlp_playlist_reading)) },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        )
        is PlaylistState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.ytdlp_playlist_failed)) },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        )
        is PlaylistState.Ready -> {
            var name by rememberSaveable(state.url) { mutableStateOf(state.info.title) }
            val entries = state.info.entries
            val toFetch = (entries.size - state.inLibrary).coerceAtLeast(0)
            // Pacing gap plus roughly twenty seconds of actual download each.
            val minutes = ((toFetch * ((pacing.minGapSeconds + pacing.maxGapSeconds) / 2 + 20)) / 60).coerceAtLeast(1)
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.ytdlp_playlist_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (entries.isEmpty()) {
                            Text(stringResource(R.string.ytdlp_playlist_empty))
                            return@Column
                        }
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.ytdlp_playlist_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.ytdlp_playlist_summary, entries.size, state.inLibrary),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.ytdlp_playlist_estimate, minutes, pacingLabel(pacing)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            entries.forEachIndexed { i, e ->
                                Text(
                                    "${i + 1}. ${e.title}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = { onImport(name.trim().ifEmpty { state.info.title }, state.info) }) {
                            Text(stringResource(R.string.ytdlp_playlist_import, entries.size))
                        }
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
    }
}

/**
 * Paste or pick a browser cookie export; see [Cookies] for why. Reads and
 * writes happen off the main thread — the export can be a few hundred KB.
 */
@Composable
private fun CookiesDialog(
    onSaved: (Cookies.Summary) -> Unit,
    onRemoved: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<Cookies.Summary?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        summary = withContext(Dispatchers.IO) { Cookies.summary(context) }
        loaded = true
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                }
            }.onSuccess { text = it }
                .onFailure { error = context.getString(R.string.ytdlp_cookies_read_failed) }
            busy = false
        }
    }

    fun save() {
        scope.launch {
            busy = true
            error = null
            val pasted = text
            withContext(Dispatchers.IO) { runCatching { Cookies.import(context, pasted) } }
                .onSuccess { onSaved(it) }
                .onFailure { error = it.message ?: it::class.java.simpleName }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ytdlp_cookies_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.ytdlp_cookies_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (loaded) {
                    val s = summary
                    Text(
                        text = when {
                            s == null -> stringResource(R.string.ytdlp_cookies_none)
                            !s.loggedIn -> stringResource(R.string.ytdlp_cookies_status_anon, s.count)
                            s.loginExpiresAt > 0 -> stringResource(
                                R.string.ytdlp_cookies_status, s.count,
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.loginExpiresAt * 1000)),
                            )
                            else -> stringResource(R.string.ytdlp_cookies_status_session, s.count)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (s?.loggedIn == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.ytdlp_cookies_paste_hint)) },
                    minLines = 3,
                    maxLines = 6,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) {
                        Text(stringResource(R.string.ytdlp_cookies_pick))
                    }
                    if (summary != null) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { Cookies.clear(context) }
                                    onRemoved()
                                }
                            },
                            enabled = !busy,
                        ) { Text(stringResource(R.string.ytdlp_cookies_remove)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = text.isNotBlank() && !busy) {
                Text(stringResource(R.string.ytdlp_cookies_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** The gap between YouTube downloads; explained in the terms that matter. */
@Composable
private fun PacingDialog(current: Pacing, onPick: (Pacing) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ytdlp_pacing_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.ytdlp_pacing_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Pacing.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == current, onClick = { onPick(option) })
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(pacingLabel(option), style = MaterialTheme.typography.bodyLarge)
                            val detail = if (option == Pacing.OFF) stringResource(R.string.ytdlp_pacing_off_detail)
                            else stringResource(R.string.ytdlp_pacing_detail, option.minGapSeconds, option.maxGapSeconds)
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
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
    val podcastGuid = done?.takeIf { it.podcast }?.episodeGuid
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
                is JobStage.Queued, is JobStage.Reading, is JobStage.Updating, is JobStage.Retrying, is JobStage.Pacing,
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
                if (podcastGuid != null) {
                    // An episode plays through the podcast player, with its
                    // chapters and saved position.
                    PodcastPlayer.playGuid(context, podcastGuid)
                    return@Card
                }
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
                stage.podcast && stage.chapters > 0 -> pluralStringResource(R.plurals.ytdlp_done_podcast_chapters, stage.chapters, stage.chapters)
                stage.podcast -> stringResource(R.string.ytdlp_done_podcast)
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
        is JobStage.Pacing ->
            stringResource(R.string.ytdlp_pacing, stage.inSeconds) to MaterialTheme.colorScheme.onSurfaceVariant
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
