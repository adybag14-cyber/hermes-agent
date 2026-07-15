package com.mobilefork.hermesagent.ui.kanban

import android.app.Application
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobilefork.hermesagent.data.KanbanBoardSnapshot
import com.mobilefork.hermesagent.data.KanbanBridge
import com.mobilefork.hermesagent.data.KanbanTask
import com.mobilefork.hermesagent.ui.shell.ShellActionItem
import com.mobilefork.hermesagent.ui.i18n.LocalHermesStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val STATUS_FILTERS = listOf(
    "all",
    "ready",
    "running",
    "blocked",
    "todo",
    "triage",
    "done",
)

data class KanbanUiState(
    val loading: Boolean = true,
    val statusFilter: String = "all",
    val board: String = "default",
    val note: String = "",
    val error: String = "",
    val statusMessage: String = "",
    val counts: Map<String, Int> = emptyMap(),
    val tasks: List<KanbanTask> = emptyList(),
    val draftTitle: String = "",
    val draftBody: String = "",
)

class KanbanViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(KanbanUiState())
    val uiState: StateFlow<KanbanUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = "") }
            val filter = _uiState.value.statusFilter
            val statusArg = filter.takeUnless { it == "all" }
            // Chaquopy can still be booting when the user opens Kanban early.
            var merged = KanbanBoardSnapshot(ok = false, error = "Waiting for Hermes runtime…")
            repeat(12) { attempt ->
                val (ensure, snapshot) = withContext(Dispatchers.IO) {
                    val ensured = KanbanBridge.ensureBoard(getApplication())
                    ensured to KanbanBridge.listBoard(getApplication(), status = statusArg)
                }
                merged = if (snapshot.ok) {
                    snapshot.copy(note = ensure.note.ifBlank { snapshot.note })
                } else {
                    snapshot
                }
                if (merged.ok) return@repeat
                val waitingOnPython = merged.error.contains("Python runtime", ignoreCase = true)
                if (!waitingOnPython || attempt == 11) return@repeat
                _uiState.update {
                    it.copy(
                        loading = true,
                        statusMessage = "Waiting for Hermes Python runtime…",
                        error = "",
                    )
                }
                kotlinx.coroutines.delay(1_500)
            }
            applySnapshot(merged, statusMessage = if (merged.ok) "Board refreshed" else merged.error)
        }
    }

    fun setStatusFilter(status: String) {
        _uiState.update { it.copy(statusFilter = status) }
        refresh()
    }

    fun updateDraftTitle(value: String) {
        _uiState.update { it.copy(draftTitle = value) }
    }

    fun updateDraftBody(value: String) {
        _uiState.update { it.copy(draftBody = value) }
    }

    fun createTask() {
        val title = _uiState.value.draftTitle.trim()
        if (title.isBlank()) {
            _uiState.update { it.copy(error = "Title is required") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = "") }
            val snapshot = withContext(Dispatchers.IO) {
                KanbanBridge.createTask(
                    getApplication(),
                    title = title,
                    body = _uiState.value.draftBody.trim(),
                )
            }
            if (snapshot.ok) {
                _uiState.update { it.copy(draftTitle = "", draftBody = "") }
            }
            applySnapshot(snapshot, statusMessage = if (snapshot.ok) "Task created" else snapshot.error)
        }
    }

    fun completeTask(taskId: String) {
        mutate { KanbanBridge.completeTask(getApplication(), taskId, summary = "Completed from Android Kanban") }
    }

    fun unblockTask(taskId: String) {
        mutate { KanbanBridge.unblockTask(getApplication(), taskId) }
    }

    fun commentTask(taskId: String, text: String) {
        if (text.isBlank()) return
        mutate { KanbanBridge.commentTask(getApplication(), taskId, text) }
    }

    private fun mutate(block: () -> KanbanBoardSnapshot) {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = "") }
            val snapshot = withContext(Dispatchers.IO) { block() }
            applySnapshot(snapshot, statusMessage = if (snapshot.ok) "Updated" else snapshot.error)
        }
    }

    private fun applySnapshot(snapshot: KanbanBoardSnapshot, statusMessage: String) {
        _uiState.update {
            it.copy(
                loading = false,
                board = snapshot.board.ifBlank { it.board },
                note = snapshot.note.ifBlank {
                    "Shared SQLite board. Multi-agent workers still need gateway dispatch."
                },
                error = if (snapshot.ok) "" else snapshot.error.ifBlank { statusMessage },
                statusMessage = statusMessage,
                counts = snapshot.counts,
                // When create/complete returns only error snapshot without tasks, keep prior list.
                tasks = if (snapshot.ok || snapshot.tasks.isNotEmpty()) snapshot.tasks else it.tasks,
            )
        }
    }
}

