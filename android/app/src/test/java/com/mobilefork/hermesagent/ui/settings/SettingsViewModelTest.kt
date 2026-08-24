package com.mobilefork.hermesagent.ui.settings

import android.content.Intent
import android.provider.Browser
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.backend.LocalBackendStatus
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.models.VerifiedLocalModelArtifacts
import com.mobilefork.hermesagent.models.LocalModelRuntimeSelectionAuthority
import com.mobilefork.hermesagent.models.persistPreferredModelRuntimeSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SettingsViewModelTest {
    @Test
    fun settingsEntryRefreshMirrorsFullPersistedTupleReconciledWhileViewModelWasInactive() {
        val application: android.app.Application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        val original = store.load()
        try {
            store.save(
                original.copy(
                    llamaCppRuntimeLane = "stable",
                    llamaCppCacheTypeK = "default",
                    llamaCppCacheTypeV = "f16",
                    llamaCppFlashAttention = "off",
                    llamaCppAdditionalArguments = listOf("--threads", "2"),
                ),
            )
            val viewModel = SettingsViewModel(application)

            val reconciled = store.update {
                it.copy(
                    llamaCppRuntimeLane = "turboquant",
                    llamaCppCacheTypeK = "q5_0",
                    llamaCppCacheTypeV = "q5_1",
                    llamaCppFlashAttention = "on",
                    llamaCppAdditionalArguments = listOf("--threads", "4", "--perf"),
                )
            }

            // SettingsScreen invokes this on entry. The tuple publication is deliberately
            // synchronous even though the endpoint probe continues in the background.
            viewModel.refreshAgentEndpoint(forceStart = false)

            assertEquals(
                reconciled.llamaCppAdvancedSettingsTuple(),
                viewModel.uiState.value.llamaCppAdvancedSettingsTuple(),
            )
            assertEquals(
                reconciled.llamaCppAdvancedSettingsTuple(),
                resolveLlamaCppAdvancedSettingsForSave(
                    existing = reconciled,
                    draft = viewModel.uiState.value,
                    persistDraft = false,
                ).llamaCppAdvancedSettingsTuple(),
            )
        } finally {
            store.save(original)
        }
    }

    @Test
    fun successfulRuntimeGenerationPublishesManagerReconciledTupleCoherently() {
        val application: android.app.Application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        val original = store.load()
        try {
            store.save(
                original.copy(
                    llamaCppRuntimeLane = "stable",
                    llamaCppCacheTypeK = "default",
                    llamaCppCacheTypeV = "default",
                    llamaCppFlashAttention = "default",
                    llamaCppAdditionalArguments = emptyList(),
                ),
            )
            val viewModel = SettingsViewModel(application)
            viewModel.updateLlamaCppAdditionalArguments(listOf("--threads", "6"))
            val generation = LocalModelRuntimeSelectionAuthority.beginAction()
            val savedDraft = viewModel.captureLlamaCppAdvancedDraft()
            val reconciled = store.update {
                it.copy(
                    llamaCppRuntimeLane = "turboquant",
                    llamaCppCacheTypeK = "q5_0",
                    llamaCppCacheTypeV = "q5_1",
                    llamaCppFlashAttention = "on",
                    llamaCppAdditionalArguments = listOf("--threads", "6"),
                )
            }

            assertTrue(
                viewModel.publishAuthoritativeLlamaCppSettingsForGeneration(
                    generation = generation,
                    expectedDraft = savedDraft,
                    authoritativeSettings = reconciled.llamaCppAdvancedSettingsTuple(),
                    allowExistingDraftChanges = true,
                ),
            )
            assertEquals(
                reconciled.llamaCppAdvancedSettingsTuple(),
                viewModel.uiState.value.llamaCppAdvancedSettingsTuple(),
            )
        } finally {
            store.save(original)
        }
    }

    @Test
    fun reconciledTuplePublicationPreservesActiveDraftAndRejectsStaleGeneration() {
        val application: android.app.Application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        val original = store.load()
        try {
            store.save(
                original.copy(
                    llamaCppRuntimeLane = "stable",
                    llamaCppCacheTypeK = "default",
                    llamaCppCacheTypeV = "f16",
                    llamaCppFlashAttention = "off",
                ),
            )
            val viewModel = SettingsViewModel(application)
            viewModel.updateLlamaCppCacheTypeK("q4_0")
            val activeDraft = viewModel.uiState.value.llamaCppAdvancedSettingsTuple()
            val passiveGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
            val passiveCapture = viewModel.captureLlamaCppAdvancedDraft()
            val reconciled = store.update {
                it.copy(
                    llamaCppRuntimeLane = "turboquant",
                    llamaCppCacheTypeK = "q5_0",
                    llamaCppCacheTypeV = "q5_1",
                    llamaCppFlashAttention = "on",
                )
            }.llamaCppAdvancedSettingsTuple()

            viewModel.refreshAgentEndpoint(forceStart = false)
            assertEquals(activeDraft, viewModel.uiState.value.llamaCppAdvancedSettingsTuple())

            assertTrue(
                viewModel.publishAuthoritativeLlamaCppSettingsForGeneration(
                    generation = passiveGeneration,
                    expectedDraft = passiveCapture,
                    authoritativeSettings = reconciled,
                    allowExistingDraftChanges = false,
                ),
            )
            assertEquals(activeDraft, viewModel.uiState.value.llamaCppAdvancedSettingsTuple())

            val editRaceGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
            val editRaceCapture = viewModel.captureLlamaCppAdvancedDraft()
            viewModel.updateLlamaCppCacheTypeV("q8_0")
            val editedAfterCapture = viewModel.uiState.value.llamaCppAdvancedSettingsTuple()
            assertTrue(
                viewModel.publishAuthoritativeLlamaCppSettingsForGeneration(
                    generation = editRaceGeneration,
                    expectedDraft = editRaceCapture,
                    authoritativeSettings = reconciled,
                    allowExistingDraftChanges = true,
                ),
            )
            assertEquals(editedAfterCapture, viewModel.uiState.value.llamaCppAdvancedSettingsTuple())

            val staleGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
            val staleCapture = viewModel.captureLlamaCppAdvancedDraft()
            LocalModelRuntimeSelectionAuthority.beginAction()
            assertFalse(
                viewModel.publishAuthoritativeLlamaCppSettingsForGeneration(
                    generation = staleGeneration,
                    expectedDraft = staleCapture,
                    authoritativeSettings = reconciled,
                    allowExistingDraftChanges = true,
                ),
            )
            assertEquals(editedAfterCapture, viewModel.uiState.value.llamaCppAdvancedSettingsTuple())
        } finally {
            store.save(original)
        }
    }

    @Test
    fun syncingDurablyCommittedNanbeigeLaneDoesNotSupersedeDownloadClickEpoch() {
        val application: android.app.Application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        val original = store.load()
        try {
            store.save(original.copy(llamaCppRuntimeLane = "turboquant"))
            val downloadClickGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
            val viewModel = SettingsViewModel(application)

            viewModel.syncPersistedRequiredLlamaCppRuntimeLane("turboquant")

            assertTrue(LocalModelRuntimeSelectionAuthority.isCurrent(downloadClickGeneration))
            assertEquals("turboquant", viewModel.uiState.value.llamaCppRuntimeLane)
        } finally {
            store.save(original)
        }
    }

    @Test
    fun savingRemoteBackendCancelsPendingNanbeigeAndLateCompletionCannotPromoteIt() {
        val application: android.app.Application = RuntimeEnvironment.getApplication()
        val settingsStore = AppSettingsStore(application)
        val downloadStore = LocalModelDownloadStore(application)
        val originalSettings = settingsStore.load()
        val originalDownloads = downloadStore.loadDownloads()
        val originalPreferredId = downloadStore.preferredDownloadId()
        val originalPendingId = downloadStore.pendingAutoStartRecordId()
        val artifact = VerifiedLocalModelArtifacts.releaseMatrix.single {
            it.modelId == "nanbeige4.2-3b-q4-k-m"
        }
        val pendingNanbeige = LocalModelDownloadRecord(
            id = "settings-remote-cancels-pending-nanbeige",
            title = artifact.modelId,
            sourceUrl = "https://huggingface.co/${artifact.repoId}/resolve/${artifact.revision}/${artifact.fileName}",
            repoOrUrl = artifact.repoId,
            filePath = artifact.fileName,
            revision = artifact.revision,
            runtimeFlavor = "GGUF",
            destinationFileName = artifact.fileName,
            destinationPath = "/models/${artifact.fileName}",
            downloadManagerId = -1L,
            totalBytes = artifact.expectedBytes,
            downloadedBytes = artifact.expectedBytes,
            status = "completed",
        )

        try {
            settingsStore.save(
                originalSettings.copy(
                    onDeviceBackend = BackendKind.LLAMA_CPP.persistedValue,
                    llamaCppRuntimeLane = "turboquant",
                ),
            )
            downloadStore.saveDownloads(listOf(pendingNanbeige))
            assertTrue(downloadStore.setPendingAutoStartRecordId(pendingNanbeige.id))
            val generation = SettingsSaveGeneration()
            val remoteSaveGeneration = generation.beginSave()

            updateSettingsAndPendingForGeneration(
                store = settingsStore,
                downloadStore = downloadStore,
                saveGeneration = generation,
                generation = remoteSaveGeneration,
                selectedBackend = BackendKind.NONE,
                selectedLlamaCppRuntimeLane = "turboquant",
            ) { current ->
                current.copy(onDeviceBackend = BackendKind.NONE.persistedValue)
            }

            assertEquals("", downloadStore.pendingAutoStartRecordId())
            assertEquals(BackendKind.NONE.persistedValue, settingsStore.load().onDeviceBackend)
            val downloadsViewModel = LocalModelDownloadsViewModel(application) { "" }
            assertEquals(
                LocalModelRuntimeHandoffResult.Rejected,
                downloadsViewModel.promoteDownloadedModelForAutoStart(pendingNanbeige.id),
            )
            assertTrue(LocalModelRuntimeSelectionAuthority.isCurrent(remoteSaveGeneration))
            assertEquals(BackendKind.NONE.persistedValue, settingsStore.load().onDeviceBackend)
            assertEquals("", downloadStore.preferredDownloadId())
        } finally {
            settingsStore.save(originalSettings)
            downloadStore.saveDownloads(originalDownloads)
            downloadStore.setPreferredDownloadId(originalPreferredId)
            downloadStore.setPendingAutoStartRecordId(originalPendingId)
        }
    }

    @Test
    fun dangerousStableLlamaSelectionCancelsSameTuplePendingOwner() {
        val application: android.app.Application = RuntimeEnvironment.getApplication()
        val settingsStore = AppSettingsStore(application)
        val downloadStore = LocalModelDownloadStore(application)
        val originalSettings = settingsStore.load()
        val originalDownloads = downloadStore.loadDownloads()
        val originalPreferredId = downloadStore.preferredDownloadId()
        val originalPendingId = downloadStore.pendingAutoStartRecordId()
        val pendingStableGguf = LocalModelDownloadRecord(
            id = "dangerous-stable-cancels-same-tuple-pending",
            title = "pending-stable.gguf",
            sourceUrl = "https://example.invalid/pending-stable.gguf",
            repoOrUrl = "example/pending-stable",
            filePath = "pending-stable.gguf",
            revision = "main",
            runtimeFlavor = "GGUF",
            destinationFileName = "pending-stable.gguf",
            destinationPath = "/models/pending-stable.gguf",
            downloadManagerId = -1L,
            totalBytes = 1_024L,
            downloadedBytes = 1_024L,
            status = "completed",
        )

        try {
            settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "stable"))
            downloadStore.saveDownloads(listOf(pendingStableGguf))
            assertTrue(downloadStore.setPendingAutoStartRecordId(pendingStableGguf.id))
            val generation = SettingsSaveGeneration()
            val ordinaryThemeSaveGeneration = generation.beginSave()
            updateSettingsAndPendingForGeneration(
                store = settingsStore,
                downloadStore = downloadStore,
                saveGeneration = generation,
                generation = ordinaryThemeSaveGeneration,
                selectedBackend = BackendKind.LLAMA_CPP,
                selectedLlamaCppRuntimeLane = "stable",
            ) { current -> current.copy(keywordHighlightingEnabled = !current.keywordHighlightingEnabled) }
            assertEquals(pendingStableGguf.id, downloadStore.pendingAutoStartRecordId())

            val dangerousStartGeneration = generation.beginSave()

            updateSettingsAndPendingForGeneration(
                store = settingsStore,
                downloadStore = downloadStore,
                saveGeneration = generation,
                generation = dangerousStartGeneration,
                selectedBackend = BackendKind.LLAMA_CPP,
                selectedLlamaCppRuntimeLane = "stable",
                clearAnyPendingAutoStart = true,
            ) { current ->
                current.copy(
                    onDeviceBackend = BackendKind.LLAMA_CPP.persistedValue,
                    llamaCppRuntimeLane = "stable",
                )
            }

            assertEquals("", downloadStore.pendingAutoStartRecordId())
            assertEquals(BackendKind.LLAMA_CPP.persistedValue, settingsStore.load().onDeviceBackend)
            assertEquals("stable", settingsStore.load().llamaCppRuntimeLane)
            assertEquals(
                LocalModelRuntimeHandoffResult.Rejected,
                LocalModelDownloadsViewModel(application) { "" }
                    .promoteDownloadedModelForAutoStart(pendingStableGguf.id),
            )
            assertTrue(LocalModelRuntimeSelectionAuthority.isCurrent(dangerousStartGeneration))
        } finally {
            settingsStore.save(originalSettings)
            downloadStore.saveDownloads(originalDownloads)
            downloadStore.setPreferredDownloadId(originalPreferredId)
            downloadStore.setPendingAutoStartRecordId(originalPendingId)
        }
    }

    @Test
    fun invalidationIsNonBlockingAndCleansStaleLongResultBeforeRejectingFollowOnEffects() {
        val generation = SettingsSaveGeneration()
        val oldSaveGeneration = generation.beginSave()
        val stopEntered = CountDownLatch(1)
        val allowStopToFinish = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val invalidationStarted = CountDownLatch(1)
        val invalidationFinished = CountDownLatch(1)
        val stopCalls = AtomicInteger(0)
        val staleCleanupCalls = AtomicInteger(0)
        val ensureCalls = AtomicInteger(0)
        val uiPublications = AtomicInteger(0)
        val workerFailure = AtomicReference<Throwable?>(null)
        val oldEffectFailure = AtomicReference<Throwable?>(null)
        var stopThread: Thread? = null
        var invalidationThread: Thread? = null

        try {
            stopThread = Thread {
                try {
                    generation.performLongIfCurrent(
                        candidate = oldSaveGeneration,
                        action = {
                            stopCalls.incrementAndGet()
                            stopEntered.countDown()
                            check(allowStopToFinish.await(5, TimeUnit.SECONDS))
                        },
                        cleanupStaleResultWhileOwned = {
                            staleCleanupCalls.incrementAndGet()
                        },
                    )
                } catch (error: Throwable) {
                    oldEffectFailure.set(error)
                } finally {
                    stopFinished.countDown()
                }
            }.apply {
                name = "admitted-old-runtime-stop"
                isDaemon = true
                start()
            }
            assertTrue("old stop did not enter its admitted effect", stopEntered.await(5, TimeUnit.SECONDS))

            invalidationThread = Thread {
                try {
                    invalidationStarted.countDown()
                    generation.invalidate()
                } catch (error: Throwable) {
                    workerFailure.compareAndSet(null, error)
                } finally {
                    invalidationFinished.countDown()
                }
            }.apply {
                name = "new-model-handoff-invalidation"
                isDaemon = true
                start()
            }
            assertTrue("new handoff did not request invalidation", invalidationStarted.await(5, TimeUnit.SECONDS))
            assertTrue(
                "epoch invalidation must not wait for a long admitted effect",
                invalidationFinished.await(250, TimeUnit.MILLISECONDS),
            )
            assertFalse("long effect unexpectedly finished before release", stopFinished.await(100, TimeUnit.MILLISECONDS))

            allowStopToFinish.countDown()
            assertTrue("old stop did not finish", stopFinished.await(5, TimeUnit.SECONDS))
            assertNull(workerFailure.get())
            assertTrue(oldEffectFailure.get() is SettingsSaveSupersededException)
            assertEquals(1, stopCalls.get())
            assertEquals(1, staleCleanupCalls.get())

            assertThrows(SettingsSaveSupersededException::class.java) {
                generation.withCurrent(oldSaveGeneration) {
                    ensureCalls.incrementAndGet()
                }
            }
            assertFalse(
                generation.runIfCurrent(oldSaveGeneration) {
                    uiPublications.incrementAndGet()
                },
            )
            assertEquals(0, ensureCalls.get())
            assertEquals(0, uiPublications.get())
        } finally {
            allowStopToFinish.countDown()
            stopThread?.join(1_000)
            invalidationThread?.join(1_000)
        }
    }

    @Test
    fun newerNanbeigeAdoptionAndHandoffSupersedePausedOlderAdvancedSave() {
        val application: android.app.Application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        val original = store.load()
        val baseline = original.copy(
            onDeviceBackend = BackendKind.NONE.persistedValue,
            llamaCppRuntimeLane = "stable",
            llamaCppCacheTypeK = "q5_0",
            llamaCppCacheTypeV = "q5_1",
            llamaCppFlashAttention = "on",
            llamaCppAdditionalArguments = listOf("--threads-batch", "3"),
        )
        val generation = SettingsSaveGeneration()
        val oldSaveGeneration = generation.beginSave()
        val oldSavePaused = CountDownLatch(1)
        val allowOldSave = CountDownLatch(1)
        val oldSaveFinished = CountDownLatch(1)
        val oldSaveFailure = AtomicReference<Throwable?>(null)
        val downloadPreferences = application.getSharedPreferences(
            "settings_paused_apply_nanbeige_handoff",
            0,
        )
        assertTrue(downloadPreferences.edit().clear().commit())
        val downloadStore = LocalModelDownloadStore(
            preferences = downloadPreferences,
            commitEditor = { editor -> editor.commit() },
        )
        val nanbeigeArtifact = VerifiedLocalModelArtifacts.releaseMatrix.single {
            it.modelId == "nanbeige4.2-3b-q4-k-m"
        }
        val nanbeigeRecord = LocalModelDownloadRecord(
            id = "paused-apply-nanbeige",
            title = nanbeigeArtifact.modelId,
            sourceUrl = "https://huggingface.co/${nanbeigeArtifact.repoId}/resolve/${nanbeigeArtifact.revision}/${nanbeigeArtifact.fileName}",
            repoOrUrl = nanbeigeArtifact.repoId,
            filePath = nanbeigeArtifact.fileName,
            revision = nanbeigeArtifact.revision,
            runtimeFlavor = "GGUF",
            destinationFileName = nanbeigeArtifact.fileName,
            destinationPath = "/models/${nanbeigeArtifact.fileName}",
            downloadManagerId = -1L,
            totalBytes = nanbeigeArtifact.expectedBytes,
            downloadedBytes = nanbeigeArtifact.expectedBytes,
            status = "completed",
        )
        var oldSaveThread: Thread? = null

        try {
            store.save(baseline)
            downloadStore.saveDownloads(listOf(nanbeigeRecord))
            oldSaveThread = Thread {
                try {
                    oldSavePaused.countDown()
                    check(allowOldSave.await(5, TimeUnit.SECONDS))
                    updateSettingsForGeneration(store, generation, oldSaveGeneration) { current ->
                        // This is the stale Settings/advanced Apply snapshot which must not
                        // overwrite a newer exact-model requirement or runtime handoff.
                        current.copy(
                            onDeviceBackend = BackendKind.NONE.persistedValue,
                            llamaCppRuntimeLane = "stable",
                            llamaCppCacheTypeK = "default",
                            llamaCppCacheTypeV = "default",
                            llamaCppFlashAttention = "default",
                            llamaCppAdditionalArguments = emptyList(),
                        )
                    }
                } catch (error: Throwable) {
                    oldSaveFailure.set(error)
                } finally {
                    oldSaveFinished.countDown()
                }
            }.apply {
                name = "paused-old-settings-save"
                isDaemon = true
                start()
            }

            assertTrue("old Settings save did not reach its pause", oldSavePaused.await(5, TimeUnit.SECONDS))

            assertTrue(
                persistPreferredModelRuntimeSelection(
                    settingsStore = store,
                    downloadStore = downloadStore,
                    recordId = nanbeigeRecord.id,
                    backendKind = BackendKind.LLAMA_CPP,
                    requiredLlamaCppRuntimeLane = "turboquant",
                ),
            )

            allowOldSave.countDown()
            assertTrue("old Settings save did not finish", oldSaveFinished.await(5, TimeUnit.SECONDS))
            assertTrue(oldSaveFailure.get() is SettingsSaveSupersededException)

            store.invalidateCache()
            val persisted = store.load()
            assertEquals(BackendKind.LLAMA_CPP.persistedValue, persisted.onDeviceBackend)
            assertEquals("turboquant", persisted.llamaCppRuntimeLane)
            assertEquals("q5_0", persisted.llamaCppCacheTypeK)
            assertEquals("q5_1", persisted.llamaCppCacheTypeV)
            assertEquals("on", persisted.llamaCppFlashAttention)
            assertEquals(listOf("--threads-batch", "3"), persisted.llamaCppAdditionalArguments)
            assertEquals(nanbeigeRecord.id, downloadStore.preferredDownloadId())
        } finally {
            allowOldSave.countDown()
            oldSaveThread?.join(1_000)
            store.save(original)
        }
    }

    @Test
    fun settingsSaveSurfacesUnsafeLocalShutdownInsteadOfStartingRemoteRuntime() {
        val message = "LiteRT-LM did not stop safely. Force stop and reopen Hermes."

        assertEquals(
            message,
            settingsSaveUnsafeTransitionMessage(
                LocalBackendStatus(
                    backendKind = BackendKind.LITERT_LM,
                    started = false,
                    statusMessage = message,
                    requiresAppRestart = true,
                ),
            ),
        )
        assertNull(
            settingsSaveUnsafeTransitionMessage(
                LocalBackendStatus(backendKind = BackendKind.NONE, started = false),
            ),
        )

        assertEquals(
            message,
            settingsRuntimeTransitionFailureMessage(
                backendKind = BackendKind.NONE,
                offlineAirplaneMode = false,
                localBackendStatus = LocalBackendStatus(
                    backendKind = BackendKind.LITERT_LM,
                    started = false,
                    statusMessage = message,
                    requiresAppRestart = true,
                ),
                runtimeState = HermesRuntimeManager.RuntimeState(
                    started = true,
                    baseUrl = "http://127.0.0.1:15436/v1",
                ),
            ),
        )
        assertEquals(
            "Final local startup failed",
            settingsRuntimeTransitionFailureMessage(
                backendKind = BackendKind.LITERT_LM,
                offlineAirplaneMode = false,
                localBackendStatus = LocalBackendStatus(
                    backendKind = BackendKind.LITERT_LM,
                    started = false,
                    statusMessage = "Final local startup failed",
                ),
                runtimeState = HermesRuntimeManager.RuntimeState(started = false),
            ),
        )
        assertEquals(
            "Remote restart failed",
            settingsRuntimeTransitionFailureMessage(
                backendKind = BackendKind.NONE,
                offlineAirplaneMode = false,
                localBackendStatus = LocalBackendStatus(backendKind = BackendKind.NONE, started = false),
                runtimeState = HermesRuntimeManager.RuntimeState(
                    started = false,
                    error = "Remote restart failed",
                ),
            ),
        )
        assertEquals(
            "Local runtime publication failed",
            settingsRuntimeTransitionFailureMessage(
                backendKind = BackendKind.LITERT_LM,
                offlineAirplaneMode = false,
                localBackendStatus = LocalBackendStatus(
                    backendKind = BackendKind.LITERT_LM,
                    started = true,
                    modelName = "experimental-local-model",
                    baseUrl = "http://127.0.0.1:15436/v1",
                ),
                runtimeState = HermesRuntimeManager.RuntimeState(
                    started = false,
                    error = "Local runtime publication failed",
                ),
            ),
        )
    }

    @Test
    fun readyLocalBackendSummaryPreservesMeasuredCompletionCanaryDetail() {
        val detailed =
            "llama.cpp Experimental TurboQuant / Nanbeige lane is serving locally; " +
                "completion canary passed with nonblank message.content (17 characters) in 321 ms"

        assertEquals(
            detailed,
            visibleReadyLocalBackendSummary(
                status = LocalBackendStatus(
                    backendKind = BackendKind.LLAMA_CPP,
                    started = true,
                    statusMessage = detailed,
                    completionVerified = true,
                    completionLatencyMs = 321L,
                ),
                fallback = { "generic ready" },
            ),
        )
        assertEquals(
            "generic ready",
            visibleReadyLocalBackendSummary(
                status = LocalBackendStatus(
                    backendKind = BackendKind.LLAMA_CPP,
                    started = true,
                    statusMessage = detailed,
                    completionVerified = false,
                ),
                fallback = { "generic ready" },
            ),
        )
    }

    @Test
    fun saveAgentPersonaPersistsCustomSystemPromptWithoutSecrets() {
        val application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        store.save(AppSettings())
        val viewModel = SettingsViewModel(application)

        viewModel.updateCustomSystemPrompt("Stay concise and prefer local diagnostics first.")
        viewModel.saveAgentPersona()

        assertEquals(
            "Stay concise and prefer local diagnostics first.",
            store.load().customSystemPrompt,
        )
        assertTrue(viewModel.uiState.value.status.contains("Agent persona saved"))
    }

    @Test
    fun saveModelGenerationConfigPersistsSamplingAndApiOptIn() {
        val application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        store.save(AppSettings())
        val viewModel = SettingsViewModel(application)

        viewModel.updateLocalModelMaxTokens(3072)
        viewModel.updateLocalModelTopK(72)
        viewModel.updateLocalModelTopP(0.85f)
        viewModel.updateLocalModelTemperature(0.6f)
        viewModel.updateLocalModelAccelerator("gpu")
        viewModel.updateApiGenerationKnobsEnabled(true)
        viewModel.updateCustomSystemPrompt("Prefer concise local model replies.")
        viewModel.saveModelGenerationConfig()

        val reloaded = store.load()
        assertEquals(3072, reloaded.localModelMaxTokens)
        assertEquals(72, reloaded.localModelTopK)
        assertEquals(0.85f, reloaded.localModelTopP, 0.0001f)
        assertEquals(0.6f, reloaded.localModelTemperature, 0.0001f)
        assertEquals("gpu", reloaded.localModelAccelerator)
        assertTrue(reloaded.apiGenerationKnobsEnabled)
        assertEquals("Prefer concise local model replies.", reloaded.customSystemPrompt)
        assertTrue(viewModel.uiState.value.status.contains("Model configuration saved"))
    }

    @Test
    fun llamaCppAdvancedDraftPreservesEveryRawArgvLineUntilValidation() {
        val application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        store.save(AppSettings())
        val viewModel = SettingsViewModel(application)

        viewModel.updateLlamaCppRuntimeLane("experimental")
        viewModel.updateLlamaCppCacheTypeK("Q5_0")
        viewModel.updateLlamaCppCacheTypeV("turbo3")
        viewModel.updateLlamaCppFlashAttention("ON")
        val rawArguments = listOf(
            " --load-mode ",
            "mmap",
            "",
            "x".repeat(AppSettings.MAX_LLAMA_CPP_ARGUMENT_CHARS + 1),
        )
        viewModel.updateLlamaCppAdditionalArguments(rawArguments)

        val state = viewModel.uiState.value
        assertEquals("turboquant", state.llamaCppRuntimeLane)
        assertEquals("q5_0", state.llamaCppCacheTypeK)
        assertEquals("turbo3", state.llamaCppCacheTypeV)
        assertEquals("on", state.llamaCppFlashAttention)
        assertEquals(rawArguments, state.llamaCppAdditionalArguments)
        assertEquals(
            "invalid_arguments",
            llamaCppAdvancedValidationKey(
                state.llamaCppRuntimeLane,
                state.llamaCppCacheTypeK,
                state.llamaCppCacheTypeV,
                state.llamaCppFlashAttention,
                state.llamaCppAdditionalArguments,
            ),
        )
    }

    @Test
    fun llamaCppAdvancedValidationRejectsUnsupportedTurboCombinations() {
        assertEquals(
            "invalid_stable_turbo",
            llamaCppAdvancedValidationKey("stable", "turbo2", "default", "on"),
        )
        assertEquals(
            "invalid_quantized_v_flash_off",
            llamaCppAdvancedValidationKey("turboquant", "turbo2", "q5_0", "off"),
        )
        assertEquals(
            "invalid_turbo_flash_off",
            llamaCppAdvancedValidationKey("turboquant", "turbo3", "default", "off"),
        )
        assertNull(llamaCppAdvancedValidationKey("turboquant", "q5_0", "turbo3", "auto"))
        assertNull(llamaCppAdvancedValidationKey("turboquant", "q5_0", "turbo3", "default"))
        assertNull(llamaCppAdvancedValidationKey("turboquant", "q5_1", "turbo3", "on"))
        assertEquals(
            "invalid_quantized_v_flash_off",
            llamaCppAdvancedValidationKey("stable", "q5_0", "q5_1", "off"),
        )
        assertNull(llamaCppAdvancedValidationKey("stable", "q5_0", "f16", "off"))
        assertEquals(
            "invalid_arguments",
            llamaCppAdvancedValidationKey(
                "stable",
                "q5_0",
                "f16",
                "off",
                listOf("--no-mmap"),
            ),
        )
        assertEquals(
            "invalid_arguments",
            llamaCppAdvancedValidationKey(
                "stable",
                "default",
                "default",
                "default",
                listOf("--load-mode=mmap"),
            ),
        )
        assertNull(
            llamaCppAdvancedValidationKey(
                "stable",
                "default",
                "default",
                "default",
                listOf("--tags", "mmap"),
            ),
        )
        assertEquals(
            "invalid_arguments",
            llamaCppAdvancedValidationKey(
                "stable",
                "default",
                "default",
                "default",
                List(AppSettings.MAX_LLAMA_CPP_ADDITIONAL_ARGUMENTS + 1) { "--mlock" },
            ),
        )
        assertEquals(
            "invalid_arguments",
            llamaCppAdvancedValidationKey(
                "stable",
                "default",
                "default",
                "default",
                listOf("-" + "x".repeat(AppSettings.MAX_LLAMA_CPP_ARGUMENT_CHARS)),
            ),
        )
        assertEquals(
            "invalid_arguments",
            llamaCppAdvancedValidationKey(
                "stable",
                "default",
                "default",
                "default",
                listOf("--load-mode", ""),
            ),
        )
        assertEquals(
            "invalid_arguments",
            llamaCppAdvancedValidationKey(
                "stable",
                "default",
                "default",
                "default",
                listOf("--load-mode", "mmap\u0000"),
            ),
        )
        assertEquals(
            "invalid_arguments",
            llamaCppAdvancedValidationKey(
                "stable",
                "default",
                "default",
                "default",
                listOf(" --no-mmap "),
            ),
        )
    }

    @Test
    fun llamaCppArgumentLineParserPreservesInvalidLinesForVisibleValidation() {
        assertTrue(llamaCppArgumentLines("").isEmpty())
        assertEquals(listOf("--load-mode", "mmap"), llamaCppArgumentLines("--load-mode\r\nmmap"))
        assertEquals(listOf("--mlock", ""), llamaCppArgumentLines("--mlock\n"))
        assertEquals(listOf("", "--mlock"), llamaCppArgumentLines("\n--mlock"))
        assertEquals(listOf("--load-mode", "mmap\u0000"), llamaCppArgumentLines("--load-mode\nmmap\u0000"))
    }

    @Test
    fun invalidLlamaCppAdvancedApplyDoesNotPersistDraft() {
        val application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        store.save(AppSettings())
        val viewModel = SettingsViewModel(application)

        viewModel.updateLlamaCppAdditionalArguments(
            List(AppSettings.MAX_LLAMA_CPP_ADDITIONAL_ARGUMENTS + 1) { "--mlock" },
        )
        viewModel.applyLlamaCppAdvancedSettings()

        assertTrue(store.load().llamaCppAdditionalArguments.isEmpty())
        assertTrue(viewModel.uiState.value.status.contains("Additional arguments are invalid"))
    }

    @Test
    fun generalSaveResolutionPreservesEntireExistingLlamaTupleWhenDraftIsInvalid() {
        val existing = AppSettings(
            llamaCppRuntimeLane = "turboquant",
            llamaCppCacheTypeK = "turbo3",
            llamaCppCacheTypeV = "q5_1",
            llamaCppFlashAttention = "auto",
            llamaCppAdditionalArguments = listOf("--tags", "mmap"),
        )
        val invalidDraft = SettingsUiState(
            llamaCppRuntimeLane = "stable",
            llamaCppCacheTypeK = "turbo3",
            llamaCppCacheTypeV = "q5_0",
            llamaCppFlashAttention = "off",
            llamaCppAdditionalArguments = List(
                AppSettings.MAX_LLAMA_CPP_ADDITIONAL_ARGUMENTS + 1,
            ) { "--perf" },
        )

        val resolved = resolveLlamaCppAdvancedSettingsForGeneralSave(existing, invalidDraft)

        assertEquals(existing.llamaCppRuntimeLane, resolved.llamaCppRuntimeLane)
        assertEquals(existing.llamaCppCacheTypeK, resolved.llamaCppCacheTypeK)
        assertEquals(existing.llamaCppCacheTypeV, resolved.llamaCppCacheTypeV)
        assertEquals(existing.llamaCppFlashAttention, resolved.llamaCppFlashAttention)
        assertEquals(existing.llamaCppAdditionalArguments, resolved.llamaCppAdditionalArguments)
    }

    @Test
    fun generalSaveResolutionCanonicalizesOnlyAfterTheWholeDraftIsValid() {
        val existing = AppSettings()
        val validDraft = SettingsUiState(
            llamaCppRuntimeLane = "experimental",
            llamaCppCacheTypeK = "Q5_0",
            llamaCppCacheTypeV = "turbo3",
            llamaCppFlashAttention = "AUTO",
            llamaCppAdditionalArguments = listOf("--tags", "matrix-profile", "--perf"),
        )

        val resolved = resolveLlamaCppAdvancedSettingsForGeneralSave(existing, validDraft)

        assertEquals("turboquant", resolved.llamaCppRuntimeLane)
        assertEquals("q5_0", resolved.llamaCppCacheTypeK)
        assertEquals("turbo3", resolved.llamaCppCacheTypeV)
        assertEquals("auto", resolved.llamaCppFlashAttention)
        assertEquals(validDraft.llamaCppAdditionalArguments, resolved.llamaCppAdditionalArguments)
        assertEquals(
            resolved.llamaCppAdditionalArguments.joinToString("\n"),
            validDraft.llamaCppAdditionalArguments.joinToString("\n"),
        )
    }

    @Test
    fun everyAppearancePresetPopulatesTheDraftAndPersistsAllFivePaletteFields() {
        val application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        val original = store.load()

        try {
            store.save(
                original.copy(
                    themePrimaryHex = "#123456",
                    themeSecondaryHex = "#234567",
                    themeBackgroundHex = "#345678",
                    themeSurfaceHex = "#456789",
                    themeSurfaceVariantHex = "#56789A",
                ),
            )
            val viewModel = SettingsViewModel(application)

            appearanceThemePresets.forEach { preset ->
                assertTrue(
                    "${preset.id} must start from a different persisted palette",
                    !store.load().matchesPalette(preset),
                )

                viewModel.applyThemePreset(preset)
                assertUiStateMatchesPalette("${preset.id} draft", preset, viewModel.uiState.value)

                viewModel.saveAppearance()
                store.invalidateCache()
                assertSettingsMatchPalette("${preset.id} persisted", preset, store.load())
            }
        } finally {
            store.save(original)
        }
    }

    @Test
    fun everyShapeAndFontStateUpdatesDraftAndPersistsExactPair() {
        val application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        val original = store.load()
        val canonicalTargets = appearanceCardShapes.map { shape ->
            ShapeFontTarget(shape, expectedRegressionFontScale(shape))
        }
        val targets = canonicalTargets.filterNot { it.shape == "rounded" } +
            canonicalTargets.single { it.shape == "rounded" }

        try {
            store.save(
                original.copy(
                    themeCardShape = "rounded",
                    uiFontScale = AppSettings.DEFAULT_UI_FONT_SCALE,
                ),
            )
            val viewModel = SettingsViewModel(application)

            targets.forEach { target ->
                val draftBefore = viewModel.uiState.value
                val storedBefore = store.load()
                assertTrue(
                    "$target must start from a different draft pair",
                    !draftBefore.matches(target),
                )
                assertTrue(
                    "$target must start from a different persisted pair",
                    !storedBefore.matches(target),
                )

                viewModel.updateThemeCardShape(target.shape)
                viewModel.updateUiFontScale(target.fontScale)

                val draft = viewModel.uiState.value
                assertEquals("$target draft shape", target.shape, draft.themeCardShape)
                assertEquals("$target draft font scale", target.fontScale, draft.uiFontScale, 0.0001f)

                store.invalidateCache()
                val beforeSave = store.load()
                assertEquals(
                    "$target visible shape action must persist the selected shape",
                    target.shape,
                    beforeSave.themeCardShape,
                )
                assertTrue(
                    "$target pair must not be fully persisted before Save",
                    !beforeSave.matches(target),
                )

                viewModel.saveAppearance()
                store.invalidateCache()
                val persisted = store.load()
                assertEquals("$target persisted shape", target.shape, persisted.themeCardShape)
                assertEquals(
                    "$target persisted font scale",
                    target.fontScale,
                    persisted.uiFontScale,
                    0.0001f,
                )
            }
        } finally {
            store.save(original)
        }
    }

    @Test
    fun completedDownloadRuntimeHandoffIsDurableBeforeTheMethodReturns() {
        val application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        val original = store.load()

        try {
            store.save(original.copy(onDeviceBackend = BackendKind.NONE.persistedValue))
            val viewModel = SettingsViewModel(application)

            assertTrue(viewModel.startLocalRuntimeForFlavor("LiteRT-LM"))

            store.invalidateCache()
            assertEquals(BackendKind.LITERT_LM.persistedValue, store.load().onDeviceBackend)
        } finally {
            store.save(original)
        }
    }

    @Test
    fun explicitSameTupleLocalStartCancelsOlderPendingOwnerBeforeLateCompletion() {
        val application: android.app.Application = RuntimeEnvironment.getApplication()
        val settingsStore = AppSettingsStore(application)
        val downloadStore = LocalModelDownloadStore(application)
        val originalSettings = settingsStore.load()
        val originalDownloads = downloadStore.loadDownloads()
        val originalPreferredId = downloadStore.preferredDownloadId()
        val originalPendingId = downloadStore.pendingAutoStartRecordId()
        val pendingA = LocalModelDownloadRecord(
            id = "explicit-start-older-pending-a",
            title = "pending-a.gguf",
            sourceUrl = "https://example.invalid/pending-a.gguf",
            repoOrUrl = "example/pending-a",
            filePath = "pending-a.gguf",
            revision = "main",
            runtimeFlavor = "GGUF",
            destinationFileName = "pending-a.gguf",
            destinationPath = "/models/pending-a.gguf",
            downloadManagerId = -1L,
            totalBytes = 1_024L,
            downloadedBytes = 1_024L,
            status = "completed",
        )
        val preferredB = pendingA.copy(
            id = "explicit-start-preferred-b",
            title = "preferred-b.gguf",
            sourceUrl = "https://example.invalid/preferred-b.gguf",
            repoOrUrl = "example/preferred-b",
            filePath = "preferred-b.gguf",
            destinationFileName = "preferred-b.gguf",
            destinationPath = "/models/preferred-b.gguf",
        )

        try {
            settingsStore.save(
                originalSettings.copy(
                    onDeviceBackend = BackendKind.LLAMA_CPP.persistedValue,
                    llamaCppRuntimeLane = "stable",
                ),
            )
            downloadStore.saveDownloads(listOf(pendingA, preferredB))
            assertTrue(downloadStore.setPreferredDownloadId(preferredB.id))
            assertTrue(downloadStore.setPendingAutoStartRecordId(pendingA.id))
            val settingsViewModel = SettingsViewModel(application)

            assertTrue(settingsViewModel.startLocalRuntimeForFlavor("GGUF"))
            assertEquals("", downloadStore.pendingAutoStartRecordId())
            assertEquals(preferredB.id, downloadStore.preferredDownloadId())
            assertEquals(
                LocalModelRuntimeHandoffResult.Rejected,
                LocalModelDownloadsViewModel(application) { "" }
                    .promoteDownloadedModelForAutoStart(pendingA.id),
            )
            assertEquals(preferredB.id, downloadStore.preferredDownloadId())
        } finally {
            settingsStore.save(originalSettings)
            downloadStore.saveDownloads(originalDownloads)
            downloadStore.setPreferredDownloadId(originalPreferredId)
            downloadStore.setPendingAutoStartRecordId(originalPendingId)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun openProviderKeyPageUsesExternalBrowserForProviderSetupUrls() {
        val application = RuntimeEnvironment.getApplication()
        val viewModel = SettingsViewModel(application)

        viewModel.openProviderKeyPage("https://docs.qwencloud.com/api-reference/preparation/api-key")

        val started = Shadows.shadowOf(application).nextStartedActivity
        val wrapped = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        assertEquals(Intent.ACTION_VIEW, wrapped?.action)
        assertEquals(
            "https://docs.qwencloud.com/api-reference/preparation/api-key",
            wrapped?.data.toString(),
        )
        assertEquals(
            application.packageName,
            wrapped?.getStringExtra(Browser.EXTRA_APPLICATION_ID),
        )
        assertNull(wrapped?.`package`)
        assertTrue(viewModel.uiState.value.status.contains("in your browser"))
    }

    private fun assertUiStateMatchesPalette(
        stage: String,
        preset: AppearanceThemePreset,
        state: SettingsUiState,
    ) {
        assertEquals("$stage primary", preset.primaryHex, state.themePrimaryHex)
        assertEquals("$stage secondary", preset.secondaryHex, state.themeSecondaryHex)
        assertEquals("$stage background", preset.backgroundHex, state.themeBackgroundHex)
        assertEquals("$stage surface", preset.surfaceHex, state.themeSurfaceHex)
        assertEquals("$stage surface variant", preset.surfaceVariantHex, state.themeSurfaceVariantHex)
    }

    private fun assertSettingsMatchPalette(
        stage: String,
        preset: AppearanceThemePreset,
        settings: AppSettings,
    ) {
        assertEquals("$stage primary", preset.primaryHex, settings.themePrimaryHex)
        assertEquals("$stage secondary", preset.secondaryHex, settings.themeSecondaryHex)
        assertEquals("$stage background", preset.backgroundHex, settings.themeBackgroundHex)
        assertEquals("$stage surface", preset.surfaceHex, settings.themeSurfaceHex)
        assertEquals("$stage surface variant", preset.surfaceVariantHex, settings.themeSurfaceVariantHex)
    }

    private fun AppSettings.matchesPalette(preset: AppearanceThemePreset): Boolean {
        return themePrimaryHex.equals(preset.primaryHex, ignoreCase = true) &&
            themeSecondaryHex.equals(preset.secondaryHex, ignoreCase = true) &&
            themeBackgroundHex.equals(preset.backgroundHex, ignoreCase = true) &&
            themeSurfaceHex.equals(preset.surfaceHex, ignoreCase = true) &&
            themeSurfaceVariantHex.equals(preset.surfaceVariantHex, ignoreCase = true)
    }

    private data class ShapeFontTarget(
        val shape: String,
        val fontScale: Float,
    )

    private fun expectedRegressionFontScale(shape: String): Float = when (shape) {
        "rounded" -> AppSettings.DEFAULT_UI_FONT_SCALE
        "soft" -> AppSettings.MIN_UI_FONT_SCALE
        "square" -> AppSettings.MAX_UI_FONT_SCALE
        else -> error("No shape/font regression scale is defined for canonical shape '$shape'")
    }

    private fun SettingsUiState.matches(target: ShapeFontTarget): Boolean {
        return themeCardShape == target.shape &&
            kotlin.math.abs(uiFontScale - target.fontScale) < 0.0001f
    }

    private fun AppSettings.matches(target: ShapeFontTarget): Boolean {
        return themeCardShape == target.shape &&
            kotlin.math.abs(uiFontScale - target.fontScale) < 0.0001f
    }
}
