package com.gpsradio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gpsradio.app.R
import com.gpsradio.app.platform.DeviceInfo
import com.gpsradio.app.platform.LocalModelCatalog
import com.gpsradio.app.platform.LocalModels
import com.gpsradio.app.platform.ModelDownload
import com.gpsradio.app.platform.NanoState

/** What Settings shows about the on-device story writers (spec A §69), and what it can ask for. */
data class LocalAiUi(
    val nano: NanoState = NanoState.UNKNOWN,
    val installed: Set<String> = emptySet(),
    val downloads: Map<String, ModelDownload> = emptyMap(),
    val onDownload: (String) -> Unit = {},
    val onCancel: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val device: DeviceInfo? = null,
)

@Composable
fun rememberLocalAi(models: LocalModels): LocalAiUi {
    val nano by models.nano.collectAsStateWithLifecycle()
    val installed by models.installed.collectAsStateWithLifecycle()
    val downloads by models.downloads.collectAsStateWithLifecycle()
    var device by remember { mutableStateOf<DeviceInfo?>(null) }
    LaunchedEffect(models) {
        device = runCatching { models.deviceInfo() }.getOrNull()
        models.refreshNano()
    }
    return LocalAiUi(nano, installed, downloads, models::download, models::cancel, models::delete, device)
}

/** "Stories without OpenAI": which on-device model writes them, and its download. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelPicker(choice: String, onChoice: (String) -> Unit, ai: LocalAiUi) {
    ai.device?.let { d ->
        Text(stringResource(R.string.device_check_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.device_check_line, d.android, "%.1f".format(java.util.Locale.US, d.ramGb), "%.1f".format(java.util.Locale.US, d.freeGb)),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(if (d.aiCore) R.string.device_check_aicore_yes else R.string.device_check_aicore_no),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.device_check_recommend, "${d.recommended.label} (${gb(d.recommended.sizeMb)})"),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val autoLabel = stringResource(R.string.local_model_auto)
    val offLabel = stringResource(R.string.local_model_off)
    fun label(id: String): String = when (id) {
        "auto" -> autoLabel
        "off" -> offLabel
        LocalModels.NANO -> "Gemini Nano"
        else -> LocalModelCatalog.byId(id)?.let { "${it.label} (${gb(it.sizeMb)})" } ?: id
    }
    val options = listOf("auto", LocalModels.NANO) + LocalModelCatalog.all.map { it.id } + "off"
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label(choice),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.local_model_label)) },
            supportingText = { Text(stringResource(R.string.local_model_hint)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable).testTag("localModel"),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { id ->
                DropdownMenuItem(text = { Text(label(id)) }, onClick = { onChoice(id); expanded = false })
            }
        }
    }

    // Gemini Nano: built into some phones; AICore may need to fetch it first.
    if (choice == "auto" || choice == LocalModels.NANO) {
        Text(
            when (ai.nano) {
                NanoState.UNKNOWN -> stringResource(R.string.nano_checking)
                NanoState.AVAILABLE -> stringResource(R.string.nano_ready)
                NanoState.DOWNLOADABLE -> stringResource(R.string.nano_downloadable)
                NanoState.DOWNLOADING -> stringResource(R.string.nano_downloading)
                NanoState.UNAVAILABLE -> stringResource(R.string.nano_unavailable)
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (ai.nano == NanoState.DOWNLOADABLE) {
            OutlinedButton(onClick = { ai.onDownload(LocalModels.NANO) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.nano_download))
            }
        }
    }

    // A downloadable open model: the chosen one, or under "auto" the recommended one while Nano isn't there.
    val spec = LocalModelCatalog.byId(choice)
        ?: (ai.device?.recommended ?: LocalModelCatalog.all.first()).takeIf { choice == "auto" && ai.nano != NanoState.AVAILABLE && ai.installed.isEmpty() }
        ?: LocalModelCatalog.all.firstOrNull { choice == "auto" && it.id in ai.installed }
        ?: return
    val download = ai.downloads[spec.id]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            spec.id in ai.installed -> {
                Text(stringResource(R.string.local_model_ready, spec.label), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { ai.onDelete(spec.id) }, modifier = Modifier.fillMaxWidth().testTag("localModelDelete")) {
                    Text(stringResource(R.string.local_model_delete, gb(spec.sizeMb)))
                }
            }
            download != null && download.error == null -> {
                val f = download.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.local_model_downloading, spec.label, f?.let { "${(it * 100).toInt()}%" } ?: "…"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { ai.onCancel(spec.id) }) { Text(stringResource(R.string.local_model_cancel)) }
                }
            }
            else -> {
                download?.error?.let {
                    Text(stringResource(R.string.local_model_failed, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text(stringResource(R.string.local_model_download_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { ai.onDownload(spec.id) }, modifier = Modifier.fillMaxWidth().testTag("localModelDownload")) {
                    Text(stringResource(R.string.local_model_download, spec.label, gb(spec.sizeMb)))
                }
            }
        }
    }
}

private fun gb(mb: Int): String = if (mb >= 1000) "%.1f GB".format(java.util.Locale.US, mb / 1000.0) else "$mb MB"
