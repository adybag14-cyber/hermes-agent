package com.mobilefork.hermesagent.ui.chat

import android.content.Context
import com.mobilefork.hermesagent.api.ChatContentPart
import com.mobilefork.hermesagent.api.ChatMessage
import org.json.JSONObject

internal data class NativeToolChatSendResult(
    val content: String,
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

    fun send(
        context: Context,
        baseUrl: String,
        modelName: String,
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
                sessionId = sessionId,
                userText = userText,
                userContentParts = userContentParts,
                priorMessages = priorMessages,
                relevantMemoryContext = relevantMemoryContext,
                onEvent = onEvent,
            )
            NativeToolChatSendResult(content = result.content)
        } finally {
            if (activeClient === client) activeClient = null
        }
    }
}
