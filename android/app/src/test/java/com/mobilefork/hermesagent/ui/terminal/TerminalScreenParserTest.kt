package com.mobilefork.hermesagent.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalScreenParserTest {
    @Test
    fun interactiveProotLoginBecomesVirtualSessionInsteadOfBlockingCommand() {
        assertEquals(
            SandboxLoginRequest("hermes-alpine", null),
            parseSandboxLoginRequest("proot-distro login hermes-alpine"),
        )
        assertEquals(
            SandboxLoginRequest("hermes-alpine", null),
            parseSandboxLoginRequest("pd login hermes-alpine -- /bin/sh"),
        )
    }

    @Test
    fun inlineProotLoginKeepsOneShotGuestCommand() {
        assertEquals(
            SandboxLoginRequest("hermes-alpine", "pwd"),
            parseSandboxLoginRequest("proot-distro login hermes-alpine -- pwd"),
        )
    }

    @Test
    fun ordinaryTerminalCommandIsNotParsedAsSandboxLogin() {
        assertNull(parseSandboxLoginRequest("printf 'proot-distro login hermes-alpine'"))
    }
}
