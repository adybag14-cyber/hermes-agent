package com.mobilefork.hermesagent.api

private val emptyThinkingPreface = Regex("""\A[\s]*(?:<think\s*>[\s]*</think\s*>[\t ]*\r?\n[\s]*)+""", RegexOption.IGNORE_CASE)

/** Remove empty model prefaces only; preserve literal inline/fenced markup and all substantive text. */
fun assistantDisplayText(text: String): String {
    val preface = emptyThinkingPreface.find(text) ?: return text
    val remainder = text.substring(preface.range.last + 1)
    return if (remainder.isNotBlank()) remainder else text
}
