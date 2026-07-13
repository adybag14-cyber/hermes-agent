package com.mobilefork.hermesagent.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedLocalModelsTest {
    @Test
    fun requestedSmallModelCompatibilityTargetsStayInCatalog() {
        val presets = LocalModelDownloadsViewModel.recommendedModelPresets.associateBy { it.id }

        assertEquals(
            "Qwen3.5-0.8B-Q4_K_M.gguf",
            presets.getValue("qwen35-08b-q4km-gguf").filePath,
        )
        assertEquals(
            "MiniCPM5-1B-Claude-Opus-Fable5-Thinking-Q4_K_M.gguf",
            presets.getValue("minicpm5-1b-fable5-q4km-gguf").filePath,
        )
        assertEquals(
            "MiniCPM5-1B-web.litertlm",
            presets.getValue("minicpm5-1b-web-litert-lm").filePath,
        )
        assertEquals(
            "VibeThinker-3B.litertlm",
            presets.getValue("vibethinker-3b-litert-lm").filePath,
        )
        assertTrue(presets.values.count { it.runtimeFlavor == "GGUF" } >= 2)
        assertTrue(presets.values.count { it.runtimeFlavor == "LiteRT-LM" } >= 4)
    }
}
