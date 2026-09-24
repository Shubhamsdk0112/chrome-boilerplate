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

package org.akanework.gramophone.extras.home

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.akanework.gramophone.extras.R
import org.akanework.gramophone.extras.history.HistoryEntry
import org.akanework.gramophone.extras.history.ListeningHistory
import org.akanework.gramophone.extras.history.ui.HistoryActivity
import org.akanework.gramophone.extras.importer.ui.DownloaderActivity
import org.akanework.gramophone.extras.podcast.Cover
import org.akanework.gramophone.extras.podcast.Episode
import org.akanework.gramophone.extras.podcast.Podcast
import org.akanework.gramophone.extras.podcast.PodcastPlayer
import org.akanework.gramophone.extras.podcast.PodcastStore
import org.akanework.gramophone.extras.podcast.ui.PodcastsActivity
import org.akanework.gramophone.extras.ui.ExtrasTheme
import org.akanework.gramophone.extras.ui.VerticalScrollReporter
import java.util.Calendar

/**
 * The Home tab: what you were just listening to and what is new, the way a
 * streaming app's front page works, built only from what is on the phone.
 *
 * A plain Fragment hosting Compose so it can sit in Gramophone's ViewPager2
 * next to the library tabs. Its root reports whether the list can scroll up,
 * which is what the library's pull-to-refresh asks before taking a drag.
 */
class HomeFragment : Fragment() {

    private val listState = LazyListState()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ScrollReportingFrame(requireContext()) { listState.canScrollBackward }.apply {
        addView(
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent { ExtrasTheme { HomeScreen(listState) } }
            },
        )
    }

    private class ScrollReportingFrame(
        context: Context,
        private val canScrollUpNow: () -> Boolean,
    ) : FrameLayout(context), VerticalScrollReporter {
        override fun canScrollUp(): Boolean = canScrollUpNow()
        override fun canScrollVertically(direction: Int): Boolean =
            if (direction < 0) canScrollUpNow() else super.canScrollVertically(direction)
    }
}

private data class ContinueItem(val podcast: Podcast, val episode: Episode, val positionMs: Long)

@Composable
private fun HomeScreen(listState: LazyListState) {
    val context = LocalContext.current
    val history by ListeningHistory.entries.collectAsStateWithLifecycle()
    val store = remember { PodcastStore.get(context) }
    val podcasts by store.podcasts.collectAsStateWithLifecycle()
    val positions by store.positions.collectAsStateWithLifecycle()
    val playedAt by store.playedAt.collectAsStateWithLifecycle()

    // Re-read the newest songs whenever the screen comes back and whenever
    // MediaStore changes under it (a download landing).
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(observer)
        val handler = Handler(Looper.getMainLooper())
        val bump = Runnable { tick++ }
        val media = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(bump)
                handler.postDelayed(bump, 1_500)
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, media)
        }
        onDispose {
            lifecycle.removeObserver(observer)
            handler.removeCallbacks(bump)
            runCatching { context.contentResolver.unregisterContentObserver(media) }
        }
    }
    val recentlyAdded by produceState<List<HistoryEntry>>(emptyList(), tick) {
        value = RecentlyAdded.load(context, limit = 20)
    }

    val recent = remember(history) { history.values.sortedByDescending { it.lastPlayedAt } }
    val mostPlayed = remember(history) {
        history.values.filter { it.playCount >= 2 }.sortedWith(
            compareByDescending<HistoryEntry> { it.playCount }.thenByDescending { it.lastPlayedAt },
        ).take(20)
    }
    val continueListening = remember(podcasts, positions, playedAt) {
        podcasts.flatMap { p -> p.episodes.map { p to it } }
            .filter { (_, e) -> (positions[e.guid] ?: 0L) > 5_000L && !store.isFinished(e) }
            .sortedByDescending { (_, e) -> playedAt[e.guid] ?: 0L }
            .take(10)
            .map { (p, e) -> ContinueItem(p, e, positions[e.guid] ?: 0L) }
    }
    val newEpisodes = remember(podcasts, positions) {
        val since = System.currentTimeMillis() - 14L * 24 * 3600 * 1000
        podcasts.flatMap { p -> p.episodes.map { p to it } }
            .filter { (_, e) -> e.publishedAt >= since && (positions[e.guid] ?: 0L) == 0L }
            .sortedByDescending { (_, e) -> e.publishedAt }
            .take(10)
    }
    val nothingYet = recent.isEmpty() && recentlyAdded.isEmpty() && continueListening.isEmpty() && newEpisodes.isEmpty()

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(rememberNestedScrollInteropConnection()),
            contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    greeting(),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item { Shortcuts(context) }

            if (recent.isNotEmpty()) {
                item { QuickGrid(recent.take(6)) { i -> ListeningHistory.play(context, recent.take(6), i) } }
            }
            if (continueListening.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.home_continue)) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(continueListening, key = { it.episode.guid }) { item ->
                            ContinueCard(item) { PodcastPlayer.play(context, item.podcast, item.episode) }
                        }
                    }
                }
            }
            if (recentlyAdded.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.home_recently_added)) }
                item {
                    SongRow(recentlyAdded) { i -> ListeningHistory.play(context, recentlyAdded, i) }
                }
            }
            if (mostPlayed.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.home_most_played)) }
                item { SongRow(mostPlayed, showPlays = true) { i -> ListeningHistory.play(context, mostPlayed, i) } }
            }
            if (newEpisodes.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.home_new_episodes)) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(newEpisodes, key = { it.second.guid }) { (p, e) ->
                            EpisodeCard(p, e) {
                                if (e.streamable || store.downloadedFile(e.guid) != null) {
                                    PodcastPlayer.play(context, p, e)
                                } else {
                                    context.startActivity(Intent(context, PodcastsActivity::class.java))
                                }
                            }
                        }
                    }
                }
            }
            if (nothingYet) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.home_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun greeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
}

