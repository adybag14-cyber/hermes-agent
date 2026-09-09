package com.mobilefork.hermesagent.settings

import android.content.Context
import com.mobilefork.hermesagent.BuildConfig
import com.mobilefork.hermesagent.data.McpRuntimePhase
import com.mobilefork.hermesagent.data.McpRuntimeStatus
import com.mobilefork.hermesagent.data.McpSettingsStore
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.McpRuntimeText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class McpRuntimeRobotest {
    @Test
    fun retainedConfigNeverGrantsConsentAndRevocationPreservesConfig() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("hermes_android_mcp_settings", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val config = File(context.filesDir, "hermes-home/mcp/mcp_config.json")
        config.parentFile!!.mkdirs()
        val original = "{\"mcpServers\":{\"retained\":{\"command\":\"never-run-without-consent\"}}}"
        config.writeText(original)
        val store = McpSettingsStore(context)
        assertFalse(store.externalMcpAllowed())
        if (!BuildConfig.HERMES_PLAY_EDITION) {
            assertTrue(store.saveExternalMcpEnabled(true).externalMcpEnabled)
            assertFalse(store.saveExternalMcpEnabled(false).externalMcpEnabled)
        } else {
            assertThrows(IllegalStateException::class.java) { store.saveExternalMcpEnabled(true) }
        }
        assertEquals(original, config.readText())
        assertFalse(McpSettingsStore(context).load().externalMcpEnabled)
    }

    @Test
    fun runtimeStatusRequiresActualConnectionProofAndTranslationsCoverDisclosure() {
        assertEquals(McpRuntimePhase.NEEDS_RUNTIME,
            McpRuntimeStatus.fromJson("{\"reason\":\"python_agent_runtime_not_running\"}").phase)
        assertEquals(McpRuntimePhase.FAILED,
            McpRuntimeStatus.fromJson("{\"synced\":true,\"enabled\":true,\"server_count\":1}").phase)
        val partial = McpRuntimeStatus.fromJson("""{"synced":true,"enabled":true,"server_count":2,"tool_count":3,
            "servers":[{"connected":true},{"connected":false}]}""")
        assertEquals(McpRuntimePhase.PARTIAL, partial.phase)
        assertEquals(1, partial.connectedServers)
        assertEquals(3, partial.tools)
        McpRuntimeText.entries.forEach { text ->
            AppLanguage.entries.filter { it != AppLanguage.ENGLISH }.forEach { language ->
                assertTrue(text.inLanguage(language).isNotBlank())
                assertNotEquals(text.inLanguage(AppLanguage.ENGLISH), text.inLanguage(language))
            }
        }
    }
}
