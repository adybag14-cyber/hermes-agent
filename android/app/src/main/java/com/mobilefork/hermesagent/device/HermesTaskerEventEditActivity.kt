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

class HermesTaskerEventEditActivity : Activity() {
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

        val existing = HermesTaskerEventBridge.bundleFromIntent(intent)
        val existingType = existing?.getString(HermesTaskerEventBridge.KEY_EVENT_TYPE).orEmpty()
        val existingAutomationId = existing?.getString(HermesTaskerEventBridge.KEY_AUTOMATION_ID).orEmpty()
        val existingToken = existing?.getString(HermesTaskerEventBridge.KEY_TOKEN).orEmpty()
        val eventChoices = HermesTaskerEventBridge.eventChoices(this)
        val automationChoices = HermesAutomationStore(applicationContext)
            .list()
            .sortedWith(compareBy<HermesAutomationRecord> { it.label.lowercase() }.thenBy { it.id })
            .map { AutomationChoice(it.id, it.label) }

        val eventSpinner = Spinner(this).apply {
            adapter = HermesChoiceAdapter(
                this@HermesTaskerEventEditActivity,
                eventChoices,
                palette,
            )
            val selectedIndex = eventChoices.indexOfFirst { it.id == existingType }
            if (selectedIndex >= 0) {
                setSelection(selectedIndex)
            }
        }
        val automationSpinner = Spinner(this).apply {
            adapter = HermesChoiceAdapter(
                this@HermesTaskerEventEditActivity,
                automationChoices.ifEmpty { listOf(AutomationChoice("", getString(R.string.hermes_tasker_plugin_no_automations))) },
                palette,
            )
            val selectedIndex = automationChoices.indexOfFirst { it.id == existingAutomationId }
            if (selectedIndex >= 0) {
                setSelection(selectedIndex)
            }
        }
        val automationIdInput = EditText(this).apply {
            hint = getString(R.string.hermes_tasker_plugin_manual_id_hint)
            setSingleLine(true)
            setText(existingAutomationId)
        }
        val labelInput = EditText(this).apply {
            hint = getString(R.string.hermes_tasker_event_label_hint)
            setSingleLine(true)
            setText(existing?.getString(HermesTaskerEventBridge.KEY_LABEL).orEmpty())
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val panelPadding = hermesDp(18f)
            setPadding(panelPadding, panelPadding, panelPadding, panelPadding)
            background = hermesViewPanelDrawable(this@HermesTaskerEventEditActivity, palette, elevated = true)
            addView(TextView(this@HermesTaskerEventEditActivity).apply {
                text = getString(R.string.hermes_tasker_event_title)
                textSize = 22f
            })
            addView(TextView(this@HermesTaskerEventEditActivity).apply {
                text = getString(R.string.hermes_tasker_event_summary)
                textSize = 15f
                setPadding(0, hermesDp(12f), 0, hermesDp(20f))
            })
            addView(TextView(this@HermesTaskerEventEditActivity).apply {
                text = getString(R.string.hermes_tasker_event_type)
            })
            addView(eventSpinner, fullWidthParams())
            addView(TextView(this@HermesTaskerEventEditActivity).apply {
                text = getString(R.string.hermes_tasker_plugin_existing_automation)
                setPadding(0, hermesDp(20f), 0, 0)
            })
            addView(automationSpinner, fullWidthParams())
            addView(TextView(this@HermesTaskerEventEditActivity).apply {
                text = getString(R.string.hermes_tasker_plugin_manual_id)
                setPadding(0, hermesDp(20f), 0, 0)
            })
            addView(automationIdInput, fullWidthParams())
            addView(TextView(this@HermesTaskerEventEditActivity).apply {
                text = getString(R.string.hermes_tasker_event_blurb_label)
                setPadding(0, hermesDp(20f), 0, 0)
            })
            addView(labelInput, fullWidthParams())
            addView(Button(this@HermesTaskerEventEditActivity).apply {
                text = getString(R.string.hermes_tasker_plugin_save)
                setOnClickListener {
                    val event = eventSpinner.selectedItem as? HermesTaskerEventBridge.EventChoice
                    val selectedAutomation = automationSpinner.selectedItem as? AutomationChoice
                    val automationId = automationIdInput.text.toString().trim().ifBlank { selectedAutomation?.id.orEmpty() }
                    val result = runCatching {
                        HermesTaskerEventBridge.buildResultIntent(
                            context = this@HermesTaskerEventEditActivity,
                            eventType = event?.id.orEmpty(),
                            automationId = automationId,
                            label = labelInput.text.toString(),
                            existingToken = existingToken,
                        )
                    }.getOrElse {
                        Toast.makeText(
                            this@HermesTaskerEventEditActivity,
                            getString(R.string.hermes_tasker_event_invalid),
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@setOnClickListener
                    }
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
