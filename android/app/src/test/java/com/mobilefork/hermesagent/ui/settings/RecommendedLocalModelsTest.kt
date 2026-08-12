package com.mobilefork.hermesagent.ui.settings

import com.mobilefork.hermesagent.models.VerifiedLocalModelArtifacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedLocalModelsTest {
    @Test
    fun everyVerifiedArtifactIsExposedAsAnExactlyPinnedRecommendedPreset() {
        val presetsByArtifact = LocalModelDownloadsViewModel.recommendedModelPresets
            .mapNotNull { preset ->
                VerifiedLocalModelArtifacts.find(preset.repoOrUrl, preset.filePath)?.let { it to preset }
            }
            .associateBy { (artifact, _) -> artifact.modelId }

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
}
