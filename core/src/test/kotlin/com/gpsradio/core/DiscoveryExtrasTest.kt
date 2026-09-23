package com.gpsradio.core

import com.gpsradio.core.ai.HostLine
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.discovery.DiscoveryService
import com.gpsradio.core.discovery.OverpassClient
import com.gpsradio.core.discovery.TopicClassifier
import com.gpsradio.core.discovery.WikidataClient
import com.gpsradio.core.discovery.WikipediaClient
import com.gpsradio.core.editorial.EditorialRanker
import com.gpsradio.core.editorial.HeardHistory
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.PlaceFeature
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Places to eat/shop, film locations, historical events and Jewish heritage (spec A §29). */
class DiscoveryExtrasTest {
    private val here = GeoPoint(47.9180, 13.7990)

    private fun sparql(vararg rows: String) = """{"head":{"vars":[]},"results":{"bindings":[${rows.joinToString(",")}]}}"""
    private fun uri(v: String) = """{"type":"uri","value":"$v"}"""
    private fun lit(v: String) = """{"type":"literal","value":${JsonPrimitive(v)}}"""

    @Test
    fun osmQueryAsksOnlyForNotableEateriesShopsAndJewishHeritage() {
        val q = OverpassClient(OkHttpClient(), "ua").buildQuery(here, 1000, 80)
        assertTrue("[\"amenity\"~\"^(restaurant|cafe|pub|bar|biergarten|ice_cream)$\"][\"name\"][~\"^(wikidata|wikipedia|heritage|historic)$\"~\".\"]" in q)
        assertTrue("[\"shop\"][\"name\"][~\"^(wikidata|wikipedia|heritage|historic)$\"~\".\"]" in q)
        assertTrue("[\"religion\"=\"jewish\"][\"name\"]" in q)
        assertTrue("[\"memorial\"=\"stolperstein\"];\nout tags 40;" in q, "Stolpersteine have their own output limit")
    }

    @Test
    fun topicsAndFeaturesFromTagsAndText() {
        assertTrue(Topic.JEWISH in TopicClassifier.fromText("Old Synagogue", "former synagogue in Gmunden"))
        assertTrue(Topic.JEWISH in TopicClassifier.fromText("She emigrated to Israel in 1935."))
        assertFalse(Topic.JEWISH in TopicClassifier.fromText("The castle burned down in 1634."))
        assertTrue(Topic.FILM in TopicClassifier.fromText("The castle was a filming location for a TV series."))
        assertTrue(TopicClassifier.isJewish(mapOf("amenity" to "place_of_worship", "religion" to "jewish")))
        assertTrue(Topic.FOOD in TopicClassifier.fromOsmTags(mapOf("amenity" to "cafe", "wikidata" to "Q1")))
        val svc = DiscoveryService(WikipediaClient(OkHttpClient(), "ua"), OverpassClient(OkHttpClient(), "ua"))
        val merged = svc.merge(
            emptyList(),
            listOf(
                OverpassClient.Element("osm:node/1", here, mapOf("amenity" to "cafe", "name" to "Café Zauner", "wikidata" to "Q1", "cuisine" to "coffee_shop;cake", "start_date" to "1832")),
                OverpassClient.Element("osm:node/2", Geo.destination(here, 0.0, 100.0), mapOf("shop" to "confectionery", "name" to "Old Sweets", "heritage" to "2")),
                OverpassClient.Element("osm:way/3", Geo.destination(here, 90.0, 300.0), mapOf("amenity" to "place_of_worship", "religion" to "jewish", "name" to "Synagogue")),
            ),
            "en",
        )
        val cafe = merged.first { it.name == "Café Zauner" }
        assertEquals(setOf(PlaceFeature.EAT_DRINK), cafe.features)
        assertTrue("cuisine: coffee shop,cake" in cafe.extract!! && "dates from: 1832" in cafe.extract!!)
        assertEquals("amenity: cafe", cafe.category)
        assertEquals(setOf(PlaceFeature.SHOP), merged.first { it.name == "Old Sweets" }.features)
        val syn = merged.first { it.name == "Synagogue" }
        assertTrue(PlaceFeature.JEWISH_HERITAGE in syn.features && Topic.JEWISH in syn.topics)
    }

