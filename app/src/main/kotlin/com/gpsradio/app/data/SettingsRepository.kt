package com.gpsradio.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.ModelConfig
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
    val interests: Set<Topic> = setOf(Topic.HISTORY, Topic.NATURE, Topic.ARCHITECTURE, Topic.CULTURE),
    val models: ModelConfig = ModelConfig(),
    val hostStyle: HostStyle = HostStyle.ENTERTAINING,
    /** Natural, hands-free voice conversation via the OpenAI Realtime API (falls back to classic). */
    val liveVoice: Boolean = true,
    /** The listener chose "Try without a key": stories from source facts with the on-device voice. */
    val previewMode: Boolean = false,
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
            putStringSet(KEY_INTERESTS, next.interests.map { it.key }.toSet())
            putString(KEY_NARRATION_MODEL, next.models.narrationModel)
            putString(KEY_CONVERSATION_MODEL, next.models.conversationModel)
            putString(KEY_TTS_MODEL, next.models.ttsModel)
            putString(KEY_TTS_VOICE, next.models.ttsVoice)
            putString(KEY_STT_MODEL, next.models.transcriptionModel)
            putString(KEY_REALTIME_MODEL, next.models.realtimeModel)
            putString(KEY_HOST_STYLE, next.hostStyle.key)
            putBoolean(KEY_LIVE_VOICE, next.liveVoice)
            putBoolean(KEY_PREVIEW, next.previewMode)
        }
        _settings.value = next.copy(apiKey = next.apiKey.trim())
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        val m = d.models
        return AppSettings(
            apiKey = secure.getString(KEY_API, "").orEmpty(),
            languageAuto = plain.getBoolean(KEY_LANG_AUTO, d.languageAuto),
            preferredLanguage = plain.getString(KEY_LANG, null) ?: d.preferredLanguage,
            interests = plain.getStringSet(KEY_INTERESTS, null)?.mapNotNull { Topic.fromKey(it) }?.toSet() ?: d.interests,
            models = ModelConfig(
                narrationModel = plain.getString(KEY_NARRATION_MODEL, null) ?: m.narrationModel,
                conversationModel = plain.getString(KEY_CONVERSATION_MODEL, null) ?: m.conversationModel,
                ttsModel = plain.getString(KEY_TTS_MODEL, null) ?: m.ttsModel,
                ttsVoice = plain.getString(KEY_TTS_VOICE, null) ?: m.ttsVoice,
                transcriptionModel = plain.getString(KEY_STT_MODEL, null) ?: m.transcriptionModel,
                realtimeModel = plain.getString(KEY_REALTIME_MODEL, null) ?: m.realtimeModel,
            ),
            hostStyle = HostStyle.fromKey(plain.getString(KEY_HOST_STYLE, null)),
            liveVoice = plain.getBoolean(KEY_LIVE_VOICE, d.liveVoice),
            previewMode = plain.getBoolean(KEY_PREVIEW, d.previewMode),
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
        const val KEY_CONVERSATION_MODEL = "conversation_model"
        const val KEY_TTS_MODEL = "tts_model"
        const val KEY_TTS_VOICE = "tts_voice"
        const val KEY_STT_MODEL = "stt_model"
        const val KEY_REALTIME_MODEL = "realtime_model"
        const val KEY_HOST_STYLE = "host_style"
        const val KEY_LIVE_VOICE = "live_voice"
        const val KEY_PREVIEW = "preview_mode"
    }
}
