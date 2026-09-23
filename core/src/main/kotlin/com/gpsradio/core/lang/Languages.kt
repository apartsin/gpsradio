package com.gpsradio.core.lang

/** Language selection and resolution (spec A §13, spec B §21). Tags are BCP-47. */
object Languages {
    data class Language(val tag: String, val displayName: String) {
        /** ISO-639-1 base, e.g. "ru" for "ru-RU"; also the Wikipedia edition code. */
        val base: String get() = tag.substringBefore('-').lowercase()
    }

    const val FALLBACK = "en-US"

    val supported: List<Language> = listOf(
        Language("en-US", "English"),
        Language("ru-RU", "Русский"),
        Language("he-IL", "עברית"),
        Language("de-DE", "Deutsch"),
        Language("fr-FR", "Français"),
        Language("es-ES", "Español"),
        Language("it-IT", "Italiano"),
        Language("pt-BR", "Português"),
        Language("uk-UA", "Українська"),
        Language("pl-PL", "Polski"),
        Language("nl-NL", "Nederlands"),
        Language("tr-TR", "Türkçe"),
        Language("ar", "العربية"),
        Language("ja-JP", "日本語"),
        Language("zh-CN", "中文"),
    )

    fun find(tag: String?): Language? {
        if (tag.isNullOrBlank()) return null
        supported.firstOrNull { it.tag.equals(tag, ignoreCase = true) }?.let { return it }
        val base = tag.substringBefore('-').lowercase()
        return supported.firstOrNull { it.base == base }
    }

    fun displayName(tag: String): String = find(tag)?.displayName ?: tag

    /**
     * Session language: explicit session choice → explicit preferred default →
     * supported device locale (auto mode) → product fallback.
     */
    fun resolveSessionLanguage(
        sessionOverride: String?,
        preferred: String?,
        autoMode: Boolean,
        deviceLocaleTag: String?,
    ): String {
        find(sessionOverride)?.let { return it.tag }
        if (!autoMode) find(preferred)?.let { return it.tag }
        find(deviceLocaleTag)?.let { return it.tag }
        find(preferred)?.let { return it.tag }
        return FALLBACK
    }
}
