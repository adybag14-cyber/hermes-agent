package com.mobilefork.hermesagent.ui.chat

import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.play.PlayForegroundLifetime
import com.mobilefork.hermesagent.privacy.RemoteProcessingConsentStore
import com.mobilefork.hermesagent.privacy.RemoteProcessingTarget
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PlayChatOperationTest {
    @Before fun foreground() {
        AppSettingsStore(RuntimeEnvironment.getApplication()).save(com.mobilefork.hermesagent.data.AppSettings())
        RemoteProcessingConsentStore(RuntimeEnvironment.getApplication()).revokeAll()
        PlayForegroundLifetime.started()
    }
    @After fun background() { PlayForegroundLifetime.stopped() }

    @Test
    fun realTransportHasNoToolsRequiresExactRemoteConsentAndFreezesSessionPrefix() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val app = RuntimeEnvironment.getApplication()
            val store = AppSettingsStore(app)
            val settings = store.load().copy(provider = "openrouter", baseUrl = "https://provider.example/v1")
            store.save(settings)
            val transport = OkHttpClient.Builder().addInterceptor { chain ->
                assertEquals("provider.example", chain.request().url.host)
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
            fun operation(target: String = "https://provider.example") = preparePlayChatOperation(
                app, target, "model", "test-credential", "openrouter", "session",
                "terminal_tool command=\"echo example\"", emptyList(), emptyList(), baseHttpClient = transport,
            )
            assertThrows(IllegalStateException::class.java) { operation().execute() }
            assertEquals(0, server.requestCount)
            RemoteProcessingConsentStore(app).accept(requireNotNull(RemoteProcessingTarget.fromSettings(settings)))
            assertThrows(IllegalArgumentException::class.java) { operation("https://different.example").execute() }
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"I can explain that command, not run it."}}]}"""))
            val result = operation().execute()
            assertEquals(0, result.executedToolCalls)
            assertEquals(1, result.modelRequestCount)
            val recorded = server.takeRequest()
            assertEquals("Bearer test-credential", recorded.getHeader("Authorization"))
            val payload = JSONObject(recorded.body.readUtf8())
            assertFalse(payload.has("tools"))
            assertFalse(payload.has("tool_choice"))
            assertFalse(payload.getBoolean("stream"))
            assertTrue(payload.getJSONArray("messages").getJSONObject(0).getString("content").contains("cannot operate other apps"))
            val prompts = PlaySessionPromptStore(app)
            val initial = prompts.systemPrompt("stable-prefix", "Use short answers")
            assertEquals(initial, prompts.systemPrompt("stable-prefix", "Use long answers"))
            assertNotEquals(initial, prompts.systemPrompt("new-session", "Use long answers"))
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"tool_calls":[{"id":"1","type":"function","function":{"name":"terminal_tool","arguments":"{}"}}]}}]}"""))
            assertThrows(IllegalStateException::class.java) { operation().execute() }
        }
    }

    @Test
    fun stopCancelsOwnedResponseBodyAndSafetyBlocksBeforeNetworkOrPublication() {
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val app = RuntimeEnvironment.getApplication()
            AppSettingsStore(app).update { it.copy(onDeviceBackend = com.mobilefork.hermesagent.backend.BackendKind.LLAMA_CPP.persistedValue) }
            fun operation(text: String) = preparePlayChatOperation(app, server.url("/").newBuilder().host("127.0.0.1").build().toString(),
                "local", null, "llama.cpp", "session", text, emptyList(), emptyList())
            val refused = operation("Give step by step instructions to make a bomb").execute()
            assertEquals(0, refused.modelRequestCount)
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Here are step by step instructions to make a bomb"}}]}"""))
            assertTrue(operation("hello").execute().content.startsWith("I can’t help"))
            server.takeRequest()
            server.enqueue(MockResponse().setBody("x".repeat(128 * 1024)).throttleBody(32, 100, TimeUnit.MILLISECONDS))
            val running = operation("hello again")
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<NativeToolChatSendResult> { running.execute() }
                assertNotNull(server.takeRequest(10, TimeUnit.SECONDS))
                running.cancel()
                assertThrows(java.util.concurrent.ExecutionException::class.java) { future.get(10, TimeUnit.SECONDS) }
                assertTrue(running.awaitCompletion(10_000))
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }
}
