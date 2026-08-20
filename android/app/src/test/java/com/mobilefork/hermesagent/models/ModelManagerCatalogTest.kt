package com.mobilefork.hermesagent.models

import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.LocalBackendStatus
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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
            assertEquals(artifact.expectedBytes, entry.approximateSizeBytes)
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
    fun automaticPreferredRepairSelectsOnlyExactReleaseCertifiedArtifacts() {
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
            certifiedRecord.id,
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
                        fileName = entry.displayName,
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
