package com.mobilefork.hermesagent.ui.chat

import com.mobilefork.hermesagent.api.HermesToolActivity
import com.mobilefork.hermesagent.api.HermesToolPhase
import com.mobilefork.hermesagent.data.ConversationStore
import com.mobilefork.hermesagent.data.StoredConversationMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

internal fun appendOwnedToolActivity(
    coordinator: ChatSendRequestCoordinator,
    request: ChatSendRequestCoordinator.Request,
    store: ConversationStore,
    uiState: MutableStateFlow<ChatUiState>,
    event: NativeAgentEvent,
    eventId: String = UUID.randomUUID().toString(),
) {
    coordinator.mutateIfActive(request) {
        val snapshot = uiState.value
        if (snapshot.activeConversationId != request.sessionId || snapshot.messages.any { it.id == eventId }) return@mutateIfActive
        if (snapshot.messages.none { it.id == request.assistantMessageId }) return@mutateIfActive
        val message = ChatUiMessage(eventId, event.type.persistedRole,
            listOf(event.title, event.content).filter { it.isNotBlank() }.joinToString("\n"), System.currentTimeMillis())
        store.insertMessageBefore(request.sessionId, request.assistantMessageId,
            StoredConversationMessage(message.id, message.role, message.content, message.createdAtEpochMs))
        uiState.update { state ->
            val index = state.messages.indexOfFirst { it.id == request.assistantMessageId }
            if (index < 0 || state.activeConversationId != request.sessionId) state else
                state.copy(messages = state.messages.toMutableList().apply { add(index, message) })
        }
    }
}

internal fun HermesToolActivity.toTimelineEvent(): NativeAgentEvent = NativeAgentEvent(
    type = if (phase == HermesToolPhase.RUNNING) AgentEventType.ToolCall else AgentEventType.ToolResult,
    title = tool,
    content = detail,
)

internal fun mayReplayFailedStream(receivedToolActivity: Boolean, agentEndpoint: Boolean = false): Boolean =
    !receivedToolActivity && !agentEndpoint
