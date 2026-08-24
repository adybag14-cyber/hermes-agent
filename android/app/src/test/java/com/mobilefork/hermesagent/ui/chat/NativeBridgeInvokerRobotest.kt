package com.mobilefork.hermesagent.ui.chat

import android.content.Context
import com.mobilefork.hermesagent.device.HermesCrashLogStore
import com.mobilefork.hermesagent.device.HermesHindsightMemoryBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
class NativeBridgeInvokerRobotest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearCrashStore() {
        HermesCrashLogStore.clearAllForTest(context)
        HermesHindsightMemoryBridge.performActionJson(context, "clear")
    }

    @Test
    fun stoppedDirectDiagnosticsAHasNoAuthorityToClearIndependentPersistentB() {
        val coordinator = ChatSendRequestCoordinator()
        val requestA = coordinator.begin("diagnostics-a", "assistant-a") {}!!
        val stalePublicationGateA = coordinator.publicationGate(requestA)
        assertEquals(requestA, coordinator.stopActive {})

        val requestB = coordinator.begin("diagnostics-b", "assistant-b") {}!!
        assertTrue(
            coordinator.mutateIfActive(requestB) {
                HermesCrashLogStore.recordCrashForTest(
                    context = context,
                    throwable = IllegalStateException("independent persistent request B"),
                    threadName = "request-b",
                    nowMs = 200L,
                )
            },
        )

        val staleAFailure = runCatching {
            NativeBridgeInvoker.performDiagnosticsAction(
                context = context,
                action = "clear_last_crash",
                arguments = JSONObject(),
                publicationGate = stalePublicationGateA,
            )
        }.exceptionOrNull()

        assertTrue(staleAFailure is CancellationException)
        val persistedB = HermesCrashLogStore.statusSnapshot(context)
        assertTrue(persistedB.hasLastCrash)
        assertTrue(persistedB.previewLines.joinToString("\n").contains("independent persistent request B"))
        assertTrue(coordinator.isActive(requestB))
    }

    @Test
    fun promptMemoryInvokerRecallDoesNotReinforcePersistentFields() {
        HermesHindsightMemoryBridge.performActionJson(
            context,
            "retain",
            JSONObject().put("content", "Nanbeige automatic prompt memory must be read only."),
        )
        val before = JSONObject(HermesHindsightMemoryBridge.performActionJson(context, "list"))
            .getJSONArray("memories")
            .getJSONObject(0)

        val recalled = JSONObject(
            NativeBridgeInvoker.performMemoryAction(
                context = context,
                action = "relevant_context",
                arguments = JSONObject().put("query", "Nanbeige automatic prompt"),
                reinforceRecall = false,
            ),
        )

        val after = JSONObject(HermesHindsightMemoryBridge.performActionJson(context, "list"))
            .getJSONArray("memories")
            .getJSONObject(0)
        assertTrue(recalled.getString("system_prompt_context").contains("Nanbeige"))
        assertEquals(before.getInt("hit_count"), after.getInt("hit_count"))
        assertEquals(before.getLong("last_accessed_at_ms"), after.getLong("last_accessed_at_ms"))
    }
}
