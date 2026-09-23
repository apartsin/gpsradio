package com.gpsradio.core.ai

/** Who is talking: the host's personality shapes narration, answers and the voice's delivery. */
enum class HostStyle(val key: String, val label: String, val persona: String, val voiceDirection: String) {
    ENTERTAINING(
        "entertaining",
        "Witty guide",
        "a warm, witty and genuinely knowledgeable local guide: part favourite history teacher, part born storyteller. " +
            "You love a surprising fun fact, you give the historical and cultural context that makes a place matter, " +
            "and you slip in light, content-related humour (a wry aside, a playful comparison, a gentle pun) when it fits.",
        "Warm, lively and smiling, like a favourite guide telling a great story to a friend. Natural rhythm, " +
            "small pauses before the punchline or the surprising fact, never rushed.",
    ),
    DOCUMENTARY(
        "documentary",
        "Documentary",
        "a calm, authoritative documentary narrator with a gift for vivid detail and context; humour is rare and dry.",
        "Measured, rich and calm documentary narration with clear articulation.",
    ),
    KIDS(
        "kids",
        "Family & kids",
        "a playful guide for a family with children aged about 6–12. Use short sentences (about 12 words or fewer), " +
            "everyday words, and explain any title or hard word simply (say 'a prince named Johann', not 'Archduke Johann " +
            "Salvator'). Include one wow-fact, a silly comparison, and now and then a quick question for the kids.",
        "Bright, playful and expressive, like a children's museum guide; clear and not too fast.",
    ),
    CHILL(
        "chill",
        "Late-night chill",
        "a relaxed late-night radio host: unhurried, thoughtful, with gentle humour and a sense of wonder.",
        "Soft, relaxed, intimate late-night radio voice; slow and warm.",
    );

    companion object {
        fun fromKey(key: String?): HostStyle = entries.firstOrNull { it.key == key } ?: ENTERTAINING
    }
}

/** What kind of segment to produce. */
enum class SegmentFormat {
    /** A complete short story. */
    STORY,
    /** A one or two sentence hook that ends by asking whether the listener wants the full story. */
    TEASER,
    /** A short second chapter on arrival at a tour stop: what to look for, standing in front of it. */
    ARRIVAL,
    /** A ~15 s "did you know" bumper: one surprising fact from a nearby place's facts. */
    BUMPER,
    /** A ~30 s "on this day" segment about a historical event on today's date. */
    ON_THIS_DAY,
    /** A short question about a nearby place; the answer is revealed later (see [Segment.quizAnswer]). */
    QUIZ,
    /** A 1–2 sentence station ident with a recap of what the listener heard so far. */
    STATION_ID,
    /** A story about the current town or region, one facet (history, people, culture, geography) at a time. */
    AREA;

    /** Everything except place stories, teasers and tour arrivals: segments that fill the gaps between stories. */
    val isFiller: Boolean get() = this != STORY && this != TEASER && this != ARRIVAL
}
