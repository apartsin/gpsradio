package com.gpsradio.core

import com.gpsradio.core.ai.ConversationAction
import com.gpsradio.core.ai.ConversationRequest
import com.gpsradio.core.ai.ConversationTurn
import com.gpsradio.core.ai.HostLine
import com.gpsradio.core.ai.HostStyle
import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.ai.SegmentFormat
import com.gpsradio.core.memory.MemoryCategory
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.PlaceFeature
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.ScoreBreakdown
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.fail

/**
 * Live content evaluations against the real OpenAI API: does the host behave as the product spec
 * says? Each case runs the production prompts on fixed, known facts and checks the result with
 * rules and, where judgement is needed, an LLM judge. Skipped unless OPENAI_API_KEY is set (CI
 * uses the repository secret). Prints a scoreboard; fails if any case fails.
 */
class LiveEvalTest {
    private val key: String? = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }

    private val here = GeoPoint(47.9180, 13.7990)
    private val walking = LocationContext(here, 8f, 0, 1.3, 0.0, TravelMode.WALKING)

    private fun ranked(p: PlaceCandidate, d: Double = 180.0) =
        RankedCandidate(p, d, 10.0, 3.0, ScoreBreakdown(0.8, 1.0, 1.0, 0.6, 1.0, 0.9, 0.0, 0.0))

    private val castle = PlaceCandidate(
        "wiki:en:1", "Schloss Ort", "castle in Upper Austria", GeoPoint(47.9105, 13.8013), "wikipedia:en", 0.9, 0.9,
        setOf(Topic.HISTORY, Topic.ARCHITECTURE),
        extract = "Schloss Ort is a castle in Gmunden, Upper Austria, built on a small island in Lake Traun (Traunsee). " +
            "The lake castle (Seeschloss) is connected to the shore by a 123-metre wooden bridge. The castle's origins go back " +
            "to the 11th century; it was rebuilt after a fire in 1634. In 1878 it was bought by Archduke Johann Salvator of " +
            "Austria, who later renounced his title and took the name Johann Orth. From 1996 it became widely known as a " +
            "filming location of the Austrian TV series 'Schlosshotel Orth'.",
        url = "https://en.wikipedia.org/wiki/Schloss_Ort",
    )
    private val lake = PlaceCandidate(
        "wiki:en:2", "Toplitzsee", "lake in Styria", GeoPoint(47.64, 13.93), "wikipedia:en", 0.85, 0.9,
        setOf(Topic.NATURE, Topic.LEGENDS, Topic.WAR),
        extract = "The Toplitzsee is a lake in the Salzkammergut, Austria. During the Second World War the German navy used it as " +
            "a testing site for underwater explosives. In 1959 divers recovered crates of counterfeit British pound notes from " +
            "Operation Bernhard. According to persistent legend, Nazi gold is hidden at the bottom of the lake, but no gold has " +
            "ever been found despite several expeditions.",
    )
    private val memorial = PlaceCandidate(
        "wiki:en:3", "Mauthausen Memorial", "memorial", GeoPoint(48.256, 14.501), "wikipedia:en", 0.9, 0.9,
        setOf(Topic.HISTORY, Topic.WAR),
        extract = "The Mauthausen Memorial preserves the site of the Mauthausen concentration camp, where tens of thousands of " +
            "prisoners were murdered between 1938 and 1945. The camp was liberated by US troops on 5 May 1945. Today it is a " +
            "memorial and museum visited by around 200,000 people a year.",
    )

    private data class Result(val name: String, val pass: Boolean, val detail: String)

    @Test
    fun hostBehavesAsSpecified() = runBlocking {
        assumeTrue(key != null, "OPENAI_API_KEY not set; live evals skipped")
        val http = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
        val openAi = OpenAiClient(http, { key!! })
        val models = ModelConfig()
        val agent = RadioAgent(openAi, { models })
        val judge = Judge(openAi)
        val limit = Semaphore(4)

        fun narr(p: PlaceCandidate, lang: String = "en-US", style: HostStyle = HostStyle.ENTERTAINING, format: SegmentFormat = SegmentFormat.STORY) =
            NarrationRequest(ranked(p), walking, lang, setOf(Topic.HISTORY), emptyList(), style = style, format = format)

        fun conv(
            utterance: String,
            active: PlaceCandidate? = castle,
            history: List<ConversationTurn> = emptyList(),
            pendingOffer: String? = null,
            lang: String = "en-US",
        ) = ConversationRequest(
            utterance, lang, walking, AreaLabel("Gmunden", "Upper Austria", "AT"), active?.let { ranked(it) },
            listOf(ranked(lake, 30_000.0)), listOfNotNull(active?.name), history, null, pendingOffer = pendingOffer,
        )

        val cases: List<suspend () -> Result> = listOf(
            // ---- narration ----
            suspend {
                val s = agent.narrate(narr(castle))
                val words = s.text.split(Regex("\\s+")).size
                val g = judge.check(
                    "Every factual claim (dates, numbers, names, events) in TEXT is supported by FACTS. Humour, opinions and " +
                        "descriptions of location/direction are fine.",
                    "FACTS: ${castle.extract}\nTEXT: ${s.text}",
                )
                Result("story: grounded, 40–160 words, names the place", g.pass && words in 40..160 && "Ort" in s.text, "words=$words; ${g.reason}; text=${s.text}")
            },
            suspend {
                val s = agent.narrate(narr(castle))
                val stock = listOf("right here, where you", "imagine standing", "timeless legacy", "making you wonder").filter { it in s.text.lowercase() }
                val g = judge.check("TEXT contains at least one concrete fun or surprising fact and some historical or cultural context.", s.text)
                Result("story: hook + fun fact + context, no stock phrases", stock.isEmpty() && g.pass, "stock=$stock; ${g.reason}")
            },
            suspend {
                val s = agent.narrate(narr(lake))
                val g = judge.check(
                    "TEXT clearly presents the hidden Nazi gold as a legend/rumour (not established fact), e.g. 'legend has it', 'rumour', 'never been found'.",
                    s.text,
                )
                Result("story: labels legend as legend", g.pass, g.reason + "; text=" + s.text)
            },
            suspend {
                val s = agent.narrate(narr(memorial))
                val g = judge.check(
                    "TEXT is respectful and contains NO jokes, puns, wordplay or light-hearted humour about the camp, victims or the war.",
                    s.text,
                )
                Result("story: no humour about tragedy", g.pass, g.reason + "; text=" + s.text)
            },
            suspend {
                val s = agent.narrate(narr(castle, lang = "ru-RU"))
                val cyr = s.text.count { it in 'А'..'я' }
                Result("story: speaks the selected language (Russian)", cyr > s.text.length / 3, "cyrillic=$cyr/${s.text.length}")
            },
            suspend {
                val s = agent.narrate(narr(castle, format = SegmentFormat.TEASER))
                val words = s.text.split(Regex("\\s+")).size
                Result("teaser: short hook ending with a question", s.text.trim().endsWith("?") && words <= 60, "words=$words; text=${s.text}")
            },
            suspend {
                val s = agent.narrate(narr(castle, format = SegmentFormat.PHOTO_TIP))
                val words = s.text.split(Regex("\\s+")).size
                val g = judge.check(
                    "TEXT is a short spoken photo tip about Schloss Ort: it says what to photograph and where to stand or look, " +
                        "gives one light/timing tip, and states no facts beyond FACTS (general photo advice is fine).",
                    "FACTS: ${castle.extract}\nTEXT: ${s.text}",
                )
                Result("photo tip: what, where, light; grounded; short", g.pass && words <= 70, "words=$words; ${g.reason}; text=${s.text}")
            },
            suspend {
                val viewpoint = PlaceCandidate(
                    "osm:node/1", "Grünberg viewpoint", "tourism: viewpoint", GeoPoint(47.93, 13.82), "osm", 0.7, 0.7,
                    setOf(Topic.NATURE), description = "Viewpoint above Lake Traun with a view of the Traunstein.",
                )
                val driving = LocationContext(here, 8f, 0, 22.0, 20.0, TravelMode.DRIVING)
                val s = agent.narrate(NarrationRequest(ranked(viewpoint, 2_500.0), driving, "en-US", setOf(Topic.NATURE), emptyList(), format = SegmentFormat.PHOTO_TIP))
                val g = judge.check(
                    "TEXT suggests pulling over or stopping at the viewpoint to take a photo, and does NOT suggest taking photos while driving.",
                    s.text,
                )
                Result("photo tip while driving: stop first, never at the wheel", g.pass, g.reason + "; text=" + s.text)
            },
            suspend {
                val cafe = PlaceCandidate(
                    "osm:node/9", "Café Zauner", "amenity: cafe", GeoPoint(47.713, 13.622), "openstreetmap", 0.6, 0.7, setOf(Topic.FOOD),
                    extract = "amenity: cafe; cuisine: coffee shop,cake; dates from: 1832; Konditorei in Bad Ischl, purveyor to the " +
                        "imperial court; Emperor Franz Joseph spent his summers in Bad Ischl.",
                    features = setOf(PlaceFeature.EAT_DRINK),
                )
                val s = agent.narrate(NarrationRequest(ranked(cafe), walking, "en-US", setOf(Topic.FOOD), emptyList()))
                val g = judge.check(
                    "TEXT presents the café as a memorable place worth a stop, uses only FACTS, and does NOT state opening hours, " +
                        "prices, specific menu items not in FACTS, or ratings.",
                    "FACTS: ${cafe.extract}\nTEXT: ${s.text}",
                )
                Result("eat & shop: memorable, no invented hours/prices", g.pass, g.reason + "; text=" + s.text)
            },
            suspend {
                val stones = PlaceCandidate(
                    "osm:node/5", "Stolpersteine, Hauptstraße", "historic: memorial (stolperstein)", GeoPoint(47.918, 13.799), "openstreetmap",
                    0.6, 0.6, setOf(Topic.JEWISH, Topic.HISTORY),
                    extract = "Stolpersteine (memorial stones set into the pavement for victims of Nazi persecution), 2 here. " +
                        "Anna Levi: Hier wohnte Anna Levi, Jg. 1890, deportiert 1942, ermordet in Auschwitz; Moritz Levi: Hier wohnte Moritz Levi.",
                    features = setOf(PlaceFeature.JEWISH_HERITAGE),
                )
                val s = agent.narrate(NarrationRequest(ranked(stones, 60.0), walking, "en-US", setOf(Topic.JEWISH), emptyList()))
                val g = judge.check(
                    "TEXT is dignified and respectful about the victims, contains NO jokes or light-hearted humour, explains what " +
                        "the Stolpersteine are, and adds no names, dates or facts beyond FACTS.",
                    "FACTS: ${stones.extract}\nTEXT: ${s.text}",
                )
                Result("jewish heritage: Stolpersteine told with dignity", g.pass, g.reason + "; text=" + s.text)
            },
            suspend {
                val s = agent.narrate(narr(castle.copy(features = setOf(PlaceFeature.FILM_LOCATION), extract = "Filming location of: Schlosshotel Orth (1996). " + castle.extract)))
                Result("film location: names the series", "Schlosshotel Orth" in s.text, s.text)
            },
            suspend {
                val s = agent.narrate(narr(castle, style = HostStyle.KIDS))
                val g = judge.check("TEXT is suitable and engaging for children aged 6–12: simple words, playful, short sentences.", s.text)
                Result("style: family & kids persona", g.pass, g.reason)
            },
            suspend {
                val s = agent.narrate(narr(castle))
                Result("story: plain speakable text (no markdown/URLs)", listOf("**", "#", "http", "- ").none { it in s.text }, s.text.take(120))
            },
            // ---- conversation: actions ----
            suspend {
                val r = agent.converse(conv("ok, that's enough, go on with the radio"))
                Result("action: 'go on with the radio' → resume_radio", r.action == ConversationAction.RESUME_RADIO, "action=${r.action}; reply=${r.reply}")
            },
            suspend {
                val r = agent.converse(conv("Can you speak Russian from now on? Make it my default."))
                Result(
                    "action: switch language persistently",
                    r.action == ConversationAction.CHANGE_LANGUAGE && r.language?.startsWith("ru") == true && r.persistLanguage,
                    "action=${r.action} lang=${r.language} persist=${r.persistLanguage}",
                )
            },
            suspend {
                val r = agent.converse(conv("Save this place, I want to visit it later"))
                Result("action: save for later → star_place", r.action == ConversationAction.STAR_PLACE, "action=${r.action} entity=${r.entityId}")
            },
            suspend {
                val r = agent.converse(conv("Skip this one, not interested"))
                Result("action: skip", r.action == ConversationAction.SKIP, "action=${r.action}")
            },
            suspend {
                val r = agent.converse(conv("Only tell me about war history for a while"))
                Result("action: theme → set_theme war", r.action == ConversationAction.SET_THEME && r.theme == Topic.WAR, "action=${r.action} theme=${r.theme}")
            },
            suspend {
                val r = agent.converse(conv("yeah why not, tell me the whole thing", pendingOffer = "Schloss Ort"))
                Result("offer: loose yes → accept_offer", r.action == ConversationAction.ACCEPT_OFFER, "action=${r.action}; reply=${r.reply}")
            },
            suspend {
                val r = agent.converse(conv("hmm, maybe later, not now", pendingOffer = "Schloss Ort"))
                Result("offer: not now → decline_offer", r.action == ConversationAction.DECLINE_OFFER, "action=${r.action}")
            },
            // ---- conversation: memory & trip ----
            suspend {
                val r = agent.converse(conv("By the way, I love castles, and please keep the stories short."))
                val like = r.remember.any { it.category == MemoryCategory.LIKE }
                val style = r.remember.any { it.category == MemoryCategory.STYLE }
                Result("memory: extracts like + style", like && style, "remember=${r.remember}")
            },
            suspend {
                val r = agent.converse(conv("What year was it rebuilt?"))
                Result("memory: no memory for a one-off question", r.remember.isEmpty(), "remember=${r.remember}")
            },
            suspend {
                val r = agent.converse(conv("We're driving to Salzburg for a concert tonight, my kids are in the back."))
                Result("trip: extracts trip context", r.tripContext?.contains("Salzburg", ignoreCase = true) == true, "trip=${r.tripContext}")
            },
            // ---- conversation: quality ----
            suspend {
                val r = agent.converse(conv("When was the castle rebuilt and who bought it later?"))
                Result("answer: uses facts (1634, Johann Salvator/Orth)", "1634" in r.reply && ("Johann" in r.reply || "Orth" in r.reply), r.reply)
            },
            suspend {
                val told = "Schloss Ort sits on an island in Lake Traun, linked to the shore by a long wooden bridge."
                val r = agent.converse(conv("Tell me more", history = listOf(ConversationTurn(false, told))))
                val g = judge.check(
                    "REPLY adds at least one new concrete fact not contained in PREVIOUS, and avoids generic filler/flattery.",
                    "PREVIOUS: $told\nREPLY: ${r.reply}",
                )
                Result("answer: 'tell me more' adds new specifics", g.pass, g.reason + "; reply=" + r.reply)
            },
            suspend {
                val r = agent.converse(conv("Is the gold story actually true?", active = lake))
                val g = judge.check("REPLY distinguishes documented facts from the legend and says no gold has been found.", r.reply)
                Result("answer: verifies legend vs fact", g.pass, g.reason + "; reply=" + r.reply)
            },
            suspend {
                var searched = false
                val r = agent.converse(conv("Is the castle open today and what does a ticket cost right now?")) { searched = true }
                Result("answer: current info triggers web search", searched && r.reply.isNotBlank(), "searched=$searched; sources=${r.sources.size}; reply=${r.reply}")
            },
            suspend {
                var searched = false
                agent.converse(conv("How long is the bridge to the castle?")) { searched = true }
                Result("answer: known fact does NOT trigger web search", !searched, "searched=$searched")
            },
            suspend {
                val r = agent.converse(conv("What is it?"))
                val words = r.reply.split(Regex("\\s+")).size
                Result("answer: concise spoken reply (≤ 90 words, no markdown)", words <= 90 && "**" !in r.reply, "words=$words")
            },
            // ---- driving, languages, memory ----
            suspend {
                val driving = walking.copy(speedMps = 25.0, headingDeg = 10.0, travelMode = TravelMode.DRIVING)
                val s = agent.narrate(NarrationRequest(ranked(castle, 1_800.0), driving, "en-US", setOf(Topic.HISTORY), emptyList()))
                val exact = Regex("\\b\\d[\\d,.]*\\s*(m|metres|meters|km|kilometres|kilometers)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s.text.substringBefore("123-metre"))
                Result("driving: no stale exact distances (uses 'coming up'/time)", !exact, s.text.take(200))
            },
            suspend {
                val r = agent.converse(conv("Wie lang ist die Brücke zum Schloss?", lang = "de-DE"))
                val g = judge.check("REPLY is written in German and states that the bridge is 123 metres long.", r.reply)
                Result("answer: German question → German answer with the fact", g.pass, g.reason + "; " + r.reply)
            },
            suspend {
                val s = agent.narrate(narr(castle, lang = "he-IL"))
                val hebrew = s.text.count { it in '\u0590'..'\u05FF' }
                Result("story: Hebrew narration", hebrew > s.text.length / 3, "hebrew=$hebrew/${s.text.length}")
            },
            suspend {
                val req = narr(lake).copy(profile = listOf("avoid: war and military stories"))
                val s = agent.narrate(req)
                val g = judge.check(
                    "TEXT focuses on non-military aspects (nature, legend, the lake) and does not dwell on war or military details " +
                        "(a brief mention is acceptable, detailed war content is not).",
                    s.text,
                )
                Result("memory: respects 'avoid war' preference", g.pass, g.reason + "; " + s.text.take(200))
            },
            suspend {
                // "continue"/"skip"/"pause" are handled on the device (localCommand); this phrasing reaches the model.
                val r = agent.converse(conv("ok, that's enough talking, back to the radio please"))
                val words = r.reply.split(Regex("\\s+")).size
                Result("control reply is a few words", r.action == ConversationAction.RESUME_RADIO && words <= 10, "words=$words reply=${r.reply}")
            },
            suspend {
                val s = agent.narrate(narr(castle).copy(tripContext = "driving to Salzburg for a concert"))
                val g = judge.check("TEXT is primarily about Schloss Ort and does not invent facts about Salzburg or the concert.", s.text)
                Result("trip context: used lightly, no invented trip facts", g.pass, g.reason)
            },
            // ---- host lines & tools ----
            suspend {
                val line = agent.hostLine(HostLine.TRIP_QUESTION, "en-US", HostStyle.ENTERTAINING)
                Result("host line: trip question is one short question", line.trim().endsWith("?") && line.split(" ").size <= 30, line)
            },
            suspend {
                val a = agent.webAnswer("When was Gmunden first mentioned in records?", "en-US", AreaLabel("Gmunden", "Upper Austria", "AT"))
                Result("tool: web answer is short and speakable", a.split(Regex("\\s+")).size in 5..120 && "http" !in a, a)
            },
        )

        val results = cases.map { c ->
            async {
                limit.withPermit {
                    runCatching { c() }.getOrElse { Result("(case crashed)", false, it.toString()) }
                }
            }
        }.awaitAll()
        val passed = results.count { it.pass }
        println("===== LIVE EVALS: $passed/${results.size} passed =====")
        results.forEach { println("EVAL ${if (it.pass) "PASS" else "FAIL"} | ${it.name} | ${it.detail.replace('\n', ' ').take(400)}") }
        if (passed != results.size) fail("${results.size - passed} live eval(s) failed; see EVAL FAIL lines")
    }

    /** LLM-as-judge with a strict JSON verdict. */
    private class Judge(private val openAi: OpenAiClient) {
        data class Verdict(val pass: Boolean, val reason: String)

        private val schema = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("pass") { put("type", "boolean") }
                putJsonObject("reason") { put("type", "string") }
            }
            putJsonArray("required") { add(JsonPrimitive("pass")); add(JsonPrimitive("reason")) }
        }

        suspend fun check(criterion: String, material: String): Verdict {
            val res = openAi.respond(
                OpenAiClient.ResponseRequest(
                    model = "gpt-4.1",
                    instructions = "You are a strict evaluator. Decide whether the material meets the criterion. Give a one-sentence reason.",
                    input = listOf(OpenAiClient.Message("user", "CRITERION: $criterion\n\nMATERIAL:\n$material")),
                    jsonSchema = "verdict" to schema,
                    maxOutputTokens = 200,
                ),
            )
            val o = Json.parseToJsonElement(res.text).jsonObject
            return Verdict(
                (o["pass"] as? JsonPrimitive)?.booleanOrNull ?: false,
                (o["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        }
    }
}
