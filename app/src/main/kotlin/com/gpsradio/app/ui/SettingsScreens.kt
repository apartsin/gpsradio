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
import com.gpsradio.core.lang.Languages
import com.gpsradio.core.model.Topic

private const val AUTO = "auto"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(settings: AppSettings, onSave: (AppSettings) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Welcome to GPS Radio") }) }) { pad ->
        SettingsForm(
            initial = settings,
            modifier = Modifier.padding(pad),
            intro = "GPS Radio tells you stories about the places around you and answers your questions by voice.\n\n" +
                "It talks to OpenAI directly from this phone using your own API key. The key is stored encrypted " +
                "on this device only. Tip: create a dedicated key with a monthly spending limit at platform.openai.com.",
            saveLabel = "Start",
            showAdvanced = false,
            onSave = onSave,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, onSave: (AppSettings) -> Unit, onClearHistory: () -> Unit, onBack: () -> Unit) {
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
            extra = {
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
) {
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var language by remember { mutableStateOf(if (initial.languageAuto) AUTO else initial.preferredLanguage) }
    var interests by remember { mutableStateOf(initial.interests) }
    var narrationModel by remember { mutableStateOf(initial.models.narrationModel) }
    var conversationModel by remember { mutableStateOf(initial.models.conversationModel) }
    var ttsModel by remember { mutableStateOf(initial.models.ttsModel) }
    var ttsVoice by remember { mutableStateOf(initial.models.ttsVoice) }
    var sttModel by remember { mutableStateOf(initial.models.transcriptionModel) }

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
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        LanguagePicker(language) { language = it }

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
            extra()
        }

        Spacer(Modifier.height(4.dp))
        Row {
            Button(
                enabled = apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onSave(
                        initial.copy(
                            apiKey = apiKey.trim(),
                            languageAuto = language == AUTO,
                            preferredLanguage = if (language == AUTO) initial.preferredLanguage else language,
                            interests = interests,
                            models = initial.models.copy(
                                narrationModel = narrationModel.trim().ifBlank { initial.models.narrationModel },
                                conversationModel = conversationModel.trim().ifBlank { initial.models.conversationModel },
                                ttsModel = ttsModel.trim().ifBlank { initial.models.ttsModel },
                                ttsVoice = ttsVoice.trim().ifBlank { initial.models.ttsVoice },
                                transcriptionModel = sttModel.trim().ifBlank { initial.models.transcriptionModel },
                            ),
                        ),
                    )
                },
            ) { Text(saveLabel) }
        }
    }
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
