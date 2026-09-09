package com.mobilefork.hermesagent.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobilefork.hermesagent.data.McpConfigActionResult
import com.mobilefork.hermesagent.data.McpConfigurationMode
import com.mobilefork.hermesagent.data.McpPromptCacheResendPolicy
import com.mobilefork.hermesagent.data.McpRuntimeBridge
import com.mobilefork.hermesagent.data.McpRuntimePhase
import com.mobilefork.hermesagent.data.McpRuntimeStatus
import com.mobilefork.hermesagent.data.McpSettings
import com.mobilefork.hermesagent.data.McpSettingsStore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class McpSettingsUiState(
    val mode: McpConfigurationMode = McpConfigurationMode.SIMPLE,
    val configText: String = "",
    val externalMcpEnabled: Boolean = false,
    val providerPromptCacheResendEnabled: Boolean = false,
    val statusMessage: String = "",
    val configFilePath: String = "",
    val runtime: McpRuntimeStatus = McpRuntimeStatus(),
    val busy: Boolean = false,
)

class McpSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = McpSettingsStore(application)
    private val generation = AtomicLong()
    private val _uiState = MutableStateFlow(store.load().toUiState(store.configFilePath()))
    val uiState = _uiState.asStateFlow()

    fun refreshStatus() {
        if (_uiState.value.busy) return
        val request = generation.get()
        viewModelScope.launch(Dispatchers.IO) {
            val observed = McpRuntimeBridge.currentStatus(getApplication())
            if (generation.get() == request && !_uiState.value.busy) _uiState.update { it.copy(runtime = observed) }
        }
    }

    fun selectMode(mode: McpConfigurationMode) {
        if (!_uiState.value.busy) _uiState.update { it.copy(mode = store.saveMode(mode).mode) }
    }

    fun updateAdvancedConfigText(value: String) {
        if (!_uiState.value.busy) _uiState.update { it.copy(configText = value) }
    }

    fun setExternalMcpEnabled(enabled: Boolean) {
        if (enabled && _uiState.value.busy) return
        // Revocation is saved immediately, even while an old reload is busy.
        // Both Python entry and each tool invocation consult this same gate.
        val updated = store.saveExternalMcpEnabled(enabled)
        _uiState.update { it.copy(externalMcpEnabled = updated.externalMcpEnabled) }
        reloadRuntime(allowWhileBusy = !enabled)
    }

    fun saveAdvancedConfigAndReload() {
        val text = _uiState.value.configText
        perform { store.saveAdvancedConfigTextAndReload(text) }
    }

    fun quickAddStdioServer(command: String) = perform { store.quickAddStdioPreset(command) }
    fun quickAddSseServer(url: String) = perform { store.quickAddSsePreset(url) }
    fun quickAddStreamableHttpServer(url: String, authorization: String) =
        perform { store.quickAddStreamableHttpPreset(url, authorization) }

    fun reloadServers() = reloadRuntime()

    private fun perform(allowWhileBusy: Boolean = false, action: (() -> McpConfigActionResult)? = null) {
        if (_uiState.value.busy && !allowWhileBusy) return
        val request = generation.incrementAndGet()
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val saved = action?.invoke()
                val runtime = if (saved?.success != false) McpRuntimeBridge.reloadIntoRuntime(getApplication())
                    else McpRuntimeStatus(McpRuntimePhase.FAILED)
                store.load().toUiState(store.configFilePath()).copy(
                    statusMessage = saved?.statusMessage.orEmpty(), runtime = runtime,
                )
            }.getOrElse { _uiState.value.copy(busy = false, runtime = McpRuntimeStatus(McpRuntimePhase.FAILED)) }
            if (generation.get() == request) _uiState.value = result
        }
    }

    private fun reloadRuntime(allowWhileBusy: Boolean = false) = perform(allowWhileBusy = allowWhileBusy)

    fun updateProviderPromptCacheResend(enabled: Boolean, providerId: String) {
        val updated = store.saveProviderPromptCacheResendEnabled(enabled)
        _uiState.update { it.copy(providerPromptCacheResendEnabled = enabled,
            statusMessage = McpPromptCacheResendPolicy.statusFor(providerId, updated)) }
    }
}

private fun McpSettings.toUiState(path: String) = McpSettingsUiState(
    mode = mode, configText = configText, externalMcpEnabled = externalMcpEnabled,
    providerPromptCacheResendEnabled = providerPromptCacheResendEnabled, configFilePath = path,
    runtime = McpRuntimeStatus(if (externalMcpEnabled) McpRuntimePhase.NEEDS_RUNTIME else McpRuntimePhase.DISABLED),
)
