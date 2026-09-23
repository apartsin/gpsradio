package com.gpsradio.core.discovery

import com.gpsradio.core.model.Topic

/** Cheap keyword/tag heuristics that map source metadata onto content categories (spec A §8). */
object TopicClassifier {
    private val keywords: Map<Topic, List<String>> = mapOf(
        Topic.HISTORY to listOf("history", "historic", "century", "founded", "medieval", "ancient", "roman", "castle", "ruins", "archaeolog", "monument", "memorial", "palace", "fortress", "abbey", "monastery"),
        Topic.LEGENDS to listOf("legend", "folklore", "myth", "ghost", "haunted", "saint", "miracle", "treasure"),
        Topic.ARCHITECTURE to listOf("architect", "building", "church", "cathedral", "tower", "bridge", "baroque", "gothic", "romanesque", "renaissance", "synagogue", "mosque", "house", "villa", "skyscraper"),
        Topic.NATURE to listOf("mountain", "lake", "river", "forest", "park", "valley", "waterfall", "cave", "glacier", "peak", "island", "beach", "nature reserve", "geolog", "spring", "gorge", "hill"),
        Topic.CULTURE to listOf("museum", "theatre", "theater", "gallery", "artist", "painter", "writer", "poet", "composer", "novel", "film", "music", "festival", "library", "university"),
        Topic.INDUSTRY to listOf("mine", "mining", "factory", "railway", "station", "industrial", "brewery", "mill", "canal", "power station", "salt", "observatory", "laboratory"),
        Topic.WAR to listOf("war", "battle", "siege", "army", "military", "nazi", "wwii", "world war", "bunker", "concentration camp", "resistance", "soldier"),
        Topic.FOOD to listOf("cuisine", "food", "wine", "vineyard", "cheese", "market", "brewery", "bakery", "restaurant", "dish"),
        Topic.UNUSUAL to listOf("unusual", "oldest", "smallest", "largest", "only", "mystery", "strange", "record", "curious"),
        Topic.ATTRACTIONS to listOf("tourist", "attraction", "viewpoint", "landmark", "square", "zoo", "garden"),
    )

    fun fromText(vararg texts: String?): Set<Topic> {
        val hay = texts.filterNotNull().joinToString(" ").lowercase()
        if (hay.isBlank()) return emptySet()
        return keywords.filterValues { words -> words.any { it in hay } }.keys
    }

    fun fromOsmTags(tags: Map<String, String>): Set<Topic> {
        val out = mutableSetOf<Topic>()
        val historic = tags["historic"]
        if (historic != null) {
            out += Topic.HISTORY
            when (historic) {
                "battlefield", "bunker", "tank", "cannon", "aircraft", "military" -> out += Topic.WAR
                "castle", "church", "manor", "tower", "building", "city_gate", "fort" -> out += Topic.ARCHITECTURE
                "mine", "mine_shaft", "industrial", "railway", "mill" -> out += Topic.INDUSTRY
                "memorial" -> if (tags["memorial"] in setOf("war_memorial", "stolperstein")) out += Topic.WAR
            }
        }
        when (tags["tourism"]) {
            "museum", "gallery", "artwork" -> out += Topic.CULTURE
            "viewpoint" -> { out += Topic.NATURE; out += Topic.ATTRACTIONS }
            "attraction", "theme_park", "zoo" -> out += Topic.ATTRACTIONS
        }
        if (tags["natural"] != null || tags["leisure"] == "nature_reserve" || tags["boundary"] == "national_park") out += Topic.NATURE
        if (tags["building"] in setOf("cathedral", "church", "castle", "palace") || tags["amenity"] == "place_of_worship") out += Topic.ARCHITECTURE
        if (tags["man_made"] in setOf("lighthouse", "tower", "windmill", "watermill", "observatory")) out += Topic.INDUSTRY
        return out
    }
}
