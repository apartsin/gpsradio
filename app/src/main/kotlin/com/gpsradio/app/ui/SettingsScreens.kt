package com.gpsradio.app.ui

import com.gpsradio.core.cost.CostMeter
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
import androidx.compose.ui.platform.testTag
import com.gpsradio.app.platform.OfflineVoiceInfo
import com.gpsradio.app.platform.TtsEngineOption
import com.gpsradio.app.platform.UpdateState
import com.gpsradio.core.update.UpdateInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gpsradio.app.data.AppSettings
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.editorial.Pacing
import com.gpsradio.core.lang.Languages
import com.gpsradio.core.memory.MemoryItem
import com.gpsradio.core.model.Topic
import androidx.compose.ui.res.stringResource
import com.gpsradio.app.R

private const val AUTO = "auto"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    /** Keyless preview: start listening with Wikipedia facts read by the phone's own voice. */
    onTryWithoutKey: (AppSettings) -> Unit = {},
) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.setup_title)) }) }) { pad ->
        SettingsForm(
            initial = settings,
            modifier = Modifier.padding(pad),
            intro = stringResource(R.string.setup_intro),
            saveLabel = stringResource(R.string.setup_save),
            showAdvanced = false,
            onSave = onSave,
            secondaryLabel = stringResource(R.string.setup_try_without_key),
            secondaryHint = stringResource(R.string.setup_try_without_key_hint),
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
    update: UpdateState = UpdateState.Idle,
    onCheckUpdate: () -> Unit = {},
    onInstallUpdate: (UpdateInfo) -> Unit = {},
    onAllowInstalls: () -> Unit = {},
    cost: CostMeter.Totals = CostMeter.Totals(),
    /** The phone's TextToSpeech engines and whether an offline voice for the language is installed. */
    offlineVoice: OfflineVoiceInfo = OfflineVoiceInfo(),
    /** Opens the engine's "install voice data" screen. */
    onInstallVoiceData: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { pad ->
        SettingsForm(
            initial = settings,
            modifier = Modifier.padding(pad),
            intro = null,
            saveLabel = stringResource(R.string.save),
            showAdvanced = true,
            onSave = onSave,
            // In the keyless preview, other settings can be saved before a key is added.
            requireKey = !settings.previewMode,
            budget = true,
            offlineVoice = offlineVoice,
            onInstallVoiceData = onInstallVoiceData,
            extra = {
                CostSection(cost, settings.dailyBudgetUsd)
                MemorySection(memory, onForgetMemory, onForgetAllMemory)
                OutlinedButton(onClick = onClearHistory, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.forget_heard_stories))
                }
                UpdatesSection(update, onCheckUpdate, onInstallUpdate, onAllowInstalls)
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
    budget: Boolean = false,
    requireKey: Boolean = true,
    secondaryLabel: String? = null,
    secondaryHint: String? = null,
    onSecondary: (AppSettings) -> Unit = {},
    offlineVoice: OfflineVoiceInfo = OfflineVoiceInfo(),
    onInstallVoiceData: () -> Unit = {},
) {
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var asrOnDevice by remember { mutableStateOf(initial.asrOnDevice) }
    var offlineTtsEngine by remember { mutableStateOf(initial.offlineTtsEngine) }
    var language by remember { mutableStateOf(if (initial.languageAuto) AUTO else initial.preferredLanguage) }
    var interests by remember { mutableStateOf(initial.interests) }
    var narrationModel by remember { mutableStateOf(initial.models.narrationModel) }
    var conversationModel by remember { mutableStateOf(initial.models.conversationModel) }
    var researchModel by remember { mutableStateOf(initial.models.researchModel) }
    var ttsModel by remember { mutableStateOf(initial.models.ttsModel) }
    var ttsVoice by remember { mutableStateOf(initial.models.ttsVoice) }
    var sttModel by remember { mutableStateOf(initial.models.transcriptionModel) }
    var realtimeModel by remember { mutableStateOf(initial.models.realtimeModel) }
    var hostStyle by remember { mutableStateOf(initial.hostStyle) }
    var liveVoice by remember { mutableStateOf(initial.liveVoice) }
    var alwaysListening by remember { mutableStateOf(initial.alwaysListening) }
    var soundEffects by remember { mutableStateOf(initial.soundEffects) }
    var localEvents by remember { mutableStateOf(initial.localEvents) }
    var pacing by remember { mutableStateOf(initial.pacing) }
    var budgetText by remember { mutableStateOf(if (initial.dailyBudgetUsd > 0) formatUsd(initial.dailyBudgetUsd) else "") }
    var showKey by remember { mutableStateOf(false) }
    val alwaysListeningLabel = stringResource(R.string.always_listening)
    val soundEffectsLabel = stringResource(R.string.sound_effects)
    val localEventsLabel = stringResource(R.string.local_events)

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
            label = { Text(stringResource(R.string.api_key_label)) },
            placeholder = { Text(if (initial.usingEmbeddedKey) stringResource(R.string.api_key_builtin_placeholder) else "sk-…") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(if (showKey) R.string.hide_key else R.string.show_key))
                }
            },
            supportingText = {
                if (apiKey.isNotBlank() && !apiKey.trim().startsWith("sk-")) Text(stringResource(R.string.api_key_format_hint))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        LanguagePicker(language) { language = it }

        Text(stringResource(R.string.your_host), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HostStyle.entries.forEach { h ->
                FilterChip(selected = hostStyle == h, onClick = { hostStyle = h }, label = { Text(hostStyleLabel(h)) })
            }
        }

        Text(stringResource(R.string.pacing), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pacing.entries.forEach { p ->
                FilterChip(selected = pacing == p, onClick = { pacing = p }, label = { Text(pacingLabel(p)) })
            }
        }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.natural_voice), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.natural_voice_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = liveVoice, onCheckedChange = { liveVoice = it })
        }

        if (liveVoice) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(alwaysListeningLabel, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.always_listening_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = alwaysListening,
                    onCheckedChange = { alwaysListening = it },
                    modifier = Modifier.semantics { contentDescription = alwaysListeningLabel },
                )
            }
        }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(soundEffectsLabel, style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.sound_effects_hint), style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = soundEffects,
                onCheckedChange = { soundEffects = it },
                modifier = Modifier.semantics { contentDescription = soundEffectsLabel },
            )
        }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(localEventsLabel, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.local_events_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = localEvents,
                onCheckedChange = { localEvents = it },
                modifier = Modifier.semantics { contentDescription = localEventsLabel },
            )
        }

        Text(stringResource(R.string.interests_title), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Topic.entries.forEach { t ->
                FilterChip(
                    selected = t in interests,
                    onClick = { interests = if (t in interests) interests - t else interests + t },
                    label = { Text(topicLabel(t)) },
                )
            }
        }

        if (budget) {
            val budgetDescription = stringResource(R.string.budget_description)
            OutlinedTextField(
                value = budgetText,
                onValueChange = { budgetText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                label = { Text(stringResource(R.string.budget_label)) },
                supportingText = { Text(stringResource(R.string.budget_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = budgetDescription },
            )
        }

        if (showAdvanced) {
            Text(stringResource(R.string.models), style = MaterialTheme.typography.titleSmall)
            ModelPicker(stringResource(R.string.model_narration), narrationModel, narrationOptions(), "modelNarration") { narrationModel = it }
            ModelPicker(stringResource(R.string.model_conversation), conversationModel, conversationOptions(), "modelConversation") { conversationModel = it }
            ModelPicker(stringResource(R.string.model_research), researchModel, researchOptions(), "modelResearch") { researchModel = it }
            ModelPicker(stringResource(R.string.model_speech), ttsModel, ttsOptions(), "modelSpeech") { ttsModel = it }
            val voiceWarning = if (ttsModel.trim() in TTS1_MODELS && ttsVoice.trim().lowercase() !in TTS1_VOICES) {
                stringResource(R.string.voice_tts1_warning, ttsVoice.trim(), ttsModel.trim())
            } else {
                null
            }
            ModelPicker(
                stringResource(R.string.model_voice),
                ttsVoice,
                voiceOptions(),
                "modelVoice",
                note = stringResource(R.string.voice_note),
                warning = voiceWarning,
            ) { ttsVoice = it }
            ModelPicker(stringResource(R.string.model_transcription), sttModel, transcriptionOptions(), "modelTranscription") { sttModel = it }
            ModelPicker(stringResource(R.string.model_realtime), realtimeModel, realtimeOptions(), "modelRealtime") { realtimeModel = it }
            FreeOfflineSection(
                asrOnDevice = asrOnDevice,
                onAsrOnDevice = { asrOnDevice = it },
                engine = offlineTtsEngine,
                onEngine = { offlineTtsEngine = it },
                info = offlineVoice,
                languageTag = initial.resolvedLanguage(),
                onInstallVoiceData = onInstallVoiceData,
            )
            extra()
        }

        fun collect() = initial.copy(
            apiKey = apiKey.trim(),
            languageAuto = language == AUTO,
            preferredLanguage = if (language == AUTO) initial.preferredLanguage else language,
            interests = interests,
            hostStyle = hostStyle,
            liveVoice = liveVoice,
            alwaysListening = alwaysListening,
            soundEffects = soundEffects,
            localEvents = localEvents,
            pacing = pacing,
            asrOnDevice = asrOnDevice,
            offlineTtsEngine = offlineTtsEngine,
            dailyBudgetUsd = if (budget) budgetText.replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0 else initial.dailyBudgetUsd,
            // Adding a key ends the keyless preview.
            previewMode = initial.previewMode && apiKey.isBlank(),
            models = initial.models.copy(
                narrationModel = narrationModel.trim().ifBlank { initial.models.narrationModel },
                conversationModel = conversationModel.trim().ifBlank { initial.models.conversationModel },
                researchModel = researchModel.trim().ifBlank { initial.models.researchModel },
                ttsModel = ttsModel.trim().ifBlank { initial.models.ttsModel },
                ttsVoice = ttsVoice.trim().ifBlank { initial.models.ttsVoice },
                transcriptionModel = sttModel.trim().ifBlank { initial.models.transcriptionModel },
                realtimeModel = realtimeModel.trim().ifBlank { initial.models.realtimeModel },
            ),
        )

        Spacer(Modifier.height(4.dp))
        Row {
            Button(
                enabled = apiKey.isNotBlank() || initial.usingEmbeddedKey || !requireKey,
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
    Text(stringResource(R.string.memory_title), style = MaterialTheme.typography.titleSmall)
    if (memory.isEmpty()) {
        Text(
            stringResource(R.string.memory_empty),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    memory.forEach { m ->
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                stringResource(R.string.memory_item, memoryCategoryLabel(m.category), m.text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onForget(m.id) }) { Icon(Icons.Default.Close, stringResource(R.string.forget_item, m.text)) }
        }
    }
    OutlinedButton(onClick = onForgetAll, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.forget_all_memory)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val autoLabel = stringResource(R.string.language_auto)
    val label = if (selected == AUTO) autoLabel else Languages.displayName(selected)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.narration_language)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(autoLabel) }, onClick = { onSelect(AUTO); expanded = false })
            Languages.supported.forEach { lang ->
                DropdownMenuItem(text = { Text(lang.displayName) }, onClick = { onSelect(lang.tag); expanded = false })
            }
        }
    }
}

