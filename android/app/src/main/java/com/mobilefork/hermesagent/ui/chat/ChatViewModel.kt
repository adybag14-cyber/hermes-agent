package com.mobilefork.hermesagent.ui.chat

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobilefork.hermesagent.api.ChatCompletionRequest
import com.mobilefork.hermesagent.api.ChatContentPart
import com.mobilefork.hermesagent.api.ChatMessage
import com.mobilefork.hermesagent.api.HermesEndpointUrl
import com.mobilefork.hermesagent.api.HermesApiClient
import com.mobilefork.hermesagent.api.HermesSseClient
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.backend.LocalBackendStatus
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import com.mobilefork.hermesagent.data.ConversationStore
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.HermesNetworkPolicy
import com.mobilefork.hermesagent.data.McpPromptCacheResendPolicy
import com.mobilefork.hermesagent.data.McpSettingsStore
import com.mobilefork.hermesagent.data.ProviderPresets
import com.mobilefork.hermesagent.data.SecureSecretsStore
import com.mobilefork.hermesagent.data.StoredConversationAttachment
import com.mobilefork.hermesagent.data.StoredConversationMessage
import com.mobilefork.hermesagent.device.AutomationPublicationGate
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.hermesStringsFor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

private val DIRECT_OPENAI_COMPATIBLE_PROVIDERS = setOf(
    "openrouter",
    "openai",
    "codex",
    "gemini",
    "alibaba",
    "alibaba-coding-plan",
    "qwen-oauth",
    "zai",
    "zai-coding-plan",
    "bigmodel",
    "xai",
    "xai-oauth",
    "nous",
    "bigmodel",
    "groq",
    "mistral",
    "perplexity",
    "cerebras",
    "together",
    "fireworks",
    "deepinfra",
)
private val RESPONSES_API_PROVIDERS = setOf("openai", "codex")
private const val STREAM_PERSIST_INTERVAL_MS = 400L

/**
 * Linearizes ownership of one chat request without holding the lock across model or network work.
 * Every persistence/UI mutation belonging to a send must enter through [mutateIfActive] or
 * [finishIfActive]. Stop retires ownership under the same lock, waits operation cleanup outside
 * that mutation gate, then terminalizes before releasing replacement admission.
 */
internal class ChatSendRequestCoordinator(
    private val nativeUnwindTimeoutMs: Long = DEFAULT_NATIVE_UNWIND_TIMEOUT_MS,
) {
    internal class Request internal constructor(
        val generation: Long,
        val sessionId: String,
        val assistantMessageId: String,
    ) {
        internal var cancelJob: (() -> Unit)? = null
        internal var cancelNetwork: (() -> Unit)? = null
        internal var awaitNativeUnwind: ((Long) -> Boolean)? = null
        internal var streamBuffer: StringBuilder? = null
        internal var lastStreamPersistMs: Long = 0L
    }

    private val lock = Any()
    private var nextGeneration = 0L
    private var activeRequest: Request? = null
    private var retirementLatch: CountDownLatch? = null

    fun begin(
        sessionId: String,
        assistantMessageId: String,
        onBegin: (Request) -> Unit,
    ): Request? {
        while (true) {
            var waitForRetirement: CountDownLatch? = null
            var admitted: Request? = null
            val decided = synchronized(lock) {
                val retiring = retirementLatch
                if (retiring != null) {
                    waitForRetirement = retiring
                    false
                } else if (activeRequest != null) {
                    true
                } else {
                    val request = Request(
                        generation = ++nextGeneration,
                        sessionId = sessionId,
                        assistantMessageId = assistantMessageId,
                    )
                    activeRequest = request
                    try {
                        onBegin(request)
                        admitted = request
                        true
                    } catch (error: Throwable) {
                        activeRequest = null
                        throw error
                    }
                }
            }
            if (decided) return admitted
            checkNotNull(waitForRetirement).await()
        }
    }

    fun isActive(request: Request): Boolean = synchronized(lock) {
        activeRequest === request
    }

    fun mutateIfActive(request: Request, mutation: (Request) -> Unit): Boolean = synchronized(lock) {
        if (activeRequest !== request) return@synchronized false
        mutation(request)
        true
    }

    fun finishIfActive(request: Request, mutation: (Request) -> Boolean): Boolean = synchronized(lock) {
        if (activeRequest !== request) return@synchronized false
        if (!mutation(request)) return@synchronized false
        activeRequest = null
        request.cancelNetwork = null
        request.awaitNativeUnwind = null
        true
    }

    fun attachJob(request: Request, cancel: () -> Unit): Boolean = synchronized(lock) {
        if (activeRequest !== request) return@synchronized false
        request.cancelJob = cancel
        true
    }

    fun attachNetwork(request: Request, cancel: () -> Unit): Boolean =
        attachNetwork(request, cancel, awaitNativeUnwind = null)

    fun attachNetwork(
        request: Request,
        cancel: () -> Unit,
        awaitNativeUnwind: ((Long) -> Boolean)?,
    ): Boolean = synchronized(lock) {
        if (activeRequest !== request) return@synchronized false
        request.cancelNetwork = cancel
        request.awaitNativeUnwind = awaitNativeUnwind
        true
    }

    /**
     * A request-owned publication token. The publication itself must remain a short durable/UI
     * commit: execution and callback waits belong outside this coordinator lock. Lock order is
     * request ownership first, then any short per-store transaction lock used by [publication].
     */
    fun publicationGate(request: Request): AutomationPublicationGate = AutomationPublicationGate { publication ->
        mutateIfActive(request) { publication() }
    }

    /** Linearize a prepared native/direct operation's start with Stop/navigation ownership. */
    fun claimWorkStartIfActive(request: Request, claimStart: () -> Boolean): Boolean = synchronized(lock) {
        if (activeRequest !== request) return@synchronized false
        claimStart()
    }

    /**
     * Retire and cancel the current request before another request can begin. Slow operation
     * unwind runs outside the mutation lock while a retirement latch prevents B admission. This
     * lets rejected late callbacks observe retired ownership instead of deadlocking behind Stop.
     */
    fun stopActive(onStop: (Request) -> Unit): Request? = retireCurrent(onStop)

    /**
     * Retire work when the owning ViewModel is destroyed. [onRetire] runs as part of the same
     * ownership transition. Request-local transports and the coroutine job are cancelled first,
     * then callers persist a nonblank lifecycle terminal before replacement admission is released.
     * Unlike [stopActive], this does not imply a user-requested Stop status.
     */
    fun retireActive(onRetire: (Request) -> Unit): Request? = retireCurrent(onRetire)

    /** Retire an unexpectedly completed job only if it still owns the active send. */
    fun jobCompleted(request: Request, onUnexpectedCompletion: (Request) -> Unit): Boolean = synchronized(lock) {
        request.cancelJob = null
        request.cancelNetwork = null
        request.awaitNativeUnwind = null
        if (activeRequest !== request) return@synchronized false
        activeRequest = null
        onUnexpectedCompletion(request)
        true
    }

    private fun cancelOwnedWorkBeforeTerminal(
        cancelNetwork: (() -> Unit)?,
        cancelJob: (() -> Unit)?,
        awaitNativeUnwind: ((Long) -> Boolean)?,
    ) {
        var failure: Throwable? = null
        fun capture(block: () -> Unit) {
            try {
                block()
            } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }

        // The sticky request-local token is published first and must not wait on a native
        // publication lock. Cancelling the coroutine then interrupts runInterruptible so the
        // exact operation worker can finish its owned process/callback cleanup.
        cancelNetwork?.let { capture(it) }
        cancelJob?.let { capture(it) }
        awaitNativeUnwind?.let { await ->
            capture {
                check(await(nativeUnwindTimeoutMs)) {
                    "Native request cleanup could not be verified within ${nativeUnwindTimeoutMs}ms; " +
                        "the native operation lane is fail-closed until the app is restarted."
                }
            }
        }
        failure?.let { throw it }
    }

    private data class Retirement(
        val request: Request,
        val cancelNetwork: (() -> Unit)?,
        val cancelJob: (() -> Unit)?,
        val awaitNativeUnwind: ((Long) -> Boolean)?,
        val latch: CountDownLatch,
    )

    private fun retireCurrent(onTerminal: (Request) -> Unit): Request? {
        val retirement = synchronized(lock) {
            val request = activeRequest ?: return@synchronized null
            activeRequest = null
            request.streamBuffer = null
            val latch = CountDownLatch(1)
            check(retirementLatch == null) { "A chat request retirement is already in progress" }
            retirementLatch = latch
            Retirement(
                request = request,
                cancelNetwork = request.cancelNetwork.also { request.cancelNetwork = null },
                cancelJob = request.cancelJob.also { request.cancelJob = null },
                awaitNativeUnwind = request.awaitNativeUnwind.also { request.awaitNativeUnwind = null },
                latch = latch,
            )
        } ?: return null

        var failure = runCatching {
            cancelOwnedWorkBeforeTerminal(
                retirement.cancelNetwork,
                retirement.cancelJob,
                retirement.awaitNativeUnwind,
            )
        }.exceptionOrNull()
        val terminalFailure = runCatching {
            synchronized(lock) {
                onTerminal(retirement.request)
            }
        }.exceptionOrNull()
        val cancellationFailure = failure
        if (cancellationFailure == null) {
            failure = terminalFailure
        } else if (terminalFailure != null && terminalFailure !== cancellationFailure) {
            cancellationFailure.addSuppressed(terminalFailure)
        }
        synchronized(lock) {
            if (retirementLatch === retirement.latch) retirementLatch = null
            retirement.latch.countDown()
        }
        failure?.let { throw it }
        return retirement.request
    }

    private companion object {
        const val DEFAULT_NATIVE_UNWIND_TIMEOUT_MS = 10_000L
    }
}

