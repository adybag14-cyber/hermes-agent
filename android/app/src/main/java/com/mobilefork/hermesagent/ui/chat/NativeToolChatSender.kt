package com.mobilefork.hermesagent.ui.chat

import android.content.Context
import com.mobilefork.hermesagent.api.ChatContentPart
import com.mobilefork.hermesagent.api.ChatMessage
import org.json.JSONObject

internal data class NativeToolChatSendResult(
    val content: String,
    val executedToolCalls: Int = 0,
    val modelRequestCount: Int = 0,
)

data class NativeAgentEvent(
    val type: AgentEventType,
    val title: String,
    val content: String,
)

internal object NativeToolChatSender {
    @Volatile
    private var activeClient: NativeToolCallingChatClient? = null

    fun cancelActive() {
        activeClient?.cancel()
    }

    fun extractDirectDiagnosticsArguments(prompt: String): JSONObject? {
        return NativeToolCallingChatClient.extractExplicitAndroidDiagnosticsArguments(prompt)
            ?: NativeToolCallingChatClient.extractImplicitAndroidDiagnosticsArguments(prompt)
    }

    fun extractDirectReadOnlyTerminalCommand(prompt: String): String? {
        return NativeToolCallingChatClient.inferSafeNaturalTerminalCommand(prompt)
    }

    fun extractDirectLinuxSandboxPrompt(prompt: String): Boolean {
        return NativeToolCallingChatClient.isGuestLinuxSandboxIntent(prompt) &&
            (
                "linux_sandbox_tool" in prompt.lowercase() ||
                    "mcp_run_in_proot" in prompt.lowercase() ||
                    "linux sandbox" in prompt.lowercase()
                )
    }

    fun executeDirectLinuxSandbox(context: Context, prompt: String): NativeToolChatSendResult? {
        val client = NativeToolCallingChatClient(context.applicationContext)
        activeClient = client
        return try {
            client.executeExplicitLinuxSandboxRequest(prompt)?.let { result ->
                NativeToolChatSendResult(
                    content = result.content,
                    executedToolCalls = result.executedToolCalls,
                    modelRequestCount = result.modelRequestCount,
                )
            }
        } finally {
            if (activeClient === client) activeClient = null
        }
    }

    fun executeDirectReadOnlyTerminal(context: Context, prompt: String): NativeToolChatSendResult? {
        val client = NativeToolCallingChatClient(context.applicationContext)
        activeClient = client
        return try {
            client.executeSafeNaturalTerminalRequest(prompt)?.let { result ->
                NativeToolChatSendResult(
                    content = result.content,
                    executedToolCalls = result.executedToolCalls,
                    modelRequestCount = result.modelRequestCount,
                )
            }
        } finally {
            if (activeClient === client) activeClient = null
        }
    }

    fun send(
        context: Context,
        baseUrl: String,
        modelName: String,
        apiKey: String? = null,
        providerId: String = "",
        sessionId: String,
        userText: String,
        userContentParts: List<ChatContentPart>,
        priorMessages: List<ChatMessage>,
        relevantMemoryContext: String,
        onEvent: (NativeAgentEvent) -> Unit = {},
    ): NativeToolChatSendResult {
        val client = NativeToolCallingChatClient(context.applicationContext)
        activeClient = client
        return try {
            val result = client.send(
                baseUrl = baseUrl,
                modelName = modelName,
                apiKey = apiKey,
                providerId = providerId,
                sessionId = sessionId,
                userText = userText,
                userContentParts = userContentParts,
                priorMessages = priorMessages,
                relevantMemoryContext = relevantMemoryContext,
                onEvent = onEvent,
            )
            NativeToolChatSendResult(
                content = result.content,
                executedToolCalls = result.executedToolCalls,
                modelRequestCount = result.modelRequestCount,
            )
        } finally {
            if (activeClient === client) activeClient = null
        }
    }
}
