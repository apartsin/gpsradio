package com.gpsradio.core.session

import com.gpsradio.core.ai.QuotaErrors
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

    /** Roughly how much queued host audio is still to be heard, in ms (0 when silent or unknown). */
    fun pendingPlaybackMs(): Long = 0

    /**
     * The radio itself (stories, stings) is playing through the speaker; an always-open mic must then
     * require the listener to be clearly louder than the playback (see SpeechGate).
     */
    fun setRadioAudible(audible: Boolean) {}
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

    /** Always-listening mode: the exchange is over (quiet for a while); the connection stays open. */
    fun onLiveIdle() {}
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
    private val idleTimeoutMs: Long = 15_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var connection: RealtimeConnection? = null
    private var eventsJob: Job? = null
    private var watchdog: Job? = null
    private var lastActivityMs = 0L
    private var speaking = false
    private var pendingOpening: String? = null
    private var gotFirstAudio = false
    /** After a barge-in, drop leftover audio from the interrupted response until it is done. */
    private var dropStaleAudio = false
    private var toolRunning = false
    /** The assistant message being played and how much of its audio arrived (24 kHz 16-bit mono = 48 bytes/ms). */
    private var currentItemId: String? = null
    private var receivedBytes = 0L

    @Volatile
    var isOpen: Boolean = false
        private set

    /** Always-listening: stays connected after an exchange ends (see [LiveHost.onLiveIdle]). */
    var persistent: Boolean = false
        private set

    /** An exchange is going on (the listener spoke, typed or was asked something) and hasn't gone quiet. */
    var inConversation: Boolean = false
        private set

    private var ready = false

    /**
     * Opens the conversation; [opening] makes the host speak first (e.g. to ask a question). With
     * [persistent] the connection stays open between exchanges (always-listening mode).
     */
    fun start(opening: String? = null, persistent: Boolean = false) {
        if (isOpen) return
        isOpen = true
        this.persistent = persistent
        inConversation = opening != null || !persistent
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
                // Audio still playing from the queue counts as activity: never cut off the end of an answer.
                if (audio.pendingPlaybackMs() > 0) lastActivityMs = clock()
                if (!speaking && !toolRunning && clock() - lastActivityMs > idleTimeoutMs) {
                    if (!persistent) {
                        end()
                        break
                    }
                    if (inConversation) {
                        inConversation = false
                        host.onLiveIdle()
                    }
                    lastActivityMs = clock()
                }
            }
        }
    }

    /** Typed question while the live conversation is open. */
    fun sendText(text: String) {
        val conn = connection ?: return
        lastActivityMs = clock()
        inConversation = true
        if (speaking) {
            // Only one response can be active: interrupt the current one first.
            conn.send(RealtimeProtocol.cancelResponse())
            truncateHeard(conn)
            audio.stopPlayback()
            speaking = false
            dropStaleAudio = true
        }
        conn.send(RealtimeProtocol.userText(text))
        conn.send(RealtimeProtocol.responseCreate())
    }

    /** The host asks or says something now (e.g. an offer's question) and listens for the answer. */
    fun prompt(instructions: String) {
        val conn = connection ?: return
        lastActivityMs = clock()
        inConversation = true
        if (ready) conn.send(RealtimeProtocol.responseCreate(instructions)) else pendingOpening = instructions
    }

    /** The listener is expected to answer (e.g. after an offer): count this as an exchange. */
    fun beginExchange() {
        lastActivityMs = clock()
        inConversation = true
    }

    /** Stop the host talking (radio controls, a new story) but keep listening. */
    fun quiet() {
        val conn = connection ?: return
        if (speaking) {
            conn.send(RealtimeProtocol.cancelResponse())
            truncateHeard(conn)
            speaking = false
            dropStaleAudio = true
        }
        audio.stopPlayback()
        inConversation = false
    }

    /** Refresh the host's context (a new story started, the listener moved) without reconnecting. */
    fun updateInstructions() {
        val conn = connection ?: return
        if (!ready) return
        conn.send(RealtimeProtocol.instructionsUpdate(host.liveInstructions()))
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
                ready = true
                host.onLiveState(LiveState.LISTENING)
                pendingOpening?.let { conn.send(RealtimeProtocol.responseCreate(it)) }
                pendingOpening = null
            }
            RealtimeEvent.SpeechStarted -> {
                // Barge-in: the listener talks over the host, so stop the host immediately (also when the
                // response is complete but its audio is still queued for the speaker).
                lastActivityMs = clock()
                inConversation = true
                if (speaking || audio.pendingPlaybackMs() > 0) truncateHeard(conn)
                if (speaking) dropStaleAudio = true
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
                if (dropStaleAudio) return
                if (e.itemId != null && e.itemId != currentItemId) {
                    currentItemId = e.itemId
                    receivedBytes = 0
                }
                receivedBytes += e.pcm.size
                gotFirstAudio = true
                lastActivityMs = clock()
                if (!speaking) host.onLiveState(LiveState.ASSISTANT_SPEAKING)
                speaking = true
                audio.play(e.pcm)
            }
            is RealtimeEvent.AssistantTranscript -> host.onAssistantSaid(e.text)
            is RealtimeEvent.FunctionCall -> {
                lastActivityMs = clock()
                val args = runCatching { json.parseToJsonElement(e.arguments).jsonObject }.getOrElse { buildJsonObject { } }
                toolRunning = true
                val output = try {
                    host.callTool(e.name, args)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    "error: ${ex.message}"
                } finally {
                    toolRunning = false
                    lastActivityMs = clock()
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
                dropStaleAudio = false
                if (isOpen) host.onLiveState(LiveState.LISTENING)
            }
            is RealtimeEvent.Error -> {
                // Before the host has spoken, an error means setup failed (bad key, rejected session.update):
                // surface it. Later errors (e.g. a cancelled response) keep the conversation open.
                val fatal = !gotFirstAudio || QuotaErrors.matches(e.message) || e.message.contains("API key") || e.message.startsWith("HTTP") || e.message.contains("failed")
                if (fatal) fail(e.message)
            }
            is RealtimeEvent.Closed -> if (isOpen) {
                if (!gotFirstAudio) fail(e.reason.ifBlank { "connection closed" }) else end()
            }
        }
    }

    /** Tell the server what was actually heard of the interrupted message (sent audio minus what's still queued). */
    private fun truncateHeard(conn: RealtimeConnection) {
        val item = currentItemId ?: return
        val heardMs = receivedBytes / BYTES_PER_MS - audio.pendingPlaybackMs()
        conn.send(RealtimeProtocol.truncate(item, heardMs))
        currentItemId = null
        receivedBytes = 0
    }

    private fun fail(message: String) {
        host.onLiveError(message)
        end()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val BYTES_PER_MS = 48L
    }
}

