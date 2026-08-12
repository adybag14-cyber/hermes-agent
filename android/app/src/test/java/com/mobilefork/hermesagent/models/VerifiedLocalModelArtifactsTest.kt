package com.mobilefork.hermesagent.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

class VerifiedLocalModelArtifactsTest {
    @Test
    fun releaseMatrixEntriesAreContentAddressedAndUnambiguous() {
        val matrix = VerifiedLocalModelArtifacts.releaseMatrix

        assertTrue(matrix.isNotEmpty())
        assertEquals(matrix.size, matrix.map { it.modelId }.distinct().size)
        assertEquals(
            matrix.size,
            matrix.map { "${it.repoId.lowercase()}/${it.fileName.lowercase()}" }.distinct().size,
        )
        matrix.forEach { artifact ->
            assertTrue(artifact.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(artifact.revision.matches(Regex("[0-9a-f]{40}")))
            assertTrue(artifact.expectedBytes > 0L)
            assertTrue(artifact.validationEvidence.isNotBlank())
            assertTrue(
                artifact.runtime == "litert-lm" && artifact.fileName.endsWith(".litertlm") ||
                    artifact.runtime == "llama.cpp" && artifact.fileName.endsWith(".gguf"),
            )
            assertEquals(artifact, VerifiedLocalModelArtifacts.require(artifact.repoId, artifact.fileName))
        }
    }

    @Test
    fun lookupNormalizesHuggingFaceUrlAndExactFilename() {
        val expected = VerifiedLocalModelArtifacts.releaseMatrix.first()
        val artifact = VerifiedLocalModelArtifacts.find(
            "https://huggingface.co/${expected.repoId}",
            "models/${expected.fileName}",
        )

        assertNotNull(artifact)
        assertEquals(expected, artifact)
    }

    @Test
    fun verifierRejectsRightNameWithWrongBytesBeforeHashing() {
        val artifact = VerifiedLocalModelArtifacts.releaseMatrix.first()
        val directory = createTempDirectory("hermes-artifact-test-").toFile()
        val file = File(directory, artifact.fileName).apply { writeBytes(ByteArray(64)) }

        val result = VerifiedLocalModelArtifacts.verify(file, artifact)

        assertFalse(result.valid)
        assertEquals(64L, result.actualBytes)
        assertTrue(result.detail, result.detail.contains("Byte-size mismatch"))
    }

    @Test
    fun launchVerificationRejectsSameSizeReplacementWithRestoredTimestamp() {
        val directory = createTempDirectory("hermes-artifact-mutation-").toFile()
        val file = File(directory, "fixture.gguf").apply { writeText("good") }
        val goodHash = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val artifact = VerifiedLocalModelArtifacts.Artifact(
            modelId = "fixture",
            repoId = "local/fixture",
            revision = "a".repeat(40),
            fileName = file.name,
            runtime = "llama.cpp",
            expectedBytes = file.length(),
            sha256 = goodHash,
            validationEvidence = "unit fixture",
            remoteManifestMatches = false,
        )

        val originalModified = file.lastModified()
        assertTrue(VerifiedLocalModelArtifacts.verifyCached(file, artifact).valid)
        file.writeText("evil")
        assertTrue(file.setLastModified(originalModified))

        val replacement = VerifiedLocalModelArtifacts.verifyCached(file, artifact)
        assertFalse(replacement.valid)
        assertTrue(replacement.detail.contains("SHA-256 mismatch"))
    }
}