@Composable
private fun Shortcuts(context: Context) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            AssistChip(
                onClick = { context.startActivity(Intent(context, DownloaderActivity::class.java)) },
                label = { Text(stringResource(R.string.home_add_songs)) },
                leadingIcon = { Icon(Icons.Default.Download, null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
        item {
            AssistChip(
                onClick = { context.startActivity(Intent(context, PodcastsActivity::class.java)) },
                label = { Text(stringResource(R.string.home_podcasts)) },
                leadingIcon = { Icon(Icons.Default.Podcasts, null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
        item {
            AssistChip(
                onClick = { context.startActivity(Intent(context, HistoryActivity::class.java)) },
                label = { Text(stringResource(R.string.home_history)) },
                leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** Spotify's top grid: two columns of compact tiles for the last few songs. */
@Composable
private fun QuickGrid(entries: List<HistoryEntry>, onPlay: (Int) -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { col, entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onPlay(row * 2 + col) },
                    ) {
                        SongArt(entry.artworkUri, Modifier.size(56.dp), corner = 0)
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp),
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SongRow(entries: List<HistoryEntry>, showPlays: Boolean = false, onPlay: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(entries.size, key = { entries[it].mediaId }) { i ->
            val entry = entries[i]
            Column(
                modifier = Modifier
                    .width(140.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPlay(i) },
            ) {
                SongArt(entry.artworkUri, Modifier.size(140.dp), corner = 8)
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val sub = if (showPlays) "${entry.playCount} plays" else entry.artist.orEmpty()
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SongArt(uri: String?, modifier: Modifier, corner: Int) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ContinueCard(item: ContinueItem, onPlay: () -> Unit) {
    val durationMs = item.episode.durationSeconds * 1000L
    Column(
        modifier = Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onPlay),
    ) {
        Cover(
            url = item.episode.imageUrl ?: item.podcast.imageUrl,
            size = 160.dp,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            corner = 8.dp,
        )
        if (durationMs > 0) {
            LinearProgressIndicator(
                progress = { (item.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                drawStopIndicator = {},
            )
        }
        Text(
            item.episode.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        val left = if (durationMs > 0) ((durationMs - item.positionMs) / 60_000L).coerceAtLeast(1) else null
        Text(
            listOfNotNull(item.podcast.title, left?.let { "$it min left" }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun EpisodeCard(podcast: Podcast, episode: Episode, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen),
    ) {
        Cover(
            url = episode.imageUrl ?: podcast.imageUrl,
            size = 160.dp,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            corner = 8.dp,
        )
        Text(
            episode.title,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            podcast.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
