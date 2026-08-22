package com.mobilefork.hermesagent.models

import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.LocalBackendStatus
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsPersistenceException
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class ModelManagerCatalogTest {
    @Test
    fun readyStateRequiresAStartedBackendAndRealCompletionProof() {
        assertFalse(
            isModelRuntimeReady(
                LocalBackendStatus(
                    backendKind = BackendKind.LITERT_LM,
                    started = true,
                    completionVerified = false,
                ),
            ),
        )
        assertTrue(
            isModelRuntimeReady(
                LocalBackendStatus(
                    backendKind = BackendKind.LITERT_LM,
                    started = true,
                    completionVerified = true,
                ),
            ),
        )
    }

    @Test
    fun verifiedArtifactsStayPinnedAcrossTheModelManagerCatalog() {
        val catalogByRepo = ModelManagerViewModel.buildDefaultCatalog()
            .associateBy { it.repoId.lowercase() }

        VerifiedLocalModelArtifacts.releaseMatrix.forEach { artifact ->
            val entry = requireNotNull(catalogByRepo[artifact.repoId.lowercase()]) {
                "Missing catalog entry for ${artifact.repoId}"
            }
            assertEquals(artifact.revision, entry.revision)
            assertEquals(artifact.fileName, entry.filePath)
            assertEquals(artifact.expectedBytes, entry.approximateSizeBytes)
            assertEquals(artifact.sha256, entry.sha256)
            assertTrue(entry.isMobileRecommended)
            assertTrue(
                if (artifact.runtime == "litert-lm") {
                    ModelRuntimeBackend.LITERT_LM in entry.supportedBackends
                } else {
                    ModelRuntimeBackend.LLAMA_CPP in entry.supportedBackends
                },
            )
        }
    }

    @Test
    fun nanbeigeCatalogEntryRequiresTurboQuantAndOtherCertifiedEntriesStayLaneNeutral() {
        val certifiedEntries = ModelManagerViewModel.buildDefaultCatalog().filter { it.isMobileRecommended }
        val nanbeige = certifiedEntries.single { it.repoId == "Tdamre/Nanbeige4.2-3B-GGUF" }

        assertEquals("Nanbeige4.2-3B-Q4_K_M.gguf", nanbeige.filePath)
        assertEquals("turboquant", nanbeige.requiredLlamaCppRuntimeLane)
        assertEquals("Apache-2.0", nanbeige.license)
        assertTrue(
            certifiedEntries
                .filterNot { it.id == nanbeige.id }
                .all { it.requiredLlamaCppRuntimeLane == null },
        )
    }

    @Test
    fun uncertifiedGemma4ArtifactsAreDiscoverableButNotMobileRecommended() {
        val gemma4 = ModelManagerViewModel.buildDefaultCatalog()
            .filter { it.id.startsWith("gemma-4-") }

        assertTrue(gemma4.isNotEmpty())
        assertTrue(gemma4.all { it.revision != "main" })
        assertTrue(gemma4.all { !it.isMobileRecommended })
        assertTrue(gemma4.all { !it.supportsImageInput && !it.supportsAudioInput })
        assertTrue(gemma4.all { "experimental" in it.tags && "text-only" in it.tags })
        assertTrue(gemma4.all { entry ->
            listOf("mtp", "speculative-decoding", "multimodal", "tool-use").none { it in entry.tags }
        })
        assertFalse(gemma4.any { entry ->
            VerifiedLocalModelArtifacts.releaseMatrix.any { artifact ->
                artifact.repoId.equals(entry.repoId, ignoreCase = true)
            }
        })
    }

    @Test
    fun mobileRecommendedFilterContainsOnlyReleaseMatrixArtifacts() {
        val recommended = ModelManagerViewModel.buildDefaultCatalog().filter { it.isMobileRecommended }

        assertEquals(VerifiedLocalModelArtifacts.releaseMatrix.size, recommended.size)
        recommended.forEach { entry ->
            assertTrue(
                VerifiedLocalModelArtifacts.releaseMatrix.any { artifact ->
                    artifact.repoId.equals(entry.repoId, ignoreCase = true) &&
                        artifact.revision == entry.revision &&
                        artifact.expectedBytes == entry.approximateSizeBytes
                }
            )
        }
    }

    @Test
    fun preferredRepairPreservesOnlyAnExistingValidExplicitPointer() {
        val certified = VerifiedLocalModelArtifacts.releaseMatrix.first()
        val certifiedRecord = completedRecord(
            id = "certified",
            repo = certified.repoId,
            revision = certified.revision,
            fileName = certified.fileName,
            runtime = if (certified.runtime == "litert-lm") "LiteRT-LM" else "GGUF",
            bytes = certified.expectedBytes,
        )
        val experimentalE4b = completedRecord(
            id = "experimental-e4b",
            repo = "litert-community/gemma-4-E4B-it-litert-lm",
            revision = "9695417f248178c63a9f318c6e0c56cb917cb837",
            fileName = "gemma-4-E4B-it.litertlm",
            runtime = "LiteRT-LM",
            bytes = 3_654_467_584L,
        )
        val observedBytes = { record: LocalModelDownloadRecord -> record.totalBytes }

        assertEquals(
            "",
            HermesModelDownloadManager.repairedPreferredDownloadId(
                preferredId = "",
                records = listOf(experimentalE4b),
                observedFileBytes = observedBytes,
            ),
        )
        assertEquals(
            "",
            HermesModelDownloadManager.repairedPreferredDownloadId(
                preferredId = "missing",
                records = listOf(experimentalE4b, certifiedRecord),
                observedFileBytes = observedBytes,
            ),
        )
        assertEquals(
            experimentalE4b.id,
            HermesModelDownloadManager.repairedPreferredDownloadId(
                preferredId = experimentalE4b.id,
                records = listOf(experimentalE4b, certifiedRecord),
                observedFileBytes = observedBytes,
            ),
        )
        assertEquals(
            "",
            HermesModelDownloadManager.repairedPreferredDownloadId(
                preferredId = "",
                records = listOf(certifiedRecord.copy(revision = "main")),
                observedFileBytes = observedBytes,
            ),
        )
    }

    @Test
    fun preferredModelPersistsTheBackendUsedByCentralizedRuntimeRestart() {
        val application = RuntimeEnvironment.getApplication()
        val settingsStore = AppSettingsStore(application)
        val downloadStore = LocalModelDownloadStore(application)
        val originalSettings = settingsStore.load()
        val originalDownloads = downloadStore.loadDownloads()
        val originalPreferredId = downloadStore.preferredDownloadId()

        val entries = ModelManagerViewModel.buildDefaultCatalog().let { catalog ->
            listOf(
                requireNotNull(catalog.firstOrNull {
                    ModelRuntimeBackend.LITERT_LM in it.supportedBackends
                }),
                requireNotNull(catalog.firstOrNull {
                    ModelRuntimeBackend.LLAMA_CPP in it.supportedBackends
                }),
            )
        }
        try {
            settingsStore.save(AppSettings(onDeviceBackend = BackendKind.NONE.persistedValue))
            downloadStore.saveDownloads(
                entries.map { entry ->
                    completedRecord(
                        id = "download-${entry.id}",
                        repo = entry.repoId,
                        revision = entry.revision,
                        fileName = entry.filePath.ifBlank { entry.displayName },
                        runtime = if (ModelRuntimeBackend.LITERT_LM in entry.supportedBackends) {
                            "LiteRT-LM"
                        } else {
                            "GGUF"
                        },
                        bytes = entry.approximateSizeBytes,
                    )
                },
            )

            val viewModel = ModelManagerViewModel(application)
            entries.forEach { entry ->
                val expectedBackend = if (ModelRuntimeBackend.LITERT_LM in entry.supportedBackends) {
                    BackendKind.LITERT_LM
                } else {
                    BackendKind.LLAMA_CPP
                }

                viewModel.setPreferred(entry.id)

                assertEquals(expectedBackend.persistedValue, settingsStore.load().onDeviceBackend)
                assertEquals("download-${entry.id}", downloadStore.preferredDownloadId())
            }
        } finally {
            settingsStore.save(originalSettings)
            downloadStore.saveDownloads(originalDownloads)
            downloadStore.setPreferredDownloadId(originalPreferredId)
        }
    }

    @Test
    fun preferredNanbeigeAppliesTurboQuantWhileOtherCatalogEntriesPreserveTheLane() {
        val application = RuntimeEnvironment.getApplication()
        val settingsStore = AppSettingsStore(application)
        val downloadStore = LocalModelDownloadStore(application)
        val originalSettings = settingsStore.load()
        val originalDownloads = downloadStore.loadDownloads()
        val originalPreferredId = downloadStore.preferredDownloadId()
        val catalog = ModelManagerViewModel.buildDefaultCatalog()
        val nanbeige = catalog.single { it.repoId == "Tdamre/Nanbeige4.2-3B-GGUF" }
        val laneNeutral = catalog.single { it.repoId == "unsloth/Qwen3.5-0.8B-GGUF" }

        fun recordFor(entry: ModelCatalogEntry): LocalModelDownloadRecord = completedRecord(
            id = "download-${entry.id}",
            repo = entry.repoId,
            revision = entry.revision,
            fileName = entry.filePath,
            runtime = "GGUF",
            bytes = entry.approximateSizeBytes,
        )

        try {
            downloadStore.saveDownloads(listOf(recordFor(nanbeige), recordFor(laneNeutral)))
            settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "stable"))
            val viewModel = ModelManagerViewModel(application)

            viewModel.setPreferred(nanbeige.id)
            assertEquals("turboquant", settingsStore.load().llamaCppRuntimeLane)
            assertEquals("download-${nanbeige.id}", downloadStore.preferredDownloadId())

            settingsStore.save(settingsStore.load().copy(llamaCppRuntimeLane = "stable"))
            viewModel.setPreferred(laneNeutral.id)
            assertEquals("stable", settingsStore.load().llamaCppRuntimeLane)

            settingsStore.save(settingsStore.load().copy(llamaCppRuntimeLane = "turboquant"))
            viewModel.setPreferred(laneNeutral.id)
            assertEquals("turboquant", settingsStore.load().llamaCppRuntimeLane)
        } finally {
            settingsStore.save(originalSettings)
            downloadStore.saveDownloads(originalDownloads)
            downloadStore.setPreferredDownloadId(originalPreferredId)
        }
    }

    @Test
    fun settingsCommitFailureCannotPublishNanbeigePreferredWithTheStaleStableLane() {
        val application = RuntimeEnvironment.getApplication()
        val durableSettingsStore = AppSettingsStore(application)
        val downloadStore = LocalModelDownloadStore(application)
        val originalSettings = durableSettingsStore.load()
        val originalDownloads = downloadStore.loadDownloads()
        val originalPreferredId = downloadStore.preferredDownloadId()
        val catalog = ModelManagerViewModel.buildDefaultCatalog()
        val nanbeige = catalog.single { it.repoId == "Tdamre/Nanbeige4.2-3B-GGUF" }
        val laneNeutral = catalog.single { it.repoId == "unsloth/Qwen3.5-0.8B-GGUF" }
        val nanbeigeRecord = completedRecord(
            id = "download-${nanbeige.id}",
            repo = nanbeige.repoId,
            revision = nanbeige.revision,
            fileName = nanbeige.filePath,
            runtime = "GGUF",
            bytes = nanbeige.approximateSizeBytes,
        )
        val previousRecord = completedRecord(
            id = "download-${laneNeutral.id}",
            repo = laneNeutral.repoId,
            revision = laneNeutral.revision,
            fileName = laneNeutral.filePath,
            runtime = "GGUF",
            bytes = laneNeutral.approximateSizeBytes,
        )

        try {
            downloadStore.saveDownloads(listOf(previousRecord, nanbeigeRecord))
            assertTrue(downloadStore.setPreferredDownloadId(previousRecord.id))
            durableSettingsStore.save(
                originalSettings.copy(
                    onDeviceBackend = BackendKind.LLAMA_CPP.persistedValue,
                    llamaCppRuntimeLane = "stable",
                ),
            )
            val failingSettingsStore = AppSettingsStore.withCommitterForTest(application) { false }

            assertThrows(AppSettingsPersistenceException::class.java) {
                persistPreferredModelRuntimeSelection(
                    settingsStore = failingSettingsStore,
                    downloadStore = downloadStore,
                    recordId = nanbeigeRecord.id,
                    backendKind = BackendKind.LLAMA_CPP,
                    requiredLlamaCppRuntimeLane = nanbeige.requiredLlamaCppRuntimeLane,
                )
            }

            assertEquals("", downloadStore.preferredDownloadId())
            durableSettingsStore.invalidateCache()
            assertEquals("stable", durableSettingsStore.load().llamaCppRuntimeLane)
        } finally {
            durableSettingsStore.save(originalSettings)
            downloadStore.saveDownloads(originalDownloads)
            downloadStore.setPreferredDownloadId(originalPreferredId)
        }
    }

    @Test
    fun newerNanbeigeSelectionWaitsForAdmittedShortHandoffThenWinsAsOnePair() {
        val application: android.app.Application = RuntimeEnvironment.getApplication()
        val settingsStore = AppSettingsStore(application)
        val originalSettings = settingsStore.load()
        val preferences = application.getSharedPreferences(
            "model_manager_concurrent_runtime_selection",
            0,
        )
        assertTrue(preferences.edit().clear().commit())
        val downloadStore = LocalModelDownloadStore(
            preferences = preferences,
            commitEditor = { editor -> editor.commit() },
        )
        val oldLiteRt = completedRecord(
            id = "old-litert-selection",
            repo = "local/test-litert",
            revision = "main",
            fileName = "old-litert.bin",
            runtime = "LiteRT-LM",
            bytes = 1L,
        )
        val nanbeigeArtifact = VerifiedLocalModelArtifacts.releaseMatrix.single {
            it.modelId == "nanbeige4.2-3b-q4-k-m"
        }
        val newerNanbeige = completedRecord(
            id = "newer-nanbeige-selection",
            repo = nanbeigeArtifact.repoId,
            revision = nanbeigeArtifact.revision,
            fileName = nanbeigeArtifact.fileName,
            runtime = "GGUF",
            bytes = nanbeigeArtifact.expectedBytes,
        )
        val oldSettingsCommitted = CountDownLatch(1)
        val releaseOldHandoff = CountDownLatch(1)
        val newerAttempted = CountDownLatch(1)
        val oldFinished = CountDownLatch(1)
        val newerFinished = CountDownLatch(1)
        val oldFailure = AtomicReference<Throwable?>(null)
        val newerFailure = AtomicReference<Throwable?>(null)
        var oldThread: Thread? = null
        var newerThread: Thread? = null

        try {
            settingsStore.save(
                originalSettings.copy(
                    onDeviceBackend = BackendKind.NONE.persistedValue,
                    llamaCppRuntimeLane = "stable",
                ),
            )
            downloadStore.saveDownloads(listOf(oldLiteRt, newerNanbeige))
            val oldGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
            oldThread = Thread {
                try {
                    assertTrue(
                        persistPreferredModelRuntimeSelection(
                            settingsStore = settingsStore,
                            downloadStore = downloadStore,
                            recordId = oldLiteRt.id,
                            backendKind = BackendKind.LITERT_LM,
                            requiredLlamaCppRuntimeLane = null,
                            selectionGeneration = oldGeneration,
                            afterSettingsCommitted = {
                                oldSettingsCommitted.countDown()
                                check(releaseOldHandoff.await(5, TimeUnit.SECONDS))
                            },
                        ),
                    )
                } catch (error: Throwable) {
                    oldFailure.set(error)
                } finally {
                    oldFinished.countDown()
                }
            }.apply { isDaemon = true; start() }

            assertTrue(oldSettingsCommitted.await(5, TimeUnit.SECONDS))
            newerThread = Thread {
                try {
                    newerAttempted.countDown()
                    val newerGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
                    assertTrue(
                        persistPreferredModelRuntimeSelection(
                            settingsStore = settingsStore,
                            downloadStore = downloadStore,
                            recordId = newerNanbeige.id,
                            backendKind = BackendKind.LLAMA_CPP,
                            requiredLlamaCppRuntimeLane = "turboquant",
                            selectionGeneration = newerGeneration,
                        ),
                    )
                } catch (error: Throwable) {
                    newerFailure.set(error)
                } finally {
                    newerFinished.countDown()
                }
            }.apply { isDaemon = true; start() }

            assertTrue(newerAttempted.await(5, TimeUnit.SECONDS))
            assertFalse("newer handoff bypassed the process-wide authority monitor", newerFinished.await(150, TimeUnit.MILLISECONDS))
            releaseOldHandoff.countDown()
            assertTrue(oldFinished.await(5, TimeUnit.SECONDS))
            assertTrue(newerFinished.await(5, TimeUnit.SECONDS))
            oldFailure.get()?.let { throw AssertionError("admitted older handoff failed", it) }
            newerFailure.get()?.let { throw AssertionError("newer handoff failed", it) }

            settingsStore.invalidateCache()
            val finalSettings = settingsStore.load()
            assertEquals(BackendKind.LLAMA_CPP.persistedValue, finalSettings.onDeviceBackend)
            assertEquals("turboquant", finalSettings.llamaCppRuntimeLane)
            assertEquals(newerNanbeige.id, downloadStore.preferredDownloadId())
        } finally {
            releaseOldHandoff.countDown()
            oldThread?.join(1_000)
            newerThread?.join(1_000)
            settingsStore.save(originalSettings)
        }
    }

    private fun completedRecord(
        id: String,
        repo: String,
        revision: String,
        fileName: String,
        runtime: String,
        bytes: Long,
    ) = LocalModelDownloadRecord(
        id = id,
        title = fileName,
        sourceUrl = "https://huggingface.co/$repo/resolve/$revision/$fileName",
        repoOrUrl = repo,
        filePath = fileName,
        revision = revision,
        runtimeFlavor = runtime,
        destinationFileName = fileName,
        destinationPath = "/models/$fileName",
        downloadManagerId = -1L,
        totalBytes = bytes,
        downloadedBytes = bytes,
        status = "completed",
    )
}
