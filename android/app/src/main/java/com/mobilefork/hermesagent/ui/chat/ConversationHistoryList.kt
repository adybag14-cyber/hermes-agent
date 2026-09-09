package com.mobilefork.hermesagent.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobilefork.hermesagent.ui.i18n.ConversationHistoryText
import com.mobilefork.hermesagent.ui.i18n.LocalHermesStrings
import com.mobilefork.hermesagent.ui.i18n.historyText

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationHistoryList(
    summaries: List<ChatConversationSummary>,
    onOpenConversation: (String) -> Unit,
    onStartNew: () -> Unit,
    onRename: (String, String) -> Unit,
    onRegenerateTitle: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalHermesStrings.current
    var menuId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var titleDraft by rememberSaveable { mutableStateOf("") }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(strings.conversationHistoryTitle(), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f))
            Button(onClick = onStartNew) { Text(strings.newChat) }
        }
        if (summaries.isEmpty()) {
            Text(strings.noConversationHistory(), modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(summaries, key = { it.id }) { summary ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().testTag("HermesHistoryRow-${summary.id}")
                            .combinedClickable(
                                onClick = { onOpenConversation(summary.id) },
                                onLongClickLabel = strings.historyText(ConversationHistoryText.ACTIONS),
                                onLongClick = { menuId = summary.id },
                            ),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (summary.isDefaultTitle) strings.newChat else summary.title,
                                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Box {
                                    TextButton(onClick = { menuId = summary.id },
                                        modifier = Modifier.testTag("HermesHistoryActions-${summary.id}")) {
                                        Text(strings.historyText(ConversationHistoryText.ACTIONS))
                                    }
                                    DropdownMenu(expanded = menuId == summary.id, onDismissRequest = { menuId = null }) {
                                        DropdownMenuItem(text = { Text(strings.historyText(ConversationHistoryText.RENAME)) },
                                            onClick = { menuId = null; renameId = summary.id; titleDraft = summary.title })
                                        DropdownMenuItem(text = { Text(strings.historyText(ConversationHistoryText.REGENERATE)) },
                                            onClick = { menuId = null; onRegenerateTitle(summary.id) })
                                        DropdownMenuItem(text = { Text(strings.historyText(ConversationHistoryText.DELETE)) },
                                            onClick = { menuId = null; deleteId = summary.id })
                                    }
                                }
                            }
                            if (summary.preview.isNotBlank()) Text(summary.preview, style = MaterialTheme.typography.bodySmall,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("${summary.updatedLabel} · ${strings.messageCount(summary.messageCount)}",
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
    if (renameId != null) AlertDialog(
        onDismissRequest = { renameId = null },
        title = { Text(strings.historyText(ConversationHistoryText.RENAME)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = titleDraft, onValueChange = { titleDraft = it.take(192) },
                    singleLine = true, label = { Text(strings.historyText(ConversationHistoryText.TITLE)) },
                    modifier = Modifier.testTag("HermesHistoryTitleInput"))
                Text(strings.historyText(ConversationHistoryText.LOCAL_ONLY))
            }
        },
        confirmButton = { TextButton(enabled = titleDraft.isNotBlank(), onClick = {
            renameId?.let { onRename(it, titleDraft) }; renameId = null
        }) { Text(strings.historyText(ConversationHistoryText.SAVE)) } },
        dismissButton = { TextButton(onClick = { renameId = null }) { Text(strings.historyText(ConversationHistoryText.CANCEL)) } },
    )
    if (deleteId != null) AlertDialog(
        onDismissRequest = { deleteId = null },
        title = { Text(strings.historyText(ConversationHistoryText.DELETE)) },
        text = { Text(strings.historyText(ConversationHistoryText.DELETE_CONFIRM)) },
        confirmButton = { TextButton(onClick = { deleteId?.let(onDelete); deleteId = null }) {
            Text(strings.historyText(ConversationHistoryText.DELETE))
        } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text(strings.historyText(ConversationHistoryText.CANCEL)) } },
    )
}
