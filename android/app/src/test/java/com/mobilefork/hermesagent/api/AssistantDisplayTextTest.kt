package com.mobilefork.hermesagent.api

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantDisplayTextTest {
    @Test
    fun emptyThinkingPrefacesAreRemovedWithoutChangingAnswerText() {
        val answer = "Shadows stretch long and warm.\nThe world begins to gleam anew."
        assertEquals(answer, assistantDisplayText("<think>\n\n</think>\n\n$answer"))
        assertEquals(answer, assistantDisplayText(" \n<THINK > </THINK>\r\n<think>\n</think>\n$answer"))
    }

    @Test
    fun literalMarkupCodeAndNonemptyBlocksRemainUnmodified() {
        listOf("<think></think> means an empty block", "```xml\n<think>\n</think>\n```",
            "Use `<think></think>`.", "<think>\n</think>\n", "<think>substantive text</think>\nAnswer",
            "Answer\n<think>\n</think>\nMore text").forEach { text ->
            assertEquals(text, assistantDisplayText(text))
        }
    }
}
