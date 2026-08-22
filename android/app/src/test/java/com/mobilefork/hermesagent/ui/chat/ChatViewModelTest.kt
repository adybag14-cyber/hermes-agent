package com.mobilefork.hermesagent.ui.chat

import com.mobilefork.hermesagent.api.ChatContentPart
import com.mobilefork.hermesagent.api.ChatMessage
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.backend.LocalBackendStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ChatViewModelTest {
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
        val arguments = directNativeDiagnosticArgumentsForPrompt("Write a short welcome message")

        assertEquals(null, arguments)
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
