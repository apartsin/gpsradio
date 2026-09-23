package com.gpsradio.core.ai

import com.gpsradio.core.geo.Geo
import com.gpsradio.core.lang.Languages
import com.gpsradio.core.memory.MemoryCategory
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.SourceRef
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Model-facing interface: narration and conversation (spec B §13 responsibilities). */
interface Narrator {
    suspend fun narrate(req: NarrationRequest): Segment
    /** [onSearching] is invoked when the model decides it must search the web before answering. */
    suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit = {}): ConversationReply

    /** A short host line such as the road-trip question, in the listener's language and the host's style. */
    suspend fun hostLine(kind: HostLine, language: String, style: HostStyle): String = kind.fallback

    /** A short, spoken-style answer researched on the web (used by the live voice host as a tool). */
    suspend fun webAnswer(question: String, language: String, area: AreaLabel?): String = "Web search is not available."
}

enum class HostLine(val instruction: String, val fallback: String) {
    TRIP_QUESTION(
        "The listener just started driving. In ONE short, friendly sentence, ask where they are heading today " +
            "(and optionally what they are in the mood for), so you can pick stories along the way.",
        "Looks like we're on the road! Where are we heading today? I'll pick stories along the way.",
    ),
}

data class Segment(
    val text: String,
    val entityId: String?,
    val title: String,
    val sources: List<SourceRef>,
    val imageUrl: String? = null,
    val point: GeoPoint? = null,
    /** Model-judged basis of the story's claims; null when unknown (plain-text reply or on-device notes). */
    val basis: StoryBasis? = null,
    /** Language of [text] when it differs from the session language (on-device notes read a source extract). */
    val language: String? = null,
)

data class NarrationRequest(
    val candidate: RankedCandidate,
    val location: LocationContext,
    val language: String,
    val interests: Set<Topic>,
    val recentTitles: List<String>,
    /** Remembered listener preferences, one line each. */
    val profile: List<String> = emptyList(),
    val style: HostStyle = HostStyle.ENTERTAINING,
    val format: SegmentFormat = SegmentFormat.STORY,
    /** What the listener told us about this trip, e.g. "driving to Salzburg for a concert". */
    val tripContext: String? = null,
)

data class ConversationTurn(val fromUser: Boolean, val text: String)

data class ConversationRequest(
    val utterance: String,
    val language: String,
    val location: LocationContext?,
    val area: AreaLabel?,
    val active: RankedCandidate?,
    val nearby: List<RankedCandidate>,
    val recentTitles: List<String>,
    val history: List<ConversationTurn>,
    val theme: Topic?,
    val profile: List<String> = emptyList(),
    val style: HostStyle = HostStyle.ENTERTAINING,
    val tripContext: String? = null,
    /** A story the radio just offered ("want to hear it?"), awaiting the listener's answer. */
    val pendingOffer: String? = null,
)

/** A preference the model decided to remember. */
data class MemoryDraft(val category: MemoryCategory, val text: String, val topic: Topic?)

enum class ConversationAction {
    NONE, RESUME_RADIO, PAUSE, SKIP, CHANGE_LANGUAGE, SET_THEME, CLEAR_THEME, NAVIGATE, REFRESH_NEARBY,
    ACCEPT_OFFER, DECLINE_OFFER, STAR_PLACE;

    companion object {
        fun parse(s: String?): ConversationAction =
            entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: NONE
    }
}

data class ConversationReply(
    val reply: String,
    val action: ConversationAction = ConversationAction.NONE,
    val language: String? = null,
    val persistLanguage: Boolean = false,
    val theme: Topic? = null,
    val entityId: String? = null,
    val sources: List<SourceRef> = emptyList(),
    val remember: List<MemoryDraft> = emptyList(),
    val forget: List<String> = emptyList(),
    val needsSearch: Boolean = false,
    val tripContext: String? = null,
)

data class ModelConfig(
    val narrationModel: String = "gpt-4.1-mini",
    val conversationModel: String = "gpt-4.1-mini",
    val ttsModel: String = "gpt-4o-mini-tts",
    /** Used by both speech and the live voice (supported by gpt-4o-mini-tts and gpt-realtime). */
    val ttsVoice: String = "coral",
    val transcriptionModel: String = "gpt-4o-mini-transcribe",
    /** Speech-to-speech model for natural, interruptible voice conversation. */
    val realtimeModel: String = "gpt-realtime",
)

