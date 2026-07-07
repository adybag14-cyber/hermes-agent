package com.mobilefork.hermesagent.ui.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceViewModelSandboxTargetTest {
    @Test
    fun resolveSandboxTargetDefaultsToDebianBookworm() {
        val (distroId, sandboxName) = resolveSandboxTarget("debian-bookworm")
        assertEquals("debian-bookworm", distroId)
        assertEquals("hermes-debian", sandboxName)
    }

    @Test
    fun resolveSandboxTargetMapsAlpineCatalogEntry() {
        val (distroId, sandboxName) = resolveSandboxTarget("alpine-3-21")
        assertEquals("alpine-3-21", distroId)
        assertEquals("hermes-alpine", sandboxName)
    }

    @Test
    fun resolveSandboxTargetAcceptsSandboxNameAlias() {
        val (distroId, sandboxName) = resolveSandboxTarget("hermes-alpine")
        assertEquals("alpine-3-21", distroId)
        assertEquals("hermes-alpine", sandboxName)
    }
}