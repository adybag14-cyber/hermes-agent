package com.mobilefork.hermesagent.device

import com.mobilefork.hermesagent.ui.chat.NativeToolCallingChatClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FifteenAlpineAgentCommandsRobotest {
    private lateinit var server: MockWebServer
    private lateinit var client: NativeToolCallingChatClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = NativeToolCallingChatClient(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

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
    fun fifteenDistinctMcpRunInProotCallsAreExecutedAndFedBack() {
        val commands = AlpineAgentCommandCatalog.release148Commands
        server.enqueue(jsonResponse(fifteenToolCallsPayload(commands)))
        server.enqueue(jsonResponse(finalPayload("fifteen alpine commands processed")))

        val result = client.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "scripted-alpine-15",
            sessionId = "robotest-alpine-15",
            userText = AlpineAgentCommandCatalog.guestPrompt(
                commands.joinToString("; ") { it.command },
            ),
        )

        assertEquals(
            "Each of the 15 Alpine commands must be a processed tool call, not a dropped one: $result",
            15,
            result.executedToolCalls,
        )
        assertEquals(2, result.modelRequestCount)

        server.takeRequest()
        val followUp = JSONObject(server.takeRequest().body.readUtf8())
        val messages = followUp.getJSONArray("messages")
        val toolMessages = (0 until messages.length())
            .mapNotNull { messages.optJSONObject(it) }
            .filter { it.optString("role") == "tool" }
        assertEquals(15, toolMessages.size)

        val seenCommands = mutableSetOf<String>()
        val seenModes = mutableSetOf<String>()
        toolMessages.forEach { message ->
            val body = JSONObject(message.optString("content", "{}"))
            assertEquals("proot_distro_qemu", body.optString("sandbox_execution_mode"))
            assertEquals(AlpineAgentCommandCatalog.SANDBOX_NAME, body.optString("sandbox_name"))
            val sandboxCommand = body.optString("sandbox_command")
            assertTrue("Missing sandbox_command: $body", sandboxCommand.isNotBlank())
            seenCommands += sandboxCommand
            seenModes += body.optString("sandbox_execution_mode")
        }
        assertEquals(15, seenCommands.size)
        assertEquals(setOf("proot_distro_qemu"), seenModes)
        commands.forEach { entry ->
            assertTrue(
                "Expected guest command ${entry.command} among processed tool results: $seenCommands",
                seenCommands.contains(entry.command),
            )
        }
    }

    private fun fifteenToolCallsPayload(commands: List<AlpineAgentCommandCatalog.GuestCommand>): JSONObject {
        val toolCalls = JSONArray()
        commands.forEachIndexed { index, entry ->
            toolCalls.put(
                JSONObject()
                    .put("id", "call_alpine_${index + 1}")
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", "mcp_run_in_proot")
                            .put(
                                "arguments",
                                JSONObject()
                                    .put("distro_id", AlpineAgentCommandCatalog.DISTRO_ID)
                                    .put("name", AlpineAgentCommandCatalog.SANDBOX_NAME)
                                    .put("command", entry.command)
                                    .toString(),
                            ),
                    ),
            )
        }
        val message = JSONObject()
            .put("role", "assistant")
            .put("content", JSONObject.NULL)
            .put("tool_calls", toolCalls)
        return completionPayload(message, "tool_calls")
    }

    private fun finalPayload(content: String): JSONObject {
        return completionPayload(
            JSONObject().put("role", "assistant").put("content", content),
            "stop",
        )
    }

    private fun jsonResponse(body: JSONObject): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body.toString())
    }

    private fun completionPayload(message: JSONObject, finishReason: String): JSONObject {
        return JSONObject()
            .put("id", "chatcmpl-alpine-15")
            .put("object", "chat.completion")
            .put("created", 1)
            .put("model", "scripted")
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("message", message)
                        .put("finish_reason", finishReason),
                ),
            )
    }
}