class RadioAgent(
    private val openAi: OpenAiClient,
    private val models: () -> ModelConfig,
    /** Ask for {text, basis} JSON so the UI can show how well-founded a story is; plain text still parses. */
    private val structuredNarration: Boolean = true,
) : Narrator {

    override suspend fun narrate(req: NarrationRequest): Segment {
        val c = req.candidate
        val seconds = targetSeconds(req.location.travelMode)
        val context = buildJsonObject {
            put("place_name", c.place.name)
            put("category", c.place.category)
            put("distance", describeDistance(c.distanceM))
            put("direction", describeDirection(c, req.location))
            put("travel_mode", req.location.travelMode.name.lowercase())
            put("facts_source", c.place.source)
            put("facts", (c.place.extract ?: c.place.description ?: "").take(MAX_FACTS_CHARS))
            put("listener_interests", buildJsonArray { req.interests.forEach { add(JsonPrimitive(it.key)) } })
            if (req.profile.isNotEmpty()) put("listener_profile", buildJsonArray { req.profile.forEach { add(JsonPrimitive(it)) } })
            put("already_told_this_trip", buildJsonArray { req.recentTitles.takeLast(8).forEach { add(JsonPrimitive(it)) } })
            put("target_length_words", (seconds * 2.3).toInt())
            put("format", req.format.name.lowercase())
            req.tripContext?.let { put("trip", it) }
        }
        val request = OpenAiClient.ResponseRequest(
            model = models().narrationModel,
            instructions = narrationInstructions(req.language, req.style),
            input = listOf(OpenAiClient.Message("user", context.toString())),
            jsonSchema = if (structuredNarration) "radio_story" to storySchema else null,
            maxOutputTokens = 900,
        )
        val res = if (structuredNarration) respondStructured(request) else openAi.respond(request)
        val (text, basis) = parseNarration(res.text)
        val sources = listOfNotNull(c.place.url?.let { SourceRef(c.place.name, it) })
        return Segment(text, c.place.id, c.place.name, sources, c.place.imageUrl, c.place.point, basis = basis)
    }

    override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit): ConversationReply {
        // Static rules go in `instructions` (cacheable prefix); per-turn context goes last in `input`.
        val input = req.history.takeLast(12).map {
            OpenAiClient.Message(if (it.fromUser) "user" else "assistant", it.text)
        } + OpenAiClient.Message("developer", "Context (JSON): " + conversationContext(req)) +
            OpenAiClient.Message("user", req.utterance)
        val base = OpenAiClient.ResponseRequest(
            model = models().conversationModel,
            instructions = conversationInstructions(req.language, searchAvailable = false, style = req.style),
            input = input,
            webSearch = false,
            userArea = req.area,
            jsonSchema = "radio_reply" to replySchema,
            maxOutputTokens = 1200,
        )
        // Fast path without web search; only search when the model says it needs to.
        val first = respondStructured(base)
        val firstReply = parseReply(first.text)
        if (!firstReply.needsSearch) return firstReply.copy(sources = first.citations)

        onSearching()
        val searched = respondStructured(
            base.copy(instructions = conversationInstructions(req.language, searchAvailable = true, style = req.style), webSearch = true),
        )
        return parseReply(searched.text).copy(needsSearch = false, sources = searched.citations)
    }

    override suspend fun hostLine(kind: HostLine, language: String, style: HostStyle): String = runCatching {
        openAi.respond(
            OpenAiClient.ResponseRequest(
                model = models().narrationModel,
                instructions = "You are ${style.persona} Speak ${Languages.displayName(language)} ($language). " +
                    "Output only the spoken line, plain text.",
                input = listOf(OpenAiClient.Message("user", kind.instruction)),
                maxOutputTokens = 120,
            ),
        ).text.let(::cleanForSpeech)
    }.getOrElse { kind.fallback }

    override suspend fun webAnswer(question: String, language: String, area: AreaLabel?): String {
        val res = openAi.respond(
            OpenAiClient.ResponseRequest(
                model = models().conversationModel,
                instructions = "Research the question with web search and answer in 2–4 short sentences suitable for reading " +
                    "aloud, in ${Languages.displayName(language)}. Facts only; say when something is uncertain or disputed. " +
                    "Plain text, no URLs or lists.",
                input = listOf(OpenAiClient.Message("user", question)),
                webSearch = true,
                userArea = area,
                maxOutputTokens = 500,
            ),
        )
        return cleanForSpeech(res.text)
    }

    private suspend fun respondStructured(req: OpenAiClient.ResponseRequest): OpenAiClient.ResponseResult = try {
        openAi.respond(req)
    } catch (e: OpenAiException) {
        // Some model/tool combinations reject structured output; only then fall back to plain text.
        val msg = e.message.orEmpty().lowercase()
        if (e.status != 400 || listOf("schema", "format", "json").none { it in msg }) throw e
        openAi.respond(req.copy(jsonSchema = null))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Enough for a rich story; keeps per-call tokens (and cost) bounded. */
        const val MAX_FACTS_CHARS = 1500

        fun targetSeconds(mode: TravelMode): Int = when (mode) {
            TravelMode.DRIVING -> 30
            TravelMode.WALKING, TravelMode.UNKNOWN -> 40
            TravelMode.STATIONARY -> 50
        }

        fun describeDistance(m: Double): String = when {
            m < 60 -> "right here"
            m < 1000 -> "about ${((m / 50).toInt().coerceAtLeast(1)) * 50} metres"
            else -> "about ${"%.1f".format(m / 1000)} km"
        }

        fun describeDirection(c: RankedCandidate, loc: LocationContext): String {
            if (c.distanceM < 60) return "at your location"
            val heading = loc.headingDeg
            return if (heading != null && loc.travelMode != TravelMode.STATIONARY) {
                Geo.relativeDirection(c.bearingDeg, heading) + " (" + Geo.compass(c.bearingDeg) + ")"
            } else "to the " + Geo.compass(c.bearingDeg)
        }

        /** Strip markdown so TTS does not read symbols aloud. */
        fun cleanForSpeech(s: String): String = s
            .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("[*_#`>]+"), "")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()

        fun narrationInstructions(language: String, style: HostStyle = HostStyle.ENTERTAINING): String = """
            You host a personal, location-aware radio show. The listener is out in the real world (walking or driving)
            and hears you through headphones or the car speakers. You are ${style.persona}

            Write ONE spoken segment about the place in the JSON input.
            Facts:
            - Every factual claim (dates, numbers, names, events) must come from "facts". Never invent or embellish facts.
            - Label legends, folklore and disputed claims as such ("the story goes…", "locals insist…").
            - Humour and comparisons are welcome but must not add new facts, and never joke about tragedies, victims, war or disasters.
            Craft:
            - Open with a hook: the most surprising, specific or human detail. Never start with "Welcome", "Did you know" every time, or the place's name followed by "is a".
            - Blend story, one memorable fun fact, and the context that makes it matter (who, why, what changed).
            - Say where it is once, naturally, using the given distance and direction ("just ahead on your left, about 200 metres").
            - Sound like speech, not an encyclopedia: short sentences, contractions, vivid verbs, the occasional rhetorical question.
            - Stay close to target_length_words; with thin facts, be shorter rather than padding.
            - Do not repeat anything from already_told_this_trip. No greetings or sign-offs.
            - If "trip" is given, you may connect the place to where the listener is heading, briefly.
            - Respect listener_profile: lean into what they like, avoid what they avoid, follow their style wishes.
            - format "teaser": instead of the full story, give a one or two sentence irresistible hook and end by asking
              whether they want to hear the story (for example "Want the full story?"). Do not tell the story itself yet.
            - Speak ${Languages.displayName(language)} ($language). Keep original place names, adding a short translation when useful.
            - The spoken text is plain speech only: no lists, markdown, URLs, emojis or stage directions.
            - When a JSON format is requested, put the spoken segment in "text" and set "basis" to how well-founded
              its claims are: documented (all from the facts), disputed (includes contested claims), legend (mostly
              folklore or legend), mixed (documented facts plus some legend or disputed claims).
        """.trimIndent()

        /** Structured narration: the spoken text plus the basis of its claims. */
        val storySchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("text") { put("type", "string") }
                putJsonObject("basis") {
                    put("type", "string")
                    putJsonArray("enum") { StoryBasis.entries.forEach { add(JsonPrimitive(it.key)) } }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("text")); add(JsonPrimitive("basis")) }
        }

        /** Parses {text, basis} JSON, or treats the whole reply as plain spoken text (basis unknown). */
        fun parseNarration(raw: String): Pair<String, StoryBasis?> {
            val trimmed = raw.trim()
            if (trimmed.startsWith("{")) {
                val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
                val text = (obj?.get("text") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                if (obj != null && text != null) {
                    return cleanForSpeech(text) to StoryBasis.parse((obj["basis"] as? JsonPrimitive)?.contentOrNull)
                }
            }
            return cleanForSpeech(trimmed) to null
        }

        fun conversationContext(req: ConversationRequest): String {
            val loc = req.location
            val ctx = buildJsonObject {
                put("language", req.language)
                if (loc != null) {
                    val q = Geo.quantize(loc.point, 3)
                    put("approx_coordinates", "${q.lat}, ${q.lon}")
                    put("travel_mode", loc.travelMode.name.lowercase())
                    loc.headingDeg?.let { put("heading", Geo.compass(it)) }
                }
                req.area?.let { a ->
                    put("area", listOfNotNull(a.city, a.region, a.countryCode).joinToString(", "))
                }
                req.theme?.let { put("active_theme", it.key) }
                req.tripContext?.let { put("trip", it) }
                req.pendingOffer?.let { put("pending_offer", it) }
                if (req.profile.isNotEmpty()) putJsonArray("listener_profile") { req.profile.forEach { add(JsonPrimitive(it)) } }
                req.active?.let { a ->
                    putJsonObject("active_story") {
                        put("entity_id", a.place.id)
                        put("name", a.place.name)
                        put("distance", describeDistance(a.distanceM))
                        if (loc != null) put("direction", describeDirection(a, loc))
                        put("facts", (a.place.extract ?: a.place.description ?: "").take(MAX_FACTS_CHARS))
                        a.place.url?.let { put("source_url", it) }
                    }
                }
                putJsonArray("nearby") {
                    req.nearby.take(10).forEach { n ->
                        add(buildJsonObject {
                            put("entity_id", n.place.id)
                            put("name", n.place.name)
                            put("category", n.place.category)
                            put("distance", describeDistance(n.distanceM))
                            if (loc != null) put("direction", describeDirection(n, loc))
                            put("summary", (n.place.extract ?: n.place.description ?: "").take(280))
                        })
                    }
                }
                putJsonArray("recently_narrated") { req.recentTitles.takeLast(8).forEach { add(JsonPrimitive(it)) } }
            }
            return ctx.toString()
        }

        fun conversationInstructions(language: String, searchAvailable: Boolean, style: HostStyle = HostStyle.ENTERTAINING): String {
            val search = if (searchAvailable) {
                "Web search is available now: use it to answer, and set needs_search to false."
            } else {
                "Web search is not available in this call. If a good answer needs it (verifying a claim beyond the given facts, " +
                    "anything current like opening hours or events, or much more detail than the facts contain), set needs_search=true " +
                    "and reply with only a very short holding line such as 'Let me check that.' You will be called again with web search."
            }
            return """
                You are the host of a location-aware radio show, now talking with the listener. You are ${style.persona}
                Talk like a real person on the radio: natural, warm, concise, with personality. Facts must come from the
                context or from web search; humour must never add facts, and never joke about tragedies.
                Answer in ${Languages.displayName(language)} ($language) unless the listener asks to switch.
                The latest developer message holds the current context (location, active story, nearby places, listener profile).

                Behaviour:
                - Resolve references like "that place", "there", "the second one", "tell me more" using active_story, nearby and the conversation.
                - "Tell me more" continues the active story in more depth; do not restart it.
                - For "is that true?" verify: separate documented fact, disputed interpretation, and legend.
                - $search
                - Never invent places. If nothing suitable is known, say so briefly.
                - You may ask ONE short clarifying or refining question when it genuinely helps (for example which place
                  they mean, or where they are heading), but never quiz the listener repeatedly.
                - If pending_offer is set, the listener is answering "do you want to hear that story?": yes → accept_offer
                  (reply with at most a few words like "Here we go."), no → decline_offer (acknowledge lightly).
                - If they tell you about their trip (destination, purpose, time available, who is with them), put a short
                  summary in "trip_context"; otherwise null.
                - Replies are spoken aloud: concise (usually 2–5 sentences), plain text, no lists, no markdown, no URLs.
                - If the listener is driving, never ask them to look at the screen.

                Set "action":
                - resume_radio: listener says continue / go on with the radio / that's all.
                - pause: listener asks you to be quiet or stop for now.
                - skip: listener wants to skip the current story.
                - change_language: listener asks to speak another language; set "language" to a BCP-47 tag and reply in that language. persist_language=true only if they explicitly ask to make it their default.
                - set_theme: listener wants a theme (theme one of: ${Topic.entries.joinToString { it.key }}); clear_theme to remove it.
                - navigate: listener wants to go to a place; set entity_id from nearby/active_story.
                - refresh_nearby: listener asks what else is nearby and the list is empty or stale.
                - accept_offer / decline_offer: answer to pending_offer (see above).
                - star_place: listener wants to save/star/favourite a place for later; set entity_id (active story if unclear).
                - none: otherwise.

                Memory (persists across sessions; listener_profile shows what is already remembered):
                - Add to "remember" only durable preferences or facts the listener states or clearly implies
                  ("I love castles", "no war stories please", "keep it shorter", "we travel with kids", "remember that I'm vegetarian").
                  Not one-off requests about the current moment. category: like | avoid | style | about_me;
                  topic: one of the theme keys when it clearly maps to one, else null. Acknowledge briefly in the reply.
                - Add to "forget" the text of remembered items the listener asks to drop or contradicts.
                - Otherwise leave both arrays empty.
            """.trimIndent()
        }

        /** System prompt for the live (Realtime) voice host; context is embedded because the session is long-lived. */
        fun liveInstructions(req: ConversationRequest): String = """
            You are the host of a location-aware radio show, talking live by voice with the listener, like a phone call.
            You are ${req.style.persona}
            Speak ${Languages.displayName(req.language)} (${req.language}) unless the listener switches language.

            How to talk:
            - Sound like a real person: warm, relaxed, expressive, with natural rhythm. Short turns (1–4 sentences); it's a conversation, not a lecture.
            - If the listener interrupts, stop and listen. Ask at most one short clarifying question when it genuinely helps.
            - Facts must come from the context below or from web_search; never invent places or facts. Label legends as legends.
              Content-related humour is welcome; never joke about tragedies.
            - If the listener is driving, never ask them to look at the screen.

            Tools:
            - web_search: for anything beyond the context facts (verification, current info, more depth). Say a quick filler first.
            - radio_control: resume_radio when they're done or say "continue"; pause; skip; change_language; set_theme/clear_theme;
              navigate; star_place when they want to save a place; accept_offer / decline_offer to answer pending_offer.
            - remember: durable preferences they state ("I love castles", "keep it short"); acknowledge briefly.
            - set_trip: when they tell you where they're heading or what the trip is about.

            If pending_offer is set, you just asked whether they want to hear that story: a yes → radio_control accept_offer
            (say at most "Here we go"); a no → decline_offer and a light acknowledgement.

            Context (JSON): ${conversationContext(req)}
        """.trimIndent()

        val replySchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("reply") { put("type", "string") }
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") { ConversationAction.entries.forEach { add(JsonPrimitive(it.name.lowercase())) } }
                }
                putJsonObject("language") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
                putJsonObject("persist_language") { put("type", "boolean") }
                putJsonObject("theme") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
                putJsonObject("entity_id") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
                putJsonObject("remember") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        put("additionalProperties", false)
                        putJsonObject("properties") {
                            putJsonObject("category") {
                                put("type", "string")
                                putJsonArray("enum") { MemoryCategory.entries.forEach { add(JsonPrimitive(it.name.lowercase())) } }
                            }
                            putJsonObject("text") { put("type", "string") }
                            putJsonObject("topic") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
                        }
                        putJsonArray("required") { listOf("category", "text", "topic").forEach { add(JsonPrimitive(it)) } }
                    }
                }
                putJsonObject("forget") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("needs_search") { put("type", "boolean") }
                putJsonObject("trip_context") { putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) } }
            }
            putJsonArray("required") {
                listOf("reply", "action", "language", "persist_language", "theme", "entity_id", "remember", "forget", "needs_search", "trip_context")
                    .forEach { add(JsonPrimitive(it)) }
            }
        }

        fun parseReply(text: String): ConversationReply {
            val obj = runCatching {
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
            }.getOrNull() ?: return ConversationReply(reply = cleanForSpeech(text))
            fun str(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
            return ConversationReply(
                reply = cleanForSpeech(str("reply") ?: text),
                action = ConversationAction.parse(str("action")),
                language = str("language"),
                persistLanguage = (obj["persist_language"] as? JsonPrimitive)?.booleanOrNull ?: false,
                theme = str("theme")?.let { Topic.fromKey(it) },
                entityId = str("entity_id"),
                remember = (obj["remember"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val cat = MemoryCategory.parse((o["category"] as? JsonPrimitive)?.contentOrNull) ?: return@mapNotNull null
                    val t = (o["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    MemoryDraft(cat, t, (o["topic"] as? JsonPrimitive)?.contentOrNull?.let { Topic.fromKey(it) })
                },
                needsSearch = (obj["needs_search"] as? JsonPrimitive)?.booleanOrNull ?: false,
                tripContext = str("trip_context"),
                forget = (obj["forget"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } },
            )
        }
    }
}