    @Test
    fun stolpersteineOnAStreetBecomeOnePlace() {
        val svc = DiscoveryService(WikipediaClient(OkHttpClient(), "ua"), OverpassClient(OkHttpClient(), "ua"))
        fun stone(id: Int, d: Double, who: String) = OverpassClient.Element(
            "osm:node/$id", Geo.destination(here, 0.0, d),
            mapOf("memorial" to "stolperstein", "memorial:name" to who, "addr:street" to "Hauptstraße", "inscription" to "Hier wohnte $who"),
        )
        val merged = svc.merge(emptyList(), listOf(stone(1, 0.0, "Anna Levi"), stone(2, 40.0, "Moritz Levi"), stone(3, 800.0, "Ida Klein")), "de")
        assertEquals(2, merged.size)
        val street = merged.first { "Anna Levi" in it.extract!! }
        assertEquals("Stolpersteine, Hauptstraße", street.name)
        assertTrue("2 here" in street.extract!! && "Moritz Levi" in street.extract!!)
        assertTrue(PlaceFeature.JEWISH_HERITAGE in street.features && Topic.JEWISH in street.topics)
    }

    @Test
    fun wikidataParsesFilmLocationsEventsAndJewishConnections() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val q = request.requestUrl!!.queryParameter("query")!!
                assertEquals("gpsradio-test", request.getHeader("User-Agent"))
                return MockResponse().setBody(
                    when {
                        "wdt:P915" in q -> sparql(
                            """{"loc":${uri("http://www.wikidata.org/entity/Q100")},"locLabel":${lit("Schloss Ort")},"coord":${lit("Point(13.8013 47.9105)")},"film":${uri("http://www.wikidata.org/entity/Q7")},"filmLabel":${lit("Schlosshotel Orth")},"date":${lit("1996-01-01T00:00:00Z")}}""",
                            """{"loc":${uri("http://www.wikidata.org/entity/Q100")},"locLabel":${lit("Schloss Ort")},"coord":${lit("Point(13.8013 47.9105)")},"film":${uri("http://www.wikidata.org/entity/Q8")},"filmLabel":${lit("Q8")}}""",
                        )
                        "wdt:P19" in q -> sparql(
                            """{"place":${uri("http://www.wikidata.org/entity/Q200")},"placeLabel":${lit("Gmunden")},"coord":${lit("Point(13.8 47.92)")},"person":${uri("http://www.wikidata.org/entity/Q9")},"personLabel":${lit("Ruth Example")},"personDescription":${lit("Israeli writer")}}""",
                        )
                        "wdt:P585" in q -> sparql(
                            """{"e":${uri("http://www.wikidata.org/entity/Q300")},"eLabel":${lit("Battle of Gmunden")},"eDescription":${lit("1626 peasant battle")},"coord":${lit("Point(13.79 47.93)")},"date":${lit("1626-11-15T00:00:00Z")},"article":${uri("https://en.wikipedia.org/wiki/Battle_of_Gmunden")}}""",
                        )
                        else -> sparql()
                    },
                )
            }
        }
        server.start()
        try {
            val wd = WikidataClient(OkHttpClient(), "gpsradio-test", server.url("/sparql"))
            val films = wd.filmLocations(here, 5_000, "en")
            assertEquals(1, films.size)
            assertEquals("Schloss Ort", films[0].name)
            assertEquals(listOf("Schlosshotel Orth"), films[0].films.map { it.title }, "unlabelled items are dropped")
            assertEquals(1996, films[0].films[0].year)
            assertEquals(47.9105, films[0].point.lat, 1e-6)
            val people = wd.jewishConnections(here, 5_000, "en").single()
            assertEquals("Ruth Example (Israeli writer)", WikidataClient.peopleText(people.people))
            val ev = wd.events(here, 5_000, "en").single()
            assertEquals(1626, ev.year)
            assertEquals("Battle of Gmunden", ev.article)
            assertTrue("wikibase:around" in WikidataClient.eventQuery(here, 5_000, "en", 10))
            assertEquals(-480, WikidataClient.year("-0480-01-01T00:00:00Z"))
        } finally {
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun wikidataEnrichesKnownPlacesAndAddsNewOnes() = runBlocking {
        // No event has an article title here, so Wikipedia is never called.
        val svc = DiscoveryService(WikipediaClient(OkHttpClient(), "ua"), OverpassClient(OkHttpClient(), "ua"))
        val castle = place("wiki:en:1", GeoPoint(47.9105, 13.8013), name = "Schloss Ort").copy(wikidataId = "Q100", extract = "A lake castle.")
        val out = svc.addWikidata(
            listOf(castle),
            films = listOf(WikidataClient.FilmLocation("Q100", "Schloss Ort", castle.point, listOf(WikidataClient.Film("Q7", "Schlosshotel Orth", 1996)))),
            events = listOf(WikidataClient.Event("Q300", "Battle", GeoPoint(47.93, 13.79), 1626, "1626 peasant battle", null)),
            languageBase = "en",
            jewish = listOf(WikidataClient.JewishConnection("Q200", "Gmunden", GeoPoint(47.92, 13.8), listOf(WikidataClient.Person("Q9", "Ruth Example", "Israeli writer")))),
        )
        val c = out.first { it.id == castle.id }
        assertTrue(PlaceFeature.FILM_LOCATION in c.features && Topic.FILM in c.topics)
        assertTrue(c.extract!!.startsWith("Filming location of: Schlosshotel Orth (1996)."))
        val battle = assertNotNull(out.firstOrNull { it.wikidataId == "Q300" })
        assertEquals(1626, battle.eventYear)
        assertTrue(PlaceFeature.HISTORIC_EVENT in battle.features)
        val town = out.first { it.wikidataId == "Q200" }
        assertTrue(PlaceFeature.JEWISH_HERITAGE in town.features && "Birthplace of: Ruth Example (Israeli writer)." == town.extract)
    }

    @Test
    fun promptExplainsTheNewFeaturesAndStaysRespectful() = runBlocking {
        val instr = RadioAgent.narrationInstructions("en-US", HostStyle.ENTERTAINING)
        assertTrue("eat_drink / shop" in instr && "Never invent" in instr && "opening hours, prices, menu items" in instr)
        assertTrue("film_location" in instr && "historic_event" in instr)
        assertTrue("jewish_heritage" in instr && "no humour" in instr)
        assertTrue("never test their knowledge" in HostLine.PREFERENCE_QUESTION.instruction)

        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Story.","annotations":[]}]}]}"""))
        server.start()
        try {
            val agent = RadioAgent(OpenAiClient(OkHttpClient(), { "sk-test" }, server.url("/v1").toString()), { ModelConfig() }, structuredNarration = false)
            val loc = LocationContext(here, 5f, 0, 1.3, 0.0, TravelMode.WALKING)
            val p = place("wd:Q300", Geo.destination(here, 0.0, 200.0), name = "Battle of Gmunden")
                .copy(features = setOf(PlaceFeature.HISTORIC_EVENT, PlaceFeature.JEWISH_HERITAGE), eventYear = 1626)
            val c = EditorialRanker().rank(listOf(p), EditorialRanker.Context(loc, mapOf(Topic.HISTORY to 1.0), HeardHistory(), 0)).single()
            agent.narrate(NarrationRequest(c, loc, "en-US", setOf(Topic.HISTORY), emptyList()))
            val body = server.takeRequest().body.readUtf8()
            assertTrue("historic_event" in body && "jewish_heritage" in body && "event_year\\\":1626" in body, body.take(500))
        } finally {
            runCatching { server.shutdown() }
        }
    }
}
