package com.gpsradio.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.gpsradio.app.data.SettingsRepository
import com.gpsradio.app.platform.FileHistoryStore
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.gpsradio.app.platform.AndroidPcmAudio
import com.gpsradio.app.platform.AndroidTtsSpeech
import com.gpsradio.app.platform.FileAreaCacheStore
import com.gpsradio.app.platform.NetworkMonitor
import com.gpsradio.app.platform.FileFavoritesStore
import com.gpsradio.app.platform.FileMemoryStore
import com.gpsradio.app.platform.GeocoderAreaLabeler
import com.gpsradio.app.platform.MediaAudioOutput
import com.gpsradio.core.ai.NarrationFallback
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.ai.RealtimeClient
import com.gpsradio.core.session.LiveConversation
import com.gpsradio.core.session.LiveHost
import kotlinx.coroutines.CoroutineScope
import com.gpsradio.core.discovery.AreaDiskCache
import com.gpsradio.core.discovery.DiscoveryService
import com.gpsradio.core.discovery.OverpassClient
import com.gpsradio.core.discovery.WikipediaClient
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.session.AreaLabeler
import com.gpsradio.core.session.AudioOutput
import com.gpsradio.core.session.OpenAiSpeech
import com.gpsradio.core.session.RadioSession
import com.gpsradio.core.session.SessionConfig
import com.gpsradio.core.session.SpeechService
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.osmdroid.config.Configuration
import java.util.concurrent.TimeUnit

/** External service locations; tests point these at a local fake server. */
data class Endpoints(
    val openAiBaseUrl: String = "https://api.openai.com/v1",
    val wikipedia: (String) -> HttpUrl = { lang -> "https://$lang.wikipedia.org/w/api.php".toHttpUrl() },
    val overpassUrl: String = "https://overpass-api.de/api/interpreter",
)

/**
 * Manual dependency wiring. Everything runs on the device and talks directly to OpenAI,
 * Wikipedia and OpenStreetMap; there is no app backend. Open for instrumentation tests,
 * which swap endpoints, audio output and geocoding.
 */
open class GpsRadioApp : Application() {
    lateinit var settings: SettingsRepository
        private set
    lateinit var session: RadioSession
        private set

    protected open fun endpoints(): Endpoints = Endpoints()

    protected open fun audioOutput(): AudioOutput = MediaAudioOutput(this, onFocusLost = { session.pause() })

    protected open fun areaLabeler(): AreaLabeler? = GeocoderAreaLabeler(this)

    /** On-device voice for the keyless preview and when OpenAI is unreachable. */
    protected open fun fallbackSpeech(): SpeechService? = AndroidTtsSpeech(this)

    /** Network state for offline-aware scheduling; tests may force online/offline. */
    protected open fun isOnline(): () -> Boolean = NetworkMonitor(this)::isOnline

    /** Natural hands-free voice (OpenAI Realtime); tests return null to use the classic pipeline. */
    protected open fun liveFactory(http: OkHttpClient, baseUrl: String): ((LiveHost, CoroutineScope) -> LiveConversation)? {
        val realtime = RealtimeClient(http, { settings.current.apiKey })
        val pcm = AndroidPcmAudio(this)
        return { host, scope -> LiveConversation({ model -> realtime.connect(model) }, pcm, host, scope) }
    }

    private fun micGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        // OpenStreetMap tile servers require an identifying user agent.
        Configuration.getInstance().apply {
            userAgentValue = BuildConfig.APPLICATION_ID
            // Keep the tile cache in app-private storage (no storage permission needed).
            osmdroidBasePath = java.io.File(filesDir, "osmdroid")
            osmdroidTileCache = java.io.File(cacheDir, "osmdroid-tiles")
        }

        val ep = endpoints()
        val userAgent = "GpsRadio/${BuildConfig.VERSION_NAME} (Android; https://github.com/apartsin/gpsradio)"
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
        val openAi = OpenAiClient(http, apiKey = { settings.current.apiKey }, baseUrl = ep.openAiBaseUrl)
        val models = { settings.current.models }
        val online = isOnline()

        session = RadioSession(
            places = DiscoveryService(
                WikipediaClient(http, userAgent, ep.wikipedia),
                OverpassClient(http, userAgent, ep.overpassUrl),
                diskCache = AreaDiskCache(FileAreaCacheStore(this)),
                isOnline = online,
            ),
            narrator = RadioAgent(openAi, models),
            speech = OpenAiSpeech(openAi, models),
            audio = audioOutput(),
            historyStore = FileHistoryStore(this),
            config = {
                settings.current.let {
                    SessionConfig(
                        language = it.resolvedLanguage(),
                        interests = it.interests,
                        style = it.hostStyle,
                        liveVoice = it.liveVoice && micGranted() && it.hasApiKey,
                        voice = it.models.ttsVoice,
                        liveModel = it.models.realtimeModel,
                        transcriptionModel = it.models.transcriptionModel,
                        // Without a key the radio can only read notes aloud on the device.
                        previewMode = !it.hasApiKey,
                    )
                }
            },
            areaLabeler = areaLabeler(),
            memoryStore = FileMemoryStore(this),
            favoritesStore = FileFavoritesStore(this),
            liveFactory = liveFactory(http, ep.openAiBaseUrl),
            onPersistLanguage = { tag -> settings.update { it.copy(languageAuto = false, preferredLanguage = tag) } },
            onNavigate = ::openInMaps,
            fallbackNarrator = NarrationFallback(),
            fallbackSpeech = fallbackSpeech(),
            isOnline = online,
        )
    }

    /** Navigation handoff (spec A PR-10): let the user's maps app do the routing. */
    private fun openInMaps(place: PlaceCandidate) {
        val label = Uri.encode(place.name)
        val uri = Uri.parse("geo:${place.point.lat},${place.point.lon}?q=${place.point.lat},${place.point.lon}($label)")
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }
}
