package com.gpsradio.core.discovery

/**
 * Is a found photo really of the thing the story names? (spec A §68) Precision over recall: no photo (the map)
 * beats an unrelated one.
 */
object PhotoRelevance {
    private val generic = setOf(
        "photo", "photograph", "picture", "image", "view", "views", "old", "historic", "historical", "the", "and",
        "with", "from", "near", "portrait", "painting", "drawing", "building", "town", "city", "village", "austria",
        "germany", "lake", "river", "street", "centre", "center", "area", "region", "famous", "local",
    )

    private fun words(text: String) = text.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

    /** The specific words of a search ("Schloss Ort wooden bridge Gmunden" → schloss, wooden, bridge), minus the place's own names. */
    fun keyTokens(query: String, placeWords: Collection<String>): List<String> {
        val place = placeWords.flatMap { words(it) }.toSet()
        return words(query).filter { it.length >= 4 && it !in generic && it !in place }.distinct()
    }

    /** A file or result title names at least one specific word of the search (stem match for inflections). */
    fun titleMatches(title: String, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        val t = words(title).joinToString(" ")
        return tokens.any { tok -> tok.take(5) in t }
    }

    /**
     * A Wikipedia article is the one meant: its short description or opening mentions the story's context (the
     * search's specific words, or the place). Unknown (no description) counts as fine.
     */
    fun articleFits(title: String, description: String?, extract: String?, query: String, placeWords: Collection<String>): Boolean {
        val text = listOfNotNull(description, extract?.take(400)).joinToString(" ")
        if (text.isBlank()) return true
        val own = words(title).toSet()
        val context = (keyTokens(query, emptyList()) + placeWords.flatMap { words(it) }.filter { it.length >= 4 })
            .filter { it !in own }.distinct()
        if (context.isEmpty()) return true
        val w = words(text).joinToString(" ")
        return context.any { it.take(5) in w }
    }
}
