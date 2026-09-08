package com.mobilefork.hermesagent.ui.privacy

import android.app.ActivityManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.mobilefork.hermesagent.ui.i18n.*

@Composable
fun LocalDataDeletionControl(strings: HermesStrings) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    Text(strings.let { LocalPrivacyText.accountScope(it.language) })
    TextButton(onClick = { confirm = true }, modifier = Modifier.testTag("DeleteLocalAppData")) {
        Text(LocalPrivacyText.delete(strings.language))
    }
    if (denied) Text(LocalPrivacyText.deletionDenied(strings.language))
    if (confirm) AlertDialog(
        modifier = Modifier.testTag("DeleteLocalDataConfirmation"),
        onDismissRequest = { confirm = false },
        title = { Text(LocalPrivacyText.delete(strings.language)) },
        text = { Text(LocalPrivacyText.warning(strings.language)) },
        confirmButton = {
            TextButton(modifier = Modifier.testTag("ConfirmDeleteLocalAppData"), onClick = {
                confirm = false
                // No caller-controlled package or path: Android can clear only this application's own data.
                val accepted = runCatching {
                    context.getSystemService(ActivityManager::class.java).clearApplicationUserData()
                }.getOrDefault(false)
                denied = !accepted
            }) { Text(LocalPrivacyText.delete(strings.language)) }
        },
        dismissButton = {
            TextButton(modifier = Modifier.testTag("CancelDeleteLocalAppData"), onClick = { confirm = false }) {
                Text(strings.privacyText(PrivacyText.CANCEL))
            }
        },
    )
}
