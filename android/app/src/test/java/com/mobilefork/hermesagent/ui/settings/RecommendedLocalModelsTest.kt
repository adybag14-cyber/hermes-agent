package com.mobilefork.hermesagent.ui.settings

import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.models.DetectedHfModel
import com.mobilefork.hermesagent.models.VerifiedLocalModelArtifacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedLocalModelsTest {
    @Test
    fun everyVerifiedArtifactIsExposedAsAnExactlyPinnedRecommendedPreset() {
        val presets = LocalModelDownloadsViewModel.recommendedModelPresets
        val presetsByArtifact = presets
            .mapNotNull { preset ->
                VerifiedLocalModelArtifacts.find(preset.repoOrUrl, preset.filePath)?.let { it to preset }
            }
            .associateBy { (artifact, _) -> artifact.modelId }

        assertEquals(VerifiedLocalModelArtifacts.releaseMatrix.size, presets.size)
        assertEquals(
            VerifiedLocalModelArtifacts.releaseMatrix.map { it.modelId }.toSet(),
            presetsByArtifact.keys,
        )
        presetsByArtifact.values.forEach { (artifact, preset) ->
            assertEquals(artifact.revision, preset.revision)
            assertEquals(artifact.fileName, preset.filePath.substringAfterLast('/'))
            assertEquals(
                if (artifact.runtime == "litert-lm") "LiteRT-LM" else "GGUF",
                preset.runtimeFlavor,
            )
            assertTrue(preset.revision != "main")
        }
    }

    @Test
    fun recommendedReuseRequiresExactRepoRevisionFileRuntimeAndBytes() {
        val preset = LocalModelDownloadsViewModel.recommendedModelPresets.first()
        val artifact = VerifiedLocalModelArtifacts.require(preset.repoOrUrl, preset.filePath)
        val exact = recordFor(artifact)

        assertTrue(LocalModelDownloadsViewModel.recordMatchesPreset(exact, preset))
        assertFalse(
            LocalModelDownloadsViewModel.recordMatchesPreset(
                exact.copy(
                    filePath = "different-model.gguf",
                    destinationFileName = "different-model.gguf",
                    destinationPath = "/models/different-model.gguf",
                ),
                preset,
            )
        )
        assertFalse(
            LocalModelDownloadsViewModel.recordMatchesPreset(
                exact.copy(revision = "0000000000000000000000000000000000000000"),
                preset,
            )
        )
        assertFalse(
            LocalModelDownloadsViewModel.recordMatchesPreset(
                exact.copy(runtimeFlavor = if (artifact.runtime == "llama.cpp") "LiteRT-LM" else "GGUF"),
                preset,
            )
        )
        assertFalse(
            LocalModelDownloadsViewModel.recordMatchesPreset(
                exact.copy(totalBytes = artifact.expectedBytes - 1L),
                preset,
            )
        )
    }

    @Test
    fun detectedReuseRejectsSameFileWithWrongRevisionOrRuntimeLabel() {
        val artifact = VerifiedLocalModelArtifacts.releaseMatrix.first()
        val exactRecord = recordFor(artifact)
        val exactModel = detectedModelFor(artifact)

        assertTrue(LocalModelDownloadsViewModel.recordMatchesDetectedModel(exactRecord, exactModel))
        assertFalse(
            LocalModelDownloadsViewModel.recordMatchesDetectedModel(
                exactRecord,
                exactModel.copy(revision = "1111111111111111111111111111111111111111"),
            )
        )
        assertFalse(
            LocalModelDownloadsViewModel.recordMatchesDetectedModel(
                exactRecord,
                exactModel.copy(runtimeFlavor = if (artifact.runtime == "llama.cpp") "LiteRT-LM" else "GGUF"),
            )
        )
        assertFalse(
            LocalModelDownloadsViewModel.recordMatchesDetectedModel(
                exactRecord.copy(repoOrUrl = "example.invalid/different-repo"),
                exactModel,
            )
        )
    }

    private fun recordFor(artifact: VerifiedLocalModelArtifacts.Artifact): LocalModelDownloadRecord {
        val runtimeFlavor = if (artifact.runtime == "litert-lm") "LiteRT-LM" else "GGUF"
        return LocalModelDownloadRecord(
            title = artifact.modelId,
            sourceUrl = "https://huggingface.co/${artifact.repoId}/resolve/${artifact.revision}/${artifact.fileName}",
            repoOrUrl = artifact.repoId,
            filePath = artifact.fileName,
            revision = artifact.revision,
            runtimeFlavor = runtimeFlavor,
            destinationFileName = artifact.fileName,
            destinationPath = "/models/${artifact.fileName}",
            downloadManagerId = 1L,
            totalBytes = artifact.expectedBytes,
            downloadedBytes = artifact.expectedBytes,
            status = "completed",
        )
    }

    private fun detectedModelFor(artifact: VerifiedLocalModelArtifacts.Artifact): DetectedHfModel {
        return DetectedHfModel(
            id = artifact.modelId,
            title = artifact.modelId,
            summary = "release certified",
            repoOrUrl = artifact.repoId,
            filePath = artifact.fileName,
            revision = artifact.revision,
            runtimeFlavor = if (artifact.runtime == "litert-lm") "LiteRT-LM" else "GGUF",
            sourceLabel = "test",
            expectedBytes = artifact.expectedBytes,
            releaseCertified = true,
            immutableRevision = true,
        )
    }
}
