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
    }
}
