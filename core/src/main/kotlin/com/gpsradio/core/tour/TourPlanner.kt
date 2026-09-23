package com.gpsradio.core.tour

import com.gpsradio.core.geo.Geo
import com.gpsradio.core.model.GeoPoint
import com.gpsradio.core.model.PlaceCandidate
import com.gpsradio.core.model.RankedCandidate

/** One stop of a walking mini-tour. */
data class TourStop(
    val place: PlaceCandidate,
    /** Straight-line distance from the previous stop (or the start) in metres. */
    val legM: Double,
    /** Minutes from the start of the tour until arriving here (walking plus time spent at earlier stops). */
    val etaMin: Double,
) {
    val id: String get() = place.id
    val name: String get() = place.name
    val point: GeoPoint get() = place.point
}

data class TourPlan(
    val minutes: Int,
    val start: GeoPoint,
    val stops: List<TourStop>,
    /** Walking distance of the whole route, including the walk back to the start when [loop]. */
    val totalWalkM: Double,
    /** Estimated duration: walking (with a street-detour factor) plus time at each stop. */
    val totalMin: Double,
    val loop: Boolean,
)

/**
 * Plans a walking mini-tour ("give me 30 minutes", spec A §18 #10): picks 3–6 high-scoring stops
 * that fit the time budget and orders them into a short route (nearest neighbour, then 2-opt).
 * Pure and deterministic.
 */
class TourPlanner(
    val walkKmh: Double = 4.5,
    val minutesPerStop: Double = 4.0,
    val minStops: Int = 3,
    val maxStops: Int = 6,
    /** Streets are longer than straight lines: walking time uses distance × this factor. */
    val streetFactor: Double = 1.25,
    /** Return to the starting point (a loop) within the budget. */
    val loop: Boolean = true,
    /** Only the best candidates are considered. */
    val poolSize: Int = 20,
) {
    private val metresPerMin: Double get() = walkKmh * 1000.0 / 60.0

    /** Minutes to walk [m] straight-line metres. */
    fun walkMinutes(m: Double): Double = m * streetFactor / metresPerMin

    /**
     * Returns a plan for [minutes], or null when not even two stops fit. Prefers [minStops]..[maxStops]
     * stops; with very little time or few sights around it falls back to two.
     */
    fun plan(candidates: List<RankedCandidate>, from: GeoPoint, minutes: Int): TourPlan? {
        if (minutes <= 0) return null
        val budget = minutes.toDouble()
        // Anything whose out-and-back walk alone exceeds the budget can never fit.
        val reachable = candidates
            .distinctBy { it.place.id }
            .filter { c ->
                val d = Geo.distanceM(from, c.place.point)
                walkMinutes(if (loop) 2 * d else d) + minutesPerStop <= budget
            }
            .sortedByDescending { it.score }
            .take(poolSize)
        if (reachable.size < 2) return null

        // Greedy selection under several orderings; the best feasible result wins.
        val orderings = listOf(
            reachable,
            reachable.sortedByDescending { it.score - Geo.distanceM(from, it.place.point) / 400.0 },
            reachable.sortedBy { Geo.distanceM(from, it.place.point) },
        )
        var best: Pair<List<RankedCandidate>, Double>? = null
        for (ordering in orderings) {
            val chosen = greedy(ordering, from, budget)
            if (chosen.size < 2) continue
            val value = value(chosen)
            if (best == null || value > best.second + 1e-9) best = chosen to value
        }
        val chosen = best?.first ?: return null
        val route = order(chosen.map { it.place }, from)
        return build(route, from, minutes)
    }

    /** Stops count most (up to [minStops]), then the sum of scores. */
    private fun value(chosen: List<RankedCandidate>): Double =
        minOf(chosen.size, minStops) * 1_000.0 + chosen.sumOf { it.score }

    private fun greedy(ordering: List<RankedCandidate>, from: GeoPoint, budget: Double): List<RankedCandidate> {
        val chosen = ArrayList<RankedCandidate>()
        for (c in ordering) {
            if (chosen.size >= maxStops) break
            val trial = chosen + c
            if (durationMin(order(trial.map { it.place }, from), from) <= budget) chosen += c
        }
        return chosen
    }

    fun durationMin(route: List<PlaceCandidate>, from: GeoPoint): Double =
        walkMinutes(routeLength(route.map { it.point }, from)) + minutesPerStop * route.size

    private fun routeLength(points: List<GeoPoint>, from: GeoPoint): Double {
        var total = 0.0
        var prev = from
        for (p in points) {
            total += Geo.distanceM(prev, p)
            prev = p
        }
        if (loop && points.isNotEmpty()) total += Geo.distanceM(prev, from)
        return total
    }

    /** Nearest-neighbour route from [from], improved with 2-opt. */
    fun order(places: List<PlaceCandidate>, from: GeoPoint): List<PlaceCandidate> {
        if (places.size <= 1) return places
        val left = places.toMutableList()
        val route = ArrayList<PlaceCandidate>()
        var at = from
        while (left.isNotEmpty()) {
            val next = left.minBy { Geo.distanceM(at, it.point) }
            left.remove(next)
            route += next
            at = next.point
        }
        return twoOpt(route, from)
    }

    private fun twoOpt(initial: List<PlaceCandidate>, from: GeoPoint): List<PlaceCandidate> {
        var route = initial
        var bestLen = routeLength(route.map { it.point }, from)
        var improved = true
        var guard = 0
        while (improved && guard++ < 100) {
            improved = false
            for (i in 0 until route.size - 1) {
                for (k in i + 1 until route.size) {
                    val candidate = route.subList(0, i) + route.subList(i, k + 1).reversed() + route.subList(k + 1, route.size)
                    val len = routeLength(candidate.map { it.point }, from)
                    if (len < bestLen - 1e-6) {
                        route = candidate
                        bestLen = len
                        improved = true
                    }
                }
            }
        }
        return route
    }

    private fun build(route: List<PlaceCandidate>, from: GeoPoint, minutes: Int): TourPlan {
        var prev = from
        var elapsed = 0.0
        val stops = route.mapIndexed { i, p ->
            val leg = Geo.distanceM(prev, p.point)
            if (i > 0) elapsed += minutesPerStop
            elapsed += walkMinutes(leg)
            prev = p.point
            TourStop(p, leg, elapsed)
        }
        val length = routeLength(route.map { it.point }, from)
        return TourPlan(
            minutes = minutes,
            start = from,
            stops = stops,
            totalWalkM = length,
            totalMin = walkMinutes(length) + minutesPerStop * route.size,
            loop = loop,
        )
    }
}

