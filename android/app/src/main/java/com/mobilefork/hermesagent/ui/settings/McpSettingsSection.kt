package com.mobilefork.hermesagent.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobilefork.hermesagent.data.McpConfigurationMode
import com.mobilefork.hermesagent.data.McpPromptCacheResendPolicy
import com.mobilefork.hermesagent.data.McpSettings
import com.mobilefork.hermesagent.data.McpSettingsMessages
import com.mobilefork.hermesagent.data.McpSettingsStore
import com.mobilefork.hermesagent.ui.i18n.LocalHermesStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class McpSettingsUiState(
    val mode: McpConfigurationMode = McpConfigurationMode.SIMPLE,
    val configText: String = "",
    val providerPromptCacheResendEnabled: Boolean = false,
    val statusMessage: String = McpSettingsMessages.SIMPLE_READY,
    val configFilePath: String = "",
    val lastReloadEpochMs: Long = 0L,
)

class McpSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = McpSettingsStore(application)
    private val _uiState = MutableStateFlow(store.load().toUiState(store.configFilePath()))
    val uiState: StateFlow<McpSettingsUiState> = _uiState.asStateFlow()

    fun reloadFromDisk() {
        _uiState.value = store.load().toUiState(store.configFilePath())
    }

    fun selectMode(mode: McpConfigurationMode) {
        _uiState.value = store.saveMode(mode).toUiState(store.configFilePath())
    }

    fun detectExistingConfiguration() {
        val result = store.detectExistingConfiguration()
        _uiState.update {
            it.copy(
                configText = result.configText,
                statusMessage = result.statusMessage,
                lastReloadEpochMs = result.lastReloadEpochMs.takeIf { value -> value > 0L } ?: it.lastReloadEpochMs,
            )
        }
    }

    fun autoFillSimpleConfiguration() {
        val result = store.autoFillSimpleConfiguration()
        _uiState.update {
            it.copy(
                mode = McpConfigurationMode.SIMPLE,
                configText = result.configText,
                statusMessage = result.statusMessage,
            )
        }
    }

    fun autoSetupSimpleConfiguration() {
        val result = store.autoSetupSimpleConfiguration()
        _uiState.update {
            it.copy(
                mode = McpConfigurationMode.SIMPLE,
                configText = result.configText,
                statusMessage = result.statusMessage,
                lastReloadEpochMs = result.lastReloadEpochMs,
            )
        }
    }

    fun addDraftServer(serverNameOrCommand: String, note: String) {
        val result = store.addDraftServer(serverNameOrCommand, note)
        _uiState.update {
            it.copy(
                mode = McpConfigurationMode.SIMPLE,
                configText = result.configText,
                statusMessage = result.statusMessage,
                lastReloadEpochMs = result.lastReloadEpochMs.takeIf { value -> value > 0L } ?: it.lastReloadEpochMs,
            )
        }
    }

    fun updateAdvancedConfigText(value: String) {
        _uiState.update { it.copy(configText = value) }
    }

    fun saveAdvancedConfigAndReload() {
        val result = store.saveAdvancedConfigTextAndReload(_uiState.value.configText)
        _uiState.update {
            it.copy(
                mode = McpConfigurationMode.ADVANCED,
                configText = result.configText,
                statusMessage = result.statusMessage,
                lastReloadEpochMs = result.lastReloadEpochMs.takeIf { value -> value > 0L } ?: it.lastReloadEpochMs,
            )
        }
    }

    fun reloadServers() {
        val result = store.reloadServers()
        _uiState.update {
            it.copy(
                configText = result.configText,
                statusMessage = result.statusMessage,
                lastReloadEpochMs = result.lastReloadEpochMs.takeIf { value -> value > 0L } ?: it.lastReloadEpochMs,
            )
        }
    }

    fun updateProviderPromptCacheResend(enabled: Boolean, providerId: String = "") {
        val updated = store.saveProviderPromptCacheResendEnabled(enabled)
        _uiState.value = updated.toUiState(
            configFilePath = store.configFilePath(),
            statusOverride = McpPromptCacheResendPolicy.statusFor(providerId, updated),
        )
    }
}

