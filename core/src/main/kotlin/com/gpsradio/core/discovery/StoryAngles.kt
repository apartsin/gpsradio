package com.gpsradio.core.discovery

import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.OpenAiClient
import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.Topic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale
import kotlin.random.Random

/**
 * The 50 location-aware angles a visitor may enjoy (spec A §37). When the nearby places run out, the non-stop
 * radio researches the next untold angle for the listener's town, then region, then country, so it never runs dry.
 * [countryOk]: the angle still makes sense at country scale (landscape, food, customs); the rest stay local.
 */
enum class StoryAngle(val key: String, val label: String, val hint: String, val topics: Set<Topic>, val countryOk: Boolean = false) {
    // History & heritage
    ORIGINS("origins", "origins and the name", "how the place began and where its name comes from (record vs legend)", setOf(Topic.HISTORY)),
    TURNING_POINTS("turning_points", "turning-point events", "a battle, treaty, fire or decision that changed the place", setOf(Topic.HISTORY)),
    DAILY_LIFE_PAST("daily_life_past", "everyday life in the past", "trades, markets, guilds, how ordinary people lived here", setOf(Topic.HISTORY, Topic.CULTURE)),
    WORK_HERITAGE("work_heritage", "work heritage", "mines, salt, mills, factories, railways: what people worked at", setOf(Topic.INDUSTRY, Topic.HISTORY)),
    ARCHITECTURE("architecture", "architecture", "why the buildings look the way they do: a style, a builder, a detail to spot", setOf(Topic.ARCHITECTURE)),
    DEFENCE("defence", "castles, walls and defence", "fortifications, sieges, watchtowers", setOf(Topic.HISTORY, Topic.ARCHITECTURE)),
    FAITH("faith", "religious heritage", "churches, monasteries, pilgrimages, saints tied to the place", setOf(Topic.HISTORY, Topic.CULTURE)),
    JEWISH("jewish", "Jewish heritage and Israel connections", "communities, synagogues, people, memorials, links to Israel", setOf(Topic.JEWISH)),
    WAR_MEMORY("war_memory", "war and remembrance", "what the wars meant here and how they are remembered (told with dignity)", setOf(Topic.WAR)),
    PREHISTORY("prehistory", "archaeology and prehistory", "Roman, Celtic or older finds and sites", setOf(Topic.HISTORY)),
    RULERS("rulers", "borders and rulers", "who ruled here over the centuries and how borders moved", setOf(Topic.HISTORY)),
    COMMUNITIES("communities", "migration and communities", "groups who came, left or shaped the place", setOf(Topic.HISTORY, Topic.CULTURE)),
    ROYALS("royals", "royal and aristocratic links", "emperors, kings, nobles and their stays here", setOf(Topic.HISTORY)),
    MYSTERIES("mysteries", "documented scandals, crimes and mysteries", "a real case or riddle, clearly separated from rumour", setOf(Topic.UNUSUAL, Topic.HISTORY)),
    DISASTERS("disasters", "disasters and recovery", "plague, flood, fire, avalanche: what happened and how the place recovered", setOf(Topic.HISTORY)),
    // People
    NATIVES("natives", "famous natives", "someone well known who was born here", setOf(Topic.HISTORY, Topic.CULTURE)),
    VISITORS("visitors", "famous visitors and residents", "who lived, worked or stayed here, and why", setOf(Topic.HISTORY, Topic.CULTURE)),
    CHARACTERS("characters", "local characters", "an unsung local hero, eccentric or benefactor", setOf(Topic.UNUSUAL, Topic.CULTURE)),
    FIRSTS("firsts", "inventions and firsts", "something invented, discovered or done first here", setOf(Topic.UNUSUAL, Topic.HISTORY)),
    WOMEN("women", "women who shaped the place", "a woman whose work or life left a mark here", setOf(Topic.HISTORY, Topic.CULTURE)),
    // Arts & culture
    LITERATURE("literature", "the place in literature", "books, poems or writers that feature the place", setOf(Topic.CULTURE)),
    FILM("film", "film and TV locations", "what was filmed here and where", setOf(Topic.FILM)),
    MUSIC("music", "music", "composers, songs about the place, music traditions", setOf(Topic.CULTURE)),
    ART("art", "painted here", "painters and artworks that show the place", setOf(Topic.CULTURE)),
    LEGENDS("legends", "legends and folklore", "a local legend or ghost story, told as a legend", setOf(Topic.LEGENDS), countryOk = true),
    CUSTOMS("customs", "festivals and customs", "seasonal traditions and what they mean", setOf(Topic.CULTURE), countryOk = true),
    DIALECT("dialect", "dialect, place names and sayings", "local words, a saying, what a place name means", setOf(Topic.CULTURE, Topic.UNUSUAL)),
    CRAFTS("crafts", "crafts and specialties", "a traditional craft or product made here", setOf(Topic.CULTURE, Topic.INDUSTRY)),
    // Food & drink
    DISHES("dishes", "signature dishes", "a local dish and the story behind it", setOf(Topic.FOOD), countryOk = true),
    DRINKS("drinks", "wine, beer and spirits", "local drinks, producers and their history", setOf(Topic.FOOD)),
    FOOD_PLACES("food_places", "historic cafés and food institutions", "a café, market or shop with a history", setOf(Topic.FOOD, Topic.HISTORY)),
    FOOD_ORIGINS("food_origins", "food origin stories", "a dish or product invented here", setOf(Topic.FOOD, Topic.UNUSUAL)),
    // Nature & landscape
    GEOLOGY("geology", "how the landscape formed", "glaciers, rock, volcanoes, what shaped what you see", setOf(Topic.NATURE), countryOk = true),
    WATER("water", "lakes, rivers, springs and spas", "the water around here and its story", setOf(Topic.NATURE)),
    MOUNTAINS("mountains", "mountains and viewpoints", "peaks, passes and views, and their stories", setOf(Topic.NATURE, Topic.ATTRACTIONS)),
    WILDLIFE("wildlife", "wildlife and birds", "animals you might spot and when", setOf(Topic.NATURE), countryOk = true),
    TREES_GARDENS("trees_gardens", "ancient trees, parks and gardens", "a notable tree, park or garden", setOf(Topic.NATURE)),
    PHENOMENA("phenomena", "natural phenomena", "local winds, light, fog, tides, sounds or other natural effects", setOf(Topic.NATURE, Topic.UNUSUAL), countryOk = true),
    CLIMATE("climate", "climate and seasons", "why the weather and seasons are the way they are here", setOf(Topic.NATURE)),
    PROTECTED("protected", "protected areas", "a reserve or protected landscape and what it protects", setOf(Topic.NATURE)),
    // Modern life & quirks
    RECORDS("records", "records and superlatives", "the oldest, smallest, highest or first of something", setOf(Topic.UNUSUAL)),
    QUIRKS("quirks", "quirky facts", "an odd, surprising, documented fact", setOf(Topic.UNUSUAL)),
    TRANSPORT("transport", "transport history", "roads, railways, ports, bridges and how people travelled", setOf(Topic.HISTORY, Topic.INDUSTRY)),
    ECONOMY_TODAY("economy_today", "what the place lives from today", "the industries, products or trades of today", setOf(Topic.INDUSTRY)),
    SPORTS("sports", "sports and outdoor culture", "famous athletes, races, sports traditions", setOf(Topic.CULTURE, Topic.ATTRACTIONS)),
    SKY_SCIENCE("sky_science", "science and the sky", "observatories, meteorites, research done here", setOf(Topic.UNUSUAL)),
    STREET_NAMES("street_names", "street names and town planning", "why a street or square has its name, how the town was laid out", setOf(Topic.HISTORY, Topic.UNUSUAL)),
    WORLD_LINKS("world_links", "links abroad", "twin towns and surprising connections to faraway places", setOf(Topic.UNUSUAL, Topic.CULTURE)),
    HIDDEN_GEMS("hidden_gems", "hidden gems", "a little-known spot worth seeing nearby", setOf(Topic.ATTRACTIONS, Topic.UNUSUAL)),
    THEN_AND_NOW("then_and_now", "then and now", "how the place changed: what stood here before", setOf(Topic.HISTORY));

