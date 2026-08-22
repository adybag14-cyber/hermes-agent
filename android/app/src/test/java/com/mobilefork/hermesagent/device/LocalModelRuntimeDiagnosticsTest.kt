package com.mobilefork.hermesagent.device

import android.content.Context
import com.mobilefork.hermesagent.backend.LlamaCppLaunchConfig
import com.mobilefork.hermesagent.backend.LlamaCppRuntimeLane
import com.mobilefork.hermesagent.backend.LlamaCppServerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LocalModelRuntimeDiagnosticsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearRuntimeAttempt() {
        LocalModelRuntimeDiagnostics.clearForTest(context)
    }

    @Test
    fun blocksSixPointFiveGbLiteRtModelOnNominalSixteenGbPhone() {
        val decision = LocalModelRuntimeDiagnostics.evaluatePreflight(
            backend = "litert-lm",
            modelBytes = 6_500_000_000L,
            requestedContextTokens = 32_000,
            memory = memory(total = 16_000_000_000L, available = 10_000_000_000L),
        )

        assertFalse(decision.allowed)
        assertEquals(2_048, decision.effectiveContextTokens)
        assertTrue(decision.detail, decision.detail.contains("needs about 16.3 GB total RAM"))
        assertTrue(decision.detail, decision.detail.contains("Choose a smaller artifact"))
    }

    @Test
    fun permitsExactMiniCpmLiteRtArtifactWithUsableHeadroom() {
        val decision = LocalModelRuntimeDiagnostics.evaluatePreflight(
            backend = "litert-lm",
            modelBytes = 1_103_486_896L,
            requestedContextTokens = 4_096,
            memory = memory(total = 8_000_000_000L, available = 6_500_000_000L),
        )

        assertTrue(decision.detail, decision.allowed)
        assertEquals(4_096, decision.effectiveContextTokens)
        assertEquals("ok", decision.level)
    }

    @Test
    fun lowMemorySignalBlocksBeforeNativeAllocation() {
        val decision = LocalModelRuntimeDiagnostics.evaluatePreflight(
            backend = "llama.cpp",
            modelBytes = 532_517_120L,
            requestedContextTokens = 1_024,
            memory = memory(total = 8_000_000_000L, available = 700_000_000L, lowMemory = true),
        )

        assertFalse(decision.allowed)
        assertEquals(512, decision.effectiveContextTokens)
        assertTrue(decision.detail, decision.detail.contains("active low-memory pressure"))
    }

    @Test
    fun dangerousBypassOverridesOnlyTheRamAdmissionBlockAndKeepsWarningEvidence() {
        val decision = LocalModelRuntimeDiagnostics.evaluatePreflight(
            backend = "llama.cpp",
            modelBytes = 6_500_000_000L,
            requestedContextTokens = 32_000,
            memory = memory(total = 8_000_000_000L, available = 600_000_000L, lowMemory = true),
            dangerouslySkipRamChecks = true,
        )

        assertTrue(decision.detail, decision.allowed)
        assertEquals("dangerous_bypass", decision.level)
        assertEquals(512, decision.effectiveContextTokens)
        assertTrue(decision.detail, decision.detail.contains("DANGEROUS RAM CHECK BYPASS ACTIVE"))
        assertTrue(decision.detail, decision.detail.contains("Android may kill Hermes"))
        assertTrue(decision.detail, decision.detail.contains("active low-memory pressure"))
    }

    @Test
    fun dangerousBypassAllowsInsufficientTotalRamAfterRetainingTheOriginalEstimate() {
        val decision = LocalModelRuntimeDiagnostics.evaluatePreflight(
            backend = "llama.cpp",
            modelBytes = 6_500_000_000L,
            requestedContextTokens = 8_192,
            memory = memory(total = 8_000_000_000L, available = 7_000_000_000L),
            dangerouslySkipRamChecks = true,
        )

        assertTrue(decision.detail, decision.allowed)
        assertEquals("dangerous_bypass", decision.level)
        assertTrue(decision.detail, decision.detail.contains("needs about 11.7 GB total RAM"))
    }

    @Test
    fun dangerousBypassNeverAllowsAnEmptyOrUnreadableArtifact() {
        val decision = LocalModelRuntimeDiagnostics.evaluatePreflight(
            backend = "llama.cpp",
            modelBytes = 0L,
            requestedContextTokens = 2_048,
            memory = memory(total = 8_000_000_000L, available = 7_000_000_000L),
            dangerouslySkipRamChecks = true,
        )

        assertFalse(decision.allowed)
        assertEquals("blocked", decision.level)
        assertTrue(decision.detail, decision.detail.contains("empty or unreadable artifact"))
    }

    @Test
    fun dangerousBypassAuthorityAppliesToOneEvaluationOnly() {
        val constrainedMemory = memory(
            total = 8_000_000_000L,
            available = 600_000_000L,
            lowMemory = true,
        )
        val bypassed = LocalModelRuntimeDiagnostics.evaluatePreflight(
            backend = "llama.cpp",
            modelBytes = 6_500_000_000L,
            requestedContextTokens = 32_000,
            memory = constrainedMemory,
            dangerouslySkipRamChecks = true,
        )
        val followingDefaultAttempt = LocalModelRuntimeDiagnostics.evaluatePreflight(
            backend = "llama.cpp",
            modelBytes = 6_500_000_000L,
            requestedContextTokens = 32_000,
            memory = constrainedMemory,
        )

        assertTrue(bypassed.detail, bypassed.allowed)
        assertEquals("dangerous_bypass", bypassed.level)
        assertFalse(followingDefaultAttempt.allowed)
        assertEquals("blocked", followingDefaultAttempt.level)
        assertFalse(
            followingDefaultAttempt.detail,
            followingDefaultAttempt.detail.contains("DANGEROUS RAM CHECK BYPASS ACTIVE"),
        )
    }

    @Test
    fun launchBreadcrumbPersistsHashedConfigurationWithoutRawExpertArguments() {
        val model = File(context.cacheDir, "private-argv-matrix-model.gguf")
            .apply { writeBytes(ByteArray(64)) }
        val snapshot = memory(
            total = 8_000_000_000L,
            available = 600_000_000L,
            lowMemory = true,
        )
        val rawArguments = listOf("--tags", "super-secret-value-7f1e")
        val launchConfig = LlamaCppLaunchConfig(
            lane = LlamaCppRuntimeLane.TURBOQUANT,
            cacheTypeK = "Q5_0",
            cacheTypeV = "Q5_1",
            flashAttention = "ON",
            additionalArguments = rawArguments,
        )
        val runtimeLaunch = LlamaCppServerController.diagnosticsBreadcrumbFor(launchConfig)
        val preflight = LocalModelRuntimeDiagnostics.evaluatePreflight(
            backend = "llama.cpp-turboquant",
            modelBytes = 6_500_000_000L,
            requestedContextTokens = 32_000,
            memory = snapshot,
            dangerouslySkipRamChecks = true,
        )
        val attemptId = LocalModelRuntimeDiagnostics.beginAttempt(
            context = context,
            backend = "llama.cpp-turboquant",
            modelFile = model,
            requestedAccelerator = "cpu",
            requestedContextTokens = 32_000,
            effectiveContextTokens = preflight.effectiveContextTokens,
            memory = snapshot,
            preflight = preflight,
            runtimeLaunch = runtimeLaunch,
        )

        val started = LocalModelRuntimeDiagnostics.readSnapshot(context)!!
        assertEquals("turboquant", started.getString("runtime_lane"))
        assertEquals("q5_0", started.getString("cache_type_k"))
        assertEquals("q5_1", started.getString("cache_type_v"))
        assertEquals("on", started.getString("flash_attention"))
        assertEquals(launchConfig.fingerprint(), started.getString("launch_fingerprint_sha256"))
        assertEquals(2, started.getInt("additional_argv_count"))
        assertEquals(
            "fa07cb16dc00c3fe48a2de9d9cc35898d0dddf279488764ee34b1a45470e1e87",
            started.getString("additional_argv_sha256"),
        )
        assertEquals("dangerous_bypass", started.getString("preflight_level"))
        assertTrue(
            started.getString("preflight_detail"),
            started.getString("preflight_detail").contains("DANGEROUS RAM CHECK BYPASS ACTIVE"),
        )
        assertRawArgumentsAbsent(started.toString(), rawArguments)

        LocalModelRuntimeDiagnostics.finishAttempt(
            context = context,
            attemptId = attemptId,
            status = "ready",
            stage = "completion_verified",
            detail = LlamaCppServerController.diagnosticsSafeDetail(
                "backend echo: ${rawArguments.joinToString(" ")}",
                launchConfig,
            ),
            accelerator = "cpu",
            completionVerified = true,
            completionLatencyMs = 123L,
        )

        val completed = LocalModelRuntimeDiagnostics.readSnapshot(context)!!
        assertEquals("ready", completed.getString("status"))
        assertEquals("turboquant", completed.getString("runtime_lane"))
        assertEquals(launchConfig.fingerprint(), completed.getString("launch_fingerprint_sha256"))
        assertEquals(2, completed.getInt("additional_argv_count"))
        assertEquals(
            "fa07cb16dc00c3fe48a2de9d9cc35898d0dddf279488764ee34b1a45470e1e87",
            completed.getString("additional_argv_sha256"),
        )
        assertEquals("dangerous_bypass", completed.getString("preflight_level"))
        assertRawArgumentsAbsent(completed.toString(), rawArguments)
        assertTrue(
            completed.getString("detail"),
            completed.getString("detail").contains("<redacted-additional-argv>"),
        )
    }

    @Test
    fun persistsAttemptUntilRuntimeProofIsRecorded() {
        val model = File(context.cacheDir, "matrix-model.gguf").apply { writeBytes(ByteArray(64)) }
        val snapshot = memory(total = 8_000_000_000L, available = 6_000_000_000L)
        val preflight = LocalModelRuntimeDiagnostics.evaluatePreflight(
            backend = "llama.cpp",
            modelBytes = model.length(),
            requestedContextTokens = 1_024,
            memory = snapshot,
        )
        val attemptId = LocalModelRuntimeDiagnostics.beginAttempt(
            context = context,
            backend = "llama.cpp",
            modelFile = model,
            requestedAccelerator = "cpu",
            requestedContextTokens = 1_024,
            effectiveContextTokens = preflight.effectiveContextTokens,
            memory = snapshot,
            preflight = preflight,
        )

        assertEquals("initializing", LocalModelRuntimeDiagnostics.readSnapshot(context)?.getString("status"))

        LocalModelRuntimeDiagnostics.finishAttempt(
            context = context,
            attemptId = attemptId,
            status = "ready",
            stage = "completion_verified",
            detail = "canary passed",
            accelerator = "cpu",
            completionVerified = true,
            completionLatencyMs = 321L,
        )
        val completed = LocalModelRuntimeDiagnostics.readSnapshot(context)!!
        assertEquals("ready", completed.getString("status"))
        assertEquals("completion_verified", completed.getString("stage"))
        assertTrue(completed.getBoolean("completion_verified"))
        assertEquals(321L, completed.getLong("completion_latency_ms"))
        assertFalse(completed.has("runtime_lane"))
        assertFalse(completed.has("launch_fingerprint_sha256"))
        assertFalse(completed.has("additional_argv_sha256"))
    }

    private fun assertRawArgumentsAbsent(serialized: String, rawArguments: List<String>) {
        rawArguments.forEach { rawArgument ->
            assertFalse(serialized, serialized.contains(rawArgument))
        }
    }

    private fun memory(
        total: Long,
        available: Long,
        lowMemory: Boolean = false,
    ) = LocalModelRuntimeDiagnostics.MemorySnapshot(
        totalBytes = total,
        availableBytes = available,
        thresholdBytes = 500_000_000L,
        lowMemory = lowMemory,
        memoryClassBytes = 512_000_000L,
        largeMemoryClassBytes = 1_024_000_000L,
        nativeHeapAllocatedBytes = 128_000_000L,
    )
}
