package com.mobilefork.hermesagent.device

import com.mobilefork.hermesagent.ui.chat.NativeToolCallingChatClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlpineSandboxAgentRoutingRobotest {
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
    fun mcpRunInProotToolCallRoutesToAlpineGuestNotHostShell() {
        val command = "cat /etc/alpine-release"
        server.enqueue(jsonResponse(openaiToolCallPayload("mcp_run_in_proot", alpineRunArguments(command))))
        server.enqueue(jsonResponse(finalPayload("guest done")))

        val result = client.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "scripted-alpine-router",
            sessionId = "robotest-alpine-routing",
            userText = AlpineAgentCommandCatalog.guestPrompt(command),
        )

        assertTrue("Dropped Alpine tool call must fail: $result", result.executedToolCalls > 0)
        assertEquals(2, result.modelRequestCount)

        server.takeRequest()
        val followUp = JSONObject(server.takeRequest().body.readUtf8())
        val messages = followUp.getJSONArray("messages")
        val toolMessage = findToolMessage(messages)
        assertTrue("Expected a tool result in the next model request: $messages", toolMessage != null)
        val body = JSONObject(toolMessage!!.optString("content", "{}"))
        assertEquals("proot_distro_qemu", body.optString("sandbox_execution_mode"))
        assertEquals("run", body.optString("action"))
        assertEquals(AlpineAgentCommandCatalog.SANDBOX_NAME, body.optString("sandbox_name"))
        assertEquals(command, body.optString("sandbox_command"))
        assertFalse(
            "Alpine run must not collapse to a host-only /system/bin shell: $body",
            body.optString("sandbox_execution_mode") == "android_system_shell" ||
                body.optString("shell") == "/system/bin/sh" && !body.has("sandbox_execution_mode"),
        )
    }

    @Test
    fun linuxSandboxToolRunUsesTheSameAlpineGuestRoute() {
        val command = "uname -s"
        server.enqueue(
            jsonResponse(
                openaiToolCallPayload(
                    "linux_sandbox_tool",
                    alpineRunArguments(command).put("action", "run"),
                ),
            ),
        )
        server.enqueue(jsonResponse(finalPayload("guest done")))

        val result = client.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "scripted-alpine-router",
            sessionId = "robotest-alpine-linux-sandbox",
            userText = AlpineAgentCommandCatalog.guestPrompt(command),
        )

        assertTrue("Dropped linux_sandbox_tool run must fail: $result", result.executedToolCalls > 0)
        server.takeRequest()
        val followUp = JSONObject(server.takeRequest().body.readUtf8())
        val body = JSONObject(findToolMessage(followUp.getJSONArray("messages"))!!.optString("content", "{}"))
        assertEquals("proot_distro_qemu", body.optString("sandbox_execution_mode"))
        assertEquals(AlpineAgentCommandCatalog.SANDBOX_NAME, body.optString("sandbox_name"))
    }

    @Test
    fun catalogListsAlpine321AsTheSmallGuest() {
        val recommended = HermesLinuxSandboxCatalog.recommendedSandboxIds()
        val ids = (0 until recommended.length()).map { recommended.getString(it) }
        assertTrue(ids.toString(), ids.contains(AlpineAgentCommandCatalog.DISTRO_ID))
        val alpine = HermesLinuxSandboxCatalog.findDistro(AlpineAgentCommandCatalog.DISTRO_ID)
        requireNotNull(alpine)
        assertEquals(AlpineAgentCommandCatalog.SANDBOX_NAME, alpine.getString("name"))
        assertEquals("apk", alpine.getString("package_manager"))
    }

    private fun alpineRunArguments(command: String): JSONObject {
        return JSONObject()
            .put("distro_id", AlpineAgentCommandCatalog.DISTRO_ID)
            .put("name", AlpineAgentCommandCatalog.SANDBOX_NAME)
            .put("command", command)
    }

    private fun findToolMessage(messages: JSONArray): JSONObject? {
        for (index in 0 until messages.length()) {
            val item = messages.optJSONObject(index) ?: continue
            if (item.optString("role") == "tool") return item
        }
        return null
    }

    private fun jsonResponse(body: JSONObject): MockResponse {
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body.toString())
    }

    private fun openaiToolCallPayload(name: String, arguments: JSONObject): JSONObject {
        val message = JSONObject()
            .put("role", "assistant")
            .put("content", JSONObject.NULL)
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call_alpine_1")
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", name)
                                .put("arguments", arguments.toString()),
                        ),
                ),
            )
        return completionPayload(message, "tool_calls")
    }

    private fun finalPayload(content: String): JSONObject {
        return completionPayload(
            JSONObject().put("role", "assistant").put("content", content),
            "stop",
        )
    }

    private fun completionPayload(message: JSONObject, finishReason: String): JSONObject {
        return JSONObject()
            .put("id", "chatcmpl-alpine-router")
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
