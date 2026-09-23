package com.gpsradio.core.editorial

import com.gpsradio.core.model.RankedCandidate
import com.gpsradio.core.model.Topic
import kotlin.math.roundToInt

/**
 * "Why this story?" (spec A §18.7): a one-line, human explanation of the editorial score, e.g.
 * "Close by (200 m) · matches your interest in history · well documented".
 */
object StoryReason {
    fun of(c: RankedCandidate, likes: Set<Topic>, theme: Topic? = null, maxParts: Int = 3): String? {
        val b = c.breakdown
        val parts = ArrayList<String>()
        parts += where(c.distanceM, ahead = b.direction >= 0.9 && c.distanceM >= 60)
        val topic = Topic.entries.firstOrNull { it in c.place.topics && it in likes }
        when {
            theme != null && theme in c.place.topics -> parts += "fits your ${theme.key} theme"
            topic != null -> parts += "matches your interest in ${topic.key}"
        }
        when {
            b.novelty in 0.01..0.99 -> parts += "came up earlier"
            b.sourceQuality >= 0.8 -> parts += "well documented"
            b.relevance >= 0.7 -> parts += "a rich story"
        }
        return parts.take(maxParts).joinToString(" · ").takeIf { it.isNotBlank() }
    }

    private fun where(m: Double, ahead: Boolean): String {
        val d = when {
            m < 1000 -> "${((m / 50).roundToInt().coerceAtLeast(1)) * 50} m"
            else -> "${"%.1f".format(java.util.Locale.ROOT, m / 1000)} km"
        }
        return when {
            m < 60 -> "Right here"
            ahead -> if (m < 1000) "Just ahead ($d)" else "Ahead ($d)"
            m < 500 -> "Close by ($d)"
            else -> "$d away"
        }
    }
}
