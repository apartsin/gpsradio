package com.gpsradio.core.session

import com.gpsradio.core.ai.OpenAiException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * Decides when stories go to the on-device fallback instead of OpenAI (spec B §17): after an
 * outage the primary path rests with exponential backoff, and fallback notes fill the gap
 * instead of error messages. The first story after the backoff probes OpenAI again.
 */
class FallbackGate(
    private val clock: () -> Long,
    private val minBackoffMs: Long = 15_000,
    private val maxBackoffMs: Long = 300_000,
) {
    private var downUntilMs = 0L
    private var backoffMs = 0L

    /** True while the last OpenAI attempt failed with an outage and no success has followed. */
    var degraded: Boolean = false
        private set

    fun primaryResting(): Boolean = clock() < downUntilMs

    fun onPrimaryFailure() {
        backoffMs = (backoffMs * 2).coerceIn(minBackoffMs, maxBackoffMs)
        downUntilMs = clock() + backoffMs
        degraded = true
    }

    /** Returns true when this success ends a degraded period. */
    fun onPrimarySuccess(): Boolean {
        backoffMs = 0
        downUntilMs = 0
        return degraded.also { degraded = false }
    }

    companion object {
        /** OpenAI unavailable (no/invalid key, quota, server error, network, timeout) rather than a bad request. */
        fun isOutage(e: Throwable): Boolean = e !is CancellationException &&
            ((e is OpenAiException && e.isTransient) || e is IOException || e is TimeoutException)
    }
}
