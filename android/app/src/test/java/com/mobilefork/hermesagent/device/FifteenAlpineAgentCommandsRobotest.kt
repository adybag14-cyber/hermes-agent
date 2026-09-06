package com.mobilefork.hermesagent.device

import com.mobilefork.hermesagent.ui.chat.NativeToolCallingChatClient
import com.mobilefork.hermesagent.ui.chat.NativeDirectToolAuthorityParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FifteenAlpineAgentCommandsRobotest {
    @Test
    fun catalogHasFifteenDistinctGuestCommands() {
        val commands = AlpineAgentCommandCatalog.release148Commands
        assertEquals(15, commands.size)
        assertEquals(15, commands.map { it.id }.toSet().size)
        assertEquals(15, commands.map { it.command }.toSet().size)
        assertTrue(commands.any { it.command.contains("/etc/alpine-release") })
    }

    @Test
    fun wrappedGuestCommandDoesNotTeeOntoAPathTheCommandAlreadyWrites() {
        val writeCat = AlpineAgentCommandCatalog.release148Commands.first { it.id == "write-cat" }
        val wrapped = AlpineAgentCommandCatalog.wrappedGuestCommand(writeCat)
        assertEquals(writeCat.command, wrapped)
        assertTrue(wrapped.contains(writeCat.proofFile))
        val printf = AlpineAgentCommandCatalog.release148Commands.first { it.id == "printf-marker" }
        val teed = AlpineAgentCommandCatalog.wrappedGuestCommand(printf)
        assertTrue(teed, teed.contains("| tee ${printf.proofFile}"))
    }

    @Test
    fun fifteenDistinctTypedGuestRequestsFailClosedWithoutModelOrGuestDispatch() {
        val commands = AlpineAgentCommandCatalog.release148Commands
        val expectedCommands = commands.map(AlpineAgentCommandCatalog::wrappedGuestCommand)
        val dispatchedTools = mutableListOf<String>()
        val parsedCommands = mutableListOf<String>()
        val blockedBridgeResults = mutableListOf<String>()
        val activeRequestGate = AutomationPublicationGate { publication ->
            publication()
            true
        }
        val client = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            onToolDispatch = dispatchedTools::add,
        )

        expectedCommands.forEachIndexed { index, command ->
            val prompt = exactTypedGuestPrompt(command)
            val authority = NativeDirectToolAuthorityParser.parse(prompt)
            assertTrue("Request ${index + 1} must use the closed typed grammar", authority.isTypedInvocation)
            assertEquals("mcp_run_in_proot", authority.toolName)
            assertEquals(command, authority.arguments().optString("command"))
            assertEquals(AlpineAgentCommandCatalog.DISTRO_ID, authority.arguments().optString("distro_id"))
            assertEquals(AlpineAgentCommandCatalog.SANDBOX_NAME, authority.arguments().optString("name"))
            parsedCommands += authority.arguments().optString("command")

            val blocked = HermesLinuxSandboxBridge.performAction(
                context = RuntimeEnvironment.getApplication(),
                action = "run",
                distroId = AlpineAgentCommandCatalog.DISTRO_ID,
                name = AlpineAgentCommandCatalog.SANDBOX_NAME,
                command = command,
                timeoutSeconds = 60,
                publicationGate = activeRequestGate,
            )
            assertEquals("Request ${index + 1} bridge result: ${blocked.toString(2)}", 126, blocked.optInt("exit_code", -1))
            assertTrue(blocked.toString(2), blocked.optBoolean("request_owned_operation_blocked"))
            assertEquals("request_owned_proot_blocked", blocked.optString("sandbox_execution_mode"))
            assertEquals("run", blocked.optString("action"))
            assertEquals(command, blocked.optString("sandbox_command"))
            blockedBridgeResults += blocked.optString("sandbox_execution_mode")

            val result = client.send(
                baseUrl = "http://127.0.0.1:9",
                modelName = "unused-for-exact-typed-request",
                sessionId = "robotest-alpine-blocked-${index + 1}",
                userText = prompt,
            )
            assertEquals("Request ${index + 1} must resolve as one terminal tool result: $result", 1, result.executedToolCalls)
            assertEquals("Exact typed requests must not delegate authority to a model", 0, result.modelRequestCount)
            assertTrue(
                "Request ${index + 1} must report that guest dispatch was blocked: ${result.content}",
                result.content == com.mobilefork.hermesagent.ui.chat.sandboxStopPolicyMessage(
                    com.mobilefork.hermesagent.ui.i18n.AppLanguage.ENGLISH,
                ),
            )
        }

        assertEquals(expectedCommands, parsedCommands)
        assertEquals(15, parsedCommands.toSet().size)
        assertEquals(List(15) { "mcp_run_in_proot" }, dispatchedTools)
        assertEquals(List(15) { "request_owned_proot_blocked" }, blockedBridgeResults)
    }

    private fun exactTypedGuestPrompt(command: String): String {
        return "mcp_run_in_proot " +
            "command=\"$command\" " +
            "distro_id=\"${AlpineAgentCommandCatalog.DISTRO_ID}\" " +
            "name=\"${AlpineAgentCommandCatalog.SANDBOX_NAME}\" " +
            "timeout_seconds=60"
    }
}
