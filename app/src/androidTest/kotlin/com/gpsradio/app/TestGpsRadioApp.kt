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
            // Unknown to the fake server (404): Wikidata is optional, so discovery carries on without it.
            wikidataSparql = base.resolve("/wikidata/sparql")!!,
        )
    }

    /** "Plays" each clip for a short, fixed time and records it. */
    override fun audioOutput(): AudioOutput = AudioOutput { bytes ->
        FakeServices.played += String(bytes)
        delay(1_500)
    }

    override fun areaLabeler(): AreaLabeler? = null

    /** The tests match English text: keep the emulator's locale. */
    override fun uiLanguage(): String? = null

    /** The phone's voice, faked: the emulator may have no offline voice installed. */
    override fun fallbackSpeech(): com.gpsradio.core.session.SpeechService = object : com.gpsradio.core.session.SpeechService {
        override suspend fun synthesize(text: String, language: String, style: com.gpsradio.core.ai.HostStyle) = "DEVICE:$text".toByteArray()
        override suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, prompt: String?) = ""
    }

    // No on-device model on the test emulator: offline stories are the plain notes.
    override fun localWriter(): com.gpsradio.core.ai.LocalWriter? = null

    /** No event search against the fake OpenAI server. */
    override fun eventScout(openAi: com.gpsradio.core.ai.OpenAiClient, models: () -> com.gpsradio.core.ai.ModelConfig): com.gpsradio.core.events.EventScout? = null

    /** No visit-info web checks against the fake OpenAI server. */
    override fun visitScout(openAi: com.gpsradio.core.ai.OpenAiClient, models: () -> com.gpsradio.core.ai.ModelConfig): com.gpsradio.core.visit.VisitSource? = null

    /** No angle research against the fake OpenAI server. */
    override fun angleResearch(openAi: com.gpsradio.core.ai.OpenAiClient, models: () -> com.gpsradio.core.ai.ModelConfig): com.gpsradio.core.discovery.AngleResearch? = null

    /** No extra picture-finding model calls during E2E runs. */
    override fun pictureFinder(openAi: com.gpsradio.core.ai.OpenAiClient, models: () -> com.gpsradio.core.ai.ModelConfig): com.gpsradio.core.ai.PictureFinder? = null

    /** No self-update checks against GitHub during E2E runs. */
    override fun updateClient(http: okhttp3.OkHttpClient): com.gpsradio.core.update.UpdateClient? = null

    /** No earcons: keeps the E2E timing and recorded audio exactly as before. */
    override fun stingPlayer(): com.gpsradio.core.session.StingPlayer? = null

    /** The E2E test exercises the classic voice pipeline against the fake server. */
    override fun liveFactory(http: okhttp3.OkHttpClient, baseUrl: String): ((com.gpsradio.core.session.LiveHost, kotlinx.coroutines.CoroutineScope) -> com.gpsradio.core.session.LiveConversation)? = null
}

object FakeServices {
    val played = java.util.Collections.synchronizedList(mutableListOf<String>())
    /** The fake server's dispatcher: its request log goes into failure messages. */
    val dispatcher = FakeDispatcher()
    /** Base URL of the fake server; started off the main thread (network on main is not allowed). */
    val baseUrl: okhttp3.HttpUrl by lazy {
        var url: okhttp3.HttpUrl? = null
        val t = Thread {
            val server = MockWebServer().apply {
                dispatcher = FakeServices.dispatcher
                start()
            }
            url = server.url("/")
        }
        t.start()
        t.join()
        url!!
    }
}
