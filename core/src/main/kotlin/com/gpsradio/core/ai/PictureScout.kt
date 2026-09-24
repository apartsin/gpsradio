package com.gpsradio.core.ai

import com.gpsradio.core.model.AreaLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * One picture a spoken text calls for (spec A §62): what it shows, where to find it, and where in the text it's
 * mentioned (so the slideshow shows it as it's said).
 */
data class PictureRef(
    /** Shown on the photo, in the listener's language: "Archduke Johann Orth", "The 123 m wooden bridge". */
    val caption: String,
    /** English Wikipedia article title, or null when there's none. */
    val wikipedia: String?,
    /** Wikimedia Commons search words (English) for a photo of it. */
    val search: String,
    /** A short phrase copied from the text where it's mentioned. */
    val quote: String,
)

/** Finds the pictures a spoken text calls for; null-safe (empty when unsure or on error). */
fun interface PictureFinder {
    suspend fun find(text: String, language: String, area: AreaLabel?): List<PictureRef>
}

/**
 * Lists the people, buildings, objects, views and scenes a story or answer names, in the order they're said,
 * using the fast research model (no web search: it only reads the text).
 */
class PictureScout(
    private val openAi: OpenAiClient,
    private val models: () -> ModelConfig,
) : PictureFinder {
    override suspend fun find(text: String, language: String, area: AreaLabel?): List<PictureRef> {
        if (text.length < 40) return emptyList()
        val input = buildJsonObject {
            put("language", language)
            area?.let { put("area", listOfNotNull(it.city, it.region, it.countryCode).joinToString(", ")) }
            put("text", text)
        }
        val res = openAi.respond(
            OpenAiClient.ResponseRequest(
                model = models().researchModel,
                instructions = INSTRUCTIONS,
                input = listOf(OpenAiClient.Message("user", input.toString())),
                jsonSchema = "pictures" to schema,
                maxOutputTokens = 700,
                cacheKey = "gpsradio-pictures",
            ),
        )
        return parse(res.text, text)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        const val INSTRUCTIONS = """
You pick photos for a radio story as it is spoken. From "text", list up to 5 concrete things a listener would like to
SEE, in the order the text mentions them: named people, buildings, monuments, objects, views, landscapes, historical
scenes or events. Skip vague or generic things ("history", "the town" when no specific view is meant).
For each:
- "caption": 2 to 6 words in the "language" of the text, saying exactly what the photo shows ("Эрцгерцог Иоганн Орт").
- "wikipedia": the exact English Wikipedia article title of that specific thing or person, or "" if there is none.
- "search": 2 to 5 English words to find a real photo of it on Wikimedia Commons (include the place name).
- "quote": 1 to 4 words copied EXACTLY from "text" where it is mentioned (same language and spelling).
Never invent things the text doesn't mention.
"""

        val schema: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            putJsonObject("properties") {
                putJsonObject("pictures") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        put("additionalProperties", false)
                        putJsonObject("properties") {
                            for (k in listOf("caption", "wikipedia", "search", "quote")) putJsonObject(k) { put("type", "string") }
                        }
                        putJsonArray("required") { listOf("caption", "wikipedia", "search", "quote").forEach { add(it) } }
                    }
                }
            }
            putJsonArray("required") { add("pictures") }
        }

        /** The pictures, in the order the text mentions them (by where their quote appears), at most 5. */
        fun parse(raw: String, text: String): List<PictureRef> {
            val items = runCatching { (json.parseToJsonElement(raw.trim()).jsonObject["pictures"] as? JsonArray).orEmpty() }.getOrDefault(emptyList())
            fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            return items.mapNotNull { it as? JsonObject }.mapNotNull { o ->
                val caption = o.s("caption").take(60)
                val search = o.s("search").take(80)
                if (caption.isEmpty() || (search.isEmpty() && o.s("wikipedia").isEmpty())) null
                else PictureRef(caption, o.s("wikipedia").ifEmpty { null }?.take(120), search, o.s("quote").take(60))
            }.distinctBy { it.wikipedia ?: it.search }
                .sortedBy { p -> position(text, p.quote) ?: Int.MAX_VALUE }
                .take(5)
        }

        /** Where [quote] is said in [text] (character index), tolerant of case; null if it isn't there. */
        fun position(text: String, quote: String): Int? {
            if (quote.isBlank()) return null
            val i = text.indexOf(quote, ignoreCase = true)
            if (i >= 0) return i
            // Inflected forms: the first word's stem.
            val stem = quote.split(' ').first().let { if (it.length > 5) it.dropLast(2) else it }
            return text.indexOf(stem, ignoreCase = true).takeIf { it >= 0 && stem.length >= 3 }
        }
    }
}
