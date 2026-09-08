package com.mobilefork.hermesagent.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mobilefork.hermesagent.privacy.AiContentReportClient
import com.mobilefork.hermesagent.privacy.AiReportReason
import com.mobilefork.hermesagent.privacy.AiReportReceipt
import com.mobilefork.hermesagent.ui.i18n.LocalHermesStrings
import com.mobilefork.hermesagent.ui.i18n.PrivacyText
import com.mobilefork.hermesagent.ui.i18n.privacyText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiContentReportDialog(message: String, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val strings = LocalHermesStrings.current
    val scope = rememberCoroutineScope()
    val client = remember(context) { AiContentReportClient(context) }
    var preview by remember { mutableStateOf(message.take(4000)) }
    var notes by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf(AiReportReason.OTHER) }
    var receipt by remember { mutableStateOf<AiReportReceipt?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<PrivacyText?>(null) }
    val confirmed = receipt?.submitted == true
    val frozen = receipt != null || busy
    AlertDialog(
        modifier = Modifier.testTag("AiContentReportDialog"),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(strings.privacyText(PrivacyText.REPORT)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(strings.privacyText(PrivacyText.REPORT_NOTICE))
                Text(AiContentReportClient.ENDPOINT)
                OutlinedTextField(value = preview, onValueChange = { preview = it.take(4000) },
                    enabled = !frozen, modifier = Modifier.fillMaxWidth().testTag("AiReportPreview"),
                    label = { Text(strings.privacyText(PrivacyText.PREVIEW)) })
                AiReportReason.entries.forEach { item ->
                    TextButton(onClick = { reason = item }, enabled = !frozen) {
                        Text((if (reason == item) "✓ " else "") + strings.privacyText(reasonText(item)))
                    }
                }
                OutlinedTextField(value = notes, onValueChange = { notes = it.take(1000) }, enabled = !frozen,
                    modifier = Modifier.fillMaxWidth().testTag("AiReportNotes"),
                    label = { Text(strings.privacyText(PrivacyText.NOTES)) })
                status?.let { Text(strings.privacyText(it), Modifier.testTag("AiReportStatus")) }
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            if (!confirmed) TextButton(
                enabled = !busy && preview.isNotBlank(), modifier = Modifier.testTag("AiReportSubmit"),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            // Save the deletion secret before attempting transport, including uncertain delivery.
                            val prepared = receipt ?: withContext(Dispatchers.IO) { client.prepare() }
                            receipt = prepared
                            receipt = withContext(Dispatchers.IO) { client.submit(prepared, reason, preview, notes) }
                            status = PrivacyText.SENT
                        } catch (_: Exception) {
                            status = PrivacyText.FAILED
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(strings.privacyText(PrivacyText.SUBMIT)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.testTag("AiReportCancel")) {
                Text(strings.privacyText(if (confirmed) PrivacyText.CLOSE else PrivacyText.CANCEL))
            }
        },
    )
}

private fun reasonText(reason: AiReportReason): PrivacyText = when (reason) {
    AiReportReason.CHILD_SAFETY -> PrivacyText.CHILD
    AiReportReason.VIOLENCE -> PrivacyText.VIOLENCE
    AiReportReason.SEXUAL_CONTENT -> PrivacyText.SEXUAL
    AiReportReason.HATE -> PrivacyText.HATE
    AiReportReason.DECEPTIVE_CONTENT -> PrivacyText.DECEPTION
    AiReportReason.OTHER -> PrivacyText.OTHER
}
