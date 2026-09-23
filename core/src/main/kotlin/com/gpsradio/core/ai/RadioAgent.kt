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
}

data class Segment(
    val text: String,
    val entityId: String?,
    val title: String,
    val sources: List<SourceRef>,
    val imageUrl: String? = null,
    val point: GeoPoint? = null,
)

data class NarrationRequest(
    val candidate: RankedCandidate,
    val location: LocationContext,
    val language: String,
    val interests: Set<Topic>,
    val recentTitles: List<String>,
    /** Remembered listener preferences, one line each. */
    val profile: List<String> = emptyList(),
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
)

/** A preference the model decided to remember. */
data class MemoryDraft(val category: MemoryCategory, val text: String, val topic: Topic?)

enum class ConversationAction {
    NONE, RESUME_RADIO, PAUSE, SKIP, CHANGE_LANGUAGE, SET_THEME, CLEAR_THEME, NAVIGATE, REFRESH_NEARBY;

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
)

data class ModelConfig(
    val narrationModel: String = "gpt-4.1-mini",
    val conversationModel: String = "gpt-4.1-mini",
    val ttsModel: String = "gpt-4o-mini-tts",
    val ttsVoice: String = "alloy",
    val transcriptionModel: String = "gpt-4o-mini-transcribe",
)

class RadioAgent(
    private val openAi: OpenAiClient,
    private val models: () -> ModelConfig,
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
        }
        val res = openAi.respond(
            OpenAiClient.ResponseRequest(
                model = models().narrationModel,
                instructions = narrationInstructions(req.language),
                input = listOf(OpenAiClient.Message("user", context.toString())),
                maxOutputTokens = 900,
            ),
        )
        val sources = listOfNotNull(c.place.url?.let { SourceRef(c.place.name, it) })
        return Segment(cleanForSpeech(res.text), c.place.id, c.place.name, sources, c.place.imageUrl, c.place.point)
    }

    override suspend fun converse(req: ConversationRequest, onSearching: suspend () -> Unit): ConversationReply {
        // Static rules go in `instructions` (cacheable prefix); per-turn context goes last in `input`.
        val input = req.history.takeLast(12).map {
            OpenAiClient.Message(if (it.fromUser) "user" else "assistant", it.text)
        } + OpenAiClient.Message("developer", "Context (JSON): " + conversationContext(req)) +
            OpenAiClient.Message("user", req.utterance)
        val base = OpenAiClient.ResponseRequest(
            model = models().conversationModel,
            instructions = conversationInstructions(req.language, searchAvailable = false),
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
            base.copy(instructions = conversationInstructions(req.language, searchAvailable = true), webSearch = true),
        )
        return parseReply(searched.text).copy(needsSearch = false, sources = searched.citations)
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

        fun narrationInstructions(language: String): String = """
            You are the host of a personal, location-aware radio station. The listener is moving through
            the real world and hears you through headphones or a car speaker.

            Write ONE short spoken segment about the place described in the JSON input.
            Rules:
            - Speak in ${Languages.displayName(language)} (BCP-47: $language). Keep the original place name, with a short translation if it helps.
            - Use ONLY the facts provided. Do not add dates, numbers, names or claims that are not in the facts.
            - Lead with the single most interesting, specific story or fact, not a generic description.
            - Mention where it is using the given distance and direction, once, naturally.
            - If something is a legend, folklore or disputed, say so explicitly.
            - Stay close to target_length_words. If the facts are thin, be shorter rather than padding.
            - Do not repeat anything from already_told_this_trip. No greetings, no sign-offs, no questions to the listener.
            - Respect listener_profile (remembered preferences): lean into what they like, skip what they avoid, follow their style wishes (e.g. length, pace, detail).
            - Plain spoken text only: no lists, markdown, URLs, or stage directions.
        """.trimIndent()

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

        fun conversationInstructions(language: String, searchAvailable: Boolean): String {
            val search = if (searchAvailable) {
                "Web search is available now: use it to answer, and set needs_search to false."
            } else {
                "Web search is not available in this call. If a good answer needs it (verifying a claim beyond the given facts, " +
                    "anything current like opening hours or events, or much more detail than the facts contain), set needs_search=true " +
                    "and reply with only a very short holding line such as 'Let me check that.' You will be called again with web search."
            }
            return """
                You are the voice of a location-aware radio station, now in a spoken conversation with the listener.
                Answer in ${Languages.displayName(language)} ($language) unless the listener asks to switch.
                The latest developer message holds the current context (location, active story, nearby places, listener profile).

                Behaviour:
                - Resolve references like "that place", "there", "the second one", "tell me more" using active_story, nearby and the conversation.
                - "Tell me more" continues the active story in more depth; do not restart it.
                - For "is that true?" verify: separate documented fact, disputed interpretation, and legend.
                - $search
                - Never invent places. If nothing suitable is known, say so briefly.
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
            }
            putJsonArray("required") {
                listOf("reply", "action", "language", "persist_language", "theme", "entity_id", "remember", "forget", "needs_search")
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
                forget = (obj["forget"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } },
            )
        }
    }
}
