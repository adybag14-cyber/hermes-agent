package com.nousresearch.hermesagent.device

import android.os.Bundle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HermesTaskerPluginBridgeTest {
    @Test
    fun taskerPluginResultBundleRequiresTokenBeforeRunningAutomation() {
        val context = RuntimeEnvironment.getApplication()
        val store = HermesAutomationStore(context)
        store.clear()

        val created = JSONObject(
            HermesAutomationBridge.performActionJson(
                context,
                "create_variable_action_task",
                JSONObject()
                    .put("id", "auto-tasker-plugin")
                    .put("label", "Tasker plugin smoke")
                    .put("variable_action", "set")
                    .put("name", "%PLUGIN_RESULT")
                    .put("value", "ran via %TASKER_PLUGIN_AUTOMATION_ID")
                    .put("automation_enabled", false),
            ),
        )
        assertTrue(created.toString(), created.getBoolean("success"))

        val missingBundle = JSONObject(HermesTaskerPluginBridge.runPluginBundleJson(context, null))
        assertFalse(missingBundle.toString(), missingBundle.getBoolean("success"))

        val unauthorized = JSONObject(
            HermesTaskerPluginBridge.runPluginBundleJson(
                context,
                Bundle().apply {
                    putString(HermesTaskerPluginBridge.KEY_AUTOMATION_ID, "auto-tasker-plugin")
                    putString(HermesTaskerPluginBridge.KEY_TOKEN, "not-authorized-token")
                },
            ),
        )
        assertFalse(unauthorized.toString(), unauthorized.getBoolean("success"))

        val resultIntent = HermesTaskerPluginBridge.buildResultIntent(
            context,
            automationId = "auto-tasker-plugin",
            label = "Plugin smoke",
        )
        assertEquals(
            "Run Hermes automation: Plugin smoke",
            resultIntent.getStringExtra(HermesTaskerPluginBridge.EXTRA_STRING_BLURB),
        )
        val bundle = HermesTaskerPluginBridge.bundleFromIntent(resultIntent)
        val fired = JSONObject(HermesTaskerPluginBridge.runPluginBundleJson(context, bundle, "net.dinglisch.android.taskerm"))

        assertTrue(fired.toString(), fired.getBoolean("success"))
        assertTrue(fired.getBoolean("tasker_plugin"))
        assertEquals(HermesTaskerPluginBridge.TRIGGER_TASKER_PLUGIN, fired.getString("trigger"))
        assertEquals("ran via auto-tasker-plugin", store.getVariable("PLUGIN_RESULT"))
        assertEquals("auto-tasker-plugin", store.getVariable("TASKER_PLUGIN_AUTOMATION_ID"))
        assertEquals("net.dinglisch.android.taskerm", store.getVariable("TASKER_PLUGIN_CALLER_PACKAGE"))
    }
}