/** An active tour, as shown in the UI and used by the session. */
data class TourState(
    val stops: List<TourStop>,
    /** Index of the stop the listener is heading to; equals stops.size once the last stop was told. */
    val nextIndex: Int,
    val minutes: Int,
    val start: GeoPoint,
    val loop: Boolean = true,
) {
    val next: TourStop? get() = stops.getOrNull(nextIndex)
    val finished: Boolean get() = nextIndex >= stops.size

    companion object {
        fun of(plan: TourPlan) = TourState(plan.stops, 0, plan.minutes, plan.start, plan.loop)
    }
}

/** Plain English drafts of the tour's host lines (the narrator may restyle or translate them). */
object TourText {
    fun distance(m: Double): String = when {
        m < 60 -> "just a few steps"
        m < 1000 -> "about ${((m / 50).toInt().coerceAtLeast(1)) * 50} metres"
        else -> "about ${"%.1f".format(java.util.Locale.ROOT, m / 1000)} km"
    }

    /** "ahead", "to your left", … when a heading is known; otherwise a compass direction. */
    fun direction(from: GeoPoint, headingDeg: Double?, to: GeoPoint): String {
        val bearing = Geo.bearingDeg(from, to)
        return if (headingDeg != null) Geo.relativeDirection(bearing, headingDeg) else "to the " + Geo.compass(bearing)
    }

    fun way(from: GeoPoint, headingDeg: Double?, to: GeoPoint): String {
        val d = Geo.distanceM(from, to)
        return if (d < 60) "just a few steps away" else "${distance(d)} ${direction(from, headingDeg, to)}"
    }

    fun names(list: List<String>): String = when (list.size) {
        0 -> ""
        1 -> list[0]
        else -> list.dropLast(1).joinToString(", ") + " and " + list.last()
    }

    fun intro(state: TourState, at: GeoPoint, headingDeg: Double?): String {
        val kind = if (state.loop) "loop" else "walk"
        val first = state.stops.first()
        return "Here's a ${state.minutes}-minute $kind with ${state.stops.size} stops: ${names(state.stops.map { it.name })}. " +
            "First stop: ${first.name}, ${way(at, headingDeg, first.point)}."
    }

    fun next(stop: TourStop, at: GeoPoint, headingDeg: Double?): String =
        "Next stop: ${stop.name}, ${way(at, headingDeg, stop.point)}."

    fun finish(state: TourState, at: GeoPoint, headingDeg: Double?): String = buildString {
        append("That was the last stop of our ${state.minutes}-minute tour.")
        if (state.loop && Geo.distanceM(at, state.start) >= 60) {
            append(" Your starting point is ${way(at, headingDeg, state.start)}.")
        }
        append(" Thanks for walking with me!")
    }
}
