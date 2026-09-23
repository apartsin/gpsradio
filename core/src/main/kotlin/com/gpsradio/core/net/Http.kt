package com.gpsradio.core.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpException(val code: Int, message: String) : IOException(message)

/** Executes the call without blocking; cancelling the coroutine cancels the HTTP call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            // If the coroutine was cancelled meanwhile, close the body instead of leaking it.
            cont.resume(response) { response.close() }
        }
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** Runs the request and returns the body text, throwing [HttpException] on non-2xx. */
suspend fun OkHttpClient.fetchString(request: Request): String {
    newCall(request).await().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code} from ${request.url.host}: ${body.take(300)}")
        return body
    }
}

suspend fun OkHttpClient.fetchBytes(request: Request): ByteArray {
    newCall(request).await().use { resp ->
        if (!resp.isSuccessful) {
            val body = resp.body?.string().orEmpty()
            throw HttpException(resp.code, "HTTP ${resp.code} from ${request.url.host}: ${body.take(300)}")
        }
        return resp.body?.bytes() ?: ByteArray(0)
    }
}
