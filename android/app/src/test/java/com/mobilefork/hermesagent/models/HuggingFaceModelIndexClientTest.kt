package com.mobilefork.hermesagent.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HuggingFaceModelIndexClientTest {
    @Test
    fun releaseCertifiedRuntimeComesFromVerifiedArtifactNotSignedRowText() {
        val liteRt = VerifiedLocalModelArtifacts.releaseMatrix.first { it.runtime == "litert-lm" }
        val gguf = VerifiedLocalModelArtifacts.releaseMatrix.first { it.runtime == "llama.cpp" }

        assertEquals("LiteRT-LM", HuggingFaceModelIndexClient.runtimeFlavorForVerifiedArtifact(liteRt))
        assertEquals("GGUF", HuggingFaceModelIndexClient.runtimeFlavorForVerifiedArtifact(gguf))
    }

    @Test
    fun verifiedArtifactUsesItsImmutableReleaseRevision() {
        val artifact = VerifiedLocalModelArtifacts.releaseMatrix.first { it.runtime == "llama.cpp" }

        val binding = requireNotNull(HuggingFaceModelIndexClient.revisionBinding(
            repoOrUrl = artifact.repoId,
            filePath = artifact.fileName,
            currentSha = "0".repeat(40),
            advertisedRevision = "main",
        ))

        assertEquals(artifact.revision, binding.revision)
        assertTrue(binding.releaseCertified)
        assertTrue(binding.immutableRevision)
    }

    @Test
    fun unverifiedCatalogRowUsesCurrentCommitWithoutClaimingReleaseCertification() {
        val currentSha = "1".repeat(40)

        val binding = requireNotNull(HuggingFaceModelIndexClient.revisionBinding(
            repoOrUrl = "litert-community/experimental-model",
            filePath = "model.litertlm",
            currentSha = currentSha,
            advertisedRevision = "main",
        ))

        assertEquals(currentSha, binding.revision)
        assertFalse(binding.releaseCertified)
        assertTrue(binding.immutableRevision)
    }

    @Test
    fun mutableCatalogRowWithoutAValidCurrentCommitIsRejected() {
        assertEquals(
            null,
            HuggingFaceModelIndexClient.revisionBinding(
                repoOrUrl = "litert-community/stale-model",
                filePath = "missing.litertlm",
                currentSha = "not-a-commit",
                advertisedRevision = "main",
            ),
        )
    }

    @Test
    fun staleTwelveBRowIsNeitherPrioritizedNorAutoSelected() {
        val staleTwelveB = detected(
            id = "stale-12b",
            title = "Gemma 4 12B",
            repo = "litert-community/gemma-4-12B-it-litert-lm",
            file = "gemma-4-12B-it-gpu.litertlm",
            runtime = "LiteRT-LM",
        )
        val experimentalE2B = detected(
            id = "experimental-e2b",
            title = "Gemma 4 E2B",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            file = "gemma-4-E2B-it.litertlm",
            runtime = "LiteRT-LM",
        )
        val certifiedQwen = detected(
            id = "certified-qwen",
            title = "Qwen3.5 0.8B",
            repo = "unsloth/Qwen3.5-0.8B-GGUF",
            file = "Qwen3.5-0.8B-Q4_K_M.gguf",
            runtime = "GGUF",
            releaseCertified = true,
        )

        val ordered = HuggingFaceModelIndexClient.prioritizeDetectedModels(
            listOf(staleTwelveB, experimentalE2B, certifiedQwen)
        )

        assertEquals(listOf("certified-qwen", "experimental-e2b", "stale-12b"), ordered.map { it.id })
        assertEquals(
            "certified-qwen",
            HuggingFaceModelIndexClient.preferredDetectedModelId(ordered, currentSelectionId = ""),
        )
        assertEquals(
            "",
            HuggingFaceModelIndexClient.preferredDetectedModelId(
                models = listOf(staleTwelveB, experimentalE2B),
                currentSelectionId = "",
            ),
        )
    }

    @Test
    fun experimentalCatalogSelectionCannotBecomeAQuickStartDefault() {
        val experimental = detected(
            id = "user-selected",
            title = "Experimental model",
            repo = "example/model",
            file = "model.litertlm",
            runtime = "LiteRT-LM",
        )

        assertEquals(
            "",
            HuggingFaceModelIndexClient.preferredDetectedModelId(
                models = listOf(experimental),
                currentSelectionId = "user-selected",
            ),
        )
    }

    private fun detected(
        id: String,
        title: String,
        repo: String,
        file: String,
        runtime: String,
        releaseCertified: Boolean = false,
    ): DetectedHfModel {
        return DetectedHfModel(
            id = id,
            title = title,
            summary = "",
            repoOrUrl = repo,
            filePath = file,
            revision = "2".repeat(40),
            runtimeFlavor = runtime,
            sourceLabel = "test",
            releaseCertified = releaseCertified,
            immutableRevision = true,
            expectedBytes = 532_517_120L,
        )
    }

    @Test
    fun mobileQuickCatalogRequiresReleaseCertificationImmutableRevisionAndKnownBoundedBytes() {
        val certified = detected(
            id = "certified",
            title = "Certified",
            repo = "example/certified",
            file = "model.gguf",
            runtime = "GGUF",
            releaseCertified = true,
        )
        val experimental = detected(
            id = "experimental",
            title = "Experimental",
            repo = "example/experimental",
            file = "model.gguf",
            runtime = "GGUF",
        )
        val unknownSize = certified.copy(id = "unknown", expectedBytes = null)
        val oversized = certified.copy(id = "oversized", expectedBytes = 5L * 1024L * 1024L * 1024L + 1L)
        val mutable = certified.copy(id = "mutable", immutableRevision = false)

        assertEquals(
            listOf("certified"),
            HuggingFaceModelIndexClient.mobileQuickCatalogModels(
                listOf(certified, experimental, unknownSize, oversized, mutable),
            ).map { it.id },
        )
    }
}
