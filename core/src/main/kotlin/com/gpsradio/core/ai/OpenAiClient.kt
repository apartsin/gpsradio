package com.gpsradio.core.ai

import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.SourceRef
import com.gpsradio.core.net.HttpException
import com.gpsradio.core.net.fetchBytes
import com.gpsradio.core.net.fetchString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAiException(val status: Int, message: String) : Exception(message) {
    /** Worth retrying later (auth problems, rate limits, server errors) rather than blaming the content. */
    val isTransient: Boolean get() = status == 401 || status == 403 || status == 408 || status == 429 || status >= 500

    /** The key's credit or billing limit is used up (not a short rate limit): the listener has to act. */
    val isQuotaExhausted: Boolean get() = QuotaErrors.matches(message)
}

/** Recognises OpenAI's "out of credit / billing limit" errors in HTTP bodies, Realtime events and messages. */
object QuotaErrors {
    /** Prefix of the friendly message; [matches] recognises it too. */
    const val MESSAGE = "OpenAI credit ran out"

    private val markers = listOf(
        MESSAGE.lowercase(), "insufficient_quota", "exceeded your current quota", "billing_hard_limit",
        "billing hard limit", "check your plan and billing",
    )

    fun matches(text: String?): Boolean {
        val t = text?.lowercase() ?: return false
        return markers.any { it in t }
    }
}

/**
 * Minimal direct client for the OpenAI REST API (Responses, speech, transcription).
 * The key comes from the user's device settings; nothing is proxied through a backend.
 */
