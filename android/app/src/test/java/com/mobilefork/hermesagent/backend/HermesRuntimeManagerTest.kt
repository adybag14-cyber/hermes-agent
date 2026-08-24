package com.mobilefork.hermesagent.backend

import com.mobilefork.hermesagent.models.LocalModelRuntimeSelectionAuthority
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesRuntimeManagerTest {
    @Test
    fun activityLocalAutoStart_generationAndPersistedBackendValidationAreAtomic() {
        val selectionGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        val persistedBackend = AtomicReference(BackendKind.LLAMA_CPP)
        val backendReadEntered = CountDownLatch(1)
        val releaseBackendRead = CountDownLatch(1)
        val newerSelectionCommitted = CountDownLatch(1)
        val oldFailure = AtomicReference<Throwable?>(null)

        val oldAutoStart = thread(name = "activity-local-autostart-admission") {
            oldFailure.set(
                runCatching {
                    HermesRuntimeManager.validateExpectedLocalBackendAdmission(
                        expectedLocalBackend = BackendKind.LLAMA_CPP,
                        admissionCheck = {
                            LocalModelRuntimeSelectionAuthority.requireCurrent(selectionGeneration)
                        },
                        loadPersistedBackend = {
                            backendReadEntered.countDown()
                            check(releaseBackendRead.await(5, TimeUnit.SECONDS))
                            persistedBackend.get()
                        },
                    )
                }.exceptionOrNull(),
            )
        }
        assertTrue(backendReadEntered.await(5, TimeUnit.SECONDS))

        val newerSelection = thread(name = "newer-runtime-selection") {
            LocalModelRuntimeSelectionAuthority.beginAction()
            persistedBackend.set(BackendKind.NONE)
            newerSelectionCommitted.countDown()
        }

        assertFalse(
            "A newer selection landed between generation admission and backend validation",
            newerSelectionCommitted.await(100, TimeUnit.MILLISECONDS),
        )
        releaseBackendRead.countDown()
        oldAutoStart.join(5_000L)
        newerSelection.join(5_000L)

        assertFalse(oldAutoStart.isAlive)
        assertFalse(newerSelection.isAlive)
        assertNull(oldFailure.get())
        assertTrue(newerSelectionCommitted.await(0, TimeUnit.MILLISECONDS))
        assertEquals(BackendKind.NONE, persistedBackend.get())
    }

    @Test
    fun activityLocalAutoStart_finalBackendMismatchStopsStaleLocalBeforeNewOwner() {
        val selectionGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        val persistedBackend = AtomicReference(BackendKind.LLAMA_CPP)
        val backendReadEntered = CountDownLatch(1)
        val releaseBackendRead = CountDownLatch(1)
        val stopInvocations = AtomicInteger(0)
        val remoteInvocations = AtomicInteger(0)
        val finalState = AtomicReference<HermesRuntimeManager.RuntimeState?>(null)
        val staleLocal = HermesRuntimeManager.RuntimeState(
            started = true,
            baseUrl = "http://127.0.0.1:15435/v1",
            apiKey = "stale-local-bearer",
            localBackendKind = BackendKind.LLAMA_CPP,
            modelName = "stale-model-a",
        )

        val oldAutoStart = thread(name = "activity-local-autostart-final-admission") {
            val result = try {
                HermesRuntimeManager.validateExpectedLocalBackendAdmission(
                    expectedLocalBackend = BackendKind.LLAMA_CPP,
                    admissionCheck = {
                        LocalModelRuntimeSelectionAuthority.requireCurrent(selectionGeneration)
                    },
                    loadPersistedBackend = {
                        backendReadEntered.countDown()
                        check(releaseBackendRead.await(5, TimeUnit.SECONDS))
                        persistedBackend.get()
                    },
                )
                staleLocal
            } catch (_: ExpectedLocalBackendSupersededException) {
                HermesRuntimeManager.retireSupersededExpectedLocalBackend(
                    runtimeState = staleLocal,
                    stopAllLocalBackends = {
                        stopInvocations.incrementAndGet()
                        LocalBackendStatus(backendKind = BackendKind.NONE, started = false)
                    },
                )
            }
            finalState.set(result)
        }
        assertTrue(backendReadEntered.await(5, TimeUnit.SECONDS))

        // Model a direct persisted-backend change which did not increment the selection epoch.
        // The final expected-backend check must still retire the already-created local result.
        persistedBackend.set(BackendKind.NONE)
        releaseBackendRead.countDown()
        oldAutoStart.join(5_000L)

        assertFalse(oldAutoStart.isAlive)
        assertEquals(1, stopInvocations.get())
        assertEquals(0, remoteInvocations.get())
        val retired = requireNotNull(finalState.get())
        assertFalse(retired.started)
        assertNull(retired.baseUrl)
        assertNull(retired.apiKey)
        assertEquals(BackendKind.NONE, retired.localBackendKind)

        val newerGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        assertTrue(newerGeneration > selectionGeneration)
    }

    @Test
    fun activityLocalAutoStart_selectionChangedToNoneInvokesNeitherLauncher() {
        var persistedBackend = BackendKind.LLAMA_CPP
        val expectedAtActivityLaunch = persistedBackend
        var localInvocations = 0

        persistedBackend = BackendKind.NONE

        val result = HermesRuntimeManager.routeExpectedLocalBackend(
            selectedLocalBackend = persistedBackend,
            expectedLocalBackend = expectedAtActivityLaunch,
            localLauncher = {
                localInvocations += 1
                LocalBackendStatus(backendKind = BackendKind.LLAMA_CPP, started = true)
            },
        )

        assertTrue(result is HermesRuntimeManager.BackendRouteResult.SelectionSuperseded)
        val superseded = result as HermesRuntimeManager.BackendRouteResult.SelectionSuperseded
        assertEquals(BackendKind.LLAMA_CPP, superseded.expected)
        assertEquals(BackendKind.NONE, superseded.observed)
        assertEquals(0, localInvocations)
    }

    @Test
    fun routeConfiguredBackend_failedExplicitLocalSelectionNeverInvokesRemoteLauncher() {
        var localInvocations = 0
        var remoteInvocations = 0

        val result = HermesRuntimeManager.routeConfiguredBackend(
            selectedLocalBackend = BackendKind.LLAMA_CPP,
            remoteAllowed = true,
            localLauncher = { _ ->
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
            localLauncher = { _ ->
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
            localLauncher = { _ ->
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
            localLauncher = { _ ->
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
    fun routeConfiguredBackendForwardsOneShotRamAuthorityOnlyToThatLaunch() {
        val observedAuthorities = mutableListOf<Boolean>()
        val localLauncher: (Boolean) -> LocalBackendStatus = { authority ->
            observedAuthorities += authority
            LocalBackendStatus(backendKind = BackendKind.LLAMA_CPP, started = true)
        }

        HermesRuntimeManager.routeConfiguredBackend(
            selectedLocalBackend = BackendKind.LLAMA_CPP,
            remoteAllowed = true,
            dangerouslySkipRamChecks = true,
            localLauncher = localLauncher,
            remoteLauncher = { error("Explicit local selection must not launch remote") },
        )
        HermesRuntimeManager.routeConfiguredBackend(
            selectedLocalBackend = BackendKind.LLAMA_CPP,
            remoteAllowed = true,
            localLauncher = localLauncher,
            remoteLauncher = { error("Explicit local selection must not launch remote") },
        )

        assertEquals(listOf(true, false), observedAuthorities)
    }

    @Test
    fun currentState_defaultsToNotStarted() {
        assertFalse(HermesRuntimeManager.currentState().started)
    }

    @Test
    fun selectingNoneStopsOwnedLocalBackendBeforeCachedRemoteReuse() {
        var stopInvocations = 0
        val staleLocal = LocalBackendStatus(
            backendKind = BackendKind.LLAMA_CPP,
            started = true,
            baseUrl = "http://127.0.0.1:15435/v1",
            modelName = "stale-local",
            apiKey = "local-process-key",
        )

        val transitioned = HermesRuntimeManager.localStatusBeforeCachedRemoteReuse(
            selectedLocalBackend = BackendKind.NONE,
            observedLocalStatus = staleLocal,
            stopAllLocalBackends = {
                stopInvocations += 1
                LocalBackendStatus(backendKind = BackendKind.NONE, started = false)
            },
        )

        assertEquals(1, stopInvocations)
        assertEquals(BackendKind.NONE, transitioned.backendKind)
        assertFalse(transitioned.started)
        assertTrue(
            HermesRuntimeManager.shouldReuseCachedRemoteRuntime(
                selectedLocalBackend = BackendKind.NONE,
                localBackendStatus = transitioned,
                runtimeState = HermesRuntimeManager.RuntimeState(
                    started = true,
                    baseUrl = "https://remote.example/v1",
                    apiKey = "remote-key",
                    localBackendKind = BackendKind.NONE,
                ),
            ),
        )

        assertFalse(
            HermesRuntimeManager.shouldReuseCachedRemoteRuntime(
                selectedLocalBackend = BackendKind.NONE,
                localBackendStatus = transitioned,
                runtimeState = HermesRuntimeManager.RuntimeState(
                    started = true,
                    baseUrl = "http://127.0.0.1:15435/v1",
                    apiKey = "local-process-key",
                    localBackendKind = BackendKind.LLAMA_CPP,
                ),
            ),
        )
    }

    @Test
    fun cachedRemoteRuntimeCannotBypassAStaleStartedLocalStatus() {
        assertFalse(
            HermesRuntimeManager.shouldReuseCachedRemoteRuntime(
                selectedLocalBackend = BackendKind.NONE,
                localBackendStatus = LocalBackendStatus(
                    backendKind = BackendKind.LLAMA_CPP,
                    started = true,
                    baseUrl = "http://127.0.0.1:15435/v1",
                    modelName = "stale-local",
                    apiKey = "local-process-key",
                ),
                runtimeState = HermesRuntimeManager.RuntimeState(
                    started = true,
                    baseUrl = "http://127.0.0.1:15435/v1",
                    apiKey = "local-process-key",
                    localBackendKind = BackendKind.LLAMA_CPP,
                ),
            ),
        )
    }

    @Test
    fun successfulDirectLocalStopClearsStaleLoopbackUrlAndBearerButPreservesRemoteState() {
        val local = HermesRuntimeManager.RuntimeState(
            started = true,
            baseUrl = "http://127.0.0.1:15435/v1",
            apiKey = "owned-local-key",
            localBackendKind = BackendKind.LLAMA_CPP,
            modelName = "local-model",
        )
        val stopped = LocalBackendStatus(backendKind = BackendKind.NONE, started = false)

        val cleared = HermesRuntimeManager.runtimeStateAfterLocalOperation(local, stopped)

        assertFalse(cleared.started)
        assertNull(cleared.baseUrl)
        assertNull(cleared.apiKey)
        assertEquals(BackendKind.NONE, cleared.localBackendKind)

        val remote = HermesRuntimeManager.RuntimeState(
            started = true,
            baseUrl = "https://remote.example/v1",
            apiKey = "remote-key",
            localBackendKind = BackendKind.NONE,
        )
        assertEquals(remote, HermesRuntimeManager.runtimeStateAfterLocalOperation(remote, stopped))
    }

    @Test
    fun failedDirectLocalStopPublishesNoStaleEndpointOrBearer() {
        val previous = HermesRuntimeManager.RuntimeState(
            started = true,
            baseUrl = "http://127.0.0.1:15435/v1",
            apiKey = "still-live-but-no-longer-routable",
            localBackendKind = BackendKind.LLAMA_CPP,
        )
        val unsafe = LocalBackendStatus(
            backendKind = BackendKind.LLAMA_CPP,
            started = false,
            statusMessage = "Owned llama.cpp process did not stop safely",
            requiresAppRestart = true,
        )

        val failed = HermesRuntimeManager.runtimeStateAfterLocalOperation(previous, unsafe)

        assertFalse(failed.started)
        assertNull(failed.baseUrl)
        assertNull(failed.apiKey)
        assertEquals(BackendKind.LLAMA_CPP, failed.localBackendKind)
        assertTrue(failed.error.orEmpty().contains("did not stop safely"))
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
