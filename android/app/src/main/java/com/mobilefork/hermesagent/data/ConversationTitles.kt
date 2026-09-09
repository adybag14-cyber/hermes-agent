package com.mobilefork.hermesagent.data

import java.util.Locale

enum class ConversationTitleSource(val persistedValue: String) {
    AUTOMATIC("automatic"),
    MANUAL("manual"),
    LOCAL_REGENERATED("local_regenerated");

    companion object {
        fun fromPersistedValue(value: String): ConversationTitleSource =
            entries.firstOrNull { it.persistedValue == value } ?: AUTOMATIC
    }
}

/** Local text only: this policy has no model, provider, network or credential dependency. */
internal object ConversationTitles {
    const val DEFAULT = "New chat"
    private val greetings = setOf(
        "hi", "hello", "hey", "你好", "您好", "嗨", "hola", "hallo", "olá", "ola", "bonjour", "salut",
    )

    fun normalize(text: String, maxCodePoints: Int = 96): String {
        val collapsed = text.replace(Regex("[\\p{Cc}\\p{Z}\\s]+"), " ").trim()
        if (collapsed.codePointCount(0, collapsed.length) <= maxCodePoints) return collapsed
        return collapsed.substring(0, collapsed.offsetByCodePoints(0, maxCodePoints - 1)).trimEnd() + "…"
    }

    fun automatic(existingTitle: String, messages: List<StoredConversationMessage>): String {
        val text = messages.firstOrNull { it.role == "user" }?.content.orEmpty().trim().removePrefix("/")
        return normalize(text, 48).ifBlank { existingTitle.ifBlank { DEFAULT } }
    }

    fun regenerate(messages: List<StoredConversationMessage>): String {
        val userText = messages.asSequence().filter { it.role == "user" }
            .map { normalize(it.content) }.filter { it.isNotBlank() }.toList()
        val meaningful = userText.firstOrNull { text ->
            !text.startsWith("/") && text.trimEnd { !it.isLetterOrDigit() }.lowercase(Locale.ROOT) !in greetings
        }
        return normalize(meaningful ?: userText.firstOrNull().orEmpty().removePrefix("/"), 48)
            .ifBlank { DEFAULT }
    }
}
