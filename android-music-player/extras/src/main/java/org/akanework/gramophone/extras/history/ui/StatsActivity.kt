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

package org.akanework.gramophone.extras.history.ui

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import org.akanework.gramophone.extras.R
import org.akanework.gramophone.extras.history.HistoryEntry
import org.akanework.gramophone.extras.history.ListeningHistory
import org.akanework.gramophone.extras.history.PlayLog
import org.akanework.gramophone.extras.history.Stats
import org.akanework.gramophone.extras.ui.ExtrasTheme
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Your stats": a month (or all time) of listening, Wrapped-style. */
class StatsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ExtrasTheme { StatsScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsScreen() {
    val context = LocalContext.current
    var monthOffset by remember { mutableIntStateOf(0) }
    var allTime by remember { mutableStateOf(false) }
    val history by ListeningHistory.entries.collectAsStateWithLifecycle()

    val data by produceState<Pair<Stats.Summary, Map<String, String?>>?>(null, monthOffset, allTime, history) {
        val plays = PlayLog.plays(context)
        val listened = PlayLog.listenedByDay(context)
        val (from, to) = if (allTime) 0L to Long.MAX_VALUE else Stats.monthRange(System.currentTimeMillis(), monthOffset)
        var summary = Stats.compute(plays, listened, from, to)
        if (allTime) summary = Stats.withHistory(summary, history.values.toList())
        value = summary to history.mapValues { it.value.artworkUri }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title)) },
                navigationIcon = {
                    IconButton(onClick = { (context as? Activity)?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { allTime = false; monthOffset-- }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                    }
                    Text(
                        if (allTime) stringResource(R.string.stats_all_time)
                        else SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                            .format(Date(Stats.monthRange(System.currentTimeMillis(), monthOffset).first)),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { allTime = false; monthOffset++ }, enabled = allTime || monthOffset < 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                    FilterChip(selected = allTime, onClick = { allTime = !allTime }, label = { Text(stringResource(R.string.stats_all_time)) })
                }
            }
            val (s, art) = data ?: return@LazyColumn
            if (s.plays == 0 && s.minutes == 0L) {
                item {
                    Text(
                        stringResource(R.string.stats_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
                return@LazyColumn
            }
            item { Hero(s) }
            if (s.topArtists.isNotEmpty()) {
                item { Section(stringResource(R.string.stats_top_artists)) }
                item { RankedList(s.topArtists, art = null, history = history) }
            }
            if (s.topSongs.isNotEmpty()) {
                item { Section(stringResource(R.string.stats_top_songs)) }
                item { RankedList(s.topSongs, art = art, history = history) }
            }
            if (s.topAlbums.isNotEmpty()) {
                item { Section(stringResource(R.string.stats_top_albums)) }
                item { RankedList(s.topAlbums, art = null, history = history) }
            }
            if (s.byHour.any { it > 0 }) {
                item { Section(stringResource(R.string.stats_when)) }
                item { HourChart(s.byHour) }
                item { WeekChart(s.byWeekday) }
            }
            if (s.longestStreak > 1) {
                item {
                    Text(
                        stringResource(R.string.stats_streak, s.longestStreak),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero(s: Stats.Summary) {
    val nf = NumberFormat.getIntegerInstance()
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), MaterialTheme.colorScheme.surfaceContainerHigh),
                ),
            ),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.stats_minutes_label), style = MaterialTheme.typography.labelLarge)
                Text(
                    nf.format(s.minutes),
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black),
                )
                if (s.minutes >= 120) {
                    Text(
                        stringResource(R.string.stats_hours, nf.format(s.minutes / 60)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 12.dp)) {
                    Stat(nf.format(s.plays), stringResource(R.string.stats_plays))
                    Stat(nf.format(s.distinctSongs), stringResource(R.string.stats_songs))
                    Stat(nf.format(s.distinctArtists), stringResource(R.string.stats_artists))
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun RankedList(items: List<Stats.Ranked>, art: Map<String, String?>?, history: Map<String, HistoryEntry>) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { i, r ->
            val entry = r.mediaId?.let { history[it] }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = entry != null) {
                        val playable = items.mapNotNull { it.mediaId?.let(history::get) }
                        ListeningHistory.play(context, playable, playable.indexOf(entry).coerceAtLeast(0))
                    },
            ) {
                Text(
                    "${i + 1}",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                    color = if (i == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp),
                )
                if (art != null) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        art[r.mediaId]?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    r.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(
                    "${r.count}×",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HourChart(byHour: IntArray) {
    val max = (byHour.maxOrNull() ?: 0).coerceAtLeast(1)
    val peak = byHour.indices.maxByOrNull { byHour[it] } ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            byHour.forEachIndexed { h, n ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight((n.toFloat() / max).coerceAtLeast(0.03f))
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (h == peak) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("12a", "6a", "12p", "6p").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
        val label = when (peak) { 0 -> "12 AM"; in 1..11 -> "$peak AM"; 12 -> "12 PM"; else -> "${peak - 12} PM" }
        Text(stringResource(R.string.stats_peak_hour, label), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WeekChart(byWeekday: IntArray) {
    val max = (byWeekday.maxOrNull() ?: 0).coerceAtLeast(1)
    val days = listOf("M", "T", "W", "T", "F", "S", "S")
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        byWeekday.forEachIndexed { d, n ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(56.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight((n.toFloat() / max).coerceAtLeast(0.05f))
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (n == max && n > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                }
                Text(days[d], style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