/**
 * Prevents the asynchronous initial store read from publishing a snapshot after a user action has
 * already established newer UI state. The check and publication share one lock, so initialization
 * either wins completely before the mutation or is rejected completely after it.
 */
internal class ChatInitializationGuard {
    private val lock = Any()
    private var generation = 0L

    fun capture(): Long = synchronized(lock) { generation }

    fun invalidate() = synchronized(lock) {
        generation += 1L
    }

    fun applyIfCurrent(capturedGeneration: Long, publish: () -> Unit): Boolean = synchronized(lock) {
        if (generation != capturedGeneration) return@synchronized false
        publish()
        true
    }
}

/**
 * Tracks one request's exact OkHttp calls through response-body close, not merely until a
 * synchronous [Call.execute] returns response headers. OkHttp's dispatcher removes a synchronous
 * call at that earlier boundary, so dispatcher cancellation alone cannot reliably stop a body
 * which is still being streamed.
 */
internal class RequestOwnedHttpTransport(
    baseClient: OkHttpClient = OkHttpClient.Builder().build(),
    private val cancellationMessage: String = "Chat fallback stopped before network dispatch",
) {
    private val calls = RequestOwnedCallRegistry(cancellationMessage)

    val client: OkHttpClient = baseClient.newBuilder().let { builder ->
        // OkHttpClient.newBuilder() shares the base Dispatcher by default. Request ownership must
        // be enforced here, even when a caller supplies a shared base client, so cancelling this
        // transport can never reach another request's queued or running calls.
        builder.dispatcher(Dispatcher())
        builder.interceptors().add(0, Interceptor(calls::intercept))
        builder.build()
    }

    fun cancel() {
        calls.cancelAll()
        // Also reaches asynchronous calls which are still queued and have not entered the
        // tracking interceptor yet. This dispatcher belongs only to this request transport.
        client.dispatcher.cancelAll()
    }

    internal fun activeCallCountForTest(): Int = calls.activeCallCount()
}

private class RequestOwnedCallRegistry(
    private val cancellationMessage: String,
) {
    private val lock = Any()
    private var cancellationRequested = false
    private val activeCalls = mutableSetOf<Call>()

    fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        synchronized(lock) {
            if (cancellationRequested) {
                call.cancel()
                throw InterruptedIOException(cancellationMessage)
            }
            activeCalls += call
        }
        val response = try {
            chain.proceed(chain.request())
        } catch (error: Throwable) {
            forget(call)
            throw error
        }
        val body = response.body
        if (body == null) {
            forget(call)
            return response
        }
        return response.newBuilder()
            .body(RequestOwnedResponseBody(body) { forget(call) })
            .build()
    }

    fun cancelAll() {
        val snapshot = synchronized(lock) {
            cancellationRequested = true
            activeCalls.toList()
        }
        snapshot.forEach(Call::cancel)
    }

    fun activeCallCount(): Int = synchronized(lock) { activeCalls.size }

    private fun forget(call: Call) = synchronized(lock) {
        activeCalls -= call
        Unit
    }
}

private class RequestOwnedResponseBody(
    private val delegate: ResponseBody,
    onFinished: () -> Unit,
) : ResponseBody() {
    private val finished = AtomicBoolean(false)
    private val trackedSource: BufferedSource = object : ForwardingSource(delegate.source()) {
        private fun finishOnce() {
            if (finished.compareAndSet(false, true)) onFinished()
        }

        override fun read(sink: Buffer, byteCount: Long): Long {
            return try {
                super.read(sink, byteCount).also { if (it == -1L) finishOnce() }
            } catch (error: Throwable) {
                finishOnce()
                throw error
            }
        }

        override fun close() {
            try {
                super.close()
            } finally {
                finishOnce()
            }
        }
    }.buffer()

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = trackedSource
}

internal fun mergeInitialChatState(loaded: ChatUiState, current: ChatUiState): ChatUiState {
    return loaded.copy(
        input = current.input,
        attachments = current.attachments,
        isSending = current.isSending,
        isListening = current.isListening,
        isShowingHistory = current.isShowingHistory,
        showIntermediateSteps = current.showIntermediateSteps,
        status = if (current.status == "Loading…") loaded.status else current.status,
        error = current.error,
    )
}

internal data class AssistantCompletionResolution(
    val content: String,
    val hasAssistantContent: Boolean,
)

internal fun resolveAssistantCompletion(
    streamedContent: String,
    localizedFailureMessage: String,
): AssistantCompletionResolution {
    require(localizedFailureMessage.isNotBlank()) { "Assistant failure terminal must not be blank" }
    return if (streamedContent.isNotBlank()) {
        AssistantCompletionResolution(content = streamedContent, hasAssistantContent = true)
    } else {
        AssistantCompletionResolution(content = localizedFailureMessage, hasAssistantContent = false)
    }
}

