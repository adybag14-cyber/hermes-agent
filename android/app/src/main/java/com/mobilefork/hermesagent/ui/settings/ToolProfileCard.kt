package com.mobilefork.hermesagent.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobilefork.hermesagent.ui.i18n.LocalHermesStrings

/** Schemas actually offered by the in-app native tool caller. */
private val NATIVE_TOOL_SCHEMAS = listOf(
    "terminal_tool",
    "android_system_tool",
    "android_device_diagnostics_tool",
    "android_ui_tool",
    "android_automation_tool",
)

@Composable
fun ToolProfileCard() {
    val strings = LocalHermesStrings.current
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(strings.toolProfileTitle(), style = MaterialTheme.typography.titleMedium)
            Text(
                strings.toolProfileEnabledSummary(NATIVE_TOOL_SCHEMAS.joinToString()),
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                strings.toolProfileLinuxSummary(),
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                strings.toolProfileAccessibilitySummary(),
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                strings.toolProfileCommandSuiteSummary(),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
