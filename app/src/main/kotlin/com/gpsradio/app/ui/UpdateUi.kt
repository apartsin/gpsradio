package com.gpsradio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.gpsradio.app.platform.UpdateState
import com.gpsradio.core.update.UpdateInfo
import androidx.compose.ui.res.stringResource
import com.gpsradio.app.R

private fun UpdateInfo.label() = "GPS Radio $version" + (notes.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")

/** Settings section: installed version, check, download progress and install. */
@Composable
fun UpdatesSection(
    state: UpdateState,
    onCheck: () -> Unit,
    onInstall: (UpdateInfo) -> Unit,
    onAllowInstalls: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.updates), style = MaterialTheme.typography.titleMedium)
        Text(
            versionLabel(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag("appVersion"),
        )
        val line = when (state) {
            UpdateState.Idle -> null
            UpdateState.Checking -> stringResource(R.string.update_checking)
            UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
            is UpdateState.Available -> stringResource(R.string.update_available_line, state.info.label())
            is UpdateState.Downloading -> stringResource(R.string.update_downloading, state.info.version, (state.progress * 100).toInt())
            is UpdateState.NeedsPermission -> stringResource(R.string.update_needs_permission)
            is UpdateState.Installing -> stringResource(R.string.update_installing_line, state.info.version)
            is UpdateState.Failed -> state.message
        }
        line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("updateStatus")) }
        if (state is UpdateState.Downloading) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        val info = when (state) {
            is UpdateState.Available -> state.info
            is UpdateState.NeedsPermission -> state.info
            is UpdateState.Failed -> state.info
            else -> null
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state is UpdateState.NeedsPermission) OutlinedButton(onClick = onAllowInstalls) { Text(stringResource(R.string.allow_installs)) }
            if (info != null) Button(onClick = { onInstall(info) }) { Text(stringResource(R.string.install_version, info.version)) }
            val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
            if (info == null) OutlinedButton(onClick = onCheck, enabled = !busy) { Text(stringResource(R.string.check_for_updates)) }
        }
    }
}

/** A small card on the radio screen when a tested update is ready (never shown while driving). */
@Composable
fun UpdateBanner(
    state: UpdateState,
    onInstall: (UpdateInfo) -> Unit,
    onAllowInstalls: () -> Unit,
    onInstallManually: (UpdateInfo) -> Unit = onInstall,
) {
    val info = when (state) {
        is UpdateState.Available -> state.info
        is UpdateState.NeedsPermission -> state.info
        is UpdateState.Downloading -> state.info
        is UpdateState.Installing -> state.info
        is UpdateState.Failed -> state.info ?: return
        else -> return
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("updateBanner"),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SystemUpdate, contentDescription = null)
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(stringResource(R.string.update_banner_title, info.version), style = MaterialTheme.typography.titleSmall)
                when (state) {
                    is UpdateState.Downloading -> LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    is UpdateState.Installing -> Text(stringResource(R.string.installing), style = MaterialTheme.typography.bodySmall)
                    is UpdateState.NeedsPermission -> Text(stringResource(R.string.allow_installs_hint), style = MaterialTheme.typography.bodySmall)
                    is UpdateState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    else -> info.notes.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                }
            }
            when (state) {
                is UpdateState.Available -> TextButton(onClick = { onInstall(info) }) { Text(stringResource(R.string.install)) }
                is UpdateState.NeedsPermission -> TextButton(onClick = onAllowInstalls) { Text(stringResource(R.string.allow)) }
                is UpdateState.Failed -> Column {
                    TextButton(onClick = { onInstall(info) }) { Text(stringResource(R.string.retry)) }
                    TextButton(onClick = { onInstallManually(info) }) { Text(stringResource(R.string.install_manually)) }
                }
                else -> Unit
            }
        }
    }
}

/** Offered on start when a newer tested build exists (spec B §36). */
@Composable
fun UpdatePrompt(info: UpdateInfo, onUpdate: () -> Unit, onLater: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.update_prompt_title)) },
        text = { Text(stringResource(R.string.update_prompt_text, info.label())) },
        confirmButton = { TextButton(onClick = onUpdate) { Text(stringResource(R.string.update_now)) } },
        dismissButton = { TextButton(onClick = onLater) { Text(stringResource(R.string.later)) } },
    )
}
