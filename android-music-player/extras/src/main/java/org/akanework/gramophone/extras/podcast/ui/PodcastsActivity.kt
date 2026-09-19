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

package org.akanework.gramophone.extras.podcast.ui

import android.app.Activity
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.akanework.gramophone.extras.R
import org.akanework.gramophone.extras.importer.AudioFormat
import org.akanework.gramophone.extras.importer.DownloadRepository
import org.akanework.gramophone.extras.importer.DownloadService
import org.akanework.gramophone.extras.importer.JobKind
import org.akanework.gramophone.extras.importer.JobStage
import org.akanework.gramophone.extras.podcast.Cover
import org.akanework.gramophone.extras.podcast.Episode
import org.akanework.gramophone.extras.podcast.EpisodeSource
import org.akanework.gramophone.extras.podcast.Podcast
import org.akanework.gramophone.extras.podcast.PodcastPlayer
import org.akanework.gramophone.extras.podcast.PodcastSearch
import org.akanework.gramophone.extras.podcast.PodcastStore
import org.akanework.gramophone.extras.podcast.SearchResult
import org.akanework.gramophone.extras.ui.ExtrasTheme

/**
 * Podcasts: find a show, follow it, download or stream episodes, and pick
 * up where you left off. Two screens in one activity: the shows you follow
 * (with search) and one show's episodes.
 */
class PodcastsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PodcastPlayer.attach(this)
        setContent { ExtrasTheme { PodcastsRoot() } }
    }
}

@Composable
private fun PodcastsRoot() {
    val context = LocalContext.current
    val store = remember { PodcastStore.get(context) }
    val podcasts by store.podcasts.collectAsStateWithLifecycle()
    var openFeed by rememberSaveable { mutableStateOf<String?>(null) }

    var localShow by remember { mutableStateOf<Podcast?>(null) }
    val open = openFeed?.let { url ->
        podcasts.firstOrNull { it.feedUrl == url } ?: localShow?.takeIf { it.feedUrl == url }
    }
    if (open != null) {
        BackHandler { openFeed = null }
        ShowScreen(podcast = open, onBack = { openFeed = null })
    } else {
        ShowsScreen(onOpen = { show ->
            if (show.feedUrl == PodcastStore.LOCAL_FEED) localShow = show
            openFeed = show.feedUrl
        })
    }
}

