package com.mobilefork.hermesagent.ui.settings

import android.net.Uri
import android.os.Looper
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.models.LocalModelRuntimeSelectionAuthority
import com.mobilefork.hermesagent.models.RuntimeSelectionSupersededException
import com.mobilefork.hermesagent.models.VerifiedLocalModelArtifacts
import com.mobilefork.hermesagent.models.clearPendingAutoStartForGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class LocalModelDownloadsViewModelTest {
    @Test
    fun exactNanbeigeLaneSurvivesTheIntegratedSettingsRuntimeHandoff() {
        val application = RuntimeEnvironment.getApplication()
        val settingsStore = AppSettingsStore(application)
        val downloadStore = LocalModelDownloadStore(application)
        val originalSettings = settingsStore.load()
        val originalDownloads = downloadStore.loadDownloads()
        val originalPreferredId = downloadStore.preferredDownloadId()
        val originalPendingId = downloadStore.pendingAutoStartRecordId()

        try {
            settingsStore.save(
                originalSettings.copy(
                    onDeviceBackend = BackendKind.NONE.persistedValue,
                    llamaCppRuntimeLane = "stable",
                    llamaCppCacheTypeK = "q5_0",
                    llamaCppCacheTypeV = "q5_1",
                    llamaCppFlashAttention = "auto",
                ),
            )
            // Settings is already open with the old Stable draft when the separate downloads
            // ViewModel applies the exact artifact's durable TurboQuant requirement.
            val settingsViewModel = SettingsViewModel(application)
            val downloadsViewModel = LocalModelDownloadsViewModel(application) { "" }
            val nanbeige = requireNotNull(
                downloadsViewModel.selectRecommendedModel("nanbeige4.2-3b-q4-k-m"),
            )

            assertEquals("stable", settingsViewModel.uiState.value.llamaCppRuntimeLane)
            assertEquals("turboquant", settingsStore.load().llamaCppRuntimeLane)

            // The UI callback adopts the requirement immediately, while a later handoff reloads
            // the authoritative entire tuple before ordinary Settings save/restart work begins.
            settingsViewModel.adoptRequiredLlamaCppRuntimeLane(
                nanbeige.requiredLlamaCppRuntimeLane,
            )
            assertEquals("turboquant", settingsViewModel.uiState.value.llamaCppRuntimeLane)

            settingsViewModel.updateLlamaCppRuntimeLane("stable")
            val durableBeforeHandoff = settingsStore.load()
            assertEquals(
                "turboquant",
                resolveLlamaCppAdvancedSettingsForSave(
                    existing = durableBeforeHandoff,
                    draft = settingsViewModel.uiState.value,
                    persistDraft = false,
                ).llamaCppRuntimeLane,
            )

            val handoff = requireNotNull(
                settingsViewModel.prepareLocalRuntimeHandoff(BackendKind.LLAMA_CPP.persistedValue),
            )
            assertEquals("turboquant", handoff.llamaCppRuntimeLane)
            assertEquals("turboquant", settingsViewModel.uiState.value.llamaCppRuntimeLane)
            assertEquals("q5_0", settingsViewModel.uiState.value.llamaCppCacheTypeK)
            assertEquals("q5_1", settingsViewModel.uiState.value.llamaCppCacheTypeV)
            assertEquals("auto", settingsViewModel.uiState.value.llamaCppFlashAttention)

            // A completed exact artifact must repeat the same synchronous contract at both
            // preferred-model and process-recreated auto-start handoff boundaries.
            val artifact = VerifiedLocalModelArtifacts.releaseMatrix.first {
                it.modelId == "nanbeige4.2-3b-q4-k-m"
            }
            val completedNanbeige = LocalModelDownloadRecord(
                id = "nanbeige-integrated-lane-handoff",
                title = artifact.modelId,
                sourceUrl =
                    "https://huggingface.co/${artifact.repoId}/resolve/${artifact.revision}/${artifact.fileName}",
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
            downloadStore.saveDownloads(listOf(completedNanbeige))

            settingsStore.save(settingsStore.load().copy(llamaCppRuntimeLane = "stable"))
            settingsViewModel.updateLlamaCppRuntimeLane("stable")
            settingsViewModel.adoptRequiredLlamaCppRuntimeLane(
                acceptedLane(downloadsViewModel.setPreferredDownload(completedNanbeige.id)),
            )
            assertEquals("turboquant", settingsStore.load().llamaCppRuntimeLane)
            assertEquals("turboquant", settingsViewModel.uiState.value.llamaCppRuntimeLane)

            settingsStore.save(settingsStore.load().copy(llamaCppRuntimeLane = "stable"))
            settingsViewModel.updateLlamaCppRuntimeLane("stable")
            assertTrue(downloadStore.setPendingAutoStartRecordId(completedNanbeige.id))
            settingsViewModel.adoptRequiredLlamaCppRuntimeLane(
                acceptedLane(
                    downloadsViewModel.promoteDownloadedModelForAutoStart(completedNanbeige.id),
                ),
            )
            assertEquals("turboquant", settingsStore.load().llamaCppRuntimeLane)
            assertEquals("turboquant", settingsViewModel.uiState.value.llamaCppRuntimeLane)

            // Even if a stale Settings draft appears immediately after the callback, the actual
            // runtime handoff reloads the authoritative TurboQuant lane before saving/restarting.
            settingsViewModel.updateLlamaCppRuntimeLane("stable")
            val autoStartHandoff = requireNotNull(
                settingsViewModel.prepareLocalRuntimeHandoff(BackendKind.LLAMA_CPP.persistedValue),
            )
            assertEquals("turboquant", autoStartHandoff.llamaCppRuntimeLane)
            assertEquals("turboquant", settingsViewModel.uiState.value.llamaCppRuntimeLane)

            val laneNeutral = requireNotNull(
                downloadsViewModel.selectRecommendedModel("qwen35-08b-q4km-gguf"),
            )
            settingsViewModel.adoptRequiredLlamaCppRuntimeLane(
                laneNeutral.requiredLlamaCppRuntimeLane,
            )
            assertEquals("turboquant", settingsStore.load().llamaCppRuntimeLane)
            assertEquals("turboquant", settingsViewModel.uiState.value.llamaCppRuntimeLane)
        } finally {
            settingsStore.save(originalSettings)
            downloadStore.saveDownloads(originalDownloads)
            downloadStore.setPreferredDownloadId(originalPreferredId)
            downloadStore.setPendingAutoStartRecordId(originalPendingId)
        }
    }

    @Test
    fun recommendedNanbeigeSelectionAppliesTurboQuantWithoutChangingLaneForOtherPresets() {
        val application = RuntimeEnvironment.getApplication()
        val settingsStore = AppSettingsStore(application)
        val originalSettings = settingsStore.load()
        val viewModel = LocalModelDownloadsViewModel(application) { "" }

        try {
            settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "stable"))
            val nanbeige = requireNotNull(viewModel.selectRecommendedModel("nanbeige4.2-3b-q4-k-m"))
            assertEquals("turboquant", nanbeige.requiredLlamaCppRuntimeLane)
            assertEquals("turboquant", settingsStore.load().llamaCppRuntimeLane)

            settingsStore.save(settingsStore.load().copy(llamaCppRuntimeLane = "stable"))
            requireNotNull(viewModel.selectRecommendedModel("qwen35-08b-q4km-gguf"))
            assertEquals("stable", settingsStore.load().llamaCppRuntimeLane)

            settingsStore.save(settingsStore.load().copy(llamaCppRuntimeLane = "turboquant"))
            requireNotNull(viewModel.selectRecommendedModel("minicpm5-1b-web-litert-lm"))
            assertEquals("turboquant", settingsStore.load().llamaCppRuntimeLane)
        } finally {
            settingsStore.save(originalSettings)
        }
    }

    @Test
    fun pendingAutoStartSurvivesRecreationAndClearsExactlyOnceAfterAcceptedHandoff() {
        val application = RuntimeEnvironment.getApplication()
        val store = LocalModelDownloadStore(application)
        val originalDownloads = store.loadDownloads()
        val originalPendingId = store.pendingAutoStartRecordId()
        val recordId = "download-awaiting-runtime-handoff"
        val record = LocalModelDownloadRecord(
            id = recordId,
            title = "pending.gguf",
            sourceUrl = "https://example.invalid/pending.gguf",
            repoOrUrl = "example/pending",
            filePath = "pending.gguf",
            revision = "main",
            runtimeFlavor = "GGUF",
            destinationFileName = "pending.gguf",
            destinationPath = "/models/pending.gguf",
            downloadManagerId = -1L,
        )

        try {
            store.saveDownloads(listOf(record))
            assertTrue(store.setPendingAutoStartRecordId(recordId))
            val first = LocalModelDownloadsViewModel(application) { "" }
            assertEquals(recordId, first.uiState.value.pendingAutoStartRecordId)

            assertFalse(first.completePendingAutoStartHandoff(recordId, accepted = false))
            assertEquals(recordId, store.pendingAutoStartRecordId())

            val recreated = LocalModelDownloadsViewModel(application) { "" }
            assertEquals(recordId, recreated.uiState.value.pendingAutoStartRecordId)
            assertTrue(recreated.completePendingAutoStartHandoff(recordId, accepted = true))
            assertEquals("", store.pendingAutoStartRecordId())
            assertEquals("", recreated.uiState.value.pendingAutoStartRecordId)

            assertFalse(recreated.completePendingAutoStartHandoff(recordId, accepted = true))
        } finally {
            store.saveDownloads(originalDownloads)
            store.setPendingAutoStartRecordId(originalPendingId)
        }
    }

    @Test
    fun nanbeigeLaneCommitFailureRejectsAutoStartWithoutRuntimeCallbackOrPendingClear() {
        val application = RuntimeEnvironment.getApplication()
        val durableSettingsStore = AppSettingsStore(application)
        val originalSettings = durableSettingsStore.load()
        val preferences = application.getSharedPreferences(
            "local_model_handoff_nanbeige_lane_failure",
            0,
        )
        assertTrue(preferences.edit().clear().commit())
        val downloadStore = LocalModelDownloadStore(
            preferences = preferences,
            commitEditor = { editor -> editor.commit() },
        )
        val record = completedNanbeigeRecord("nanbeige-lane-commit-failure")

        try {
            durableSettingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "stable"))
            downloadStore.saveDownloads(listOf(record))
            assertTrue(downloadStore.setPendingAutoStartRecordId(record.id))
            val viewModel = LocalModelDownloadsViewModel(
                application = application,
                huggingFaceTokenLoader = { "" },
                settingsStore = AppSettingsStore.withCommitterForTest(application) { false },
                downloadStore = downloadStore,
            )

            val promotion = viewModel.promoteDownloadedModelForAutoStart(record.id)
            var runtimeCallbackCount = 0
            val promotionAccepted = dispatchAcceptedLocalModelRuntimeHandoff(promotion) { _, _ ->
                runtimeCallbackCount += 1
            }
            if (promotionAccepted) {
                viewModel.completePendingAutoStartHandoff(record.id, accepted = true)
            }

            assertEquals(LocalModelRuntimeHandoffResult.Rejected, promotion)
            assertFalse(promotionAccepted)
            assertEquals(0, runtimeCallbackCount)
            assertEquals(record.id, downloadStore.pendingAutoStartRecordId())
            assertEquals("stable", durableSettingsStore.load().llamaCppRuntimeLane)
        } finally {
            durableSettingsStore.save(originalSettings)
        }
    }

    @Test
    fun preferredPointerCommitFailureRejectsLaneNeutralAutoStartWithoutRuntimeCallbackOrPendingClear() {
        val application = RuntimeEnvironment.getApplication()
        val durableSettingsStore = AppSettingsStore(application)
        val preferences = application.getSharedPreferences(
            "local_model_handoff_preferred_pointer_failure",
            0,
        )
        assertTrue(preferences.edit().clear().commit())
        val durableDownloadStore = LocalModelDownloadStore(
            preferences = preferences,
            commitEditor = { editor -> editor.commit() },
        )
        val record = completedLaneNeutralRecord("lane-neutral-pointer-commit-failure")
        durableDownloadStore.saveDownloads(listOf(record))
        assertTrue(durableDownloadStore.setPendingAutoStartRecordId(record.id))
        val viewModel = LocalModelDownloadsViewModel(
            application = application,
            huggingFaceTokenLoader = { "" },
            settingsStore = durableSettingsStore,
            downloadStore = LocalModelDownloadStore(
                preferences = preferences,
                commitEditor = { false },
            ),
        )

        val promotion = viewModel.promoteDownloadedModelForAutoStart(record.id)
        var runtimeCallbackCount = 0
        val promotionAccepted = dispatchAcceptedLocalModelRuntimeHandoff(promotion) { _, _ ->
            runtimeCallbackCount += 1
        }
        if (promotionAccepted) {
            viewModel.completePendingAutoStartHandoff(record.id, accepted = true)
        }

        assertEquals(LocalModelRuntimeHandoffResult.Rejected, promotion)
        assertFalse(promotionAccepted)
        assertEquals(0, runtimeCallbackCount)
        assertEquals(record.id, durableDownloadStore.pendingAutoStartRecordId())
        assertEquals("", durableDownloadStore.preferredDownloadId())
    }

    @Test
    fun laneNeutralPreferredHandoffIsAcceptedWithoutConflatingNullLaneWithFailure() {
        val application = RuntimeEnvironment.getApplication()
        val preferences = application.getSharedPreferences(
            "local_model_handoff_lane_neutral_success",
            0,
        )
        assertTrue(preferences.edit().clear().commit())
        val downloadStore = LocalModelDownloadStore(
            preferences = preferences,
            commitEditor = { editor -> editor.commit() },
        )
        val modelFile = File(application.cacheDir, "lane-neutral-accepted.gguf").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val record = completedLaneNeutralRecord("lane-neutral-accepted").copy(
            destinationPath = modelFile.absolutePath,
            totalBytes = modelFile.length(),
            downloadedBytes = modelFile.length(),
        )
        downloadStore.saveDownloads(listOf(record))
        val viewModel = LocalModelDownloadsViewModel(
            application = application,
            huggingFaceTokenLoader = { "" },
            downloadStore = downloadStore,
        )

        val result = viewModel.setPreferredDownload(record.id)
        var callbackLane: String? = "not-called"
        val accepted = dispatchAcceptedLocalModelRuntimeHandoff(result) { requiredLane, _ ->
            callbackLane = requiredLane
        }

        assertTrue(result is LocalModelRuntimeHandoffResult.Accepted)
        assertTrue(accepted)
        assertEquals(null, callbackLane)
        assertEquals(record.id, downloadStore.preferredDownloadId())
        modelFile.delete()
    }

    @Test
    fun staleManualPreferredHandoffDoesNotInvokeRuntimeCallback() {
        val application = RuntimeEnvironment.getApplication()
        val preferences = application.getSharedPreferences(
            "local_model_handoff_stale_record",
            0,
        )
        assertTrue(preferences.edit().clear().commit())
        val downloadStore = LocalModelDownloadStore(
            preferences = preferences,
            commitEditor = { editor -> editor.commit() },
        )
        val record = completedLaneNeutralRecord("stale-manual-record")
        downloadStore.saveDownloads(listOf(record))
        val viewModel = LocalModelDownloadsViewModel(
            application = application,
            huggingFaceTokenLoader = { "" },
            downloadStore = downloadStore,
        )
        downloadStore.removeDownload(record.id)

        val result = viewModel.setPreferredDownload(record.id)
        var runtimeCallbackCount = 0
        val accepted = dispatchAcceptedLocalModelRuntimeHandoff(result) { _, _ ->
            runtimeCallbackCount += 1
        }

        assertEquals(LocalModelRuntimeHandoffResult.Rejected, result)
        assertFalse(accepted)
        assertEquals(0, runtimeCallbackCount)
    }

    @Test
    fun olderDownloadCompletionCannotReplaceNewerPendingAutoStartOwner() {
        val application: android.app.Application = RuntimeEnvironment.getApplication()
        val preferences = application.getSharedPreferences(
            "local_model_pending_owner_epoch",
            0,
        )
        assertTrue(preferences.edit().clear().commit())
        val downloadStore = LocalModelDownloadStore(
            preferences = preferences,
            commitEditor = { editor -> editor.commit() },
        )
        val older = completedLaneNeutralRecord("older-download-completes-last")
        val newer = completedLaneNeutralRecord("newer-download-completes-first")
        downloadStore.saveDownloads(listOf(older, newer))

        val olderClickGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        val newerClickGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        assertTrue(
            publishPendingAutoStartForGeneration(downloadStore, newer.id, newerClickGeneration),
        )
        assertThrows(RuntimeSelectionSupersededException::class.java) {
            publishPendingAutoStartForGeneration(downloadStore, older.id, olderClickGeneration)
        }

        assertEquals(newer.id, downloadStore.pendingAutoStartRecordId())
    }

    @Test
    fun completedImportCannotClearNewerRepublishedSameRecordPendingIntent() {
        val application: android.app.Application = RuntimeEnvironment.getApplication()
        val preferences = application.getSharedPreferences(
            "local_model_import_pending_intent_aba",
            0,
        )
        assertTrue(preferences.edit().clear().commit())
        val downloadStore = LocalModelDownloadStore(
            preferences = preferences,
            commitEditor = { editor -> editor.commit() },
        )
        val pending = completedLaneNeutralRecord("same-record-before-and-after-import")
        val imported = completedLaneNeutralRecord("completed-custom-import")
        downloadStore.saveDownloads(listOf(pending))

        val originalGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        assertTrue(
            publishPendingAutoStartForGeneration(
                downloadStore,
                pending.id,
                originalGeneration,
            ),
        )
        val originalIntent = requireNotNull(downloadStore.pendingAutoStartIntent())
        val importStarted = CountDownLatch(1)
        val releaseImport = CountDownLatch(1)
        val importerReturned = CountDownLatch(1)
        val viewModel = LocalModelDownloadsViewModel(
            application = application,
            huggingFaceTokenLoader = { "" },
            downloadStore = downloadStore,
            localModelFileImporter = { _, store, _ ->
                importStarted.countDown()
                check(releaseImport.await(5, TimeUnit.SECONDS))
                store.upsertDownload(imported)
                importerReturned.countDown()
                imported
            },
        )

        viewModel.importLocalModelFile(Uri.parse("content://hermes.test/import.gguf"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Import did not enter the controlled copy", importStarted.await(5, TimeUnit.SECONDS))

        val replacementGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        assertTrue(
            publishPendingAutoStartForGeneration(
                downloadStore,
                pending.id,
                replacementGeneration,
            ),
        )
        val replacementIntent = requireNotNull(downloadStore.pendingAutoStartIntent())
        assertEquals(originalIntent.recordId, replacementIntent.recordId)
        assertTrue("A replacement intent must receive a new token", originalIntent.token != replacementIntent.token)

        releaseImport.countDown()
        assertTrue("Controlled importer did not return", importerReturned.await(5, TimeUnit.SECONDS))
        awaitUiState(viewModel) { state -> state.inspectionStatus.startsWith("Imported ") }

        assertEquals(pending.id, downloadStore.pendingAutoStartRecordId())
        assertEquals(replacementIntent, downloadStore.pendingAutoStartIntent())
    }

    @Test
    fun newerDownloadClickCancelsOldPendingBeforeEnqueueGapAndLateCompletionCannotTakeAuthority() {
        val application: android.app.Application = RuntimeEnvironment.getApplication()
        val preferences = application.getSharedPreferences(
            "local_model_pending_click_admission_gap",
            0,
        )
        assertTrue(preferences.edit().clear().commit())
        val downloadStore = LocalModelDownloadStore(
            preferences = preferences,
            commitEditor = { editor -> editor.commit() },
        )
        val older = completedLaneNeutralRecord("older-pending-before-new-enqueue")
        val newer = completedLaneNeutralRecord("newer-click-awaiting-enqueue")
        downloadStore.saveDownloads(listOf(older, newer))
        assertTrue(downloadStore.setPendingAutoStartRecordId(older.id))
        val newerClickGeneration = LocalModelRuntimeSelectionAuthority.beginAction()

        assertTrue(
            clearPendingAutoStartForGeneration(downloadStore, newerClickGeneration),
        )
        assertEquals("", downloadStore.pendingAutoStartRecordId())

        val viewModel = LocalModelDownloadsViewModel(
            application = application,
            huggingFaceTokenLoader = { "" },
            downloadStore = downloadStore,
        )
        assertEquals(
            LocalModelRuntimeHandoffResult.Rejected,
            viewModel.promoteDownloadedModelForAutoStart(older.id),
        )
        assertTrue(LocalModelRuntimeSelectionAuthority.isCurrent(newerClickGeneration))
        assertTrue(
            publishPendingAutoStartForGeneration(
                downloadStore,
                newer.id,
                newerClickGeneration,
            ),
        )
        assertEquals(newer.id, downloadStore.pendingAutoStartRecordId())
    }

    @Test
    fun manualPreferredHandoffCancelsDifferentPendingOwnerAndLateCompletionCannotPromoteIt() {
        val application: android.app.Application = RuntimeEnvironment.getApplication()
        val settingsStore = AppSettingsStore(application)
        val originalSettings = settingsStore.load()
        val preferences = application.getSharedPreferences(
            "local_model_pending_owner_manual_handoff",
            0,
        )
        assertTrue(preferences.edit().clear().commit())
        val downloadStore = LocalModelDownloadStore(
            preferences = preferences,
            commitEditor = { editor -> editor.commit() },
        )
        val olderModelFile = File.createTempFile(
            "older-pending-nanbeige-",
            ".gguf",
            application.cacheDir,
        ).apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val newerModelFile = File.createTempFile(
            "newer-manual-model-",
            ".gguf",
            application.cacheDir,
        ).apply {
            writeBytes(byteArrayOf(5, 6, 7, 8))
        }
        val olderPending = completedNanbeigeRecord("older-pending-nanbeige").copy(
            destinationPath = olderModelFile.absolutePath,
        )
        val newerManual = completedLaneNeutralRecord("newer-manual-model").copy(
            destinationPath = newerModelFile.absolutePath,
        )

        try {
            settingsStore.save(
                originalSettings.copy(
                    onDeviceBackend = BackendKind.NONE.persistedValue,
                    llamaCppRuntimeLane = "stable",
                ),
            )
            downloadStore.saveDownloads(listOf(olderPending, newerManual))
            assertTrue(downloadStore.setPendingAutoStartRecordId(olderPending.id))
            val viewModel = LocalModelDownloadsViewModel(
                application = application,
                huggingFaceTokenLoader = { "" },
                settingsStore = settingsStore,
                downloadStore = downloadStore,
            )

            assertTrue(
                viewModel.setPreferredDownload(newerManual.id) is
                    LocalModelRuntimeHandoffResult.Accepted,
            )
            assertEquals("", downloadStore.pendingAutoStartRecordId())
            assertEquals(newerManual.id, downloadStore.preferredDownloadId())

            assertEquals(
                LocalModelRuntimeHandoffResult.Rejected,
                viewModel.promoteDownloadedModelForAutoStart(olderPending.id),
            )
            assertEquals("", downloadStore.pendingAutoStartRecordId())
            assertEquals(newerManual.id, downloadStore.preferredDownloadId())
            assertEquals(BackendKind.LLAMA_CPP.persistedValue, settingsStore.load().onDeviceBackend)
            assertEquals("stable", settingsStore.load().llamaCppRuntimeLane)
        } finally {
            try {
                settingsStore.save(originalSettings)
            } finally {
                olderModelFile.delete()
                newerModelFile.delete()
            }
        }
    }

    private fun acceptedLane(result: LocalModelRuntimeHandoffResult): String? {
        return (result as LocalModelRuntimeHandoffResult.Accepted).requiredLlamaCppRuntimeLane
    }

    private fun awaitUiState(
        viewModel: LocalModelDownloadsViewModel,
        predicate: (LocalModelDownloadsUiState) -> Boolean,
    ) {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate(viewModel.uiState.value) && System.nanoTime() < deadlineNanos) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(
            "Timed out waiting for LocalModelDownloadsViewModel state: ${viewModel.uiState.value}",
            predicate(viewModel.uiState.value),
        )
    }

    private fun completedNanbeigeRecord(id: String): LocalModelDownloadRecord {
        val artifact = VerifiedLocalModelArtifacts.releaseMatrix.first {
            it.modelId == "nanbeige4.2-3b-q4-k-m"
        }
        return LocalModelDownloadRecord(
            id = id,
            title = artifact.modelId,
            sourceUrl =
                "https://huggingface.co/${artifact.repoId}/resolve/${artifact.revision}/${artifact.fileName}",
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
    }

    private fun completedLaneNeutralRecord(id: String): LocalModelDownloadRecord {
        return LocalModelDownloadRecord(
            id = id,
            title = "lane-neutral.gguf",
            sourceUrl = "https://example.invalid/lane-neutral.gguf",
            repoOrUrl = "example/lane-neutral",
            filePath = "lane-neutral.gguf",
            revision = "main",
            runtimeFlavor = "GGUF",
            destinationFileName = "lane-neutral.gguf",
            destinationPath = "/models/lane-neutral.gguf",
            downloadManagerId = -1L,
            totalBytes = 1024L,
            downloadedBytes = 1024L,
            status = "completed",
        )
    }
}
