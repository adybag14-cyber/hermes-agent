package com.nousresearch.hermesagent.device

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HermesHindsightMemoryBridgeTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearStore() {
        HermesHindsightMemoryBridge.performActionJson(context, "clear")
    }

    @Test
    fun retainsRecallsAndReflectsStructuredMemories() {
        val retain = JSONObject(
            HermesHindsightMemoryBridge.performActionJson(
                context,
                "retain",
                JSONObject()
                    .put("content", "JDK 21 is the stable Android validation toolchain for native Hermes builds.")
                    .put("source", "test")
                    .put("category", "android")
                    .put("tags", JSONArray().put("validation").put("toolchain")),
            ),
        )

        assertTrue(retain.getBoolean("success"))
        assertEquals(1, retain.getInt("retained_count"))
        assertTrue(retain.getJSONArray("retained_memories").getJSONObject(0).getJSONArray("entities").toString().contains("JDK"))

        val recall = JSONObject(
            HermesHindsightMemoryBridge.performActionJson(
                context,
                "recall",
                JSONObject().put("query", "Android JDK validation").put("limit", 3),
            ),
        )

        assertTrue(recall.getBoolean("success"))
        assertEquals(1, recall.getInt("result_count"))
        assertTrue(recall.getJSONArray("memories").getJSONObject(0).getString("content").contains("JDK 21"))

        val reflect = JSONObject(HermesHindsightMemoryBridge.performActionJson(context, "reflect"))
        assertTrue(reflect.getBoolean("success"))
        assertEquals(1, reflect.getInt("after_count"))
    }
}
