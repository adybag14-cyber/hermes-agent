package com.mobilefork.hermesagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobilefork.hermesagent.BuildConfig
import com.mobilefork.hermesagent.data.McpConfigurationMode
import com.mobilefork.hermesagent.data.McpRuntimePhase
import com.mobilefork.hermesagent.ui.i18n.LocalHermesStrings
import com.mobilefork.hermesagent.ui.i18n.McpRuntimeText
import com.mobilefork.hermesagent.ui.i18n.mcpRuntimeText

@Composable
fun McpSettingsSection(
    modifier: Modifier = Modifier,
    selectedProviderId: String = "",
    viewModel: McpSettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(viewModel) { viewModel.refreshStatus() }
    McpSettingsCard(
        uiState = state, selectedProviderId = selectedProviderId, modifier = modifier,
        onProviderPromptCacheResendChange = viewModel::updateProviderPromptCacheResend,
        onEnable = viewModel::setExternalMcpEnabled, onMode = viewModel::selectMode,
        onConfig = viewModel::updateAdvancedConfigText, onSave = viewModel::saveAdvancedConfigAndReload,
        onReload = viewModel::reloadServers, onStdio = viewModel::quickAddStdioServer,
        onSse = viewModel::quickAddSseServer, onHttp = viewModel::quickAddStreamableHttpServer,
    )
}

@Composable
fun McpSettingsCard(
    uiState: McpSettingsUiState,
    selectedProviderId: String,
    onProviderPromptCacheResendChange: (Boolean, String) -> Unit,
    modifier: Modifier = Modifier,
    onEnable: (Boolean) -> Unit = {},
    onMode: (McpConfigurationMode) -> Unit = {},
    onConfig: (String) -> Unit = {},
    onSave: () -> Unit = {},
    onReload: () -> Unit = {},
    onStdio: (String) -> Unit = {},
    onSse: (String) -> Unit = {},
    onHttp: (String, String) -> Unit = { _, _ -> },
) {
    val strings = LocalHermesStrings.current
    if (BuildConfig.HERMES_PLAY_EDITION) {
        Text(strings.mcpRuntimeText(McpRuntimeText.DISABLED), modifier = modifier)
        return
    }
    var confirmEnable by remember { mutableStateOf(false) }
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.mcpConfigurationTitle(), style = MaterialTheme.typography.titleMedium)
            Text(strings.mcpRuntimeText(McpRuntimeText.DESCRIPTION), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val label = strings.mcpRuntimeText(McpRuntimeText.ENABLE)
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = uiState.externalMcpEnabled,
                    enabled = !uiState.busy || uiState.externalMcpEnabled,
                    onCheckedChange = { if (it) confirmEnable = true else onEnable(false) },
                    modifier = Modifier.testTag("McpExternalEnabled").semantics { contentDescription = label },
                )
            }
            Text(strings.mcpRuntimeText(McpRuntimeText.FROZEN), style = MaterialTheme.typography.bodySmall)
            val phaseText = when (uiState.runtime.phase) {
                McpRuntimePhase.DISABLED -> McpRuntimeText.DISABLED
                McpRuntimePhase.NEEDS_RUNTIME -> McpRuntimeText.NEEDS_RUNTIME
                McpRuntimePhase.READY -> McpRuntimeText.READY
                McpRuntimePhase.PARTIAL -> McpRuntimeText.PARTIAL
                McpRuntimePhase.FAILED -> McpRuntimeText.FAILED
                McpRuntimePhase.RESTART_REQUIRED -> McpRuntimeText.RESTART
            }
            Text(strings.mcpRuntimeText(if (uiState.busy) McpRuntimeText.BUSY else phaseText),
                modifier = Modifier.testTag("McpRuntimeStatus"), style = MaterialTheme.typography.bodyMedium)
            if (uiState.runtime.phase in setOf(McpRuntimePhase.READY, McpRuntimePhase.PARTIAL)) {
                Text(strings.mcpRuntimeText(McpRuntimeText.CONNECTED) + ": " +
                    uiState.runtime.connectedServers + " / " + uiState.runtime.totalServers + " · " +
                    strings.mcpRuntimeText(McpRuntimeText.TOOLS) + ": " + uiState.runtime.tools,
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = uiState.mode == McpConfigurationMode.SIMPLE, enabled = !uiState.busy,
                    onClick = { onMode(McpConfigurationMode.SIMPLE) }, label = { Text(strings.mcpSimpleMode()) },
                    modifier = Modifier.testTag("McpSimpleMode"))
                FilterChip(selected = uiState.mode == McpConfigurationMode.ADVANCED, enabled = !uiState.busy,
                    onClick = { onMode(McpConfigurationMode.ADVANCED) }, label = { Text(strings.mcpAdvancedMode()) },
                    modifier = Modifier.testTag("McpAdvancedMode"))
            }
            if (uiState.mode == McpConfigurationMode.SIMPLE) {
                McpQuickAddControls(!uiState.busy, onStdio, onSse, onHttp)
                Text(strings.mcpRuntimeText(McpRuntimeText.STDIO_HELP), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = uiState.configText, onValueChange = onConfig, enabled = !uiState.busy,
                readOnly = uiState.mode != McpConfigurationMode.ADVANCED,
                label = { Text(strings.mcpConfigJsonLabel()) }, minLines = 4, maxLines = 12,
                modifier = Modifier.fillMaxWidth().testTag("McpAdvancedConfigText"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (uiState.mode == McpConfigurationMode.ADVANCED) {
                    Button(onClick = onSave, enabled = !uiState.busy, modifier = Modifier.weight(1f).testTag("McpSaveAdvancedButton")) {
                        Text(strings.mcpSaveAndReload())
                    }
                }
                Button(onClick = onReload, enabled = !uiState.busy, modifier = Modifier.weight(1f).testTag("McpReloadServersButton")) {
                    Text(strings.mcpReloadServers())
                }
            }
            if (uiState.statusMessage.isNotBlank()) {
                Text(strings.mcpStatusText(uiState.statusMessage), style = MaterialTheme.typography.bodySmall)
            }
            Text(strings.mcpConfigFile(uiState.configFilePath), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("McpConfigFilePath"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(strings.mcpProviderCacheResendTitle(), style = MaterialTheme.typography.titleSmall)
                    Text(strings.mcpProviderCacheResendDescription(), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = uiState.providerPromptCacheResendEnabled,
                    onCheckedChange = { onProviderPromptCacheResendChange(it, selectedProviderId) },
                    modifier = Modifier.testTag("McpProviderCacheResendSwitch").semantics {
                        contentDescription = strings.mcpProviderCacheResendTitle()
                    })
            }
        }
    }
    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text(strings.mcpRuntimeText(McpRuntimeText.ENABLE)) },
            text = { Text(strings.mcpRuntimeText(McpRuntimeText.DISCLOSURE)) },
            confirmButton = { TextButton(onClick = { confirmEnable = false; onEnable(true) },
                modifier = Modifier.testTag("McpEnableConfirm")) {
                Text(strings.mcpRuntimeText(McpRuntimeText.ENABLE_CONFIRM))
            } },
            dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text(strings.mcpCancel()) } },
        )
    }
}

