package com.gpsradio.core

import com.gpsradio.core.discovery.WikipediaClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live (CI only): Wikipedia photos really download the way the app's image loader fetches them, with the app's
 * identifying User-Agent (Wikimedia's policy; generic clients may be refused). Prints what a generic client gets.
 */
class LivePhotoTest {
    private val userAgent = "GpsRadio/test (Android; https://github.com/apartsin/gpsradio)"

    private fun status(http: OkHttpClient, url: String, ua: String?): Pair<Int, String?> {
        val req = Request.Builder().url(url).apply { if (ua != null) header("User-Agent", ua) }.build()
        return http.newCall(req).execute().use { it.code to it.header("Content-Type") }
    }

    @Test
    fun wikipediaPhotosDownloadWithTheAppsUserAgent() = runBlocking {
        assumeTrue(System.getenv("OPENAI_API_KEY") != null, "live job only")
        val http = OkHttpClient()
        val wiki = WikipediaClient(http, userAgent)
        val page = wiki.pagesByTitle("en", listOf("Traunsee")).firstOrNull()
        val thumb = assertNotNull(page?.thumbnailUrl, "Traunsee has a page image")
        val gallery = wiki.articleImages("en", "Traunsee")
        println("PHOTO lead: $thumb; gallery=${gallery.size}")
        val generic = status(http, thumb, null)
        println("PHOTO generic okhttp UA -> $generic")
        val ours = status(http, thumb, userAgent)
        println("PHOTO app UA -> $ours")
        assertEquals(200, ours.first)
        assertTrue(ours.second.orEmpty().startsWith("image/"), ours.toString())
        gallery.take(2).forEach { u -> assertEquals(200, status(http, u, userAgent).first, u) }
        // More slideshow photos from Commons (spec A §60): taken near Schloss Ort, and of the subject.
        val near = wiki.commonsPhotosNear(com.gpsradio.core.model.GeoPoint(47.9105, 13.8013), 300)
        val of = wiki.commonsPhotosOf("Traunsee")
        println("PHOTO commons near=${near.size} ${near.take(3)}; of=${of.size} ${of.take(3)}")
        assertTrue(near.isNotEmpty() && of.isNotEmpty())
        assertEquals(200, status(http, near.first(), userAgent).first)
    }

    @Test
    fun aStoryGetsCaptionedPicturesOfWhatItNames() = runBlocking {
        val key = System.getenv("OPENAI_API_KEY")
        assumeTrue(key != null, "live job only")
        val http = OkHttpClient.Builder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
        val openAi = com.gpsradio.core.ai.OpenAiClient(http, { key!! })
        val scout = com.gpsradio.core.ai.PictureScout(openAi, { com.gpsradio.core.ai.ModelConfig() })
        val text = "Эрцгерцог Иоганн Сальватор купил замок Шлосс Орт, а потом отказался от титула. " +
            "К замку ведёт деревянный мост длиной 123 метра, а за озером Траунзее поднимается гора Траунштайн."
        val refs = scout.find(text, "ru-RU", com.gpsradio.core.model.AreaLabel("Gmunden", "Upper Austria", "AT"))
        println("PICTURES: " + refs.joinToString { "${it.caption} | ${it.wikipedia} | ${it.search} | ${it.quote}" })
        assertTrue(refs.size >= 2, refs.toString())
        assertTrue(refs.all { r -> r.caption.any { it in '\u0400'..'\u04FF' } }, "captions in Russian")
        assertTrue(refs.count { com.gpsradio.core.ai.PictureScout.position(text, it.quote) != null } >= 2, "quotes found in the text")
        val wiki = WikipediaClient(http, userAgent)
        val found = refs.mapNotNull { r ->
            r.wikipedia?.let { t -> wiki.pagesByTitle("en", listOf(t)).firstOrNull()?.thumbnailUrl } ?: wiki.commonsPhotosOf(r.search).firstOrNull()
        }
        println("PICTURES resolved: ${found.size}/${refs.size}")
        assertTrue(found.size >= 2)
    }
}
