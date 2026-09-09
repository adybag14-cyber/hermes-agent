package com.mobilefork.hermesagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaquo.python.Python
import com.mobilefork.hermesagent.data.McpRuntimeBridge
import com.mobilefork.hermesagent.data.McpRuntimePhase
import com.mobilefork.hermesagent.data.McpSettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayMcpIsolationInstrumentedTest {
    @Test
    fun retainedFullEditionConsentCannotEnableMcpOrStartPythonInPlay() {
        assertTrue("Install the Play APK for this check", BuildConfig.HERMES_PLAY_EDITION)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("hermes_android_mcp_settings", Context.MODE_PRIVATE)
        // The actual v157 Full preference may survive a same-package edition
        // change. Treat it as data, never as authority over the installed build.
        val key = "external_mcp_enabled_v157"
        val existed = preferences.contains(key)
        val previous = preferences.getBoolean(key, false)
        val store = McpSettingsStore(context)
        val configuration = store.load().configText
        try {
            assertTrue(preferences.edit().putBoolean(key, true).commit())
            assertFalse(Python.isStarted())
            assertFalse(store.externalMcpAllowed())
            assertFalse(McpRuntimeBridge.externalMcpAllowed())
            assertThrows(IllegalStateException::class.java) { store.saveExternalMcpEnabled(true) }
            assertEquals(McpRuntimePhase.DISABLED, McpRuntimeBridge.currentStatus(context).phase)
            assertEquals(McpRuntimePhase.DISABLED, McpRuntimeBridge.reloadIntoRuntime(context).phase)
            assertFalse("MCP settings must never bootstrap Python in Play", Python.isStarted())
            assertEquals(configuration, store.load().configText)
        } finally {
            val editor = preferences.edit()
            if (existed) editor.putBoolean(key, previous) else editor.remove(key)
            assertTrue(editor.commit())
        }
    }
}
