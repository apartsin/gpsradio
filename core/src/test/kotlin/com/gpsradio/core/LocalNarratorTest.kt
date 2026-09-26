package com.gpsradio.core

import com.gpsradio.core.ai.LocalNarrator
import com.gpsradio.core.ai.LocalWriter
import com.gpsradio.core.ai.NarrationRequest
import com.gpsradio.core.ai.NotInListenerLanguageException
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.ScoreBreakdown
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalNarratorTest {
    private val here = GeoPoint(47.61, 13.78)
    private val place = PlaceCandidate(
        "a", "Ort Castle", "castle", here, "wikipedia:en", 0.85, 0.8, setOf(Topic.HISTORY),
        extract = "Ort Castle (German: Schloss Ort) is a castle on Lake Traun. It dates from the 11th century.",
        url = "https://en.wikipedia.org/wiki/Ort_Castle",
    )
    private fun request(lang: String) = NarrationRequest(
        RankedCandidate(place, 120.0, 0.0, 3.0, ScoreBreakdown(0.8, 1.0, 1.0, 0.6, 0.5, 0.85, 0.0, 0.0)),
        LocationContext(here, 5f, 0, 1.0, null, TravelMode.WALKING), lang, setOf(Topic.HISTORY), emptyList(),
    )

    private class Writer(val ready: Boolean = true, val reply: suspend (String) -> String?) : LocalWriter {
        override val name = "test"
        val prompts = ArrayList<String>()
        override suspend fun available() = ready
        override suspend fun write(prompt: String): String? { prompts += prompt; return reply(prompt) }
    }

    private val story = "Замок Орт стоит прямо на озере Траун. Ему почти тысяча лет, он построен в одиннадцатом веке."

    @Test
    fun retellsEnglishFactsInRussian() = runTest {
        val writer = Writer { story }
        val seg = LocalNarrator(writer).narrate(request("ru-RU"))
        assertEquals(story, seg.text)
        assertNull(seg.language)
        assertTrue(writer.prompts.single().contains("in Russian"))
        assertTrue(writer.prompts.single().contains("Lake Traun"))
        assertTrue("Schloss" !in writer.prompts.single(), "asides are stripped from the facts")
    }

    @Test
    fun withoutModelFallsBackToNotes() = runTest {
        val seg = LocalNarrator(Writer(ready = false) { story }).narrate(request("en-US"))
        assertTrue(seg.text.contains("Lake Traun"))
        // Notes can't translate: a Russian listener gets nothing rather than English.
        assertFailsWith<NotInListenerLanguageException> { LocalNarrator(Writer(ready = false) { story }).narrate(request("ru-RU")) }
    }

    @Test
    fun slowOrFailingModelFallsBackToNotes() = runTest {
        val slow = LocalNarrator(Writer { delay(60_000); story }, timeoutMs = 1_000).narrate(request("en-US"))
        assertTrue(slow.text.startsWith("Quick note about Ort Castle"))
        val broken = LocalNarrator(Writer { throw IllegalStateException("AICore busy") }).narrate(request("en-US"))
        assertTrue(broken.text.startsWith("Quick note about Ort Castle"))
        val empty = LocalNarrator(Writer { "OK." }).narrate(request("en-US"))
        assertTrue(empty.text.startsWith("Quick note about Ort Castle"))
    }

    @Test
    fun aReplyInTheWrongLanguageIsNotAired() = runTest {
        // A small model may answer a Russian prompt in English: the listener gets the next option instead.
        val narrator = LocalNarrator(Writer { "Ort Castle stands on Lake Traun and is almost a thousand years old." })
        assertFailsWith<NotInListenerLanguageException> { narrator.narrate(request("ru-RU")) }
        assertTrue(LocalNarrator.fitsLanguage(story, "ru-RU"))
        assertTrue(LocalNarrator.fitsLanguage("Замок Орт (Schloss Ort) стоит на озере Траун.", "ru"))
        assertTrue(!LocalNarrator.fitsLanguage("The castle is old.", "ru-RU"))
        assertTrue(!LocalNarrator.fitsLanguage("Замок очень старый и красивый.", "en-US"))
        assertTrue(LocalNarrator.fitsLanguage("Das Schloss Ort liegt am Traunsee.", "de-DE"))
    }

    @Test
    fun aSlowFirstLoadIsNotCountedAsASlowAnswer() = runTest {
        val writer = object : LocalWriter {
            override val name = "slow load"
            override suspend fun available() = true
            override suspend fun prepare(): Boolean { delay(90_000); return true }
            override suspend fun write(prompt: String) = story
        }
        assertEquals(story, LocalNarrator(writer, timeoutMs = 10_000).narrate(request("ru-RU")).text)
    }

    @Test
    fun cleansMarkdownAndLongReplies() {
        assertEquals(
            "Ort Castle sits on the lake. It is nearly a thousand years old.",
            LocalNarrator.clean("**Ort Castle** sits on the lake.\n- It is nearly a thousand years old."),
        )
        assertEquals(
            "Ort Castle sits on the lake. It is nearly a thousand years old.",
            LocalNarrator.clean("<think>\nThe user wants a story.\n</think>\nOrt Castle sits on the lake. It is nearly a thousand years old."),
        )
        val long = LocalNarrator.clean("A fine castle stands here. ".repeat(60))!!
        assertTrue(long.length <= 900 && long.endsWith("."))
    }

    @Test
    fun aColdModelDoesNotHoldUpAStoryThatCanBeReadAsItIs() = runTest {
        var warmed = false
        val cold = object : LocalWriter {
            override val name = "cold"
            override val isLoaded = false
            override suspend fun available() = true
            override fun warmUp() { warmed = true }
            override suspend fun write(prompt: String): String? = error("must not wait for a cold model")
        }
        // English facts for an English listener: read now, the model loads meanwhile.
        val seg = LocalNarrator(cold).narrate(request("en-US"))
        assertTrue(seg.text.startsWith("Quick note about Ort Castle"))
        assertTrue(warmed)
        // Russian listener, English facts: only the model can tell it, so it's asked (and fails here → notes throw).
        warmed = false
        assertFailsWith<NotInListenerLanguageException> { LocalNarrator(cold).narrate(request("ru-RU")) }
    }
}