internal fun usesDirectOpenAiCompatibleTransport(providerId: String): Boolean {
    // The OpenAI Codex OAuth runtime uses its Responses transport and ChatGPT Web
    // uses the /conversation protocol inside the embedded Python runtime. Neither
    // token may be sent to a fabricated <base>/v1/chat/completions endpoint.
    return providerId.trim().lowercase() in DIRECT_OPENAI_COMPATIBLE_PROVIDERS
}

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val conversationStore = ConversationStore(application)
    private val _uiState = MutableStateFlow(
        ChatUiState(
            activeConversationId = "",
            activeConversationTitle = "Chat",
            status = "Loading…",
        ),
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val sendCoordinator = ChatSendRequestCoordinator()
    private val initializationGuard = ChatInitializationGuard()

    init {
        val initializationGeneration = initializationGuard.capture()
        viewModelScope.launch(Dispatchers.IO) {
            val next = buildState()
            initializationGuard.applyIfCurrent(initializationGeneration) {
                _uiState.update {
                    mergeInitialChatState(loaded = next, current = it)
                }
            }
        }
    }

    fun updateInput(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    fun attachImage(uriString: String) {
        val uri = Uri.parse(uriString)
        val details = queryAttachmentDetails(uri)
        _uiState.update { state ->
            if (state.attachments.any { it.uri == uriString }) {
                state
            } else {
                state.copy(
                    attachments = state.attachments + ChatAttachment(
                        uri = uriString,
                        displayName = details.displayName,
                        mimeType = details.mimeType,
                        sizeBytes = details.sizeBytes,
                    ),
                    status = "Image attached for multimodal Gemma requests",
                    error = "",
                )
            }
        }
    }

    fun removeAttachment(uriString: String) {
        _uiState.update { state ->
            state.copy(attachments = state.attachments.filterNot { it.uri == uriString })
        }
    }

    fun applyVoiceInput(text: String) {
        _uiState.update { state ->
            val merged = listOf(state.input.trim(), text.trim()).filter { it.isNotBlank() }.joinToString(" ")
            state.copy(input = merged, isListening = false, status = "Voice input captured", error = "")
        }
    }

    fun setListening(active: Boolean) {
        _uiState.update { it.copy(isListening = active, status = if (active) "Listening…" else it.status) }
    }

    fun setStatus(message: String) {
        _uiState.update { it.copy(status = message) }
    }

    fun clearStatus() {
        _uiState.update { it.copy(status = "") }
    }

    fun toggleIntermediateSteps() {
        _uiState.update { it.copy(showIntermediateSteps = !it.showIntermediateSteps) }
    }

    fun stopCurrentTask() {
        stopActiveSend(status = "Stopped by user")
    }

    override fun onCleared() {
        // A cancelled coroutine does not interrupt a blocking OkHttp Call.execute(). Retire
        // ownership first, terminalize the admitted placeholder without claiming the user pressed
        // Stop, and invoke the request-owned cancellation handle. Late callbacks cannot overwrite
        // this persisted terminal or enter the fallback lane after the ViewModel is destroyed.
        initializationGuard.invalidate()
        sendCoordinator.retireActive { request ->
            persistOwnedAssistantTerminal(
                sessionId = request.sessionId,
                assistantMessageId = request.assistantMessageId,
                terminalMessage = currentStrings().lifecycleInterruptedReplyMessage(),
            )
        }
        super.onCleared()
    }

    private fun stopActiveSend(status: String): Boolean {
        val terminalMessage = currentStrings().stoppedReplyMessage()
        return sendCoordinator.stopActive { request ->
            request.streamBuffer = null
            _uiState.update {
                it.copy(
                    isSending = false,
                    status = status,
                    error = "",
                )
            }
            finalizeOwnedAssistantMessage(
                sessionId = request.sessionId,
                assistantMessageId = request.assistantMessageId,
                terminalMessage = terminalMessage,
            )
        } != null
    }

    fun startNewConversation() {
        initializationGuard.invalidate()
        stopActiveSend(status = "Stopped by user")
        val conversation = conversationStore.createNewConversation()
        _uiState.value = buildState(
            activeConversationId = conversation.sessionId,
            messages = emptyList(),
            status = "Started a new chat",
        )
    }

    fun clearCurrentConversation() {
        initializationGuard.invalidate()
        stopActiveSend(status = "Stopped by user")
        val nextConversation = conversationStore.clearCurrentConversation()
        _uiState.value = buildState(
            activeConversationId = nextConversation.sessionId,
            messages = nextConversation.messages.toUiMessages(),
            status = "Cleared the previous conversation",
        )
    }

    fun showHistory() {
        _uiState.update {
            it.copy(
                isShowingHistory = true,
                conversationSummaries = loadSummaries(),
                status = "",
                error = "",
            )
        }
    }

    fun hideHistory() {
        _uiState.update { it.copy(isShowingHistory = false) }
    }

    fun openConversation(sessionId: String) {
        initializationGuard.invalidate()
        stopActiveSend(status = "Stopped by user")
        val conversation = conversationStore.switchConversation(sessionId) ?: return
        _uiState.value = buildState(
            activeConversationId = conversation.sessionId,
            messages = conversation.messages.toUiMessages(),
            isShowingHistory = false,
            status = "Opened ${conversation.title}",
        )
    }

    fun consumeCommandResult(commandText: String, feedback: String?) {
        if (feedback.isNullOrBlank()) {
            _uiState.update { it.copy(input = "", error = "", isSending = false, status = "") }
            return
        }
        initializationGuard.invalidate()
        val now = System.currentTimeMillis()
        val sessionId = conversationStore.currentSessionId()
        val userMessage = ChatUiMessage(UUID.randomUUID().toString(), "user", commandText, now)
        val assistantMessage = ChatUiMessage(UUID.randomUUID().toString(), "assistant", feedback, now + 1)
        persistMessages(sessionId, userMessage, assistantMessage)
        _uiState.update {
            it.copy(
                activeConversationId = sessionId,
                activeConversationTitle = conversationStore.currentConversation().title,
                conversationSummaries = loadSummaries(),
                messages = conversationStore.currentConversationMessages().toUiMessages(),
                input = "",
                isSending = false,
                error = "",
                status = "",
            )
        }
    }

    fun latestAssistantReply(): String {
        return _uiState.value.messages.lastOrNull { it.role == "assistant" && it.content.isNotBlank() }?.content.orEmpty()
    }

    fun sendMessage() {
        val snapshot = _uiState.value
        sendPreparedMessage(text = snapshot.input.trim(), attachments = snapshot.attachments)
    }

    fun sendQuickPrompt(prompt: String) {
        val snapshot = _uiState.value
        val decision = evaluateQuickPromptSend(prompt, snapshot)
        if (!decision.shouldSend) {
            if (decision.blockedStatus != null) {
                _uiState.update { it.copy(status = decision.blockedStatus) }
            }
            return
        }
        sendPreparedMessage(text = prompt.trim(), attachments = emptyList())
    }

    fun stageMessageEdit(messageId: String) {
        val snapshot = _uiState.value
        if (snapshot.isSending) {
            _uiState.update { it.copy(status = "Wait for Hermes to finish before editing a sent message.") }
            return
        }
        val message = snapshot.messages.firstOrNull { it.id == messageId && it.role == "user" } ?: return
        _uiState.update {
            it.copy(
                input = message.content,
                attachments = message.attachments,
                status = "Editing sent message; send to resubmit.",
                error = "",
                isShowingHistory = false,
            )
        }
    }

    fun resendMessage(messageId: String) {
        val snapshot = _uiState.value
        if (snapshot.isSending) {
            return
        }
        val message = snapshot.messages.firstOrNull { it.id == messageId && it.role == "user" } ?: return
        sendPreparedMessage(text = message.content.trim(), attachments = message.attachments)
    }

    private fun sendPreparedMessage(text: String, attachments: List<ChatAttachment>) {
        // Order the initial store publication before the send snapshot. If initialization already
        // owns its guard, this waits for it and the snapshot sees loaded history; if send wins,
        // the later stale publication is rejected.
        initializationGuard.invalidate()
        val snapshot = _uiState.value
        if ((text.isEmpty() && attachments.isEmpty()) || snapshot.isSending) {
            return
        }
        val sessionId = conversationStore.currentSessionId()
        val priorConversationMessages = buildPriorChatRequestMessages(snapshot.messages)
        val now = System.currentTimeMillis()
        val userMessage = ChatUiMessage(UUID.randomUUID().toString(), "user", text, now, attachments)
        val assistantMessageId = UUID.randomUUID().toString()
        val assistantPlaceholder = ChatUiMessage(assistantMessageId, "assistant", "", now + 1)
        val sendRequest = sendCoordinator.begin(sessionId, assistantMessageId) {
            // Admission owns persistence. Stop and conversation navigation use the same
            // coordinator lock, so they cannot retire this request between the user message and
            // its assistant placeholder, and the lazy job never needs to recreate either later.
            persistMessages(sessionId, userMessage, assistantPlaceholder)
            val persistedConversation = conversationStore.loadConversation(sessionId)
            _uiState.update { state ->
                state.copy(
                    activeConversationId = sessionId,
                    activeConversationTitle = persistedConversation?.title ?: state.activeConversationTitle,
                    conversationSummaries = loadSummaries(),
                    messages = persistedConversation?.messages?.toUiMessages() ?: state.messages,
                    input = "",
                    attachments = emptyList(),
                    isSending = true,
                    error = "",
                    status = "Starting Hermes runtime…",
                    isShowingHistory = false,
                )
            }
        } ?: return

        // Register the job before it can run. Without LAZY start, a stop tap immediately after
        // send could arrive before its cancellation handle was assigned, leaving just-launched work
        // alive and able to re-enter the sending state.
        val sendJob = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val typedDirectToolName = if (attachments.isEmpty()) {
                NativeToolChatSender.extractTypedDirectToolName(text)
            } else {
                null
            }
            if (typedDirectToolName != null) {
                currentCoroutineContext().ensureActive()
                if (!sendCoordinator.mutateIfActive(sendRequest) {
                        _uiState.update {
                            it.copy(
                                error = "",
                                status = "Running $typedDirectToolName…",
                                isShowingHistory = false,
                            )
                        }
                    }
                ) return@launch
                val directOperation = NativeToolChatSender.prepareDirectTyped(
                    context = getApplication<Application>(),
                    prompt = text,
                )
                if (!sendCoordinator.attachNetwork(
                        request = sendRequest,
                        cancel = { directOperation.cancel() },
                        awaitNativeUnwind = directOperation::awaitCompletion,
                    )
                ) {
                    directOperation.cancel()
                    return@launch
                }
                currentCoroutineContext().ensureActive()
                if (!sendCoordinator.claimWorkStartIfActive(sendRequest, directOperation::claimStart)) {
                    directOperation.cancel()
                    return@launch
                }
                val directResult = runSynchronousDirectRouteWithCancellationCheck {
                    requireNotNull(directOperation.executeClaimed()) {
                        "The validated native action could not be executed."
                    }
                }.getOrElse { error ->
                    NativeToolChatSendResult(
                        content = "$typedDirectToolName failed: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
                currentCoroutineContext().ensureActive()
                if (!sendCoordinator.isActive(sendRequest)) return@launch
                val content = directResult.content.ifBlank { currentStrings().failedReplyMessage() }
                val directEvents = if (directResult.executedToolCalls > 0) {
                    listOf(
                        ChatUiMessage(
                            id = UUID.randomUUID().toString(),
                            role = AgentEventType.ToolCall.persistedRole,
                            content = "$typedDirectToolName\n$text",
                            createdAtEpochMs = System.currentTimeMillis(),
                        ),
                        ChatUiMessage(
                            id = UUID.randomUUID().toString(),
                            role = when (typedDirectToolName) {
                                "terminal_tool", "linux_sandbox_tool", "mcp_run_in_proot" ->
                                    AgentEventType.ProcessLog.persistedRole
                                "file_write_tool" -> AgentEventType.FileAccess.persistedRole
                                else -> AgentEventType.ToolResult.persistedRole
                            },
                            content = content,
                            createdAtEpochMs = System.currentTimeMillis() + 1L,
                        ),
                    )
                } else {
                    emptyList()
                }
                val completed = sendCoordinator.finishIfActive(sendRequest) {
                    if (!conversationStore.updateBlankMessageContent(
                            sessionId = sessionId,
                            messageId = assistantMessageId,
                            newContent = content,
                        )
                    ) return@finishIfActive false
                    directEvents.forEach { eventMessage ->
                        conversationStore.insertMessageBefore(
                            sessionId = sessionId,
                            beforeMessageId = assistantMessageId,
                            message = eventMessage.toStoredMessage(),
                        )
                    }
                    _uiState.update { state ->
                        val messagesWithContent = state.messages.map { message ->
                            if (message.id == assistantMessageId) message.copy(content = content) else message
                        }
                        val finalIndex = messagesWithContent.indexOfFirst { it.id == assistantMessageId }
                        state.copy(
                            activeConversationTitle = conversationStore.currentConversation().title,
                            conversationSummaries = loadSummaries(),
                            messages = messagesWithContent.toMutableList().apply {
                                if (finalIndex >= 0) addAll(finalIndex, directEvents) else addAll(directEvents)
                            },
                            isSending = false,
                            error = "",
                            status = "",
                        )
                    }
                    true
                }
                if (completed) retainConversationMemory(sessionId, text, content)
                return@launch
            }

            val directDiagnosticArguments = if (attachments.isEmpty()) directNativeDiagnosticArgumentsForPrompt(text) else null
            if (directDiagnosticArguments != null) {
                val directStrings = AppSettingsStore(getApplication<Application>()).load().let { settings ->
                    hermesStringsFor(AppLanguage.fromTag(settings.languageTag))
                }
                currentCoroutineContext().ensureActive()
                if (!sendCoordinator.mutateIfActive(sendRequest) {
                    _uiState.update {
                        it.copy(
                            error = "",
                            status = directStrings.runningNativeAndroidDiagnostics(),
                            isShowingHistory = false,
                        )
                    }
                }) return@launch
                val action = directDiagnosticArguments.optString("action").ifBlank { "agent_native_tool_self_test_report" }
                val diagnosticCancellationRequested = AtomicBoolean(false)
                if (!sendCoordinator.attachNetwork(
                        request = sendRequest,
                        cancel = { diagnosticCancellationRequested.set(true) },
                    )
                ) {
                    diagnosticCancellationRequested.set(true)
                    return@launch
                }
                currentCoroutineContext().ensureActive()
                val directExecution = runSynchronousDirectRouteWithCancellationCheck {
                    NativeBridgeInvoker.performDiagnosticsAction(
                        context = getApplication<Application>(),
                        action = action,
                        arguments = directDiagnosticArguments,
                        cancellationRequested = {
                            diagnosticCancellationRequested.get() || Thread.currentThread().isInterrupted
                        },
                        publicationGate = sendCoordinator.publicationGate(sendRequest),
                    )
                }
                currentCoroutineContext().ensureActive()
                if (!sendCoordinator.isActive(sendRequest)) return@launch
                val content = directExecution.fold(
                    onSuccess = { formatDirectNativeDiagnosticsReply(it) },
                    onFailure = { error ->
                        directStrings.nativeAndroidDiagnosticsFailed(error.message ?: error.javaClass.simpleName)
                    },
                )
                val resultEventContent = directExecution.fold(
                    onSuccess = {
                        directStrings.nativeAndroidDiagnosticsCompleted(content, modelRequests = 0)
                    },
                    onFailure = { error ->
                        directStrings.nativeAndroidDiagnosticsFailureResult(
                            detail = error.message ?: error.javaClass.simpleName,
                            modelRequests = 0,
                        )
                    },
                )
                val directEvents = listOf(
                    ChatUiMessage(
                        id = UUID.randomUUID().toString(),
                        role = AgentEventType.ToolCall.persistedRole,
                        content = "android_device_diagnostics_tool\n$action",
                        createdAtEpochMs = System.currentTimeMillis(),
                    ),
                    ChatUiMessage(
                        id = UUID.randomUUID().toString(),
                        role = AgentEventType.ToolResult.persistedRole,
                        content = resultEventContent,
                        createdAtEpochMs = System.currentTimeMillis() + 1L,
                    ),
                )
                val completed = sendCoordinator.finishIfActive(sendRequest) {
                    if (!conversationStore.updateBlankMessageContent(
                        sessionId = sessionId,
                        messageId = assistantMessageId,
                        newContent = content,
                    )) return@finishIfActive false
                    directEvents.forEach { eventMessage ->
                        conversationStore.insertMessageBefore(
                            sessionId = sessionId,
                            beforeMessageId = assistantMessageId,
                            message = eventMessage.toStoredMessage(),
                        )
                    }
                    _uiState.update { state ->
                        state.copy(
                            activeConversationTitle = conversationStore.currentConversation().title,
                            conversationSummaries = loadSummaries(),
                            messages = state.messages.map { message ->
                                if (message.id == assistantMessageId) {
                                    message.copy(content = content)
                                } else {
                                    message
                                }
                            }.let { messages ->
                                val finalIndex = messages.indexOfFirst { it.id == assistantMessageId }
                                messages.toMutableList().apply {
                                    if (finalIndex >= 0) addAll(finalIndex, directEvents) else addAll(directEvents)
                                }
                            },
                            isSending = false,
                            error = "",
                            status = "",
                        )
                    }
                    true
                }
                if (completed) retainConversationMemory(sessionId, text, content)
                return@launch
            }

            if (attachments.isEmpty() && NativeToolChatSender.extractDirectLinuxSandboxPrompt(text)) {
                currentCoroutineContext().ensureActive()
                if (!sendCoordinator.mutateIfActive(sendRequest) {
                    _uiState.update {
                        it.copy(
                            error = "",
                            status = "Running Linux sandbox tool…",
                            isShowingHistory = false,
                        )
                    }
                }) return@launch
                val sandboxOperation = NativeToolChatSender.prepareDirectLinuxSandbox(
                    context = getApplication<Application>(),
                    prompt = text,
                )
                if (!sendCoordinator.attachNetwork(
                        request = sendRequest,
                        cancel = { sandboxOperation.cancel() },
                        awaitNativeUnwind = sandboxOperation::awaitCompletion,
                    )
                ) {
                    sandboxOperation.cancel()
                    return@launch
                }
                currentCoroutineContext().ensureActive()
                if (!sendCoordinator.claimWorkStartIfActive(sendRequest, sandboxOperation::claimStart)) {
                    sandboxOperation.cancel()
                    return@launch
                }
                val sandboxResult = runSynchronousDirectRouteWithCancellationCheck {
                    requireNotNull(
                        sandboxOperation.executeClaimed(),
                    ) { "Linux sandbox tool was not executed." }
                }.getOrElse { error ->
                    NativeToolChatSendResult(
                        content = "Linux sandbox tool failed: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
                currentCoroutineContext().ensureActive()
                if (!sendCoordinator.isActive(sendRequest)) return@launch
                val sandboxContent = sandboxResult.content
                val sandboxEvents = if (sandboxResult.executedToolCalls > 0) {
                    listOf(
                        ChatUiMessage(
                            id = UUID.randomUUID().toString(),
                            role = AgentEventType.ToolCall.persistedRole,
                            content = "linux_sandbox_tool\n$text",
                            createdAtEpochMs = System.currentTimeMillis(),
                        ),
                        ChatUiMessage(
                            id = UUID.randomUUID().toString(),
                            role = AgentEventType.ProcessLog.persistedRole,
                            content = sandboxContent,
                            createdAtEpochMs = System.currentTimeMillis() + 1L,
                        ),
                    )
                } else {
                    emptyList()
                }
                val completed = sendCoordinator.finishIfActive(sendRequest) {
                    if (!conversationStore.updateBlankMessageContent(
                        sessionId = sessionId,
                        messageId = assistantMessageId,
                        newContent = sandboxContent,
                    )) return@finishIfActive false
                    sandboxEvents.forEach { eventMessage ->
                        conversationStore.insertMessageBefore(
                            sessionId = sessionId,
                            beforeMessageId = assistantMessageId,
                            message = eventMessage.toStoredMessage(),
                        )
                    }
                    _uiState.update { state ->
                        val messagesWithContent = state.messages.map { message ->
                            if (message.id == assistantMessageId) {
                                message.copy(content = sandboxContent)
                            } else {
                                message
                            }
                        }
                        val finalIndex = messagesWithContent.indexOfFirst { it.id == assistantMessageId }
                        val updated = messagesWithContent.toMutableList().apply {
                            if (finalIndex >= 0) addAll(finalIndex, sandboxEvents) else addAll(sandboxEvents)
                        }
                        state.copy(
                            activeConversationTitle = conversationStore.currentConversation().title,
                            conversationSummaries = loadSummaries(),
                            messages = updated,
                            isSending = false,
                            error = "",
                            status = "",
                        )
                    }
                    true
                }
                if (completed) retainConversationMemory(sessionId, text, sandboxContent)
                return@launch
            }

            val directTerminalCommand = if (attachments.isEmpty()) {
                NativeToolChatSender.extractDirectReadOnlyTerminalCommand(text)
            } else {
                null
            }
            if (directTerminalCommand != null) {
                val directStrings = AppSettingsStore(getApplication<Application>()).load().let { settings ->
                    hermesStringsFor(AppLanguage.fromTag(settings.languageTag))
                }
                currentCoroutineContext().ensureActive()
                if (!sendCoordinator.mutateIfActive(sendRequest) {
                    _uiState.update {
                        it.copy(
                            error = "",
                            status = directStrings.runningReadOnlyNativeCommand(),
                            isShowingHistory = false,
                        )
                    }
                }) return@launch
                val terminalOperation = NativeToolChatSender.prepareDirectReadOnlyTerminal(
                    context = getApplication<Application>(),
                    prompt = text,
                )
                if (!sendCoordinator.attachNetwork(
                        request = sendRequest,
                        cancel = { terminalOperation.cancel() },
                        awaitNativeUnwind = terminalOperation::awaitCompletion,
                    )
                ) {
                    terminalOperation.cancel()
                    return@launch
                }
                currentCoroutineContext().ensureActive()
                if (!sendCoordinator.claimWorkStartIfActive(sendRequest, terminalOperation::claimStart)) {
                    terminalOperation.cancel()
                    return@launch
                }
                val directResult = runSynchronousDirectRouteWithCancellationCheck {
                    requireNotNull(
                        terminalOperation.executeClaimed(),
                    ) { directStrings.readOnlyNativeCommandUnavailable() }
                }.getOrElse { error ->
                    NativeToolChatSendResult(
                        content = directStrings.nativeTerminalCommandFailed(
                            error.message ?: error.javaClass.simpleName,
                        ),
                    )
                }
                currentCoroutineContext().ensureActive()
                if (!sendCoordinator.isActive(sendRequest)) return@launch
                val content = directResult.content
                val directEvents = if (directResult.executedToolCalls > 0) {
                    listOf(
                        ChatUiMessage(
                            id = UUID.randomUUID().toString(),
                            role = AgentEventType.ToolCall.persistedRole,
                            content = "terminal_tool\n$directTerminalCommand",
                            createdAtEpochMs = System.currentTimeMillis(),
                        ),
                        ChatUiMessage(
                            id = UUID.randomUUID().toString(),
                            role = AgentEventType.ToolResult.persistedRole,
                            content = directStrings.nativeReadOnlyCommandCompleted(
                                content = content,
                                modelRequests = directResult.modelRequestCount,
                            ),
                            createdAtEpochMs = System.currentTimeMillis() + 1L,
                        ),
                    )
                } else {
                    emptyList()
                }
                val completed = sendCoordinator.finishIfActive(sendRequest) {
                    if (!conversationStore.updateBlankMessageContent(
                        sessionId = sessionId,
                        messageId = assistantMessageId,
                        newContent = content,
                    )) return@finishIfActive false
                    directEvents.forEach { eventMessage ->
                        conversationStore.insertMessageBefore(
                            sessionId = sessionId,
                            beforeMessageId = assistantMessageId,
                            message = eventMessage.toStoredMessage(),
                        )
                    }
                    _uiState.update { state ->
                        val messagesWithContent = state.messages.map { message ->
                            if (message.id == assistantMessageId) {
                                message.copy(content = content)
                            } else {
                                message
                            }
                        }
                        val finalIndex = messagesWithContent.indexOfFirst { it.id == assistantMessageId }
                        val updated = messagesWithContent.toMutableList().apply {
                            if (finalIndex >= 0) addAll(finalIndex, directEvents) else addAll(directEvents)
                        }
                        state.copy(
                            activeConversationTitle = conversationStore.currentConversation().title,
                            conversationSummaries = loadSummaries(),
                            messages = updated,
                            isSending = false,
                            error = "",
                            status = "",
                        )
                    }
                    true
                }
                if (completed) retainConversationMemory(sessionId, text, content)
                return@launch
            }

            val directEndpoint = resolveDirectProviderEndpoint()
            val runtime = if (directEndpoint == null) {
                ensureRuntimeReady()
            } else {
                HermesRuntimeManager.RuntimeState(started = true)
            }
            val endpoint = directEndpoint ?: resolveChatEndpoint(runtime)
            currentCoroutineContext().ensureActive()
            if (!sendCoordinator.isActive(sendRequest)) return@launch
            if (!runtime.started || endpoint == null) {
                sendCoordinator.finishIfActive(sendRequest) {
                    finalizeOwnedAssistantMessage(
                        sessionId = sessionId,
                        assistantMessageId = assistantMessageId,
                        terminalMessage = currentStrings().failedReplyMessage(),
                    )
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            error = runtime.error ?: "Hermes runtime is not ready",
                            status = "",
                        )
                    }
                    true
                }
                return@launch
            }
            if (!sendCoordinator.mutateIfActive(sendRequest) {
                _uiState.update {
                    it.copy(
                        status = "Checking ${endpoint.debugLabel()} before sending…",
                        error = "",
                    )
                }
            }) return@launch

            val userContentParts = runCatching { buildUserContentParts(text, attachments) }.getOrElse { error ->
                sendCoordinator.finishIfActive(sendRequest) {
                    finalizeOwnedAssistantMessage(
                        sessionId = sessionId,
                        assistantMessageId = assistantMessageId,
                        terminalMessage = currentStrings().failedReplyMessage(),
                    )
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            error = error.message ?: error.javaClass.simpleName,
                            status = "",
                        )
                    }
                    true
                }
                return@launch
            }
            val memoryContext = recallConversationMemoryContext(text)

            currentCoroutineContext().ensureActive()
            if (!sendCoordinator.mutateIfActive(sendRequest) {
                _uiState.update {
                    it.copy(
                        error = "",
                        status = endpoint.streamingStatus(attachments.isNotEmpty()),
                        isShowingHistory = false,
                    )
                }
            }) return@launch

            if (endpoint.nativeToolCalling) {
                val nativeOperation = NativeToolChatSender.prepareSend(
                    context = getApplication<Application>(),
                    baseUrl = endpoint.baseUrl,
                    modelName = endpoint.modelName,
                    apiKey = endpoint.apiKey,
                    providerId = endpoint.providerId,
                    sessionId = sessionId,
                    userText = text,
                    userContentParts = userContentParts,
                    priorMessages = priorConversationMessages,
                    relevantMemoryContext = memoryContext,
                    onEvent = event@ { event ->
                        val eventMessage = ChatUiMessage(
                            id = UUID.randomUUID().toString(),
                            role = event.type.persistedRole,
                            content = buildString {
                                append(event.title)
                                if (event.content.isNotBlank()) {
                                    append('\n')
                                    append(event.content)
                                }
                            },
                            createdAtEpochMs = System.currentTimeMillis(),
                        )
                        sendCoordinator.mutateIfActive(sendRequest) {
                            conversationStore.insertMessageBefore(
                                sessionId = sessionId,
                                beforeMessageId = assistantMessageId,
                                message = eventMessage.toStoredMessage(),
                            )
                            _uiState.update { state ->
                                val finalIndex = state.messages.indexOfFirst { it.id == assistantMessageId }
                                val updated = state.messages.toMutableList().apply {
                                    if (finalIndex >= 0) add(finalIndex, eventMessage) else add(eventMessage)
                                }
                                state.copy(messages = updated)
                            }
                        }
                    },
                )
                if (!sendCoordinator.attachNetwork(
                        request = sendRequest,
                        cancel = { nativeOperation.cancel() },
                        awaitNativeUnwind = nativeOperation::awaitCompletion,
                    )
                ) {
                    nativeOperation.cancel()
                    return@launch
                }
                runCatching {
                    currentCoroutineContext().ensureActive()
                    if (!sendCoordinator.claimWorkStartIfActive(sendRequest, nativeOperation::claimStart)) {
                        nativeOperation.cancel()
                        return@runCatching
                    }
                    val result = runSynchronousDirectRouteWithCancellationCheck {
                        nativeOperation.executeClaimed()
                    }.getOrThrow()
                    currentCoroutineContext().ensureActive()
                    val completed = sendCoordinator.finishIfActive(sendRequest) {
                        if (!conversationStore.updateBlankMessageContent(
                            sessionId = sessionId,
                            messageId = assistantMessageId,
                            newContent = result.content,
                        )) return@finishIfActive false
                        _uiState.update { state ->
                            state.copy(
                                activeConversationTitle = conversationStore.currentConversation().title,
                                conversationSummaries = loadSummaries(),
                                messages = state.messages.map { message ->
                                    if (message.id == assistantMessageId) {
                                        message.copy(content = result.content)
                                    } else {
                                        message
                                    }
                                },
                                isSending = false,
                                status = "",
                            )
                        }
                        true
                    }
                    if (completed) retainConversationMemory(sessionId, text, result.content)
                }.onFailure { error ->
                    val failureMessage = endpoint.failureMessage(error.message ?: error.javaClass.simpleName)
                    sendCoordinator.finishIfActive(sendRequest) {
                        finalizeOwnedAssistantMessage(
                            sessionId = sessionId,
                            assistantMessageId = assistantMessageId,
                            terminalMessage = currentStrings().failedReplyMessage(),
                        )
                        _uiState.update { state ->
                            state.copy(
                                isSending = false,
                                error = failureMessage,
                                status = "",
                            )
                        }
                        true
                    }
                }
                return@launch
            }

            val client = HermesSseClient(
                baseUrl = endpoint.baseUrl,
                apiKey = endpoint.apiKey,
                networkGuard = { url ->
                    HermesNetworkPolicy.requireExternalNetworkAllowed(
                        getApplication<Application>(),
                        url,
                        actionLabel = "chat request",
                    )
                },
            )
            if (!sendCoordinator.attachNetwork(sendRequest, client::cancel)) return@launch
            currentCoroutineContext().ensureActive()
            if (!sendCoordinator.isActive(sendRequest)) return@launch
            val appSettings = AppSettingsStore(getApplication<Application>()).load()
            val customSystemPrompt = appSettings.customSystemPrompt
            val cacheResendEnabled = McpPromptCacheResendPolicy.shouldResendCachedContext(
                providerId = endpoint.providerId,
                settings = McpSettingsStore(getApplication<Application>()).load(),
            )
            val apiMaxTokens = if (appSettings.apiGenerationKnobsEnabled) {
                AppSettings.normalizeLocalModelMaxTokens(appSettings.localModelMaxTokens).takeIf { it > 0 }
            } else {
                null
            }
            val apiTopP = if (appSettings.apiGenerationKnobsEnabled) {
                AppSettings.normalizeLocalModelTopP(appSettings.localModelTopP)
            } else {
                null
            }
            val apiTemperature = if (appSettings.apiGenerationKnobsEnabled) {
                AppSettings.normalizeLocalModelTemperature(appSettings.localModelTemperature)
            } else {
                null
            }
            val suppressLocalLlamaReasoning = shouldSuppressLocalLlamaReasoning(
                providerId = endpoint.providerId,
                runtimeLane = appSettings.llamaCppRuntimeLane,
            )
            val request = ChatCompletionRequest(
                model = endpoint.modelName,
                messages = buildChatRequestMessages(
                    userText = text,
                    userContentParts = userContentParts,
                    customSystemPrompt = customSystemPrompt,
                    priorMessages = priorConversationMessages,
                    memoryContext = memoryContext,
                    cacheResendEnabled = cacheResendEnabled,
                ),
                stream = true,
                sessionId = sessionId,
                maxTokens = apiMaxTokens,
                topP = apiTopP,
                temperature = apiTemperature,
                reasoningFormat = if (suppressLocalLlamaReasoning) "none" else null,
                chatTemplateEnableThinking = if (suppressLocalLlamaReasoning) false else null,
            )
            runCatching {
                val onDelta: (String) -> Unit = onDelta@ { delta ->
                    sendCoordinator.mutateIfActive(sendRequest) { ownedRequest ->
                        // Keep each request's stream buffer isolated; throttle disk writes to avoid jank.
                        if (ownedRequest.streamBuffer == null) {
                            ownedRequest.streamBuffer = StringBuilder(
                                conversationStore.loadConversation(sessionId)
                                    ?.messages
                                    ?.firstOrNull { it.id == assistantMessageId }
                                    ?.content
                                    .orEmpty(),
                            )
                        }
                        ownedRequest.streamBuffer?.append(delta)
                        val now = System.currentTimeMillis()
                        if (now - ownedRequest.lastStreamPersistMs >= STREAM_PERSIST_INTERVAL_MS) {
                            ownedRequest.lastStreamPersistMs = now
                            val persistedSnapshot = ownedRequest.streamBuffer?.toString().orEmpty()
                            conversationStore.updateMessageContentInMemory(
                                sessionId = sessionId,
                                messageId = assistantMessageId,
                                newContent = persistedSnapshot,
                            )
                            conversationStore.flushCacheToDisk()
                        }
                        _uiState.update { state ->
                            state.copy(
                                messages = state.messages.map { message ->
                                    if (message.id == assistantMessageId) {
                                        message.copy(content = message.content + delta)
                                    } else {
                                        message
                                    }
                                },
                            )
                        }
                    }
                }
                val onComplete: () -> Unit = onComplete@ {
                    var assistantContent = ""
                    var hasAssistantContent = false
                    val completed = sendCoordinator.finishIfActive(sendRequest) { ownedRequest ->
                        val streamedContent = ownedRequest.streamBuffer?.toString()
                            ?: conversationStore.loadConversation(sessionId)
                                ?.messages
                                ?.firstOrNull { it.id == assistantMessageId }
                                ?.content
                                .orEmpty()
                        val resolution = resolveAssistantCompletion(
                            streamedContent = streamedContent,
                            localizedFailureMessage = currentStrings().failedReplyMessage(),
                        )
                        assistantContent = resolution.content
                        hasAssistantContent = resolution.hasAssistantContent
                        conversationStore.updateMessageContent(
                            sessionId = sessionId,
                            messageId = assistantMessageId,
                            newContent = resolution.content,
                        )
                        ownedRequest.streamBuffer = null
                        _uiState.update { state ->
                            state.copy(
                                activeConversationTitle = conversationStore.currentConversation().title,
                                conversationSummaries = loadSummaries(),
                                messages = state.messages.map { message ->
                                    if (message.id == assistantMessageId) {
                                        message.copy(content = resolution.content)
                                    } else {
                                        message
                                    }
                                },
                                isSending = false,
                                error = if (resolution.hasAssistantContent) "" else resolution.content,
                                status = "",
                            )
                        }
                        true
                    }
                    if (completed && hasAssistantContent) {
                        retainConversationMemory(sessionId, text, assistantContent)
                    }
                }
                val onError: (String) -> Unit = { error ->
                    if (sendCoordinator.isActive(sendRequest)) {
                        tryNonStreamingEndpointFallback(
                            endpoint = endpoint,
                            request = request,
                            sendRequest = sendRequest,
                            streamError = error,
                        )
                    }
                }
                val onStatus: (String) -> Unit = { status ->
                    sendCoordinator.mutateIfActive(sendRequest) {
                        _uiState.update {
                            it.copy(status = "${endpoint.debugLabel()}: $status")
                        }
                    }
                }
                if (endpoint.apiMode == EndpointApiMode.RESPONSES) {
                    client.streamResponse(
                        request = request,
                        onDelta = onDelta,
                        onComplete = onComplete,
                        onError = onError,
                        onStatus = onStatus,
                    )
                } else {
                    client.streamChatCompletion(
                        request = request,
                        onDelta = onDelta,
                        onComplete = onComplete,
                        onError = onError,
                        onStatus = onStatus,
                    )
                }
            }.onFailure { error ->
                val message = error.message ?: error.javaClass.simpleName
                if (sendCoordinator.isActive(sendRequest)) {
                    tryNonStreamingEndpointFallback(
                        endpoint = endpoint,
                        request = request,
                        sendRequest = sendRequest,
                        streamError = message,
                    )
                }
            }
        }
        sendJob.invokeOnCompletion {
            sendCoordinator.jobCompleted(sendRequest) { request ->
                request.streamBuffer = null
                finalizeOwnedAssistantMessage(
                    sessionId = request.sessionId,
                    assistantMessageId = request.assistantMessageId,
                    terminalMessage = currentStrings().failedReplyMessage(),
                )
                _uiState.update {
                    it.copy(
                        isSending = false,
                        status = "",
                    )
                }
            }
        }
        if (!sendCoordinator.attachJob(sendRequest, sendJob::cancel)) {
            sendJob.cancel()
            return
        }
        sendJob.start()
    }

    private fun tryNonStreamingEndpointFallback(
        endpoint: ChatEndpoint,
        request: ChatCompletionRequest,
        sendRequest: ChatSendRequestCoordinator.Request,
        streamError: String,
    ): Boolean {
        if (endpoint.nativeToolCalling || !sendCoordinator.isActive(sendRequest)) {
            return false
        }
        if (!sendCoordinator.mutateIfActive(sendRequest) {
            _uiState.update {
                it.copy(
                    status = "${endpoint.debugLabel()}: stream issue detected; retrying non-stream request…",
                    error = "",
                )
            }
        }) return false
        val sessionId = sendRequest.sessionId
        val assistantMessageId = sendRequest.assistantMessageId
        var completedContent = ""
        return runCatching {
            val fallbackTransport = RequestOwnedHttpTransport()
            if (!sendCoordinator.attachNetwork(sendRequest, fallbackTransport::cancel)) {
                return@runCatching false
            }
            val fallbackClient = HermesApiClient(
                baseUrl = endpoint.baseUrl,
                apiKey = endpoint.apiKey,
                httpClient = fallbackTransport.client,
                networkGuard = { url ->
                    HermesNetworkPolicy.requireExternalNetworkAllowed(
                        getApplication<Application>(),
                        url,
                        actionLabel = "chat fallback request",
                    )
                },
            )
            val result = if (endpoint.apiMode == EndpointApiMode.RESPONSES) {
                fallbackClient.createResponse(request.copy(stream = false))
            } else {
                fallbackClient.createChatCompletion(request.copy(stream = false))
            }
            val content = if (endpoint.apiMode == EndpointApiMode.RESPONSES) {
                extractAssistantContentFromResponse(result.rawBody)
            } else {
                extractAssistantContentFromChatCompletion(result.rawBody)
            }
            require(content.isNotBlank()) {
                "Non-stream endpoint returned no assistant text"
            }
            val completed = sendCoordinator.finishIfActive(sendRequest) { ownedRequest ->
                // A fallback is the completed result for this still-owned request. Replace any
                // partial SSE snapshot; Stop or request B cannot reach this mutation because they
                // participate in the same ownership transition.
                conversationStore.updateMessageContent(
                    sessionId = sessionId,
                    messageId = assistantMessageId,
                    newContent = content,
                )
                ownedRequest.streamBuffer = null
                _uiState.update { state ->
                    state.copy(
                        activeConversationTitle = conversationStore.currentConversation().title,
                        conversationSummaries = loadSummaries(),
                        messages = state.messages.map { message ->
                            if (message.id == assistantMessageId) {
                                message.copy(content = content)
                            } else {
                                message
                            }
                        },
                        isSending = false,
                        error = "",
                        status = "${endpoint.debugLabel()}: recovered with non-stream request after SSE failed.",
                    )
                }
                completedContent = content
                true
            }
            if (completed) {
                retainConversationMemory(
                    sessionId,
                    request.messages.lastOrNull { it.role == "user" }?.content.orEmpty(),
                    completedContent,
                )
            }
            completed
        }.getOrElse { fallbackError ->
            sendCoordinator.finishIfActive(sendRequest) {
                finalizeOwnedAssistantMessage(
                    sessionId = sessionId,
                    assistantMessageId = assistantMessageId,
                    terminalMessage = currentStrings().failedReplyMessage(),
                )
                _uiState.update {
                    it.copy(
                        isSending = false,
                        error = endpoint.failureMessage(
                            "Streaming failed: $streamError. Non-stream fallback also failed: " +
                                (fallbackError.message ?: fallbackError.javaClass.simpleName),
                        ),
                        status = "",
                    )
                }
                true
            }
            false
        }
    }

    private data class ChatEndpoint(
        val baseUrl: String,
        val apiKey: String?,
        val modelName: String,
        val nativeToolCalling: Boolean = false,
        val apiMode: EndpointApiMode = EndpointApiMode.CHAT_COMPLETIONS,
        val directProvider: Boolean = false,
        val providerId: String = "",
    )

    private enum class EndpointApiMode {
        CHAT_COMPLETIONS,
        RESPONSES,
    }

    private fun ChatEndpoint.streamingStatus(hasAttachments: Boolean): String {
        val action = if (hasAttachments) "Hermes is reading the image" else "Hermes is replying"
        return if (nativeToolCalling) {
            "$action on-device…"
        } else {
            "$action via ${endpointHostLabel(baseUrl)}…"
        }
    }

    private fun ChatEndpoint.failureMessage(message: String): String {
        val clean = message.ifBlank { "Endpoint request failed" }
        if (nativeToolCalling || !clean.looksLikeEndpointDisconnect()) {
            return clean
        }
        return "$clean Hermes normalizes raw hosts, /v1 URLs, and /v1/chat/completions URLs, but the host must still be reachable, the model name must match the server exactly, and streaming endpoints must stay open until [DONE]."
    }

    private fun ChatEndpoint.debugLabel(): String {
        val mode = when {
            nativeToolCalling -> "on-device"
            apiMode == EndpointApiMode.RESPONSES -> "responses"
            directProvider -> "provider"
            else -> "endpoint"
        }
        return "$mode ${endpointHostLabel(baseUrl)} · $modelName"
    }

    private fun String.looksLikeEndpointDisconnect(): Boolean {
        val lower = lowercase()
        return listOf("timeout", "closed", "reset", "disconnect", "unexpected end", "[done]", "sse", "stream").any { token ->
            lower.contains(token)
        }
    }

    private fun endpointHostLabel(baseUrl: String): String {
        val normalizedBaseUrl = runCatching { HermesEndpointUrl.normalizeBaseUrl(baseUrl) }.getOrDefault(baseUrl)
        return runCatching {
            val uri = URI(normalizedBaseUrl)
            val host = uri.host.orEmpty().ifBlank { normalizedBaseUrl }
            val port = uri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
            "$host$port"
        }.getOrDefault(normalizedBaseUrl)
            .replace("https://", "")
            .replace("http://", "")
            .take(64)
    }

    private fun resolveChatEndpoint(runtime: HermesRuntimeManager.RuntimeState): ChatEndpoint? {
        if (HermesRuntimeManager.remoteStopRequiresAppRestart()) {
            return null
        }
        val selectedLocalBackend = BackendKind.fromPersistedValue(
            AppSettingsStore(getApplication<Application>()).load().onDeviceBackend,
        )
        val localBackend = OnDeviceBackendManager.currentStatus()
        if (!chatRuntimeRoutingAllowed(localBackend)) {
            return null
        }
        if (shouldPreferLocalChatEndpoint(selectedLocalBackend, localBackend)) {
            return ChatEndpoint(
                baseUrl = HermesEndpointUrl.normalizeBaseUrl(localBackend.baseUrl),
                modelName = localBackend.modelName,
                apiKey = localBackend.apiKey.takeIf { it.isNotBlank() },
                nativeToolCalling = true,
                providerId = localBackend.backendKind.persistedValue,
            )
        }
        if (!runtimeCanProvideRemoteChatEndpoint(runtime)) {
            return null
        }
        val runtimeBaseUrl = runtime.baseUrl?.takeIf { it.isNotBlank() } ?: return null
        val normalizedRuntimeBaseUrl = runCatching {
            HermesEndpointUrl.normalizeBaseUrl(runtimeBaseUrl)
        }.getOrNull() ?: return null
        return ChatEndpoint(
            baseUrl = normalizedRuntimeBaseUrl,
            apiKey = runtime.apiKey,
            modelName = runtime.modelName ?: "hermes-agent-android",
            providerId = AppSettingsStore(getApplication<Application>()).load().provider,
        )
    }

    private fun resolveDirectProviderEndpoint(): ChatEndpoint? {
        if (HermesRuntimeManager.remoteStopRequiresAppRestart()) {
            return null
        }
        if (!chatRuntimeRoutingAllowed(OnDeviceBackendManager.currentStatus())) {
            return null
        }
        val settings = AppSettingsStore(getApplication<Application>()).load()
        if (settings.offlineAirplaneMode || BackendKind.fromPersistedValue(settings.onDeviceBackend) != BackendKind.NONE) {
            return null
        }
        val provider = settings.provider.trim().lowercase()
        if (!usesDirectOpenAiCompatibleTransport(provider)) {
            return null
        }
        val preset = ProviderPresets.find(provider)
        val baseUrl = settings.baseUrl.ifBlank { preset?.baseUrl.orEmpty() }
        val modelName = settings.model.ifBlank { preset?.modelHint.orEmpty() }
        if (baseUrl.isBlank() || modelName.isBlank()) {
            return null
        }
        val apiKey = SecureSecretsStore(getApplication<Application>()).loadApiKey(provider)
        if (apiKey.isBlank()) {
            return null
        }
        return ChatEndpoint(
            baseUrl = HermesEndpointUrl.normalizeBaseUrl(baseUrl),
            apiKey = apiKey,
            modelName = modelName,
            apiMode = if (provider in RESPONSES_API_PROVIDERS) EndpointApiMode.RESPONSES else EndpointApiMode.CHAT_COMPLETIONS,
            directProvider = true,
            providerId = provider,
        )
    }

    private fun ensureRuntimeReady(): HermesRuntimeManager.RuntimeState {
        val current = HermesRuntimeManager.currentState()
        val localStatus = OnDeviceBackendManager.currentStatus()
        val selectedLocalBackend = BackendKind.fromPersistedValue(
            AppSettingsStore(getApplication<Application>()).load().onDeviceBackend,
        )
        if (
            shouldReuseCachedRuntime(
                selectedLocalBackend = selectedLocalBackend,
                localBackendStatus = localStatus,
                runtimeStarted = current.started,
                runtimeBaseUrl = current.baseUrl,
                endpointAvailable = resolveChatEndpoint(current) != null,
            )
        ) {
            return current
        }
        return HermesRuntimeManager.ensureStarted(getApplication())
    }

    private fun retainConversationMemory(sessionId: String, userText: String, assistantText: String) {
        val fact = conversationMemoryFact(sessionId, userText, assistantText)
        if (fact.isBlank()) {
            return
        }
        runCatching {
            NativeBridgeInvoker.performMemoryAction(
                context = getApplication<Application>(),
                action = "retain",
                arguments = JSONObject()
                    .put("content", fact)
                    .put("source", "chat")
                    .put("category", "conversation")
                    .put("tags", JSONArray().put("conversation").put("auto_recall")),
            )
        }
    }

    private fun recallConversationMemoryContext(userText: String): String {
        return runCatching {
            JSONObject(
                NativeBridgeInvoker.performMemoryAction(
                    context = getApplication<Application>(),
                    action = "relevant_context",
                    arguments = JSONObject()
                        .put("query", userText)
                        .put("limit", 6)
                        .put("max_chars", 1600),
                    reinforceRecall = false,
                ),
            ).optString("system_prompt_context")
        }.getOrDefault("").trim()
    }

    private fun buildState(
        activeConversationId: String = conversationStore.currentSessionId(),
        messages: List<ChatUiMessage> = conversationStore.currentConversationMessages().toUiMessages(),
        isShowingHistory: Boolean = false,
        status: String = "",
    ): ChatUiState {
        val conversation = conversationStore.loadConversation(activeConversationId) ?: conversationStore.currentConversation()
        return ChatUiState(
            activeConversationId = conversation.sessionId,
            activeConversationTitle = conversation.title,
            conversationSummaries = loadSummaries(),
            isShowingHistory = isShowingHistory,
            messages = messages,
            status = status,
        )
    }

    private fun loadSummaries(): List<ChatConversationSummary> {
        return conversationStore.listConversationSummaries().map { summary ->
            ChatConversationSummary(
                id = summary.sessionId,
                title = summary.title,
                preview = summary.preview,
                updatedLabel = DateFormat.format("MMM d, HH:mm", summary.updatedAtEpochMs).toString(),
                messageCount = summary.messageCount,
            )
        }
    }

    private fun persistMessages(sessionId: String, vararg messages: ChatUiMessage) {
        conversationStore.upsertMessages(
            sessionId = sessionId,
            messages = messages.map { message -> message.toStoredMessage() },
        )
    }

    private fun currentStrings() = AppSettingsStore(getApplication<Application>()).load().let { settings ->
        hermesStringsFor(AppLanguage.fromTag(settings.languageTag))
    }

    private fun finalizeOwnedAssistantMessage(
        sessionId: String,
        assistantMessageId: String,
        terminalMessage: String,
    ) {
        if (sessionId.isBlank() || assistantMessageId.isBlank() || terminalMessage.isBlank()) return
        // The caller holds this request's coordinator transition. It is therefore safe—and
        // necessary—to replace streamed partial text with Stop/failure, while a stale callback
        // cannot enter this path after another request or completion has won ownership.
        persistOwnedAssistantTerminal(
            sessionId = sessionId,
            assistantMessageId = assistantMessageId,
            terminalMessage = terminalMessage,
        )
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.id == assistantMessageId) {
                        message.copy(content = terminalMessage)
                    } else {
                        message
                    }
                },
            )
        }
    }

    private fun persistOwnedAssistantTerminal(
        sessionId: String,
        assistantMessageId: String,
        terminalMessage: String,
    ) {
        if (sessionId.isBlank() || assistantMessageId.isBlank() || terminalMessage.isBlank()) return
        conversationStore.updateMessageContent(
            sessionId = sessionId,
            messageId = assistantMessageId,
            newContent = terminalMessage,
        )
    }

    private fun ChatUiMessage.toStoredMessage(): StoredConversationMessage = StoredConversationMessage(
        id = id,
        role = role,
        content = content,
        createdAtEpochMs = createdAtEpochMs,
        attachments = attachments.map { attachment ->
            StoredConversationAttachment(
                uri = attachment.uri,
                displayName = attachment.displayName,
                mimeType = attachment.mimeType,
                sizeBytes = attachment.sizeBytes,
            )
        },
    )

    private data class AttachmentDetails(
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
    )

    private fun queryAttachmentDetails(uri: Uri): AttachmentDetails {
        val app = getApplication<Application>()
        var displayName = uri.lastPathSegment ?: "image"
        var sizeBytes = 0L
        if (uri.scheme == "file") {
            uri.path?.let(::File)?.let { file ->
                displayName = file.name.ifBlank { displayName }
                sizeBytes = file.length().coerceAtLeast(0L)
            }
        }
        runCatching { app.contentResolver.query(uri, null, null, null, null) }.getOrNull()?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) {
                    displayName = cursor.getString(nameIndex) ?: displayName
                }
                if (sizeIndex >= 0) {
                    sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                }
            }
        }
        val mimeType = runCatching { app.contentResolver.getType(uri) }.getOrNull().orEmpty().ifBlank {
            when (displayName.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/*"
            }
        }
        return AttachmentDetails(displayName = displayName, mimeType = mimeType, sizeBytes = sizeBytes)
    }

    private fun buildUserContentParts(text: String, attachments: List<ChatAttachment>): List<ChatContentPart> {
        if (attachments.isEmpty()) {
            return emptyList()
        }
        val parts = mutableListOf<ChatContentPart>()
        if (text.isNotBlank()) {
            parts += ChatContentPart(type = "text", text = text)
        }
        attachments.forEach { attachment ->
            parts += ChatContentPart(
                type = "image_url",
                imageUrl = readAttachmentAsDataUrl(attachment),
            )
        }
        return parts
    }

    private fun readAttachmentAsDataUrl(attachment: ChatAttachment): String {
        val app = getApplication<Application>()
        val uri = Uri.parse(attachment.uri)
        val mimeType = attachment.mimeType.ifBlank {
            app.contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        }
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Unable to read ${attachment.displayName}")
        require(bytes.isNotEmpty()) { "Selected image ${attachment.displayName} is empty" }
        return "data:$mimeType;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun List<StoredConversationMessage>.toUiMessages(): List<ChatUiMessage> {
        return map { message ->
            ChatUiMessage(
                id = message.id,
                role = message.role,
                content = message.content,
                createdAtEpochMs = message.createdAtEpochMs,
                attachments = message.attachments.map { attachment ->
                    ChatAttachment(
                        uri = attachment.uri,
                        displayName = attachment.displayName,
                        mimeType = attachment.mimeType,
                        sizeBytes = attachment.sizeBytes,
                    )
                },
            )
        }
    }
}

internal fun chatRuntimeRoutingAllowed(localBackendStatus: LocalBackendStatus): Boolean {
    return !localBackendStatus.requiresAppRestart
}

internal fun shouldPreferLocalChatEndpoint(
    selectedLocalBackend: BackendKind,
    localBackendStatus: LocalBackendStatus,
): Boolean {
    return selectedLocalBackend != BackendKind.NONE &&
        localBackendStatus.backendKind == selectedLocalBackend &&
        localBackendStatus.started &&
        localBackendStatus.baseUrl.isNotBlank() &&
        localBackendStatus.modelName.isNotBlank()
}

internal fun runtimeCanProvideRemoteChatEndpoint(
    runtimeState: HermesRuntimeManager.RuntimeState,
): Boolean = runtimeState.localBackendKind == BackendKind.NONE

internal fun shouldSuppressLocalLlamaReasoning(providerId: String, runtimeLane: String): Boolean {
    return BackendKind.fromPersistedValue(providerId) == BackendKind.LLAMA_CPP &&
        AppSettings.normalizeLlamaCppRuntimeLane(runtimeLane) == "turboquant"
}

internal fun shouldReuseCachedRuntime(
    selectedLocalBackend: BackendKind,
    localBackendStatus: LocalBackendStatus,
    runtimeStarted: Boolean,
    runtimeBaseUrl: String?,
    endpointAvailable: Boolean,
): Boolean {
    if (!chatRuntimeRoutingAllowed(localBackendStatus) || !runtimeStarted || !endpointAvailable) {
        return false
    }
    // Cached status cannot prove that a native child is still alive. Re-enter the
    // backend controller for every selected-local send; it cheaply reuses only after
    // checking its owned Process and authenticated /v1/models endpoint.
    if (selectedLocalBackend != BackendKind.NONE) return false
    return !localBackendStatus.started && runtimeBaseUrl.normalizedRuntimeIdentity().isNotBlank()
}

private fun String?.normalizedRuntimeIdentity(): String = orEmpty().trim().trimEnd('/')

internal fun extractAssistantContentFromChatCompletion(rawBody: String): String {
    val root = JSONObject(rawBody)
    val choices = root.optJSONArray("choices") ?: return ""
    if (choices.length() == 0) {
        return ""
    }
    val choice = choices.optJSONObject(0) ?: return ""
    val messageContent = choice.optJSONObject("message")?.opt("content")
    val deltaContent = choice.optJSONObject("delta")?.opt("content")
    return chatCompletionContentToText(messageContent ?: deltaContent).trim()
}

internal fun extractAssistantContentFromResponse(rawBody: String): String {
    val root = JSONObject(rawBody)
    val directOutput = root.optString("output_text").trim()
    if (directOutput.isNotBlank()) {
        return directOutput
    }
    val output = root.optJSONArray("output") ?: return ""
    val chunks = mutableListOf<String>()
    for (outputIndex in 0 until output.length()) {
        val item = output.optJSONObject(outputIndex) ?: continue
        val content = item.opt("content")
        val text = when (content) {
            is JSONArray -> responseContentArrayToText(content)
            else -> chatCompletionContentToText(content)
        }.trim()
        if (text.isNotBlank()) {
            chunks += text
        }
    }
    return chunks.joinToString("\n").trim()
}

internal fun conversationMemoryFact(sessionId: String, userText: String, assistantText: String): String {
    val user = userText.compactForMemory()
    val assistant = assistantText.compactForMemory()
    if (user.isBlank() && assistant.isBlank()) {
        return ""
    }
    return buildString {
        append("Conversation ")
        append(sessionId.take(36))
        append(": ")
        if (user.isNotBlank()) {
            append("user asked: ")
            append(user.take(420))
        }
        if (assistant.isNotBlank()) {
            if (user.isNotBlank()) append(" | ")
            append("assistant answered: ")
            append(assistant.take(700))
        }
    }.take(1_200)
}

internal fun buildChatRequestMessages(
    userText: String,
    userContentParts: List<ChatContentPart> = emptyList(),
    customSystemPrompt: String = "",
    priorMessages: List<ChatMessage> = emptyList(),
    memoryContext: String = "",
    cacheResendEnabled: Boolean = false,
): List<ChatMessage> {
    val userMessage = ChatMessage(role = "user", content = userText, contentParts = userContentParts)
    val persona = NativeToolContextCompressor.compactCustomSystemPrompt(
        AppSettings.normalizeCustomSystemPrompt(customSystemPrompt),
    )
    val relevantMemory = NativeToolContextCompressor.compactPromotedMemoryContext(memoryContext)
    val requestMessages = mutableListOf<ChatMessage>()
    val compactedPriorMessages = if (cacheResendEnabled) {
        NativeToolContextCompressor.cacheFriendlyPriorChatRequestMessages(priorMessages)
    } else {
        NativeToolContextCompressor.compactPriorChatRequestMessages(priorMessages)
    }
    if (persona.isBlank() && relevantMemory.isBlank()) {
        requestMessages += compactedPriorMessages
        requestMessages += userMessage
        return requestMessages
    }
    requestMessages += ChatMessage(
        role = "system",
        content = buildString {
            if (persona.isNotBlank()) {
                append("User-configured agent persona/system instructions. Apply them unless they conflict ")
                append("with the current user request, Android permissions, tool truthfulness, or safety constraints:\n")
                append(persona)
            }
            if (relevantMemory.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Relevant local memory context recalled from prior conversations. Use it when it helps the current request, and ignore stale or unrelated rows:\n")
                append(relevantMemory)
            }
        },
    )
    requestMessages += compactedPriorMessages
    requestMessages += userMessage
    return requestMessages
}

internal fun buildPriorChatRequestMessages(messages: List<ChatUiMessage>): List<ChatMessage> {
    return NativeToolContextCompressor.compactPriorChatRequestMessages(
        messages.mapNotNull { message ->
            if (message.role != "user" && message.role != "assistant") {
                return@mapNotNull null
            }
            val content = buildString {
                val text = message.content.trim()
                if (text.isNotBlank()) {
                    append(text)
                }
                val attachmentLabels = message.attachments.map { attachment ->
                    attachment.displayName
                        .ifBlank { attachment.mimeType }
                        .ifBlank { "attachment" }
                }
                if (attachmentLabels.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append(
                        attachmentLabels.joinToString("\n") { label ->
                            "[prior turn attachment omitted: $label]"
                        },
                    )
                }
            }.trim()
            if (content.isBlank()) {
                null
            } else {
                ChatMessage(
                    role = message.role,
                    content = content,
                )
            }
        },
    )
}

internal fun directNativeDiagnosticArgumentsForPrompt(text: String): JSONObject? {
    val prompt = text.trim()
    if (prompt.isBlank()) {
        return null
    }
    val authority = NativeDirectToolAuthorityParser.parse(prompt)
    if (!authority.allows("android_device_diagnostics_tool")) return null
    return authority.arguments().takeIf { it.optString("action").isNotBlank() }
}

internal suspend fun <T> runSynchronousDirectRouteWithCancellationCheck(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: () -> T,
): Result<T> {
    // Dispatchers.IO cancellation alone does not interrupt a blocking Process/Call. Keep the
    // blocking lane explicitly interruptible so this request's Stop/onCleared cancellation can
    // enter NativeAndroidShellTool's verified parent/descendant cleanup instead of merely
    // suppressing a late UI write.
    val result = runCatching {
        runInterruptible(dispatcher) {
            block()
        }
    }
    currentCoroutineContext().ensureActive()
    return result
}

internal fun formatDirectNativeDiagnosticsReply(rawJson: String): String {
    val json = runCatching { JSONObject(rawJson) }.getOrNull()
        ?: return rawJson.take(4_000)
    val output = json.optString("output")
    if (output.isNotBlank()) {
        return output
    }
    if (json.optString("action") == "status") {
        // The status bridge also supplies generic cards. Preserve the actual status payload for
        // this direct user request instead of reducing it to those three explanatory cards.
        return json.toString(2).take(4_000)
    }
    val cards = json.optJSONArray("cards")
    if (cards != null && cards.length() > 0) {
        val lines = mutableListOf<String>()
        for (index in 0 until cards.length()) {
            val card = cards.optJSONObject(index) ?: continue
            val title = card.optString("title").ifBlank { "Diagnostic" }
            val body = card.optString("body").ifBlank { card.optString("subtitle") }
            lines.add(listOf(title, body).filter { it.isNotBlank() }.joinToString(": "))
        }
        if (lines.isNotEmpty()) {
            return lines.joinToString("\n")
        }
    }
    return json.toString(2).take(4_000)
}

private fun String.compactForMemory(): String {
    return replace(Regex("\\s+"), " ").trim()
}

private fun chatCompletionContentToText(value: Any?): String {
    if (value == null || value == JSONObject.NULL) {
        return ""
    }
    return when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                val item = value.opt(index)
                val text = when (item) {
                    is JSONObject -> item.optString("text")
                        .ifBlank { item.optString("content") }
                    is String -> item
                    else -> item?.toString().orEmpty()
                }
                if (text.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(text)
                }
            }
        }
        is JSONObject -> value.optString("text").ifBlank { value.optString("content") }
        else -> value.toString()
    }
}

private fun responseContentArrayToText(value: JSONArray): String {
    return buildString {
        for (index in 0 until value.length()) {
            val item = value.opt(index)
            val text = when (item) {
                is JSONObject -> when (item.optString("type")) {
                    "output_text", "input_text", "summary_text" -> item.optString("text")
                    else -> item.optString("text")
                        .ifBlank { item.optString("content") }
                }
                is String -> item
                else -> item?.toString().orEmpty()
            }
            if (text.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(text)
            }
        }
    }
}
