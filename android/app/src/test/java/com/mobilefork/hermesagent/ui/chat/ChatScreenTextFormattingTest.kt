package com.mobilefork.hermesagent.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScreenTextFormattingTest {
    @Test
    fun chatDisplayTextCleansMarkdownTablesAndEmphasis() {
        val rendered = sanitizeChatDisplayText(
            """
            **Hermes self-test**
            | Tool | Result | Detail |
            | --- | --- | --- |
            | terminal_tool | ready | bridge returned output |
            """.trimIndent(),
        )

        assertTrue(rendered.contains("Hermes self-test"))
        assertTrue(rendered.contains("Tool  Result  Detail"))
        assertTrue(rendered.contains("terminal_tool  ready  bridge returned output"))
        assertFalse(rendered.contains("**Hermes self-test**"))
        assertFalse(rendered.contains("| --- | --- | --- |"))
    }
}
