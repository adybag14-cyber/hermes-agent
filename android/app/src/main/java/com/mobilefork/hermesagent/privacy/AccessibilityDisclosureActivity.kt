package com.mobilefork.hermesagent.privacy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mobilefork.hermesagent.BuildConfig
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.ui.i18n.*
import com.mobilefork.hermesagent.ui.theme.HermesTheme

class AccessibilityDisclosureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.HERMES_PLAY_EDITION) { finish(); return }
        val strings = hermesStringsFor(AppLanguage.fromTag(AppSettingsStore(this).load().languageTag))
        setContent {
            HermesTheme {
                var failed by remember { mutableStateOf(false) }
                AlertDialog(
                    modifier = Modifier.testTag("AccessibilityDisclosure"),
                    onDismissRequest = ::finish,
                    title = { Text(strings.accessibilityDisclosureTitle()) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                            Text(strings.accessibilityDisclosureBody())
                            if (failed) Text(strings.privacyText(PrivacyText.SAVE_FAILED))
                        }
                    },
                    confirmButton = {
                        TextButton(modifier = Modifier.testTag("AccessibilityConsentAccept"), onClick = {
                            if (runCatching { PrivacyConsentStore(this).acceptAccessibility() }.isSuccess) {
                                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                finish()
                            } else { failed = true }
                        }) { Text(strings.acceptAccessibilityDisclosure()) }
                    },
                    dismissButton = {
                        TextButton(modifier = Modifier.testTag("AccessibilityConsentDecline"), onClick = ::finish) {
                            Text(strings.privacyText(PrivacyText.CANCEL))
                        }
                    },
                )
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, AccessibilityDisclosureActivity::class.java)
    }
}
