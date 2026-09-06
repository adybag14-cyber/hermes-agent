package com.mobilefork.hermesagent.ui.chat

import android.content.Context
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.device.ACTION_TYPE_SHELL
import com.mobilefork.hermesagent.device.AutomationPublicationGate
import com.mobilefork.hermesagent.device.HermesAutomationBridge
import com.mobilefork.hermesagent.device.HermesAutomationRecord
import com.mobilefork.hermesagent.device.HermesAutomationStore
import com.mobilefork.hermesagent.device.HermesHyMemoryBridge
import com.mobilefork.hermesagent.device.HermesSystemControlBridge
import com.mobilefork.hermesagent.device.HermesWorkspaceFileBridge
import com.mobilefork.hermesagent.device.TRIGGER_MANUAL
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeToolCallDispatchRobotest {
    private lateinit var server: MockWebServer
    private lateinit var client: NativeToolCallingChatClient
    private lateinit var settingsStore: AppSettingsStore
    private lateinit var originalSettings: AppSettings

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val application = RuntimeEnvironment.getApplication()
        settingsStore = AppSettingsStore(application)
        originalSettings = settingsStore.load()
        settingsStore.save(originalSettings.copy(offlineAirplaneMode = false))
        client = NativeToolCallingChatClient(application)
    }

    @After
    fun tearDown() {
        settingsStore.save(originalSettings)
        server.shutdown()
    }

    @Test
    fun nativeSandboxPolicyDenialDoesNotAskTheModelToExplainOrRetryIt() {
        val blocked = JSONObject()
            .put("exit_code", 126)
            .put("sandbox_execution_mode", "request_owned_proot_blocked")
            .put("request_owned_operation_blocked", true)
            .put("error", "Guest mutations cannot be committed atomically with Stop.")
        val runnerCalls = AtomicInteger()
        val scopedClient = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            linuxSandboxActionRunner = { _, _, _, _, _, _ ->
                runnerCalls.incrementAndGet()
                blocked
            },
        )
        server.enqueue(jsonResponse(openaiToolCallPayload(
            "mcp_run_in_proot", JSONObject().put("command", "uname -a"),
        )))
        server.enqueue(jsonResponse(finalPayload("Invented filesystem ownership explanation")))

        val result = scopedClient.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "scripted",
            sessionId = "sandbox-policy-denial",
            userText = "Run uname -a inside the Alpine sandbox.",
            providerId = "llama.cpp",
        )

        assertEquals(1, runnerCalls.get())
        assertEquals(1, result.executedToolCalls)
        assertEquals(blocked.toString(), result.lastToolResult)
        assertEquals("Native policy denial must not trigger a model follow-up", 1, server.requestCount)
        assertEquals(1, result.modelRequestCount)
        assertFalse(result.content, result.content.contains("Invented"))
        assertEquals(sandboxStopPolicyMessage(
            com.mobilefork.hermesagent.ui.i18n.AppLanguage.fromTag(settingsStore.load().languageTag),
        ), result.content)
    }

    @Test
    fun sandboxRequestABlockedAtClientGateThenStoppedCannotAffectIndependentB() {
        val requestAAtGate = CountDownLatch(1)
        val releaseRequestA = CountDownLatch(1)
        val committedNames = CopyOnWriteArrayList<String>()
        val runner: (
            Context,
            String,
            JSONObject,
            OkHttpClient,
            () -> Boolean,
            AutomationPublicationGate,
        ) -> JSONObject = { _, action, arguments, _, cancellationRequested, publicationGate ->
            if (cancellationRequested()) throw CancellationException("sandbox request stopped")
            val sandboxName = arguments.optString("name")
            val published = publicationGate.publishIfActive {
                committedNames += sandboxName
            }
            if (!published) throw CancellationException("sandbox request stopped before commit")
            JSONObject()
                .put("exit_code", 0)
                .put("action", action)
                .put("sandbox_name", sandboxName)
        }
        val clientA = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            linuxSandboxActionRunner = runner,
            beforeAutomationPublication = {
                requestAAtGate.countDown()
                check(releaseRequestA.await(5, TimeUnit.SECONDS))
            },
        )
        val clientB = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            linuxSandboxActionRunner = runner,
        )
        val requestAFailure = AtomicReference<Throwable?>()
        val workerA = thread(name = "sandbox-client-request-a") {
            try {
                clientA.send(
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    modelName = "backend-must-not-be-used",
                    sessionId = "sandbox-request-a",
                    userText = "linux_sandbox_tool action=start name=sandbox-a",
                    providerId = "llama.cpp",
                )
            } catch (error: Throwable) {
                requestAFailure.set(error)
            }
        }

        assertTrue("request A never reached the client-owned mutation gate", requestAAtGate.await(5, TimeUnit.SECONDS))
        val resultB = clientB.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "backend-must-not-be-used",
            sessionId = "sandbox-request-b",
            userText = "linux_sandbox_tool action=start name=sandbox-b",
            providerId = "llama.cpp",
        )
        assertEquals(1, resultB.executedToolCalls)
        assertEquals(listOf("sandbox-b"), committedNames.toList())

        clientA.cancel()
        releaseRequestA.countDown()
        workerA.join(5_000)

        assertFalse("request A did not unwind", workerA.isAlive)
        assertTrue(requestAFailure.get() is CancellationException)
        assertEquals("cancelled request A crossed or poisoned request B's gate", listOf("sandbox-b"), committedNames.toList())
        assertEquals("typed sandbox calls unexpectedly reached the model backend", 0, server.requestCount)
    }

    @Test
    fun cancellingOneNativeClientStopsOnlyItsRequestOwnedToolHttpCalls() {
        val serverA = MockWebServer()
        val serverB = MockWebServer()
        serverA.start()
        serverB.start()
        try {
            serverA.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            serverB.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val sharedBaseTransport = OkHttpClient.Builder().build()
            val clientA = NativeToolCallingChatClient(
                context = RuntimeEnvironment.getApplication(),
                requestToolHttpClient = sharedBaseTransport,
            )
            val clientB = NativeToolCallingChatClient(
                context = RuntimeEnvironment.getApplication(),
                requestToolHttpClient = sharedBaseTransport,
            )
            val transportA = clientA.requestOwnedToolHttpClientForTest()
            val transportB = clientB.requestOwnedToolHttpClientForTest()
            assertFalse(transportA.dispatcher === transportB.dispatcher)
            val failureA = AtomicReference<Throwable?>(null)
            val failureB = AtomicReference<Throwable?>(null)
            val workerA = thread(name = "native-tool-http-a") {
                failureA.set(
                    runCatching {
                        transportA.newCall(Request.Builder().url(serverA.url("/pkg-search")).build())
                            .execute()
                            .use { response -> response.body?.string() }
                    }.exceptionOrNull(),
                )
            }
            val workerB = thread(name = "native-tool-http-b") {
                failureB.set(
                    runCatching {
                        transportB.newCall(Request.Builder().url(serverB.url("/sandbox-layer")).build())
                            .execute()
                            .use { response -> response.body?.string() }
                    }.exceptionOrNull(),
                )
            }

            assertTrue(
                "request-owned HTTP A never reached its server; alive=${workerA.isAlive}, failure=${failureA.get()}",
                serverA.takeRequest(5, TimeUnit.SECONDS) != null,
            )
            assertTrue(
                "request-owned HTTP B never reached its server; alive=${workerB.isAlive}, failure=${failureB.get()}",
                serverB.takeRequest(5, TimeUnit.SECONDS) != null,
            )
            clientA.cancel()
            workerA.join(5_000L)

            assertFalse("Client A's request-owned tool call remained alive", workerA.isAlive)
            assertTrue("Client A cancellation did not reach its tool HTTP call", failureA.get() != null)
            assertTrue("Cancelling client A also cancelled client B", workerB.isAlive)
            assertEquals(null, failureB.get())

            clientB.cancel()
            workerB.join(5_000L)
            assertFalse("Client B cleanup left its tool HTTP call alive", workerB.isAlive)
            assertTrue(failureB.get() != null)
        } finally {
            serverA.shutdown()
            serverB.shutdown()
        }
    }

    @Test
    fun genericSavedHttpAutomationCancellationIsRequestOwnedAndPublishesNoVariablesOrHistory() {
        val context = RuntimeEnvironment.getApplication()
        val store = com.mobilefork.hermesagent.device.HermesAutomationStore(context)
        val serverA = MockWebServer()
        val serverB = MockWebServer()
        store.clear()
        serverA.start()
        serverB.start()
        val sharedBaseTransport = OkHttpClient.Builder().build()
        val clientA = NativeToolCallingChatClient(context, requestToolHttpClient = sharedBaseTransport)
        val clientB = NativeToolCallingChatClient(context, requestToolHttpClient = sharedBaseTransport)
        try {
            serverA.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            serverB.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            listOf(
                Triple("http-cancel-a", serverA.url("/saved-a").toString(), "HTTP_A"),
                Triple("http-cancel-b", serverB.url("/saved-b").toString(), "HTTP_B"),
            ).forEach { (id, url, prefix) ->
                val created = JSONObject(
                    HermesAutomationBridge.performActionJson(
                        context = context,
                        action = "create_http_request_task",
                        arguments = JSONObject()
                            .put("id", id)
                            .put("method", "GET")
                            .put("url", url)
                            .put("save_status_variable", "${prefix}_STATUS")
                            .put("save_response_variable", "${prefix}_BODY")
                            .put("trigger", "manual"),
                    ),
                )
                assertTrue(created.toString(), created.getBoolean("success"))
            }

            val failureA = AtomicReference<Throwable?>(null)
            val failureB = AtomicReference<Throwable?>(null)
            val workerA = thread(name = "saved-http-automation-a") {
                failureA.set(
                    runCatching {
                        clientA.send(
                            baseUrl = server.url("/").toString().trimEnd('/'),
                            modelName = "backend-must-not-be-used",
                            sessionId = "saved-http-a",
                            userText = "android_automation_tool action=run id=http-cancel-a",
                            providerId = "llama.cpp",
                        )
                    }.exceptionOrNull(),
                )
            }
            val workerB = thread(name = "saved-http-automation-b") {
                failureB.set(
                    runCatching {
                        clientB.send(
                            baseUrl = server.url("/").toString().trimEnd('/'),
                            modelName = "backend-must-not-be-used",
                            sessionId = "saved-http-b",
                            userText = "android_automation_tool action=run id=http-cancel-b",
                            providerId = "llama.cpp",
                        )
                    }.exceptionOrNull(),
                )
            }

            assertTrue(
                "saved HTTP A never reached its server; alive=${workerA.isAlive}, failure=${failureA.get()}",
                serverA.takeRequest(5, TimeUnit.SECONDS) != null,
            )
            assertTrue(
                "saved HTTP B never reached its server; alive=${workerB.isAlive}, failure=${failureB.get()}",
                serverB.takeRequest(5, TimeUnit.SECONDS) != null,
            )
            clientA.cancel()
            workerA.join(5_000L)

            assertFalse("Cancelled saved HTTP automation A remained alive", workerA.isAlive)
            assertTrue("Cancellation did not abort saved HTTP automation A", failureA.get() != null)
            assertTrue("Cancelling A also cancelled saved HTTP automation B", workerB.isAlive)
            assertNull(failureB.get())
            assertEquals(0, server.requestCount)
            listOf("HTTPR", "HTTP_STATUS_CODE", "HTTPD", "HTTP_RESPONSE_BODY", "HTTP_A_STATUS", "HTTP_A_BODY").forEach { name ->
                assertNull("Cancelled request published automation variable $name", store.getVariable(name))
            }
            assertTrue(store.listRunEvents().none { it.automationId == "http-cancel-a" })
            assertNull(store.get("http-cancel-a")?.lastRunEpochMs)

            clientB.cancel()
            workerB.join(5_000L)
            assertFalse("Saved HTTP automation B cleanup remained alive", workerB.isAlive)
            assertTrue(failureB.get() != null)
            assertTrue(store.listRunEvents().none { it.automationId in setOf("http-cancel-a", "http-cancel-b") })
            assertNull(store.get("http-cancel-b")?.lastRunEpochMs)
        } finally {
            clientA.cancel()
            clientB.cancel()
            store.clear()
            serverA.shutdown()
            serverB.shutdown()
        }
    }

    @Test
    fun stopAtSavedHttpPublicationBoundaryRejectsAllOfAWhileIndependentBCommits() {
        val context = RuntimeEnvironment.getApplication()
        val store = HermesAutomationStore(context)
        val serverA = MockWebServer()
        val serverB = MockWebServer()
        val aReachedPublication = CountDownLatch(1)
        val releaseAPublication = CountDownLatch(1)
        val failureA = AtomicReference<Throwable?>(null)
        store.clear()
        serverA.start()
        serverB.start()
        serverA.enqueue(MockResponse().setResponseCode(200).setBody("A_BODY"))
        serverB.enqueue(MockResponse().setResponseCode(200).setBody("B_BODY"))
        val clientA = NativeToolCallingChatClient(
            context = context,
            beforeAutomationPublication = {
                aReachedPublication.countDown()
                check(releaseAPublication.await(5, TimeUnit.SECONDS))
            },
        )
        val clientB = NativeToolCallingChatClient(context)
        try {
            listOf(
                Triple("http-publication-a", serverA.url("/saved-a").toString(), "PUB_A"),
                Triple("http-publication-b", serverB.url("/saved-b").toString(), "PUB_B"),
            ).forEach { (id, url, prefix) ->
                val created = JSONObject(
                    HermesAutomationBridge.performActionJson(
                        context = context,
                        action = "create_http_request_task",
                        arguments = JSONObject()
                            .put("id", id)
                            .put("method", "GET")
                            .put("url", url)
                            .put("save_status_variable", "${prefix}_STATUS")
                            .put("save_response_variable", "${prefix}_BODY")
                            .put("trigger", "manual"),
                    ),
                )
                assertTrue(created.toString(), created.getBoolean("success"))
            }

            val workerA = thread(name = "saved-http-publication-a") {
                failureA.set(
                    runCatching {
                        clientA.send(
                            baseUrl = server.url("/").toString().trimEnd('/'),
                            modelName = "backend-must-not-be-used",
                            sessionId = "saved-http-publication-a",
                            userText = "android_automation_tool action=run id=http-publication-a",
                            providerId = "llama.cpp",
                        )
                    }.exceptionOrNull(),
                )
            }
            assertTrue("request A never reached its publication boundary", aReachedPublication.await(5, TimeUnit.SECONDS))

            val resultB = clientB.send(
                baseUrl = server.url("/").toString().trimEnd('/'),
                modelName = "backend-must-not-be-used",
                sessionId = "saved-http-publication-b",
                userText = "android_automation_tool action=run id=http-publication-b",
                providerId = "llama.cpp",
            )
            assertEquals(1, resultB.executedToolCalls)
            assertEquals(0, resultB.modelRequestCount)

            clientA.cancel()
            releaseAPublication.countDown()
            workerA.join(5_000L)

            assertFalse("request A remained alive after Stop won publication", workerA.isAlive)
            assertTrue("request A published instead of failing cancellation", failureA.get() != null)
            assertNull(store.getVariable("PUB_A_STATUS"))
            assertNull(store.getVariable("PUB_A_BODY"))
            assertNull(store.get("http-publication-a")?.lastRunEpochMs)
            assertTrue(store.listRunEvents().none { it.automationId == "http-publication-a" })

            assertEquals(
                "B response=${resultB.content}; variables=${store.listVariables()}; " +
                    "record=${store.get("http-publication-b")}; events=${store.listRunEvents()}",
                "200",
                store.getVariable("PUB_B_STATUS"),
            )
            assertEquals("B_BODY", store.getVariable("PUB_B_BODY"))
            assertEquals("200", store.getVariable("HTTPR"))
            assertEquals("B_BODY", store.getVariable("HTTPD"))
            assertTrue(store.get("http-publication-b")?.lastRunEpochMs != null)
            assertTrue(store.listRunEvents().any { it.automationId == "http-publication-b" })
            assertEquals("typed requests unexpectedly reached the model backend", 0, server.requestCount)
        } finally {
            releaseAPublication.countDown()
            clientA.cancel()
            clientB.cancel()
            store.clear()
            serverA.shutdown()
            serverB.shutdown()
        }
    }

    @Test
    fun stopAtInjectedDefaultWorkspaceCommitBoundaryRejectsAWhileIndependentBCommits() {
        val context = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory(context.filesDir.toPath(), "hermes-stop-workspace-").toFile()
        val targetA = File(root, "request-a.txt")
        val targetB = File(root, "request-b.txt").apply { writeText("old-") }
        val aReachedCommit = CountDownLatch(1)
        val releaseACommit = CountDownLatch(1)
        val failureA = AtomicReference<Throwable?>(null)
        val clientA = NativeToolCallingChatClient(
            context = context,
            workspaceFileWriter = { writeContext, path, content, append, publicationGate ->
                HermesWorkspaceFileBridge.writeTextJson(
                    context = writeContext,
                    rawPath = path,
                    content = content,
                    append = append,
                    publicationGate = publicationGate,
                )
            },
            beforeAutomationPublication = {
                aReachedCommit.countDown()
                check(releaseACommit.await(5, TimeUnit.SECONDS))
            },
        )
        val clientB = NativeToolCallingChatClient(context)
        val workerA = thread(name = "workspace-final-commit-a", isDaemon = true) {
            failureA.set(
                runCatching {
                    clientA.send(
                        baseUrl = server.url("/").toString().trimEnd('/'),
                        modelName = "backend-must-not-be-used",
                        sessionId = "workspace-final-commit-a",
                        userText = "file_write_tool path=\"${targetA.absolutePath}\" content=A-owned",
                        providerId = "llama.cpp",
                    )
                }.exceptionOrNull(),
            )
        }
        try {
            assertTrue("request A never reached the staged file's final commit", aReachedCommit.await(5, TimeUnit.SECONDS))
            clientA.cancel()

            val resultB = clientB.send(
                baseUrl = server.url("/").toString().trimEnd('/'),
                modelName = "backend-must-not-be-used",
                sessionId = "workspace-final-commit-b",
                userText = "file_write_tool path=\"${targetB.absolutePath}\" content=B-owned append=true",
                providerId = "llama.cpp",
            )
            assertEquals(1, resultB.executedToolCalls)
            assertEquals(0, resultB.modelRequestCount)

            releaseACommit.countDown()
            workerA.join(5_000L)

            assertFalse("request A remained alive after Stop won the file commit", workerA.isAlive)
            assertTrue("request A returned success after Stop won the file commit", failureA.get() != null)
            assertFalse("request A published durable file bytes after Stop", targetA.exists())
            assertEquals("old-B-owned", targetB.readText())
            assertEquals("typed requests unexpectedly reached the model backend", 0, server.requestCount)
        } finally {
            releaseACommit.countDown()
            clientA.cancel()
            clientB.cancel()
            workerA.join(5_000L)
            root.deleteRecursively()
        }
    }

    @Test
    fun stopAtAndroidSystemCommitBoundaryRejectsAWhileIndependentBCommits() {
        val context = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "hermes-stop-system-").toFile()
        val aReachedCommit = CountDownLatch(1)
        val releaseACommit = CountDownLatch(1)
        val failureA = AtomicReference<Throwable?>(null)
        val systemRunner: (Context, String, JSONObject, AutomationPublicationGate) -> String =
            { _, action, _, publicationGate ->
                val published = publicationGate.publishIfActive {
                    File(root, "$action.marker").writeText(action)
                }
                JSONObject()
                    .put("success", published)
                    .put("cancelled", !published)
                    .put("action", action)
                    .toString()
            }
        val clientA = NativeToolCallingChatClient(
            context = context,
            androidSystemActionRunner = systemRunner,
            beforeAutomationPublication = {
                aReachedCommit.countDown()
                check(releaseACommit.await(5, TimeUnit.SECONDS))
            },
        )
        val clientB = NativeToolCallingChatClient(
            context = context,
            androidSystemActionRunner = systemRunner,
        )
        val workerA = thread(name = "android-system-final-commit-a", isDaemon = true) {
            failureA.set(
                runCatching {
                    clientA.send(
                        baseUrl = server.url("/").toString().trimEnd('/'),
                        modelName = "backend-must-not-be-used",
                        sessionId = "android-system-final-commit-a",
                        userText = "android_system_tool action=open_wifi_panel",
                        providerId = "llama.cpp",
                    )
                }.exceptionOrNull(),
            )
        }
        try {
            assertTrue("request A never reached the Android system commit", aReachedCommit.await(5, TimeUnit.SECONDS))
            clientA.cancel()

            val resultB = clientB.send(
                baseUrl = server.url("/").toString().trimEnd('/'),
                modelName = "backend-must-not-be-used",
                sessionId = "android-system-final-commit-b",
                userText = "android_system_tool action=open_all_settings",
                providerId = "llama.cpp",
            )
            assertEquals(1, resultB.executedToolCalls)
            assertEquals(0, resultB.modelRequestCount)

            releaseACommit.countDown()
            workerA.join(5_000L)

            assertFalse("request A remained alive after Stop won the system commit", workerA.isAlive)
            assertTrue("request A returned success after Stop won the system commit", failureA.get() != null)
            assertFalse(File(root, "open_wifi_panel.marker").exists())
            assertEquals("open_all_settings", File(root, "open_all_settings.marker").readText())
            assertEquals("typed requests unexpectedly reached the model backend", 0, server.requestCount)
        } finally {
            releaseACommit.countDown()
            clientA.cancel()
            clientB.cancel()
            workerA.join(5_000L)
            root.deleteRecursively()
        }
    }

    @Test
    fun stopAtDefaultHyMemoryCommitBoundaryRejectsAWhileIndependentBCommits() {
        val context = RuntimeEnvironment.getApplication()
        val memoryPrefs = context.getSharedPreferences("hermes_hindsight_memory", Context.MODE_PRIVATE)
        val originalEntries = memoryPrefs.getString("entries_json", null)
        memoryPrefs.edit().remove("entries_json").commit()
        val aReachedCommit = CountDownLatch(1)
        val releaseACommit = CountDownLatch(1)
        val failureA = AtomicReference<Throwable?>(null)
        val clientA = NativeToolCallingChatClient(
            context = context,
            beforeAutomationPublication = {
                aReachedCommit.countDown()
                check(releaseACommit.await(5, TimeUnit.SECONDS))
            },
        )
        val clientB = NativeToolCallingChatClient(context)
        val workerA = thread(name = "hy-memory-final-commit-a", isDaemon = true) {
            failureA.set(
                runCatching {
                    clientA.send(
                        baseUrl = server.url("/").toString().trimEnd('/'),
                        modelName = "backend-must-not-be-used",
                        sessionId = "hy-memory-final-commit-a",
                        userText = "memory_add content=A-stop-owned-memory",
                        providerId = "llama.cpp",
                    )
                }.exceptionOrNull(),
            )
        }
        try {
            assertTrue("request A never reached the HY Memory commit", aReachedCommit.await(5, TimeUnit.SECONDS))
            clientA.cancel()

            val resultB = clientB.send(
                baseUrl = server.url("/").toString().trimEnd('/'),
                modelName = "backend-must-not-be-used",
                sessionId = "hy-memory-final-commit-b",
                userText = "memory_add content=B-independent-memory",
                providerId = "llama.cpp",
            )
            assertEquals(1, resultB.executedToolCalls)
            assertEquals(0, resultB.modelRequestCount)

            releaseACommit.countDown()
            workerA.join(5_000L)

            val memories = HermesHyMemoryBridge.performActionJson(context, "list")
            assertFalse("request A remained alive after Stop won the memory commit", workerA.isAlive)
            assertTrue("request A returned success after Stop won the memory commit", failureA.get() != null)
            assertFalse(memories, memories.contains("A-stop-owned-memory"))
            assertTrue(memories, memories.contains("B-independent-memory"))
            assertEquals("typed requests unexpectedly reached the model backend", 0, server.requestCount)
        } finally {
            releaseACommit.countDown()
            clientA.cancel()
            clientB.cancel()
            workerA.join(5_000L)
            val editor = memoryPrefs.edit()
            if (originalEntries == null) editor.remove("entries_json") else editor.putString("entries_json", originalEntries)
            editor.commit()
        }
    }

    @Test
    fun stopAtOpenGuiWorkingMemoryCommitRejectsAWhileIndependentBCommits() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("hermes_opengui_working_memory", Context.MODE_PRIVATE)
        val unique = System.nanoTime().toString()
        val sessionA = "ui-final-commit-a-$unique"
        val sessionB = "ui-final-commit-b-$unique"
        val keyA = "session_$sessionA"
        val keyB = "session_$sessionB"
        prefs.edit().remove(keyA).remove(keyB).commit()
        val aPublicationCount = AtomicInteger(0)
        val aReachedCommit = CountDownLatch(1)
        val releaseACommit = CountDownLatch(1)
        val failureA = AtomicReference<Throwable?>(null)
        val clientA = NativeToolCallingChatClient(
            context = context,
            beforeAutomationPublication = {
                // Parsing first offers an empty optional screen-hash publication. The second
                // boundary is the irreversible SharedPreferences working-memory commit itself.
                if (aPublicationCount.incrementAndGet() == 2) {
                    aReachedCommit.countDown()
                    check(releaseACommit.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val clientB = NativeToolCallingChatClient(context)
        val workerA = thread(name = "opengui-memory-final-commit-a", isDaemon = true) {
            failureA.set(
                runCatching {
                    clientA.send(
                        baseUrl = server.url("/").toString().trimEnd('/'),
                        modelName = "backend-must-not-be-used",
                        sessionId = sessionA,
                        userText = "android_ui_tool action=opengui_action raw_action=\"Action: update_working_memory(content='A-stop-owned-ui')\"",
                        providerId = "llama.cpp",
                    )
                }.exceptionOrNull(),
            )
        }
        try {
            assertTrue("request A never reached the OpenGUI working-memory commit", aReachedCommit.await(5, TimeUnit.SECONDS))
            clientA.cancel()

            val resultB = clientB.send(
                baseUrl = server.url("/").toString().trimEnd('/'),
                modelName = "backend-must-not-be-used",
                sessionId = sessionB,
                userText = "android_ui_tool action=opengui_action raw_action=\"Action: update_working_memory(content='B-independent-ui')\"",
                providerId = "llama.cpp",
            )
            assertEquals(1, resultB.executedToolCalls)
            assertEquals(0, resultB.modelRequestCount)

            releaseACommit.countDown()
            workerA.join(5_000L)

            assertFalse("request A remained alive after Stop won the UI commit", workerA.isAlive)
            assertTrue("request A returned success after Stop won the UI commit", failureA.get() != null)
            assertNull("request A mutated durable OpenGUI working memory after Stop", prefs.getString(keyA, null))
            assertEquals("B-independent-ui", prefs.getString(keyB, null))
            assertEquals("typed requests unexpectedly reached the model backend", 0, server.requestCount)
        } finally {
            releaseACommit.countDown()
            clientA.cancel()
            clientB.cancel()
            workerA.join(5_000L)
            prefs.edit().remove(keyA).remove(keyB).commit()
        }
    }

    @Test
    fun turboQuantLlamaNativeChatUsesBearerAndSuppressesReasoningButLiteRtDoesNot() {
        settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "turboquant"))
        server.enqueue(jsonResponse(finalPayload("NANBEIGE_OK\n</think>\n\nNANBEIGE_OK")))

        val llamaResult = client.send(
            baseUrl = server.url("/v1/").toString().trimEnd('/'),
            modelName = "nanbeige-model",
            apiKey = "owned-loopback-token",
            sessionId = "robotest-nanbeige-native-chat",
            userText = "Say hello.",
            providerId = "llama.cpp",
        )

        assertEquals("NANBEIGE_OK", llamaResult.content)
        val llamaRequest = server.takeRequest()
        assertEquals("/v1/chat/completions", llamaRequest.path)
        assertEquals("Bearer owned-loopback-token", llamaRequest.getHeader("Authorization"))
        val llamaPayload = JSONObject(llamaRequest.body.readUtf8())
        assertEquals("none", llamaPayload.getString("reasoning_format"))
        assertFalse(
            llamaPayload.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"),
        )
        assertFalse(llamaPayload.has("tools"))

        server.enqueue(jsonResponse(finalPayload("litert visible answer")))
        client.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "litert-model",
            sessionId = "robotest-litert-native-chat",
            userText = "Say hello.",
            providerId = "litert-lm",
        )
        val liteRtPayload = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(liteRtPayload.has("reasoning_format"))
        assertFalse(liteRtPayload.has("tools"))
    }

    @Test
    fun openaiToolCallPayloadIsExecutedAndReturnedToTheNextModelRequest() {
        server.enqueue(jsonResponse(openaiToolCallPayload("android_device_diagnostics_tool", JSONObject().put("action", "status"))))
        server.enqueue(jsonResponse(finalPayload("tool processed")))

        val result = client.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "scripted-openai-tools",
            sessionId = "robotest-openai-tool-dispatch",
            userText = "Show the current device status.",
        )

        assertTrue("Dropped OpenAI tool_calls must fail: $result", result.executedToolCalls > 0)
        assertEquals(2, result.modelRequestCount)
        assertFalse(result.content.isBlank())
        assertEquals(2, server.requestCount)

        server.takeRequest()
        val followUp = JSONObject(server.takeRequest().body.readUtf8())
        val messages = followUp.getJSONArray("messages").toString()
        assertTrue(messages, messages.contains("\"role\":\"tool\""))
        assertTrue(messages, messages.contains("android_device_diagnostics_tool"))
        assertTrue(messages, messages.contains("device"))
    }

    @Test
    fun xmlTaggedToolCallPayloadIsExecutedAndReturnedToTheNextModelRequest() {
        val xml = """<tool_call>{"name":"android_device_diagnostics_tool","arguments":{"action":"status"}}</tool_call>"""
        server.enqueue(jsonResponse(contentOnlyPayload(xml)))
        server.enqueue(jsonResponse(finalPayload("xml tool processed")))

        val result = client.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "scripted-xml-tools",
            sessionId = "robotest-xml-tool-dispatch",
            userText = "Show the current device status.",
        )

        assertTrue("Dropped XML tool call must fail: $result", result.executedToolCalls > 0)
        assertEquals(2, result.modelRequestCount)

        server.takeRequest()
        val followUp = JSONObject(server.takeRequest().body.readUtf8())
        val messages = followUp.getJSONArray("messages").toString()
        assertTrue(messages, messages.contains("\"role\":\"tool\""))
        assertTrue(messages, messages.contains("android_device_diagnostics_tool"))
    }

    @Test
    fun turboQuantOrdinaryPromptBlocksUnofferedOpenAiFileWriteCallBeforeDispatch() {
        settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "turboquant"))
        val dispatches = AtomicInteger(0)
        val guardedClient = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            onToolDispatch = { dispatches.incrementAndGet() },
        )
        server.enqueue(
            jsonResponse(
                openaiToolCallPayload(
                    "file_write_tool",
                    JSONObject().put("path", "unoffered.txt").put("content", "must not be written"),
                ),
            ),
        )

        val result = guardedClient.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "nanbeige-unoffered-json",
            sessionId = "robotest-unoffered-file-write",
            userText = "Reply exactly NANBEIGE_OK",
            providerId = "llama.cpp",
        )

        assertEquals(0, result.executedToolCalls)
        assertEquals(0, dispatches.get())
        assertEquals(1, result.modelRequestCount)
        assertEquals(1, server.requestCount)
        assertTrue(result.content.contains("blocked a model-requested action"))
    }

    @Test
    fun turboQuantOrdinaryPromptBlocksUnofferedXmlTerminalCallBeforeDispatch() {
        settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "turboquant"))
        val dispatches = AtomicInteger(0)
        val guardedClient = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            onToolDispatch = { dispatches.incrementAndGet() },
        )
        server.enqueue(
            jsonResponse(
                contentOnlyPayload(
                    """<tool_call>{"name":"terminal_tool","arguments":{"command":"printf must-not-run"}}</tool_call>""",
                ),
            ),
        )

        val result = guardedClient.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "nanbeige-unoffered-xml",
            sessionId = "robotest-unoffered-terminal",
            userText = "Reply exactly NANBEIGE_OK",
            providerId = "llama.cpp",
        )

        assertEquals(0, result.executedToolCalls)
        assertEquals(0, dispatches.get())
        assertEquals(1, result.modelRequestCount)
        assertEquals(1, server.requestCount)
        assertTrue(result.content.contains("blocked a model-requested action"))
    }

    @Test
    fun turboQuantContextRecoveryKeepsOnlyTheConservativeReminderSchema() {
        settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "turboquant"))
        server.enqueue(contextOverflowResponse())
        server.enqueue(jsonResponse(finalPayload("Reminder route recovered")))

        client.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "nanbeige-context-recovery",
            sessionId = "robotest-reminder-context-recovery",
            userText = "Remind me at 08:30 to call Alice.",
            providerId = "llama.cpp",
        )

        val initialPayload = JSONObject(server.takeRequest().body.readUtf8())
        val recoveryPayload = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(listOf("schedule_task"), requestToolNames(initialPayload))
        assertEquals(listOf("schedule_task"), requestToolNames(recoveryPayload))
    }

    @Test
    fun turboQuantNoIntentContextRecoveryKeepsToolsEmpty() {
        settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "turboquant"))
        server.enqueue(contextOverflowResponse())
        server.enqueue(jsonResponse(finalPayload("Explanation route recovered")))

        client.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "nanbeige-context-recovery",
            sessionId = "robotest-explanation-context-recovery",
            userText = "Explain how to create a file",
            providerId = "llama.cpp",
        )

        val initialPayload = JSONObject(server.takeRequest().body.readUtf8())
        val recoveryPayload = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(initialPayload.has("tools"))
        assertFalse(recoveryPayload.has("tools"))
    }

    @Test
    fun turboQuantContradictoryNaturalActionsStayEmptyOnInitialAndRecoveryAndCannotDispatch() {
        settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "turboquant"))
        val dispatchedNames = mutableListOf<String>()
        val guardedClient = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            onToolDispatch = { dispatchedNames += it },
        )
        val prompts = listOf(
            "Create a file named pwned.txt, but not now",
            "Create a file named pwned.txt. Actually no",
            "Crea un archivo llamado pwned.txt, pero no lo hagas",
            "Erstelle eine Datei namens pwned.txt, aber erstelle sie nicht",
            "Crie um arquivo chamado pwned.txt, mas não o crie",
            "Crée un fichier nommé pwned.txt, mais ne le crée pas",
            "Delete file notes.txt",
            "Edit this file notes.txt",
            "删除文件 notes.txt",
            "Elimina este archivo notes.txt",
            "Lösche diese Datei notes.txt",
            "Remova este arquivo notes.txt",
            "Supprime ce fichier notes.txt",
        )

        prompts.forEachIndexed { index, prompt ->
            server.enqueue(contextOverflowResponse())
            server.enqueue(
                jsonResponse(
                    openaiToolCallPayload(
                        "file_write_tool",
                        JSONObject().put("path", "natural-tail-$index.txt").put("content", "pwned"),
                    ),
                ),
            )

            val result = guardedClient.send(
                baseUrl = server.url("/").toString().trimEnd('/'),
                modelName = "nanbeige-natural-tail-authority",
                sessionId = "robotest-natural-tail-$index",
                userText = prompt,
                providerId = "llama.cpp",
            )
            assertEquals("contradictory action dispatched for '$prompt'", 0, result.executedToolCalls)

            val initialPayload = JSONObject(server.takeRequest().body.readUtf8())
            val recoveryPayload = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse("initial schemas leaked for '$prompt'", initialPayload.has("tools"))
            assertFalse("recovery schemas leaked for '$prompt'", recoveryPayload.has("tools"))
        }

        assertTrue("contradictory natural actions reached dispatch: $dispatchedNames", dispatchedNames.isEmpty())
    }

    @Test
    fun turboQuantEmbeddedNegationAndChineseCancelKeepOnlyTheirExactSchemasOnRecovery() {
        settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "turboquant"))
        val cases = listOf(
            "Remind me at 08:30 not to forget my medicine" to listOf("schedule_task"),
            "提醒我 08:30 不要忘记吃药" to listOf("schedule_task"),
            "取消任务 42" to listOf("cancel_task"),
            "Create a file named cannot-connect.md" to listOf("file_write_tool"),
        )

        cases.forEachIndexed { index, (prompt, expectedNames) ->
            server.enqueue(contextOverflowResponse())
            server.enqueue(jsonResponse(finalPayload("Recovered $index")))

            client.send(
                baseUrl = server.url("/").toString().trimEnd('/'),
                modelName = "nanbeige-localized-authority",
                sessionId = "robotest-localized-authority-$index",
                userText = prompt,
                providerId = "llama.cpp",
            )

            val initialPayload = JSONObject(server.takeRequest().body.readUtf8())
            val recoveryPayload = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("initial schema mismatch for '$prompt'", expectedNames, requestToolNames(initialPayload))
            assertEquals("recovery schema mismatch for '$prompt'", expectedNames, requestToolNames(recoveryPayload))
        }
    }

    @Test
    fun turboQuantGoverningReminderNegationStaysEmptyOnRecoveryAndCannotDispatch() {
        settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "turboquant"))
        val dispatchedNames = mutableListOf<String>()
        val guardedClient = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            onToolDispatch = { dispatchedNames += it },
        )
        val prompts = listOf(
            "Do not schedule a reminder for tomorrow",
            "不要安排明天的提醒",
            "Remind me at 08:30 not to forget medicine, but do not schedule anything",
            "提醒我 08:30 不要忘记吃药，但是不要安排任何提醒",
            "Recuérdame a las 08:30 no olvidar la medicina, pero no programes nada",
            "Erinnere mich um 08:30 daran, die Medizin nicht zu vergessen, aber plane es nicht",
            "Lembre-me às 08:30 para não esquecer o remédio, mas não agende nada",
            "Rappelle-moi à 08:30 de ne pas oublier le médicament, mais ne programme rien",
        )

        prompts.forEachIndexed { index, prompt ->
            server.enqueue(contextOverflowResponse())
            server.enqueue(
                jsonResponse(
                    openaiToolCallPayload(
                        "schedule_task",
                        JSONObject().put("task", "must not schedule $index"),
                    ),
                ),
            )
            val result = guardedClient.send(
                baseUrl = server.url("/").toString().trimEnd('/'),
                modelName = "nanbeige-governing-negation",
                sessionId = "robotest-governing-negation-$index",
                userText = prompt,
                providerId = "llama.cpp",
            )
            assertEquals(0, result.executedToolCalls)
            assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("tools"))
            assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("tools"))
        }

        assertTrue("governing reminder negation reached dispatch: $dispatchedNames", dispatchedNames.isEmpty())
    }

    @Test
    fun negatedAndMetaExplicitFormsNeverReachADirectSideEffect() {
        val dispatches = AtomicInteger(0)
        val guardedClient = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            onToolDispatch = { dispatches.incrementAndGet() },
        )
        val prompts = listOf(
            "Do not use terminal_tool. run command: printf pwned",
            "Do not use file_write_tool; write \"negated.txt\" with content pwned",
            "Do not install linux_sandbox_tool",
            "Explain what android_system_tool action=status does",
            "Use terminal_tool? No, do not. run command: printf pwned",
            "Use file_write_tool? No, do not. write \"negated.txt\" with content pwned",
            "Use linux_sandbox_tool? No, do not. action=uninstall distro_id=alpine-3-21",
            "terminal_tool must not run command: printf pwned",
            "file_write_tool must not write negated.txt with content pwned",
            "linux_sandbox_tool must not action=install distro_id=alpine-3-21",
            "Use terminal_tool to run touch pwned.txt but do not execute it",
            "terminal_tool run command: rm -f victim.txt but not now",
            "Use terminal_tool? No, no lo ejecutes. run command: printf pwned",
            "Use terminal_tool? Nein, nicht ausführen. run command: printf pwned",
            "Use terminal_tool? Não execute. run command: printf pwned",
            "Use terminal_tool? Non, ne l'exécute pas. run command: printf pwned",
            "Use terminal_tool? 不要执行。run command: printf pwned",
            "`terminal_tool command=\"printf pwned\"`",
            "Show me what my friend meant when she said open the browser.",
            "Do not use android_device_diagnostics_tool action=wifi_scan",
            "Explain what an all features test does",
            "file_write_tool path=pwned.txt content=pwned do_not_write=true",
            "linux_sandbox_tool action=install distro_id=alpine-3-21 do_not_execute=true",
            "Create a game. Use file_write_tool path=game.html content=x. Then use android_automation_tool action=open_uri.",
        )

        prompts.forEachIndexed { index, prompt ->
            server.enqueue(jsonResponse(finalPayload("No action $index")))
            val result = guardedClient.send(
                baseUrl = server.url("/").toString().trimEnd('/'),
                modelName = "direct-authority-guard",
                sessionId = "robotest-negated-direct-$index",
                userText = prompt,
                providerId = "llama.cpp",
            )
            assertEquals(0, result.executedToolCalls)
        }

        assertEquals("a negated/meta request reached a direct tool boundary", 0, dispatches.get())
        assertEquals(prompts.size, server.requestCount)
    }

    @Test
    fun quotedTerminalAuthorityDispatchesOnlyTheLeadingTerminalTool() {
        val dispatchedNames = mutableListOf<String>()
        val guardedClient = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            onToolDispatch = { dispatchedNames += it },
        )

        val result = guardedClient.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "direct-leading-tool-authority",
            sessionId = "robotest-leading-terminal-only",
            userText = "terminal_tool command=\"printf file_write_tool write escalated.txt with content pwned\"",
            providerId = "llama.cpp",
        )

        assertEquals(1, result.executedToolCalls)
        assertEquals(listOf("terminal_tool"), dispatchedNames)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun typedNativeAuthoritiesExecuteExactOnceWithoutAnyBackendRequest() {
        val dispatched = mutableListOf<String>()
        val directClient = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            onToolDispatch = { dispatched += it },
        )
        val prompts = listOf(
            "android_system_tool action=status",
            "android_ui_tool action=status",
            "android_automation_tool action=list",
            "memory_search query=alice",
            "linux_host_pkg_tool action=status",
            "file_write_tool path=typed-direct-authority.txt content=ok",
        )

        prompts.forEachIndexed { index, prompt ->
            val result = directClient.send(
                baseUrl = server.url("/").toString().trimEnd('/'),
                modelName = "backend-must-not-be-used",
                sessionId = "typed-direct-$index",
                userText = prompt,
                providerId = "llama.cpp",
            )
            assertEquals("typed authority did not dispatch exactly once: $prompt", 1, result.executedToolCalls)
            assertEquals("typed authority reached the model backend: $prompt", 0, result.modelRequestCount)
        }

        assertEquals(0, server.requestCount)
        assertEquals(prompts.size, dispatched.size)
    }

    @Test
    fun typedPrivilegedShellAliasesFailClosedBeforeAnyAndroidSystemBridgeDispatch() {
        val androidSystemBridgeDispatches = AtomicInteger(0)
        val directClient = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            androidSystemActionRunner = { _, _, _, _ ->
                androidSystemBridgeDispatches.incrementAndGet()
                JSONObject().put("success", true).toString()
            },
        )

        listOf("run_privileged_shell", "shizuku_shell", "privileged_shell").forEach { action ->
            val result = directClient.send(
                baseUrl = server.url("/").toString().trimEnd('/'),
                modelName = "backend-must-not-be-used",
                sessionId = "typed-$action-blocked",
                userText = "android_system_tool action=$action command=\"id\"",
                providerId = "llama.cpp",
            )

            assertEquals("typed $action did not terminate as one exact tool call", 1, result.executedToolCalls)
            assertEquals("typed $action reached the model backend", 0, result.modelRequestCount)
            assertTrue(result.content, result.content.contains("blocked in chat", ignoreCase = true))
        }

        assertEquals("A blocked privileged shell reached the Android system bridge", 0, androidSystemBridgeDispatches.get())
        assertEquals(0, server.requestCount)

        val gatedBridgePublications = AtomicInteger(0)
        val gatedResult = HermesSystemControlBridge.performAction(
            context = RuntimeEnvironment.getApplication(),
            action = "force_stop_app",
            publicationGate = AutomationPublicationGate { publication ->
                gatedBridgePublications.incrementAndGet()
                publication()
                true
            },
        )
        assertFalse("A request-owned bridge admitted an uncancellable Shizuku action", gatedResult.success)
        assertTrue(gatedResult.message, gatedResult.message.contains("cannot be cancelled", ignoreCase = true))
        assertEquals("A blocked Shizuku action entered the request publication gate", 0, gatedBridgePublications.get())
    }

    @Test
    fun chatOwnedSavedShizukuShellFailsClosedThroughTheComposedAutomationPath() {
        val context = RuntimeEnvironment.getApplication()
        val store = HermesAutomationStore(context)
        store.clear()
        val record = HermesAutomationRecord(
            id = "chat-saved-shizuku-block",
            label = "Chat saved Shizuku shell",
            actionType = ACTION_TYPE_SHELL,
            command = "id",
            useShizuku = true,
            triggerType = TRIGGER_MANUAL,
            intervalMinutes = null,
            enabled = true,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        store.upsert(record)
        try {
            val result = client.send(
                baseUrl = server.url("/").toString().trimEnd('/'),
                modelName = "backend-must-not-be-used",
                sessionId = "typed-saved-shizuku-blocked",
                userText = "android_automation_tool action=run id=${record.id}",
                providerId = "llama.cpp",
            )

            assertEquals(1, result.executedToolCalls)
            assertEquals(0, result.modelRequestCount)
            assertTrue(result.content, result.content.contains("blocked in chat", ignoreCase = true))
            assertEquals(0, server.requestCount)
        } finally {
            store.clear()
        }
    }

    @Test
    fun typedTerminalCarriesExactTimeoutAndFollowingLaneUsable() {
        val observedTimeouts = mutableListOf<Long>()
        val terminalClient = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            nativeShellRunner = { _, command, timeoutSeconds, _, _ ->
                observedTimeouts += timeoutSeconds
                JSONObject()
                    .put("exit_code", if (command == "sleep 2") 124 else 0)
                    .put("output", if (command == "pwd") "/workspace" else "")
                    .put("error", if (command == "sleep 2") "Command timed out" else "")
                    .put("execution_mode", "test_request_owned_native_shell")
            },
        )
        val timeout = terminalClient.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "backend-must-not-be-used",
            sessionId = "typed-timeout",
            userText = "terminal_tool command=\"sleep 2\" timeout_seconds=1",
            providerId = "llama.cpp",
        )
        assertEquals(1, timeout.executedToolCalls)
        assertEquals(0, timeout.modelRequestCount)
        assertEquals("typed timeout_seconds was not passed unchanged to the native runner", listOf(1L), observedTimeouts)
        assertTrue("native timeout result was not preserved: ${timeout.content}", timeout.content.contains("timed out", ignoreCase = true))

        val following = terminalClient.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "backend-must-not-be-used",
            sessionId = "typed-timeout-followup",
            userText = "terminal_tool command=\"pwd\"",
            providerId = "llama.cpp",
        )
        assertEquals(1, following.executedToolCalls)
        assertEquals(0, following.modelRequestCount)
        assertEquals(listOf(1L, 60L), observedTimeouts)
        assertFalse("following terminal request was not independently usable: ${following.content}", following.content.contains("exit_code\":125"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun requestScopeBlocksSameNameWrongActionsBeforeAnyDispatch() {
        settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "turboquant"))
        val dispatches = AtomicInteger(0)
        val scopedClient = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            onToolDispatch = { dispatches.incrementAndGet() },
        )
        val cases = listOf(
            Triple(
                "Take a screenshot of the current screen.",
                "android_ui_tool",
                JSONObject().put("action", "click").put("text_contains", "Delete"),
            ),
            Triple(
                "Show the current device status.",
                "android_device_diagnostics_tool",
                JSONObject().put("action", "wifi_scan"),
            ),
            Triple(
                "Open the browser https://example.com.",
                "android_automation_tool",
                JSONObject().put("action", "delete").put("id", "victim"),
            ),
        )

        cases.forEachIndexed { index, (prompt, name, arguments) ->
            server.enqueue(jsonResponse(openaiToolCallPayload(name, arguments)))
            val result = scopedClient.send(
                baseUrl = server.url("/").toString().trimEnd('/'),
                modelName = "wrong-action-$index",
                sessionId = "wrong-action-$index",
                userText = prompt,
                providerId = "llama.cpp",
            )
            assertEquals("same-name wrong action dispatched for '$prompt'", 0, result.executedToolCalls)
            assertTrue(result.content.contains("outside this request's exact tool scope"))
        }
        assertEquals(0, dispatches.get())
    }

    @Test
    fun modelReturnedAliasCannotSatisfyAnOfferedCanonicalToolContract() {
        settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "turboquant"))
        val dispatches = AtomicInteger(0)
        val scopedClient = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            onToolDispatch = { dispatches.incrementAndGet() },
        )
        server.enqueue(
            jsonResponse(
                openaiToolCallPayload(
                    "device_diagnostics_tool",
                    JSONObject().put("action", "status"),
                ),
            ),
        )

        val result = scopedClient.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "alias-must-not-widen",
            sessionId = "alias-must-not-widen",
            userText = "Show the current device status.",
            providerId = "llama.cpp",
        )

        assertEquals(0, result.executedToolCalls)
        assertEquals(0, dispatches.get())
        assertTrue(result.content, result.content.contains("alias", ignoreCase = true))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun memorySearchScopeCannotBeWidenedToDeleteOrBroadMemoryActions() {
        settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "turboquant"))
        val dispatches = mutableListOf<String>()
        val scopedClient = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            onToolDispatch = { dispatches += it },
        )
        val prompt = "What do you remember about Alice?"
        listOf(
            "memory_delete" to JSONObject().put("memory_id", "victim"),
            "hy_memory_tool" to JSONObject().put("action", "delete").put("memory_id", "victim"),
        ).forEachIndexed { index, (name, arguments) ->
            server.enqueue(jsonResponse(openaiToolCallPayload(name, arguments)))
            val result = scopedClient.send(
                baseUrl = server.url("/").toString().trimEnd('/'),
                modelName = "memory-scope-block-$index",
                sessionId = "memory-scope-block-$index",
                userText = prompt,
                providerId = "llama.cpp",
            )
            assertEquals(0, result.executedToolCalls)
        }

        server.enqueue(jsonResponse(openaiToolCallPayload("memory_search", JSONObject().put("query", "alice"))))
        server.enqueue(jsonResponse(finalPayload("Memory search complete")))
        val valid = scopedClient.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "memory-scope-valid",
            sessionId = "memory-scope-valid",
            userText = prompt,
            providerId = "llama.cpp",
        )
        assertEquals(1, valid.executedToolCalls)
        assertEquals(listOf("memory_search"), dispatches)
    }

    @Test
    fun requestScopeAtomicallyBlocksDuplicateBatchAndLaterReplay() {
        settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "turboquant"))
        val dispatches = AtomicInteger(0)
        val scopedClient = NativeToolCallingChatClient(
            context = RuntimeEnvironment.getApplication(),
            onToolDispatch = { dispatches.incrementAndGet() },
        )
        val statusArguments = JSONObject().put("action", "status")

        server.enqueue(
            jsonResponse(
                openaiMultiToolCallPayload(
                    "android_device_diagnostics_tool" to statusArguments,
                    "android_device_diagnostics_tool" to statusArguments,
                ),
            ),
        )
        val duplicate = scopedClient.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "duplicate-batch",
            sessionId = "duplicate-batch",
            userText = "Show the current device status.",
            providerId = "llama.cpp",
        )
        assertEquals(0, duplicate.executedToolCalls)
        assertEquals(0, dispatches.get())

        server.enqueue(jsonResponse(openaiToolCallPayload("android_device_diagnostics_tool", statusArguments)))
        server.enqueue(jsonResponse(openaiToolCallPayload("android_device_diagnostics_tool", statusArguments)))
        val replay = scopedClient.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "later-replay",
            sessionId = "later-replay",
            userText = "Show the current device status.",
            providerId = "llama.cpp",
        )
        assertEquals(1, replay.executedToolCalls)
        assertEquals("later-round replay reached dispatch", 1, dispatches.get())
        assertTrue(replay.content.contains("repeated native action"))
    }

    @Test
    fun validClockReminderHasIdenticalScopedRecoveryAndExecutesOnce() {
        settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "turboquant"))
        val prompt = "Remind me at 08:30 to call Alice."
        server.enqueue(contextOverflowResponse())
        server.enqueue(
            jsonResponse(
                openaiToolCallPayload(
                    "schedule_task",
                    JSONObject()
                        .put("task", prompt.lowercase())
                        .put("time", "08:30"),
                ),
            ),
        )
        server.enqueue(jsonResponse(finalPayload("Reminder scheduled")))
        val events = mutableListOf<NativeAgentEvent>()

        val result = client.send(
            baseUrl = server.url("/").toString().trimEnd('/'),
            modelName = "clock-reminder",
            sessionId = "clock-reminder",
            userText = prompt,
            providerId = "llama.cpp",
            onEvent = { events += it },
        )

        assertEquals(1, result.executedToolCalls)
        val initial = JSONObject(server.takeRequest().body.readUtf8())
        val recovery = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(listOf("schedule_task"), requestToolNames(initial))
        assertEquals(initial.optJSONArray("tools").toString(), recovery.optJSONArray("tools").toString())
        val toolResult = events.last { it.type == AgentEventType.ToolResult }.content
        assertTrue(toolResult, JSONObject(toolResult).optBoolean("success", false))
        val automationId = JSONObject(toolResult).optJSONObject("automation")?.optString("id").orEmpty()
        if (automationId.isNotBlank()) {
            HermesAutomationBridge.cancelTaskJson(
                RuntimeEnvironment.getApplication(),
                JSONObject().put("task_id", automationId),
            )
        }
    }

    @Test
    fun localizedAppendScopesAndExecutesAsAppendWithoutOverwritingExistingBytes() {
        settingsStore.save(originalSettings.copy(llamaCppRuntimeLane = "turboquant"))
        val cases = listOf(
            "Append to file append-en.txt" to "append-en.txt",
            "追加到文件 append-zh.txt" to "append-zh.txt",
            "Añade al archivo append-es.txt" to "append-es.txt",
            "Hänge an die Datei append-de.txt" to "append-de.txt",
            "Adicione ao arquivo append-pt.txt" to "append-pt.txt",
            "Ajoute au fichier append-fr.txt" to "append-fr.txt",
        )
        val application = RuntimeEnvironment.getApplication()
        val root = Files.createTempDirectory("hermes-localized-append").toFile()
        try {
            val appendClient = NativeToolCallingChatClient(
                context = application,
                workspaceFileWriter = { _, rawPath, content, append, publicationGate ->
                    val target = File(root, rawPath)
                    var result = JSONObject()
                        .put("exit_code", 130)
                        .put("success", false)
                        .put("cancelled", true)
                    publicationGate.publishIfActive {
                        target.parentFile?.mkdirs()
                        if (append) target.appendText(content) else target.writeText(content)
                        result = JSONObject()
                            .put("exit_code", 0)
                            .put("success", true)
                            .put("path", target.absolutePath)
                            .put("bytes", target.length())
                            .put("append", append)
                    }
                    result
                },
            )

            cases.forEachIndexed { index, (prompt, path) ->
                val target = File(root, path).apply { writeText("old") }
                server.enqueue(
                    jsonResponse(
                        openaiToolCallPayload(
                            "file_write_tool",
                            JSONObject()
                                .put("path", path)
                                .put("content", "-new")
                                .put("append", true),
                        ),
                    ),
                )
                server.enqueue(jsonResponse(finalPayload("Appended $index")))
                val fileResults = mutableListOf<String>()

                val result = appendClient.send(
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    modelName = "localized-append-$index",
                    sessionId = "localized-append-$index",
                    userText = prompt,
                    providerId = "llama.cpp",
                    onEvent = { event ->
                        if (event.type == AgentEventType.FileAccess) fileResults += event.content
                    },
                )

                assertEquals("localized append was blocked or widened for '$prompt'", 1, result.executedToolCalls)
                val initialRequest = JSONObject(server.takeRequest().body.readUtf8())
                server.takeRequest()
                val appendProperty = initialRequest
                    .getJSONArray("tools")
                    .getJSONObject(0)
                    .getJSONObject("function")
                    .getJSONObject("parameters")
                    .getJSONObject("properties")
                    .getJSONObject("append")
                assertEquals(true, appendProperty.getJSONArray("enum").getBoolean(0))
                assertTrue("No file result was emitted for '$prompt'", fileResults.isNotEmpty())
                val fileResult = JSONObject(fileResults.last())
                assertTrue(fileResults.last(), fileResult.optBoolean("append", false))
                assertEquals(target.absolutePath, fileResult.getString("path"))
                assertEquals("old-new", target.readText())
            }
        } finally {
            root.deleteRecursively()
        }
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

    private fun contextOverflowResponse(): MockResponse {
        return MockResponse()
            .setResponseCode(400)
            .setHeader("Content-Type", "application/json")
            .setBody(
                JSONObject()
                    .put(
                        "error",
                        JSONObject().put(
                            "message",
                            "exceed_context_size: prompt exceeds the available context size",
                        ),
                    )
                    .toString(),
            )
    }

    private fun requestToolNames(payload: JSONObject): List<String> {
        val tools = payload.optJSONArray("tools") ?: return emptyList()
        return buildList {
            for (index in 0 until tools.length()) {
                add(tools.getJSONObject(index).getJSONObject("function").getString("name"))
            }
        }
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

    private fun openaiMultiToolCallPayload(vararg calls: Pair<String, JSONObject>): JSONObject {
        val toolCalls = JSONArray()
        calls.forEachIndexed { index, (name, arguments) ->
            toolCalls.put(
                JSONObject()
                    .put("id", "call_openai_${index + 1}")
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", name)
                            .put("arguments", arguments.toString()),
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
