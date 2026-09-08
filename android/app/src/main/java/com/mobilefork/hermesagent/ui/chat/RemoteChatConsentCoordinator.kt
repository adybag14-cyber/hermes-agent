package com.mobilefork.hermesagent.ui.chat

import android.content.Context
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.privacy.RemoteProcessingConsentStore
import com.mobilefork.hermesagent.privacy.RemoteProcessingTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PendingRemoteChatRequest(
    val text: String,
    val attachments: List<ChatAttachment>,
    val sessionId: String,
    val target: RemoteProcessingTarget,
)

/** A declined/back-dismissed disclosure does not persist a chat or cause a request. */
internal class RemoteChatConsentCoordinator(private val context: Context) {
    private val store = RemoteProcessingConsentStore(context)
    private var pending: PendingRemoteChatRequest? = null
    private val pendingTarget = MutableStateFlow<RemoteProcessingTarget?>(null)
    val target = pendingTarget.asStateFlow()

    fun admit(text: String, attachments: List<ChatAttachment>, sessionId: String): Boolean {
        if (!com.mobilefork.hermesagent.BuildConfig.HERMES_PLAY_EDITION && attachments.isEmpty() &&
            NativeDirectToolAuthorityParser.parse(text).source != NativeDirectToolAuthority.Source.NONE) return true
        val target = RemoteProcessingTarget.fromSettings(AppSettingsStore(context).load()) ?: return true
        if (store.allowed(target)) return true
        pending = PendingRemoteChatRequest(text, attachments.toList(), sessionId, target)
        pendingTarget.value = target
        return false
    }

    fun accept(): PendingRemoteChatRequest? {
        val request = pending ?: return null
        store.accept(request.target)
        decline()
        return request
    }

    fun decline() {
        pending = null
        pendingTarget.value = null
    }
}
