package com.gpsradio.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.editorial.Pacing
import com.gpsradio.core.lang.Languages
import com.gpsradio.core.model.Topic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

const val DEFAULT_LANGUAGE = "ru-RU"

/** Default OpenAI key built into the app by CI (unscrambled at runtime); empty in local/test builds. */
object EmbeddedKey {
    val value: String by lazy {
        val raw = com.gpsradio.app.BuildConfig.EMBEDDED_KEY
        if (raw.isBlank()) return@lazy ""
        raw.split(',').mapIndexed { i, s -> (s.trim().toInt() xor (0x5A + i % 7)).toByte() }.toByteArray().toString(Charsets.UTF_8)
    }
}

data class AppSettings(
    val apiKey: String = "",
    /** Auto follows the device language (spec A §13). */
    /** Russian by default; the listener can pick another language or Auto (phone language) in Settings. */
    val languageAuto: Boolean = false,
    val preferredLanguage: String = DEFAULT_LANGUAGE,
    /** Every topic is on by default (spec A §54); the listener switches off what they don't want. */
    val interests: Set<Topic> = Topic.entries.toSet(),
    val models: ModelConfig = ModelConfig(),
    val hostStyle: HostStyle = HostStyle.ENTERTAINING,
    /** Natural, hands-free voice conversation via the OpenAI Realtime API (falls back to classic). */
    val liveVoice: Boolean = true,
    /** Always listening (like ChatGPT voice mode): the mic stays open with the natural voice; switchable anytime. */
    val alwaysListening: Boolean = true,
    /** The listener chose "Try without a key": stories from source facts with the on-device voice. */
    val previewMode: Boolean = false,
    /** Short stings before stories and answers, and a blip when listening starts. */
    val soundEffects: Boolean = true,
    /** Concerts, festivals, markets… today nearby: spoken heads-up and a notification. */
    val localEvents: Boolean = true,
    /** How often the radio speaks. Non-stop by default: unless stopped, the stories keep coming (spec A §37). */
    val pacing: Pacing = Pacing.NONSTOP,
    /** Daily OpenAI spending limit in USD (estimate, spec A §41); 0 = no limit. */
    val dailyBudgetUsd: Double = 0.0,
) {
    /** The listener's own key if they entered one, otherwise the key built into this app (if any). */
    val effectiveApiKey: String get() = apiKey.ifBlank { EmbeddedKey.value }

    val usingEmbeddedKey: Boolean get() = apiKey.isBlank() && EmbeddedKey.value.isNotBlank()

    val hasApiKey: Boolean get() = effectiveApiKey.isNotBlank()

    /** Show the radio (not the setup screen): a key is set, or the keyless preview was chosen. */
    val canListen: Boolean get() = hasApiKey || previewMode

    fun resolvedLanguage(): String = Languages.resolveSessionLanguage(
        sessionOverride = null,
        preferred = preferredLanguage,
        autoMode = languageAuto,
        deviceLocaleTag = Locale.getDefault().toLanguageTag(),
    )
}

/**
 * User settings. The OpenAI key lives only on this device, in Keystore-backed encrypted prefs;
 * it is never bundled in the APK or committed anywhere.
 */
