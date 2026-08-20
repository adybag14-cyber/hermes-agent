package com.mobilefork.hermesagent.backend

import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceBackendPolicyTest {
    @Test
    fun unsafeLiteRtShutdownFailsBackendTransitionClosed() {
        val status = OnDeviceBackendManager.liteRtStopFailureStatus(
            targetBackend = BackendKind.LLAMA_CPP,
            failure = IllegalStateException("native generation is still unwinding"),
        )

        assertEquals(BackendKind.LITERT_LM, status.backendKind)
        assertFalse(status.started)
        assertTrue(status.requiresAppRestart)
        assertTrue(status.statusMessage.contains("did not stop safely"))
        assertTrue(status.statusMessage.contains("did not start llama.cpp"))
        assertTrue(status.statusMessage.contains("Force stop and reopen Hermes"))
    }

    @Test
    fun unsafeLlamaShutdownFailsBackendTransitionClosed() {
        val status = OnDeviceBackendManager.llamaStopFailureStatus(
            targetBackend = BackendKind.LITERT_LM,
            failure = IllegalStateException("process remained alive"),
        )

        assertEquals(BackendKind.LLAMA_CPP, status.backendKind)
        assertFalse(status.started)
        assertTrue(status.requiresAppRestart)
        assertTrue(status.statusMessage.contains("did not stop safely"))
        assertTrue(status.statusMessage.contains("did not start litert-lm"))
        assertTrue(status.statusMessage.contains("Force stop and reopen Hermes"))
    }

    @Test
    fun experimentalGemma4FilenameDoesNotInventImageAudioOrNpuCapabilities() {
        val record = LocalModelDownloadRecord(
            title = "Gemma 4 E4B multimodal MTP",
            sourceUrl = "https://example.invalid/gemma-4-E4B-it.litertlm",
            repoOrUrl = "litert-community/gemma-4-E4B-it-litert-lm",
            filePath = "gemma-4-E4B-it.litertlm",
            revision = "9695417f248178c63a9f318c6e0c56cb917cb837",
            runtimeFlavor = "LiteRT-LM",
            destinationFileName = "gemma-4-E4B-it.litertlm",
            destinationPath = "/tmp/gemma-4-E4B-it.litertlm",
            downloadManagerId = 1L,
            status = "completed",
        )

        val support = OnDeviceBackendManager.inferredInputSupport(record)

        assertFalse(support.image)
        assertFalse(support.audio)
        assertTrue(support.policy.contains("not release-certified"))
        assertFalse(AICoreBackendController.isAICoreAvailable())
        assertFalse(AICoreBackendController.shouldUseAICore())
        assertEquals(listOf("gpu", "cpu"), AICoreBackendController.getBackendPriority())
        assertTrue(AICoreBackendController.getBackendDescription().contains("not implemented"))
        assertEquals("auto", AppSettings.normalizeLocalModelAccelerator("npu"))
    }
}
