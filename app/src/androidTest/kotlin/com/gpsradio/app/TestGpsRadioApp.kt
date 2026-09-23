package com.gpsradio.app

import com.gpsradio.core.session.AreaLabeler
import com.gpsradio.core.session.AudioOutput
import kotlinx.coroutines.delay
import okhttp3.mockwebserver.MockWebServer

/** The real app wired to a local fake of OpenAI, Wikipedia and Overpass, with silent audio. */
class TestGpsRadioApp : GpsRadioApp() {
    override fun endpoints(): Endpoints {
        val base = FakeServices.baseUrl
        return Endpoints(
            openAiBaseUrl = base.resolve("/openai/v1").toString(),
            wikipedia = { lang -> base.resolve("/wiki/$lang/w/api.php")!! },
            overpassUrl = base.resolve("/overpass").toString(),
            // Unknown to the fake server (404): no "on this day" segments in E2E runs.
            onThisDay = { lang -> base.resolve("/onthisday/$lang")!! },
        )
    }

    /** "Plays" each clip for a short, fixed time and records it. */
    override fun audioOutput(): AudioOutput = AudioOutput { bytes ->
        FakeServices.played += String(bytes)
        delay(1_500)
    }

    override fun areaLabeler(): AreaLabeler? = null

    /** The E2E test exercises the classic voice pipeline against the fake server. */
    override fun liveFactory(http: okhttp3.OkHttpClient, baseUrl: String): ((com.gpsradio.core.session.LiveHost, kotlinx.coroutines.CoroutineScope) -> com.gpsradio.core.session.LiveConversation)? = null
}

object FakeServices {
    val played = java.util.Collections.synchronizedList(mutableListOf<String>())
    /** Base URL of the fake server; started off the main thread (network on main is not allowed). */
    val baseUrl: okhttp3.HttpUrl by lazy {
        var url: okhttp3.HttpUrl? = null
        val t = Thread {
            val server = MockWebServer().apply {
                dispatcher = FakeDispatcher()
                start()
            }
            url = server.url("/")
        }
        t.start()
        t.join()
        url!!
    }
}
