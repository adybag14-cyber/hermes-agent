package com.mobilefork.hermesagent.ui.chat

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
class NativeToolCallDispatchRobotest {
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
    fun openaiToolCallPayloadIsExecutedAndReturnedToTheNextModelRequest() {
        server.enqueue(jsonResponse(openaiToolCallPayload("terminal_tool", JSONObject().put("command", "printf tool-dispatch-ok"))))
        server.enqueue(jsonResponse(finalPayload("tool processed")))

        val result = client.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "scripted-openai-tools",
            sessionId = "robotest-openai-tool-dispatch",
            userText = "Please inspect the workspace and report the tool result.",
        )

        assertTrue("Dropped OpenAI tool_calls must fail: $result", result.executedToolCalls > 0)
        assertEquals(2, result.modelRequestCount)
        assertFalse(result.content.isBlank())
        assertEquals(2, server.requestCount)

        server.takeRequest()
        val followUp = JSONObject(server.takeRequest().body.readUtf8())
        val messages = followUp.getJSONArray("messages").toString()
        assertTrue(messages, messages.contains("\"role\":\"tool\""))
        assertTrue(messages, messages.contains("terminal_tool"))
        assertTrue(messages, messages.contains("exit_code"))
    }

    @Test
    fun xmlTaggedToolCallPayloadIsExecutedAndReturnedToTheNextModelRequest() {
        val xml = """<tool_call>{"name":"terminal_tool","arguments":{"command":"printf xml-tool-ok"}}</tool_call>"""
        server.enqueue(jsonResponse(contentOnlyPayload(xml)))
        server.enqueue(jsonResponse(finalPayload("xml tool processed")))

        val result = client.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "scripted-xml-tools",
            sessionId = "robotest-xml-tool-dispatch",
            userText = "Please inspect the workspace and report the tool result.",
        )

        assertTrue("Dropped XML tool call must fail: $result", result.executedToolCalls > 0)
        assertEquals(2, result.modelRequestCount)

        server.takeRequest()
        val followUp = JSONObject(server.takeRequest().body.readUtf8())
        val messages = followUp.getJSONArray("messages").toString()
        assertTrue(messages, messages.contains("\"role\":\"tool\""))
        assertTrue(messages, messages.contains("terminal_tool"))
    }

    @Test
    fun assistantProseWithoutAToolCallDoesNotCountAsToolExecution() {
        server.enqueue(jsonResponse(finalPayload("I cannot run commands.")))

        val result = client.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "scripted-no-tools",
            sessionId = "robotest-dropped-tool-call",
            userText = "Please inspect the workspace and report the tool result.",
        )

        assertEquals("A reply with no tool_calls must not be treated as executed", 0, result.executedToolCalls)
        assertEquals(1, result.modelRequestCount)
    }

    @Test
    fun parsesOpenAiStyleAndXmlFormsTheLocalBackendsAlreadyEmit() {
        val openai = NativeToolCallingChatClient.parseToolCallContentForTest(
            "",
        )
        assertEquals(0, openai.size)

        val xml = NativeToolCallingChatClient.parseToolCallContentForTest(
            "<tool_call>{\"name\":\"mcp_run_in_proot\",\"arguments\":{\"command\":\"cat /etc/alpine-release\"}}</tool_call>",
        )
        assertEquals("mcp_run_in_proot", xml.single().first)
        assertTrue(xml.single().second, xml.single().second.contains("alpine-release"))
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
                        .put("id", "call_openai_1")
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

    private fun contentOnlyPayload(content: String): JSONObject {
        return completionPayload(
            JSONObject().put("role", "assistant").put("content", content),
            "stop",
        )
    }

    private fun finalPayload(content: String): JSONObject {
        return completionPayload(
            JSONObject().put("role", "assistant").put("content", content),
            "stop",
        )
    }

    private fun completionPayload(message: JSONObject, finishReason: String): JSONObject {
        return JSONObject()
            .put("id", "chatcmpl-robotest")
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