    /** 1 = headliners most visitors love, 2 = strong, 3 = for the curious. The listener's interests lift an angle one tier. */
    val tier: Int get() = when (this) {
        LEGENDS, MYSTERIES, FIRSTS, RECORDS, QUIRKS, FILM, NATIVES, VISITORS, ROYALS, FOOD_ORIGINS, DISHES,
        TURNING_POINTS, PHENOMENA, HIDDEN_GEMS, LITERATURE, ORIGINS, JEWISH -> 1
        ARCHITECTURE, DEFENCE, DISASTERS, MUSIC, ART, CUSTOMS, DRINKS, FOOD_PLACES, WATER, MOUNTAINS, WILDLIFE,
        GEOLOGY, CHARACTERS, WAR_MEMORY, THEN_AND_NOW, WORK_HERITAGE -> 2
        else -> 3
    }

    companion object {
        fun fromKey(key: String?): StoryAngle? = entries.firstOrNull { it.key == key }

        /** The catalogue as prompt lines, headliners first ("- legends and folklore: a local legend…"). */
        fun promptList(): String = entries.sortedBy { it.tier }.joinToString("\n") { "- ${it.label}: ${it.hint}" }
    }
}

/** Where to look: the town, then the region, then the country. */
enum class AngleScope { TOWN, REGION, COUNTRY }

