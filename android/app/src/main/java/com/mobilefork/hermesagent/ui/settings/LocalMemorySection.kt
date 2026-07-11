package com.mobilefork.hermesagent.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.mobilefork.hermesagent.device.HermesHyMemoryBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class LocalMemoryEntry(
    val id: String,
    val content: String,
    val hitCount: Int,
    val promoted: Boolean,
)

data class LocalMemoryUiState(
    val loading: Boolean = true,
    val statusLine: String = "",
    val memoryCount: Int = 0,
    val reinforced: Int = 0,
    val promoted: Int = 0,
    val entries: List<LocalMemoryEntry> = emptyList(),
    val error: String = "",
)

class LocalMemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(LocalMemoryUiState())
    val uiState: StateFlow<LocalMemoryUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = "") }
            val app = getApplication<Application>()
            val status = withContext(Dispatchers.IO) {
                JSONObject(HermesHyMemoryBridge.performActionJson(app, "status"))
            }
            val list = withContext(Dispatchers.IO) {
                JSONObject(
                    HermesHyMemoryBridge.performActionJson(
                        app,
                        "list",
                        JSONObject().put("limit", 12),
                    ),
                )
            }
            val entries = ArrayList<LocalMemoryEntry>()
            val array = list.optJSONArray("memories")
                ?: list.optJSONArray("entries")
                ?: list.optJSONArray("items")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    entries += LocalMemoryEntry(
                        id = item.optString("id"),
                        content = item.optString("content").ifBlank { item.optString("text") },
                        hitCount = item.optInt("hit_count", 0),
                        promoted = item.optBoolean("promoted", false) ||
                            item.optString("tier").equals("promoted", ignoreCase = true),
                    )
                }
            }
            val ok = status.optBoolean("success", false)
            _uiState.update {
                it.copy(
                    loading = false,
                    memoryCount = status.optInt("memory_count", entries.size),
                    reinforced = status.optInt("reinforced_memory_count", 0),
                    promoted = status.optInt("promoted_memory_count", 0),
                    statusLine = if (ok) {
                        "hy-memory local companion · ${status.optInt("memory_count", 0)} facts"
                    } else {
                        status.optString("error").ifBlank { "Memory status unavailable" }
                    },
                    entries = entries,
                    error = if (ok) "" else status.optString("error"),
                )
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                HermesHyMemoryBridge.performActionJson(getApplication(), "clear")
            }
            refresh()
        }
    }

    fun delete(id: String) {
        if (id.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                HermesHyMemoryBridge.performActionJson(
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
fun LocalMemorySection(
    modifier: Modifier = Modifier,
    viewModel: LocalMemoryViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("HermesLocalMemorySection"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Local memory (hy-memory)", style = MaterialTheme.typography.titleMedium)
            Text(
                "On-device retain/recall used by the agent (`hy_memory_tool`). Facts stay on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                uiState.statusLine.ifBlank { if (uiState.loading) "Loading…" else "No status" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("HermesLocalMemoryStatus"),
            )
            Text(
                "Reinforced ${uiState.reinforced} · Promoted ${uiState.promoted}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::refresh, enabled = !uiState.loading) {
                    Text("Refresh")
                }
                TextButton(onClick = viewModel::clearAll, enabled = !uiState.loading && uiState.memoryCount > 0) {
                    Text("Clear all")
                }
            }
            if (uiState.error.isNotBlank()) {
                Text(uiState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (uiState.entries.isEmpty() && !uiState.loading) {
                Text(
                    "No retained memories yet. Chat facts the agent stores will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            uiState.entries.take(8).forEach { entry ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        entry.content,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "hits ${entry.hitCount}${if (entry.promoted) " · promoted" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { viewModel.delete(entry.id) }, enabled = entry.id.isNotBlank()) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}
