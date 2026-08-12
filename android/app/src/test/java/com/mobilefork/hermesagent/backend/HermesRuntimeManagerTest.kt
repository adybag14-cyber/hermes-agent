package com.mobilefork.hermesagent.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesRuntimeManagerTest {
    @Test
    fun routeConfiguredBackend_failedExplicitLocalSelectionNeverInvokesRemoteLauncher() {
        var localInvocations = 0
        var remoteInvocations = 0

        val result = HermesRuntimeManager.routeConfiguredBackend(
            selectedLocalBackend = BackendKind.LLAMA_CPP,
            remoteAllowed = true,
            localLauncher = {
                localInvocations += 1
                LocalBackendStatus(
                    backendKind = BackendKind.LLAMA_CPP,
                    started = false,
                    statusMessage = "GGUF completion canary failed",
                )
            },
            remoteLauncher = {
                remoteInvocations += 1
                "remote-started"
            },
        )

        assertTrue(result is HermesRuntimeManager.BackendRouteResult.LocalFailed)
        assertEquals(1, localInvocations)
        assertEquals(0, remoteInvocations)
    }

    @Test
    fun routeConfiguredBackend_remoteModeInvokesRemoteOnlyAfterLocalProbeIsNotReady() {
        var remoteInvocations = 0

        val result = HermesRuntimeManager.routeConfiguredBackend(
            selectedLocalBackend = BackendKind.NONE,
            remoteAllowed = true,
            localLauncher = {
                LocalBackendStatus(backendKind = BackendKind.NONE, started = false)
            },
            remoteLauncher = {
                remoteInvocations += 1
                "remote-started"
            },
        )

        assertTrue(result is HermesRuntimeManager.BackendRouteResult.Remote)
        assertEquals(1, remoteInvocations)
        assertEquals(
            "remote-started",
            (result as HermesRuntimeManager.BackendRouteResult.Remote).value,
        )
    }

    @Test
    fun currentState_defaultsToNotStarted() {
        assertFalse(HermesRuntimeManager.currentState().started)
    }

    @Test
    fun localBackendFallbackWarning_isBlankForRemoteMode() {
        val warning = HermesRuntimeManager.localBackendFallbackWarning(
            selectedLocalBackend = BackendKind.NONE,
            localBackendStatus = LocalBackendStatus(
                backendKind = BackendKind.NONE,
                started = false,
                statusMessage = "Remote provider mode",
            ),
        )

        assertNull(warning)
    }

    @Test
    fun localBackendFallbackWarning_preservesReasonForMissingModel() {
        val warning = HermesRuntimeManager.localBackendFallbackWarning(
            selectedLocalBackend = BackendKind.LLAMA_CPP,
            localBackendStatus = LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = false,
                statusMessage = "No preferred local model is ready for llama.cpp yet",
            ),
        )

        assertEquals(
            "Local llama.cpp backend unavailable: No preferred local model is ready for llama.cpp yet. " +
                "Remote fallback is disabled while a local backend is explicitly selected.",
            warning,
        )
    }

    @Test
    fun withLocalBackendWarning_appendsWarningToProbeText() {
        val warning = "Local litert-lm backend unavailable: model missing. Remote fallback is disabled."

        assertEquals(
            "python-ok\n$warning",
            with(HermesRuntimeManager) {
                "python-ok".withLocalBackendWarning(warning)
            },
        )
    }
}
