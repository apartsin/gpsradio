package com.gpsradio.core.ai

import com.gpsradio.core.model.AreaLabel
import com.gpsradio.core.model.SourceRef
import com.gpsradio.core.net.HttpException
import com.gpsradio.core.net.fetchBytes
import com.gpsradio.core.net.fetchString
import kotlinx.serialization.json.Json
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

class OpenAiException(val status: Int, message: String) : Exception(message)

/**
 * Minimal direct client for the OpenAI REST API (Responses, speech, transcription).
 * The key comes from the user's device settings; nothing is proxied through a backend.
 */
class OpenAiClient(
    private val http: OkHttpClient,
    private val apiKey: () -> String,
    private val baseUrl: String = "https://api.openai.com/v1",
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
    )

    data class ResponseResult(val text: String, val citations: List<SourceRef>)

    suspend fun respond(req: ResponseRequest): ResponseResult {
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
            if (isReasoningModel(req.model)) put("reasoning", buildJsonObject { put("effort", "low") })
            req.maxOutputTokens?.let { put("max_output_tokens", it) }
        }
        val raw = call { http.fetchString(post("/responses", body.toString().toRequestBody(JSON))) }
        return parseResponse(raw)
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
    }

    /** Speech-to-text for a recorded utterance. Language is auto-detected so users can switch by voice. */
    suspend fun transcribe(audio: ByteArray, fileName: String, mimeType: String, model: String): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", fileName, audio.toRequestBody(mimeType.toMediaType()))
            .build()
        val raw = call { http.fetchString(post("/audio/transcriptions", body)) }
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

    private fun friendlyError(e: HttpException): String {
        val detail = e.message?.substringAfter(": ", "")?.let { body ->
            runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull }.getOrNull()
        }
        return when (e.code) {
            401 -> "OpenAI rejected the API key"
            429 -> "OpenAI rate limit or quota reached" + (detail?.let { ": $it" } ?: "")
            else -> "OpenAI error ${e.code}" + (detail?.let { ": $it" } ?: "")
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

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
                throw OpenAiException(200, "Model returned no text (status: $status)")
            }
            return ResponseResult(text.toString().trim(), cites.values.toList())
        }

        private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
    }
}
