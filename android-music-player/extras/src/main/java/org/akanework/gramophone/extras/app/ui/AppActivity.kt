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

package org.akanework.gramophone.extras.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.akanework.gramophone.extras.R
import org.akanework.gramophone.extras.app.Backup
import org.akanework.gramophone.extras.app.ExtrasVersion
import org.akanework.gramophone.extras.app.UpdateChecker
import org.akanework.gramophone.extras.app.Releases
import org.akanework.gramophone.extras.ui.ExtrasTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings › Updates & backup. */
class AppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ExtrasTheme { AppScreen() } }
    }
}

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Offline : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Releases.Release) : UpdateState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }

    var update by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var autoCheck by remember { mutableStateOf(true) }
    var autoBackup by remember { mutableStateOf(true) }
    var lastBackup by remember { mutableStateOf(0L) }
    var pendingRestore by remember { mutableStateOf<Pair<String, Backup.Summary>?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            autoCheck = UpdateChecker.autoCheckEnabled(context)
            autoBackup = Backup.autoBackupEnabled(context)
            lastBackup = Backup.lastAutoBackup(context)
        }
    }

    fun check() {
        update = UpdateState.Checking
        scope.launch {
            val latest = runCatching { UpdateChecker.latest() }.getOrNull()
            update = when {
                latest == null -> UpdateState.Offline
                Releases.isNewer(latest.version, ExtrasVersion.NAME) -> UpdateState.Available(latest)
                else -> UpdateState.UpToDate
            }
        }
    }
    LaunchedEffect(Unit) { check() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val ok = runCatching {
                val text = Backup.export(context)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                }
            }.isSuccess
            busy = false
            snackbars.showSnackbar(context.getString(if (ok) R.string.app_backup_saved else R.string.app_backup_failed))
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                }
                text to Backup.inspect(text)
            }.onSuccess { pendingRestore = it }
                .onFailure { snackbars.showSnackbar(it.message ?: context.getString(R.string.app_backup_failed)) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_title)) },
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.app_updates), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.app_version, ExtrasVersion.NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                when (val u = update) {
                    UpdateState.Idle, UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.app_checking), modifier = Modifier.padding(start = 12.dp))
                    }
                    UpdateState.Offline -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.app_check_failed), modifier = Modifier.weight(1f))
                        TextButton(onClick = { check() }) { Text(stringResource(R.string.app_check_again)) }
                    }
                    UpdateState.UpToDate -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.app_up_to_date), color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { check() }) { Text(stringResource(R.string.app_check_again)) }
                    }
                    is UpdateState.Available -> Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.app_update_available, u.release.version),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                u.release.notes.replace("**", "").replace("## ", "").trim(),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 14,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                stringResource(R.string.app_install_over),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                u.release.apkUrl?.let { apk ->
                                    Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apk))) }) {
                                        Text(stringResource(R.string.app_download_apk))
                                    }
                                }
                                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.release.pageUrl))) }) {
                                    Text(stringResource(R.string.app_release_page))
                                }
                            }
                        }
                    }
                }
            }
            item {
                SwitchRow(stringResource(R.string.app_auto_check), stringResource(R.string.app_auto_check_summary), autoCheck) {
                    autoCheck = it
                    scope.launch(Dispatchers.IO) { UpdateChecker.setAutoCheck(context, it) }
                }
            }
            item { HorizontalDivider() }
            item {
                Text(stringResource(R.string.app_backup), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.app_backup_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = {
                        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        exportLauncher.launch("gramophone-extras-backup-$stamp.json")
                    }) { Text(stringResource(R.string.app_backup_now)) }
                    OutlinedButton(enabled = !busy, onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Text(stringResource(R.string.app_restore))
                    }
                }
            }
            item {
                SwitchRow(
                    stringResource(R.string.app_auto_backup),
                    if (lastBackup > 0) stringResource(
                        R.string.app_auto_backup_last,
                        DateUtils.getRelativeTimeSpanString(lastBackup, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                    ) else stringResource(R.string.app_auto_backup_summary),
                    autoBackup,
                ) {
                    autoBackup = it
                    scope.launch(Dispatchers.IO) { Backup.setAutoBackup(context, it) }
                }
            }
        }
    }

    pendingRestore?.let { (text, summary) ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.app_restore_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.app_restore_body,
                        DateUtils.getRelativeTimeSpanString(summary.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                        summary.shows,
                        summary.historyEntries,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    scope.launch {
                        runCatching { Backup.restore(context, text) }
                            .onSuccess { Backup.restartApp(context) }
                            .onFailure { snackbars.showSnackbar(it.message ?: context.getString(R.string.app_backup_failed)) }
                    }
                }) { Text(stringResource(R.string.app_restore_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