@Composable
fun McpSettingsSection(
    modifier: Modifier = Modifier,
    selectedProviderId: String = "",
    viewModel: McpSettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    McpSettingsCard(
        modifier = modifier,
        uiState = uiState,
        selectedProviderId = selectedProviderId,
        onModeChange = viewModel::selectMode,
        onDetect = viewModel::detectExistingConfiguration,
        onAutoFill = viewModel::autoFillSimpleConfiguration,
        onAutoSetup = viewModel::autoSetupSimpleConfiguration,
        onAddDraftServer = viewModel::addDraftServer,
        onConfigTextChange = viewModel::updateAdvancedConfigText,
        onSaveAdvanced = viewModel::saveAdvancedConfigAndReload,
        onReloadServers = viewModel::reloadServers,
        onProviderPromptCacheResendChange = viewModel::updateProviderPromptCacheResend,
    )
}

@Composable
fun McpSettingsCard(
    uiState: McpSettingsUiState,
    selectedProviderId: String,
    onModeChange: (McpConfigurationMode) -> Unit,
    onDetect: () -> Unit,
    onAutoFill: () -> Unit,
    onAutoSetup: () -> Unit,
    onAddDraftServer: (String, String) -> Unit,
    onConfigTextChange: (String) -> Unit,
    onSaveAdvanced: () -> Unit,
    onReloadServers: () -> Unit,
    onProviderPromptCacheResendChange: (Boolean, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalHermesStrings.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(strings.mcpConfigurationTitle(), style = MaterialTheme.typography.titleMedium)
            Text(
                strings.mcpConfigurationDescription(),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("McpSimpleModeButton"),
                    onClick = { onModeChange(McpConfigurationMode.SIMPLE) },
                    enabled = uiState.mode != McpConfigurationMode.SIMPLE,
                ) {
                    McpButtonLabel(strings.mcpSimpleMode())
                }
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .testTag("McpAdvancedModeButton"),
                    onClick = { onModeChange(McpConfigurationMode.ADVANCED) },
                    enabled = uiState.mode != McpConfigurationMode.ADVANCED,
                ) {
                    McpButtonLabel(strings.mcpAdvancedMode())
                }
            }
            Text(
                strings.mcpConfigFile(uiState.configFilePath),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("McpConfigFilePath"),
            )
            if (uiState.mode == McpConfigurationMode.SIMPLE) {
                SimpleMcpOnboardingControls(
                    configText = uiState.configText,
                    onDetect = onDetect,
                    onAutoFill = onAutoFill,
                    onAutoSetup = onAutoSetup,
                    onAddDraftServer = onAddDraftServer,
                    onReloadServers = onReloadServers,
                )
            } else {
                AdvancedMcpConfigEditor(
                    configText = uiState.configText,
                    onConfigTextChange = onConfigTextChange,
                    onSaveAdvanced = onSaveAdvanced,
                    onReloadServers = onReloadServers,
                )
            }
            ProviderPromptCacheControls(
                enabled = uiState.providerPromptCacheResendEnabled,
                selectedProviderId = selectedProviderId,
                onChange = onProviderPromptCacheResendChange,
            )
            Text(
                strings.mcpStatusText(uiState.statusMessage),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("McpStatusMessage"),
            )
        }
    }
}