class SettingsRepository(context: Context) {
    private val plain: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secure: SharedPreferences = openSecure(context)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val current: AppSettings get() = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        secure.edit { putString(KEY_API, next.apiKey.trim()) }
        plain.edit {
            putBoolean(KEY_LANG_AUTO, next.languageAuto)
            putString(KEY_LANG, next.preferredLanguage)
            putInt(KEY_LANG_VERSION, LANG_VERSION)
            putStringSet(KEY_INTERESTS, next.interests.map { it.key }.toSet())
            putInt(KEY_MODELS_VERSION, MODELS_VERSION)
            putInt(KEY_INTERESTS_VERSION, INTERESTS_VERSION)
            putString(KEY_NARRATION_MODEL, next.models.narrationModel)
            putString(KEY_CONVERSATION_MODEL, next.models.conversationModel)
            putString(KEY_RESEARCH_MODEL, next.models.researchModel)
            putString(KEY_TTS_MODEL, next.models.ttsModel)
            putString(KEY_TTS_VOICE, next.models.ttsVoice)
            putInt(KEY_VOICE_VERSION, VOICE_VERSION)
            putString(KEY_STT_MODEL, next.models.transcriptionModel)
            putString(KEY_REALTIME_MODEL, next.models.realtimeModel)
            putString(KEY_HOST_STYLE, next.hostStyle.key)
            putBoolean(KEY_LIVE_VOICE, next.liveVoice)
            putBoolean(KEY_ALWAYS_LISTENING, next.alwaysListening)
            putBoolean(KEY_PREVIEW, next.previewMode)
            putBoolean(KEY_SOUND_EFFECTS, next.soundEffects)
            putBoolean(KEY_LOCAL_EVENTS, next.localEvents)
            putString(KEY_PACING_V2, next.pacing.key)
            putFloat(KEY_DAILY_BUDGET, next.dailyBudgetUsd.coerceAtLeast(0.0).toFloat())
        }
        _settings.value = next.copy(apiKey = next.apiKey.trim())
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        val m = d.models
        val modelsCurrent = plain.getInt(KEY_MODELS_VERSION, 1) >= MODELS_VERSION
        val langCurrent = plain.getInt(KEY_LANG_VERSION, 1) >= LANG_VERSION
        return AppSettings(
            apiKey = secure.getString(KEY_API, "").orEmpty(),
            // v2: Russian is the default (spec A §49). Installs saved under the old default (Auto, which follows an
            // English phone) move to Russian once; a language chosen after that is kept.
            languageAuto = if (langCurrent) plain.getBoolean(KEY_LANG_AUTO, d.languageAuto) else d.languageAuto,
            preferredLanguage = (if (langCurrent) plain.getString(KEY_LANG, null) else null) ?: d.preferredLanguage,
            interests = plain.getStringSet(KEY_INTERESTS, null)?.mapNotNull { Topic.fromKey(it) }?.toSet()
                // v3: all topics on by default; an untouched earlier default moves to it once.
                ?.takeUnless { plain.getInt(KEY_INTERESTS_VERSION, 1) < INTERESTS_VERSION && it in OLD_DEFAULT_INTERESTS }
                ?: d.interests,
            models = ModelConfig(
                // Models saved before v3 were older defaults: move to the new defaults once (spec A §38, §53).
                narrationModel = plain.getString(KEY_NARRATION_MODEL, null)?.takeIf { modelsCurrent } ?: m.narrationModel,
                conversationModel = plain.getString(KEY_CONVERSATION_MODEL, null)?.takeIf { modelsCurrent } ?: m.conversationModel,
                // Not touched by the models_version migration: it was never saved before v3.
                researchModel = plain.getString(KEY_RESEARCH_MODEL, null) ?: m.researchModel,
                ttsModel = plain.getString(KEY_TTS_MODEL, null) ?: m.ttsModel,
                // v2: one voice for stories and conversation; the old story default (coral) moves to it once.
                ttsVoice = plain.getString(KEY_TTS_VOICE, null)
                    ?.takeUnless { plain.getInt(KEY_VOICE_VERSION, 1) < VOICE_VERSION && it == "coral" } ?: m.ttsVoice,
                transcriptionModel = plain.getString(KEY_STT_MODEL, null) ?: m.transcriptionModel,
                realtimeModel = plain.getString(KEY_REALTIME_MODEL, null) ?: m.realtimeModel,
            ),
            hostStyle = HostStyle.fromKey(plain.getString(KEY_HOST_STYLE, null)),
            liveVoice = plain.getBoolean(KEY_LIVE_VOICE, d.liveVoice),
            alwaysListening = plain.getBoolean(KEY_ALWAYS_LISTENING, d.alwaysListening),
            previewMode = plain.getBoolean(KEY_PREVIEW, d.previewMode),
            soundEffects = plain.getBoolean(KEY_SOUND_EFFECTS, d.soundEffects),
            localEvents = plain.getBoolean(KEY_LOCAL_EVENTS, d.localEvents),
            // v2: everyone moved to non-stop once; a pacing chosen after that is kept.
            pacing = plain.getString(KEY_PACING_V2, null)?.let { Pacing.fromKey(it) } ?: d.pacing,
            dailyBudgetUsd = plain.getFloat(KEY_DAILY_BUDGET, d.dailyBudgetUsd.toFloat()).toDouble(),
        )
    }

    private companion object {
        const val SECURE_FILE = "secure_settings"

        fun createSecure(context: Context): SharedPreferences = EncryptedSharedPreferences.create(
            context,
            SECURE_FILE,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

        /**
         * Keystore keys can be wiped (OEM bugs, restored backups), which makes the encrypted file
         * unreadable and would crash every start. Recover by discarding the unreadable store: the
         * user re-enters the key, nothing else is lost.
         */
        fun openSecure(context: Context): SharedPreferences = try {
            createSecure(context)
        } catch (e: Exception) {
            context.deleteSharedPreferences(SECURE_FILE)
            runCatching {
                java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
            createSecure(context)
        }

        const val KEY_API = "openai_api_key"
        const val KEY_LANG_AUTO = "language_auto"
        const val KEY_LANG = "preferred_language"
        const val KEY_INTERESTS = "interests"
        const val KEY_NARRATION_MODEL = "narration_model"
        const val KEY_MODELS_VERSION = "models_version"
        const val MODELS_VERSION = 3
        const val KEY_LANG_VERSION = "language_version"
        const val LANG_VERSION = 2
        const val KEY_VOICE_VERSION = "voice_version"
        const val VOICE_VERSION = 2
        const val KEY_INTERESTS_VERSION = "interests_version"
        const val INTERESTS_VERSION = 3
        val OLD_DEFAULT_INTERESTS = setOf(
            setOf(Topic.HISTORY, Topic.NATURE, Topic.ARCHITECTURE, Topic.CULTURE, Topic.JEWISH),
            setOf(Topic.HISTORY, Topic.NATURE, Topic.ARCHITECTURE, Topic.CULTURE),
        )
        const val KEY_CONVERSATION_MODEL = "conversation_model"
        const val KEY_RESEARCH_MODEL = "research_model"
        const val KEY_TTS_MODEL = "tts_model"
        const val KEY_TTS_VOICE = "tts_voice"
        const val KEY_STT_MODEL = "stt_model"
        const val KEY_REALTIME_MODEL = "realtime_model"
        const val KEY_HOST_STYLE = "host_style"
        const val KEY_LIVE_VOICE = "live_voice"
        const val KEY_ALWAYS_LISTENING = "always_listening"
        const val KEY_PREVIEW = "preview_mode"
        const val KEY_SOUND_EFFECTS = "sound_effects"
        const val KEY_LOCAL_EVENTS = "local_events"
        const val KEY_PACING_V2 = "pacing_v2"
        const val KEY_DAILY_BUDGET = "daily_budget_usd"
    }
}
