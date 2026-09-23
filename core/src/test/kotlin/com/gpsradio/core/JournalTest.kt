package com.gpsradio.core

import com.gpsradio.core.ai.ConversationAction
import com.gpsradio.core.ai.RadioAgent
import com.gpsradio.core.ai.RealtimeProtocol
import com.gpsradio.core.journal.Journal
import com.gpsradio.core.journal.JournalEntry
import com.gpsradio.core.journal.JournalGpx
import com.gpsradio.core.journal.JournalText
import com.gpsradio.core.session.Sting
import com.gpsradio.core.session.StingSynth
import kotlin.math.abs
import com.gpsradio.core.model.GeoPoint
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalTest {
    private val zone = ZoneId.of("Europe/Vienna")
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 9, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val castle = GeoPoint(47.612, 13.78)
    private val church = GeoPoint(47.609, 13.781)

    @Test
    fun recordsPerDayAndUpdatesRepeatsOnTheSameDay() {
        val j = Journal(zone = { zone })
        j.record("c", "Ort Castle", castle, "Built on a rock in 1080. It later became a prison.", "https://w/c", at(23, 10))
        j.record("k", "Church & Tower", church, "A tower with a secret.", null, at(23, 11))
        j.record("c", "Ort Castle", castle, "Built on a rock in 1080! Told again.", "https://w/c", at(23, 12))
        j.record("c", "Ort Castle", castle, "Next day.", "https://w/c", at(24, 9))
        assertEquals(listOf("2026-09-24", "2026-09-23"), j.days)
        assertEquals(listOf("k", "c"), j.entriesFor("2026-09-23").map { it.placeId })
        assertEquals("Built on a rock in 1080!", j.entriesFor("2026-09-23").last().firstSentence)
        assertEquals(4 - 1, j.all.size)
        assertEquals("c", j.all.first().placeId)

        // Late evening local time still belongs to that local day.
        assertEquals("2026-09-23", JournalEntry.dayOf(at(23, 23, 30), zone))

        val restored = Journal(zone = { zone }).apply { restore(j.serialize()) }
        assertEquals(j.all, restored.all)
        Journal().apply { restore("not json") }.also { assertTrue(it.all.isEmpty()) }
        assertTrue(j.remove("c", "2026-09-24"))
        assertEquals(listOf("2026-09-23"), j.days)
    }

    @Test
    fun oldDaysArePruned() {
        val j = Journal(maxDays = 30, zone = { ZoneOffset.UTC })
        j.record("old", "Old", castle, "Old story.", null, at(1, 10) - 60L * 24 * 3600 * 1000)
        j.record("new", "New", castle, "New story.", null, at(23, 10))
        assertEquals(listOf("new"), j.all.map { it.placeId })
    }

    @Test
    fun firstSentenceIsShortAndClean() {
        assertEquals("Hello there.", JournalEntry.firstSentence("  Hello   there. And more."))
        assertEquals("Wow!", JournalEntry.firstSentence("Wow! Next one."))
        assertEquals("No punctuation at all", JournalEntry.firstSentence("No punctuation at all"))
        val long = JournalEntry.firstSentence("x".repeat(500) + ".")
        assertEquals(220, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun gpxIsValidXmlWithWaypointsAndTrack() {
        val j = Journal(zone = { zone })
        j.record("k", "Church & \"Tower\" <1>", church, "A tower with a secret.", "https://w/k?a=1&b=2", at(23, 11))
        j.record("c", "Ort Castle", castle, "Built on a rock.", null, at(23, 10))
        val gpx = JournalGpx.build(j.entriesFor("2026-09-23"), "GPS Radio · 2026-09-23")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(gpx.toByteArray()))
        val root = doc.documentElement
        assertEquals("gpx", root.localName)
        assertEquals("http://www.topografix.com/GPX/1/1", root.namespaceURI)
        assertEquals("1.1", root.getAttribute("version"))
        val wpts = root.getElementsByTagNameNS("*", "wpt")
        assertEquals(2, wpts.length)
        val first = wpts.item(0) as Element
        assertEquals(castle.lat, first.getAttribute("lat").toDouble())
        assertEquals(castle.lon, first.getAttribute("lon").toDouble())
        assertEquals("Ort Castle", first.getElementsByTagNameNS("*", "name").item(0).textContent)
        assertEquals("2026-09-23T08:00:00Z", first.getElementsByTagNameNS("*", "time").item(0).textContent)
        val second = wpts.item(1) as Element
        assertEquals("Church & \"Tower\" <1>", second.getElementsByTagNameNS("*", "name").item(0).textContent)
        assertEquals("https://w/k?a=1&b=2", (second.getElementsByTagNameNS("*", "link").item(0) as Element).getAttribute("href"))
        assertEquals(2, root.getElementsByTagNameNS("*", "trkpt").length)
        // A single story: waypoint only, no track.
        assertTrue("<trk>" !in JournalGpx.build(j.entriesFor("2026-09-23").take(1), "x"))
        assertEquals("gpsradio-2026-09-23.gpx", JournalGpx.fileName("2026-09-23"))
    }

    @Test
    fun journalEntryShareTextHasSummaryAndLinks() {
        val e = Journal(zone = { zone }).record("c", "Ort Castle", castle, "Built on a rock. More.", "https://w/c", at(23, 10))
        val text = JournalText.share(e)
        assertTrue(text.startsWith("📍 Ort Castle\n\nBuilt on a rock."))
        assertTrue("Read more: https://w/c" in text)
        assertTrue("openstreetmap.org/?mlat=${castle.lat}&mlon=${castle.lon}" in text)
        assertEquals("Heard on GPS Radio: Ort Castle", JournalText.subject(e))
    }

    @Test
    fun stingsAreShortCleanAndQuiet() {
        val rate = StingSynth.SAMPLE_RATE
        val station = StingSynth.pcm(Sting.STATION)
        assertEquals(600L, StingSynth.durationMs(Sting.STATION))
        assertEquals((0.6 * rate).toInt(), station.size)
        Sting.entries.forEach { s ->
            val pcm = StingSynth.pcm(s)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue(peak > 2_000, "$s is audible")
            assertTrue(peak < 0.4 * Short.MAX_VALUE, "$s stays well below speech level")
            // Soft attack and fade-out: no click at either end.
            assertTrue(abs(pcm.first().toInt()) < 200 && abs(pcm.last().toInt()) < 200, "$s has no clicks")
            assertEquals(StingSynth.durationMs(s) * rate / 1000, pcm.size.toLong())
        }
        assertTrue(StingSynth.durationMs(Sting.LISTENING) < 150)
        assertTrue(StingSynth.durationMs(Sting.ANSWER) < StingSynth.durationMs(Sting.STATION))
    }

    @Test
    fun conversationSchemaAndLiveToolKnowAboutTours() {
        val reply = RadioAgent.parseReply("""{"reply":"Let's go!","action":"start_tour","tour_minutes":15}""")
        assertEquals(ConversationAction.START_TOUR, reply.action)
        assertEquals(15, reply.tourMinutes)
        assertNull(RadioAgent.parseReply("""{"reply":"ok","action":"none","tour_minutes":null}""").tourMinutes)
        assertEquals(ConversationAction.END_TOUR, ConversationAction.parse("end_tour"))
        val required = RadioAgent.replySchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("tour_minutes" in required)
        val props = RadioAgent.replySchema["properties"]!!.jsonObject
        assertTrue(props.keys.containsAll(required), "strict schemas need every required key defined")
        val actions = props["action"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("start_tour" in actions && "end_tour" in actions)
        val control = RealtimeProtocol.tools.first { it.name == "radio_control" }
        assertTrue("start_tour" in control.description)
        assertEquals("integer", control.parameters["properties"]!!.jsonObject["minutes"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue("start_tour" in RadioAgent.conversationInstructions("en", false))
        assertTrue("format \"arrival\"" in RadioAgent.narrationInstructions("en"))
    }
}