class OpenAiClient(
    private val http: OkHttpClient,
    private val apiKey: () -> String,
    private val baseUrl: String = "https://api.openai.com/v1",
    /** Estimated spend (spec A §41); null in tests that don't care. */
    private val meter: com.gpsradio.core.cost.CostMeter? = null,
) {
    data class Message(val role: String, val content: String)

    data class ResponseRequest(
        val model: String,
        val instructions: String,
        val input: List<Message>,
        val webSearch: Boolean = false,
        val userArea: AreaLabel? = null,
        /** JSON schema for structured output, or null for free text. */
        val jsonSchema: Pair<String, JsonObject>? = null,
        val maxOutputTokens: Int? = null,
        /**
         * Routes requests that share a long static prefix (the instructions) to the same cache, so
         * OpenAI's automatic prompt caching hits more often (cheaper, faster).
         */
        val cacheKey: String? = null,
    )

    data class ResponseResult(
        val text: String,
        val citations: List<SourceRef>,
        /** Input tokens served from OpenAI's prompt cache (usage.input_tokens_details.cached_tokens). */
        val cachedTokens: Int = 0,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        /** Web searches the model ran (billed per call). */
        val webSearches: Int = 0,
    )

    /** Models this key can't use (not found or no access); requests go straight to their fallback. */
    private val unavailableModels = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Sends [req], falling back along [MODEL_FALLBACKS] when the model doesn't exist or this key has no access to it
     * (spec A §53), so a newer default model never breaks the radio on an older account.
     */
    suspend fun respond(req: ResponseRequest): ResponseResult {
        var model = req.model
        while (model in unavailableModels) model = MODEL_FALLBACKS[model] ?: break
        return try {
            respondOnce(if (model == req.model) req else req.copy(model = model))
        } catch (e: OpenAiException) {
            val next = MODEL_FALLBACKS[model]
            if (next == null || !isModelUnavailable(e)) throw e
            unavailableModels += model
            respond(req.copy(model = next))
        }
    }

    private suspend fun respondOnce(req: ResponseRequest): ResponseResult {
        val body = buildJsonObject {
            put("model", req.model)
            put("instructions", req.instructions)
            put("store", false)
            put("input", buildJsonArray {
                req.input.forEach { m -> add(buildJsonObject { put("role", m.role); put("content", m.content) }) }
            })
            if (req.webSearch) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "web_search")
                        val area = req.userArea
                        if (area != null && (area.city != null || area.countryCode != null)) {
                            put("user_location", buildJsonObject {
                                put("type", "approximate")
                                area.city?.let { put("city", it) }
                                area.region?.let { put("region", it) }
                                area.countryCode?.let { put("country", it) }
                            })
                        }
                    })
                })
            }
            req.jsonSchema?.let { (name, schema) ->
                put("text", buildJsonObject {
                    put("format", buildJsonObject {
                        put("type", "json_schema")
                        put("name", name)
                        put("strict", true)
                        put("schema", schema)
                    })
                })
            }
            req.cacheKey?.let { put("prompt_cache_key", it) }
            val reasoning = isReasoningModel(req.model)
            if (reasoning) put("reasoning", buildJsonObject { put("effort", reasoningEffort(req.model)) })
            // For reasoning models the cap also covers hidden reasoning tokens; leave generous room.
            req.maxOutputTokens?.let { put("max_output_tokens", if (reasoning) maxOf(it, 4000) else it) }
        }
        val raw = call { http.fetchString(post("/responses", body.toString().toRequestBody(JSON))) }
        return parseResponse(raw).also { r ->
            val kind = if (req.webSearch) com.gpsradio.core.cost.CostMeter.Kind.RESEARCH else com.gpsradio.core.cost.CostMeter.Kind.STORIES
            meter?.recordResponse(req.model, r.inputTokens, r.cachedTokens, r.outputTokens, r.webSearches, kind)
        }
    }

    /** Text-to-speech; returns MP3 bytes. */
    suspend fun speech(text: String, model: String, voice: String, instructions: String? = null): ByteArray {
        val body = buildJsonObject {
            put("model", model)
            put("voice", voice)
            put("input", text)
            put("response_format", "mp3")
            if (instructions != null && model.contains("gpt")) put("instructions", instructions)
        }
        return call { http.fetchBytes(post("/audio/speech", body.toString().toRequestBody(JSON))) }
            .also { meter?.recordSpeech(text.length) }
    }

    /** Speech-to-text for a recorded utterance. Language is auto-detected so users can switch by voice. */
    suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, model: String, prompt: String? = null): String {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
        // Nearby place names help the recognizer with proper nouns.
        if (!prompt.isNullOrBlank()) builder.addFormDataPart("prompt", prompt.take(800))
        val body = builder
            .addFormDataPart("file", fileName, audio.toRequestBody(mimeType.toMediaType()))
            .build()
        val raw = call { http.fetchString(post("/audio/transcriptions", body)) }
        meter?.recordTranscription()
        return json.parseToJsonElement(raw).jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    }

    private fun post(path: String, body: okhttp3.RequestBody): Request {
        val key = apiKey()
        if (key.isBlank()) throw OpenAiException(401, "No OpenAI API key configured")
        return Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (e: HttpException) {
        throw OpenAiException(e.code, friendlyError(e))
    }

    private fun friendlyError(e: HttpException): String = friendlyError(e.code, e.message?.substringAfter(": ", ""))

    companion object {
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        /** User-facing message for an OpenAI HTTP error; quota exhaustion starts with [QuotaErrors.MESSAGE]. */
        fun friendlyError(code: Int, body: String?): String {
            val error = body?.let { runCatching { json.parseToJsonElement(it).jsonObject["error"]?.jsonObject }.getOrNull() }
            val detail = error?.get("message")?.jsonPrimitive?.contentOrNull
            val type = listOfNotNull(error?.get("code")?.jsonPrimitive?.contentOrNull, error?.get("type")?.jsonPrimitive?.contentOrNull)
            if ((code == 429 || code == 402 || code == 403) && 
                // The body may be truncated (not valid JSON), so the raw text is checked too.
                (type.any(QuotaErrors::matches) || QuotaErrors.matches(detail) || QuotaErrors.matches(body))
            ) {
                return QuotaErrors.MESSAGE + " (insufficient_quota)" + (detail?.let { ": $it" } ?: "")
            }
            return when (code) {
                401 -> "OpenAI rejected the API key"
                429 -> "OpenAI rate limit reached" + (detail?.let { ": $it" } ?: "")
                else -> "OpenAI error $code" + (detail?.let { ": $it" } ?: "")
            }
        }

        /** Newer default models step down to one every account has. */
        val MODEL_FALLBACKS = mapOf("gpt-5.1" to "gpt-5", "gpt-5" to "gpt-4.1", "gpt-5-mini" to "gpt-4.1-mini")

        /** "The model does not exist or you do not have access to it" (404, or 400/403 naming the model). */
        fun isModelUnavailable(e: OpenAiException): Boolean {
            val m = e.message.orEmpty().lowercase()
            return e.status == 404 || (e.status in setOf(400, 403) && "model" in m && ("not exist" in m || "not found" in m || "access" in m))
        }

        /** Lowest effort each model accepts: stories need good writing, not long deliberation, and speed matters. */
        fun reasoningEffort(model: String): String = when {
            model.startsWith("gpt-5.1") -> "none"
            model == "gpt-5" || model.startsWith("gpt-5-") -> "minimal"
            else -> "low"
        }

        fun isReasoningModel(model: String): Boolean =
            model.startsWith("gpt-5") || Regex("^o\\d").containsMatchIn(model)

        /** Collects output_text parts and url_citation annotations from a Responses API payload. */
        fun parseResponse(raw: String): ResponseResult {
            val root = json.parseToJsonElement(raw).jsonObject
            val text = StringBuilder()
            val cites = LinkedHashMap<String, SourceRef>()
            (root["output"] as? JsonArray).orEmpty().forEach { item ->
                val o = item.jsonObject
                if (o["type"]?.jsonPrimitive?.contentOrNull != "message") return@forEach
                (o["content"] as? JsonArray).orEmpty().forEach { part ->
                    val p = part.jsonObject
                    if (p["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                        text.append(p["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        (p["annotations"] as? JsonArray).orEmpty().forEach { a ->
                            val ao = a.jsonObject
                            if (ao["type"]?.jsonPrimitive?.contentOrNull == "url_citation") {
                                val url = ao["url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                                cites.putIfAbsent(url, SourceRef(ao["title"]?.jsonPrimitive?.contentOrNull ?: url, url))
                            }
                        }
                    }
                }
            }
            if (text.isEmpty()) {
                val status = root["status"]?.jsonPrimitive?.contentOrNull
                val reason = (root["incomplete_details"] as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull
                throw OpenAiException(200, "Model returned no text (status: $status${reason?.let { ", reason: $it" } ?: ""})")
            }
            val usage = root["usage"] as? JsonObject
            val cached = ((usage?.get("input_tokens_details") as? JsonObject)?.get("cached_tokens") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            val input = (usage?.get("input_tokens") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            val output = (usage?.get("output_tokens") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            val searches = (root["output"] as? JsonArray).orEmpty().count { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "web_search_call" }
            return ResponseResult(text.toString().trim(), cites.values.toList(), cached, input, output, searches)
        }

        private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
    }
}
