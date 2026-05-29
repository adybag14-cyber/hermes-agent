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

    @Test
    fun chatDisplayTextCleansCollapsedInlineDiagnosticTable() {
        val rendered = sanitizeChatDisplayText(
            "**Full feature test results** | Tool | Status | Detail || --- | --- | --- || **Terminal** | X error | `env_var_enabled` not defined || **Android UI** | needs service | `HermesAccessibilityUiBridge` gated",
        )

        assertTrue(rendered.contains("Full feature test results  Tool  Status  Detail"))
        assertTrue(rendered.contains("Terminal  X error  env_var_enabled not defined"))
        assertTrue(rendered.contains("Android UI  needs service  HermesAccessibilityUiBridge gated"))
        assertFalse(rendered.contains("**"))
        assertFalse(rendered.contains("`"))
        assertFalse(rendered.contains("| --- |"))
    }
}
