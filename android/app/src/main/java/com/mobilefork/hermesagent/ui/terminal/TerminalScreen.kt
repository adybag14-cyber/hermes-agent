package com.mobilefork.hermesagent.ui.terminal

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobilefork.hermesagent.device.NativeAndroidShellTool
import com.mobilefork.hermesagent.ui.i18n.LocalHermesStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TerminalEntry(val id: Long, val command: String, val output: String, val exitCode: Int)

data class TerminalUiState(
    val command: String = "",
    val entries: List<TerminalEntry> = emptyList(),
    val running: Boolean = false,
)

class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(TerminalUiState())
    val uiState = _uiState.asStateFlow()

    fun updateCommand(value: String) = _uiState.update { it.copy(command = value) }

    fun clear() = _uiState.update { it.copy(entries = emptyList()) }

    fun run() {
        val command = _uiState.value.command.trim()
        if (command.isBlank() || _uiState.value.running) return
        _uiState.update { it.copy(command = "", running = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                NativeAndroidShellTool.run(
                    context = getApplication<Application>(),
                    command = command,
                    timeoutSeconds = 300,
                    includeLinuxSandboxStatus = false,
                )
            }
            val json = result.getOrNull()
            val exitCode = json?.optInt("exit_code", 1) ?: 1
            val output = if (json != null) {
                listOf(json.optString("output"), json.optString("error"))
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                    .ifBlank { "(no output)" }
            } else {
                result.exceptionOrNull()?.message ?: "Command failed"
            }
            _uiState.update {
                it.copy(
                    running = false,
                    entries = it.entries + TerminalEntry(System.nanoTime(), command, output, exitCode),
                )
            }
        }
    }
}

@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier,
    viewModel: TerminalViewModel = viewModel(),
    extraBottomSpacing: Dp = 0.dp,
) {
    val state by viewModel.uiState.collectAsState()
    val strings = LocalHermesStrings.current
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings.terminalTitle(), style = MaterialTheme.typography.titleMedium)
            Text(
                strings.terminalDescription(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("HermesManualTerminalOutput"),
                contentPadding = PaddingValues(bottom = extraBottomSpacing),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.entries, key = { it.id }) { entry ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("$ ${entry.command}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                            Text(
                                entry.output.ifBlank {
                                    if (entry.exitCode == 0) strings.noCommandOutputLabel() else strings.commandFailedLabel()
                                },
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(strings.exitCodeLabel(entry.exitCode), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = state.command,
                onValueChange = viewModel::updateCommand,
                modifier = Modifier.fillMaxWidth().testTag("HermesManualTerminalInput"),
                enabled = !state.running,
                label = { Text(strings.commandLabel()) },
                minLines = 1,
                maxLines = 4,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::run, enabled = !state.running && state.command.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Text(if (state.running) strings.runningLabel() else strings.runLabel())
                }
                Button(onClick = viewModel::clear, enabled = state.entries.isNotEmpty()) { Text(strings.clearLabel()) }
            }
        }
    }
}
