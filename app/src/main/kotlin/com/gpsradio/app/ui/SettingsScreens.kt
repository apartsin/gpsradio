package com.gpsradio.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Switch
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gpsradio.app.data.AppSettings
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.lang.Languages
import com.gpsradio.core.memory.MemoryItem
import com.gpsradio.core.model.Topic

private const val AUTO = "auto"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    /** Keyless preview: start listening with Wikipedia facts read by the phone's own voice. */
    onTryWithoutKey: (AppSettings) -> Unit = {},
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Welcome to GPS Radio") }) }) { pad ->
        SettingsForm(
            initial = settings,
            modifier = Modifier.padding(pad),
            intro = "Stories about the places around you, told as you go — with fun facts, history, the odd joke, " +
                "and answers to anything you ask.\n\n" +
                "GPS Radio talks to OpenAI directly from this phone using your own API key. The key stays encrypted " +
                "on this device only. Tip: create a dedicated key with a monthly spending limit at platform.openai.com.",
            saveLabel = "Save and start listening",
            showAdvanced = false,
            onSave = onSave,
            secondaryLabel = "Try without a key (on-device voice)",
            secondaryHint = "Short notes from Wikipedia, read by your phone's voice. Questions need a key.",
            onSecondary = { onTryWithoutKey(it.copy(apiKey = "", previewMode = true)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit,
    memory: List<MemoryItem> = emptyList(),
    onForgetMemory: (String) -> Unit = {},
    onForgetAllMemory: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        SettingsForm(
            initial = settings,
            modifier = Modifier.padding(pad),
            intro = null,
            saveLabel = "Save",
            showAdvanced = true,
            onSave = onSave,
            // In the keyless preview, other settings can be saved before a key is added.
            requireKey = !settings.previewMode,
            extra = {
                MemorySection(memory, onForgetMemory, onForgetAllMemory)
                OutlinedButton(onClick = onClearHistory, modifier = Modifier.fillMaxWidth()) {
                    Text("Forget stories I've already heard")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsForm(
    initial: AppSettings,
    modifier: Modifier,
    intro: String?,
    saveLabel: String,
    showAdvanced: Boolean,
    onSave: (AppSettings) -> Unit,
    extra: @Composable () -> Unit = {},
    requireKey: Boolean = true,
    secondaryLabel: String? = null,
    secondaryHint: String? = null,
    onSecondary: (AppSettings) -> Unit = {},
) {
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var language by remember { mutableStateOf(if (initial.languageAuto) AUTO else initial.preferredLanguage) }
    var interests by remember { mutableStateOf(initial.interests) }
    var narrationModel by remember { mutableStateOf(initial.models.narrationModel) }
    var conversationModel by remember { mutableStateOf(initial.models.conversationModel) }
    var ttsModel by remember { mutableStateOf(initial.models.ttsModel) }
    var ttsVoice by remember { mutableStateOf(initial.models.ttsVoice) }
    var sttModel by remember { mutableStateOf(initial.models.transcriptionModel) }
    var realtimeModel by remember { mutableStateOf(initial.models.realtimeModel) }
    var hostStyle by remember { mutableStateOf(initial.hostStyle) }
    var liveVoice by remember { mutableStateOf(initial.liveVoice) }
    var showKey by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        intro?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("OpenAI API key") },
            placeholder = { Text("sk-…") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showKey) "Hide key" else "Show key")
                }
            },
            supportingText = {
                if (apiKey.isNotBlank() && !apiKey.trim().startsWith("sk-")) Text("OpenAI keys usually start with \"sk-\"")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        LanguagePicker(language) { language = it }

        Text("Your host", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HostStyle.entries.forEach { h ->
                FilterChip(selected = hostStyle == h, onClick = { hostStyle = h }, label = { Text(h.label) })
            }
        }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Natural voice conversation", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Talk hands-free like a phone call; you can interrupt anytime. Uses the OpenAI Realtime API.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = liveVoice, onCheckedChange = { liveVoice = it })
        }

        Text("What are you into?", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Topic.entries.forEach { t ->
                FilterChip(
                    selected = t in interests,
                    onClick = { interests = if (t in interests) interests - t else interests + t },
                    label = { Text(t.key.replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        if (showAdvanced) {
            Text("Models", style = MaterialTheme.typography.titleSmall)
            ModelField("Narration model", narrationModel) { narrationModel = it }
            ModelField("Conversation model (uses web search)", conversationModel) { conversationModel = it }
            ModelField("Speech model", ttsModel) { ttsModel = it }
            ModelField("Voice", ttsVoice) { ttsVoice = it }
            ModelField("Transcription model", sttModel) { sttModel = it }
            ModelField("Realtime voice model", realtimeModel) { realtimeModel = it }
            extra()
        }

        fun collect() = initial.copy(
            apiKey = apiKey.trim(),
            languageAuto = language == AUTO,
            preferredLanguage = if (language == AUTO) initial.preferredLanguage else language,
            interests = interests,
            hostStyle = hostStyle,
            liveVoice = liveVoice,
            // Adding a key ends the keyless preview.
            previewMode = initial.previewMode && apiKey.isBlank(),
            models = initial.models.copy(
                narrationModel = narrationModel.trim().ifBlank { initial.models.narrationModel },
                conversationModel = conversationModel.trim().ifBlank { initial.models.conversationModel },
                ttsModel = ttsModel.trim().ifBlank { initial.models.ttsModel },
                ttsVoice = ttsVoice.trim().ifBlank { initial.models.ttsVoice },
                transcriptionModel = sttModel.trim().ifBlank { initial.models.transcriptionModel },
                realtimeModel = realtimeModel.trim().ifBlank { initial.models.realtimeModel },
            ),
        )

        Spacer(Modifier.height(4.dp))
        Row {
            Button(
                enabled = apiKey.isNotBlank() || !requireKey,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSave(collect()) },
            ) { Text(saveLabel) }
        }
        if (secondaryLabel != null) {
            OutlinedButton(onClick = { onSecondary(collect()) }, modifier = Modifier.fillMaxWidth()) { Text(secondaryLabel) }
            secondaryHint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** What the radio has learned about the listener; each item can be removed. */
@Composable
private fun MemorySection(memory: List<MemoryItem>, onForget: (String) -> Unit, onForgetAll: () -> Unit) {
    Text("What I remember about you", style = MaterialTheme.typography.titleSmall)
    if (memory.isEmpty()) {
        Text(
            "Nothing yet. Tell the radio what you like, e.g. \"I love castles\" or \"keep stories short\", and it will remember.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    memory.forEach { m ->
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                "${m.category.name.lowercase().replace('_', ' ')}: ${m.text}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onForget(m.id) }) { Icon(Icons.Default.Close, "Forget ${m.text}") }
        }
    }
    OutlinedButton(onClick = onForgetAll, modifier = Modifier.fillMaxWidth()) { Text("Forget everything about me") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (selected == AUTO) "Auto (phone language)" else Languages.displayName(selected)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Narration language") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Auto (phone language)") }, onClick = { onSelect(AUTO); expanded = false })
            Languages.supported.forEach { lang ->
                DropdownMenuItem(text = { Text(lang.displayName) }, onClick = { onSelect(lang.tag); expanded = false })
            }
        }
    }
}

@Composable
private fun ModelField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
