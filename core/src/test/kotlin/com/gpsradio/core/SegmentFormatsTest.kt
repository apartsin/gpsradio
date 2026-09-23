package com.gpsradio.core

import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.FillerRequest
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.Narrator
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.ai.QuizQuestion
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.ai.Segment
import com.gpsradio.core.ai.SegmentFormat
import com.gpsradio.core.discovery.AreaFacet
import com.gpsradio.core.discovery.AreaFacetKind
import com.gpsradio.core.discovery.AreaFacts
import com.gpsradio.core.discovery.OnThisDayClient
import com.gpsradio.core.discovery.OnThisDayEvent
import com.gpsradio.core.discovery.WikipediaClient
import com.gpsradio.core.editorial.EditorialRanker
import com.gpsradio.core.editorial.HeardHistory
import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SegmentFormatsTest {
    private val gmunden = GeoPoint(47.918, 13.799)
    private val longExtract = "Vienna is the capital of Austria and its largest city, on the Danube. ".repeat(4)

    private val feed = """
        {"events":[
          {"text":"The first ascent of the Weisshorn in Switzerland.","year":1861,"pages":[
            {"title":"Weisshorn","titles":{"normalized":"Weisshorn"},"description":"Mountain in Switzerland",
             "extract":"${"The Weisshorn is a major peak of the Swiss Alps. ".repeat(6)}",
             "coordinates":{"lat":46.1,"lon":7.7},"content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Weisshorn"}}}]},
          {"text":"A treaty is signed in Vienna, Austria.","year":1900,"pages":[
            {"title":"Vienna","titles":{"normalized":"Vienna"},"description":"Capital of Austria","extract":"$longExtract",
             "coordinates":{"lat":48.2,"lon":16.37},"content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Vienna"}},"thumbnail":{"source":"x"}}]},
          {"text":"An earthquake kills hundreds of people in Austria.","year":1850,"pages":[]},
          {"text":"","year":1,"pages":[]}
        ]}
    """.trimIndent()

    // ---- on this day client --------------------------------------------------------------

    @Test
    fun onThisDayParsesTheFeedWithUserAgentAndCaches() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(feed))
        server.start()
        val client = OnThisDayClient(OkHttpClient(), "GpsRadioTest/1.0", { lang -> server.url("/feed/$lang/onthisday/events") })
        val events = client.events("en", 9, 3)
        val req = server.takeRequest()
        assertEquals("/feed/en/onthisday/events/09/03", req.path)
        assertEquals("GpsRadioTest/1.0", req.getHeader("User-Agent"))
        assertEquals(3, events.size) // blank events dropped
        val vienna = events[1]
        assertEquals(1900, vienna.year)
        assertEquals("Vienna", vienna.pages.single().title)
        assertEquals(GeoPoint(48.2, 16.37), vienna.pages.single().point)
        assertEquals("https://en.wikipedia.org/wiki/Vienna", vienna.pages.single().url)
        // Cached: no second request.
        assertEquals(events, client.events("en", 9, 3))
        assertEquals(1, server.requestCount)
        server.shutdown()
    }

    @Test
    fun onThisDayFallsBackToEnglishWhenAnEditionHasNoFeed() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"type":"not_found"}"""))
        server.enqueue(MockResponse().setBody(feed))
        server.start()
        val client = OnThisDayClient(OkHttpClient(), "ua", { lang -> server.url("/feed/$lang") })
        val events = client.events("he", 12, 31)
        assertEquals("/feed/he/12/31", server.takeRequest().path)
        assertEquals("/feed/en/12/31", server.takeRequest().path)
        assertEquals(3, events.size)
        server.shutdown()
    }

    @Test
    fun onThisDayAirsOnlyEventsAboutTheListenersArea() {
        val events = OnThisDayClient.parse(feed)
        val area = AreaLabel(city = "Gmunden", region = "Upper Austria", countryCode = "AT")
        // Gmunden: a treaty in Vienna (190 km, same country) and a Swiss mountain are not local: nothing airs.
        assertNull(OnThisDayClient.pick(events, area, gmunden, "en"))
        // Without any location knowledge nothing is local either.
        assertNull(OnThisDayClient.pick(events, null, null, "en"))
        // In Vienna the treaty is local (named, and its page is right here).
        val vienna = GeoPoint(48.21, 16.37)
        assertEquals(1900, OnThisDayClient.pick(events, AreaLabel("Vienna", "Vienna", "AT"), vienna, "en")?.year)
        // By name alone: an event that mentions the town, without coordinates.
        val named = events + OnThisDayClient.parse(
            """{"events":[{"text":"A great flood hits Gmunden on Lake Traun.","year":1899,"pages":[]}]}""",
        )
        assertEquals(1899, OnThisDayClient.pick(named, area, gmunden, "en")?.year)
        // By distance alone: a page within 100 km, even when the text names neither town nor region.
        val near = OnThisDayClient.parse(
            """{"events":[{"text":"A salt mine opens.","year":1600,"pages":[{"title":"Hallstatt","coordinates":{"lat":47.56,"lon":13.65}}]}]}""",
        )
        assertEquals(1600, OnThisDayClient.pick(near, area, gmunden, "en")?.year)
        // Once told, it isn't picked again.
        assertNull(OnThisDayClient.pick(named, area, gmunden, "en", exclude = setOf(named.last().text)))
        assertNull(OnThisDayClient.pick(emptyList(), area, gmunden, "en"))
        // Country names are still used for ranking among local events.
        assertTrue("Österreich" in OnThisDayClient.areaNames(area, "de").keys)
    }

    // ---- area facts -----------------------------------------------------------------------

    private val article = WikipediaClient.Article(
        "Gmunden",
        """
        Gmunden is a town in Upper Austria on the northern shore of the Traunsee. ${"It is known for its ceramics and its lake castle. ".repeat(5)}

        == History ==
        ${"Salt from the Salzkammergut was shipped from Gmunden for centuries. ".repeat(4)}
        === Modern era ===
        The railway arrived in the 19th century and brought spa guests.

        == Notable people ==
        ${"The composer Johannes Brahms spent summers here. ".repeat(5)}

        == Economy ==
        Short.

        == Culture ==
        Tiny.
        """.trimIndent(),
        "https://en.wikipedia.org/wiki/Gmunden",
    )

    @Test
    fun areaArticleSplitsIntoGroundedFacets() {
        val sections = AreaFacts.sections(article.text)
        assertEquals(listOf("", "History", "Notable people", "Economy", "Culture"), sections.map { it.first })
        // Sub-sections stay with their parent.
        assertTrue("railway" in sections[1].second)
        val facets = AreaFacts.facets("Gmunden", article)
        // Culture is too thin to narrate; economy is not a facet.
        assertEquals(listOf(AreaFacetKind.OVERVIEW, AreaFacetKind.HISTORY, AreaFacetKind.PEOPLE), facets.map { it.kind })
        assertTrue(facets.all { it.facts.length in AreaFacts.MIN_FACTS_CHARS..AreaFacts.MAX_FACTS_CHARS })
        assertEquals("Gmunden#history", facets[1].id)
        assertTrue("Brahms" in facets[2].facts)
        assertEquals("https://en.wikipedia.org/wiki/Gmunden", facets[0].url)
    }

    @Test
    fun wikipediaArticleByTitleFetchesThePlainTextArticle() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"query":{"pages":[{"pageid":5,"title":"Gmunden","extract":${JsonPrimitive(article.text)}}]}}"""))
        server.enqueue(MockResponse().setBody("""{"query":{"pages":[{"title":"Nowhere","missing":true}]}}"""))
        server.start()
        val wiki = WikipediaClient(OkHttpClient(), "ua", { lang -> server.url("/$lang/w/api.php") })
        val a = wiki.articleByTitle("de", "Gmunden")!!
        val path = server.takeRequest().path!!
        assertTrue(path.startsWith("/de/w/api.php") && "titles=Gmunden" in path && "explaintext=1" in path && "exintro" !in path)
        assertEquals("Gmunden", a.title)
        assertEquals("https://de.wikipedia.org/wiki/Gmunden", a.url)
        assertTrue("== History ==" in a.text)
        assertNull(wiki.articleByTitle("de", "Nowhere"))
        server.shutdown()
    }

    // ---- prompts and agent ----------------------------------------------------------------

    @Test
    fun promptsCoverTheNewFormatsAndKeepTheGuardrails() {
        val p = RadioAgent.narrationInstructions("en-US", HostStyle.ENTERTAINING)
        listOf("bumper", "on_this_day", "station_id", "area").forEach { assertTrue("format \"$it\"" in p, it) }
        // Knowledge quizzes are off by product decision: no quiz instructions are paid for on every call.
        assertTrue("format \"quiz\"" !in p)
        assertTrue("never joke about tragedies" in p && "Never invent or embellish facts" in p)
        assertTrue("never invent" in p && "a connection the event doesn't give" in p)
        val c = RadioAgent.conversationInstructions("en-US", searchAvailable = false)
        assertTrue("quiz.answer" !in c && "never test the listener's knowledge" in c)
    }

    @Test
    fun targetLengthsPerFormatNeverExceedThirtySecondsWhenDriving() {
        assertEquals(15, RadioAgent.targetSeconds(TravelMode.WALKING, SegmentFormat.BUMPER))
        assertEquals(30, RadioAgent.targetSeconds(TravelMode.WALKING, SegmentFormat.ON_THIS_DAY))
        assertEquals(RadioAgent.targetSeconds(TravelMode.STATIONARY), RadioAgent.targetSeconds(TravelMode.STATIONARY, SegmentFormat.STORY))
        SegmentFormat.entries.forEach { assertTrue(RadioAgent.targetSeconds(TravelMode.DRIVING, it) <= 30, it.name) }
        assertTrue(SegmentFormat.BUMPER.isFiller && SegmentFormat.AREA.isFiller && !SegmentFormat.TEASER.isFiller)
    }

    @Test
    fun quizReplySplitsIntoQuestionAndReveal() {
        assertEquals(
            "Which lake is it on? The Traunsee or the Attersee? Shout it out!" to "The answer to our quiz: the Traunsee!",
            RadioAgent.splitQuiz("Which lake is it on? The Traunsee or the Attersee? Shout it out!\nANSWER: The answer to our quiz: the Traunsee!"),
        )
        assertEquals("Q?" to "It's B.", RadioAgent.splitQuiz("Q?\n**Answer:** It's B."))
        assertEquals("No answer line here." to null, RadioAgent.splitQuiz("No answer line here."))
        assertEquals("ANSWER: only" to null, RadioAgent.splitQuiz("ANSWER: only"))
    }

    @Test
    fun conversationContextCarriesThePendingQuiz() {
        val req = ConversationRequest(
            "Is it the Traunsee?", "en-US", null, null, null, emptyList(), emptyList(), emptyList(), null,
            quiz = QuizQuestion("Which lake?", "The Traunsee!", "p1"),
        )
        val ctx = RadioAgent.conversationContext(req)
        assertTrue("\"quiz\":{\"question\":\"Which lake?\",\"answer\":\"The Traunsee!\"}" in ctx)
        assertTrue("quiz" in RadioAgent.liveInstructions(req))
    }

    private fun respond(text: String) = MockResponse().setBody(
        """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":${JsonPrimitive(text)},"annotations":[]}]}]}""",
    )

    @Test
    fun agentNarratesQuizBumperAndFillersFromGroundedInput() = runTest {
        val server = MockWebServer()
        server.enqueue(respond("Which lake does the castle stand in?\nANSWER: The answer: the Traunsee!"))
        server.enqueue(respond("The lake castle once hosted an emperor's wedding party."))
        server.enqueue(respond("On this day in 1900, a treaty was signed in Vienna."))
        server.enqueue(respond("Gmunden has shipped salt for centuries."))
        server.enqueue(respond("You're on GPS Radio. So far: Schloss Ort and the Traunsee."))
        server.start()
        val agent = RadioAgent(OpenAiClient(OkHttpClient(), { "sk-test" }, server.url("/v1").toString()), { ModelConfig() })
        val loc = LocationContext(gmunden, 5f, 0, 1.3, 0.0, TravelMode.WALKING)
        val c = EditorialRanker().rank(
            listOf(place("wiki:en:1", Geo.destination(gmunden, 0.0, 200.0), name = "Schloss Ort").copy(extract = "Schloss Ort stands in the Traunsee.")),
            EditorialRanker.Context(loc, mapOf(Topic.HISTORY to 1.0), HeardHistory(), 0),
        ).single()

        val quiz = agent.narrate(NarrationRequest(c, loc, "en-US", setOf(Topic.HISTORY), emptyList(), format = SegmentFormat.QUIZ))
        assertEquals("Which lake does the castle stand in?", quiz.text)
        assertEquals("The answer: the Traunsee!", quiz.quizAnswer)
        val quizBody = server.takeRequest().body.readUtf8()
        assertTrue("format\\\":\\\"quiz" in quizBody && "target_length_words\\\":34" in quizBody, quizBody.take(400))

        val bumper = agent.narrate(NarrationRequest(c, loc, "en-US", setOf(Topic.HISTORY), emptyList(), format = SegmentFormat.BUMPER))
        assertNull(bumper.quizAnswer)
        assertTrue("format\\\":\\\"bumper" in server.takeRequest().body.readUtf8())

        val event = OnThisDayClient.parse(feed)[1]
        val otd = agent.narrateFiller(FillerRequest(SegmentFormat.ON_THIS_DAY, "en-US", loc, event = event, dateLabel = "September 23"))
        assertEquals("On this day", otd.title)
        assertEquals("https://en.wikipedia.org/wiki/Vienna", otd.sources.single().url)
        val otdBody = server.takeRequest().body.readUtf8()
        assertTrue("A treaty is signed in Vienna" in otdBody && "September 23" in otdBody)

        val facet = AreaFacts.facets("Gmunden", article)[1]
        val area = agent.narrateFiller(FillerRequest(SegmentFormat.AREA, "en-US", loc, areaFacet = facet, areaToldFacets = listOf("Gmunden#overview")))
        assertEquals("Gmunden", area.title)
        val areaBody = server.takeRequest().body.readUtf8()
        assertTrue("Salzkammergut" in areaBody && "Gmunden#overview" in areaBody && "facet\\\":\\\"history" in areaBody)

        val id = agent.narrateFiller(FillerRequest(SegmentFormat.STATION_ID, "en-US", loc, recap = listOf("Schloss Ort", "Traunsee")))
        assertEquals("Station ID", id.title)
        assertTrue("Traunsee" in server.takeRequest().body.readUtf8())
        server.shutdown()
    }

    @Test
    fun narratorsWithoutAModelStillProduceGroundedFillers() = runTest {
        val plain = object : Narrator {
            override suspend fun narrate(req: NarrationRequest) = Segment("", null, "", emptyList())
            override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit) = throw UnsupportedOperationException()
        }
        val id = plain.narrateFiller(FillerRequest(SegmentFormat.STATION_ID, "en", null, recap = listOf("A", "B", "C", "D")))
        assertEquals("You're listening to GPS Radio. So far today: B, C and D.", id.text)
        val otd = plain.narrateFiller(FillerRequest(SegmentFormat.ON_THIS_DAY, "en", null, event = OnThisDayEvent(1900, "A treaty.")))
        assertEquals("On this day in 1900: A treaty.", otd.text)
        val area = plain.narrateFiller(
            FillerRequest(SegmentFormat.AREA, "en", null, areaFacet = AreaFacet("Gmunden", AreaFacetKind.HISTORY, "One. Two. Three. Four.")),
        )
        assertEquals("One. Two. Three.", area.text)
        assertFalse(area.text.contains("Four"))
    }
}
