package com.gpsradio.core.session

import com.gpsradio.core.discovery.WikipediaClient

/** A caption from a Commons file name when it describes the photo ("Schloss Ort from the lake"), spec A §62. */
object PhotoCaptions {
    private val camera = Regex("^(img|dsc|dscn|dscf|p|pic|photo|image|pxl|mvimg|sam|gopr|wp)[ _-]?\\d", RegexOption.IGNORE_CASE)

    fun fromUrl(url: String): String? {
        val file = WikipediaClient.fileTitle(url) ?: return null
        val name = file.substringBeforeLast('.').replace(Regex("[_]+"), " ")
            .replace(Regex("\\s*\\(\\d+\\)$"), "").replace(Regex("\\s+\\d{3,}$"), "").trim()
        if (name.length < 4 || camera.containsMatchIn(name) || name.count { it.isLetter() } < name.length / 2) return null
        return name.take(60)
    }
}