// ----------------------------------------------------------------------
// Shows you follow + search
// ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShowsScreen(onOpen: (Podcast) -> Unit) {
    val context = LocalContext.current
    val store = remember { PodcastStore.get(context) }
    val podcasts by store.podcasts.collectAsStateWithLifecycle()
    val loaded by store.loaded.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }

    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val repository = remember { DownloadRepository.get(context) }
    val jobs by repository.jobs.collectAsStateWithLifecycle()
    val inFlight = jobs.filter { !it.stage.isTerminal && (it.kind == JobKind.PODCAST || it.asPodcast) }
    // Long audio already on the phone, as a virtual show. Re-read whenever the
    // screen comes back, so a file hidden by the filter shows up here.
    var local by remember { mutableStateOf<Podcast?>(null) }
    LaunchedEffect(Unit) { local = store.localLongAudio() }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) { results = null; return }
        searching = true
        scope.launch {
            if (isYouTube(q)) {
                // A YouTube link: the video is downloaded as an episode, with
                // its chapters, into this section. Progress shows below.
                DownloadRepository.get(context).enqueue(q, AudioFormat.M4A, asPodcast = true)
                DownloadService.ensureRunning(context)
                query = ""; results = null
                snackbars.showSnackbar(context.getString(R.string.podcast_youtube_queued))
            } else if (q.startsWith("http://") || q.startsWith("https://")) {
                // A pasted feed URL: fetch it and follow it straight away.
                runCatching { PodcastSearch.fetchFeed(q) }
                    .onSuccess { store.subscribe(it); query = ""; results = null
                        snackbars.showSnackbar(context.getString(R.string.podcast_followed, it.title)) }
                    .onFailure { snackbars.showSnackbar(context.getString(R.string.podcast_feed_failed)) }
            } else {
                results = PodcastSearch.search(q)
                if (results.isNullOrEmpty()) snackbars.showSnackbar(context.getString(R.string.podcast_no_results))
            }
            searching = false
        }
    }

    fun refreshAll() {
        if (refreshing) return
        refreshing = true
        scope.launch {
            var failed = 0
            for (p in podcasts) {
                runCatching { PodcastSearch.fetchFeed(p.feedUrl) }
                    .onSuccess { store.subscribe(it) }
                    .onFailure { failed++ }
            }
            refreshing = false
            if (failed > 0) snackbars.showSnackbar(context.getString(R.string.podcast_refresh_failed, failed))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.podcast_title)) },
                navigationIcon = {
                    IconButton(onClick = { (context as? Activity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (podcasts.isNotEmpty()) {
                        IconButton(onClick = { refreshAll() }, enabled = !refreshing) {
                            if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.podcast_refresh))
                        }
                    }
                },
            )
        },
        bottomBar = { NowPlayingBar() },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.podcast_search_hint)) },
                    singleLine = true,
                    trailingIcon = {
                        if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else if (query.isNotEmpty()) IconButton(onClick = { query = ""; results = null }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        } else Icon(Icons.Default.Search, contentDescription = null)
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val shown = results
            if (shown != null) {
                item { SectionTitle(stringResource(R.string.podcast_results), shown.size) }
                items(shown, key = { it.feedUrl }) { r ->
                    val followed = podcasts.any { it.feedUrl == r.feedUrl }
                    ResultRow(
                        result = r,
                        followed = followed,
                        onFollow = {
                            scope.launch {
                                runCatching { PodcastSearch.fetchFeed(r.feedUrl) }
                                    .onSuccess { store.subscribe(it)
                                        snackbars.showSnackbar(context.getString(R.string.podcast_followed, it.title)) }
                                    .onFailure { snackbars.showSnackbar(context.getString(R.string.podcast_feed_failed)) }
                            }
                        },
                        onOpen = { podcasts.firstOrNull { it.feedUrl == r.feedUrl }?.let(onOpen) },
                    )
                }
            } else {
                if (loaded && podcasts.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.podcast_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                    }
                }
                if (inFlight.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.podcast_downloading), inFlight.size) }
                    items(inFlight, key = { "job:" + it.id }) { job ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(job.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val stage = job.stage
                                    Text(
                                        when (stage) {
                                            is JobStage.Downloading -> "${stage.progress.toInt()}%"
                                            else -> stringResource(R.string.podcast_preparing)
                                        },
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { repository.cancel(job.id) }) { Icon(Icons.Default.Close, contentDescription = null) }
                            }
                        }
                    }
                }
                if (podcasts.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.podcast_following), podcasts.size) }
                    items(podcasts, key = { it.feedUrl }) { p -> ShowRow(p, onClick = { onOpen(p) }) }
                }
                local?.let { show ->
                    item { SectionTitle(stringResource(R.string.podcast_local), show.episodes.size) }
                    item { ShowRow(show, onClick = { onOpen(show) }) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultRow(result: SearchResult, followed: Boolean, onFollow: () -> Unit, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = { if (followed) onOpen() else onFollow() }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cover(result.imageUrl, 56.dp, Modifier.size(56.dp))
            Column(Modifier.weight(1f)) {
                Text(result.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                result.author?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (followed) {
                Icon(Icons.Default.DownloadDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            } else {
                FilledTonalButton(onClick = onFollow) { Text(stringResource(R.string.podcast_follow)) }
            }
        }
    }
}

@Composable
private fun ShowRow(podcast: Podcast, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cover(podcast.imageUrl, 64.dp, Modifier.size(64.dp))
            Column(Modifier.weight(1f)) {
                Text(podcast.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    podcast.author,
                    stringResource(R.string.podcast_episode_count, podcast.episodes.size),
                ).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ----------------------------------------------------------------------
// One show
// ----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShowScreen(podcast: Podcast, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PodcastStore.get(context) }
    val repository = remember { DownloadRepository.get(context) }
    val downloads by store.downloads.collectAsStateWithLifecycle()
    val positions by store.positions.collectAsStateWithLifecycle()
    val jobs by repository.jobs.collectAsStateWithLifecycle()
    val current by PodcastPlayer.current.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(podcast.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
                },
                actions = {
                    if (podcast.feedUrl.startsWith("http")) IconButton(
                        onClick = {
                            refreshing = true
                            scope.launch {
                                runCatching { PodcastSearch.fetchFeed(podcast.feedUrl) }
                                    .onSuccess { store.subscribe(it) }
                                    .onFailure { snackbars.showSnackbar(context.getString(R.string.podcast_feed_failed)) }
                                refreshing = false
                            }
                        },
                        enabled = !refreshing,
                    ) {
                        if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.podcast_refresh))
                    }
                    if (podcast.feedUrl != PodcastStore.LOCAL_FEED) IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = null) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.podcast_unfollow)) },
                            onClick = { menuOpen = false; store.unsubscribe(podcast.feedUrl); onBack() },
                        )
                    }
                },
            )
        },
        bottomBar = { NowPlayingBar() },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Cover(podcast.imageUrl, 96.dp, Modifier.size(96.dp), corner = 14.dp)
                    Column(Modifier.weight(1f)) {
                        Text(podcast.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        podcast.author?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            stringResource(R.string.podcast_episode_count, podcast.episodes.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            podcast.description?.takeIf { it.isNotBlank() }?.let { desc ->
                item {
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }

            items(podcast.episodes, key = { it.guid }) { episode ->
                val job = jobs.firstOrNull {
                    !it.stage.isTerminal &&
                        ((it.kind == JobKind.PODCAST && it.episodeGuid == episode.guid) ||
                            (it.asPodcast && it.url == episode.audioUrl))
                }
                val downloaded = episode.source == EpisodeSource.LOCAL || downloads[episode.guid] != null
                EpisodeRow(
                    episode = episode,
                    downloaded = downloaded,
                    downloading = job?.stage as? JobStage.Downloading,
                    queued = job != null && job.stage !is JobStage.Downloading,
                    positionMs = positions[episode.guid] ?: 0L,
                    isCurrent = current == episode.guid,
                    onPlay = { startMs -> PodcastPlayer.play(context, podcast, episode, startMs) },
                    onDownload = {
                        if (episode.source == EpisodeSource.YOUTUBE) {
                            repository.enqueue(episode.audioUrl, AudioFormat.M4A, asPodcast = true)
                            DownloadService.ensureRunning(context)
                        } else {
                            repository.enqueueEpisode(podcast, episode)
                        }
                    },
                    onCancel = { job?.let { repository.cancel(it.id) } },
                    onDelete = {
                        scope.launch {
                            store.downloadedFile(episode.guid)?.delete()
                            store.forgetDownload(episode.guid)
                            if (episode.source == EpisodeSource.YOUTUBE) store.removeEpisode(podcast.feedUrl, episode.guid)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    downloaded: Boolean,
    downloading: JobStage.Downloading?,
    queued: Boolean,
    positionMs: Long,
    isCurrent: Boolean,
    onPlay: (startMs: Long?) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = if (isCurrent) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    else CardDefaults.cardColors()
    val canPlay = downloaded || episode.streamable
    var chaptersOpen by rememberSaveable(episode.guid) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), colors = colors, onClick = { if (canPlay) onPlay(null) else onDownload() }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(episode.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = buildList {
                if (episode.publishedAt > 0) add(DateUtils.getRelativeTimeSpanString(episode.publishedAt, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString())
                if (episode.durationSeconds > 0) add(formatDuration(episode.durationSeconds))
                if (positionMs > 0 && episode.durationSeconds > 0) {
                    val left = episode.durationSeconds - (positionMs / 1000).toInt()
                    if (left > 60) add(stringResource(R.string.podcast_minutes_left, left / 60))
                }
                if (downloaded && episode.source != EpisodeSource.LOCAL) add(stringResource(R.string.podcast_downloaded))
                if (episode.chapters.isNotEmpty()) add(stringResource(R.string.podcast_chapter_count, episode.chapters.size))
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (positionMs > 0 && episode.durationSeconds > 0) {
                LinearProgressIndicator(
                    progress = { (positionMs / 1000f / episode.durationSeconds).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
            // Some hosts put the title in the notes as well; that adds nothing.
            episode.description?.takeIf { it.isNotBlank() && it.trim() != episode.title.trim() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canPlay) {
                    Button(onClick = { onPlay(null) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (positionMs > 0) stringResource(R.string.podcast_resume) else stringResource(R.string.podcast_play))
                    }
                } else {
                    Text(
                        stringResource(R.string.podcast_needs_download),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (episode.chapters.isNotEmpty()) {
                    TextButton(onClick = { chaptersOpen = !chaptersOpen }) {
                        Text(if (chaptersOpen) stringResource(R.string.podcast_hide_chapters) else stringResource(R.string.podcast_show_chapters))
                    }
                }
                Spacer(Modifier.weight(1f))
                when {
                    downloading != null -> {
                        Text("${downloading.progress.toInt()}%", style = MaterialTheme.typography.labelMedium)
                        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ytdlp_cancel)) }
                    }
                    queued -> {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ytdlp_cancel)) }
                    }
                    downloaded -> IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.podcast_delete_download))
                    }
                    else -> IconButton(onClick = onDownload) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.podcast_download))
                    }
                }
            }
            if (downloading != null) {
                LinearProgressIndicator(progress = { downloading.progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            if (chaptersOpen && episode.chapters.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                val currentChapter = episode.chapterAt(positionMs)
                episode.chapters.forEachIndexed { index, chapter ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = canPlay) { onPlay(chapter.startMs) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            formatClock(chapter.startMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(56.dp),
                        )
                        Text(
                            chapter.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent && chapter == currentChapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Now playing strip
// ----------------------------------------------------------------------

@Composable
private fun NowPlayingBar() {
    val context = LocalContext.current
    val store = remember { PodcastStore.get(context) }
    val current by PodcastPlayer.current.collectAsStateWithLifecycle()
    val playing by PodcastPlayer.isPlaying.collectAsStateWithLifecycle()
    val playerEpisode by PodcastPlayer.currentEpisode.collectAsStateWithLifecycle()
    val episode = current?.let { id -> store.episode(id) ?: playerEpisode?.takeIf { it.guid == id } }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    val chapter = episode?.chapterAt(position)

    LaunchedEffect(current, playing) {
        while (current != null) {
            position = PodcastPlayer.currentPositionMs() ?: 0L
            duration = PodcastPlayer.currentDurationMs() ?: (episode?.durationSeconds?.toLong()?.times(1000) ?: 0L)
            delay(1_000)
        }
    }

    AnimatedVisibility(visible = episode != null) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
            Column {
                if (duration > 0) {
                    LinearProgressIndicator(
                        progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(chapter?.title ?: episode?.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (chapter != null) "${episode?.title.orEmpty()} · ${formatClock(position)}"
                            else "${formatClock(position)} / ${formatClock(duration)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val hasChapters = !episode?.chapters.isNullOrEmpty()
                    if (hasChapters) {
                        IconButton(onClick = { PodcastPlayer.skipChapter(forward = false) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = null)
                        }
                    }
                    IconButton(onClick = { PodcastPlayer.seekBy(-10_000) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Replay10, contentDescription = null) }
                    IconButton(onClick = { PodcastPlayer.togglePlayPause() }, modifier = Modifier.size(40.dp)) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                    }
                    IconButton(onClick = { PodcastPlayer.seekBy(30_000) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Forward30, contentDescription = null) }
                    if (hasChapters) {
                        IconButton(onClick = { PodcastPlayer.skipChapter(forward = true) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.SkipNext, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}

private fun isYouTube(text: String): Boolean {
    val t = text.lowercase()
    return t.contains("youtube.com/") || t.contains("youtu.be/") || t.contains("music.youtube.com/")
}

private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m} min"
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
