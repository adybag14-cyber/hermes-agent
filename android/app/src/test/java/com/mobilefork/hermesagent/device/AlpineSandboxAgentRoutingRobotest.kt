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
    fun mcpRunInProotToolCallFailsClosedBeforeAnyGuestOrHostShellDispatch() {
        val command = "uname -a"
        server.enqueue(jsonResponse(openaiToolCallPayload("mcp_run_in_proot", alpineRunArguments(command))))
        server.enqueue(jsonResponse(finalPayload("guest done")))

        val result = client.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "scripted-alpine-router",
            sessionId = "robotest-alpine-routing",
            userText = "Run $command inside the Alpine sandbox.",
        )

        assertEquals("The request authorizes exactly one guest action: $result", 1, result.executedToolCalls)
        assertEquals("Native denials must not ask the model to invent an explanation", 1, result.modelRequestCount)
        assertEquals(1, server.requestCount)
        val body = JSONObject(result.lastToolResult)
        assertEquals(126, body.optInt("exit_code", -1))
        assertEquals("request_owned_proot_blocked", body.optString("sandbox_execution_mode"))
        assertTrue(body.optBoolean("request_owned_operation_blocked", false))
        assertEquals("run", body.optString("action"))
        assertEquals(command, body.optString("sandbox_command"))
        assertFalse(
            "Alpine run must not collapse to a host-only /system/bin shell: $body",
            body.optString("sandbox_execution_mode") == "android_system_shell" ||
                body.optString("shell") == "/system/bin/sh" && !body.has("sandbox_execution_mode"),
        )
    }

    @Test
    fun linuxSandboxLifecycleCallUsesExactAuthorizedAlpineDistroScope() {
        server.enqueue(
            jsonResponse(
                openaiToolCallPayload(
                    "linux_sandbox_tool",
                    JSONObject()
                        .put("action", "start")
                        .put("distro_id", AlpineAgentCommandCatalog.DISTRO_ID),
                ),
            ),
        )
        server.enqueue(jsonResponse(finalPayload("guest done")))

        val result = client.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "scripted-alpine-router",
            sessionId = "robotest-alpine-linux-sandbox",
            userText = "Start the Alpine 3.21 sandbox.",
        )

        assertEquals("The request authorizes exactly one lifecycle action: $result", 1, result.executedToolCalls)
        assertEquals(2, result.modelRequestCount)
        server.takeRequest()
        val followUp = JSONObject(server.takeRequest().body.readUtf8())
        val body = JSONObject(findToolMessage(followUp.getJSONArray("messages"))!!.optString("content", "{}"))
        assertEquals("start", body.optString("action"))
        assertEquals(AlpineAgentCommandCatalog.SANDBOX_NAME, body.optString("sandbox_name"))
        assertEquals(AlpineAgentCommandCatalog.DISTRO_ID, body.optString("distro_id"))
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