@Composable
private fun SimpleMcpOnboardingControls(
    configText: String,
    onDetect: () -> Unit,
    onAutoFill: () -> Unit,
    onAutoSetup: () -> Unit,
    onAddDraftServer: (String, String) -> Unit,
    onReloadServers: () -> Unit,
) {
    val strings = LocalHermesStrings.current
    var addDialogVisible by rememberSaveable { mutableStateOf(false) }
    var serverName by rememberSaveable { mutableStateOf("") }
    var serverNote by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                modifier = Modifier
                    .weight(1f)
                    .testTag("McpAutoDetectButton"),
                onClick = onDetect,
            ) {
                McpButtonLabel(strings.mcpAutoDetect())
            }
            Button(
                modifier = Modifier
                    .weight(1f)
                    .testTag("McpAutoFillButton"),
                onClick = onAutoFill,
            ) {
                McpButtonLabel(strings.mcpAutoFill())
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                modifier = Modifier
                    .weight(1f)
                    .testTag("McpAddDraftServerButton"),
                onClick = { addDialogVisible = true },
            ) {
                McpButtonLabel(strings.mcpAddServer())
            }
            Button(
                modifier = Modifier
                    .weight(1f)
                    .testTag("McpAutoSetupButton"),
                onClick = onAutoSetup,
            ) {
                McpButtonLabel(strings.mcpAutoSetup())
            }
            Button(
                modifier = Modifier
                    .weight(1f)
                    .testTag("McpTestRefreshButton"),
                onClick = onReloadServers,
            ) {
                McpButtonLabel(strings.mcpTestRefresh())
            }
        }
        OutlinedTextField(
            value = strings.mcpConfigPreviewText(configText),
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .testTag("McpSimpleConfigPreview"),
            label = { Text(strings.mcpPreview()) },
            minLines = 4,
            maxLines = 10,
            readOnly = true,
        )
    }
    if (addDialogVisible) {
        AlertDialog(
            onDismissRequest = { addDialogVisible = false },
            title = { Text(strings.mcpAddDialogTitle()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.mcpAddDialogDescription(), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = serverName,
                        onValueChange = { serverName = it },
                        label = { Text(strings.mcpServerNameLabel()) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = serverNote,
                        onValueChange = { serverNote = it },
                        label = { Text(strings.mcpServerNoteLabel()) },
                        minLines = 2,
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = serverName.isNotBlank(),
                    onClick = {
                        onAddDraftServer(serverName, serverNote)
                        serverName = ""
                        serverNote = ""
                        addDialogVisible = false
                    },
                ) {
                    Text(strings.mcpAddAndTest())
                }
            },
            dismissButton = {
                TextButton(onClick = { addDialogVisible = false }) {
                    Text(strings.mcpCancel())
                }
            },
        )
    }
}

@Composable
private fun AdvancedMcpConfigEditor(
    configText: String,
    onConfigTextChange: (String) -> Unit,
    onSaveAdvanced: () -> Unit,
    onReloadServers: () -> Unit,
) {
    val strings = LocalHermesStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                modifier = Modifier
                    .weight(1f)
                    .testTag("McpSaveAdvancedButton"),
                onClick = onSaveAdvanced,
            ) {
                McpButtonLabel(strings.mcpSaveAndReload())
            }
            Button(
                modifier = Modifier
                    .weight(1f)
                    .testTag("McpReloadServersButton"),
                onClick = onReloadServers,
            ) {
                McpButtonLabel(strings.mcpReloadServers())
            }
        }
        OutlinedTextField(
            value = configText,
            onValueChange = onConfigTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("McpAdvancedConfigText"),
            label = { Text(strings.mcpConfigJsonLabel()) },
            minLines = 8,
            maxLines = 18,
        )
    }
}

@Composable
private fun ProviderPromptCacheControls(
    enabled: Boolean,
    selectedProviderId: String,
    onChange: (Boolean, String) -> Unit,
) {
    val strings = LocalHermesStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(strings.mcpProviderCacheResendTitle(), style = MaterialTheme.typography.titleSmall)
            Text(
                strings.mcpProviderCacheResendDescription(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            modifier = Modifier.testTag("McpProviderCacheResendSwitch"),
            checked = enabled,
            onCheckedChange = { onChange(it, selectedProviderId) },
        )
    }
}

@Composable
private fun McpButtonLabel(text: String) {
    Text(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun McpSettings.toUiState(
    configFilePath: String,
    statusOverride: String? = null,
): McpSettingsUiState {
    return McpSettingsUiState(
        mode = mode,
        configText = configText,
        providerPromptCacheResendEnabled = providerPromptCacheResendEnabled,
        statusMessage = statusOverride ?: lastStatusMessage,
        configFilePath = configFilePath,
        lastReloadEpochMs = lastReloadEpochMs,
    )
}
