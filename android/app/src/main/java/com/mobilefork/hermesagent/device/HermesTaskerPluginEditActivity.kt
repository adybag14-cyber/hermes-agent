package com.mobilefork.hermesagent.device

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.mobilefork.hermesagent.R
import com.mobilefork.hermesagent.ui.theme.HermesChoiceAdapter
import com.mobilefork.hermesagent.ui.theme.applyHermesViewTree
import com.mobilefork.hermesagent.ui.theme.applyHermesViewWindowTheme
import com.mobilefork.hermesagent.ui.theme.hermesDp
import com.mobilefork.hermesagent.ui.theme.hermesLocalizedContext
import com.mobilefork.hermesagent.ui.theme.hermesScrollablePage
import com.mobilefork.hermesagent.ui.theme.hermesViewPalette
import com.mobilefork.hermesagent.ui.theme.hermesViewPanelDrawable

class HermesTaskerPluginEditActivity : Activity() {
    private val palette by lazy { hermesViewPalette(this) }

    private data class AutomationChoice(val id: String, val label: String) {
        override fun toString(): String = if (label.isBlank() || label == id) id else "$label ($id)"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.hermesLocalizedContext())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyHermesViewWindowTheme(palette)

        val existing = HermesTaskerPluginBridge.bundleFromIntent(intent)
        val existingId = existing?.getString(HermesTaskerPluginBridge.KEY_AUTOMATION_ID).orEmpty()
        val existingToken = existing?.getString(HermesTaskerPluginBridge.KEY_TOKEN).orEmpty()
        val choices = HermesAutomationStore(applicationContext)
            .list()
            .sortedWith(compareBy<HermesAutomationRecord> { it.label.lowercase() }.thenBy { it.id })
            .map { AutomationChoice(it.id, it.label) }

        val spinner = Spinner(this).apply {
            adapter = HermesChoiceAdapter(
                this@HermesTaskerPluginEditActivity,
                choices.ifEmpty { listOf(AutomationChoice("", getString(R.string.hermes_tasker_plugin_no_automations))) },
                palette,
            )
            val selectedIndex = choices.indexOfFirst { it.id == existingId }
            if (selectedIndex >= 0) {
                setSelection(selectedIndex)
            }
        }
        val idInput = EditText(this).apply {
            hint = getString(R.string.hermes_tasker_plugin_manual_id_hint)
            setSingleLine(true)
            setText(existingId)
        }
        val labelInput = EditText(this).apply {
            hint = getString(R.string.hermes_tasker_plugin_label_hint)
            setSingleLine(true)
            setText(existing?.getString(HermesTaskerPluginBridge.KEY_LABEL).orEmpty())
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val panelPadding = hermesDp(18f)
            setPadding(panelPadding, panelPadding, panelPadding, panelPadding)
            background = hermesViewPanelDrawable(this@HermesTaskerPluginEditActivity, palette, elevated = true)
            addView(TextView(this@HermesTaskerPluginEditActivity).apply {
                text = getString(R.string.hermes_tasker_plugin_title)
                textSize = 22f
            })
            addView(TextView(this@HermesTaskerPluginEditActivity).apply {
                text = getString(R.string.hermes_tasker_plugin_summary)
                textSize = 15f
                setPadding(0, hermesDp(12f), 0, hermesDp(20f))
            })
            addView(TextView(this@HermesTaskerPluginEditActivity).apply {
                text = getString(R.string.hermes_tasker_plugin_existing_automation)
            })
            addView(spinner, fullWidthParams())
            addView(TextView(this@HermesTaskerPluginEditActivity).apply {
                text = getString(R.string.hermes_tasker_plugin_manual_id)
                setPadding(0, hermesDp(20f), 0, 0)
            })
            addView(idInput, fullWidthParams())
            addView(TextView(this@HermesTaskerPluginEditActivity).apply {
                text = getString(R.string.hermes_tasker_plugin_blurb_label)
                setPadding(0, hermesDp(20f), 0, 0)
            })
            addView(labelInput, fullWidthParams())
            addView(Button(this@HermesTaskerPluginEditActivity).apply {
                text = getString(R.string.hermes_tasker_plugin_save)
                setOnClickListener {
                    val selected = spinner.selectedItem as? AutomationChoice
                    val automationId = idInput.text.toString().trim().ifBlank { selected?.id.orEmpty() }
                    if (automationId.isBlank()) {
                        Toast.makeText(
                            this@HermesTaskerPluginEditActivity,
                            R.string.hermes_tasker_plugin_missing_id,
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@setOnClickListener
                    }
                    val label = labelInput.text.toString().trim().ifBlank { selected?.label.orEmpty() }
                    val result = HermesTaskerPluginBridge.buildResultIntent(
                        this@HermesTaskerPluginEditActivity,
                        automationId,
                        label,
                        existingToken,
                    )
                    setResult(RESULT_OK, result)
                    finish()
                }
            }, fullWidthParams())
        }

        applyHermesViewTree(root, palette)
        setContentView(hermesScrollablePage(root, palette))
    }

    private fun fullWidthParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = hermesDp(6f) }
    }
}
