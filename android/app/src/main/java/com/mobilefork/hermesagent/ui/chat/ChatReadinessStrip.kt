package com.mobilefork.hermesagent.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chaquo.python.Python
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.LocalBackendStatus
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.SecureSecretsStore
import com.mobilefork.hermesagent.device.HermesHyMemoryBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatReadinessUiState(
    val line: String = "Checking runtime…",
    val ready: Boolean = false,
)

internal fun pythonReadinessLabel(
    pythonReady: Boolean,
    remoteReadyWithoutPython: Boolean,
): String = when {
    pythonReady -> "up"
    remoteReadyWithoutPython -> "optional"
    // This strip is a passive observer and never starts Python, so a stopped
    // interpreter is idle rather than "booting".
    else -> "idle"
}

internal fun chatReadinessUiState(
    settings: AppSettings,
    local: LocalBackendStatus,
    pythonReady: Boolean,
    memoryCount: Int,
    hasDirectCredential: Boolean,
): ChatReadinessUiState {
    val selectedBackend = BackendKind.fromPersistedValue(settings.onDeviceBackend)
    val selectedLocalReady = selectedBackend != BackendKind.NONE &&
        local.backendKind == selectedBackend &&
        local.started &&
        !local.requiresAppRestart
    val staleLocalBlocksRoute = local.started && local.backendKind != selectedBackend
    val backendLabel = when {
        local.requiresAppRestart ->
            "${local.backendKind.persistedValue}: ${local.statusMessage.ifBlank { "force stop and reopen Hermes" }}"
        staleLocalBlocksRoute ->
            "${selectedBackend.persistedValue}: waiting for ${local.backendKind.persistedValue} to stop"
        selectedLocalReady ->
            "${local.backendKind.persistedValue} · ${local.modelName.ifBlank { "model" }}"
        selectedBackend != BackendKind.NONE ->
            "${selectedBackend.persistedValue}: ${local.statusMessage.ifBlank { "not started" }}"
        else -> "remote ${settings.provider.ifBlank { "provider" }} · ${settings.model.ifBlank { "model" }}"
    }
    val safeRemoteSelection = selectedBackend == BackendKind.NONE &&
        !local.started &&
        !local.requiresAppRestart
    val remoteReadyWithoutPython = safeRemoteSelection &&
        settings.provider.isNotBlank() &&
        hasDirectCredential
    val ready = selectedLocalReady ||
        remoteReadyWithoutPython ||
        (safeRemoteSelection && settings.provider.isNotBlank() && pythonReady)
    val line = buildString {
        append(if (ready) "Ready" else "Not ready")
        append(" · ")
        append(backendLabel)
        append(" · Python ")
        append(pythonReadinessLabel(pythonReady, remoteReadyWithoutPython))
        append(" · memory ")
        append(memoryCount.coerceAtLeast(0))
    }
    return ChatReadinessUiState(line = line, ready = ready)
}

class ChatReadinessViewModel internal constructor(
    application: Application,
    private val settingsLoader: () -> AppSettings,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        settingsLoader = { AppSettingsStore(application).load() },
    )

    private val _uiState = MutableStateFlow(ChatReadinessUiState())
    val uiState: StateFlow<ChatReadinessUiState> = _uiState.asStateFlow()

    @Volatile
    private var memoryCountCache: Int = -1
    @Volatile
    private var memoryCountPolls: Int = 0

    fun refresh() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            // Lightweight status only — never cold-start backends from the strip poller.
            val snapshot = withContext(Dispatchers.Default) {
                val settings = loadCurrentSettingsForRefresh()
                val local = OnDeviceBackendManager.currentStatus()
                val pythonReady = Python.isStarted()
                memoryCountPolls += 1
                if (memoryCountCache < 0 || memoryCountPolls % 4 == 0) {
                    memoryCountCache = runCatching {
                        HermesHyMemoryBridge.statusJson(app).optInt("memory_count", 0)
                    }.getOrDefault(0)
                }
                val hasDirectCredential = runCatching {
                    val secrets = SecureSecretsStore(app)
                    val providerId = settings.provider.ifBlank { "openrouter" }
                    secrets.loadApiKey(providerId).isNotBlank() ||
                        secrets.loadApiKey("openrouter").isNotBlank() ||
                    settings.baseUrl.isNotBlank()
                }.getOrDefault(settings.provider.isNotBlank())
                chatReadinessUiState(
                    settings = settings,
                    local = local,
                    pythonReady = pythonReady,
                    memoryCount = memoryCountCache,
                    hasDirectCredential = hasDirectCredential,
                )
            }
            _uiState.value = snapshot
        }
    }

    internal fun loadCurrentSettingsForRefresh(): AppSettings = settingsLoader()

    fun pollWhileBooting() {
        viewModelScope.launch {
            // Faster first paints, then back off. Stop once stable ready.
            val delaysMs = longArrayOf(0, 400, 800, 1_200, 2_000, 2_000, 3_000, 3_000, 4_000, 5_000)
            for (delayMs in delaysMs) {
                if (delayMs > 0) delay(delayMs)
                refresh()
                val state = _uiState.value
                if (state.ready && (state.line.contains("Python up") || state.line.contains("Python optional"))) {
                    // One more delayed refresh for memory count, then stop.
                    delay(3_000)
                    refresh()
                    return@launch
                }
            }
        }
    }

}

@Composable
fun ChatReadinessStrip(
    modifier: Modifier = Modifier,
    viewModel: ChatReadinessViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.pollWhileBooting()
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("HermesChatReadinessStrip"),
        color = if (uiState.ready) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = uiState.line,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