/** One choice in a model or voice picker: the id sent to OpenAI and an optional short human label. */
private data class ModelOption(val id: String, val hint: String? = null)

private val TTS1_MODELS = setOf("tts-1", "tts-1-hd")
private val TTS1_VOICES = setOf("alloy", "ash", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer")

@Composable
private fun narrationOptions() = listOf(
    ModelOption("gpt-5.1", stringResource(R.string.model_hint_best_default)),
    ModelOption("gpt-5", stringResource(R.string.model_hint_strong)),
    ModelOption("gpt-4.1", stringResource(R.string.model_hint_faster)),
    ModelOption("gpt-5-mini", stringResource(R.string.model_hint_cheaper)),
    ModelOption("gpt-4.1-mini", stringResource(R.string.model_hint_cheapest)),
)

@Composable
private fun conversationOptions() = listOf(
    ModelOption("gpt-4.1", stringResource(R.string.model_hint_default_fast)),
    ModelOption("gpt-5.1", stringResource(R.string.model_hint_best_slower)),
    ModelOption("gpt-5", stringResource(R.string.model_hint_strong_slower)),
    ModelOption("gpt-4.1-mini", stringResource(R.string.model_hint_cheaper)),
)

@Composable
private fun researchOptions() = listOf(
    ModelOption("gpt-4.1-mini", stringResource(R.string.model_hint_default_fast)),
    ModelOption("gpt-5-mini", stringResource(R.string.model_hint_reasoning_cheap)),
    ModelOption("gpt-4.1", stringResource(R.string.model_hint_higher_quality)),
    ModelOption("gpt-5.1", stringResource(R.string.model_hint_best_slower)),
)

@Composable
private fun ttsOptions() = listOf(
    ModelOption("gpt-4o-mini-tts", stringResource(R.string.model_hint_tts_default)),
    ModelOption("tts-1-hd", stringResource(R.string.model_hint_tts_hd)),
    ModelOption("tts-1", stringResource(R.string.model_hint_tts_fast)),
)

@Composable
private fun voiceOptions(): List<ModelOption> {
    val storiesOnly = stringResource(R.string.voice_hint_stories_only)
    return listOf(
        ModelOption("marin", stringResource(R.string.voice_hint_default)),
        ModelOption("cedar", stringResource(R.string.voice_hint_natural)),
    ) + listOf("alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse").map { ModelOption(it) } +
        listOf("fable", "nova", "onyx").map { ModelOption(it, storiesOnly) }
}

@Composable
private fun transcriptionOptions() = listOf(
    ModelOption("gpt-4o-mini-transcribe", stringResource(R.string.model_hint_default_fast)),
    ModelOption("gpt-4o-transcribe", stringResource(R.string.model_hint_more_accurate)),
    ModelOption("whisper-1", stringResource(R.string.model_hint_classic)),
)

@Composable
private fun realtimeOptions() = listOf(
    ModelOption("gpt-realtime", stringResource(R.string.model_hint_default)),
    ModelOption("gpt-realtime-mini", stringResource(R.string.model_hint_cheaper)),
)

/**
 * A dropdown of known models (or voices). A saved value that is not in the list (an older or hand-picked id)
 * is shown first and stays selectable, so choosing nothing never loses it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
    label: String,
    selected: String,
    options: List<ModelOption>,
    tag: String,
    note: String? = null,
    warning: String? = null,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = selected.trim()
    val savedHint = stringResource(R.string.model_hint_saved)
    val all = if (current.isNotEmpty() && options.none { it.id == current }) listOf(ModelOption(current, savedHint)) + options else options
    val shown = all.firstOrNull { it.id == current }
    val value = when {
        shown == null -> current
        shown.hint == null -> shown.id
        else -> "${shown.id} · ${shown.hint}"
    }
    Column {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(label) },
                supportingText = { note?.let { Text(it) } },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable).testTag(tag),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                all.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.id)
                                option.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        },
                        onClick = { onSelect(option.id); expanded = false },
                    )
                }
            }
        }
        warning?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** e.g. "GPS Radio 0.5.142 · build a1b2c3d · 2026-09-23". */
@Composable
fun versionLabel(): String = stringResource(
    R.string.version_label,
    com.gpsradio.app.BuildConfig.VERSION_NAME,
    com.gpsradio.app.BuildConfig.GIT_SHA,
    com.gpsradio.app.BuildConfig.BUILD_DATE,
)

private fun formatUsd(v: Double): String = String.format(java.util.Locale.US, if (v < 10) "%.2f" else "%.0f", v)

/** Estimated OpenAI spend today and this session (spec A §41). */
@Composable
private fun CostSection(cost: CostMeter.Totals, limit: Double) {
    Text(stringResource(R.string.cost_title), style = MaterialTheme.typography.titleSmall)
    val costDescription = stringResource(R.string.cost_today_description)
    Text(
        stringResource(R.string.cost_totals, formatUsd(cost.today), formatUsd(cost.session)) +
            (if (limit > 0) " · " + stringResource(R.string.cost_limit, formatUsd(limit)) else ""),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { contentDescription = costDescription },
    )
    val parts = CostMeter.Kind.entries.mapNotNull { k ->
        cost.todayByKind[k]?.takeIf { it >= 0.005 }?.let { stringResource(R.string.cost_part, costKindLabel(k), formatUsd(it)) }
    }
    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.cost_disclaimer), style = MaterialTheme.typography.bodySmall)
}

