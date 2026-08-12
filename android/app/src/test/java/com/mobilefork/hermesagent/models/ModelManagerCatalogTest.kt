package com.mobilefork.hermesagent.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelManagerCatalogTest {
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
}
