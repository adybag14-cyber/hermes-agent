package com.mobilefork.hermesagent.device

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.min

/**
 * Shared memory guard and persistent startup breadcrumb for native local-model runtimes.
 *
 * LiteRT-LM and llama.cpp allocate most of their working set outside the managed heap. A
 * Java exception handler therefore cannot explain every failed model start: Android may kill
 * the process for memory pressure, or a native delegate may abort it. This record is written
 * before native initialization and only moves out of `initializing` after the runtime has
 * produced the requested proof. If the process disappears, the next diagnostics export still
 * contains the model, memory snapshot, accelerator request, and startup stage.
 */
object LocalModelRuntimeDiagnostics {
    internal data class MemorySnapshot(
        val totalBytes: Long,
        val availableBytes: Long,
        val thresholdBytes: Long,
        val lowMemory: Boolean,
        val memoryClassBytes: Long,
        val largeMemoryClassBytes: Long,
        val nativeHeapAllocatedBytes: Long,
    ) {
        val usableAvailableBytes: Long
            get() = (availableBytes - thresholdBytes).coerceAtLeast(0L)

        fun toJson(): JSONObject = JSONObject()
            .put("total_bytes", totalBytes)
            .put("available_bytes", availableBytes)
            .put("threshold_bytes", thresholdBytes)
            .put("usable_available_bytes", usableAvailableBytes)
            .put("low_memory", lowMemory)
            .put("memory_class_bytes", memoryClassBytes)
            .put("large_memory_class_bytes", largeMemoryClassBytes)
            .put("native_heap_allocated_bytes", nativeHeapAllocatedBytes)
    }

    internal data class PreflightDecision(
        val allowed: Boolean,
        val effectiveContextTokens: Int,
        val estimatedAdditionalBytes: Long,
        val level: String,
        val detail: String,
    )

    /**
     * Non-secret identity for the effective native-runtime launch configuration.
     *
     * This type deliberately has no raw argv field. Expert arguments may contain private
     * paths or backend-specific secrets, so diagnostics retain only their count and a
     * deterministic SHA-256 identity.
     */
    internal data class RuntimeLaunchBreadcrumb(
        val lane: String,
        val cacheTypeK: String,
        val cacheTypeV: String,
        val flashAttention: String,
        val launchFingerprintSha256: String,
        val additionalArgvCount: Int,
        val additionalArgvSha256: String,
    )

