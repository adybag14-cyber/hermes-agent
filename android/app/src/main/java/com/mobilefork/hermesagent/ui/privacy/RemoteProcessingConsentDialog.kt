package com.mobilefork.hermesagent.ui.privacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mobilefork.hermesagent.privacy.RemoteProcessingTarget
import com.mobilefork.hermesagent.ui.i18n.*

@Composable
fun RemoteProcessingConsentDialog(target: RemoteProcessingTarget, onAccept: () -> Unit, onDecline: () -> Unit) {
    val strings = LocalHermesStrings.current
    AlertDialog(
        containerColor = opaquePrivacyDialogColor(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.testTag("RemoteProcessingConsentDialog"),
        onDismissRequest = onDecline,
        title = { Text(strings.remoteProcessingTitle()) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(target.provider)
                Text(target.endpoint, Modifier.testTag("RemoteProcessingEndpoint"))
                Text(strings.remoteProcessingDisclosure())
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept, modifier = Modifier.testTag("RemoteProcessingAccept")) {
                Text(strings.acceptRemoteProcessing())
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline, modifier = Modifier.testTag("RemoteProcessingDecline")) {
                Text(strings.privacyText(PrivacyText.CANCEL))
            }
        },
    )
}
