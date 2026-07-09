@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mobilefork.hermesagent.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mobilefork.hermesagent.ui.i18n.HermesStrings

@Composable
fun AgentEndpointCard(
    loopbackUrl: String,
    lanUrl: String,
    apiKey: String,
    modelName: String,
    started: Boolean,
    onRefresh: () -> Unit,
    strings: HermesStrings,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(strings.agentEndpointTitle(), style = MaterialTheme.typography.titleMedium)
            Text(strings.agentEndpointDescription(), style = MaterialTheme.typography.bodySmall)
            if (!started) {
                Text(strings.agentEndpointNotReady(), style = MaterialTheme.typography.bodySmall)
            } else {
                EndpointRow(
                    label = strings.agentEndpointLoopbackLabel(),
                    value = loopbackUrl,
                    strings = strings,
                    onCopy = { copyToClipboard(context, loopbackUrl, strings.agentEndpointLoopbackLabel()) },
                )
                if (lanUrl.isNotBlank()) {
                    EndpointRow(
                        label = strings.agentEndpointLanLabel(),
                        value = lanUrl,
                        strings = strings,
                        onCopy = { copyToClipboard(context, lanUrl, strings.agentEndpointLanLabel()) },
                    )
                }
                if (modelName.isNotBlank()) {
                    Text(
                        strings.agentEndpointModelLabel(modelName),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (apiKey.isNotBlank()) {
                    EndpointRow(
                        label = strings.agentEndpointApiKeyLabel(),
                        value = strings.agentEndpointApiKeyMasked(),
                        strings = strings,
                        onCopy = { copyToClipboard(context, apiKey, strings.agentEndpointApiKeyLabel()) },
                    )
                }
                Text(strings.agentEndpointAcpHint(), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRefresh,
                    modifier = Modifier.testTag("AgentEndpointRefreshButton"),
                ) {
                    Text(strings.agentEndpointRefresh())
                }
            }
        }
    }
}

@Composable
private fun EndpointRow(
    label: String,
    value: String,
    strings: HermesStrings,
    onCopy: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodySmall)
        Button(onClick = onCopy) {
            Text(strings.copyMessageLabel())
        }
    }
}

private fun copyToClipboard(context: Context, value: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}