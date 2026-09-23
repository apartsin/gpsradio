package com.gpsradio.core.ai

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Base64

/** Server events we act on (OpenAI Realtime API; both GA and beta event names are accepted). */
sealed interface RealtimeEvent {
    data object SessionReady : RealtimeEvent
    data object SpeechStarted : RealtimeEvent
    data object SpeechStopped : RealtimeEvent
    data class UserTranscript(val text: String) : RealtimeEvent
    data class AudioDelta(val pcm: ByteArray) : RealtimeEvent
    data class AssistantTranscript(val text: String) : RealtimeEvent
    data class FunctionCall(val callId: String, val name: String, val arguments: String) : RealtimeEvent
    data object ResponseDone : RealtimeEvent
    data class Error(val message: String) : RealtimeEvent
    data class Closed(val reason: String) : RealtimeEvent
}

data class RealtimeTool(val name: String, val description: String, val parameters: JsonObject)

/** Builds client events and parses server events. Pure functions, unit-tested. */
object RealtimeProtocol {
    const val SAMPLE_RATE = 24_000
    private val json = Json { ignoreUnknownKeys = true }

    fun sessionUpdate(instructions: String, voice: String, transcriptionModel: String, tools: List<RealtimeTool>): JsonObject =
        buildJsonObject {
            put("type", "session.update")
            putJsonObject("session") {
                put("type", "realtime")
                put("instructions", instructions)
                putJsonArray("output_modalities") { add(JsonPrimitive("audio")) }
                putJsonObject("audio") {
                    putJsonObject("input") {
                        putJsonObject("format") { put("type", "audio/pcm"); put("rate", SAMPLE_RATE) }
                        putJsonObject("transcription") { put("model", transcriptionModel) }
                        putJsonObject("turn_detection") {
                            put("type", "server_vad")
                            put("silence_duration_ms", 650)
                            put("create_response", true)
                            put("interrupt_response", true)
                        }
                    }
                    putJsonObject("output") {
                        putJsonObject("format") { put("type", "audio/pcm"); put("rate", SAMPLE_RATE) }
                        put("voice", voice)
                    }
                }
                putJsonArray("tools") {
                    tools.forEach { t ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", t.parameters)
                        })
                    }
                }
                put("tool_choice", "auto")
            }
        }

    fun appendAudio(pcm: ByteArray): JsonObject = buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", Base64.getEncoder().encodeToString(pcm))
    }

    fun userText(text: String): JsonObject = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "message")
            put("role", "user")
            putJsonArray("content") { add(buildJsonObject { put("type", "input_text"); put("text", text) }) }
        }
    }

    fun functionOutput(callId: String, output: String): JsonObject = buildJsonObject {
        put("type", "conversation.item.create")
        putJsonObject("item") {
            put("type", "function_call_output")
            put("call_id", callId)
            put("output", output)
        }
    }

    /** Ask the model to respond now; [instructions] overrides what to say for this one response. */
    fun responseCreate(instructions: String? = null): JsonObject = buildJsonObject {
        put("type", "response.create")
        if (instructions != null) putJsonObject("response") { put("instructions", instructions) }
    }

    fun cancelResponse(): JsonObject = buildJsonObject { put("type", "response.cancel") }

    fun parse(text: String): RealtimeEvent? {
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        return when (str("type")) {
            "session.created" -> RealtimeEvent.SessionReady
            "input_audio_buffer.speech_started" -> RealtimeEvent.SpeechStarted
            "input_audio_buffer.speech_stopped" -> RealtimeEvent.SpeechStopped
            "conversation.item.input_audio_transcription.completed" ->
                str("transcript")?.trim()?.takeIf { it.isNotEmpty() }?.let { RealtimeEvent.UserTranscript(it) }
            "response.output_audio.delta", "response.audio.delta" ->
                str("delta")?.let { RealtimeEvent.AudioDelta(Base64.getDecoder().decode(it)) }
            "response.output_audio_transcript.done", "response.audio_transcript.done" ->
                str("transcript")?.trim()?.takeIf { it.isNotEmpty() }?.let { RealtimeEvent.AssistantTranscript(it) }
            "response.function_call_arguments.done" -> {
                val callId = str("call_id") ?: return null
                RealtimeEvent.FunctionCall(callId, str("name").orEmpty(), str("arguments") ?: "{}")
            }
            "response.done" -> RealtimeEvent.ResponseDone
            "error" -> RealtimeEvent.Error(
                ((o["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Realtime error",
            )
            else -> null
        }
    }

    /** Tools the voice host can use; the session implements them. */
    val tools: List<RealtimeTool> = listOf(
        RealtimeTool(
            "web_search",
            "Search the web for current or detailed information (verification, opening hours, events, deeper history). " +
                "Say a short filler like 'let me check' before calling it.",
            schema(mapOf("query" to "string"), required = listOf("query")),
        ),
        RealtimeTool(
            "radio_control",
            "Control the radio: resume_radio (listener wants the stories to continue / is done talking), pause, skip, " +
                "change_language (set language as BCP-47), set_theme / clear_theme, navigate (entity_id), " +
                "accept_offer / decline_offer (answer to pending_offer), star_place (save entity_id for later).",
            schema(
                mapOf("action" to "string", "language" to "string", "theme" to "string", "entity_id" to "string"),
                required = listOf("action"),
            ),
        ),
        RealtimeTool(
            "remember",
            "Remember a durable listener preference or fact across sessions (like, avoid, style, about_me). " +
                "Use forget_text to drop an earlier item instead.",
            schema(mapOf("category" to "string", "text" to "string", "topic" to "string", "forget_text" to "string"), required = emptyList()),
        ),
        RealtimeTool(
            "set_trip",
            "Record what the listener said about their trip (destination, purpose, time, companions).",
            schema(mapOf("summary" to "string"), required = listOf("summary")),
        ),
    )

    private fun schema(props: Map<String, String>, required: List<String>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, t) -> putJsonObject(k) { put("type", t) } } }
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
}

/** A live Realtime session. Events arrive on [events]; send client events with [send]. */
interface RealtimeConnection {
    val events: Flow<RealtimeEvent>
    fun send(event: JsonObject): Boolean
    fun close()
}

private class WebSocketConnection(private val socket: WebSocket, override val events: Flow<RealtimeEvent>) : RealtimeConnection {
    override fun send(event: JsonObject): Boolean = socket.send(event.toString())
    override fun close() {
        socket.close(1000, "done")
    }
}

/** Connects directly to the OpenAI Realtime API with the user's key (app-only architecture). */
class RealtimeClient(
    private val http: OkHttpClient,
    private val apiKey: () -> String,
    private val baseUrl: String = "wss://api.openai.com/v1/realtime",
) {
    fun connect(model: String): RealtimeConnection {
        val channel = Channel<RealtimeEvent>(Channel.UNLIMITED)
        val request = Request.Builder()
            .url("$baseUrl?model=$model")
            .header("Authorization", "Bearer ${apiKey()}")
            .build()
        val socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                RealtimeProtocol.parse(text)?.let { channel.trySend(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = when (response?.code) {
                    401 -> "OpenAI rejected the API key"
                    null -> t.message ?: "connection failed"
                    else -> "HTTP ${response.code}"
                }
                channel.trySend(RealtimeEvent.Error(msg))
                channel.trySend(RealtimeEvent.Closed(msg))
                channel.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                channel.trySend(RealtimeEvent.Closed(reason))
                channel.close()
            }
        })
        return WebSocketConnection(socket, channel.receiveAsFlow())
    }
}

