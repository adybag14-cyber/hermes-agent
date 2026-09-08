package com.mobilefork.hermesagent.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import com.mobilefork.hermesagent.privacy.AiReportReceipt
import com.mobilefork.hermesagent.privacy.AiReportReceiptStore
import com.mobilefork.hermesagent.ui.i18n.HermesStrings
import com.mobilefork.hermesagent.ui.i18n.PrivacyText
import com.mobilefork.hermesagent.ui.i18n.privacyText
import com.mobilefork.hermesagent.ui.i18n.revokeAccessibilityDisclosure
import com.mobilefork.hermesagent.ui.i18n.revokeRemoteProcessing
import com.mobilefork.hermesagent.ui.i18n.LocalPrivacyText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PrivacySafetyCard(strings: HermesStrings) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { AiReportReceiptStore(context) }
    var receipts by remember { mutableStateOf(store.load()) }
    var selected by remember { mutableStateOf<AiReportReceipt?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<PrivacyText?>(null) }
    val scope = rememberCoroutineScope()
    var accessibilityAllowed by remember { mutableStateOf(
        com.mobilefork.hermesagent.privacy.PrivacyConsentStore(context).accessibilityAllowed()) }
    Card(Modifier.fillMaxWidth().testTag("PrivacySafetyCard")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(strings.privacyText(PrivacyText.PRIVACY), style = MaterialTheme.typography.titleMedium)
            TextButton(modifier = Modifier.testTag("OpenPrivacyPolicy"), onClick = {
                val result = com.mobilefork.hermesagent.device.HermesExternalBrowserLauncher.open(
                    context, android.net.Uri.parse("https://adybag14-cyber.github.io/hermes-agent/privacy-policy/"),
                    LocalPrivacyText.policy(strings.language), forceChooser = true,
                )
                if (!result.success) status = PrivacyText.POLICY_OPEN_FAILED
            }) { Text(LocalPrivacyText.policy(strings.language)) }
            if (status == PrivacyText.POLICY_OPEN_FAILED) {
                Text("https://adybag14-cyber.github.io/hermes-agent/privacy-policy/")
            }
            TextButton(enabled = !busy, modifier = Modifier.testTag("RevokeRemoteProcessingConsent"), onClick = {
                busy = true
                scope.launch {
                    try {
                        com.mobilefork.hermesagent.privacy.RemoteProcessingConsentStore(context).revokeAll()
                        withContext(Dispatchers.IO) {
                            com.mobilefork.hermesagent.backend.HermesRuntimeService.stop(context)
                            com.mobilefork.hermesagent.backend.HermesRuntimeManager.stopRemoteRuntime()
                        }
                        status = if (com.mobilefork.hermesagent.backend.HermesRuntimeManager.remoteStopRequiresAppRestart())
                            PrivacyText.REVOKED_RESTART else PrivacyText.REMOTE_REVOKED
                    } catch (_: Exception) {
                        status = PrivacyText.SAVE_FAILED
                    } finally { busy = false }
                }
            }) { Text(strings.revokeRemoteProcessing()) }
            if (accessibilityAllowed) {
                TextButton(modifier = Modifier.testTag("RevokeAccessibilityConsent"), onClick = {
                    com.mobilefork.hermesagent.device.HermesAccessibilityController.revokeConsent(context)
                    accessibilityAllowed = false
                }) { Text(strings.revokeAccessibilityDisclosure()) }
            }
            Text(strings.privacyText(PrivacyText.REPORT_NOTICE))
            Text(strings.privacyText(PrivacyText.RECEIPTS), style = MaterialTheme.typography.titleSmall)
            if (receipts.isEmpty()) Text(strings.privacyText(PrivacyText.NO_REPORTS))
            receipts.forEach { receipt ->
                TextButton(enabled = !busy, onClick = { selected = receipt },
                    modifier = Modifier.testTag("DeleteReport-${receipt.id}")) {
                    Text("${strings.privacyText(PrivacyText.DELETE)} · ${receipt.id}")
                }
            }
            status?.let { Text(strings.privacyText(it)) }
            LocalDataDeletionControl(strings)
        }
    }
    selected?.let { receipt ->
        AlertDialog(
            containerColor = opaquePrivacyDialogColor(MaterialTheme.colorScheme.surfaceContainerHigh),
            onDismissRequest = { if (!busy) selected = null },
            title = { Text(strings.privacyText(PrivacyText.DELETE)) },
            text = { Text(receipt.id) },
            confirmButton = {
                TextButton(enabled = !busy, modifier = Modifier.testTag("ConfirmDeleteReport"), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { AiContentReportClient(context).delete(receipt) }
                            receipts = store.load()
                            selected = null
                            status = PrivacyText.DELETED
                        } catch (_: Exception) {
                            status = PrivacyText.DELETE_FAILED
                            selected = null
                        } finally { busy = false }
                    }
                }) { Text(strings.privacyText(PrivacyText.DELETE)) }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { selected = null }) { Text(strings.privacyText(PrivacyText.CANCEL)) }
            },
        )
    }
}
