package com.gpsradio.core

import com.gpsradio.core.ai.ConversationAction
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.discovery.DiscoveryService
import com.gpsradio.core.discovery.OverpassClient
import com.gpsradio.core.discovery.WikipediaClient
import com.gpsradio.core.lang.Languages
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.Topic
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiAndDiscoveryTest {

    @Test
    fun parsesResponsesOutputAndCitations() {
        val raw = """
            {"id":"resp_1","status":"completed","output":[
              {"type":"web_search_call","id":"ws_1","status":"completed"},
              {"type":"message","role":"assistant","content":[
                {"type":"output_text","text":"The lake is real.","annotations":[
                  {"type":"url_citation","url":"https://example.org/lake","title":"Lake"}]}]}]}
        """.trimIndent()
        val r = OpenAiClient.parseResponse(raw)
        assertEquals("The lake is real.", r.text)
        assertEquals("https://example.org/lake", r.citations.single().url)
    }

    @Test
    fun parsesStructuredReplyAndFallsBackToPlainText() {
        val r = RadioAgent.parseReply(
            """{"reply":"Хорошо, продолжу по-русски.","action":"change_language","language":"ru-RU","persist_language":false,"theme":null,"entity_id":null}""",
        )
        assertEquals(ConversationAction.CHANGE_LANGUAGE, r.action)
        assertEquals("ru-RU", r.language)
        val plain = RadioAgent.parseReply("Just **text** here.")
        assertEquals("Just text here.", plain.reply)
        assertEquals(ConversationAction.NONE, plain.action)
        assertEquals(Topic.WAR, RadioAgent.parseReply("""{"reply":"ok","action":"set_theme","theme":"war"}""").theme)
    }

    @Test
    fun languageResolution() {
        assertEquals("ru-RU", Languages.resolveSessionLanguage(null, "ru-RU", autoMode = false, deviceLocaleTag = "de-AT"))
        assertEquals("de-DE", Languages.resolveSessionLanguage(null, "ru-RU", autoMode = true, deviceLocaleTag = "de-AT"))
        assertEquals("en-US", Languages.resolveSessionLanguage("en-GB", "ru-RU", autoMode = false, deviceLocaleTag = null))
        assertEquals("ru-RU", Languages.resolveSessionLanguage(null, "ru-RU", autoMode = true, deviceLocaleTag = "xx-YY"))
        assertEquals(Languages.FALLBACK, Languages.resolveSessionLanguage(null, null, autoMode = true, deviceLocaleTag = "xx"))
    }

    @Test
    fun discoveryMergesWikipediaEditionsAndOsm() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = when {
                    path.startsWith("/overpass") -> """{"elements":[
                        {"type":"node","id":1,"lat":47.611,"lon":13.781,"tags":{"name":"Schloss Ort","historic":"castle","wikidata":"Q1"}},
                        {"type":"way","id":2,"center":{"lat":47.612,"lon":13.779},"tags":{"name":"Kalvarienberg","natural":"peak","ele":"610","wikimedia_commons":"File:Kalvarienberg Gmunden.jpg"}},
                        {"type":"node","id":3,"lat":47.6,"lon":13.7,"tags":{"historic":"memorial"}}]}"""
                    path.contains("list=geosearch") && path.startsWith("/de/") ->
                        """{"query":{"geosearch":[{"pageid":10,"title":"Schloss Ort","lat":47.611,"lon":13.781,"dist":120}]}}"""
                    path.contains("list=geosearch") ->
                        """{"query":{"geosearch":[{"pageid":20,"title":"Ort Castle","lat":47.611,"lon":13.781,"dist":120},{"pageid":21,"title":"Traunsee","lat":47.85,"lon":13.8,"dist":900}]}}"""
                    path.startsWith("/de/") ->
                        """{"query":{"pages":[{"pageid":10,"title":"Schloss Ort","extract":"Das Schloss Ort ist eine Burg im Traunsee, erbaut im 11. Jahrhundert.","description":"Burg","pageprops":{"wikibase_item":"Q1"},"thumbnail":{"source":"https://upload.wikimedia.org/ort.jpg","width":640,"height":480}}]}}"""
                    else ->
                        """{"query":{"pages":[{"pageid":20,"title":"Ort Castle","extract":"Ort Castle is a castle on Lake Traun.","pageprops":{"wikibase_item":"Q1"}},{"pageid":21,"title":"Traunsee","extract":"Traunsee is a lake in Upper Austria.","description":"lake in Austria","pageprops":{"wikibase_item":"Q2"}}]}}"""
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        val http = OkHttpClient()
        val wiki = WikipediaClient(http, "test") { lang -> server.url("/$lang/w/api.php") }
        val osm = OverpassClient(http, "test", server.url("/overpass").toString())
        val svc = DiscoveryService(wiki, osm)
        val places = svc.discover(GeoPoint(47.61, 13.78), 1500, "de")
        server.shutdown()

        // Q1 appears in de + en + OSM: kept once, from the German edition.
        val castle = places.filter { it.wikidataId == "Q1" }
        assertEquals(1, castle.size)
        assertEquals("wikipedia:de", castle.single().source)
        assertTrue(Topic.HISTORY in castle.single().topics)
        assertEquals("https://upload.wikimedia.org/ort.jpg", castle.single().imageUrl)
        assertTrue(places.any { it.name == "Traunsee" })
        val peak = places.single { it.id == "osm:way/2" }
        assertTrue(Topic.NATURE in peak.topics)
        assertTrue(peak.extract!!.contains("610"))
        assertEquals("https://commons.wikimedia.org/wiki/Special:FilePath/Kalvarienberg_Gmunden.jpg?width=640", peak.imageUrl)
        // Unnamed OSM features are ignored.
        assertTrue(places.none { it.id == "osm:node/3" })
    }
}
