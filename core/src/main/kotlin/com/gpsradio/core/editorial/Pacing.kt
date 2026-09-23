package com.gpsradio.core.editorial

import com.gpsradio.core.model.TravelMode

/**
 * The listener's pacing dial (spec A §16, brainstorm "pacing dial"): how often the radio speaks.
 * It scales the speak threshold, the minimum gap between segments and how often fillers air.
 * Driving always keeps at least [DRIVING_MIN_GAP_MS] between segments and at most
 * [DRIVING_MAX_SEGMENT_S] per segment, whatever the dial says.
 */
enum class Pacing(
    val key: String,
    val label: String,
    /** Multiplies [EditorialRanker.speakThreshold]: lower talks about more places. */
    val thresholdScale: Double,
    /** Multiplies the mode's base gap between segments. */
    val gapScale: Double,
    /** Minimum time between two filler segments (bumpers, quizzes, on this day, station ID). */
    val fillerGapMs: Long,
) {
    CHATTY("chatty", "Chatty", thresholdScale = 0.85, gapScale = 0.5, fillerGapMs = 2 * 60_000L),
    BALANCED("balanced", "Balanced", thresholdScale = 1.0, gapScale = 1.0, fillerGapMs = 5 * 60_000L),
    RARE("rare", "Rare", thresholdScale = 1.25, gapScale = 2.0, fillerGapMs = 12 * 60_000L),

    /**
     * Continuous radio: at most ~8–10 s of silence between segments (walking/stationary). When no story
     * qualifies, the programme falls back to weaker nearby places, area stories, fillers and a wider search.
     * Driving still keeps the 90 s safety gap.
     */
    NONSTOP("nonstop", "Non-stop", thresholdScale = 0.7, gapScale = 0.1, fillerGapMs = 0L);

    /** Scales a mode's base gap; driving never goes below [DRIVING_MIN_GAP_MS]. */
    fun scaleGap(baseMs: Long, mode: TravelMode): Long {
        val scaled = (baseMs * gapScale).toLong()
        return if (mode == TravelMode.DRIVING) scaled.coerceAtLeast(DRIVING_MIN_GAP_MS) else scaled
    }

    companion object {
        const val DRIVING_MIN_GAP_MS = 90_000L
        const val DRIVING_MAX_SEGMENT_S = 30

        fun fromKey(key: String?): Pacing = entries.firstOrNull { it.key == key } ?: BALANCED
    }
}
