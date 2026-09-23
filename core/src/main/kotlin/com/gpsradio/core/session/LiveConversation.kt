package com.gpsradio.core.session

import com.gpsradio.core.ai.RealtimeConnection
import com.gpsradio.core.ai.RealtimeEvent
import com.gpsradio.core.ai.RealtimeProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/** Streams microphone PCM (24 kHz mono 16-bit) and plays PCM from the voice model. */
interface PcmAudio {
    /** Starts capture; [onChunk] may be called on any thread. Returns false if the mic is unavailable. */
    fun startCapture(onChunk: (ByteArray) -> Unit): Boolean
    fun stopCapture()
    fun play(pcm: ByteArray)
    /** Drops queued audio immediately (the listener started talking over the host). */
    fun stopPlayback()
}

enum class LiveState { CONNECTING, LISTENING, USER_SPEAKING, ASSISTANT_SPEAKING }

/** What a live conversation needs from the radio session. */
interface LiveHost {
    fun liveInstructions(): String
    fun liveVoice(): String
    fun liveModel(): String
    fun transcriptionModel(): String
    fun onUserSaid(text: String)
    fun onAssistantSaid(text: String)
    /** Runs a tool call; returns the text result for the model. */
    suspend fun callTool(name: String, arguments: JsonObject): String
    fun onLiveState(state: LiveState?)
    fun onLiveError(message: String)
}

/**
 * One hands-free voice conversation over the OpenAI Realtime API: the model hears the listener
 * (server-side turn detection), answers in a natural voice, can be interrupted mid-sentence, and
 * calls tools for web search, radio control and memory. Ends after [idleTimeoutMs] of silence.
 */
class LiveConversation(
    private val connect: (model: String) -> RealtimeConnection,
    private val audio: PcmAudio,
    private val host: LiveHost,
    private val scope: CoroutineScope,
    private val idleTimeoutMs: Long = 25_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var connection: RealtimeConnection? = null
    private var eventsJob: Job? = null
    private var watchdog: Job? = null
    private var lastActivityMs = 0L
    private var speaking = false
    private var pendingOpening: String? = null

    @Volatile
    var isOpen: Boolean = false
        private set

    /** Opens the conversation; [opening] makes the host speak first (e.g. to ask a question). */
    fun start(opening: String? = null) {
        if (isOpen) return
        isOpen = true
        pendingOpening = opening
        lastActivityMs = clock()
        host.onLiveState(LiveState.CONNECTING)
        val conn = try {
            connect(host.liveModel())
        } catch (e: Exception) {
            fail(e.message ?: "couldn't connect")
            return
        }
        connection = conn
        eventsJob = scope.launch {
            try {
                conn.events.collect { handle(conn, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "voice connection lost")
            }
            if (isOpen) end()
        }
        watchdog = scope.launch {
            while (isOpen) {
                delay(1_000)
                if (!speaking && clock() - lastActivityMs > idleTimeoutMs) {
                    end()
                    break
                }
            }
        }
    }

    /** Typed question while the live conversation is open. */
    fun sendText(text: String) {
        val conn = connection ?: return
        lastActivityMs = clock()
        conn.send(RealtimeProtocol.userText(text))
        conn.send(RealtimeProtocol.responseCreate())
    }

    fun end() {
        if (!isOpen) return
        isOpen = false
        audio.stopCapture()
        audio.stopPlayback()
        connection?.close()
        connection = null
        watchdog?.cancel()
        eventsJob?.cancel()
        host.onLiveState(null)
    }

    private suspend fun handle(conn: RealtimeConnection, e: RealtimeEvent) {
        when (e) {
            RealtimeEvent.SessionReady -> {
                conn.send(
                    RealtimeProtocol.sessionUpdate(host.liveInstructions(), host.liveVoice(), host.transcriptionModel(), RealtimeProtocol.tools),
                )
                val micOk = audio.startCapture { chunk -> if (isOpen) conn.send(RealtimeProtocol.appendAudio(chunk)) }
                if (!micOk) {
                    fail("microphone unavailable")
                    return
                }
                host.onLiveState(LiveState.LISTENING)
                pendingOpening?.let { conn.send(RealtimeProtocol.responseCreate(it)) }
                pendingOpening = null
            }
            RealtimeEvent.SpeechStarted -> {
                // Barge-in: the listener talks over the host, so stop the host immediately.
                lastActivityMs = clock()
                speaking = false
                audio.stopPlayback()
                host.onLiveState(LiveState.USER_SPEAKING)
            }
            RealtimeEvent.SpeechStopped -> {
                lastActivityMs = clock()
                host.onLiveState(LiveState.LISTENING)
            }
            is RealtimeEvent.UserTranscript -> host.onUserSaid(e.text)
            is RealtimeEvent.AudioDelta -> {
                lastActivityMs = clock()
                if (!speaking) host.onLiveState(LiveState.ASSISTANT_SPEAKING)
                speaking = true
                audio.play(e.pcm)
            }
            is RealtimeEvent.AssistantTranscript -> host.onAssistantSaid(e.text)
            is RealtimeEvent.FunctionCall -> {
                lastActivityMs = clock()
                val args = runCatching { json.parseToJsonElement(e.arguments).jsonObject }.getOrElse { buildJsonObject { } }
                val output = try {
                    host.callTool(e.name, args)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    "error: ${ex.message}"
                }
                // A tool may have ended the conversation (e.g. "back to the radio").
                if (isOpen) {
                    conn.send(RealtimeProtocol.functionOutput(e.callId, output))
                    conn.send(RealtimeProtocol.responseCreate())
                }
            }
            RealtimeEvent.ResponseDone -> {
                lastActivityMs = clock()
                speaking = false
                if (isOpen) host.onLiveState(LiveState.LISTENING)
            }
            is RealtimeEvent.Error -> {
                // Non-fatal errors (e.g. a cancelled response) keep the conversation open.
                if (e.message.contains("API key") || e.message.startsWith("HTTP") || e.message.contains("failed")) fail(e.message)
            }
            is RealtimeEvent.Closed -> if (isOpen) end()
        }
    }

    private fun fail(message: String) {
        host.onLiveError(message)
        end()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

