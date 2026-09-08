package com.mobilefork.hermesagent.play

import com.mobilefork.hermesagent.BuildConfig
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.backend.HermesRuntimeService
import com.mobilefork.hermesagent.device.HermesLinuxSubsystemBridge
import com.mobilefork.hermesagent.device.NativeAndroidShellTool
import com.mobilefork.hermesagent.device.HermesPrivilegedAccessBridge
import com.mobilefork.hermesagent.ui.chat.NativeToolChatSender
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PlayDistributionPolicyTest {
    @Test
    fun distributionIdentityControlsRealExecutionAdmissionNotOnlyMenus() {
        val context = RuntimeEnvironment.getApplication()
        if (BuildConfig.HERMES_PLAY_EDITION) {
            assertThrows(IllegalStateException::class.java) { HermesRuntimeManager.ensurePythonStarted(context) }
            assertThrows(IllegalStateException::class.java) { HermesLinuxSubsystemBridge.ensureInstalled(context) }
            assertThrows(IllegalStateException::class.java) { NativeAndroidShellTool.run(context, "echo should-not-run") }
            assertThrows(IllegalStateException::class.java) { HermesPrivilegedAccessBridge.runShellCommandJson(context, "id") }
            assertThrows(IllegalStateException::class.java) { HermesRuntimeService.start(context) }
            assertThrows(IllegalStateException::class.java) {
                com.mobilefork.hermesagent.device.HermesNotificationActionBridge.performNotificationJson(context,
                    org.json.JSONObject().put("notification_action", "post").put("text", "must not post"))
            }
            assertNull(NativeToolChatSender.extractTypedDirectToolName("terminal_tool command=\"date\""))
            assertFalse(NativeToolChatSender.extractDirectLinuxSandboxPrompt("linux_sandbox_tool action=install"))
        } else {
            DistributionPolicy.requireFullEdition("Full-edition feature retention")
            assertEquals("terminal_tool", NativeToolChatSender.extractTypedDirectToolName("terminal_tool command=\"date\""))
        }
    }

    @Test
    fun onlyExplicitLoopbackCanUseCleartextAndPrivateNetworkIsNotAnExemption() {
        assertTrue(PlayNetworkPolicy.requireApiUrl("http://127.0.0.1:15435"))
        assertTrue(PlayNetworkPolicy.requireApiUrl("http://[::1]:15436"))
        assertFalse(PlayNetworkPolicy.requireApiUrl("https://provider.example/v1"))
        listOf("http://192.168.1.10", "http://example.com", "file:///tmp/model", "https://key@example.com").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { PlayNetworkPolicy.requireApiUrl(url) }
        }
    }
}
