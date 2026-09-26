package com.mobilefork.hermesagent.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.mobilefork.hermesagent.ui.i18n.LocalHermesStrings
import com.mobilefork.hermesagent.ui.i18n.modelSettingsText

/** A labelled, keyboard-accessible disclosure. Collapsing never changes a saved setting. */
@Composable
internal fun SettingsDisclosure(
    sectionId: String,
    title: String,
    summary: String,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(sectionId) { mutableStateOf(initiallyExpanded) }
    val language = LocalHermesStrings.current.language
    val stateLabel = modelSettingsText(language, if (expanded) "expanded" else "collapsed")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag(sectionId)
                .semantics { stateDescription = stateLabel },
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(summary, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (expanded) "−" else "+", modifier = Modifier.padding(start = 12.dp).clearAndSetSemantics { })
            }
        }
        if (expanded) content()
    }
}
