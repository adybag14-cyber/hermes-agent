package com.mobilefork.hermesagent.device

import android.content.Context
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
