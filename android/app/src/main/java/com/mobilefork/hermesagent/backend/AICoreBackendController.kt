package com.mobilefork.hermesagent.backend

import android.os.Build
import com.google.ai.edge.litertlm.Backend

/**
 * Honest capability boundary for Android AICore/NPU selection.
 *
 * LiteRT-LM in this app currently constructs only GPU and CPU delegates. Android API
 * level and device marketing names are not proof that a separately addressable NPU
 * backend exists, so Hermes keeps AICore unavailable until a real delegate is wired,
 * content-addressed, and exercised in the headed release matrix.
 */
object AICoreBackendController {
    const val AICORE_MIN_API = 35
    const val AICORE_PORT = 15436

    /** True only after Hermes implements and certifies a dedicated NPU delegate. */
    fun isAICoreAvailable(): Boolean = false

    /**
     * Get the list of backends to try in priority order.
     * The implemented LiteRT-LM delegate order is GPU then CPU.
     */
    fun getBackendPriority(): List<String> {
        return listOf("gpu", "cpu")
    }

    /**
     * Retained for settings compatibility; this is a normal LiteRT-LM configuration
     * and does not select or claim an NPU delegate.
     */
    fun createAICoreInferenceConfig(): LiteRtLmOpenAiProxy.InferenceConfig {
        return LiteRtLmOpenAiProxy.InferenceConfig(
            topK = 50,
            topP = 0.92f,
            temperature = 0.8f,
            maxTokens = -1,
            maxContextLength = -1,
            supportImage = false,
            supportAudio = false,
        )
    }

    /** Get human-readable description of available backends */
    fun getBackendDescription(): String {
        return "AICore/NPU is not implemented in Hermes yet; select LiteRT-LM for its verified GPU/CPU path"
    }

    /** Get the minimum API level required for AICore */
    fun getAICoreRequirements(): Map<String, String> {
        return mapOf(
            "minApiLevel" to AICORE_MIN_API.toString(),
            "currentApiLevel" to Build.VERSION.SDK_INT.toString(),
            "available" to isAICoreAvailable().toString(),
            "implemented" to "false",
            "description" to getBackendDescription(),
        )
    }

    /**
     * Never select AICore merely from API level or a device-name heuristic.
     */
    fun shouldUseAICore(): Boolean {
        return false
    }

    /** Get backend label for logging/status reporting */
    fun getBackendLabel(backend: Backend): String {
        return when (backend::class.simpleName) {
            "AICoreBackend" -> "aicore"
            "GpuBackend" -> "gpu"
            "CpuBackend" -> "cpu"
            else -> "unknown"
        }
    }
}
