package com.gpsradio.core

import com.gpsradio.core.update.UpdateChecksumException
import com.gpsradio.core.update.UpdateClient
import com.gpsradio.core.update.UpdateInfo
import com.gpsradio.core.update.Updates
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {
    private val apk = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val sha = Updates.sha256Hex(apk)

    private fun manifest(build: Int = 150, url: String = "https://example.org/gpsradio.apk", hash: String = sha) =
        """{"version":"0.5.$build","build":$build,"apk":"$url","sha256":"$hash","size":${apk.size},"commit":"abc1234","notes":"Better stories","extra":1}"""

    @Test
    fun parsesTheManifestAndRejectsUnsafeOrBrokenOnes() {
        val info = assertNotNull(Updates.parse(manifest()))
        assertEquals(150, info.build)
        assertEquals("0.5.150", info.version)
        assertEquals("Better stories", info.notes)
        assertNull(Updates.parse(manifest(url = "http://example.org/a.apk")), "plain http is refused")
        assertNull(Updates.parse(manifest(hash = "abc")), "a checksum is required")
        assertNull(Updates.parse(manifest(build = 0)))
        assertNull(Updates.parse("<html>not found</html>"))
    }

    @Test
    fun comparesBuildNumbers() {
        assertEquals(142, Updates.buildNumber("0.5.142"))
        assertNull(Updates.buildNumber("0.5.0-local"))
        val info = Updates.parse(manifest(build = 150))!!
        assertTrue(Updates.isNewer("0.5.142", info))
        assertFalse(Updates.isNewer("0.5.150", info))
        assertFalse(Updates.isNewer("0.5.151", info), "never offers an older build")
        assertFalse(Updates.isNewer("0.5.0-local", info), "developer builds are not replaced")
    }

    @Test
    fun autoCheckAtMostEverySixHours() {
        val t = 1_000_000_000L
        assertTrue(Updates.autoCheckDue(0, t))
        assertFalse(Updates.autoCheckDue(t, t + 60_000))
        assertTrue(Updates.autoCheckDue(t, t + Updates.AUTO_CHECK_INTERVAL_MS))
        assertTrue(Updates.autoCheckDue(t + 10_000, t), "clock moved back")
    }

    @Test
    fun fetchesTheManifestAndDownloadsWithChecksum() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            // Direct UpdateInfo: parse() requires https, which the local mock server doesn't serve.
            val info = UpdateInfo("0.5.150", 150, server.url("/latest/gpsradio.apk").toString(), sha, apk.size.toLong())
            server.enqueue(MockResponse().setBody(manifest()))
            server.enqueue(MockResponse().setBody(Buffer().write(apk)))
            val client = UpdateClient(OkHttpClient(), server.url("/latest/update.json").toString())
            assertEquals(150, client.latest()!!.build)
            val dest = File(Files.createTempDirectory("upd").toFile(), "update.apk")
            var last = 0L
            client.download(info, dest) { done, total -> last = done; assertEquals(apk.size.toLong(), total) }
            assertContentEquals(apk, dest.readBytes())
            assertEquals(apk.size.toLong(), last)
        } finally {
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun damagedDownloadIsRejectedAndDeleted() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val broken = apk.copyOf().also { it[1000] = (it[1000] + 1).toByte() }
            server.enqueue(MockResponse().setBody(Buffer().write(broken)))
            val client = UpdateClient(OkHttpClient(), server.url("/latest/update.json").toString())
            val dest = File(Files.createTempDirectory("upd").toFile(), "update.apk")
            val info = UpdateInfo("0.5.150", 150, server.url("/a.apk").toString(), sha)
            assertFailsWith<UpdateChecksumException> { client.download(info, dest) }
            assertFalse(dest.exists())
        } finally {
            runCatching { server.shutdown() }
        }
    }

    @Test
    fun noPublishedUpdateIsNotAnError() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            assertNull(UpdateClient(OkHttpClient(), server.url("/latest/update.json").toString()).latest())
        } finally {
            runCatching { server.shutdown() }
        }
    }
}