private enum class McpQuickTransport { STDIO, SSE, HTTP }

@Composable
private fun McpQuickAddControls(
    enabled: Boolean,
    onStdio: (String) -> Unit,
    onSse: (String) -> Unit,
    onHttp: (String, String) -> Unit,
) {
    val strings = LocalHermesStrings.current
    var dialog by remember { mutableStateOf<McpQuickTransport?>(null) }
    var endpoint by remember { mutableStateOf("") }
    // Credentials are deliberately not saveable UI state.
    var authorization by remember { mutableStateOf("") }
    val labels = mapOf(
        McpQuickTransport.STDIO to strings.mcpQuickAddStdioServer(),
        McpQuickTransport.SSE to strings.mcpQuickAddSseServer(),
        McpQuickTransport.HTTP to strings.mcpRuntimeText(McpRuntimeText.HTTP_ADD),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { (kind, label) ->
            Button(onClick = { dialog = kind; endpoint = ""; authorization = "" }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("McpQuickAdd" + kind.name)) { Text(label) }
        }
    }
    dialog?.let { kind ->
        AlertDialog(
            onDismissRequest = { dialog = null; endpoint = ""; authorization = "" },
            title = { Text(labels.getValue(kind)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = endpoint, onValueChange = { endpoint = it }, singleLine = true,
                        label = { Text(if (kind == McpQuickTransport.STDIO) strings.mcpRuntimeText(McpRuntimeText.EXECUTABLE)
                            else strings.mcpServerUrlLabel()) },
                        modifier = Modifier.testTag("McpServerEndpointInput"))
                    if (kind == McpQuickTransport.HTTP) {
                        OutlinedTextField(value = authorization, onValueChange = { authorization = it }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text(strings.optionalApiTokenLabel()) },
                            modifier = Modifier.testTag("McpStreamableHttpAuthInput"))
                    }
                }
            },
            confirmButton = { TextButton(enabled = endpoint.isNotBlank(), onClick = {
                when (kind) {
                    McpQuickTransport.STDIO -> onStdio(endpoint)
                    McpQuickTransport.SSE -> onSse(endpoint)
                    McpQuickTransport.HTTP -> onHttp(endpoint, authorization)
                }
                dialog = null; endpoint = ""; authorization = ""
            }) { Text(strings.mcpAddAndTest()) } },
            dismissButton = { TextButton(onClick = { dialog = null; endpoint = ""; authorization = "" }) { Text(strings.mcpCancel()) } },
        )
    }
}
