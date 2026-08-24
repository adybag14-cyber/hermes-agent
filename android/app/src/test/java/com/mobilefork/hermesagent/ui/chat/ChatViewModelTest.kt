package com.mobilefork.hermesagent.ui.chat

import com.mobilefork.hermesagent.api.ChatContentPart
import com.mobilefork.hermesagent.api.ChatCompletionRequest
import com.mobilefork.hermesagent.api.ChatMessage
import com.mobilefork.hermesagent.api.HermesApiClient
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.backend.LocalBackendStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ChatViewModelTest {
    @Test
    fun lateInitializationCannotOverwriteAnAdmittedSend() {
        val guard = ChatInitializationGuard()
        val initializationGeneration = guard.capture()
        val storeReadFinished = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val state = AtomicReference(ChatUiState(status = "Loading…"))
        val published = AtomicBoolean(false)
        val staleLoaded = ChatUiState(
            activeConversationId = "stale-session",
            messages = listOf(ChatUiMessage("stale", "assistant", "stale reply", 1L)),
        )
        val initializer = thread(name = "chat-init-send-race") {
            storeReadFinished.countDown()
            assertTrue(releasePublication.await(5, TimeUnit.SECONDS))
            published.set(
                guard.applyIfCurrent(initializationGeneration) {
                    state.set(mergeInitialChatState(staleLoaded, state.get()))
                },
            )
        }
        assertTrue(storeReadFinished.await(5, TimeUnit.SECONDS))

        guard.invalidate()
        val admitted = ChatUiState(
            activeConversationId = "send-session",
            messages = listOf(
                ChatUiMessage("user", "user", "hello", 2L),
                ChatUiMessage("assistant", "assistant", "", 3L),
            ),
            isSending = true,
            status = "Starting Hermes runtime…",
        )
        state.set(admitted)
        releasePublication.countDown()
        initializer.join(5_000L)

        assertFalse(initializer.isAlive)
        assertFalse("stale initialization unexpectedly published after send admission", published.get())
        assertEquals(admitted, state.get())
    }

    @Test
    fun sendInvalidationWaitsForAnAdmittedInitializationPublishBeforeSnapshottingHistory() {
        val guard = ChatInitializationGuard()
        val initializationGeneration = guard.capture()
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val snapshotFinished = CountDownLatch(1)
        val loadedHistory = listOf(
            ChatUiMessage("prior-user", "user", "Earlier question", 1L),
            ChatUiMessage("prior-assistant", "assistant", "Earlier answer", 2L),
        )
        val state = AtomicReference(ChatUiState(status = "Loading…"))
        val sendSnapshot = AtomicReference<List<ChatUiMessage>>(emptyList())
        val initializer = thread(name = "chat-init-before-send-snapshot") {
            assertTrue(
                guard.applyIfCurrent(initializationGeneration) {
                    publicationEntered.countDown()
                    assertTrue(releasePublication.await(5, TimeUnit.SECONDS))
                    state.set(ChatUiState(activeConversationId = "loaded", messages = loadedHistory))
                },
            )
        }
        assertTrue(publicationEntered.await(5, TimeUnit.SECONDS))
        val sender = thread(name = "chat-send-snapshot") {
            guard.invalidate()
            sendSnapshot.set(state.get().messages)
            snapshotFinished.countDown()
        }

        assertFalse(
            "send snapshot passed initialization while its publication owned the guard",
            snapshotFinished.await(100, TimeUnit.MILLISECONDS),
        )
        releasePublication.countDown()
        initializer.join(5_000L)
        sender.join(5_000L)

        assertFalse(initializer.isAlive)
        assertFalse(sender.isAlive)
        assertEquals(loadedHistory, sendSnapshot.get())
    }

    @Test
    fun lateInitializationCannotOverwriteAConsumedCommandTurn() {
        val guard = ChatInitializationGuard()
        val initializationGeneration = guard.capture()
        val storeReadFinished = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val state = AtomicReference(ChatUiState(status = "Loading…"))
        val staleLoaded = ChatUiState(activeConversationId = "stale-session")
        val initializer = thread(name = "chat-init-command-result-race") {
            storeReadFinished.countDown()
            assertTrue(releasePublication.await(5, TimeUnit.SECONDS))
            guard.applyIfCurrent(initializationGeneration) {
                state.set(mergeInitialChatState(staleLoaded, state.get()))
            }
        }
        assertTrue(storeReadFinished.await(5, TimeUnit.SECONDS))

        guard.invalidate()
        val commandTurn = ChatUiState(
            activeConversationId = "command-session",
            messages = listOf(
                ChatUiMessage("command", "user", "run status", 2L),
                ChatUiMessage("result", "assistant", "all clear", 3L),
            ),
        )
        state.set(commandTurn)
        releasePublication.countDown()
        initializer.join(5_000L)

        assertFalse(initializer.isAlive)
        assertEquals(commandTurn, state.get())
    }

    @Test
    fun lateInitializationPreservesHistoryOpenedWhileTheStoreReadWasBlocked() {
        val guard = ChatInitializationGuard()
        val initializationGeneration = guard.capture()
        val storeReadFinished = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val state = AtomicReference(ChatUiState(status = "Loading…"))
        val initializer = thread(name = "chat-init-history-race") {
            val loaded = ChatUiState(
                activeConversationId = "loaded-session",
                activeConversationTitle = "Loaded chat",
                status = "",
            )
            storeReadFinished.countDown()
            assertTrue(releasePublication.await(5, TimeUnit.SECONDS))
            assertTrue(
                guard.applyIfCurrent(initializationGeneration) {
                    state.set(mergeInitialChatState(loaded, state.get()))
                },
            )
        }
        assertTrue(storeReadFinished.await(5, TimeUnit.SECONDS))

        state.set(
            state.get().copy(
                isShowingHistory = true,
                showIntermediateSteps = false,
                status = "Browsing chat history",
            ),
        )
        releasePublication.countDown()
        initializer.join(5_000L)

        assertFalse(initializer.isAlive)
        assertEquals("loaded-session", state.get().activeConversationId)
        assertTrue(state.get().isShowingHistory)
        assertFalse(state.get().showIntermediateSteps)
        assertEquals("Browsing chat history", state.get().status)
    }

    @Test
    fun stopSerializesBehindAnAdmittedDeltaAndBecomesTheLastMutation() {
        val coordinator = ChatSendRequestCoordinator()
        val request = coordinator.begin("session-a", "assistant-a") {}!!
        val deltaEntered = CountDownLatch(1)
        val releaseDelta = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val mutations = mutableListOf<String>()

        val deltaThread = thread(name = "chat-delta") {
            assertTrue(
                coordinator.mutateIfActive(request) {
                    mutations += "delta-start"
                    deltaEntered.countDown()
                    assertTrue(releaseDelta.await(5, TimeUnit.SECONDS))
                    mutations += "delta-end"
                },
            )
        }
        assertTrue(deltaEntered.await(5, TimeUnit.SECONDS))
        val stopThread = thread(name = "chat-stop") {
            coordinator.stopActive { mutations += "stop-terminal" }
            stopFinished.countDown()
        }

        assertFalse("Stop must wait for the admitted mutation's ownership lock", stopFinished.await(100, TimeUnit.MILLISECONDS))
        releaseDelta.countDown()
        deltaThread.join(5_000L)
        stopThread.join(5_000L)

        assertFalse(deltaThread.isAlive)
        assertFalse(stopThread.isAlive)
        assertEquals(listOf("delta-start", "delta-end", "stop-terminal"), mutations)
        assertFalse(coordinator.mutateIfActive(request) { mutations += "late-delta" })
    }

    @Test
    fun stopAndRetirePublishStickyCancellationBeforeTerminalPersistence() {
        listOf(false, true).forEach { retire ->
            val coordinator = ChatSendRequestCoordinator()
            val request = coordinator.begin(
                sessionId = if (retire) "retire-session" else "stop-session",
                assistantMessageId = if (retire) "retire-assistant" else "stop-assistant",
            ) {}!!
            val cancellationPublished = AtomicBoolean(false)
            val ordering = mutableListOf<String>()
            assertTrue(
                coordinator.attachNetwork(request) {
                    ordering += "network-cancel"
                    cancellationPublished.set(true)
                },
            )
            assertTrue(coordinator.attachJob(request) { ordering += "job-cancel" })

            val retired = if (retire) {
                coordinator.retireActive {
                    assertTrue("retire terminal ran before sticky cancellation", cancellationPublished.get())
                    ordering += "terminal"
                }
            } else {
                coordinator.stopActive {
                    assertTrue("Stop terminal ran before sticky cancellation", cancellationPublished.get())
                    ordering += "terminal"
                }
            }

            assertEquals(request, retired)
            assertEquals(listOf("network-cancel", "job-cancel", "terminal"), ordering)
        }
    }

    @Test
    fun stopWinningTheLockRejectsLateNativeResultAndSseCompletion() {
        val coordinator = ChatSendRequestCoordinator()
        val request = coordinator.begin("session-a", "assistant-a") {}!!
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val resultFinished = CountDownLatch(1)
        val resultCommitted = AtomicBoolean(false)
        val terminalCommitted = AtomicBoolean(false)

        val stopThread = thread(name = "chat-stop") {
            coordinator.stopActive {
                terminalCommitted.set(true)
                stopEntered.countDown()
                assertTrue(releaseStop.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(stopEntered.await(5, TimeUnit.SECONDS))
        val resultThread = thread(name = "chat-result") {
            val completed = coordinator.finishIfActive(request) {
                resultCommitted.set(true)
                true
            }
            assertFalse(completed)
            resultFinished.countDown()
        }

        assertFalse("A result must not pass Stop while Stop owns the transition", resultFinished.await(100, TimeUnit.MILLISECONDS))
        releaseStop.countDown()
        stopThread.join(5_000L)
        resultThread.join(5_000L)

        assertTrue(terminalCommitted.get())
        assertFalse(resultCommitted.get())
        assertFalse(coordinator.finishIfActive(request) { true })
        assertFalse(coordinator.mutateIfActive(request) {})
    }

    @Test
    fun stopDuringFallbackCancelsItsOwnedCallAndRejectsTheLateResult() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setSocketPolicy(SocketPolicy.NO_RESPONSE),
            )
            val coordinator = ChatSendRequestCoordinator()
            val request = coordinator.begin("fallback-session", "fallback-assistant") {}!!
            val transport = RequestOwnedHttpTransport()
            assertTrue(coordinator.attachNetwork(request, transport::cancel))
            val terminal = AtomicReference("active")
            val failure = AtomicReference<Throwable?>(null)
            val worker = thread(name = "chat-fallback-call") {
                failure.set(
                    runCatching {
                        HermesApiClient(
                            baseUrl = server.url("/").toString(),
                            httpClient = transport.client,
                        ).createChatCompletion(
                            ChatCompletionRequest(
                                model = "fallback",
                                messages = listOf(ChatMessage(role = "user", content = "hello")),
                            ),
                        )
                    }.exceptionOrNull(),
                )
                coordinator.finishIfActive(request) {
                    terminal.set("late result")
                    true
                }
            }

            assertTrue("fallback request never reached the server", server.takeRequest(5, TimeUnit.SECONDS) != null)
            assertEquals(request, coordinator.stopActive { terminal.set("Stopped by user") })
            worker.join(5_000L)

            assertFalse("cancelled fallback call remained alive", worker.isAlive)
            assertTrue("Call.cancel did not interrupt the fallback", failure.get() != null)
            assertEquals("Stopped by user", terminal.get())
            assertFalse(coordinator.finishIfActive(request) { true })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun throwingNetworkCancellationStillCancelsTheOwnedJobAndRetiresTheRequest() {
        val coordinator = ChatSendRequestCoordinator()
        val request = coordinator.begin("throwing-cancel-session", "assistant-a") {}!!
        val jobCancels = AtomicInteger(0)
        val terminalPersisted = AtomicBoolean(false)
        assertTrue(coordinator.attachNetwork(request) { error("network cancellation failed") })
        assertTrue(coordinator.attachJob(request) { jobCancels.incrementAndGet() })

        val failure = runCatching {
            coordinator.stopActive { terminalPersisted.set(true) }
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("network cancellation failed"))
        assertEquals(1, jobCancels.get())
        assertTrue("terminal persistence was skipped after a cancellation-hook failure", terminalPersisted.get())
        assertFalse(coordinator.isActive(request))
        assertTrue(coordinator.begin("next-session", "assistant-b") {} != null)
    }

    @Test
    fun throwingStopPersistenceStillCancelsBothOwnedHandlesAndRetiresTheRequest() {
        val coordinator = ChatSendRequestCoordinator()
        val networkCancels = AtomicInteger(0)
        val jobCancels = AtomicInteger(0)
        val request = coordinator.begin("session-a", "assistant-a") {}!!
        assertTrue(coordinator.attachNetwork(request) { networkCancels.incrementAndGet() })
        assertTrue(coordinator.attachJob(request) { jobCancels.incrementAndGet() })

        val failure = runCatching {
            coordinator.stopActive { throw IllegalStateException("persistence failed") }
        }.exceptionOrNull()

        assertEquals("persistence failed", failure?.message)
        assertEquals(1, networkCancels.get())
        assertEquals(1, jobCancels.get())
        assertFalse(coordinator.isActive(request))
        assertTrue(coordinator.begin("session-b", "assistant-b") {} != null)
    }

    @Test
    fun viewModelDestructionTerminalizesPlaceholderCancelsTransportAndRejectsFallbackAndLateWrites() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val coordinator = ChatSendRequestCoordinator()
            val request = coordinator.begin("destroyed-session", "destroyed-assistant") {}!!
            val transport = RequestOwnedHttpTransport()
            val networkCancels = AtomicInteger(0)
            val jobCancels = AtomicInteger(0)
            val persistedAssistant = AtomicReference("")
            val lifecycleTerminal = "This reply was interrupted because the chat was closed."
            val callFailure = AtomicReference<Throwable?>(null)
            val fallbackEntered = AtomicBoolean(false)
            assertTrue(
                coordinator.attachNetwork(request) {
                    networkCancels.incrementAndGet()
                    transport.cancel()
                },
            )
            assertTrue(coordinator.attachJob(request) { jobCancels.incrementAndGet() })

            val worker = thread(name = "chat-destruction-blocking-call") {
                callFailure.set(
                    runCatching {
                        HermesApiClient(
                            baseUrl = server.url("/").toString(),
                            httpClient = transport.client,
                        ).createChatCompletion(
                            ChatCompletionRequest(
                                model = "destroyed-request",
                                messages = listOf(ChatMessage(role = "user", content = "hello")),
                            ),
                        )
                    }.exceptionOrNull(),
                )
                coordinator.finishIfActive(request) {
                    persistedAssistant.set("late network result")
                    true
                }
                if (coordinator.isActive(request)) {
                    fallbackEntered.set(true)
                }
            }

            assertTrue("destroyed request never reached the server", server.takeRequest(5, TimeUnit.SECONDS) != null)
            assertEquals(
                request,
                coordinator.retireActive {
                    persistedAssistant.set(lifecycleTerminal)
                },
            )
            worker.join(5_000L)

            assertFalse("destroyed blocking call remained alive", worker.isAlive)
            assertTrue("destruction did not cancel the blocking Call.execute", callFailure.get() != null)
            assertEquals(1, networkCancels.get())
            assertEquals(1, jobCancels.get())
            assertFalse("destroyed request entered non-stream fallback", fallbackEntered.get())
            assertEquals(lifecycleTerminal, persistedAssistant.get())
            assertTrue(persistedAssistant.get().isNotBlank())
            assertFalse(coordinator.mutateIfActive(request) { persistedAssistant.set("late delta") })
            assertFalse(coordinator.finishIfActive(request) { persistedAssistant.set("late completion"); true })
            assertFalse(coordinator.jobCompleted(request) { persistedAssistant.set("late job terminal") })
            assertEquals(lifecycleTerminal, persistedAssistant.get())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun completionWinningTheLockPreventsStopFromOverwritingTheResult() {
        val coordinator = ChatSendRequestCoordinator()
        val request = coordinator.begin("session-a", "assistant-a") {}!!
        val completionEntered = CountDownLatch(1)
        val releaseCompletion = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val resultCommitted = AtomicBoolean(false)
        val terminalCommitted = AtomicBoolean(false)

        val completionThread = thread(name = "chat-completion") {
            assertTrue(
                coordinator.finishIfActive(request) {
                    completionEntered.countDown()
                    assertTrue(releaseCompletion.await(5, TimeUnit.SECONDS))
                    resultCommitted.set(true)
                    true
                },
            )
        }
        assertTrue(completionEntered.await(5, TimeUnit.SECONDS))
        val stopThread = thread(name = "chat-stop") {
            val stopped = coordinator.stopActive { terminalCommitted.set(true) }
            assertEquals(null, stopped)
            stopFinished.countDown()
        }

        assertFalse("Stop must wait for the completing result's ownership lock", stopFinished.await(100, TimeUnit.MILLISECONDS))
        releaseCompletion.countDown()
        completionThread.join(5_000L)
        stopThread.join(5_000L)

        assertTrue(resultCommitted.get())
        assertFalse(terminalCommitted.get())
    }

    @Test
    fun conversationTransitionsRetireTheOldSendBeforeAReplacementCanBegin() {
        listOf("new", "open", "clear").forEachIndexed { index, transition ->
            val coordinator = ChatSendRequestCoordinator()
            val oldRequest = coordinator.begin("session-$index", "assistant-$index") {}!!
            val callbackEntered = CountDownLatch(1)
            val releaseCallback = CountDownLatch(1)
            val transitionFinished = CountDownLatch(1)
            val terminalCount = AtomicInteger(0)
            val callbackAdmitted = AtomicBoolean(false)
            val callbackReleased = AtomicBoolean(false)
            val stoppedRequest = AtomicReference<ChatSendRequestCoordinator.Request?>()
            val replacementRequest = AtomicReference<ChatSendRequestCoordinator.Request?>()
            val ordering = mutableListOf<String>()

            val callbackThread = thread(name = "chat-$transition-callback") {
                callbackAdmitted.set(
                    coordinator.mutateIfActive(oldRequest) {
                        ordering += "callback-start"
                        callbackEntered.countDown()
                        callbackReleased.set(releaseCallback.await(5, TimeUnit.SECONDS))
                        ordering += "callback-end"
                    },
                )
            }
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
            val transitionThread = thread(name = "chat-$transition-transition") {
                stoppedRequest.set(
                    coordinator.stopActive {
                        ordering += "terminal"
                        terminalCount.incrementAndGet()
                    },
                )
                replacementRequest.set(
                    coordinator.begin("replacement-$transition", "replacement-assistant-$index") {
                        ordering += "replacement"
                    },
                )
                transitionFinished.countDown()
            }

            assertFalse(
                "The $transition transition must serialize behind an admitted old callback",
                transitionFinished.await(100, TimeUnit.MILLISECONDS),
            )
            releaseCallback.countDown()
            callbackThread.join(5_000L)
            transitionThread.join(5_000L)

            assertFalse(callbackThread.isAlive)
            assertFalse(transitionThread.isAlive)
            assertTrue(callbackAdmitted.get())
            assertTrue(callbackReleased.get())
            assertEquals(oldRequest, stoppedRequest.get())
            val replacement = replacementRequest.get()!!
            assertEquals(1, terminalCount.get())
            assertEquals(listOf("callback-start", "callback-end", "terminal", "replacement"), ordering)
            assertFalse(coordinator.mutateIfActive(oldRequest) {})
            assertFalse(coordinator.finishIfActive(oldRequest) { true })
            assertTrue(coordinator.isActive(replacement))
            assertTrue(coordinator.finishIfActive(replacement) { true })
        }
    }

    @Test
    fun stopAThenSendBThenStopBCancelsOnlyEachRequestsOwnedHandles() {
        val coordinator = ChatSendRequestCoordinator()
        val jobACancelled = AtomicInteger(0)
        val networkACancelled = AtomicInteger(0)
        val jobBCancelled = AtomicInteger(0)
        val networkBCancelled = AtomicInteger(0)
        val unexpectedOldCompletion = AtomicInteger(0)
        val stopAEntered = CountDownLatch(1)
        val releaseStopA = CountDownLatch(1)
        val sendBFinished = CountDownLatch(1)
        val stopAWaitReleased = AtomicBoolean(false)
        val stoppedA = AtomicReference<ChatSendRequestCoordinator.Request?>()
        val requestBRef = AtomicReference<ChatSendRequestCoordinator.Request?>()

        val requestA = coordinator.begin("session-a", "assistant-a") {}!!
        assertTrue(coordinator.attachJob(requestA) { jobACancelled.incrementAndGet() })
        assertTrue(coordinator.attachNetwork(requestA) { networkACancelled.incrementAndGet() })
        val stopAThread = thread(name = "chat-stop-a") {
            stoppedA.set(
                coordinator.stopActive {
                    stopAEntered.countDown()
                    stopAWaitReleased.set(releaseStopA.await(5, TimeUnit.SECONDS))
                },
            )
        }
        assertTrue(stopAEntered.await(5, TimeUnit.SECONDS))
        val sendBThread = thread(name = "chat-send-b") {
            val requestB = coordinator.begin("session-b", "assistant-b") {}!!
            requestBRef.set(requestB)
            coordinator.attachJob(requestB) { jobBCancelled.incrementAndGet() }
            coordinator.attachNetwork(requestB) { networkBCancelled.incrementAndGet() }
            sendBFinished.countDown()
        }

        assertFalse(
            "Send B must wait until Stop A has terminalized and cancelled A's handles",
            sendBFinished.await(100, TimeUnit.MILLISECONDS),
        )
        releaseStopA.countDown()
        stopAThread.join(5_000L)
        sendBThread.join(5_000L)

        assertFalse(stopAThread.isAlive)
        assertFalse(sendBThread.isAlive)
        assertTrue(stopAWaitReleased.get())
        assertEquals(requestA, stoppedA.get())
        val requestB = requestBRef.get()!!
        assertFalse(coordinator.jobCompleted(requestA) { unexpectedOldCompletion.incrementAndGet() })

        assertTrue(coordinator.isActive(requestB))
        assertEquals(1, jobACancelled.get())
        assertEquals(1, networkACancelled.get())
        assertEquals(0, jobBCancelled.get())
        assertEquals(0, networkBCancelled.get())
        assertEquals(0, unexpectedOldCompletion.get())

        assertEquals(requestB, coordinator.stopActive {})
        assertEquals(1, jobBCancelled.get())
        assertEquals(1, networkBCancelled.get())
    }

    @Test
    fun zeroDeltaSseCompletionAtomicallyCommitsLocalizedFailureTerminal() {
        val coordinator = ChatSendRequestCoordinator()
        val request = coordinator.begin("session-zero-delta", "assistant-zero-delta") {}!!
        val localizedFailure = "Hermes could not complete this reply."
        var storedContent = ""
        var retainedAsAssistantAnswer = true

        assertTrue(
            coordinator.finishIfActive(request) {
                val resolution = resolveAssistantCompletion(
                    streamedContent = " \n\t ",
                    localizedFailureMessage = localizedFailure,
                )
                storedContent = resolution.content
                retainedAsAssistantAnswer = resolution.hasAssistantContent
                true
            },
        )

        assertEquals(localizedFailure, storedContent)
        assertTrue(storedContent.isNotBlank())
        assertFalse(retainedAsAssistantAnswer)
        assertFalse(coordinator.mutateIfActive(request) { storedContent = "late delta" })
        assertEquals(localizedFailure, storedContent)

        val successful = resolveAssistantCompletion(
            streamedContent = "  completed answer  ",
            localizedFailureMessage = localizedFailure,
        )
        assertEquals("  completed answer  ", successful.content)
        assertTrue(successful.hasAssistantContent)
    }

    @Test
    fun onlyTurboQuantLocalLlamaSuppressesReasoningContent() {
        assertTrue(shouldSuppressLocalLlamaReasoning("llama.cpp", "turboquant"))
        assertFalse(shouldSuppressLocalLlamaReasoning("llama.cpp", "stable"))
        assertFalse(shouldSuppressLocalLlamaReasoning("litert-lm", "turboquant"))
        assertFalse(shouldSuppressLocalLlamaReasoning("openrouter", "turboquant"))
    }

    @Test
    fun specialCodexAndChatGptWebProtocolsNeverUseGenericDirectChatCompletions() {
        assertFalse(usesDirectOpenAiCompatibleTransport("openai-codex"))
        assertFalse(usesDirectOpenAiCompatibleTransport("chatgpt-web"))
        assertTrue(usesDirectOpenAiCompatibleTransport("openai"))
        assertTrue(usesDirectOpenAiCompatibleTransport("codex"))
    }

    @Test
    fun allChatRoutingRejectsRestartRequiredLocalRuntimeAndStaleCachedEndpoint() {
        val restartRequired = LocalBackendStatus(
            backendKind = BackendKind.LITERT_LM,
            started = false,
            requiresAppRestart = true,
        )
        assertFalse(
            chatRuntimeRoutingAllowed(restartRequired),
        )
        assertFalse(
            shouldReuseCachedRuntime(
                selectedLocalBackend = BackendKind.LITERT_LM,
                localBackendStatus = restartRequired,
                runtimeStarted = true,
                runtimeBaseUrl = "http://127.0.0.1:15436/v1",
                endpointAvailable = true,
            ),
        )
        assertTrue(
            chatRuntimeRoutingAllowed(
                LocalBackendStatus(backendKind = BackendKind.NONE, started = false),
            ),
        )
    }

    @Test
    fun explicitLocalSelectionNeverReusesAStaleRemoteRuntime() {
        val failedLocal = LocalBackendStatus(
            backendKind = BackendKind.LLAMA_CPP,
            started = false,
            statusMessage = "RAM admission rejected",
        )
        assertFalse(
            shouldReuseCachedRuntime(
                selectedLocalBackend = BackendKind.LLAMA_CPP,
                localBackendStatus = failedLocal,
                runtimeStarted = true,
                runtimeBaseUrl = "https://remote.example/v1",
                endpointAvailable = true,
            ),
        )

        val startedLocal = failedLocal.copy(
            started = true,
            baseUrl = "http://127.0.0.1:18081/v1",
        )
        assertFalse(
            shouldReuseCachedRuntime(
                selectedLocalBackend = BackendKind.LLAMA_CPP,
                localBackendStatus = startedLocal,
                runtimeStarted = true,
                runtimeBaseUrl = "https://remote.example/v1",
                endpointAvailable = true,
            ),
        )
        assertFalse(
            shouldReuseCachedRuntime(
                selectedLocalBackend = BackendKind.LLAMA_CPP,
                localBackendStatus = startedLocal,
                runtimeStarted = true,
                runtimeBaseUrl = "http://127.0.0.1:18081/v1/",
                endpointAvailable = true,
            ),
        )
    }

    @Test
    fun remoteSelectionCanReuseAHealthyCachedRemoteRuntime() {
        assertTrue(
            shouldReuseCachedRuntime(
                selectedLocalBackend = BackendKind.NONE,
                localBackendStatus = LocalBackendStatus(backendKind = BackendKind.NONE, started = false),
                runtimeStarted = true,
                runtimeBaseUrl = "https://remote.example/v1",
                endpointAvailable = true,
            ),
        )
    }

    @Test
    fun remoteSelectionNeverPrefersAStaleStartedLocalEndpoint() {
        val staleLocal = LocalBackendStatus(
            backendKind = BackendKind.LLAMA_CPP,
            started = true,
            baseUrl = "http://127.0.0.1:15435/v1",
            modelName = "stale-local",
            apiKey = "local-process-key",
        )

        assertFalse(shouldPreferLocalChatEndpoint(BackendKind.NONE, staleLocal))
        assertTrue(shouldPreferLocalChatEndpoint(BackendKind.LLAMA_CPP, staleLocal))
        assertFalse(shouldPreferLocalChatEndpoint(BackendKind.LITERT_LM, staleLocal))

        val postStopLocalState = HermesRuntimeManager.RuntimeState(
            started = true,
            baseUrl = staleLocal.baseUrl,
            apiKey = staleLocal.apiKey,
            localBackendKind = BackendKind.LLAMA_CPP,
            modelName = staleLocal.modelName,
        )
        assertFalse(runtimeCanProvideRemoteChatEndpoint(postStopLocalState))
        assertTrue(
            runtimeCanProvideRemoteChatEndpoint(
                HermesRuntimeManager.RuntimeState(
                    started = true,
                    baseUrl = "https://remote.example/v1",
                    apiKey = "remote-key",
                ),
            ),
        )
    }

    @Test
    fun chatUiState_defaultsAreEmptyAndIdle() {
        val state = ChatUiState()
        assertEquals(emptyList<ChatUiMessage>(), state.messages)
        assertEquals("", state.input)
        assertFalse(state.isSending)
        assertEquals("", state.error)
    }

    @Test
    fun buildChatTurnsPairsPromptWithAssistantReply() {
        val user = ChatUiMessage("u1", "user", "Please use your back camera", 60_000L)
        val assistant = ChatUiMessage("a1", "assistant", "Taking a photo now.", 60_001L)

        val turns = buildChatTurns(listOf(user, assistant))

        assertEquals(1, turns.size)
        assertEquals(user, turns[0].userMessage)
        assertEquals(listOf(assistant), turns[0].assistantMessages)
    }

    @Test
    fun shortPromptPreviewUsesFirstLineAndCompactsWhitespace() {
        val preview = shortPromptPreview(
            """
              Please   use camera
            with a long second line
            """.trimIndent(),
            maxLength = 40,
        )

        assertEquals("Please use camera", preview)
    }

    @Test
    fun extractAssistantContentFromChatCompletionReadsStringMessageContent() {
        val content = extractAssistantContentFromChatCompletion(
            """{"choices":[{"message":{"role":"assistant","content":"Endpoint recovered"}}]}""",
        )

        assertEquals("Endpoint recovered", content)
    }

    @Test
    fun extractAssistantContentFromChatCompletionReadsArrayMessageContent() {
        val content = extractAssistantContentFromChatCompletion(
            """{"choices":[{"message":{"role":"assistant","content":[{"type":"text","text":"First"},{"type":"text","text":"Second"}]}}]}""",
        )

        assertEquals("First\nSecond", content)
    }

    @Test
    fun extractAssistantContentFromResponseReadsOutputTextHelperAndItems() {
        assertEquals(
            "Endpoint recovered",
            extractAssistantContentFromResponse("""{"output_text":"Endpoint recovered"}"""),
        )
        assertEquals(
            "First\nSecond",
            extractAssistantContentFromResponse(
                """{"output":[{"type":"message","content":[{"type":"output_text","text":"First"},{"type":"output_text","text":"Second"}]}]}""",
            ),
        )
    }

    @Test
    fun buildChatRequestMessagesAddsSavedPersonaBeforeEndpointUserMessage() {
        val messages = buildChatRequestMessages(
            userText = "Check the local model",
            customSystemPrompt = "Prefer local tools and keep replies short.",
        )

        assertEquals(2, messages.size)
        assertEquals("system", messages[0].role)
        assertTrue(messages[0].content.contains("User-configured agent persona"))
        assertTrue(messages[0].content.contains("Prefer local tools"))
        assertEquals("user", messages[1].role)
        assertEquals("Check the local model", messages[1].content)
    }

    @Test
    fun conversationMemoryFactBoundsCompletedChatTurnsForRecall() {
        val fact = conversationMemoryFact(
            sessionId = "session-123",
            userText = "  How should the keyboard behave?\n\nKeep it away from the composer. ",
            assistantText = "The composer should slide with IME insets and return to the bottom after send.",
        )

        assertTrue(fact.contains("session-123"))
        assertTrue(fact.contains("user asked: How should the keyboard behave? Keep it away from the composer."))
        assertTrue(fact.contains("assistant answered: The composer should slide with IME insets"))
        assertTrue(fact.length <= 1_200)
    }

    @Test
    fun buildChatRequestMessagesAddsRelevantMemoryContextBeforeEndpointUserMessage() {
        val messages = buildChatRequestMessages(
            userText = "What should I check on the phone?",
            memoryContext = "1. User cares about physical overlay validation on the home screen.",
        )

        assertEquals(2, messages.size)
        assertEquals("system", messages[0].role)
        assertTrue(messages[0].content.contains("Relevant local memory context recalled from prior conversations"))
        assertTrue(messages[0].content.contains("physical overlay validation"))
        assertEquals("user", messages[1].role)
        assertEquals("What should I check on the phone?", messages[1].content)
    }

    @Test
    fun buildChatRequestMessagesPreservesAttachmentPartsAndBoundsPersona() {
        val messages = buildChatRequestMessages(
            userText = "Describe this",
            userContentParts = listOf(ChatContentPart(type = "text", text = "Describe this")),
            customSystemPrompt = "x".repeat(2_000),
        )

        assertEquals(2, messages.size)
        assertTrue(messages[0].content.contains("hermes context compressed"))
        assertTrue(messages[0].content.length < 1_200)
        assertEquals(1, messages[1].contentParts.size)
        assertEquals("text", messages[1].contentParts.single().type)
    }

    @Test
    fun buildChatRequestMessagesIncludesBoundedPriorConversationBeforeCurrentUser() {
        val messages = buildChatRequestMessages(
            userText = "What did I just send?",
            priorMessages = listOf(
                ChatMessage(role = "user", content = "hello"),
                ChatMessage(role = "assistant", content = "You sent hello."),
            ),
        )

        assertEquals(3, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("hello", messages[0].content)
        assertEquals("assistant", messages[1].role)
        assertEquals("You sent hello.", messages[1].content)
        assertEquals("What did I just send?", messages[2].content)
    }

    @Test
    fun buildChatRequestMessagesUsesLargerPriorContextWhenCacheResendIsEnabled() {
        val priorMessages = (1..20).map { index ->
            ChatMessage(
                role = if (index % 2 == 0) "assistant" else "user",
                content = "turn-$index " + "x".repeat(2_400),
            )
        }

        val compact = buildChatRequestMessages(
            userText = "continue",
            priorMessages = priorMessages,
        )
        val cacheFriendly = buildChatRequestMessages(
            userText = "continue",
            priorMessages = priorMessages,
            cacheResendEnabled = true,
        )

        assertEquals(13, compact.size)
        assertEquals(21, cacheFriendly.size)
        assertTrue(compact.first().content.length < 1_400)
        assertTrue(cacheFriendly.first().content.length > 2_000)
    }

    @Test
    fun buildPriorChatRequestMessagesDropsBlankAssistantPlaceholdersAndAttachmentPayloads() {
        val prior = buildPriorChatRequestMessages(
            listOf(
                ChatUiMessage("u1", "user", "hello", 1L),
                ChatUiMessage("a1", "assistant", "", 2L),
                ChatUiMessage(
                    id = "u2",
                    role = "user",
                    content = "",
                    createdAtEpochMs = 3L,
                    attachments = listOf(ChatAttachment("content://image", "photo.jpg", "image/jpeg")),
                ),
            ),
        )

        assertEquals(2, prior.size)
        assertEquals("hello", prior[0].content)
        assertTrue(prior[1].content.contains("[prior turn attachment omitted: photo.jpg]"))
    }

    @Test
    fun allFeaturesPromptRoutesDirectlyToNativeSelfTestDiagnostics() {
        val arguments = directNativeDiagnosticArgumentsForPrompt("Run a full all features test for Hermes native tools")

        requireNotNull(arguments)
        assertEquals("agent_native_tool_self_test_report", arguments.getString("action"))
    }

    @Test
    fun exactDeviceStatusExamplesRouteDirectlyToNativeStatusDiagnostics() {
        listOf(
            "Check my device status.",
            "检查我的设备状态。",
            "Comprueba el estado de mi dispositivo.",
            "Prüfe meinen Gerätestatus.",
            "Verifique o status do meu dispositivo.",
            "Vérifie l’état de mon appareil.",
        ).forEach { prompt ->
            val arguments = directNativeDiagnosticArgumentsForPrompt(prompt)
            requireNotNull(arguments)
            assertEquals("status", arguments.getString("action"))
        }
        assertEquals(null, directNativeDiagnosticArgumentsForPrompt("Write a story about device status dashboards"))
    }

    @Test
    fun chineseAllFeaturesPromptRoutesDirectlyToNativeSelfTestDiagnostics() {
        val arguments = directNativeDiagnosticArgumentsForPrompt("全部功能全测试")

        requireNotNull(arguments)
        assertEquals("agent_native_tool_self_test_report", arguments.getString("action"))
    }

    @Test
    fun ordinaryChatPromptDoesNotBypassConfiguredEndpoint() {
        listOf(
            "Write a short welcome message",
            "Do not use android_device_diagnostics_tool action=wifi_scan",
            "Explain what an all features test does",
            "`android_device_diagnostics_tool action=wifi_scan`",
            "\"Run android_device_diagnostics_tool action=wifi_scan\"",
            "Run android_device_diagnostics_tool? No, do not. action=wifi_scan",
        ).forEach { prompt ->
            assertEquals(
                "prompt bypassed the closed direct diagnostic authority: $prompt",
                null,
                directNativeDiagnosticArgumentsForPrompt(prompt),
            )
        }
    }

    @Test
    fun readOnlyTerminalIntentIsRecognizedBeforeEndpointSelection() {
        assertTrue(
            NativeToolChatSender.extractDirectLinuxSandboxPrompt(
                "linux_sandbox_tool action=run distro_id=alpine-3-21 command=uname",
            ),
        )
        assertFalse(
            NativeToolChatSender.extractDirectLinuxSandboxPrompt(
                "Inside the active Alpine 3.21 guest, perform this as one guest action: uname",
            ),
        )
        assertEquals("date", NativeToolChatSender.extractDirectReadOnlyTerminalCommand("What time is it?"))
        assertEquals(
            "date",
            NativeToolChatSender.extractDirectReadOnlyTerminalCommand(
                "Run a command to tell me what time it is.",
            ),
        )
        assertEquals("date", NativeToolChatSender.extractDirectReadOnlyTerminalCommand("What is the current date?"))
        assertEquals("whoami", NativeToolChatSender.extractDirectReadOnlyTerminalCommand("Who is the current user?"))
        assertEquals(
            null,
            NativeToolChatSender.extractDirectReadOnlyTerminalCommand(
                "Write a story about a character who wonders what time it is",
            ),
        )
        assertEquals(
            null,
            NativeToolChatSender.extractDirectReadOnlyTerminalCommand(
                "Use terminal_tool to run: rm -rf /data/local/tmp/example",
            ),
        )
        assertEquals(
            null,
            NativeToolChatSender.extractDirectReadOnlyTerminalCommand(
                "What is the date of the Battle of Hastings?",
            ),
        )
        listOf(
            "Run the date command and tell me the time.",
            "运行 date 命令并告诉我时间。",
            "Ejecuta date y dime la hora.",
            "Führe date aus und nenne mir die Uhrzeit.",
            "Execute date e diga a hora.",
            "Exécute date et donne-moi l’heure.",
        ).forEach { prompt ->
            assertEquals(
                "Expected localized exact time route for '$prompt'",
                "date",
                NativeToolChatSender.extractDirectReadOnlyTerminalCommand(prompt),
            )
        }
        listOf(
            "写一个关于时间的故事。",
            "Cuenta una historia sobre la hora.",
            "Schreibe eine Geschichte über die Uhrzeit.",
            "Escreva uma história sobre a hora.",
            "Écris une histoire sur l’heure.",
        ).forEach { prompt ->
            assertEquals(
                "Localized time prose must not execute for '$prompt'",
                null,
                NativeToolChatSender.extractDirectReadOnlyTerminalCommand(prompt),
            )
        }
    }

    @Test
    fun directNativeDiagnosticsReplyPrefersBridgeOutputText() {
        val reply = formatDirectNativeDiagnosticsReply(
            """{"success":true,"output":"Hermes native tool self-test\nterminal_tool: ready"}""",
        )

        assertEquals("Hermes native tool self-test\nterminal_tool: ready", reply)
    }

    @Test
    fun directNativeStatusReplyPreservesStatusFieldsInsteadOfGenericCards() {
        val reply = formatDirectNativeDiagnosticsReply(
            """{"success":true,"action":"status","sensor_count":7,"cards":[{"title":"Diagnostics","body":"Generic help"}]}""",
        )

        assertTrue(reply.contains("\"action\": \"status\""))
        assertTrue(reply.contains("\"sensor_count\": 7"))
        assertFalse(reply == "Diagnostics: Generic help")
    }

    @Test
    fun synchronousDirectRouteDoesNotPublishAfterCancellation() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = AtomicBoolean(false)
        val job = launch(Dispatchers.Default) {
            runSynchronousDirectRouteWithCancellationCheck {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                "late diagnostics"
            }.getOrThrow()
            published.set(true)
        }

        assertTrue("Direct diagnostics did not enter the synchronous bridge", entered.await(2, TimeUnit.SECONDS))
        job.cancel()
        release.countDown()
        job.join()
        assertFalse("Cancelled direct diagnostics must not publish a late result", published.get())
    }

    @Test
    fun cancellingSynchronousNativeRouteInterruptsUnderlyingWorkAndRejectsLatePublication() = runBlocking {
        val entered = CountDownLatch(1)
        val underlyingStopped = CountDownLatch(1)
        val published = AtomicBoolean(false)
        val job = launch(Dispatchers.Default) {
            runSynchronousDirectRouteWithCancellationCheck {
                entered.countDown()
                try {
                    Thread.sleep(60_000L)
                    "late native result"
                } catch (interrupted: InterruptedException) {
                    underlyingStopped.countDown()
                    throw interrupted
                }
            }.getOrThrow()
            published.set(true)
        }

        assertTrue("Native route did not enter its blocking operation", entered.await(5, TimeUnit.SECONDS))
        job.cancel()
        assertTrue(
            "Cancelling the request did not interrupt the underlying native operation",
            underlyingStopped.await(5, TimeUnit.SECONDS),
        )
        job.join()

        assertFalse("Cancelled native work must not publish a late result", published.get())
    }

    @Test
    fun cancelledInterruptibleRouteDoesNotLeakInterruptIntoNextRequestOnReusedWorker() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "request-owned-native-lane")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        try {
            val requestA = launch(Dispatchers.Default) {
                runSynchronousDirectRouteWithCancellationCheck(dispatcher) {
                    entered.countDown()
                    try {
                        Thread.sleep(60_000L)
                    } catch (error: InterruptedException) {
                        interrupted.countDown()
                        throw error
                    }
                }.getOrThrow()
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            requestA.cancel()
            assertTrue(interrupted.await(5, TimeUnit.SECONDS))
            requestA.join()

            val requestBObservedInterrupt = runSynchronousDirectRouteWithCancellationCheck(dispatcher) {
                Thread.currentThread().isInterrupted
            }.getOrThrow()
            assertFalse("Request A cancellation poisoned the reused worker for B", requestBObservedInterrupt)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun evaluateQuickPromptSend_blocksEmptyPromptWhileSendingOrDrafting() {
        val idle = ChatUiState()
        assertFalse(evaluateQuickPromptSend("", idle).shouldSend)
        assertFalse(evaluateQuickPromptSend("   ", idle).shouldSend)
        assertFalse(
            evaluateQuickPromptSend(
                "Run android_device_diagnostics_tool action=sensor_workflow_advisor_report",
                idle.copy(isSending = true),
            ).shouldSend,
        )

        val draftBlocked = evaluateQuickPromptSend(
            "Run android_device_diagnostics_tool action=sensor_workflow_advisor_report",
            idle.copy(input = "draft text"),
        )
        assertFalse(draftBlocked.shouldSend)
        assertEquals(
            "Send or clear the current draft before running a signal quick action.",
            draftBlocked.blockedStatus,
        )

        val attachmentBlocked = evaluateQuickPromptSend(
            "Run android_device_diagnostics_tool action=sensor_workflow_advisor_report",
            idle.copy(
                attachments = listOf(
                    ChatAttachment(uri = "content://image", displayName = "photo.jpg", mimeType = "image/jpeg"),
                ),
            ),
        )
        assertFalse(attachmentBlocked.shouldSend)
        assertEquals(
            "Send or clear the current draft before running a signal quick action.",
            attachmentBlocked.blockedStatus,
        )
    }

    @Test
    fun evaluateQuickPromptSend_allowsDiagnosticsPromptWhenComposerIsClear() {
        val idle = ChatUiState()
        val prompt = "Run android_device_diagnostics_tool action=motion_sensor_history"

        assertTrue(evaluateQuickPromptSend(prompt, idle).shouldSend)
        assertTrue(evaluateQuickPromptSend("  $prompt  ", idle).shouldSend)
        assertEquals(null, evaluateQuickPromptSend(prompt, idle).blockedStatus)
    }
}
