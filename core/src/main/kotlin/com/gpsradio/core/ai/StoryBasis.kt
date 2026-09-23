package com.gpsradio.core.ai

/** How well-founded a narrated story is, as judged by the model from the facts it was given (trust, spec A §18.7). */
enum class StoryBasis(val key: String, val label: String) {
    /** Every claim comes from documented facts. */
    DOCUMENTED("documented", "Documented"),
    /** Includes claims that sources contest. */
    DISPUTED("disputed", "Includes disputed claims"),
    /** Mostly folklore or legend. */
    LEGEND("legend", "Includes legend"),
    /** Documented facts plus some legend or disputed claims. */
    MIXED("mixed", "Includes legend");

    companion object {
        fun parse(s: String?): StoryBasis? = entries.firstOrNull { it.key.equals(s?.trim(), ignoreCase = true) }
    }
}