@Composable
fun KanbanScreen(
    modifier: Modifier = Modifier,
    extraBottomSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    viewModel: KanbanViewModel = viewModel(),
    onContextActionsChanged: (List<ShellActionItem>) -> Unit = {},
) {
    val strings = LocalHermesStrings.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    LaunchedEffect(uiState.tasks.size, uiState.error, strings.language) {
        onContextActionsChanged(
            listOf(
                ShellActionItem(
                    label = strings.kanbanRefresh(),
                    description = strings.kanbanRefreshDescription(),
                    iconRes = com.mobilefork.hermesagent.R.drawable.ic_action_refresh,
                    onClick = viewModel::refresh,
                ),
            ),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("HermesKanbanScreen"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(strings.kanbanTitle(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            text = strings.kanbanRuntimeText(uiState.note).ifBlank { strings.kanbanDescription() },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (uiState.statusMessage.isNotBlank()) {
            Text(strings.kanbanRuntimeText(uiState.statusMessage), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (uiState.error.isNotBlank()) {
            Text(strings.kanbanRuntimeText(uiState.error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            STATUS_FILTERS.forEach { status ->
                val count = if (status == "all") uiState.tasks.size else uiState.counts[status] ?: 0
                FilterChip(
                    selected = uiState.statusFilter == status,
                    onClick = { viewModel.setStatusFilter(status) },
                    label = { Text("${strings.kanbanFilter(status)} ($count)") },
                    modifier = Modifier.testTag("HermesKanbanFilter_$status"),
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(strings.kanbanNewTask(), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = uiState.draftTitle,
                    onValueChange = viewModel::updateDraftTitle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("HermesKanbanTitleInput"),
                    singleLine = true,
                    label = { Text(strings.kanbanTaskTitle()) },
                )
                OutlinedTextField(
                    value = uiState.draftBody,
                    onValueChange = viewModel::updateDraftBody,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("HermesKanbanBodyInput"),
                    minLines = 2,
                    maxLines = 4,
                    label = { Text(strings.kanbanTaskDetails()) },
                )
                Button(
                    onClick = viewModel::createTask,
                    modifier = Modifier.testTag("HermesKanbanCreateButton"),
                    enabled = !uiState.loading && uiState.draftTitle.isNotBlank(),
                ) {
                    Text(strings.kanbanCreateTask())
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("HermesKanbanTaskList"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = extraBottomSpacing + 12.dp),
        ) {
            if (uiState.tasks.isEmpty() && !uiState.loading) {
                item {
                    Text(
                        strings.kanbanNoTasks(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(uiState.tasks, key = { it.id }) { task ->
                KanbanTaskCard(
                    task = task,
                    enabled = !uiState.loading,
                    onComplete = { viewModel.completeTask(task.id) },
                    onUnblock = { viewModel.unblockTask(task.id) },
                    onComment = { viewModel.commentTask(task.id, it) },
                )
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun KanbanTaskCard(
    task: KanbanTask,
    enabled: Boolean,
    onComplete: () -> Unit,
    onUnblock: () -> Unit,
    onComment: (String) -> Unit,
) {
    val strings = LocalHermesStrings.current
    var commentDraft by rememberSaveable(task.id) { mutableStateOf("") }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("HermesKanbanTask_${task.id}"),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    strings.kanbanFilter(task.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (task.body.isNotBlank()) {
                Text(
                    task.body,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                buildString {
                    append(task.id)
                    if (task.assignee.isNotBlank()) append(" · ").append(task.assignee)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.status == "blocked") {
                    TextButton(onClick = onUnblock, enabled = enabled) { Text(strings.kanbanUnblock()) }
                }
                if (task.status != "done" && task.status != "archived") {
                    TextButton(onClick = onComplete, enabled = enabled) { Text(strings.kanbanComplete()) }
                }
            }
            OutlinedTextField(
                value = commentDraft,
                onValueChange = { commentDraft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(strings.kanbanComment()) },
                trailingIcon = {
                    TextButton(
                        onClick = {
                            onComment(commentDraft)
                            commentDraft = ""
                        },
                        enabled = enabled && commentDraft.isNotBlank(),
                    ) {
                        Text(strings.kanbanAdd())
                    }
                },
            )
        }
    }
}
