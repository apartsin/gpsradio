package com.gpsradio.core.update

import com.gpsradio.core.net.HttpException
import com.gpsradio.core.net.await
import com.gpsradio.core.net.fetchString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * The update manifest CI publishes next to the tested APK (`releases/download/latest/update.json`).
 * The app is not in a store, so it updates itself from here (spec B §36).
 */
@Serializable
data class UpdateInfo(
    /** e.g. "0.5.142". */
    val version: String,
    /** The CI run number; higher means newer. */
    val build: Int,
    /** Download URL of the APK. */
    val apk: String,
    /** Lower-case hex SHA-256 of the APK; the download is rejected on mismatch. */
    val sha256: String,
    val size: Long = 0,
    /** Short commit id the build was made from. */
    val commit: String = "",
    /** One-line summary of what changed. */
    val notes: String = "",
)

object Updates {
    const val MANIFEST_URL = "https://github.com/apartsin/gpsradio/releases/download/latest/update.json"

    /** Automatic checks run at most this often. */
    const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val versionPattern = Regex("""^\d+\.\d+\.(\d+)$""")

    fun parse(text: String): UpdateInfo? = runCatching { json.decodeFromString(UpdateInfo.serializer(), text) }.getOrNull()
        ?.takeIf { it.build > 0 && it.apk.startsWith("https://") && it.sha256.matches(Regex("^[0-9a-f]{64}$")) }

    /** The CI build number in a version name ("0.5.142" → 142); null for local builds ("0.5.0-local"). */
    fun buildNumber(versionName: String): Int? = versionPattern.find(versionName.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** Local/dev builds (no build number) never offer updates, so they aren't replaced by accident. */
    fun isNewer(currentVersionName: String, remote: UpdateInfo): Boolean {
        val current = buildNumber(currentVersionName) ?: return false
        return remote.build > current
    }

    fun autoCheckDue(lastCheckMs: Long, nowMs: Long): Boolean = nowMs - lastCheckMs >= AUTO_CHECK_INTERVAL_MS || nowMs < lastCheckMs

    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

class UpdateChecksumException(message: String) : IOException(message)

/** Fetches the manifest and downloads the APK, verifying its checksum while streaming. */
class UpdateClient(private val http: OkHttpClient, private val manifestUrl: String = Updates.MANIFEST_URL) {

    /** The published update, or null when there is none (404) or the manifest is malformed. */
    suspend fun latest(): UpdateInfo? {
        val text = try {
            http.fetchString(Request.Builder().url(manifestUrl).header("Cache-Control", "no-cache").build())
        } catch (e: HttpException) {
            if (e.code == 404) return null
            throw e
        }
        return Updates.parse(text)
    }

    /**
     * Downloads [info]'s APK to [dest]; [onProgress] gets (bytesSoFar, total or 0). Throws
     * [UpdateChecksumException] and deletes the file if the SHA-256 doesn't match.
     */
    suspend fun download(info: UpdateInfo, dest: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        // Downloaded before (an install that was cancelled or failed, or "Update" tapped again): use it as it is.
        if (isComplete(info, dest)) {
            onProgress(dest.length(), dest.length())
            return
        }
        dest.delete()
        // A download cut short continues where it stopped (the server supports ranges).
        val part = File(dest.path + ".part")
        val have = part.length().takeIf { part.isFile } ?: 0L
        val request = Request.Builder().url(info.apk).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        val resp = http.newCall(request).await()
        resp.use {
            if (it.code == 416) {
                part.delete() // the partial file doesn't fit this update: start over next time
                throw IOException("The partial update download was stale. Please try again.")
            }
            if (!it.isSuccessful) throw HttpException(it.code, "HTTP ${it.code} downloading the update")
            val body = it.body ?: throw IOException("empty update download")
            val append = it.code == 206 && have > 0
            val start = if (append) have else 0L
            val total = body.contentLength().takeIf { n -> n > 0 }?.let { n -> n + start } ?: info.size
            val digest = MessageDigest.getInstance("SHA-256")
            withContext(Dispatchers.IO) {
                dest.parentFile?.mkdirs()
                // The bytes already there count toward the checksum too.
                if (append) part.inputStream().use { input -> hash(input, digest) }
                body.byteStream().use { input ->
                    java.io.FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = start
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            }
            val actual = with(Updates) { digest.digest().toHex() }
            if (!actual.equals(info.sha256, ignoreCase = true)) {
                part.delete()
                throw UpdateChecksumException("The downloaded update is damaged (checksum mismatch). Please try again.")
            }
            if (!part.renameTo(dest)) throw IOException("Could not save the update")
        }
    }

    /** [dest] already holds exactly this update (same size and SHA-256). */
    suspend fun isComplete(info: UpdateInfo, dest: File): Boolean = withContext(Dispatchers.IO) {
        if (!dest.isFile || (info.size > 0 && dest.length() != info.size)) return@withContext false
        val digest = MessageDigest.getInstance("SHA-256")
        dest.inputStream().use { hash(it, digest) }
        with(Updates) { digest.digest().toHex() }.equals(info.sha256, ignoreCase = true)
    }

    private fun hash(input: java.io.InputStream, digest: MessageDigest) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
}
