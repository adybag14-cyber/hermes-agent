package com.mobilefork.hermesagent.ui.chat

import com.mobilefork.hermesagent.data.ConversationStore
import com.mobilefork.hermesagent.data.StoredConversationMessage
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
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationStoreRobotest {
    private lateinit var store: ConversationStore

    @Before
    fun setUp() {
        store = ConversationStore(RuntimeEnvironment.getApplication())
        store.clearAll()
    }

    @After
    fun tearDown() {
        store.clearAll()
    }

    @Test
    fun chosenTitleSurvivesMessageBatchesStreamingAndReload() {
        val conversation = store.createNewConversation("我的项目计划")
        store.upsertMessages(conversation.sessionId, listOf(
            StoredConversationMessage("question", "user", "你好", 1L),
            StoredConversationMessage("reply", "assistant", "", 2L),
        ))
        store.updateMessageContentInMemory(conversation.sessionId, "reply", "Streaming reply")
        store.flushCacheToDisk()
        store = ConversationStore(RuntimeEnvironment.getApplication())
        assertEquals("我的项目计划", store.loadConversation(conversation.sessionId)?.title)
        val active = store.createNewConversation()
        assertFalse(store.renameConversation(conversation.sessionId, " \n\t "))
        assertTrue(store.renameConversation(conversation.sessionId, "New chat"))
        store.insertMessageBefore(conversation.sessionId, "reply", StoredConversationMessage("call", "tool_call", "pwd", 3L))
        store.updateMessageContent(conversation.sessionId, "reply", "Complete reply")
        assertEquals(active.sessionId, store.currentSessionId())
        assertEquals("New chat", store.loadConversation(conversation.sessionId)?.title)
        assertFalse(store.listConversationSummaries().first { it.sessionId == conversation.sessionId }.isDefaultTitle)
    }

    @Test
    fun legacyHistoryCanRegenerateLocallyWithoutChangingMessagesOrActiveSelection() {
        val context = RuntimeEnvironment.getApplication()
        val legacy = """[{"sessionId":"legacy","title":"你好","updatedAtEpochMs":1,"messages":[
            {"id":"greeting","role":"user","content":"你好！","createdAtEpochMs":1},
            {"id":"reply","role":"assistant","content":"provider text is not a title source","createdAtEpochMs":2},
            {"id":"question","role":"user","content":"请检查安卓存储空间和可用内存","createdAtEpochMs":3}
        ]}]"""
        context.getSharedPreferences("hermes_android_conversation", 0).edit()
            .putString("conversations_json", legacy).putString("session_id", "legacy").commit()
        store = ConversationStore(context)
        val messagesBefore = store.loadConversation("legacy")!!.messages
        val active = store.createNewConversation()
        assertTrue(store.regenerateConversationTitle("legacy"))
        assertEquals(active.sessionId, store.currentSessionId())
        assertEquals("请检查安卓存储空间和可用内存", store.loadConversation("legacy")!!.title)
        assertEquals(messagesBefore, store.loadConversation("legacy")!!.messages)
        store.updateMessageContentInMemory("legacy", "reply", "New streamed result")
        store.flushCacheToDisk()
        store = ConversationStore(context)
        assertEquals("请检查安卓存储空间和可用内存", store.loadConversation("legacy")!!.title)
        assertTrue(store.renameConversation("legacy", "😀".repeat(100)))
        val title = store.loadConversation("legacy")!!.title
        assertEquals(96, title.codePointCount(0, title.length))
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun deletionPreservesAnotherActiveChatAndLateEventsCannotResurrectDeletedChat() {
        val active = store.createNewConversation()
        val inactive = store.createNewConversation()
        val newest = store.createNewConversation()
        store.switchConversation(active.sessionId)
        store.clearConversation(inactive.sessionId)
        assertEquals(active.sessionId, store.currentSessionId())
        store.upsertMessage(inactive.sessionId, StoredConversationMessage("late", "assistant", "late", 1L))
        store.insertMessageBefore(inactive.sessionId, "late", StoredConversationMessage("tool", "tool_result", "late", 2L))
        store.flushCacheToDisk()
        store = ConversationStore(RuntimeEnvironment.getApplication())
        assertEquals(null, store.loadConversation(inactive.sessionId))
        assertEquals(active.sessionId, store.currentSessionId())
        store.clearConversation(active.sessionId)
        assertEquals(newest.sessionId, store.currentSessionId())
        store.clearConversation(newest.sessionId)
        assertTrue(store.currentConversationMessages().isEmpty())
        assertFalse(store.currentSessionId() in setOf(active.sessionId, inactive.sessionId, newest.sessionId))
    }

    @Test
    fun terminalPlaceholderUpdateOnlyReplacesBlankAssistantContent() {
        val conversation = store.createNewConversation()
        store.upsertMessage(
            conversation.sessionId,
            StoredConversationMessage(
                id = "assistant-placeholder",
                role = "assistant",
                content = "",
                createdAtEpochMs = 1L,
            ),
        )

        assertTrue(
            store.updateBlankMessageContent(
                sessionId = conversation.sessionId,
                messageId = "assistant-placeholder",
                newContent = "This reply was stopped by the user.",
            ),
        )
        assertEquals(
            "This reply was stopped by the user.",
            store.loadConversation(conversation.sessionId)?.messages?.single()?.content,
        )
        assertFalse(
            store.updateBlankMessageContent(
                sessionId = conversation.sessionId,
                messageId = "assistant-placeholder",
                newContent = "A late answer must not overwrite Stop.",
            ),
        )
        assertEquals(
            "This reply was stopped by the user.",
            store.loadConversation(conversation.sessionId)?.messages?.single()?.content,
        )

        store.updateMessageContent(conversation.sessionId, "assistant-placeholder", "")
        assertTrue(
            store.updateBlankMessageContent(
                sessionId = conversation.sessionId,
                messageId = "assistant-placeholder",
                newContent = "A real answer won the race.",
            ),
        )
        assertFalse(
            store.updateBlankMessageContent(
                sessionId = conversation.sessionId,
                messageId = "assistant-placeholder",
                newContent = "Hermes could not complete this reply.",
            ),
        )
        assertEquals(
            "A real answer won the race.",
            store.loadConversation(conversation.sessionId)?.messages?.single()?.content,
        )
    }

    @Test
    fun allReadModifyWriteWritersUseTheSameStoreMonitor() {
        val writerNames = setOf(
            "switchConversation",
            "createNewConversation",
            "upsertMessage",
            "upsertMessages",
            "insertMessageBefore",
            "updateMessageContent",
            "updateBlankMessageContent",
            "clearCurrentConversation",
            "clearConversation",
            "clearAll",
            "clearSession",
            "updateMessageContentInMemory",
            "flushCacheToDisk",
            "renameConversation",
            "regenerateConversationTitle",
        )

        writerNames.forEach { writerName ->
            val overloads = ConversationStore::class.java.declaredMethods
                .filter { method -> method.name == writerName }
            assertTrue("Expected ConversationStore.$writerName to exist", overloads.isNotEmpty())
            assertTrue(
                "Every ConversationStore.$writerName overload must synchronize on the store instance",
                overloads.all { method -> Modifier.isSynchronized(method.modifiers) },
            )
        }
    }

    @Test
    fun writerCannotPassWhileAnotherWriterOwnsTheStoreMonitor() {
        val conversation = store.createNewConversation()
        val writerStarted = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)

        val writer = synchronized(store) {
            thread(name = "conversation-store-writer") {
                writerStarted.countDown()
                store.upsertMessage(
                    conversation.sessionId,
                    StoredConversationMessage(
                        id = "blocked-writer",
                        role = "user",
                        content = "serialized",
                        createdAtEpochMs = 2L,
                    ),
                )
                writerFinished.countDown()
            }.also {
                assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
                assertFalse(
                    "The writer must wait for the shared ConversationStore monitor",
                    writerFinished.await(100, TimeUnit.MILLISECONDS),
                )
            }
        }

        writer.join(5_000L)
        assertFalse(writer.isAlive)
        assertEquals(
            "serialized",
            store.loadConversation(conversation.sessionId)
                ?.messages
                ?.single { message -> message.id == "blocked-writer" }
                ?.content,
        )
    }

    @Test
    fun simultaneousReadModifyWriteOperationsDoNotLoseMessages() {
        val conversation = store.createNewConversation()
        val assistantMessageId = "assistant-placeholder"
        store.upsertMessage(
            conversation.sessionId,
            StoredConversationMessage(
                id = assistantMessageId,
                role = "assistant",
                content = "",
                createdAtEpochMs = 1L,
            ),
        )
        val operationCount = 24
        val ready = CountDownLatch(operationCount)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(operationCount)
        val workers = (0 until operationCount).map { index ->
            thread(name = "conversation-store-race-$index") {
                ready.countDown()
                assertTrue(start.await(5, TimeUnit.SECONDS))
                val message = StoredConversationMessage(
                    id = "concurrent-$index",
                    role = if (index % 2 == 0) "tool_result" else "user",
                    content = "message-$index",
                    createdAtEpochMs = index + 2L,
                )
                if (index % 2 == 0) {
                    store.insertMessageBefore(conversation.sessionId, assistantMessageId, message)
                } else {
                    store.upsertMessage(conversation.sessionId, message)
                }
                finished.countDown()
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        workers.forEach { worker ->
            worker.join(5_000L)
            assertFalse(worker.isAlive)
        }

        val storedIds = store.loadConversation(conversation.sessionId)
            ?.messages
            ?.map { message -> message.id }
            ?.toSet()
            .orEmpty()
        assertEquals(operationCount + 1, storedIds.size)
        assertTrue(assistantMessageId in storedIds)
        (0 until operationCount).forEach { index ->
            assertTrue("Missing concurrently written message concurrent-$index", "concurrent-$index" in storedIds)
        }
    }

    @Test
    fun immediateStopWaitsForAdmissionBatchThenTerminalizesItsPersistedPlaceholder() {
        val conversation = store.createNewConversation()
        val coordinator = ChatSendRequestCoordinator()
        val messagesPersisted = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val admissionReleased = AtomicBoolean(false)
        val admittedRequest = AtomicReference<ChatSendRequestCoordinator.Request?>()
        val stoppedRequest = AtomicReference<ChatSendRequestCoordinator.Request?>()
        val stoppedTerminal = "Stopped by user"

        val beginThread = thread(name = "conversation-send-admission") {
            admittedRequest.set(
                coordinator.begin(conversation.sessionId, "assistant-admitted") {
                    store.upsertMessages(
                        conversation.sessionId,
                        listOf(
                            StoredConversationMessage(
                                id = "user-admitted",
                                role = "user",
                                content = "hello",
                                createdAtEpochMs = 1L,
                            ),
                            StoredConversationMessage(
                                id = "assistant-admitted",
                                role = "assistant",
                                content = "",
                                createdAtEpochMs = 2L,
                            ),
                        ),
                    )
                    messagesPersisted.countDown()
                    admissionReleased.set(releaseAdmission.await(5, TimeUnit.SECONDS))
                },
            )
        }
        assertTrue(messagesPersisted.await(5, TimeUnit.SECONDS))
        val stopThread = thread(name = "conversation-immediate-stop") {
            stoppedRequest.set(
                coordinator.stopActive { request ->
                    store.updateMessageContent(
                        sessionId = request.sessionId,
                        messageId = request.assistantMessageId,
                        newContent = stoppedTerminal,
                    )
                },
            )
            stopFinished.countDown()
        }

        assertFalse(
            "Stop must not retire the request until admission has persisted both messages",
            stopFinished.await(100, TimeUnit.MILLISECONDS),
        )
        val admittedSnapshot = store.loadConversation(conversation.sessionId)?.messages.orEmpty()
        assertEquals(listOf("user-admitted", "assistant-admitted"), admittedSnapshot.map { it.id })
        assertEquals("", admittedSnapshot.last().content)

        releaseAdmission.countDown()
        beginThread.join(5_000L)
        stopThread.join(5_000L)

        assertFalse(beginThread.isAlive)
        assertFalse(stopThread.isAlive)
        assertTrue(admissionReleased.get())
        assertEquals(admittedRequest.get(), stoppedRequest.get())
        val finalMessages = store.loadConversation(conversation.sessionId)?.messages.orEmpty()
        assertEquals("hello", finalMessages.single { it.id == "user-admitted" }.content)
        assertEquals(stoppedTerminal, finalMessages.single { it.id == "assistant-admitted" }.content)
    }

    @Test
    fun lifecycleRetirementTerminalizesPersistedBlankAndRejectsLateCallbacksAndFallback() {
        val conversation = store.createNewConversation()
        val assistantMessageId = "assistant-destroyed"
        store.upsertMessage(
            conversation.sessionId,
            StoredConversationMessage(
                id = assistantMessageId,
                role = "assistant",
                content = "",
                createdAtEpochMs = 1L,
            ),
        )
        val coordinator = ChatSendRequestCoordinator()
        val request = coordinator.begin(conversation.sessionId, assistantMessageId) {}!!
        val lifecycleTerminal = "This reply was interrupted because the chat was closed."

        assertEquals(
            request,
            coordinator.retireActive { ownedRequest ->
                store.updateMessageContent(
                    sessionId = ownedRequest.sessionId,
                    messageId = ownedRequest.assistantMessageId,
                    newContent = lifecycleTerminal,
                )
            },
        )
        val persistedTerminal = store.loadConversation(conversation.sessionId)
            ?.messages
            ?.single { message -> message.id == assistantMessageId }
            ?.content
            .orEmpty()
        assertTrue(persistedTerminal.isNotBlank())
        assertEquals(lifecycleTerminal, persistedTerminal)

        assertFalse(
            coordinator.mutateIfActive(request) {
                store.updateMessageContent(conversation.sessionId, assistantMessageId, "late delta")
            },
        )
        assertFalse(
            coordinator.finishIfActive(request) {
                store.updateMessageContent(conversation.sessionId, assistantMessageId, "late completion")
                true
            },
        )
        assertFalse(
            coordinator.finishIfActive(request) {
                store.updateMessageContent(conversation.sessionId, assistantMessageId, "late fallback")
                true
            },
        )
        assertFalse(
            coordinator.jobCompleted(request) {
                store.updateMessageContent(conversation.sessionId, assistantMessageId, "late job terminal")
            },
        )
        assertEquals(
            lifecycleTerminal,
            store.loadConversation(conversation.sessionId)
                ?.messages
                ?.single { message -> message.id == assistantMessageId }
                ?.content,
        )
    }

    @Test
    fun fallbackReplacesOwnedPartialButCannotOverwriteAStopWinner() {
        val conversation = store.createNewConversation()
        val assistantMessageId = "assistant-fallback"
        store.upsertMessage(
            conversation.sessionId,
            StoredConversationMessage(
                id = assistantMessageId,
                role = "assistant",
                content = "partial SSE",
                createdAtEpochMs = 1L,
            ),
        )

        val stopWinnerCoordinator = ChatSendRequestCoordinator()
        val stopWinnerRequest = stopWinnerCoordinator.begin(conversation.sessionId, assistantMessageId) {}!!
        val stopEntered = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val fallbackFinished = CountDownLatch(1)
        val fallbackCommitted = AtomicBoolean(true)
        val stopThread = thread(name = "fallback-stop-winner") {
            stopWinnerCoordinator.stopActive { request ->
                stopEntered.countDown()
                releaseStop.await(5, TimeUnit.SECONDS)
                store.updateMessageContent(request.sessionId, request.assistantMessageId, "stopped terminal")
            }
        }
        assertTrue(stopEntered.await(5, TimeUnit.SECONDS))
        val staleFallbackThread = thread(name = "fallback-after-stop") {
            fallbackCommitted.set(
                stopWinnerCoordinator.finishIfActive(stopWinnerRequest) {
                    store.updateMessageContent(conversation.sessionId, assistantMessageId, "full fallback")
                    true
                },
            )
            fallbackFinished.countDown()
        }

        assertFalse(fallbackFinished.await(100, TimeUnit.MILLISECONDS))
        releaseStop.countDown()
        stopThread.join(5_000L)
        staleFallbackThread.join(5_000L)
        assertFalse(stopThread.isAlive)
        assertFalse(staleFallbackThread.isAlive)
        assertFalse(fallbackCommitted.get())
        assertEquals(
            "stopped terminal",
            store.loadConversation(conversation.sessionId)?.messages?.single()?.content,
        )

        store.updateMessageContent(conversation.sessionId, assistantMessageId, "partial SSE retry")
        val fallbackWinnerCoordinator = ChatSendRequestCoordinator()
        val fallbackWinnerRequest = fallbackWinnerCoordinator.begin(conversation.sessionId, assistantMessageId) {}!!
        val fallbackEntered = CountDownLatch(1)
        val releaseFallback = CountDownLatch(1)
        val stopAfterFallbackFinished = CountDownLatch(1)
        val fallbackWon = AtomicBoolean(false)
        val stopAfterFallback = AtomicReference<ChatSendRequestCoordinator.Request?>()
        val fallbackThread = thread(name = "owned-fallback-winner") {
            fallbackWon.set(
                fallbackWinnerCoordinator.finishIfActive(fallbackWinnerRequest) {
                    fallbackEntered.countDown()
                    releaseFallback.await(5, TimeUnit.SECONDS)
                    store.updateMessageContent(conversation.sessionId, assistantMessageId, "full fallback")
                    true
                },
            )
        }
        assertTrue(fallbackEntered.await(5, TimeUnit.SECONDS))
        val lateStopThread = thread(name = "stop-after-fallback") {
            stopAfterFallback.set(
                fallbackWinnerCoordinator.stopActive { request ->
                    store.updateMessageContent(request.sessionId, request.assistantMessageId, "late stop")
                },
            )
            stopAfterFallbackFinished.countDown()
        }

        assertFalse(stopAfterFallbackFinished.await(100, TimeUnit.MILLISECONDS))
        releaseFallback.countDown()
        fallbackThread.join(5_000L)
        lateStopThread.join(5_000L)
        assertFalse(fallbackThread.isAlive)
        assertFalse(lateStopThread.isAlive)
        assertTrue(fallbackWon.get())
        assertEquals(null, stopAfterFallback.get())
        assertEquals(
            "full fallback",
            store.loadConversation(conversation.sessionId)?.messages?.single()?.content,
        )
    }
}
