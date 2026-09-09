package com.mobilefork.hermesagent

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.mobilefork.hermesagent.api.ChatCompletionRequest
import com.mobilefork.hermesagent.api.ChatMessage
import com.mobilefork.hermesagent.api.HermesSseClient
import com.mobilefork.hermesagent.api.HermesToolActivity
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.McpRuntimeBridge
import com.mobilefork.hermesagent.data.McpRuntimePhase
import com.mobilefork.hermesagent.data.McpSettingsStore
import com.mobilefork.hermesagent.data.SecureSecretsStore
import com.mobilefork.hermesagent.privacy.RemoteProcessingConsentStore
import com.mobilefork.hermesagent.privacy.RemoteProcessingTarget
import com.mobilefork.hermesagent.ui.chat.ChatViewModel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real embedded Python + owned API loop + official MCP SDK; all endpoints are loopback fixtures. */
@RunWith(AndroidJUnit4::class)
class FullMcpRuntimeInstrumentedTest {
    @Test
    fun installedSdkTransportsAndRealPythonAgentPublishToolActivity() {
        assertFalse("This test requires a Full-edition APK", BuildConfig.HERMES_PLAY_EDITION)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsStore = AppSettingsStore(context)
        val originalSettings = settingsStore.load()
        val mcpStore = McpSettingsStore(context)
        val originalMcp = mcpStore.load()
        val configFile = File(mcpStore.configFilePath())
        val configExisted = configFile.exists()
        val secrets = SecureSecretsStore(context)
        val originalKey = secrets.loadApiKey("custom")
        val fixturePath = File(context.cacheDir, "mcp-device-instrumentation.py")
        val toolResultReachedProvider = AtomicBoolean(false)
        val requestWaitingTool = AtomicBoolean(false)
        val unexpectedTitleRequests = AtomicInteger(0)
        val viewModelStore = ViewModelStore()
        var fixture: PyObject? = null
        val endpoint = MockWebServer()
        endpoint.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.endsWith("/models") == true) return MockResponse().setBody(
                    JSONObject().put("data", JSONArray().put(JSONObject().put("id", "mcp-fixture-model"))).toString(),
                )
                val payload = JSONObject(request.body.readUtf8())
                val messages = payload.getJSONArray("messages")
                val toolSeen = (0 until messages.length()).any {
                    val message = messages.getJSONObject(it)
                    message.optString("role") == "tool" && message.optString("content").contains("mcp-device-echo: chat")
                }
                val message = JSONObject().put("role", "assistant")
                if (toolSeen) {
                    toolResultReachedProvider.set(true)
                    message.put("content", "mcp-device-chat-ok")
                } else {
                    val tools = payload.optJSONArray("tools") ?: JSONArray()
                    val prefix = if (requestWaitingTool.get()) "mcp_wait_for_stop_" else "mcp_echo_"
                    val name = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
                        .firstOrNull { it.startsWith(prefix) }
                    if (name == null) {
                        unexpectedTitleRequests.incrementAndGet()
                        return MockResponse().setResponseCode(400).setBody("Owned MCP tool was not in the real agent schema")
                    }
                    message.put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(JSONObject()
                        .put("id", "fixture-call-1").put("type", "function").put("function", JSONObject()
                            .put("name", name).put("arguments", if (requestWaitingTool.get()) "{}" else "{\"text\":\"chat\"}"))))
                }
                val choice = JSONObject().put("index", 0).put("finish_reason", if (toolSeen) "stop" else "tool_calls")
                val response = JSONObject().put("id", "fixture-completion").put("created", 1)
                    .put("model", "mcp-fixture-model").put("choices", JSONArray().put(choice))
                return if (payload.optBoolean("stream")) {
                    choice.put("delta", message)
                    message.optJSONArray("tool_calls")?.getJSONObject(0)?.put("index", 0)
                    response.put("object", "chat.completion.chunk")
                    MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("data: $response\n\ndata: [DONE]\n\n")
                } else {
                    choice.put("message", message)
                    response.put("object", "chat.completion")
                    MockResponse().setHeader("Content-Type", "application/json").setBody(response.toString())
                }
            }
        }
        endpoint.start()
        try {
            HermesRuntimeManager.stop()
            val settings = originalSettings.copy(provider = "custom", baseUrl = endpoint.url("/v1").toString(),
                model = "mcp-fixture-model", onDeviceBackend = "none", offlineAirplaneMode = false)
            settingsStore.save(settings)
            secrets.saveApiKey("custom", "local-instrumentation-fixture-not-a-real-key")
            RemoteProcessingConsentStore(context).accept(requireNotNull(RemoteProcessingTarget.fromSettings(settings)))
            mcpStore.saveExternalMcpEnabled(false)
            mcpStore.saveAdvancedConfigTextAndReload("{\"mcpServers\":{}}")
            val runtime = HermesRuntimeManager.ensureStarted(context)
            assertTrue(runtime.error.orEmpty(), runtime.started)
            mcpStore.saveExternalMcpEnabled(true)
            InstrumentationRegistry.getInstrumentation().context.assets.open("mcp_device_smoke.py").use { input ->
                fixturePath.outputStream().use { input.copyTo(it) }
            }
            fixture = Python.getInstance().getModule("runpy").callAttr("run_path", fixturePath.absolutePath)
            val receipt = JSONObject(fixture.callAttr("__getitem__", "run_device_checks").call().toString())
            assertEquals(receipt.toString(), 3, receipt.getJSONArray("transports").length())
            val url = fixture.callAttr("__getitem__", "start_chat_fixture").call().toString()
            val config = JSONObject().put("mcpServers", JSONObject().put("chat", JSONObject()
                .put("transport", "http").put("url", url)))
            assertTrue(mcpStore.saveAdvancedConfigTextAndReload(config.toString()).success)
            assertEquals(McpRuntimePhase.READY, McpRuntimeBridge.reloadIntoRuntime(context).phase)
            val deltas = StringBuilder()
            val errors = mutableListOf<String>()
            val activity = mutableListOf<HermesToolActivity>()
            var complete = false
            HermesSseClient(requireNotNull(runtime.baseUrl), runtime.apiKey, includeToolActivity = true).streamChatCompletion(
                ChatCompletionRequest("mcp-fixture-model", listOf(ChatMessage("user", "Use the MCP echo tool with text chat.")),
                    sessionId = "device-mcp-chat-${UUID.randomUUID()}"),
                onDelta = { deltas.append(it) }, onComplete = { complete = true }, onError = { errors += it },
                onToolActivity = { activity += it },
            )
            assertTrue(errors.joinToString("\n"), errors.isEmpty())
            assertTrue("Real agent stream did not complete: $deltas", complete)
            assertTrue(deltas.toString(), deltas.contains("mcp-device-chat-ok"))
            assertTrue("The real tool result did not reach the loopback provider", toolResultReachedProvider.get())
            assertTrue("No tool activity crossed the real API SSE route: $activity", activity.size >= 2)
            receipt.put("real_agent_chat", true).put("tool_activity_count", activity.size)
            lateinit var viewModel: ChatViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel = ViewModelProvider(viewModelStore, ViewModelProvider.AndroidViewModelFactory
                    .getInstance(context.applicationContext as android.app.Application))[ChatViewModel::class.java]
                viewModel.startNewConversation()
                viewModel.updateInput("Call the configured MCP echo tool with text chat.")
                viewModel.sendMessage()
            }
            awaitCondition("App chat did not finish") { !viewModel.uiState.value.isSending }
            val chat = viewModel.uiState.value
            assertTrue(chat.error, chat.error.isEmpty())
            assertTrue(chat.messages.toString(), chat.messages.any { it.role == "assistant" && it.content.contains("mcp-device-chat-ok") })
            assertTrue(chat.messages.toString(), chat.messages.any { it.role == "tool_call" && it.content.contains("mcp_echo_") })
            assertTrue(chat.messages.toString(), chat.messages.any { it.role == "tool_result" && it.content.contains("mcp-device-echo: chat") })
            receipt.put("app_chat_timeline", true)
            requestWaitingTool.set(true)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel.startNewConversation()
                viewModel.updateInput("Call the configured MCP waiting tool so I can stop it.")
                viewModel.sendMessage()
            }
            val entered = File(context.filesDir, "hermes-home/workspace/mcp-chat-wait-entered")
            awaitCondition("App chat never entered the real waiting MCP tool") { entered.isFile }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.stopCurrentTask() }
            assertFalse("Stop must immediately retire the UI send", viewModel.uiState.value.isSending)
            awaitCondition("Stop did not unwind the owned MCP connection") {
                fixture.callAttr("__getitem__", "chat_connection_stopped").call().toBoolean()
            }
            receipt.put("app_chat_stop_cleanup", true)
            assertEquals("Android titles must not issue hidden auxiliary AI requests", 0, unexpectedTitleRequests.get())
            receipt.put("auxiliary_title_requests", unexpectedTitleRequests.get())
            File(context.getExternalFilesDir(null), "mcp-device-smoke-receipt.json").writeText(receipt.toString(2))
            mcpStore.saveExternalMcpEnabled(false)
            assertEquals(McpRuntimePhase.DISABLED, McpRuntimeBridge.reloadIntoRuntime(context).phase)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModelStore.clear() }
            mcpStore.saveExternalMcpEnabled(false)
            McpRuntimeBridge.reloadIntoRuntime(context)
            fixture?.callAttr("__getitem__", "stop_chat_fixture")?.call()
            HermesRuntimeManager.stop()
            endpoint.shutdown()
            settingsStore.save(originalSettings)
            secrets.saveApiKey("custom", originalKey)
            mcpStore.saveAdvancedConfigTextAndReload(originalMcp.configText)
            mcpStore.saveExternalMcpEnabled(originalMcp.externalMcpEnabled)
            if (!configExisted) configFile.delete()
            fixturePath.delete()
        }
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 25_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(message, condition())
    }
}
