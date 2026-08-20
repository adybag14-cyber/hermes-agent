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
    fun routeConfiguredBackend_remoteModeRejectsUnsafeLocalShutdownWithoutRemoteLaunch() {
        var remoteInvocations = 0

        val result = HermesRuntimeManager.routeConfiguredBackend(
            selectedLocalBackend = BackendKind.NONE,
            remoteAllowed = true,
            localLauncher = {
                LocalBackendStatus(
                    backendKind = BackendKind.LITERT_LM,
                    started = false,
                    statusMessage = "LiteRT-LM shutdown is still unwinding; force stop and reopen Hermes",
                    requiresAppRestart = true,
                )
            },
            remoteLauncher = {
                remoteInvocations += 1
                "remote-started"
            },
        )

        assertTrue(result is HermesRuntimeManager.BackendRouteResult.LocalFailed)
        assertEquals(0, remoteInvocations)
    }

    @Test
    fun routeConfiguredBackend_failedRemoteStopBlocksEveryLaterLauncher() {
        var localInvocations = 0
        var remoteInvocations = 0

        val result = HermesRuntimeManager.routeConfiguredBackend(
            selectedLocalBackend = BackendKind.LITERT_LM,
            remoteAllowed = true,
            localLauncher = {
                localInvocations += 1
                LocalBackendStatus(backendKind = BackendKind.LITERT_LM, started = true)
            },
            remoteLauncher = {
                remoteInvocations += 1
                "remote-started"
            },
            remoteStopFailure = "Embedded API server thread remained alive",
        )

        assertTrue(result is HermesRuntimeManager.BackendRouteResult.RemoteOwnershipFailed)
        assertEquals(0, localInvocations)
        assertEquals(0, remoteInvocations)
    }

    @Test
    fun continueAfterSuccessfulRemoteStop_neverRestartsAfterStopFailure() {
        var restartInvocations = 0
        val stopFailure = HermesRuntimeManager.RuntimeState(
            started = false,
            error = "The previous remote runtime did not stop",
        )

        val blocked = HermesRuntimeManager.continueAfterSuccessfulRemoteStop(stopFailure) {
            restartInvocations += 1
            HermesRuntimeManager.RuntimeState(started = true)
        }

        assertEquals(stopFailure, blocked)
        assertEquals(0, restartInvocations)

        val restarted = HermesRuntimeManager.continueAfterSuccessfulRemoteStop(
            HermesRuntimeManager.RuntimeState(started = false),
        ) {
            restartInvocations += 1
            HermesRuntimeManager.RuntimeState(started = true)
        }
        assertTrue(restarted.started)
        assertEquals(1, restartInvocations)
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
