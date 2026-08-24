package com.mobilefork.hermesagent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilefork.hermesagent.device.AlpineAgentCommandCatalog
import com.mobilefork.hermesagent.device.AutomationPublicationGate
import com.mobilefork.hermesagent.device.HermesLinuxSandboxBridge
import com.mobilefork.hermesagent.ui.chat.NativeToolCallingChatClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Release148AlpineFifteenAgentCommandsSmokeTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun fifteenDistinctChatOwnedGuestRequestsFailClosedBeforeGuestDispatch() {
        val commands = AlpineAgentCommandCatalog.release148Commands
        assertEquals(15, commands.size)
        val dispatchedTools = mutableListOf<String>()
        val client = NativeToolCallingChatClient(
            context = app,
            onToolDispatch = dispatchedTools::add,
        )
        val activeRequestGate = AutomationPublicationGate { publication ->
            publication()
            true
        }

        commands.forEachIndexed { index, entry ->
            val command = AlpineAgentCommandCatalog.wrappedGuestCommand(entry)
            val blocked = HermesLinuxSandboxBridge.performAction(
                context = app,
                action = "run",
                distroId = AlpineAgentCommandCatalog.DISTRO_ID,
                name = AlpineAgentCommandCatalog.SANDBOX_NAME,
                command = command,
                timeoutSeconds = 60,
                publicationGate = activeRequestGate,
            )
            assertEquals("chat-owned bridge result ${entry.id}: ${blocked.toString(2)}", 126, blocked.optInt("exit_code", -1))
            assertTrue(blocked.toString(2), blocked.optBoolean("request_owned_operation_blocked"))
            assertEquals("request_owned_proot_blocked", blocked.optString("sandbox_execution_mode"))
            assertEquals("run", blocked.optString("action"))
            assertEquals(command, blocked.optString("sandbox_command"))

            val result = client.send(
                baseUrl = "http://127.0.0.1:9",
                modelName = "unused-for-exact-typed-request",
                sessionId = "release-151-alpine-blocked-${index + 1}",
                userText = exactTypedGuestPrompt(command),
            )
            assertEquals(
                "Exact typed request ${entry.id} must resolve as one terminal tool result: $result",
                1,
                result.executedToolCalls,
            )
            assertEquals("Exact typed requests must not delegate authority to a model", 0, result.modelRequestCount)
            assertTrue(
                "Exact typed request ${entry.id} must report that guest dispatch was blocked: ${result.content}",
                result.content.contains("blocked this chat-owned Linux guest process before dispatch"),
            )
        }

        assertEquals(List(15) { "mcp_run_in_proot" }, dispatchedTools)
    }

    @Test
    fun manualUserOwnedLaneLeavesFifteenDistinctProofsInAlpine() {
        val status = HermesLinuxSandboxBridge.performAction(app, action = "status")
        val installed = status.optJSONArray("installed_sandboxes")
        val alpine = (0 until (installed?.length() ?: 0))
            .mapNotNull { installed?.optJSONObject(it) }
            .firstOrNull { it.optString("name") == AlpineAgentCommandCatalog.SANDBOX_NAME }
        assumeTrue("A runnable hermes-alpine sandbox is required: $status", alpine != null)
        assertTrue(status.toString(2), alpine!!.optBoolean("android_execution_supported"))

        val commands = AlpineAgentCommandCatalog.release148Commands
        assertEquals(15, commands.size)
        commands.forEach { entry ->
            val cleanup = HermesLinuxSandboxBridge.runUserCommand(
                context = app,
                name = AlpineAgentCommandCatalog.SANDBOX_NAME,
                command = "rm -f ${entry.proofFile}",
                timeoutSeconds = 60,
            )
            assertEquals("proof cleanup ${entry.id}: ${cleanup.toString(2)}", 0, cleanup.optInt("exit_code", -1))
            assertTrue(cleanup.toString(2), cleanup.optBoolean("manual_terminal_session"))
        }

        val executedCommands = mutableListOf<String>()
        commands.forEach { entry ->
            val command = AlpineAgentCommandCatalog.wrappedGuestCommand(entry)
            val result = HermesLinuxSandboxBridge.runUserCommand(
                context = app,
                name = AlpineAgentCommandCatalog.SANDBOX_NAME,
                command = command,
                timeoutSeconds = 60,
            )
            assertEquals(
                "manual guest command ${entry.id}: ${result.toString(2)}",
                0,
                result.optInt("exit_code", -1),
            )
            assertEquals("proot_distro_qemu", result.optString("sandbox_execution_mode"))
            assertTrue(result.toString(2), result.optBoolean("manual_terminal_session"))
            executedCommands += command
        }
        assertEquals(15, executedCommands.toSet().size)

        val identity = HermesLinuxSandboxBridge.runUserCommand(
            context = app,
            name = AlpineAgentCommandCatalog.SANDBOX_NAME,
            command = "cat /etc/alpine-release",
            timeoutSeconds = 60,
        )
        assertEquals(identity.toString(2), 0, identity.optInt("exit_code", -1))
        assertTrue(identity.toString(2), identity.optString("output").contains(AlpineAgentCommandCatalog.ALPINE_RELEASE_NEEDLE))
        assertEquals("proot_distro_qemu", identity.optString("sandbox_execution_mode"))
        assertTrue(identity.toString(2), identity.optBoolean("manual_terminal_session"))

        commands.forEach { entry ->
            val proof = HermesLinuxSandboxBridge.runUserCommand(
                context = app,
                name = AlpineAgentCommandCatalog.SANDBOX_NAME,
                command = "cat ${entry.proofFile}",
                timeoutSeconds = 60,
            )
            assertEquals("guest proof ${entry.id}: ${proof.toString(2)}", 0, proof.optInt("exit_code", -1))
            assertEquals("proot_distro_qemu", proof.optString("sandbox_execution_mode"))
            assertTrue(proof.toString(2), proof.optBoolean("manual_terminal_session"))
            val output = proof.optString("output")
            if (entry.proofNeedle.isNotBlank()) {
                assertTrue("guest proof ${entry.id} missing ${entry.proofNeedle}: $output", output.contains(entry.proofNeedle))
            } else {
                assertTrue("guest proof ${entry.id} was blank: $proof", output.isNotBlank())
            }
        }
    }

    private fun exactTypedGuestPrompt(command: String): String {
        return "mcp_run_in_proot " +
            "command=\"$command\" " +
            "distro_id=\"${AlpineAgentCommandCatalog.DISTRO_ID}\" " +
            "name=\"${AlpineAgentCommandCatalog.SANDBOX_NAME}\" " +
            "timeout_seconds=60"
    }
}