    internal fun captureMemory(context: Context): MemorySnapshot {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        if (manager != null) {
            runCatching { manager.getMemoryInfo(info) }
        }
        val mib = 1024L * 1024L
        return MemorySnapshot(
            totalBytes = info.totalMem.coerceAtLeast(0L),
            availableBytes = info.availMem.coerceAtLeast(0L),
            thresholdBytes = info.threshold.coerceAtLeast(0L),
            lowMemory = info.lowMemory,
            memoryClassBytes = (manager?.memoryClass?.toLong() ?: 0L).coerceAtLeast(0L) * mib,
            largeMemoryClassBytes = (manager?.largeMemoryClass?.toLong() ?: 0L).coerceAtLeast(0L) * mib,
            nativeHeapAllocatedBytes = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L),
        )
    }

    /**
     * Conservative admission control for a native model load.
     *
     * The thresholds intentionally use current usable memory as well as total RAM. In
     * particular, a >=6 GB LiteRT-LM bundle needs at least 2.5x its file size in total RAM and
     * enough live headroom for delegate setup. This prevents the reported 6.5 GB Gemma 4 12B
     * bundle from being started on a nominal 16 GB phone where LMKD can terminate Hermes.
     */
    internal fun evaluatePreflight(
        backend: String,
        modelBytes: Long,
        requestedContextTokens: Int,
        memory: MemorySnapshot,
        dangerouslySkipRamChecks: Boolean = false,
    ): PreflightDecision {
        if (modelBytes <= 0L) {
            return PreflightDecision(
                allowed = false,
                effectiveContextTokens = MIN_CONTEXT_TOKENS,
                estimatedAdditionalBytes = 0L,
                level = "blocked",
                detail = "Model size is unavailable; Hermes cannot safely start a native runtime for an empty or unreadable artifact.",
            )
        }

        val normalizedBackend = backend.trim().lowercase(Locale.US)
        val contextLimit = safeContextLimit(normalizedBackend, modelBytes, memory)
        val requested = requestedContextTokens.takeIf { it > 0 } ?: contextLimit
        val effectiveContext = min(requested, contextLimit).coerceAtLeast(MIN_CONTEXT_TOKENS)
        val contextReserve = contextReserveBytes(normalizedBackend, effectiveContext)
        val modelWorkingSet = when {
            normalizedBackend == "litert-lm" && modelBytes >= VERY_LARGE_MODEL_BYTES ->
                saturatingAdd(saturatingMultiply(modelBytes, 125L, 100L), 2_000_000_000L)
            normalizedBackend == "litert-lm" && modelBytes >= LARGE_MODEL_BYTES ->
                saturatingAdd(saturatingMultiply(modelBytes, 95L, 100L), 750_000_000L)
            normalizedBackend == "litert-lm" ->
                saturatingAdd(saturatingMultiply(modelBytes, 80L, 100L), 500_000_000L)
            modelBytes >= VERY_LARGE_MODEL_BYTES ->
                saturatingAdd(saturatingMultiply(modelBytes, 90L, 100L), 1_000_000_000L)
            modelBytes >= LARGE_MODEL_BYTES ->
                saturatingAdd(saturatingMultiply(modelBytes, 75L, 100L), 600_000_000L)
            else -> saturatingAdd(saturatingMultiply(modelBytes, 65L, 100L), 384_000_000L)
        }
        val estimatedAdditional = saturatingAdd(modelWorkingSet, contextReserve)
        val requiredTotalBytes = when {
            normalizedBackend == "litert-lm" && modelBytes >= VERY_LARGE_MODEL_BYTES ->
                saturatingMultiply(modelBytes, 250L, 100L)
            normalizedBackend == "litert-lm" && modelBytes >= LARGE_MODEL_BYTES ->
                saturatingMultiply(modelBytes, 150L, 100L)
            normalizedBackend == "litert-lm" -> saturatingMultiply(modelBytes, 125L, 100L)
            modelBytes >= VERY_LARGE_MODEL_BYTES -> saturatingMultiply(modelBytes, 180L, 100L)
            modelBytes >= LARGE_MODEL_BYTES -> saturatingMultiply(modelBytes, 135L, 100L)
            else -> modelBytes
        }

        val contextNote = if (requested > effectiveContext) {
            " Requested context $requested was clamped to $effectiveContext tokens."
        } else {
            " Context is limited to $effectiveContext tokens."
        }
        if (memory.lowMemory) {
            return ramAdmissionDecision(
                dangerouslySkipRamChecks = dangerouslySkipRamChecks,
                blocked = PreflightDecision(
                    allowed = false,
                    effectiveContextTokens = effectiveContext,
                    estimatedAdditionalBytes = estimatedAdditional,
                    level = "blocked",
                    detail = "Android reports active low-memory pressure; local model initialization was stopped before native allocation.$contextNote",
                ),
            )
        }
        if (memory.totalBytes > 0L && memory.totalBytes < requiredTotalBytes) {
            return ramAdmissionDecision(
                dangerouslySkipRamChecks = dangerouslySkipRamChecks,
                blocked = PreflightDecision(
                    allowed = false,
                    effectiveContextTokens = effectiveContext,
                    estimatedAdditionalBytes = estimatedAdditional,
                    level = "blocked",
                    detail = "This ${formatGb(modelBytes)} GB model needs about ${formatGb(requiredTotalBytes)} GB total RAM for $normalizedBackend, but Android reports ${formatGb(memory.totalBytes)} GB.$contextNote Choose a smaller artifact or a higher-memory device.",
                ),
            )
        }
        if (memory.availableBytes > 0L && memory.usableAvailableBytes < estimatedAdditional) {
            val severe = modelBytes >= LARGE_MODEL_BYTES ||
                memory.usableAvailableBytes < saturatingMultiply(estimatedAdditional, 65L, 100L)
            if (severe) {
                return ramAdmissionDecision(
                    dangerouslySkipRamChecks = dangerouslySkipRamChecks,
                    blocked = PreflightDecision(
                        allowed = false,
                        effectiveContextTokens = effectiveContext,
                        estimatedAdditionalBytes = estimatedAdditional,
                        level = "blocked",
                        detail = "Only ${formatGb(memory.usableAvailableBytes)} GB usable RAM is available; this $normalizedBackend start is estimated to need ${formatGb(estimatedAdditional)} GB in addition to Android's reserve.$contextNote Close memory-heavy apps or choose a smaller model.",
                    ),
                )
            }
            return PreflightDecision(
                allowed = true,
                effectiveContextTokens = effectiveContext,
                estimatedAdditionalBytes = estimatedAdditional,
                level = "warning",
                detail = "Usable RAM (${formatGb(memory.usableAvailableBytes)} GB) is below the conservative ${formatGb(estimatedAdditional)} GB estimate; Hermes will use the reduced context limit.$contextNote",
            )
        }

        val availableLabel = if (memory.availableBytes > 0L) {
            "${formatGb(memory.usableAvailableBytes)} GB usable RAM"
        } else {
            "unknown live RAM headroom"
        }
        return PreflightDecision(
            allowed = true,
            effectiveContextTokens = effectiveContext,
            estimatedAdditionalBytes = estimatedAdditional,
            level = "ok",
            detail = "Memory preflight passed with $availableLabel.$contextNote",
        )
    }

    private fun ramAdmissionDecision(
        dangerouslySkipRamChecks: Boolean,
        blocked: PreflightDecision,
    ): PreflightDecision {
        if (!dangerouslySkipRamChecks) return blocked
        return blocked.copy(
            allowed = true,
            level = "dangerous_bypass",
            detail =
                "DANGEROUS RAM CHECK BYPASS ACTIVE: Hermes will attempt this native model even though " +
                    "the RAM admission check would block it. Android may kill Hermes or destabilize other apps. " +
                    "Original check: ${blocked.detail}",
        )
    }

    internal fun beginAttempt(
        context: Context,
        backend: String,
        modelFile: File,
        requestedAccelerator: String,
        requestedContextTokens: Int,
        effectiveContextTokens: Int,
        memory: MemorySnapshot,
        preflight: PreflightDecision,
        runtimeLaunch: RuntimeLaunchBreadcrumb? = null,
    ): String {
        val attemptId = UUID.randomUUID().toString()
        val previous = readSnapshot(context)
            ?.takeIf { it.optString("status") == "initializing" }
        val payload = JSONObject()
            .put("attempt_id", attemptId)
            .put("status", "initializing")
            .put("stage", "native_runtime_start")
            .put("started_at_ms", System.currentTimeMillis())
            .put("backend", backend)
            .put("model_file", modelFile.name)
            .put("model_bytes", modelFile.length())
            .put("requested_accelerator", requestedAccelerator)
            .put("requested_context_tokens", requestedContextTokens)
            .put("effective_context_tokens", effectiveContextTokens)
            .put("memory", memory.toJson())
            .put("preflight_level", preflight.level)
            .put("preflight_detail", preflight.detail)
            .put("estimated_additional_bytes", preflight.estimatedAdditionalBytes)
        if (runtimeLaunch != null) {
            payload
                .put("runtime_lane", runtimeLaunch.lane)
                .put("cache_type_k", runtimeLaunch.cacheTypeK)
                .put("cache_type_v", runtimeLaunch.cacheTypeV)
                .put("flash_attention", runtimeLaunch.flashAttention)
                .put("launch_fingerprint_sha256", runtimeLaunch.launchFingerprintSha256)
                .put("additional_argv_count", runtimeLaunch.additionalArgvCount.coerceAtLeast(0))
                .put("additional_argv_sha256", runtimeLaunch.additionalArgvSha256)
        }
        if (previous != null) {
            payload.put("previous_incomplete_attempt", previous)
        }
        writeSnapshot(context, payload)
        return attemptId
    }

    internal fun finishAttempt(
        context: Context,
        attemptId: String,
        status: String,
        stage: String,
        detail: String,
        accelerator: String = "",
        acceleratorFallback: String = "",
        completionVerified: Boolean = false,
        completionLatencyMs: Long = 0L,
    ) {
        val current = readSnapshot(context) ?: JSONObject().put("attempt_id", attemptId)
        if (current.optString("attempt_id") != attemptId) return
        current
            .put("status", status)
            .put("stage", stage)
            .put("updated_at_ms", System.currentTimeMillis())
            .put("detail", detail.take(MAX_DETAIL_CHARS))
            .put("accelerator", accelerator.ifBlank { JSONObject.NULL })
            .put("accelerator_fallback", acceleratorFallback.ifBlank { JSONObject.NULL })
            .put("completion_verified", completionVerified)
            .put("completion_latency_ms", completionLatencyMs.coerceAtLeast(0L))
        writeSnapshot(context, current)
    }

    fun readSnapshot(context: Context): JSONObject? {
        val file = snapshotFile(context.applicationContext)
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    internal fun clearForTest(context: Context) {
        snapshotFile(context.applicationContext).delete()
    }

    private fun safeContextLimit(backend: String, modelBytes: Long, memory: MemorySnapshot): Int {
        if (memory.lowMemory) return MIN_CONTEXT_TOKENS
        val usable = memory.usableAvailableBytes
        return when {
            modelBytes >= VERY_LARGE_MODEL_BYTES -> if (usable >= 14_000_000_000L) 4_096 else 2_048
            modelBytes >= LARGE_MODEL_BYTES -> when {
                usable >= 8_000_000_000L -> if (backend == "litert-lm") 8_192 else 4_096
                usable >= 4_000_000_000L -> 4_096
                else -> 2_048
            }
            modelBytes >= MEDIUM_MODEL_BYTES -> if (usable >= 5_000_000_000L) 8_192 else 4_096
            usable in 1 until 2_000_000_000L -> 2_048
            usable >= 8_000_000_000L -> 8_192
            else -> 4_096
        }
    }

    private fun contextReserveBytes(backend: String, contextTokens: Int): Long {
        val base = when {
            contextTokens <= 2_048 -> 256_000_000L
            contextTokens <= 4_096 -> 512_000_000L
            contextTokens <= 8_192 -> 900_000_000L
            contextTokens <= 16_384 -> 1_500_000_000L
            else -> 2_500_000_000L
        }
        return if (backend == "litert-lm") base else saturatingMultiply(base, 70L, 100L)
    }

    private fun snapshotFile(context: Context): File {
        return File(context.filesDir, "$DIAGNOSTICS_DIR/$SNAPSHOT_FILE")
    }

    private fun writeSnapshot(context: Context, payload: JSONObject) {
        runCatching {
            val destination = snapshotFile(context.applicationContext)
            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            temporary.writeText(payload.toString(2), Charsets.UTF_8)
            if (!temporary.renameTo(destination)) {
                destination.writeText(payload.toString(2), Charsets.UTF_8)
                temporary.delete()
            }
        }
    }

    private fun saturatingMultiply(value: Long, numerator: Long, denominator: Long): Long {
        if (value <= 0L || numerator <= 0L || denominator <= 0L) return 0L
        if (value > Long.MAX_VALUE / numerator) return Long.MAX_VALUE
        return (value * numerator) / denominator
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE
        return left + right
    }

    private fun formatGb(bytes: Long): String = "%.1f".format(Locale.US, bytes / 1_000_000_000.0)

    private const val DIAGNOSTICS_DIR = "hermes-diagnostics"
    private const val SNAPSHOT_FILE = "local-model-runtime.json"
    private const val MAX_DETAIL_CHARS = 2_000
    private const val MIN_CONTEXT_TOKENS = 512
    private const val MEDIUM_MODEL_BYTES = 2_000_000_000L
    private const val LARGE_MODEL_BYTES = 3_000_000_000L
    private const val VERY_LARGE_MODEL_BYTES = 6_000_000_000L
}
