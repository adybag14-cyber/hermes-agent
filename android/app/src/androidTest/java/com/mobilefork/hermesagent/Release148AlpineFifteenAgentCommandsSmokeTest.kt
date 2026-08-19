package com.mobilefork.hermesagent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilefork.hermesagent.device.AlpineAgentCommandCatalog
import com.mobilefork.hermesagent.device.HermesLinuxSandboxBridge
import com.mobilefork.hermesagent.ui.chat.NativeToolCallingChatClient
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket

@RunWith(AndroidJUnit4::class)
class Release148AlpineFifteenAgentCommandsSmokeTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun fifteenDistinctAgentToolCallsLeaveGuestProofsInAlpine() {
        val status = HermesLinuxSandboxBridge.performAction(app, action = "status")
        val installed = status.optJSONArray("installed_sandboxes")
        val alpine = (0 until (installed?.length() ?: 0))
            .mapNotNull { installed?.optJSONObject(it) }
            .firstOrNull { it.optString("name") == AlpineAgentCommandCatalog.SANDBOX_NAME }
        assumeTrue("A runnable hermes-alpine sandbox is required: $status", alpine != null)
        assertTrue(status.toString(2), alpine!!.optBoolean("android_execution_supported"))

        val start = HermesLinuxSandboxBridge.performAction(
            app,
            action = "start",
            name = AlpineAgentCommandCatalog.SANDBOX_NAME,
        )
        assertEquals(start.toString(2), 0, start.optInt("exit_code", -1))

        val commands = AlpineAgentCommandCatalog.release148Commands
        assertEquals(15, commands.size)
        commands.forEach { entry ->
            HermesLinuxSandboxBridge.performAction(
                context = app,
                action = "run",
                name = AlpineAgentCommandCatalog.SANDBOX_NAME,
                command = "rm -f ${entry.proofFile}",
                timeoutSeconds = 60,
            )
        }

        val port = ServerSocket(0).use { it.localPort }
        val server = ScriptedAlpineServer(port, commands)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val result = NativeToolCallingChatClient(app).send(
                baseUrl = "http://127.0.0.1:$port",
                modelName = "scripted-alpine-15",
                sessionId = "release-148-alpine-15",
                userText = AlpineAgentCommandCatalog.guestPrompt(
                    commands.joinToString("; ") { AlpineAgentCommandCatalog.wrappedGuestCommand(it) },
                ),
            )
            assertEquals(
                "Each of the 15 Alpine commands must be a processed tool call: $result",
                15,
                result.executedToolCalls,
            )
            assertEquals(2, result.modelRequestCount)
        } finally {
            server.stop()
        }

        val identity = HermesLinuxSandboxBridge.performAction(
            context = app,
            action = "run",
            name = AlpineAgentCommandCatalog.SANDBOX_NAME,
            command = "cat /etc/alpine-release",
            timeoutSeconds = 60,
        )
        assertEquals(identity.toString(2), 0, identity.optInt("exit_code", -1))
        assertTrue(identity.toString(2), identity.optString("output").contains(AlpineAgentCommandCatalog.ALPINE_RELEASE_NEEDLE))
        assertEquals("proot_distro_qemu", identity.optString("sandbox_execution_mode"))

        commands.forEach { entry ->
            val proof = HermesLinuxSandboxBridge.performAction(
                context = app,
                action = "run",
                name = AlpineAgentCommandCatalog.SANDBOX_NAME,
                command = "cat ${entry.proofFile}",
                timeoutSeconds = 60,
            )
            assertEquals("guest proof ${entry.id}: ${proof.toString(2)}", 0, proof.optInt("exit_code", -1))
            assertEquals("proot_distro_qemu", proof.optString("sandbox_execution_mode"))
            val output = proof.optString("output")
            if (entry.proofNeedle.isNotBlank()) {
                assertTrue("guest proof ${entry.id} missing ${entry.proofNeedle}: $output", output.contains(entry.proofNeedle))
            } else {
                assertTrue("guest proof ${entry.id} was blank: $proof", output.isNotBlank())
            }
        }
    }

    private class ScriptedAlpineServer(
        port: Int,
        private val commands: List<AlpineAgentCommandCatalog.GuestCommand>,
    ) : NanoHTTPD("127.0.0.1", port) {
        private var requestCount = 0

        override fun serve(session: IHTTPSession): Response {
            return if (session.method == Method.POST && session.uri == "/v1/chat/completions") {
                val files = HashMap<String, String>()
                session.parseBody(files)
                requestCount += 1
                val payload = if (requestCount == 1) toolCallsPayload() else finalPayload()
                newFixedLengthResponse(Response.Status.OK, "application/json", payload.toString())
            } else {
                newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    "application/json",
                    JSONObject().put("error", "not found").toString(),
                )
            }
        }

        private fun toolCallsPayload(): JSONObject {
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
                                        .put("command", AlpineAgentCommandCatalog.wrappedGuestCommand(entry))
                                        .toString(),
                                ),
                        ),
                )
            }
            return completionPayload(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", JSONObject.NULL)
                    .put("tool_calls", toolCalls),
                "tool_calls",
            )
        }

        private fun finalPayload(): JSONObject {
            return completionPayload(
                JSONObject().put("role", "assistant").put("content", "fifteen alpine commands processed"),
                "stop",
            )
        }

        private fun completionPayload(message: JSONObject, finishReason: String): JSONObject {
            return JSONObject()
                .put("id", "chatcmpl-alpine-15-live")
                .put("object", "chat.completion")
                .put("created", System.currentTimeMillis() / 1000)
                .put("model", "scripted-alpine-15")
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
}
