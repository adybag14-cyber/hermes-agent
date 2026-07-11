package com.mobilefork.hermesagent.ui.i18n

enum class AppLanguage(
    val tag: String,
    val flag: String,
    val nativeLabel: String,
) {
    ENGLISH("en", "🇬🇧", "English"),
    CHINESE("zh", "🇨🇳", "中文"),
    SPANISH("es", "🇪🇸", "Español"),
    GERMAN("de", "🇩🇪", "Deutsch"),
    PORTUGUESE("pt", "🇵🇹", "Português"),
    FRENCH("fr", "🇫🇷", "Français");

    companion object {
        fun fromTag(tag: String?): AppLanguage {
            val normalized = tag.orEmpty().trim().lowercase()
            if (normalized.isBlank()) return ENGLISH
            // Exact match first, then prefix (zh-CN → zh, pt-BR → pt).
            return entries.firstOrNull { it.tag == normalized }
                ?: entries.firstOrNull { normalized == it.tag || normalized.startsWith("${it.tag}-") || normalized.startsWith("${it.tag}_") }
                ?: ENGLISH
        }
    }
}