/** One research target: an angle at a scope (or a listener's own request, [custom]). */
data class AngleTarget(val scope: AngleScope, val scopeName: String, val angle: StoryAngle?, val custom: String? = null) {
    val key: String get() = "${scope.name}|$scopeName|${angle?.key ?: "custom:" + custom.orEmpty().lowercase()}"
}

/**
 * Picks the next angle to research: the listener's interests first (a theme narrows to it), each angle once per
 * scope, then the next wider scope. Returns null when everything has been tried.
 */
object AnglePlanner {
    fun scopes(area: AreaLabel?): List<Pair<AngleScope, String>> {
        if (area == null) return emptyList()
        val country = area.countryCode?.takeIf { it.length == 2 }?.let { Locale("", it.uppercase()).getDisplayCountry(Locale.ENGLISH) }
        return listOfNotNull(
            area.city?.takeIf { it.isNotBlank() }?.let { AngleScope.TOWN to it },
            area.region?.takeIf { it.isNotBlank() && it != area.city }?.let { AngleScope.REGION to it },
            country?.takeIf { it.isNotBlank() }?.let { AngleScope.COUNTRY to it },
        )
    }

    /** The angle's tier for this listener: one tier higher when it matches their interests. */
    fun tierFor(angle: StoryAngle, interests: Set<Topic>): Int =
        if (angle.topics.any { it in interests }) maxOf(1, angle.tier - 1) else angle.tier

    /**
     * Angles in the order to try: top tier first, random order within a tier (a fresh mix each trip); a theme
     * narrows to it; avoided topics are left out.
     */
    fun ordered(interests: Set<Topic>, theme: Topic?, avoid: Set<Topic> = emptySet(), random: Random = Random.Default): List<StoryAngle> {
        val pool = StoryAngle.entries.filter { a -> a.topics.none { it in avoid } || a.topics.any { it == theme } }
            .filter { theme == null || theme in it.topics }
        return pool.groupBy { tierFor(it, interests) }.toSortedMap().values.flatMap { it.shuffled(random) }
    }

    /** Town and region before the country; the top tier of a wider scope before the bottom tier of a narrower one. */
    private val scopeTierOrder = listOf(
        AngleScope.TOWN to 1, AngleScope.TOWN to 2, AngleScope.REGION to 1, AngleScope.TOWN to 3,
        AngleScope.REGION to 2, AngleScope.COUNTRY to 1, AngleScope.REGION to 3, AngleScope.COUNTRY to 2, AngleScope.COUNTRY to 3,
    )

    fun next(
        area: AreaLabel?,
        interests: Set<Topic>,
        theme: Topic?,
        tried: Set<String>,
        avoid: Set<Topic> = emptySet(),
        order: List<StoryAngle> = ordered(interests, theme, avoid),
    ): AngleTarget? {
        val scopes = scopes(area).toMap()
        for ((scope, tier) in scopeTierOrder) {
            val name = scopes[scope] ?: continue
            for (a in order) {
                if (tierFor(a, interests) != tier) continue
                if (scope == AngleScope.COUNTRY && !a.countryOk) continue
                val t = AngleTarget(scope, name, a)
                if (t.key !in tried) return t
            }
        }
        return null
    }
}

