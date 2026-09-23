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
 *         + wT·road_trip − wX·repetition − wC·conversation_cost
 * where road_trip is the driving bonus for places visible from the road or worth a stop (spec B §24).
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
        val roadTrip: Double = 0.6,
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
    )

    fun proximityScaleM(mode: TravelMode): Double = when (mode) {
        TravelMode.WALKING, TravelMode.UNKNOWN -> 400.0
        TravelMode.STATIONARY -> 600.0
        TravelMode.CYCLING -> 1_200.0
        TravelMode.DRIVING -> 3_000.0
    }

    /** Minimum silence between segments (driving: at least 90 s, spec B §24). */
    fun minGapMs(mode: TravelMode): Long = when (mode) {
        TravelMode.WALKING, TravelMode.UNKNOWN -> 45_000
        TravelMode.STATIONARY -> 60_000
        TravelMode.CYCLING -> 60_000
        TravelMode.DRIVING -> 90_000
    }

    /**
     * Whether stories must wait even though the gap has passed: while driving through a junction or
     * roundabout (speed or heading changing sharply), the host stays quiet. A stale fix (no update for
     * [staleMs], e.g. in a tunnel) does not hold stories forever.
     */
    fun holdForManeuver(loc: LocationContext?, nowMs: Long, staleMs: Long = 30_000): Boolean =
        loc != null && loc.travelMode == TravelMode.DRIVING && loc.maneuvering && nowMs - loc.timestampMs < staleMs

    /**
     * Driving pacing (spec B §24): a hard minimum gap of [minGapMs] since the host last spoke (the
     * conversation cost alone is only a soft penalty), and silence through junctions.
     */
    fun holdForPacing(loc: LocationContext?, lastSpeechEndMs: Long?, nowMs: Long): Boolean {
        if (loc == null || loc.travelMode != TravelMode.DRIVING) return false
        if (lastSpeechEndMs != null && nowMs - lastSpeechEndMs < minGapMs(TravelMode.DRIVING)) return true
        return holdForManeuver(loc, nowMs)
    }

    fun rank(candidates: Collection<PlaceCandidate>, ctx: Context): List<RankedCandidate> {
        val loc = ctx.location
        val convCost = when {
            ctx.userEngaged -> 1.0
            ctx.lastSpeechEndMs == null -> 0.0
            else -> {
                val gap = minGapMs(loc.travelMode)
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
                    // When driving, things behind are almost useless; cycling is ahead-weighted; walking is more forgiving.
                    when (loc.travelMode) {
                        TravelMode.DRIVING -> c.coerceAtLeast(0.0)
                        TravelMode.CYCLING -> ((c + 1) / 2).let { it * it }
                        TravelMode.WALKING, TravelMode.STATIONARY, TravelMode.UNKNOWN -> (c + 1) / 2
                    }
                }
                val roadTrip = RoadTrip.classify(place, loc)
                val b = ScoreBreakdown(
                    relevance = place.baseRelevance.coerceIn(0.0, 1.0),
                    interest = interest.coerceIn(0.0, 1.0),
                    novelty = novelty,
                    proximity = proximity,
                    direction = direction,
                    sourceQuality = place.sourceConfidence.coerceIn(0.0, 1.0),
                    repetition = if (heard) 1.0 else 0.0,
                    conversationCost = convCost,
                    roadTrip = RoadTrip.bonus(roadTrip),
                )
                RankedCandidate(place, d, bearing, score(b), b, roadTrip)
            }
            .sortedByDescending { it.score }
    }

    fun score(b: ScoreBreakdown): Double = with(weights) {
        relevance * b.relevance + interest * b.interest + novelty * b.novelty + proximity * b.proximity +
            direction * b.direction + sourceQuality * b.sourceQuality + roadTrip * b.roadTrip -
            repetition * b.repetition - conversationCost * b.conversationCost
    }

    /** The candidate that deserves airtime now, or null for silence. */
    fun pickForAirtime(ranked: List<RankedCandidate>): RankedCandidate? =
        ranked.firstOrNull()?.takeIf { it.score >= speakThreshold }
}
