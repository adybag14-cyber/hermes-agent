package com.mobilefork.hermesagent.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.mobilefork.hermesagent.data.HermesSkill
import com.mobilefork.hermesagent.data.SkillsBridge
import com.mobilefork.hermesagent.data.SkillsSnapshot
import com.mobilefork.hermesagent.ui.i18n.LocalHermesStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SkillsUiState(
    val loading: Boolean = true,
    val error: String = "",
    val note: String = "",
    val toggleSupported: Boolean = false,
    val skills: List<HermesSkill> = emptyList(),
    val statusMessage: String = "",
)

class SkillsViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(SkillsUiState())
    val uiState: StateFlow<SkillsUiState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = "") }
            var snapshot = SkillsSnapshot(ok = false, error = "Waiting for Hermes runtime…")
            repeat(12) { attempt ->
                snapshot = withContext(Dispatchers.IO) {
                    SkillsBridge.listSkills(getApplication())
                }
                if (snapshot.ok) return@repeat
                val waitingOnPython = snapshot.error.contains("Python runtime", ignoreCase = true)
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
            applySnapshot(
                snapshot,
                statusMessage = if (snapshot.ok) {
                    "Skills refreshed (${snapshot.skills.size})"
                } else {
                    snapshot.error
                },
            )
        }
    }

    fun setEnabled(name: String, enabled: Boolean) {
        if (!_uiState.value.toggleSupported) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = "") }
            val snapshot = withContext(Dispatchers.IO) {
                SkillsBridge.setSkillEnabled(getApplication(), name, enabled)
            }
            applySnapshot(
                snapshot,
                statusMessage = if (snapshot.ok) {
                    if (enabled) "Enabled $name" else "Disabled $name"
                } else {
                    snapshot.error
                },
            )
        }
    }

    private fun applySnapshot(snapshot: SkillsSnapshot, statusMessage: String) {
        _uiState.update {
            it.copy(
                loading = false,
                error = if (snapshot.ok) "" else snapshot.error.ifBlank { statusMessage },
                note = snapshot.note,
                toggleSupported = snapshot.toggleSupported,
                skills = if (snapshot.ok || snapshot.skills.isNotEmpty()) snapshot.skills else it.skills,
                statusMessage = statusMessage,
            )
        }
    }
}

@Composable
fun SkillsSection(
    modifier: Modifier = Modifier,
    viewModel: SkillsViewModel = viewModel(),
) {
    val strings = LocalHermesStrings.current
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("HermesSkillsSection"),
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
            Text(strings.skillsTitle(), style = MaterialTheme.typography.titleMedium)
            Text(
                strings.skillsDescription(),
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
                        .testTag("SkillsRefreshButton"),
                    onClick = viewModel::refresh,
                    enabled = !uiState.loading,
                ) {
                    Text(if (uiState.loading) strings.loadingLabel() else strings.refresh)
                }
            }
            if (uiState.note.isNotBlank()) {
                Text(
                    uiState.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("SkillsNote"),
                )
            }
            if (uiState.error.isNotBlank()) {
                Text(
                    uiState.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("SkillsError"),
                )
            }
            if (uiState.statusMessage.isNotBlank() && uiState.error.isBlank()) {
                Text(
                    strings.skillsStatusText(uiState.statusMessage),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("SkillsStatus"),
                )
            }
            if (uiState.skills.isEmpty() && !uiState.loading) {
                Text(
                    strings.skillsEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .testTag("SkillsList"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                userScrollEnabled = true,
            ) {
                items(uiState.skills, key = { it.name }) { skill ->
                    SkillRow(
                        skill = skill,
                        toggleSupported = uiState.toggleSupported,
                        enabledControls = !uiState.loading && uiState.toggleSupported,
                        onEnabledChange = { viewModel.setEnabled(skill.name, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillRow(
    skill: HermesSkill,
    toggleSupported: Boolean,
    enabledControls: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("SkillRow-${skill.name}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                skill.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (skill.description.isNotBlank()) {
                Text(
                    skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (toggleSupported) {
            Switch(
                modifier = Modifier.testTag("SkillSwitch-${skill.name}"),
                checked = skill.enabled,
                onCheckedChange = onEnabledChange,
                enabled = enabledControls,
            )
        }
    }
}
