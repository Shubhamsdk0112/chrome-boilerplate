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

package org.akanework.gramophone.extras.filter.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.akanework.gramophone.extras.R
import org.akanework.gramophone.extras.filter.AiClassifier
import org.akanework.gramophone.extras.filter.FilterStore
import org.akanework.gramophone.extras.filter.FilterWatcher
import org.akanework.gramophone.extras.filter.Judgement
import org.akanework.gramophone.extras.filter.LibraryScanner

/**
 * The library filter screen: switch it on, scan, and see exactly what was
 * hidden and why — with a one-tap way to put anything back.
 *
 * The review list matters more than it looks. A filter that hides things
 * without showing its working is impossible to trust, so every hidden file
 * carries its reason and can be overridden individually.
 */
class FilterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FilterTheme { FilterScreen() } }
    }
}

@Composable
private fun FilterTheme(content: @Composable () -> Unit) {
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
private fun FilterScreen() {
    val context = LocalContext.current
    val store = remember { FilterStore(context) }
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(store.enabled) }
    var aiEnabled by remember { mutableStateOf(store.aiEnabled) }
    var autoRescan by remember { mutableStateOf(store.autoRescan) }
    var apiKey by remember { mutableStateOf(store.apiKey) }
    var model by remember { mutableStateOf(store.model) }
    var options by remember { mutableStateOf(store.options) }

    var progress by remember { mutableStateOf<LibraryScanner.Progress?>(null) }
    var result by remember { mutableStateOf<LibraryScanner.Result?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // Paths the user has just restored. Kept separately so the row disappears
    // immediately instead of waiting for the next scan to rebuild the list.
    val restored = remember { mutableStateListOf<String>() }

    // Restoring files one tap at a time would otherwise rewrite the shared
    // blacklist on every tap, and each of those writes makes Gramophone re-read
    // the entire library. Collapse a burst of taps into a single write.
    LaunchedEffect(restored.size) {
        if (restored.isEmpty()) return@LaunchedEffect
        delay(700)
        store.hiddenPaths = store.hiddenPaths - restored.toSet()
    }

    fun rescan() {
        scope.launch {
            error = null
            progress = LibraryScanner.Progress(LibraryScanner.Stage.QUERYING)
            restored.clear()
            runCatching { LibraryScanner(context).scan { progress = it } }
                .onSuccess { result = it }
                .onFailure { error = it.message ?: it::class.java.simpleName }
            progress = null
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.filter_title)) }) },
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.filter_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                SwitchRow(
                    title = stringResource(R.string.filter_enable),
                    subtitle = stringResource(R.string.filter_enable_summary),
                    checked = enabled,
                    onChange = {
                        enabled = it
                        store.enabled = it
                        // Turning it off must put everything back immediately,
                        // without needing another scan.
                        if (!it) {
                            store.unhideAll()
                            result = null
                        }
                        FilterWatcher.ensureStarted(context)
                    },
                )
            }

            if (enabled) {
                item { HorizontalDivider() }

                item {
                    Text(
                        stringResource(R.string.filter_what_to_hide),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                item {
                    SwitchRow(
                        title = stringResource(R.string.filter_voice_notes),
                        subtitle = stringResource(R.string.filter_voice_notes_summary),
                        checked = options.hideVoiceNotes,
                        onChange = {
                            options = options.copy(hideVoiceNotes = it); store.options = options
                        },
                    )
                }
                item {
                    SwitchRow(
                        title = stringResource(R.string.filter_ringtones),
                        checked = options.hideRingtonesAndAlarms,
                        onChange = {
                            options = options.copy(hideRingtonesAndAlarms = it)
                            store.options = options
                        },
                    )
                }
                item {
                    SwitchRow(
                        title = stringResource(R.string.filter_video),
                        checked = options.hideVideoFiles,
                        onChange = {
                            options = options.copy(hideVideoFiles = it); store.options = options
                        },
                    )
                }
                item {
                    SwitchRow(
                        title = stringResource(R.string.filter_short),
                        subtitle = stringResource(R.string.filter_short_summary),
                        checked = options.hideShortClips,
                        onChange = {
                            options = options.copy(hideShortClips = it); store.options = options
                        },
                    )
                }
                item {
                    SwitchRow(
                        title = stringResource(R.string.filter_podcasts),
                        subtitle = stringResource(R.string.filter_podcasts_summary),
                        checked = options.hidePodcastsAndAudiobooks,
                        onChange = {
                            options = options.copy(hidePodcastsAndAudiobooks = it)
                            store.options = options
                        },
                    )
                }

                item {
                    SwitchRow(
                        title = stringResource(R.string.filter_auto),
                        subtitle = stringResource(R.string.filter_auto_summary),
                        checked = autoRescan,
                        onChange = {
                            autoRescan = it
                            store.autoRescan = it
                            FilterWatcher.ensureStarted(context)
                        },
                    )
                }

                item { HorizontalDivider() }

                // --- AI section ---------------------------------------
                item {
                    SwitchRow(
                        title = stringResource(R.string.filter_ai),
                        subtitle = stringResource(R.string.filter_ai_summary),
                        checked = aiEnabled,
                        onChange = { aiEnabled = it; store.aiEnabled = it },
                    )
                }
                if (aiEnabled) {
                    item {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it; store.apiKey = it },
                            label = { Text(stringResource(R.string.filter_api_key)) },
                            supportingText = { Text(stringResource(R.string.filter_api_key_help)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it; store.model = it },
                            label = { Text(stringResource(R.string.filter_model)) },
                            supportingText = {
                                Text(stringResource(R.string.filter_model_help, AiClassifier.DEFAULT_MODEL))
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Text(
                            text = stringResource(R.string.filter_privacy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item { HorizontalDivider() }

                // --- actions ------------------------------------------
                item {
                    val running = progress != null
                    Button(
                        onClick = { rescan() },
                        enabled = !running,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (running) stringResource(R.string.filter_scanning)
                            else stringResource(R.string.filter_scan)
                        )
                    }
                }

                progress?.let { p ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                progress = {
                                    if (p.total > 0) p.done.toFloat() / p.total else 0f
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = when (p.stage) {
                                    LibraryScanner.Stage.QUERYING ->
                                        stringResource(R.string.filter_stage_querying)
                                    LibraryScanner.Stage.CLASSIFYING ->
                                        stringResource(R.string.filter_stage_classifying, p.done, p.total)
                                    LibraryScanner.Stage.ASKING_AI ->
                                        stringResource(R.string.filter_stage_ai, p.done, p.total)
                                    LibraryScanner.Stage.SAVING ->
                                        stringResource(R.string.filter_stage_saving)
                                    LibraryScanner.Stage.DONE ->
                                        stringResource(R.string.filter_stage_saving)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                error?.let {
                    item {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                result?.let { r ->
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    stringResource(R.string.filter_result_hidden, r.hidden, r.total),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    stringResource(R.string.filter_result_kept, r.kept, r.unsure),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (r.askedAi > 0) {
                                    Text(
                                        stringResource(R.string.filter_result_ai, r.aiAnswered, r.askedAi),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }

                    item {
                        OutlinedButton(
                            onClick = {
                                store.unhideAll()
                                store.clearManualOverrides()
                                restored.clear()
                                result = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.filter_unhide_all)) }
                    }

                    val hiddenEntries = r.entries.filter {
                        it.verdict.judgement == Judgement.JUNK &&
                            it.candidate.path !in restored
                    }
                    if (hiddenEntries.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.filter_review),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        items(hiddenEntries, key = { it.candidate.id }) { entry ->
                            HiddenRow(
                                name = entry.candidate.displayName,
                                reason = entry.verdict.reason,
                                onKeep = {
                                    // Only the private override is written here.
                                    // The shared hidden set is updated by the
                                    // debounced effect above, because every
                                    // write to it reloads the whole library.
                                    store.setManualOverride(entry.candidate.path, Judgement.MUSIC)
                                    restored += entry.candidate.path
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun HiddenRow(name: String, reason: String, onKeep: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onKeep) { Text(stringResource(R.string.filter_keep)) }
    }
}
