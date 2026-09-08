package com.mobilefork.hermesagent.ui.privacy

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.mobilefork.hermesagent.ui.i18n.*

@Composable
fun VoiceInputDisclosureDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    val strings = LocalHermesStrings.current
    AlertDialog(
        containerColor = opaquePrivacyDialogColor(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.testTag("VoiceInputDisclosure"),
        onDismissRequest = onDecline,
        title = { Text(VoicePrivacyText.title(strings.language)) },
        text = { Text(VoicePrivacyText.body(strings.language)) },
        confirmButton = {
            TextButton(onClick = onAccept, modifier = Modifier.testTag("VoiceInputConsentAccept")) {
                Text(VoicePrivacyText.accept(strings.language))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline, modifier = Modifier.testTag("VoiceInputConsentDecline")) {
                Text(strings.privacyText(PrivacyText.CANCEL))
            }
        },
    )
}
