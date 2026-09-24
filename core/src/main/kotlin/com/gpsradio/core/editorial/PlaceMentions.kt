package com.gpsradio.core.editorial

import com.gpsradio.core.model.PlaceCandidate

/**
 * Which known place a piece of speech is about (spec A §51), so the photo follows what the host talks about.
 * Every word of the place's name (3+ letters) must appear in the text, allowing for inflected endings
 * (case endings: the last two letters of longer words may differ, so «Гмундене» matches «Гмунден»).
 */
object PlaceMentions {
    private val word = Regex("[\\p{L}\\p{N}]+")

    private fun words(text: String) = word.findAll(text.lowercase().replace('ё', 'е')).map { it.value }.toList()

    fun find(text: String, places: List<PlaceCandidate>): PlaceCandidate? {
        val said = words(text)
        if (said.isEmpty()) return null
        return places.mapNotNull { p ->
            val name = words(p.name).filter { it.length >= 3 }
            if (name.isEmpty() || name.sumOf { it.length } < 5) return@mapNotNull null
            val all = name.all { t ->
                val stem = if (t.length > 5) t.dropLast(2) else t
                said.any { it.startsWith(stem) }
            }
            if (all) p to name.sumOf { it.length } else null
        }.maxByOrNull { it.second }?.first
    }
}
