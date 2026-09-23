package com.gpsradio.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.gpsradio.app.data.SettingsRepository
import com.gpsradio.app.platform.FileHistoryStore
import com.gpsradio.app.platform.GeocoderAreaLabeler
import com.gpsradio.app.platform.MediaAudioOutput
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.discovery.DiscoveryService
import com.gpsradio.core.discovery.OverpassClient
import com.gpsradio.core.discovery.WikipediaClient
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.session.OpenAiSpeech
import com.gpsradio.core.session.RadioSession
import com.gpsradio.core.session.SessionConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency wiring. Everything runs on the device and talks directly to OpenAI,
 * Wikipedia and OpenStreetMap; there is no app backend.
 */
class GpsRadioApp : Application() {
    lateinit var settings: SettingsRepository
        private set
    lateinit var session: RadioSession
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)

        val userAgent = "GpsRadio/${BuildConfig.VERSION_NAME} (Android; https://github.com/apartsin/gpsradio)"
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
        val openAi = OpenAiClient(http, apiKey = { settings.current.apiKey })
        val models = { settings.current.models }

        session = RadioSession(
            places = DiscoveryService(WikipediaClient(http, userAgent), OverpassClient(http, userAgent)),
            narrator = RadioAgent(openAi, models),
            speech = OpenAiSpeech(openAi, models),
            audio = MediaAudioOutput(this, onFocusLost = { session.pause() }),
            historyStore = FileHistoryStore(this),
            config = { settings.current.let { SessionConfig(it.resolvedLanguage(), it.interests) } },
            areaLabeler = GeocoderAreaLabeler(this),
            onPersistLanguage = { tag -> settings.update { it.copy(languageAuto = false, preferredLanguage = tag) } },
            onNavigate = ::openInMaps,
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
