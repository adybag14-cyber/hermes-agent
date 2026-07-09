package com.mobilefork.hermesagent.ui.chat

import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.hermesStringsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalIntelligenceQuickActionsTest {
    @Test
    fun quickActionsExposeDirectDiagnosticsForSignalCards() {
        val actionsById = SIGNAL_INTELLIGENCE_QUICK_ACTIONS.associateBy { it.id }

        assertEquals("signal_awareness_report", actionsById.getValue("signal_overview").diagnosticAction)
        assertEquals("agent_signal_card_deck_report", actionsById.getValue("signal_card_deck").diagnosticAction)
        assertEquals("agent_signal_card_refresh_plan_report", actionsById.getValue("card_refresh_plan").diagnosticAction)
        assertEquals("agent_signal_card_refresh_status_report", actionsById.getValue("card_refresh_status").diagnosticAction)
        assertEquals("agent_signal_session_snapshot_report", actionsById.getValue("signal_session_snapshot").diagnosticAction)
        assertEquals("agent_signal_proof_audit_report", actionsById.getValue("signal_proof_audit").diagnosticAction)
        assertEquals("agent_signal_replay_export_report", actionsById.getValue("signal_replay_export").diagnosticAction)
        assertEquals("agent_signal_replay_freshness_audit_report", actionsById.getValue("signal_replay_freshness").diagnosticAction)
        assertEquals("agent_signal_observation_packet_report", actionsById.getValue("signal_observation_packet").diagnosticAction)
        assertEquals("agent_signal_timeline_report", actionsById.getValue("signal_timeline").diagnosticAction)
        assertEquals("agent_signal_evidence_report", actionsById.getValue("signal_evidence").diagnosticAction)
        assertEquals("agent_signal_workflow_handoff_report", actionsById.getValue("workflow_handoff").diagnosticAction)
        assertEquals("agent_signal_permission_runbook_report", actionsById.getValue("permission_runbook").diagnosticAction)
        assertEquals("agent_environment_report", actionsById.getValue("agent_environment").diagnosticAction)
        assertEquals("agent_observation_report", actionsById.getValue("agent_observation").diagnosticAction)
        assertEquals("agent_card_manifest_report", actionsById.getValue("card_manifest").diagnosticAction)
        assertEquals("agent_card_priority_report", actionsById.getValue("top_cards").diagnosticAction)
        assertEquals("mcp_tool_server_registry_report", actionsById.getValue("mcp_registry").diagnosticAction)
        assertEquals("agent_capability_upgrade_report", actionsById.getValue("upgrade_audit").diagnosticAction)
        assertEquals("agent_objective_coverage_report", actionsById.getValue("objective_coverage").diagnosticAction)
        assertEquals("agent_release_validation_report", actionsById.getValue("release_validation").diagnosticAction)
        assertEquals("soc_compatibility_report", actionsById.getValue("soc_compatibility").diagnosticAction)
        assertEquals("mediatek_readiness_report", actionsById.getValue("mediatek_readiness").diagnosticAction)
        assertEquals("mediatek_signal_stack_report", actionsById.getValue("mediatek_signal_stack").diagnosticAction)
        assertEquals("mediatek_device_validation_report", actionsById.getValue("mediatek_device_validation").diagnosticAction)
        assertEquals("device_validation_evidence_export_report", actionsById.getValue("device_evidence_export").diagnosticAction)
        assertEquals("accelerator_preflight_report", actionsById.getValue("accelerator_preflight").diagnosticAction)
        assertEquals("non_adreno_backend_advisor_report", actionsById.getValue("non_adreno_backend_advisor").diagnosticAction)
        assertEquals("mediatek_backend_launch_checklist_report", actionsById.getValue("mediatek_launch_checklist").diagnosticAction)
        assertEquals("gpu_backend_risk_report", actionsById.getValue("backend_risk").diagnosticAction)
        assertEquals("local_inference_compatibility_report", actionsById.getValue("inference_compatibility").diagnosticAction)
        assertEquals("local_backend_runtime_report", actionsById.getValue("runtime_backend").diagnosticAction)
        assertEquals("wifi_analyzer_report", actionsById.getValue("wifi_analyzer").diagnosticAction)
        assertEquals("wifi_signal_advisor_report", actionsById.getValue("wifi_advisor").diagnosticAction)
        assertEquals("wifi_channel_decision_packet_report", actionsById.getValue("wifi_channel_decision").diagnosticAction)
        assertEquals("wifi_connection_link", actionsById.getValue("wifi_link").diagnosticAction)
        assertEquals("wifi_scan", actionsById.getValue("wifi_nearby").diagnosticAction)
        assertEquals("wifi_channel_utilization", actionsById.getValue("wifi_occupancy").diagnosticAction)
        assertEquals("bluetooth_analyzer_report", actionsById.getValue("bluetooth_analyzer").diagnosticAction)
        assertEquals("bluetooth_signal_advisor_report", actionsById.getValue("bluetooth_advisor").diagnosticAction)
        assertEquals("bluetooth_nearby_decision_packet_report", actionsById.getValue("bluetooth_decision").diagnosticAction)
        assertEquals("bluetooth_signal_history", actionsById.getValue("bluetooth_history").diagnosticAction)
        assertEquals("bluetooth_device_details", actionsById.getValue("bluetooth_details").diagnosticAction)
        assertEquals("sensor_analyzer_report", actionsById.getValue("sensor_analyzer").diagnosticAction)
        assertEquals("sensor_workflow_advisor_report", actionsById.getValue("sensor_advisor").diagnosticAction)
        assertEquals("motion_sensor_decision_packet_report", actionsById.getValue("motion_decision").diagnosticAction)
        assertEquals("motion_sensor_history", actionsById.getValue("motion_history").diagnosticAction)
        assertEquals("motion_sensor_quality", actionsById.getValue("motion_quality").diagnosticAction)
        assertEquals("radio_signal_graph", actionsById.getValue("radio_limits").diagnosticAction)
        assertEquals("radio_signal_advisor_report", actionsById.getValue("radio_advisor").diagnosticAction)
        assertEquals("radio_signal_decision_packet_report", actionsById.getValue("radio_decision").diagnosticAction)
        assertEquals("Runtime Backend", actionsById.getValue("runtime_backend").label)
        assertEquals("Backend Risk", actionsById.getValue("backend_risk").label)
        assertEquals("MediaTek Readiness", actionsById.getValue("mediatek_readiness").label)
        assertEquals("MTK Signals", actionsById.getValue("mediatek_signal_stack").label)
        assertEquals("Device Proof", actionsById.getValue("mediatek_device_validation").label)
        assertEquals("Proof Export", actionsById.getValue("device_evidence_export").label)
        assertEquals("Accel Preflight", actionsById.getValue("accelerator_preflight").label)
        assertEquals("Backend Advisor", actionsById.getValue("non_adreno_backend_advisor").label)
        assertEquals("MTK Launch", actionsById.getValue("mediatek_launch_checklist").label)
        assertEquals("Inference Fit", actionsById.getValue("inference_compatibility").label)
        assertEquals("Wi-Fi Decision", actionsById.getValue("wifi_channel_decision").label)
        assertEquals("Evidence Bundle", actionsById.getValue("signal_evidence").label)
        assertEquals("Card Deck", actionsById.getValue("signal_card_deck").label)
        assertEquals("Refresh Plan", actionsById.getValue("card_refresh_plan").label)
        assertEquals("Refresh Status", actionsById.getValue("card_refresh_status").label)
        assertEquals("Session Snapshot", actionsById.getValue("signal_session_snapshot").label)
        assertEquals("Proof Audit", actionsById.getValue("signal_proof_audit").label)
        assertEquals("Replay Export", actionsById.getValue("signal_replay_export").label)
        assertEquals("Replay Freshness", actionsById.getValue("signal_replay_freshness").label)
        assertEquals("Sight Packet", actionsById.getValue("signal_observation_packet").label)
        assertEquals("Signal Timeline", actionsById.getValue("signal_timeline").label)
        assertEquals("Workflow Handoff", actionsById.getValue("workflow_handoff").label)
        assertEquals("Permission Runbook", actionsById.getValue("permission_runbook").label)
        assertEquals("Agent Observation", actionsById.getValue("agent_observation").label)
        assertEquals("Card Manifest", actionsById.getValue("card_manifest").label)
        assertEquals("Top Cards", actionsById.getValue("top_cards").label)
        assertEquals("MCP Registry", actionsById.getValue("mcp_registry").label)
        assertEquals("Upgrade Audit", actionsById.getValue("upgrade_audit").label)
        assertEquals("Objective Coverage", actionsById.getValue("objective_coverage").label)
        assertEquals("Release Validation", actionsById.getValue("release_validation").label)
        assertEquals("Wi-Fi Advisor", actionsById.getValue("wifi_advisor").label)
        assertEquals("Wi-Fi Link", actionsById.getValue("wifi_link").label)
        assertEquals("Bluetooth Advisor", actionsById.getValue("bluetooth_advisor").label)
        assertEquals("BT Decision", actionsById.getValue("bluetooth_decision").label)
        assertEquals("Bluetooth Details", actionsById.getValue("bluetooth_details").label)
        assertEquals("Sensor Advisor", actionsById.getValue("sensor_advisor").label)
        assertEquals("Motion Decision", actionsById.getValue("motion_decision").label)
        assertEquals("Radio Signals", actionsById.getValue("radio_limits").label)
        assertEquals("Radio Advisor", actionsById.getValue("radio_advisor").label)
        assertEquals("Radio Decision", actionsById.getValue("radio_decision").label)
        SIGNAL_INTELLIGENCE_QUICK_ACTIONS.forEach { action ->
            assertTrue(action.prompt.contains("android_device_diagnostics_tool action=${action.diagnosticAction}"))
            val parsed = requireNotNull(NativeToolCallingChatClient.extractExplicitAndroidDiagnosticsArguments(action.prompt))
            assertEquals(action.diagnosticAction, parsed.getString("action"))
            if ("refresh=false" in action.prompt) {
                assertFalse(parsed.getBoolean("refresh"))
            }
        }
    }

    @Test
    fun signalQuickActionLabelsLocalizeForChineseUi() {
        val strings = hermesStringsFor(AppLanguage.CHINESE)

        assertEquals("信号概览", strings.signalQuickActionLabel("signal_overview", "Signal Overview"))
        assertEquals("信号简报", strings.signalQuickActionLabel("signal_briefing", "Signal Briefing"))
        assertEquals("会话快照", strings.signalQuickActionLabel("signal_session_snapshot", "Session Snapshot"))
        assertEquals("证据审计", strings.signalQuickActionLabel("signal_proof_audit", "Proof Audit"))
        assertEquals("回放导出", strings.signalQuickActionLabel("signal_replay_export", "Replay Export"))
        assertEquals("回放新鲜度", strings.signalQuickActionLabel("signal_replay_freshness", "Replay Freshness"))
    }

    @Test
    fun screenshotQuickActionLabelsLocalizeForAllLanguages() {
        val expectedByLanguage = mapOf(
            AppLanguage.CHINESE to mapOf(
                "sensor_advisor" to "传感器建议",
                "motion_decision" to "运动决策",
                "motion_history" to "运动趋势",
                "motion_quality" to "运动质量",
                "radio_limits" to "无线电信号",
                "radio_advisor" to "无线电建议",
                "radio_decision" to "无线电决策",
            ),
            AppLanguage.SPANISH to mapOf(
                "sensor_advisor" to "Consejo sensor",
                "motion_decision" to "Decisión mov.",
                "motion_history" to "Tendencias mov.",
                "motion_quality" to "Calidad mov.",
                "radio_limits" to "Señales radio",
                "radio_advisor" to "Consejo radio",
                "radio_decision" to "Decisión radio",
            ),
            AppLanguage.GERMAN to mapOf(
                "sensor_advisor" to "Sensor-Rat",
                "motion_decision" to "Bewegungsentscheid",
                "motion_history" to "Bewegungstrends",
                "motion_quality" to "Bewegungsqualität",
                "radio_limits" to "Funksignale",
                "radio_advisor" to "Funk-Rat",
                "radio_decision" to "Funk-Entscheid",
            ),
            AppLanguage.PORTUGUESE to mapOf(
                "sensor_advisor" to "Conselho sensor",
                "motion_decision" to "Decisão mov.",
                "motion_history" to "Tendências mov.",
                "motion_quality" to "Qualidade mov.",
                "radio_limits" to "Sinais rádio",
                "radio_advisor" to "Conselho rádio",
                "radio_decision" to "Decisão rádio",
            ),
            AppLanguage.FRENCH to mapOf(
                "sensor_advisor" to "Conseil capteur",
                "motion_decision" to "Décision mouvement",
                "motion_history" to "Tendances mouv.",
                "motion_quality" to "Qualité mouv.",
                "radio_limits" to "Signaux radio",
                "radio_advisor" to "Conseil radio",
                "radio_decision" to "Décision radio",
            ),
            AppLanguage.ENGLISH to mapOf(
                "sensor_advisor" to "Sensor Advisor",
                "motion_decision" to "Motion Decision",
                "motion_history" to "Motion Trends",
                "motion_quality" to "Motion Quality",
                "radio_limits" to "Radio Signals",
                "radio_advisor" to "Radio Advisor",
                "radio_decision" to "Radio Decision",
            ),
        )

        expectedByLanguage.forEach { (language, labelsById) ->
            val strings = hermesStringsFor(language)
            labelsById.forEach { (id, expected) ->
                val fallback = SIGNAL_INTELLIGENCE_QUICK_ACTIONS.first { it.id == id }.label
                assertEquals(expected, strings.signalQuickActionLabel(id, fallback))
            }
        }
    }

    @Test
    fun allQuickActionLabelsLocalizeForEverySupportedLanguage() {
        val nonEnglishLanguages = AppLanguage.entries.filterNot { it == AppLanguage.ENGLISH }

        AppLanguage.entries.forEach { language ->
            val strings = hermesStringsFor(language)
            SIGNAL_INTELLIGENCE_QUICK_ACTIONS.forEach { action ->
                val localized = strings.signalQuickActionLabel(action.id, action.label)
                assertFalse("$language missing label for ${action.id}", localized.isBlank())
                if (language == AppLanguage.ENGLISH) {
                    assertEquals(action.label, localized)
                } else {
                    assertNotEquals(
                        "$language should localize ${action.id}",
                        action.label,
                        localized,
                    )
                }
            }
        }

        nonEnglishLanguages.forEach { language ->
            val strings = hermesStringsFor(language)
            val localizedOverview = strings.signalQuickActionLabel("signal_overview", "Signal Overview")
            val localizedSensorAdvisor = strings.signalQuickActionLabel("sensor_advisor", "Sensor Advisor")
            assertNotEquals("Signal Overview", localizedOverview)
            assertNotEquals("Sensor Advisor", localizedSensorAdvisor)
        }
    }

    @Test
    fun quickActionIdsAreUniqueWithStableDiagnosticRouting() {
        val ids = SIGNAL_INTELLIGENCE_QUICK_ACTIONS.map { it.id }
        val diagnosticActions = SIGNAL_INTELLIGENCE_QUICK_ACTIONS.map { it.diagnosticAction }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals(diagnosticActions.size, diagnosticActions.toSet().size)
        assertTrue(ids.size >= 55)
    }
}
