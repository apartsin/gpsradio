package com.gpsradio.core.editorial

import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.LocationContext
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.ScoreBreakdown
import com.gpsradio.core.model.Topic
import com.gpsradio.core.model.TravelMode
import kotlin.math.exp

/**
 * Transparent scoring model from spec B §8:
 * score = wR·relevance + wI·interest + wN·novelty + wP·proximity + wD·direction + wQ·source_quality
 *         − wX·repetition − wC·conversation_cost
 * A separate speak threshold decides whether the best candidate gets airtime; otherwise silence.
 */
class EditorialRanker(
    val weights: Weights = Weights(),
    val speakThreshold: Double = 2.4,
) {
    data class Weights(
        val relevance: Double = 1.0,
        val interest: Double = 0.8,
        val novelty: Double = 0.6,
        val proximity: Double = 1.0,
        val direction: Double = 0.4,
        val sourceQuality: Double = 0.5,
        val repetition: Double = 3.0,
        val conversationCost: Double = 1.0,
    )

    data class Context(
        val location: LocationContext,
        /** Topic weights 0..1; topics absent from the map count as [defaultInterest]. */
        val interests: Map<Topic, Double>,
        val heard: HeardHistory,
        val nowMs: Long,
        /** Entities mentioned in conversation but not narrated: partially novel. */
        val mentionedIds: Set<String> = emptySet(),
        val lastSpeechEndMs: Long? = null,
        val userEngaged: Boolean = false,
        val theme: Topic? = null,
        val defaultInterest: Double = 0.4,
        val pacing: Pacing = Pacing.BALANCED,
    )

    fun proximityScaleM(mode: TravelMode): Double = when (mode) {
        TravelMode.WALKING, TravelMode.UNKNOWN -> 400.0
        TravelMode.STATIONARY -> 600.0
        TravelMode.DRIVING -> 3_000.0
    }

    fun minGapMs(mode: TravelMode): Long = when (mode) {
        TravelMode.WALKING, TravelMode.UNKNOWN -> 45_000
        TravelMode.STATIONARY -> 60_000
        TravelMode.DRIVING -> 60_000
    }

    /** The gap between segments for this mode, scaled by the pacing dial (driving ≥ 90 s). */
    fun minGapMs(mode: TravelMode, pacing: Pacing): Long = pacing.scaleGap(minGapMs(mode), mode)

    /** The speak threshold scaled by the pacing dial. */
    fun thresholdFor(pacing: Pacing): Double = speakThreshold * pacing.thresholdScale

    fun rank(candidates: Collection<PlaceCandidate>, ctx: Context): List<RankedCandidate> {
        val loc = ctx.location
        val convCost = when {
            ctx.userEngaged -> 1.0
            ctx.lastSpeechEndMs == null -> 0.0
            else -> {
                val gap = minGapMs(loc.travelMode, ctx.pacing)
                (1.0 - (ctx.nowMs - ctx.lastSpeechEndMs).toDouble() / gap).coerceIn(0.0, 1.0)
            }
        }
        return candidates
            .filter { ctx.theme == null || ctx.theme in it.topics }
            .map { place ->
                val d = Geo.distanceM(loc.point, place.point)
                val bearing = Geo.bearingDeg(loc.point, place.point)
                val heard = ctx.heard.wasHeard(place.id, place.name, ctx.nowMs)
                val novelty = when {
                    heard -> 0.0
                    place.id in ctx.mentionedIds -> 0.4
                    else -> 1.0
                }
                val interest = if (place.topics.isEmpty()) ctx.defaultInterest
                else place.topics.maxOf { ctx.interests[it] ?: ctx.defaultInterest }
                val proximity = exp(-d / proximityScaleM(loc.travelMode))
                val heading = loc.headingDeg
                val direction = if (heading == null || loc.travelMode == TravelMode.STATIONARY || d < 50) 0.5
                else {
                    val c = Math.cos(Math.toRadians(Geo.angleDiff(bearing, heading)))
                    // When driving, things behind are almost useless; walking is more forgiving.
                    if (loc.travelMode == TravelMode.DRIVING) c.coerceAtLeast(0.0) else (c + 1) / 2
                }
                val b = ScoreBreakdown(
                    relevance = place.baseRelevance.coerceIn(0.0, 1.0),
                    interest = interest.coerceIn(0.0, 1.0),
                    novelty = novelty,
                    proximity = proximity,
                    direction = direction,
                    sourceQuality = place.sourceConfidence.coerceIn(0.0, 1.0),
                    repetition = if (heard) 1.0 else 0.0,
                    conversationCost = convCost,
                )
                RankedCandidate(place, d, bearing, score(b), b)
            }
            .sortedByDescending { it.score }
    }

    fun score(b: ScoreBreakdown): Double = with(weights) {
        relevance * b.relevance + interest * b.interest + novelty * b.novelty + proximity * b.proximity +
            direction * b.direction + sourceQuality * b.sourceQuality -
            repetition * b.repetition - conversationCost * b.conversationCost
    }

    /** The candidate that deserves airtime now, or null for silence. */
    fun pickForAirtime(ranked: List<RankedCandidate>, pacing: Pacing = Pacing.BALANCED): RankedCandidate? =
        ranked.firstOrNull()?.takeIf { it.score >= thresholdFor(pacing) }

    /**
     * Whether a candidate would qualify once the temporary conversation-cost penalty has decayed,
     * i.e. a good place story is ready and fillers should wait.
     */
    fun storyReady(ranked: List<RankedCandidate>, pacing: Pacing = Pacing.BALANCED): Boolean = ranked.any {
        it.score + weights.conversationCost * it.breakdown.conversationCost >= thresholdFor(pacing)
    }
}