/** Researched facts for one angle (from the web, with sources), or null when nothing specific exists there. */
fun interface AngleResearch {
    suspend fun research(target: AngleTarget, area: AreaLabel?, point: GeoPoint?, alreadyTold: List<String>): AreaFacet?
}

/**
 * Researches one angle with web search (spec A §37). Returns plain factual notes that the normal narration then
 * retells, so the grounding and style rules stay in one place. "Not found" is a valid answer: better silence on
 * an angle than a generic or invented story.
 */
class AngleScout(
    private val openAi: OpenAiClient,
    private val models: () -> ModelConfig,
) : AngleResearch {
    override suspend fun research(target: AngleTarget, area: AreaLabel?, point: GeoPoint?, alreadyTold: List<String>): AreaFacet? {
        val input = buildJsonObject {
            put("place", target.scopeName)
            put("scope", target.scope.name.lowercase())
            area?.let { put("context", listOfNotNull(it.city, it.region, it.countryCode).joinToString(", ")) }
            point?.let { put("approx_coordinates", "%.3f, %.3f".format(Locale.ROOT, it.lat, it.lon)) }
            if (target.custom != null) {
                put("listener_request", target.custom)
            } else {
                put("angle", target.angle!!.label)
                put("angle_hint", target.angle.hint)
            }
            putJsonArray("already_told") { alreadyTold.takeLast(20).forEach { add(JsonPrimitive(it)) } }
        }
        val res = openAi.respond(
            OpenAiClient.ResponseRequest(
                model = models().researchModel,
                instructions = INSTRUCTIONS,
                input = listOf(OpenAiClient.Message("user", input.toString())),
                webSearch = true,
                userArea = area,
                jsonSchema = "angle_research" to schema,
                maxOutputTokens = 900,
                cacheKey = "gpsradio-angle",
            ),
        )
        val found = parse(res.text) ?: return null
        val url = res.citations.firstOrNull()?.url
        return AreaFacet(
            area = target.scopeName,
            kind = AreaFacetKind.OVERVIEW,
            facts = found.second,
            url = url,
            angle = target.angle?.key ?: "request",
            title = found.first,
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Only items the researcher rates at least this interesting (1–5) are told: "only if interesting". */
        const val MIN_INTEREST = 4

        /** (title, facts) when found and interesting enough; null for "nothing specific here", dull, or unusable output. */
        fun parse(raw: String): Pair<String, String>? {
            val obj = runCatching { json.parseToJsonElement(raw.trim()).jsonObject }.getOrNull() ?: return null
            if (obj["found"]?.jsonPrimitive?.boolean != true) return null
            val interest = obj["interest"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            if (interest < MIN_INTEREST) return null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val facts = obj["facts"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isBlank() || facts.length < 80) return null
            return title to facts.take(AreaFacts.MAX_FACTS_CHARS)
        }

        const val INSTRUCTIONS = """
You research material for a location-aware radio show for visitors. Find ONE specific, verifiable and genuinely
interesting item about "place" for the given "angle" (or for "listener_request" when given). Use web search; prefer
encyclopedic, official, museum or local-history sources.
Rules:
- It must really belong to this place: it happened here, is located here, or is specifically about this place
  (within ~30 km for a town). Not a generic fact about the country or the region's category.
- Skip anything in already_told.
- "facts": 3 to 8 plain sentences of research notes in English: names, dates, numbers, what happened and why it
  matters, and one concrete detail a visitor could see or notice if there is one. No storytelling, no opinions.
  Mark legends and disputed claims as such. Never invent or guess.
- "title": a short name for the item (e.g. "The salt road to Hallstatt").
- "interest" 1–5: how much would a curious visitor enjoy hearing this? 5 = a surprising "wow, really?" story people
  retell; 4 = clearly interesting and specific; 3 = fine but ordinary; 1–2 = dry, generic or trivial. Be strict.
- If nothing specific and verifiable exists for this angle here, return found=false with empty title and facts and
  interest 0. Prefer found=false to a dull or generic item.
"""

        val schema: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("found") { put("type", "boolean") }
                putJsonObject("title") { put("type", "string") }
                putJsonObject("facts") { put("type", "string") }
                putJsonObject("interest") { put("type", "integer") }
            }
            putJsonArray("required") {
                add(JsonPrimitive("found")); add(JsonPrimitive("title")); add(JsonPrimitive("facts")); add(JsonPrimitive("interest"))
            }
        }
    }
}