/**
 * Free & offline: Android's own speech engines. Speech recognition on the phone instead of OpenAI (the mic
 * listens once per tap), and which installed TextToSpeech engine reads stories when OpenAI can't.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FreeOfflineSection(
    asrOnDevice: Boolean,
    onAsrOnDevice: (Boolean) -> Unit,
    engine: String?,
    onEngine: (String?) -> Unit,
    info: OfflineVoiceInfo,
    languageTag: String,
    onInstallVoiceData: () -> Unit,
) {
    Text(stringResource(R.string.free_offline_title), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.free_offline_hint), style = MaterialTheme.typography.bodySmall)

    val openAiLabel = stringResource(R.string.asr_engine_openai)
    val phoneLabel = stringResource(R.string.asr_engine_phone)
    var asrExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = asrExpanded, onExpandedChange = { asrExpanded = it }) {
        OutlinedTextField(
            value = if (asrOnDevice) phoneLabel else openAiLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.asr_engine_label)) },
            supportingText = { Text(stringResource(R.string.asr_engine_hint)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(asrExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable).testTag("asrEngine"),
        )
        ExposedDropdownMenu(expanded = asrExpanded, onDismissRequest = { asrExpanded = false }) {
            DropdownMenuItem(text = { Text(openAiLabel) }, onClick = { onAsrOnDevice(false); asrExpanded = false })
            DropdownMenuItem(text = { Text(phoneLabel) }, onClick = { onAsrOnDevice(true); asrExpanded = false })
        }
    }

    val defaultLabel = stringResource(R.string.offline_voice_system_default)
    // A saved engine that is no longer listed (uninstalled) stays visible, so choosing nothing never loses it.
    val engines = info.engines.let { list ->
        if (engine != null && list.none { it.packageName == engine }) list + TtsEngineOption(engine, engine) else list
    }
    val engineLabel = engines.firstOrNull { it.packageName == engine }?.label ?: defaultLabel
    var engineExpanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = engineExpanded, onExpandedChange = { engineExpanded = it }) {
        OutlinedTextField(
            value = engineLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.offline_voice_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(engineExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable).testTag("offlineTtsEngine"),
        )
        ExposedDropdownMenu(expanded = engineExpanded, onDismissRequest = { engineExpanded = false }) {
            DropdownMenuItem(text = { Text(defaultLabel) }, onClick = { onEngine(null); engineExpanded = false })
            engines.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.label)
                            Text(option.packageName, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = { onEngine(option.packageName); engineExpanded = false },
                )
            }
        }
    }

    val languageName = Languages.displayName(languageTag)
    Text(
        when (info.voiceInstalled) {
            null -> stringResource(R.string.offline_voice_checking)
            true -> stringResource(R.string.offline_voice_installed, languageName)
            false -> stringResource(R.string.offline_voice_missing, languageName)
        },
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = onInstallVoiceData, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.install_voice_data))
    }
}
