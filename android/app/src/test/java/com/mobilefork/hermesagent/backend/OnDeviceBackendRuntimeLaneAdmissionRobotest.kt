package com.mobilefork.hermesagent.backend

import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.models.LocalModelRuntimeSelectionAuthority
import com.mobilefork.hermesagent.models.RuntimeSelectionSupersededException
import com.mobilefork.hermesagent.models.VerifiedLocalModelArtifacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class OnDeviceBackendRuntimeLaneAdmissionRobotest {
    @Test
    fun staleNanbeigeStartupCannotOverwriteNewerSelectionLane() {
        val application: android.app.Application = RuntimeEnvironment.getApplication()
        val settingsStore = AppSettingsStore(application)
        val originalSettings = settingsStore.load()
        val staleReachedPersistence = CountDownLatch(1)
        val releaseStalePersistence = CountDownLatch(1)
        val staleFinished = CountDownLatch(1)
        val staleFailure = AtomicReference<Throwable?>(null)
        val staleResult = AtomicReference<OnDeviceBackendManager.VerifiedArtifactLaneReconciliation?>()
        var staleThread: Thread? = null

        try {
            settingsStore.save(
                originalSettings.copy(
                    model = "older-nanbeige-selection",
                    onDeviceBackend = BackendKind.LLAMA_CPP.persistedValue,
                    llamaCppRuntimeLane = "stable",
                ),
            )
            val staleSettings = settingsStore.load()
            val staleGeneration = LocalModelRuntimeSelectionAuthority.beginAction()

            staleThread = Thread {
                try {
                    staleResult.set(
                        OnDeviceBackendManager.reconcileVerifiedArtifactLlamaCppRuntimeLane(
                            currentSettings = staleSettings,
                            verifiedArtifact = requiredLaneArtifact(),
                            persistRequiredLane = { requiredLane ->
                                staleReachedPersistence.countDown()
                                check(releaseStalePersistence.await(5, TimeUnit.SECONDS))
                                OnDeviceBackendManager.persistRequiredLlamaCppRuntimeLaneIfAdmitted(
                                    settingsStore = settingsStore,
                                    requiredLane = requiredLane,
                                    admissionCheck = {
                                        LocalModelRuntimeSelectionAuthority.requireCurrent(staleGeneration)
                                    },
                                )
                            },
                        ),
                    )
                } catch (error: Throwable) {
                    staleFailure.set(error)
                } finally {
                    staleFinished.countDown()
                }
            }.apply {
                name = "stale-nanbeige-lane-reconciliation"
                isDaemon = true
                start()
            }

            assertTrue(
                "stale startup did not reach its final lane-persistence boundary",
                staleReachedPersistence.await(5, TimeUnit.SECONDS),
            )

            val newerGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
            LocalModelRuntimeSelectionAuthority.withCurrent(newerGeneration) {
                settingsStore.update { current ->
                    current.copy(
                        model = "newer-litert-selection",
                        onDeviceBackend = BackendKind.LITERT_LM.persistedValue,
                        llamaCppRuntimeLane = "stable",
                    )
                }
            }
            assertEquals("newer-litert-selection", settingsStore.load().model)
            assertEquals("stable", settingsStore.load().llamaCppRuntimeLane)

            releaseStalePersistence.countDown()
            assertTrue("stale startup did not finish", staleFinished.await(5, TimeUnit.SECONDS))
            assertNull(staleFailure.get())
            val rejected = staleResult.get()
            assertTrue(
                rejected is OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.PersistenceFailure,
            )
            rejected as OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.PersistenceFailure
            assertTrue(rejected.cause is RuntimeSelectionSupersededException)

            settingsStore.invalidateCache()
            val finalSettings = settingsStore.load()
            assertEquals("newer-litert-selection", finalSettings.model)
            assertEquals(BackendKind.LITERT_LM.persistedValue, finalSettings.onDeviceBackend)
            assertEquals("stable", finalSettings.llamaCppRuntimeLane)
        } finally {
            releaseStalePersistence.countDown()
            staleThread?.join(1_000L)
            settingsStore.save(originalSettings)
        }
    }

    @Test
    fun currentNanbeigeStartupRepairsFinalLaneAndPreservesLatestSettings() {
        val application: android.app.Application = RuntimeEnvironment.getApplication()
        val settingsStore = AppSettingsStore(application)
        val originalSettings = settingsStore.load()

        try {
            settingsStore.save(
                originalSettings.copy(
                    model = "captured-before-verification",
                    onDeviceBackend = BackendKind.LLAMA_CPP.persistedValue,
                    llamaCppRuntimeLane = "stable",
                ),
            )
            val capturedSettings = settingsStore.load()
            val currentGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
            settingsStore.update { latest ->
                latest.copy(
                    model = "latest-at-final-boundary",
                    llamaCppCacheTypeK = "q5_1",
                    llamaCppCacheTypeV = "q5_1",
                    llamaCppFlashAttention = "on",
                )
            }

            val result = OnDeviceBackendManager.reconcileVerifiedArtifactLlamaCppRuntimeLane(
                currentSettings = capturedSettings,
                verifiedArtifact = requiredLaneArtifact(),
                persistRequiredLane = { requiredLane ->
                    OnDeviceBackendManager.persistRequiredLlamaCppRuntimeLaneIfAdmitted(
                        settingsStore = settingsStore,
                        requiredLane = requiredLane,
                        admissionCheck = {
                            LocalModelRuntimeSelectionAuthority.requireCurrent(currentGeneration)
                        },
                    )
                },
            )

            assertTrue(result is OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.Ready)
            result as OnDeviceBackendManager.VerifiedArtifactLaneReconciliation.Ready
            assertTrue(result.persistedRequiredLane)
            assertEquals("turboquant", result.settings.llamaCppRuntimeLane)
            assertEquals("latest-at-final-boundary", result.settings.model)
            assertEquals("q5_1", result.settings.llamaCppCacheTypeK)
            assertEquals("q5_1", result.settings.llamaCppCacheTypeV)
            assertEquals("on", result.settings.llamaCppFlashAttention)

            settingsStore.invalidateCache()
            assertEquals(result.settings, settingsStore.load())
        } finally {
            settingsStore.save(originalSettings)
        }
    }

    private fun requiredLaneArtifact(): VerifiedLocalModelArtifacts.Artifact {
        return VerifiedLocalModelArtifacts.releaseMatrix.single {
            it.modelId == "nanbeige4.2-3b-q4-k-m"
        }
    }
}
