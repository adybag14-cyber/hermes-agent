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
            assertEquals(artifact.expectedBytes, preset.expectedBytes)
            assertEquals(artifact.sha256, preset.sha256)
            assertEquals(
                if (artifact.runtime == "litert-lm") "LiteRT-LM" else "GGUF",
                preset.runtimeFlavor,
            )
            assertTrue(preset.revision != "main")
        }
    }

    @Test
    fun exactNanbeigePresetRequiresTurboQuantAndAllOtherPresetsAreLaneNeutral() {
        val nanbeige = LocalModelDownloadsViewModel.recommendedModelPresets.single {
            it.id == "nanbeige4.2-3b-q4-k-m"
        }
        val artifact = VerifiedLocalModelArtifacts.require(
            "Tdamre/Nanbeige4.2-3B-GGUF",
            "Nanbeige4.2-3B-Q4_K_M.gguf",
        )

        assertEquals(artifact.repoId, nanbeige.repoOrUrl)
        assertEquals(artifact.fileName, nanbeige.filePath)
        assertEquals("128d8e87d69f9c1a30c37e40530c69deda96475d", nanbeige.revision)
        assertEquals(2_574_807_840L, nanbeige.expectedBytes)
        assertEquals(
            "99c7bfb88907f7eee0a04c4314f1c46bca391819478d8cb90b3e164f09576489",
            nanbeige.sha256,
        )
        assertEquals("turboquant", nanbeige.requiredLlamaCppRuntimeLane)
        assertTrue(
            LocalModelDownloadsViewModel.recommendedModelPresets
                .filterNot { it.id == nanbeige.id }
                .all { it.requiredLlamaCppRuntimeLane == null },
        )
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
