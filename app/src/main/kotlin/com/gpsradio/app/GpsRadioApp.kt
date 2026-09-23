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
import com.gpsradio.app.platform.FileInterestStore
import com.gpsradio.app.platform.FileJournalStore
import com.gpsradio.app.platform.Stings
import com.gpsradio.core.session.StingPlayer
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
import com.gpsradio.core.discovery.AreaInfoSource
import com.gpsradio.core.discovery.DiscoveryService
import com.gpsradio.core.discovery.OnThisDayClient
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
    /** Wikipedia's keyless "on this day" feed for a language edition (the client appends MM/DD). */
    val onThisDay: (String) -> HttpUrl = { lang -> "https://$lang.wikipedia.org/api/rest_v1/feed/onthisday/events".toHttpUrl() },
    /** Wikidata SPARQL: film locations, historical events, Jewish/Israeli connections (spec A §29). */
    val wikidataSparql: HttpUrl = "https://query.wikidata.org/sparql".toHttpUrl(),
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
    lateinit var updater: com.gpsradio.app.platform.AppUpdater
        private set

    /** The live voice's audio; told when the radio itself is playing (always-listening echo guard). */
    private var livePcm: AndroidPcmAudio? = null

    /** Events today nearby (OpenAI web search); tests return null so the fake server isn't asked. */
    protected open fun eventScout(openAi: OpenAiClient, models: () -> com.gpsradio.core.ai.ModelConfig): com.gpsradio.core.events.EventScout? =
        com.gpsradio.core.events.EventScout(openAi, models)

    /** Hours, admission and visit details (OpenAI web search); tests return null (OSM tags only). */
    protected open fun visitScout(openAi: OpenAiClient, models: () -> com.gpsradio.core.ai.ModelConfig): com.gpsradio.core.visit.VisitSource? =
        com.gpsradio.core.visit.VisitScout(openAi, models)

    /** Self-update source (the app isn't in a store); tests return null to disable it. */
    protected open fun updateClient(http: OkHttpClient): com.gpsradio.core.update.UpdateClient? =
        com.gpsradio.core.update.UpdateClient(http)

    protected open fun endpoints(): Endpoints = Endpoints()

    /**
     * A focus loss pauses the radio (phone call, another app), except when our own live host took focus to
     * answer: the story has already stopped for the exchange, and pausing would cut the answer.
     */
    protected open fun audioOutput(): AudioOutput = MediaAudioOutput(this, onFocusLost = {
        if (livePcm?.holdsFocus != true) session.pause()
    })

    protected open fun areaLabeler(): AreaLabeler? = GeocoderAreaLabeler(this)

    /** On-device voice for the keyless preview and when OpenAI is unreachable. */
    protected open fun fallbackSpeech(): SpeechService? = AndroidTtsSpeech(this)

    /** Network state for offline-aware scheduling; tests may force online/offline. */
    protected open fun isOnline(): () -> Boolean = NetworkMonitor(this)::isOnline
    /** Earcons synthesized in code; tests may return null to keep audio silent. */
    protected open fun stingPlayer(): StingPlayer? = Stings()

    /** Natural hands-free voice (OpenAI Realtime); tests return null to use the classic pipeline. */
    protected open fun liveFactory(http: OkHttpClient, baseUrl: String): ((LiveHost, CoroutineScope) -> LiveConversation)? {
        val realtime = RealtimeClient(http, { settings.current.effectiveApiKey })
        val pcm = AndroidPcmAudio(this).also { livePcm = it }
        return { host, scope -> LiveConversation({ model -> realtime.connect(model) }, pcm, host, scope) }
    }

    /**
     * Always listening: whatever the radio plays through the speaker (stories, answers, notices, host
     * questions) makes the mic require the listener to be clearly louder, so the phone never answers itself.
     */
    private fun audibleWhilePlaying(inner: AudioOutput): AudioOutput {
        val playing = java.util.concurrent.atomic.AtomicInteger(0)
        return AudioOutput { bytes ->
            if (playing.incrementAndGet() == 1) livePcm?.setRadioAudible(true)
            try {
                inner.play(bytes)
            } finally {
                if (playing.decrementAndGet() == 0) livePcm?.setRadioAudible(false)
            }
        }
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
        val openAi = OpenAiClient(http, apiKey = { settings.current.effectiveApiKey }, baseUrl = ep.openAiBaseUrl)
        val models = { settings.current.models }
        val online = isOnline()
        val wikipedia = WikipediaClient(http, userAgent, ep.wikipedia)

        updater = com.gpsradio.app.platform.AppUpdater(this, updateClient(http))
        session = RadioSession(
            places = DiscoveryService(
                wikipedia,
                OverpassClient(http, userAgent, ep.overpassUrl),
                diskCache = AreaDiskCache(FileAreaCacheStore(this)),
                isOnline = online,
                wikidata = com.gpsradio.core.discovery.WikidataClient(http, userAgent, ep.wikidataSparql),
            ),
            narrator = RadioAgent(openAi, models),
            speech = OpenAiSpeech(openAi, models),
            audio = audibleWhilePlaying(audioOutput()),
            historyStore = FileHistoryStore(this),
            config = {
                settings.current.let {
                    SessionConfig(
                        language = it.resolvedLanguage(),
                        interests = it.interests,
                        style = it.hostStyle,
                        liveVoice = it.liveVoice && micGranted() && it.hasApiKey,
                        handsFree = it.alwaysListening,
                        voice = it.models.ttsVoice,
                        liveModel = it.models.realtimeModel,
                        transcriptionModel = it.models.transcriptionModel,
                        // Without a key the radio can only read notes aloud on the device.
                        previewMode = !it.hasApiKey,
                        soundEffects = it.soundEffects,
                        pacing = it.pacing,
                        usingBuiltInKey = it.usingEmbeddedKey,
                        askPreferences = true,
                        localEvents = it.localEvents,
                    )
                }
            },
            areaLabeler = areaLabeler(),
            memoryStore = FileMemoryStore(this),
            favoritesStore = FileFavoritesStore(this),
            interestStore = FileInterestStore(this),
            liveFactory = liveFactory(http, ep.openAiBaseUrl),
            onPersistLanguage = { tag -> settings.update { it.copy(languageAuto = false, preferredLanguage = tag) } },
            onNavigate = ::openInMaps,
            fallbackNarrator = NarrationFallback(),
            fallbackSpeech = fallbackSpeech(),
            isOnline = online,
            stings = stingPlayer(),
            journalStore = FileJournalStore(this),
            onThisDay = OnThisDayClient(http, userAgent, ep.onThisDay),
            areaInfo = AreaInfoSource { lang, title -> wikipedia.articleByTitle(lang, title) },
            eventScout = eventScout(openAi, models),
            visitScout = visitScout(openAi, models),
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
