package com.mobilefork.hermesagent.ui.device

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobilefork.hermesagent.device.HermesAutomationBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AutomationListItem(
    val id: String,
    val label: String,
    val enabled: Boolean,
    val trigger: String,
    val actionType: String,
)

data class AutomationsUiState(
    val loading: Boolean = true,
    val items: List<AutomationListItem> = emptyList(),
    val status: String = "",
    val error: String = "",
)

class AutomationsViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(AutomationsUiState())
    val uiState: StateFlow<AutomationsUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = "") }
            val raw = withContext(Dispatchers.IO) {
                HermesAutomationBridge.performActionJson(getApplication(), "list")
            }
            val json = JSONObject(raw)
            val array = json.optJSONArray("automations")
            val items = ArrayList<AutomationListItem>()
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    items += AutomationListItem(
                        id = item.optString("id"),
                        label = item.optString("label").ifBlank { item.optString("title") },
                        enabled = item.optBoolean("enabled", true),
                        trigger = item.optString("trigger").ifBlank { item.optString("trigger_type") },
                        actionType = item.optString("action_type").ifBlank { item.optString("action") },
                    )
                }
            }
            _uiState.update {
                it.copy(
                    loading = false,
                    items = items,
                    status = if (json.optBoolean("success", false)) {
                        "${items.size} automation(s) on device"
                    } else {
                        json.optString("error").ifBlank { "Could not list automations" }
                    },
                    error = if (json.optBoolean("success", false)) "" else json.optString("error"),
                )
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        if (id.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                HermesAutomationBridge.performActionJson(
                    getApplication(),
                    if (enabled) "enable" else "disable",
                    JSONObject().put("id", id),
                )
            }
            refresh()
        }
    }

    fun runNow(id: String) {
        if (id.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                HermesAutomationBridge.performActionJson(
                    getApplication(),
                    "run",
                    JSONObject().put("id", id),
                )
            }
            refresh()
        }
    }

    fun delete(id: String) {
        if (id.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                HermesAutomationBridge.performActionJson(
                    getApplication(),
                    "delete",
                    JSONObject().put("id", id),
                )
            }
            refresh()
        }
    }
}

@Composable
fun AutomationsCard(
    modifier: Modifier = Modifier,
    viewModel: AutomationsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("HermesAutomationsCard"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Phone automations", style = MaterialTheme.typography.titleMedium)
            Text(
                "Scheduled and event-driven tasks on this device (not gateway cron). Enable, run, or delete here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                uiState.status.ifBlank { if (uiState.loading) "Loading…" else "No automations" },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = viewModel::refresh, enabled = !uiState.loading) {
                Text("Refresh")
            }
            if (uiState.error.isNotBlank()) {
                Text(uiState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (uiState.items.isEmpty() && !uiState.loading) {
                Text(
                    "No automations yet. Ask Hermes to schedule a task or create one via agent tools.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            uiState.items.take(12).forEach { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        item.label.ifBlank { item.id },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            item.trigger.takeIf { it.isNotBlank() },
                            item.actionType.takeIf { it.isNotBlank() },
                            item.id.takeIf { it.isNotBlank() },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Switch(
                            checked = item.enabled,
                            onCheckedChange = { viewModel.setEnabled(item.id, it) },
                        )
                        Text(if (item.enabled) "On" else "Off", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { viewModel.runNow(item.id) }) { Text("Run") }
                        TextButton(onClick = { viewModel.delete(item.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}
