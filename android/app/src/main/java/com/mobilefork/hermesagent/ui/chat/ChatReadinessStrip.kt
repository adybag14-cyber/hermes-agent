package com.mobilefork.hermesagent.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
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
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import com.mobilefork.hermesagent.data.AppSettingsStore
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

class ChatReadinessViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ChatReadinessUiState())
    val uiState: StateFlow<ChatReadinessUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            // Lightweight status only — never cold-start backends from the strip poller.
            val snapshot = withContext(Dispatchers.Default) {
                val settings = AppSettingsStore(app).load()
                val local = OnDeviceBackendManager.currentStatus()
                val pythonReady = Python.isStarted()
                val memoryCount = runCatching {
                    HermesHyMemoryBridge.statusJson(app).optInt("memory_count", 0)
                }.getOrDefault(0)
                val backendLabel = when {
                    local.started -> "${local.backendKind.persistedValue} · ${local.modelName.ifBlank { "model" }}"
                    settings.onDeviceBackend != BackendKind.NONE.persistedValue ->
                        "${settings.onDeviceBackend}: ${local.statusMessage.ifBlank { "not started" }}"
                    else -> "remote ${settings.provider.ifBlank { "provider" }} · ${settings.model.ifBlank { "model" }}"
                }
                val ready = when {
                    local.started -> true
                    settings.onDeviceBackend == BackendKind.NONE.persistedValue &&
                        settings.provider.isNotBlank() &&
                        pythonReady -> true
                    else -> false
                }
                val line = buildString {
                    append(if (ready) "Ready" else "Not ready")
                    append(" · ")
                    append(backendLabel)
                    append(" · Python ")
                    append(if (pythonReady) "up" else "booting")
                    append(" · memory ")
                    append(memoryCount)
                }
                ChatReadinessUiState(line = line, ready = ready)
            }
            _uiState.value = snapshot
        }
    }

    fun pollWhileBooting() {
        viewModelScope.launch {
            repeat(20) {
                refresh()
                if (_uiState.value.ready && _uiState.value.line.contains("Python up")) return@launch
                delay(2_000)
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
