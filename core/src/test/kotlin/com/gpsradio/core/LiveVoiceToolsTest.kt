package com.gpsradio.core

import com.gpsradio.core.ai.ModelConfig
import com.gpsradio.core.ai.RealtimeClient
import com.gpsradio.core.ai.RealtimeEvent
import com.gpsradio.core.ai.RealtimeProtocol
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/** Live: the Realtime voice host picks the right tools for spoken-style requests. Skipped without a key. */
class LiveVoiceToolsTest {
    private val key: String? = System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }

    private fun calledTool(userText: String): Pair<String?, String> = runBlocking {
        val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS).build()
        val conn = RealtimeClient(http, { key!! }).connect(ModelConfig().realtimeModel)
        var name: String? = null
        var args = ""
        val instructions = """
            You are the host of a location-aware radio show talking with the listener by voice. Speak English.
            Tools: web_search for current info; radio_control for resume_radio/pause/skip/star_place/change_language;
            remember for durable preferences; set_trip for trip plans.
            Context (JSON): {"active_story":{"entity_id":"wiki:en:1","name":"Schloss Ort"}}
        """.trimIndent()
        withTimeoutOrNull(60_000) {
            conn.events.first { e ->
                when (e) {
                    RealtimeEvent.SessionReady -> {
                        conn.send(RealtimeProtocol.sessionUpdate(instructions, "coral", "gpt-4o-mini-transcribe", RealtimeProtocol.tools))
                        conn.send(RealtimeProtocol.userText(userText))
                        conn.send(RealtimeProtocol.responseCreate())
                    }
                    is RealtimeEvent.FunctionCall -> { name = e.name; args = e.arguments }
                    else -> Unit
                }
                name != null || e == RealtimeEvent.ResponseDone || e is RealtimeEvent.Closed
            }
        }
        conn.close()
        println("VOICE TOOL for '$userText': $name $args")
        name to args
    }

    @Test
    fun voiceHostUsesToolsForRequests() {
        assumeTrue(key != null, "OPENAI_API_KEY not set; live test skipped")
        val (search, _) = calledTool("Is Schloss Ort open today and how much is a ticket right now?")
        val (control, controlArgs) = calledTool("Great, that's all, go back to the radio please.")
        val (save, saveArgs) = calledTool("Save this castle for later, I want to visit it.")
        val (mem, memArgs) = calledTool("Remember that I love castles.")
        val failures = buildList {
            if (search != "web_search") add("current info → web_search, got $search")
            if (control != "radio_control" || "resume_radio" !in controlArgs) add("back to radio → radio_control resume_radio, got $control $controlArgs")
            if (save != "radio_control" || "star_place" !in saveArgs) add("save → radio_control star_place, got $save $saveArgs")
            if (mem != "remember" || "castle" !in memArgs.lowercase()) add("remember → remember(castles), got $mem $memArgs")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }
}
