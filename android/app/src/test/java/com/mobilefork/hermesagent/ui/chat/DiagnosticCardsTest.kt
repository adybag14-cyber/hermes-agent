package com.mobilefork.hermesagent.ui.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticCardsTest {
    @Test
    fun activityPreviewKeepsCollapsedRowsCompactButShowsAllCardsWhenExpanded() {
        val cards = (1..6).map { index ->
            DiagnosticCardSummary(
                title = "Signal card $index",
                body = "Agent-visible signal dashboard card $index",
            )
        }

        val collapsed = diagnosticCardsForActivityPreview(cards, expanded = false)
        val expanded = diagnosticCardsForActivityPreview(cards, expanded = true)

        assertEquals(COLLAPSED_ACTIVITY_DIAGNOSTIC_CARD_LIMIT, collapsed.size)
        assertEquals(listOf("Signal card 1", "Signal card 2", "Signal card 3"), collapsed.map { it.title })
        assertEquals(cards, expanded)
        assertEquals(3, hiddenDiagnosticCardCountForActivityPreview(cards, expanded = false))
        assertEquals(0, hiddenDiagnosticCardCountForActivityPreview(cards, expanded = true))
    }

    @Test
    fun activityPreviewPromotesGraphableSignalCardsWhenCollapsed() {
        val cards = listOf(
            DiagnosticCardSummary(
                title = "Tool Catalog",
                body = "Generic tool inventory.",
                graphType = "agent_card_manifest",
            ),
            DiagnosticCardSummary(
                title = "Workflow Routes",
                body = "Route matrix.",
                graphType = "signal_workflow_routes",
            ),
            DiagnosticCardSummary(
                title = "SOC Compatibility",
                body = "SOC backend rows.",
                graphType = "soc_backend_matrix",
            ),
            DiagnosticCardSummary(
                title = "Wi-Fi Channel Graph",
                body = "Wi-Fi channel envelopes.",
                graphType = "wifi_channel_graph",
            ),
            DiagnosticCardSummary(
                title = "Bluetooth Nearby",
                body = "Nearby Bluetooth RSSI.",
                graphType = "bluetooth_rssi",
            ),
            DiagnosticCardSummary(
                title = "AM/FM Signal Graph",
                body = "Radio samples.",
                graphType = "radio_signal_graph",
            ),
        )

        val collapsed = diagnosticCardsForActivityPreview(cards, expanded = false)

        assertEquals(
            listOf("Wi-Fi Channel Graph", "Bluetooth Nearby", "AM/FM Signal Graph"),
            collapsed.map { it.title },
        )
        assertEquals(cards, diagnosticCardsForActivityPreview(cards, expanded = true))
        assertEquals(1, diagnosticCardPreviewPriority(cards[3]))
        assertEquals(2, diagnosticCardPreviewPriority(cards[4]))
        assertEquals(3, diagnosticCardPreviewPriority(cards[5]))
        assertTrue(diagnosticCardPreviewPriority(cards[0]) > diagnosticCardPreviewPriority(cards[3]))
    }

    @Test
    fun parsesAgentSignalBriefingRowsAsTopPriorityCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Agent Signal Briefing")
                            .put("body", "What Gemma can view.")
                            .put("graph_type", "agent_signal_briefing_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_briefing")
                                        .put("label", "Wi-Fi graph evidence")
                                        .put("ready", true)
                                        .put("value_label", "3 AP row(s)")
                                        .put("detail", "Gemma can inspect Wi-Fi Analyzer rows.")
                                        .put("recommendation", "Open wifi_channel_graph.")
                                        .put("fraction", 0.94),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Top Card Slots")
                            .put("body", "Open-next card slots.")
                            .put("graph_type", "agent_top_card_slots")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_top_card_slot")
                                        .put("label", "Slot 1: Wi-Fi channel and AP graph cards")
                                        .put("ready", true)
                                        .put("value_label", "Wi-Fi Analyzer")
                                        .put("detail", "open_next_action=wifi_channel_graph")
                                        .put("recommendation", "Open the Wi-Fi graph.")
                                        .put("fraction", 0.96),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals(2, cards.size)
        assertEquals("agent_signal_briefing_matrix", cards[0].graphType)
        assertEquals("Wi-Fi graph evidence", cards[0].rows.single().label)
        assertEquals("3 AP row(s)", cards[0].rows.single().valueLabel)
        assertTrue(cards[0].rows.single().detail.contains("agent signal briefing"))
        assertEquals("agent_top_card_slots", cards[1].graphType)
        assertEquals("Slot 1: Wi-Fi channel and AP graph cards", cards[1].rows.single().label)
        assertEquals(0, diagnosticCardPreviewPriority(cards[0]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[1]))
    }

    @Test
    fun parsesExpandedSignalCardDeckManifestAsTopPriorityCard() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Expanded Signal Card Deck")
                        .put("body", "Preloaded signal cards.")
                        .put("graph_type", "agent_signal_card_deck_manifest")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "agent_signal_card_deck")
                                    .put("label", "Wi-Fi Channel Graph")
                                    .put("ready", true)
                                    .put("value_label", "Wi-Fi Analyzer: 4 row(s)")
                                    .put("detail", "top_card_slot=1; graph_type=wifi_channel_graph; open_next_action=wifi_channel_graph")
                                    .put("recommendation", "Expand Wi-Fi Channel Graph.")
                                    .put("fraction", 0.94),
                            ),
                        ),
                ),
            )
            .toString()

        val card = extractDiagnosticCards(content).single()
        val row = card.rows.single()

        assertEquals("agent_signal_card_deck_manifest", card.graphType)
        assertEquals("Wi-Fi Channel Graph", row.label)
        assertEquals("Wi-Fi Analyzer: 4 row(s)", row.valueLabel)
        assertTrue(row.detail.contains("agent signal card deck"))
        assertTrue(row.detail.contains("wifi_channel_graph"))
        assertEquals(0, diagnosticCardPreviewPriority(card))
    }

    @Test
    fun parsesMediatekDeviceValidationRowsAsTopPriorityCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "MediaTek Device Validation")
                            .put("body", "Phone proof rows.")
                            .put("graph_type", "mediatek_device_validation_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "mediatek_device_validation")
                                        .put("label", "Physical MediaTek/non-Adreno identity")
                                        .put("ready", false)
                                        .put("value_label", "MediaTek / Mali")
                                        .put("detail", "physical_device_validation_required=true; claim_scope=SOC family identity only")
                                        .put("recommendation", "Validate on physical hardware.")
                                        .put("fraction", 0.72),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Live Signal Validation Routes")
                            .put("body", "Route rows.")
                            .put("graph_type", "live_signal_validation_routes")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "mediatek_device_validation_route")
                                        .put("label", "Run live Wi-Fi proof")
                                        .put("ready", true)
                                        .put("value_label", "wifi_scan")
                                        .put("detail", "permission_gate=nearby_wifi_or_location_permission")
                                        .put("recommendation", "Use active refresh only on request.")
                                        .put("fraction", 0.88),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Release Device Proof Gates")
                            .put("body", "Release gates.")
                            .put("graph_type", "release_device_proof_gates")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "mediatek_device_validation_route")
                                        .put("label", "Signed APK/AAB checksum gate")
                                        .put("ready", false)
                                        .put("value_label", "checksum required")
                                        .put("detail", "release_validation_required=true")
                                        .put("recommendation", "Verify release assets.")
                                        .put("fraction", 0.38),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals(3, cards.size)
        assertEquals("mediatek_device_validation_matrix", cards[0].graphType)
        assertEquals("Physical MediaTek/non-Adreno identity", cards[0].rows.single().label)
        assertTrue(cards[0].rows.single().detail.contains("physical_device_validation_required"))
        assertEquals(0, diagnosticCardPreviewPriority(cards[0]))
        assertEquals("live_signal_validation_routes", cards[1].graphType)
        assertEquals("Run live Wi-Fi proof", cards[1].rows.single().label)
        assertEquals(0, diagnosticCardPreviewPriority(cards[1]))
        assertEquals("release_device_proof_gates", cards[2].graphType)
        assertEquals("Signed APK/AAB checksum gate", cards[2].rows.single().label)
        assertEquals(0, diagnosticCardPreviewPriority(cards[2]))
    }

    @Test
    fun parsesDeviceValidationEvidenceExportRowsAsTopPriorityCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Required Device Artifacts")
                            .put("body", "Phone evidence artifacts.")
                            .put("graph_type", "device_validation_required_artifacts")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "device_validation_required_artifact")
                                        .put("label", "LiteRT /health backend proof")
                                        .put("ready", false)
                                        .put("value_label", "backend /health required")
                                        .put("detail", "physical_device_validation_required=true; capture_command=adb reverse tcp:8765 tcp:8765")
                                        .put("recommendation", "Capture phone backend health.")
                                        .put("fraction", 0.36),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Phone Validation Routes")
                            .put("body", "ADB routes.")
                            .put("graph_type", "phone_validation_command_routes")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "phone_validation_command_route")
                                        .put("label", "Capture phone identity and installed package")
                                        .put("ready", true)
                                        .put("value_label", "ADB/operator route")
                                        .put("detail", "capture_command=adb shell getprop")
                                        .put("recommendation", "Attach output.")
                                        .put("fraction", 0.9),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "GitHub Release Evidence")
                            .put("body", "Release routes.")
                            .put("graph_type", "github_release_evidence_routes")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "github_release_evidence_route")
                                        .put("label", "Signed APK/AAB asset and checksum route")
                                        .put("ready", false)
                                        .put("value_label", "checksum assets required")
                                        .put("detail", "release_validation_required=true")
                                        .put("recommendation", "Verify GitHub assets.")
                                        .put("fraction", 0.35),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals(3, cards.size)
        assertEquals("device_validation_required_artifacts", cards[0].graphType)
        assertEquals("LiteRT /health backend proof", cards[0].rows.single().label)
        assertTrue(cards[0].rows.single().detail.contains("device validation required artifact"))
        assertEquals("phone_validation_command_routes", cards[1].graphType)
        assertEquals("Capture phone identity and installed package", cards[1].rows.single().label)
        assertTrue(cards[1].rows.single().detail.contains("adb shell getprop"))
        assertEquals("github_release_evidence_routes", cards[2].graphType)
        assertEquals("Signed APK/AAB asset and checksum route", cards[2].rows.single().label)
        assertEquals(0, diagnosticCardPreviewPriority(cards[0]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[1]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[2]))
    }

    @Test
    fun parsesSignalCardRefreshPlanAsTopPriorityCard() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Signal Card Refresh Plan")
                        .put("body", "Per-card refresh routes.")
                        .put("graph_type", "agent_signal_card_refresh_plan_matrix")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "agent_signal_card_refresh_plan")
                                    .put("label", "Wi-Fi Channel Graph")
                                    .put("ready", true)
                                    .put("value_label", "wifi_scan")
                                    .put("detail", "passive_fallback_action=wifi_analyzer_report; active_refresh_arguments={refresh:true}")
                                    .put("recommendation", "Refresh only when current AP rows are needed.")
                                    .put("fraction", 0.9),
                            ),
                        ),
                ),
            )
            .toString()

        val card = extractDiagnosticCards(content).single()
        val row = card.rows.single()

        assertEquals("agent_signal_card_refresh_plan_matrix", card.graphType)
        assertEquals("Wi-Fi Channel Graph", row.label)
        assertEquals("wifi_scan", row.valueLabel)
        assertTrue(row.detail.contains("agent signal card refresh plan"))
        assertTrue(row.detail.contains("active_refresh_arguments"))
        assertEquals(0, diagnosticCardPreviewPriority(card))
    }

    @Test
    fun parsesSignalCardRefreshStatusAsTopPriorityCard() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Signal Card Refresh Status")
                        .put("body", "Per-card refresh status.")
                        .put("graph_type", "agent_signal_card_refresh_status_matrix")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "agent_signal_card_refresh_status")
                                    .put("label", "Wi-Fi Channel Graph")
                                    .put("ready", true)
                                    .put("value_label", "ready_phone_validation")
                                    .put("detail", "status_label=ready_phone_validation; next_best_action=wifi_scan; open_settings_action=open_wifi_settings")
                                    .put("recommendation", "Run wifi_scan on phone with explicit user intent.")
                                    .put("fraction", 0.84),
                            ),
                        ),
                ),
            )
            .toString()

        val card = extractDiagnosticCards(content).single()
        val row = card.rows.single()

        assertEquals("agent_signal_card_refresh_status_matrix", card.graphType)
        assertEquals("Wi-Fi Channel Graph", row.label)
        assertEquals("ready_phone_validation", row.valueLabel)
        assertTrue(row.detail.contains("agent signal card refresh status"))
        assertTrue(row.detail.contains("next_best_action"))
        assertEquals(0, diagnosticCardPreviewPriority(card))
    }

    @Test
    fun parsesSignalSessionSnapshotRowsAsTopPriorityCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Agent Signal Session Snapshot")
                            .put("body", "Current fused signal posture.")
                            .put("graph_type", "agent_signal_session_snapshot_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_session_snapshot")
                                        .put("label", "Session signal posture")
                                        .put("ready", true)
                                        .put("value_label", "5/7 domains ready")
                                        .put("detail", "Wi-Fi, Bluetooth, sensor, radio, RF, cards, backend")
                                        .put("recommendation", "Read this before opening domain cards.")
                                        .put("fraction", 0.88),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Session Domain Coverage")
                            .put("body", "Per-domain readiness.")
                            .put("graph_type", "agent_signal_session_domain_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_session_domain")
                                        .put("label", "Wi-Fi graph domain")
                                        .put("ready", true)
                                        .put("value_label", "ready")
                                        .put("detail", "wifi_analyzer_report -> wifi_channel_graph")
                                        .put("recommendation", "Open Wi-Fi graph cards first.")
                                        .put("fraction", 0.91),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals(2, cards.size)
        assertEquals("agent_signal_session_snapshot_matrix", cards[0].graphType)
        assertEquals("Session signal posture", cards[0].rows.single().label)
        assertEquals("5/7 domains ready", cards[0].rows.single().valueLabel)
        assertTrue(cards[0].rows.single().detail.contains("agent signal session snapshot"))
        assertEquals("agent_signal_session_domain_matrix", cards[1].graphType)
        assertEquals("Wi-Fi graph domain", cards[1].rows.single().label)
        assertTrue(cards[1].rows.single().detail.contains("agent signal session domain"))
        assertEquals(0, diagnosticCardPreviewPriority(cards[0]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[1]))
    }

    @Test
    fun parsesSignalProofAuditRowsAsTopPriorityCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Signal Proof Audit")
                            .put("body", "Active and passive proof boundaries.")
                            .put("graph_type", "agent_signal_proof_audit_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_proof_audit")
                                        .put("label", "Wi-Fi active proof")
                                        .put("ready", false)
                                        .put("value_label", "passive evidence only")
                                        .put("detail", "proof_status=passive_evidence_only; claim_scope=passive analyzer metadata")
                                        .put("recommendation", "Use passive_fallback_action before claiming live evidence.")
                                        .put("fraction", 0.64),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Signal Claim Boundaries")
                            .put("body", "Claim limits.")
                            .put("graph_type", "agent_signal_claim_boundary_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_claim_boundary")
                                        .put("label", "Radio bridge boundary")
                                        .put("ready", false)
                                        .put("value_label", "1 bridge-required row")
                                        .put("detail", "claim_boundary=radio_bridge; proof_status=bridge_required")
                                        .put("recommendation", "Supply SDR bridge samples first.")
                                        .put("fraction", 0.42),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals(2, cards.size)
        assertEquals("agent_signal_proof_audit_matrix", cards[0].graphType)
        assertEquals("Wi-Fi active proof", cards[0].rows.single().label)
        assertEquals("passive evidence only", cards[0].rows.single().valueLabel)
        assertTrue(cards[0].rows.single().detail.contains("agent signal proof audit"))
        assertTrue(cards[0].rows.single().detail.contains("proof_status"))
        assertEquals("agent_signal_claim_boundary_matrix", cards[1].graphType)
        assertEquals("Radio bridge boundary", cards[1].rows.single().label)
        assertTrue(cards[1].rows.single().detail.contains("agent signal claim boundary"))
        assertEquals(0, diagnosticCardPreviewPriority(cards[0]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[1]))
    }

    @Test
    fun parsesSignalReplayExportRowsAsTopPriorityCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Signal Replay Export")
                            .put("body", "Portable replay manifest.")
                            .put("graph_type", "agent_signal_replay_export_manifest")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_replay_export")
                                        .put("label", "Evidence bundle frame")
                                        .put("ready", true)
                                        .put("value_label", "9 evidence row(s)")
                                        .put("detail", "source_action=agent_signal_evidence_report; graph_type=signal_evidence_matrix; proof_status=passive_evidence_present")
                                        .put("recommendation", "Replay without claiming live evidence.")
                                        .put("fraction", 0.92),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Replay Frame Index")
                            .put("body", "Frame order.")
                            .put("graph_type", "agent_signal_replay_frame_index")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_replay_frame")
                                        .put("label", "Proof audit replay frame")
                                        .put("ready", true)
                                        .put("value_label", "8 row(s)")
                                        .put("detail", "frame_key=proof_audit; claim_scope=claim-safe proof ledger; proof_status=proof_status_required")
                                        .put("recommendation", "Read proof audit before replay.")
                                        .put("fraction", 0.9),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Replay Metadata Keys")
                            .put("body", "Preserved keys.")
                            .put("graph_type", "agent_signal_replay_metadata_keys")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_replay_metadata_key")
                                        .put("label", "Proof boundary keys")
                                        .put("ready", true)
                                        .put("value_label", "8 key(s)")
                                        .put("detail", "metadata_key_group=proof_boundaries; metadata_keys=claim_scope,proof_status")
                                        .put("recommendation", "Preserve these keys when compacting.")
                                        .put("fraction", 0.9),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals(3, cards.size)
        assertEquals("agent_signal_replay_export_manifest", cards[0].graphType)
        assertEquals("Evidence bundle frame", cards[0].rows.single().label)
        assertTrue(cards[0].rows.single().detail.contains("agent signal replay export"))
        assertTrue(cards[0].rows.single().detail.contains("proof_status"))
        assertEquals("agent_signal_replay_frame_index", cards[1].graphType)
        assertEquals("Proof audit replay frame", cards[1].rows.single().label)
        assertTrue(cards[1].rows.single().detail.contains("agent signal replay frame"))
        assertEquals("agent_signal_replay_metadata_keys", cards[2].graphType)
        assertEquals("Proof boundary keys", cards[2].rows.single().label)
        assertTrue(cards[2].rows.single().detail.contains("agent signal replay metadata key"))
        assertEquals(0, diagnosticCardPreviewPriority(cards[0]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[1]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[2]))
    }

    @Test
    fun parsesSignalReplayFreshnessRowsAsTopPriorityCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Replay Freshness Audit")
                            .put("body", "Freshness matrix.")
                            .put("graph_type", "agent_signal_replay_freshness_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_replay_freshness")
                                        .put("label", "Evidence bundle replay frame")
                                        .put("ready", true)
                                        .put("value_label", "passive_cached, medium risk")
                                        .put("detail", "freshness_status=passive_cached; staleness_risk=medium; active_refresh_action=agent_signal_evidence_report; proof_status=passive_replay_frame")
                                        .put("recommendation", "Refresh before live claims.")
                                        .put("fraction", 0.64),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Replay Refresh Routes")
                            .put("body", "Route rows.")
                            .put("graph_type", "agent_signal_replay_refresh_routes")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_replay_refresh_route")
                                        .put("label", "Refresh Evidence bundle replay frame")
                                        .put("ready", true)
                                        .put("value_label", "agent_signal_evidence_report")
                                        .put("detail", "route_type=active_refresh; freshness_status=active_refresh_ready")
                                        .put("recommendation", "Run the active refresh only when needed.")
                                        .put("fraction", 0.9),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Replay Staleness Summary")
                            .put("body", "Summary rows.")
                            .put("graph_type", "agent_signal_replay_staleness_summary")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_replay_staleness_summary")
                                        .put("label", "Blocked live-claim frames")
                                        .put("ready", false)
                                        .put("value_label", "1 blocked frame(s)")
                                        .put("detail", "summary_key=blocked_live_claim_replay_frames")
                                        .put("recommendation", "Keep behind claim boundaries.")
                                        .put("fraction", 0.35),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals(3, cards.size)
        assertEquals("agent_signal_replay_freshness_matrix", cards[0].graphType)
        assertEquals("Evidence bundle replay frame", cards[0].rows.single().label)
        assertTrue(cards[0].rows.single().detail.contains("agent signal replay freshness"))
        assertTrue(cards[0].rows.single().detail.contains("freshness_status"))
        assertEquals("agent_signal_replay_refresh_routes", cards[1].graphType)
        assertEquals("Refresh Evidence bundle replay frame", cards[1].rows.single().label)
        assertTrue(cards[1].rows.single().detail.contains("agent signal replay refresh route"))
        assertEquals("agent_signal_replay_staleness_summary", cards[2].graphType)
        assertEquals("Blocked live-claim frames", cards[2].rows.single().label)
        assertTrue(cards[2].rows.single().detail.contains("agent signal replay staleness summary"))
        assertEquals(0, diagnosticCardPreviewPriority(cards[0]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[1]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[2]))
    }

    @Test
    fun parsesMediatekLaunchChecklistAsTopPriorityCard() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "MediaTek Launch Checklist")
                        .put("body", "Launch gates.")
                        .put("graph_type", "mediatek_backend_launch_checklist_matrix")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "mediatek_backend_launch_checklist")
                                    .put("label", "Verify GPU proof or name CPU fallback")
                                    .put("ready", true)
                                    .put("value_label", "cpu fallback")
                                    .put("detail", "launch_step=5; launch_gate_status=ready; next_best_action=local_backend_runtime_report")
                                    .put("recommendation", "Name CPU fallback when GPU is not proven.")
                                    .put("fraction", 0.78),
                            ),
                        ),
                ),
            )
            .toString()

        val card = extractDiagnosticCards(content).single()
        val row = card.rows.single()

        assertEquals("mediatek_backend_launch_checklist_matrix", card.graphType)
        assertEquals("Verify GPU proof or name CPU fallback", row.label)
        assertEquals("cpu fallback", row.valueLabel)
        assertTrue(row.detail.contains("mediatek backend launch checklist"))
        assertTrue(row.detail.contains("launch_gate_status"))
        assertEquals(0, diagnosticCardPreviewPriority(card))
    }

    @Test
    fun parsesObjectiveCoverageRowsAsTopPriorityCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Objective Coverage")
                            .put("body", "Requirement coverage.")
                            .put("graph_type", "agent_objective_coverage_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_objective_coverage")
                                        .put("label", "WiFiAnalyzer graph and metadata parity")
                                        .put("ready", true)
                                        .put("value_label", "implemented surface")
                                        .put("detail", "coverage_status=implemented_surface_available; graph_type=wifi_channel_graph")
                                        .put("recommendation", "Open Wi-Fi graph.")
                                        .put("fraction", 0.9),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Objective Gaps")
                            .put("body", "Proof gaps.")
                            .put("graph_type", "agent_objective_gap_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_objective_gap")
                                        .put("label", "Release and CI proof")
                                        .put("ready", false)
                                        .put("value_label", "release validation required")
                                        .put("detail", "release_validation_required=true")
                                        .put("recommendation", "Validate release workflow.")
                                        .put("fraction", 0.35),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals(2, cards.size)
        assertEquals("agent_objective_coverage_matrix", cards[0].graphType)
        assertEquals("WiFiAnalyzer graph and metadata parity", cards[0].rows.single().label)
        assertTrue(cards[0].rows.single().detail.contains("coverage_status"))
        assertEquals("agent_objective_gap_matrix", cards[1].graphType)
        assertEquals("Release and CI proof", cards[1].rows.single().label)
        assertTrue(cards[1].rows.single().detail.contains("release_validation_required"))
        assertEquals(0, diagnosticCardPreviewPriority(cards[0]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[1]))
    }

    @Test
    fun parsesReleaseValidationRowsAsTopPriorityCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Release Validation")
                            .put("body", "Release proof.")
                            .put("graph_type", "agent_release_validation_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_release_validation")
                                        .put("label", "Signed release workflow gate")
                                        .put("ready", false)
                                        .put("value_label", "android-release.yml proof required")
                                        .put("detail", "workflow_file=.github/workflows/android-release.yml; release_validation_required=true")
                                        .put("recommendation", "Confirm workflow success.")
                                        .put("fraction", 0.36),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Release Artifact Gates")
                            .put("body", "Artifact proof.")
                            .put("graph_type", "agent_release_artifact_gates")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_release_artifact_gate")
                                        .put("label", "SHA-256 checksum assets")
                                        .put("ready", false)
                                        .put("value_label", "checksum proof")
                                        .put("detail", "artifact_globs=dist/android-release/*.sha256")
                                        .put("recommendation", "Compare checksums.")
                                        .put("fraction", 0.34),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals(2, cards.size)
        assertEquals("agent_release_validation_matrix", cards[0].graphType)
        assertEquals("Signed release workflow gate", cards[0].rows.single().label)
        assertTrue(cards[0].rows.single().detail.contains("android-release.yml"))
        assertEquals("agent_release_artifact_gates", cards[1].graphType)
        assertEquals("SHA-256 checksum assets", cards[1].rows.single().label)
        assertTrue(cards[1].rows.single().detail.contains("sha256"))
        assertEquals(0, diagnosticCardPreviewPriority(cards[0]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[1]))
    }

    @Test
    fun parsesAgentSignalTimelineRowsAsTopPriorityCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Agent Signal Timeline")
                            .put("body", "Recent signal observations.")
                            .put("graph_type", "agent_signal_timeline")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_timeline")
                                        .put("label", "Bluetooth proximity and identity view")
                                        .put("ready", true)
                                        .put("value_label", "2 device row(s), 1 trend row(s)")
                                        .put("detail", "open_next_action=bluetooth_signal_history; freshness=cached_rssi_history_available")
                                        .put("recommendation", "Open Bluetooth history.")
                                        .put("fraction", 0.95),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Signal Refresh Routes")
                            .put("body", "Live refresh routes.")
                            .put("graph_type", "agent_signal_refresh_routes")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_refresh_route")
                                        .put("label", "Supply radio bridge samples")
                                        .put("ready", true)
                                        .put("value_label", "radio_signal_graph")
                                        .put("detail", "Refresh argument: radio_samples_json or direct frequency/rssi fields.")
                                        .put("recommendation", "Use radio bridge samples only when available.")
                                        .put("fraction", 0.9),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals(2, cards.size)
        assertEquals("agent_signal_timeline", cards[0].graphType)
        assertEquals("Bluetooth proximity and identity view", cards[0].rows.single().label)
        assertEquals("2 device row(s), 1 trend row(s)", cards[0].rows.single().valueLabel)
        assertTrue(cards[0].rows.single().detail.contains("cached_rssi_history_available"))
        assertEquals("agent_signal_refresh_routes", cards[1].graphType)
        assertEquals("Supply radio bridge samples", cards[1].rows.single().label)
        assertEquals(0, diagnosticCardPreviewPriority(cards[0]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[1]))
    }

    @Test
    fun parsesSignalPermissionRunbookRowsAsTopPriorityCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Signal Permission Runbook")
                            .put("body", "Permission and active-refresh gates.")
                            .put("graph_type", "agent_signal_permission_runbook_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_permission_runbook")
                                        .put("label", "Prepare active Wi-Fi scan")
                                        .put("ready", false)
                                        .put("value_label", "wifi_scan")
                                        .put("detail", "permission_gate=user_consent_and_android_permissions; settings_actions=open_app_settings,open_location_settings; active_refresh_arguments={action:wifi_scan,refresh:true}; passive_fallback_action=wifi_analyzer_report")
                                        .put("recommendation", "Use passive fallback until permissions are complete.")
                                        .put("permission_gate", "user_consent_and_android_permissions")
                                        .put("passive_fallback_action", "wifi_analyzer_report")
                                        .put("fraction", 0.94),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Active Signal Refresh Routes")
                            .put("body", "Active refresh routes.")
                            .put("graph_type", "agent_signal_active_refresh_routes")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_active_refresh_route")
                                        .put("label", "Run active Wi-Fi scan")
                                        .put("ready", true)
                                        .put("value_label", "wifi_scan")
                                        .put("detail", "permission_gate=nearby_wifi_or_location_permission; active_refresh_arguments={action:wifi_scan,refresh:true}")
                                        .put("recommendation", "Run only after consent.")
                                        .put("permission_gate", "nearby_wifi_or_location_permission")
                                        .put("fraction", 0.91),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals(2, cards.size)
        assertEquals("agent_signal_permission_runbook_matrix", cards[0].graphType)
        assertEquals("Prepare active Wi-Fi scan", cards[0].rows.single().label)
        assertEquals("wifi_scan", cards[0].rows.single().valueLabel)
        assertTrue(cards[0].rows.single().detail.contains("user_consent_and_android_permissions"))
        assertTrue(cards[0].rows.single().detail.contains("wifi_analyzer_report"))
        assertEquals("agent_signal_active_refresh_routes", cards[1].graphType)
        assertEquals("Run active Wi-Fi scan", cards[1].rows.single().label)
        assertTrue(cards[1].rows.single().detail.contains("nearby_wifi_or_location_permission"))
        assertEquals(0, diagnosticCardPreviewPriority(cards[0]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[1]))
    }

    @Test
    fun parsesWifiGraphRowsForExpandableSignalCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("type", "signal_graph_card")
                        .put("title", "Wi-Fi Analyzer")
                        .put("body", "Nearby Wi-Fi signals.")
                        .put("graph_type", "wifi_channel_strength")
                        .put("row_count", 1)
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("ssid", "HermesNet")
                                    .put("rssi_dbm", -42)
                                    .put("frequency_mhz", 5180)
                                    .put("channel", 36)
                                    .put("band", "5 GHz")
                                    .put("channel_width", "80MHz")
                                    .put("security_mode", "WPA2")
                                    .put("bssid_vendor", "Apple")
                                    .put("estimated_distance_meters", 1.6),
                            ),
                        ),
                ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals(1, cards.size)
        assertEquals("Wi-Fi Analyzer", cards.single().title)
        assertEquals("wifi_channel_strength", cards.single().graphType)
        assertEquals("HermesNet", cards.single().rows.single().label)
        assertEquals("-42 dBm", cards.single().rows.single().valueLabel)
        assertTrue(cards.single().rows.single().detail.contains("ch 36"))
        assertTrue(cards.single().rows.single().detail.contains("WPA2"))
        assertTrue(cards.single().rows.single().detail.contains("Apple"))
        assertTrue(cards.single().rows.single().fraction > 0.8f)
    }

    @Test
    fun parsesWifiChannelGraphRowsForExpandableSignalCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Wi-Fi Channel Graph")
                        .put("body", "Channel envelopes.")
                        .put("graph_type", "wifi_channel_graph")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("display_ssid", "HermesWide")
                                    .put("ssid", "HermesWide")
                                    .put("rssi_dbm", -38)
                                    .put("frequency_mhz", 5180)
                                    .put("channel", 36)
                                    .put("band", "5GHz")
                                    .put("channel_width", "80MHz")
                                    .put("channel_span_start", 28)
                                    .put("channel_span_end", 44)
                                    .put("overlap_pressure_score", 61)
                                    .put("overlap_network_count", 2)
                                    .put("overlap_sample_ssids", JSONArray().put("HermesNarrow").put("LabAP"))
                                    .put("security_mode", "WPA3")
                                    .put("bssid_vendor", "Apple"),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("HermesWide", row.label)
        assertEquals("-38 dBm", row.valueLabel)
        assertTrue(row.detail.contains("5GHz ch 36"))
        assertTrue(row.detail.contains("span 28-44"))
        assertTrue(row.detail.contains("80MHz"))
        assertTrue(row.detail.contains("61% overlap pressure"))
        assertTrue(row.detail.contains("2 overlaps"))
        assertTrue(row.detail.contains("near HermesNarrow, LabAP"))
        assertTrue(row.fraction > 0.8f)
    }

    @Test
    fun parsesWifiAdvisorRowsForExpandableSignalCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Wi-Fi Advisor")
                            .put("body", "Decision matrix.")
                            .put("graph_type", "wifi_signal_advisor_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "wifi_signal_advisor")
                                        .put("label", "Current link decision")
                                        .put("ready", true)
                                        .put("value_label", "-50 dBm excellent")
                                        .put("detail", "ssid=HermesMesh | band=5GHz | channel=36")
                                        .put("recommendation", "Inspect roaming candidates only when the current link is weak.")
                                        .put("fraction", 0.9),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Wi-Fi Roaming Candidates")
                            .put("body", "Ranked AP rows.")
                            .put("graph_type", "wifi_roaming_candidates")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "wifi_roaming_candidate")
                                        .put("label", "HermesMesh ...EE:02")
                                        .put("ready", true)
                                        .put("value_label", "-48 dBm excellent")
                                        .put("detail", "band=5GHz | channel=36 | width=80MHz | security=WPA3")
                                        .put("recommendation", "Strong same-SSID candidate; compare with current AP before roaming.")
                                        .put("fraction", 0.96),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals(2, cards.size)
        assertEquals("wifi_signal_advisor_matrix", cards[0].graphType)
        assertEquals("Current link decision", cards[0].rows.single().label)
        assertEquals("-50 dBm excellent", cards[0].rows.single().valueLabel)
        assertTrue(cards[0].rows.single().detail.contains("wifi signal advisor"))
        assertTrue(cards[0].rows.single().detail.contains("ssid=HermesMesh"))
        assertEquals("wifi_roaming_candidates", cards[1].graphType)
        assertEquals("HermesMesh ...EE:02", cards[1].rows.single().label)
        assertTrue(cards[1].rows.single().detail.contains("wifi roaming candidate"))
        assertTrue(cards[1].rows.single().detail.contains("same-SSID candidate"))
        assertEquals(1, diagnosticCardPreviewPriority(cards[0]))
        assertEquals(1, diagnosticCardPreviewPriority(cards[1]))
    }

    @Test
    fun parsesBluetoothRowsEvenWhenOnlyPairedMetadataIsAvailable() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Bluetooth Nearby")
                        .put("body", "Paired or scanned devices.")
                        .put("graph_type", "bluetooth_rssi")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("device_name", "Headphones")
                                    .put("device_type", "dual")
                                    .put("device_category", "audio")
                                    .put("bond_state", "bonded")
                                    .put("paired", true)
                                    .put("proximity_label", "near")
                                    .put("service_uuid_count", 2)
                                    .put("manufacturer_data_count", 1),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Headphones", row.label)
        assertEquals("paired", row.valueLabel)
        assertTrue(row.detail.contains("bonded"))
        assertTrue(row.detail.contains("audio"))
        assertTrue(row.detail.contains("2 services"))
        assertTrue(row.fraction >= 0.4f)
    }

    @Test
    fun parsesBluetoothDeviceDetailRowsForExpandableSignalCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Bluetooth Device Details")
                        .put("body", "Per-device Bluetooth metadata.")
                        .put("graph_type", "bluetooth_device_detail")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("display_label", "Hermes Heart")
                                    .put("device_name", "Heart Strap")
                                    .put("advertised_name", "Hermes Heart")
                                    .put("device_type", "le")
                                    .put("device_category", "wearable_health")
                                    .put("bond_state", "bonded")
                                    .put("paired", true)
                                    .put("semantic_label", "health or fitness device")
                                    .put("rssi_dbm", -48)
                                    .put("proximity_label", "near")
                                    .put("estimated_distance_meters", 1.4)
                                    .put("service_labels", JSONArray().put("Heart Rate"))
                                    .put("manufacturer_names", JSONArray().put("Apple"))
                                    .put("metadata_completeness_score", 92)
                                    .put("evidence_summary", "services=Heart Rate | manufacturers=Apple"),
                            ),
                        ),
                ),
            )
            .toString()

        val card = extractDiagnosticCards(content).single()
        val row = card.rows.single()

        assertEquals("bluetooth_device_detail", card.graphType)
        assertEquals("Hermes Heart", row.label)
        assertEquals("-48 dBm", row.valueLabel)
        assertTrue(row.detail.contains("health or fitness device"))
        assertTrue(row.detail.contains("services Heart Rate"))
        assertTrue(row.detail.contains("manufacturers Apple"))
        assertTrue(row.detail.contains("92% metadata"))
        assertEquals(2, diagnosticCardPreviewPriority(card))
    }

    @Test
    fun parsesBluetoothMetadataSummaryRows() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Bluetooth Metadata")
                        .put("body", "Summary rows.")
                        .put("graph_type", "bluetooth_metadata_summary")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("summary_type", "manufacturer_id")
                                    .put("label", "0x004C")
                                    .put("semantic_label", "Apple")
                                    .put("count", 2)
                                    .put("connectable_count", 1)
                                    .put("strongest_rssi_dbm", -50)
                                    .put("recommendation", "Manufacturer data advertised nearby."),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Apple", row.label)
        assertEquals("2 devices", row.valueLabel)
        assertTrue(row.detail.contains("manufacturer id"))
        assertTrue(row.detail.contains("raw 0x004C"))
        assertTrue(row.detail.contains("1 connectable"))
        assertTrue(row.fraction > 0.7f)
    }

    @Test
    fun parsesWifiChannelRatingRowsForExpandableSignalCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Wi-Fi Channel Rating")
                        .put("body", "Channel scores.")
                        .put("graph_type", "wifi_channel_rating")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("band", "2.4GHz")
                                    .put("channel", 11)
                                    .put("score", 96)
                                    .put("rating_label", "excellent")
                                    .put("network_count", 0)
                                    .put("overlap_count", 0)
                                    .put("recommendation", "Best current option: no overlapping APs."),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("2.4GHz ch 11", row.label)
        assertEquals("96/100 excellent", row.valueLabel)
        assertTrue(row.detail.contains("0 overlapping"))
        assertTrue(row.detail.contains("Best current option"))
        assertTrue(row.fraction > 0.9f)
    }

    @Test
    fun parsesWifiChannelUtilizationRowsForExpandableSignalCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Wi-Fi Channel Utilization")
                        .put("body", "Observed channel pressure.")
                        .put("graph_type", "wifi_channel_utilization")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("band", "2.4GHz")
                                    .put("channel", 1)
                                    .put("channel_pressure_score", 72)
                                    .put("utilization_label", "crowded")
                                    .put("network_count", 2)
                                    .put("overlap_count", 3)
                                    .put("strongest_rssi_dbm", -36)
                                    .put("average_rssi_dbm", -52)
                                    .put("max_channel_width_mhz", 40)
                                    .put("security_modes", JSONArray().put("WPA3").put("WPA2"))
                                    .put("sample_ssids", JSONArray().put("HermesNet"))
                                    .put("recommendation", "Crowded channel."),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("2.4GHz ch 1", row.label)
        assertEquals("72% busy crowded", row.valueLabel)
        assertTrue(row.detail.contains("3 visible overlap"))
        assertTrue(row.detail.contains("40MHz max width"))
        assertTrue(row.detail.contains("HermesNet"))
        assertTrue(row.fraction > 0.7f)
    }

    @Test
    fun parsesWifiChannelDecisionPacketRowsForExpandableSignalCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Bluetooth Context")
                            .put("body", "Lower priority context.")
                            .put("graph_type", "bluetooth_rssi")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("label", "Agent context")
                                        .put("ready", true)
                                        .put("value_label", "ready"),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Wi-Fi Channel Decision")
                            .put("body", "Decision packets.")
                            .put("graph_type", "wifi_channel_decision_packet")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("label", "Channel recommendation packet")
                                        .put("category", "wifi_channel_decision_packet")
                                        .put("ready", true)
                                        .put("value_label", "5GHz ch 36 excellent")
                                        .put("detail", "recommended_channels=1 | utilization_rows=3")
                                        .put("recommendation", "Use this as the first router-channel packet.")
                                        .put("fraction", 0.92)
                                        .put("claim_scope", "visible Android Wi-Fi scan/channel evidence only"),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)
        val card = cards.single { it.title == "Wi-Fi Channel Decision" }
        val row = card.rows.single()

        assertEquals("Wi-Fi Channel Decision", card.title)
        assertEquals("Channel recommendation packet", row.label)
        assertEquals("5GHz ch 36 excellent", row.valueLabel)
        assertTrue(row.detail.contains("wifi channel decision packet"))
        assertTrue(row.detail.contains("recommended_channels=1"))
        assertTrue(row.detail.contains("first router-channel packet"))
        assertTrue(row.fraction > 0.9f)
    }

    @Test
    fun parsesWifiVendorSummaryRowsForExpandableSignalCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Wi-Fi Vendors")
                        .put("body", "Vendor rows.")
                        .put("graph_type", "wifi_vendor_summary")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("vendor", "Apple")
                                    .put("network_count", 2)
                                    .put("strongest_rssi_dbm", -44)
                                    .put("bssid_ouis", JSONArray().put("AC:BC:32"))
                                    .put("recommendation", "Strong nearby vendor group."),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Apple", row.label)
        assertEquals("2 APs", row.valueLabel)
        assertTrue(row.detail.contains("AC:BC:32"))
        assertTrue(row.detail.contains("Strong nearby vendor group"))
        assertTrue(row.fraction > 0.75f)
    }

    @Test
    fun parsesWifiAccessPointDetailAndAnalyzerSummaryRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Wi-Fi AP Details")
                            .put("body", "AP details.")
                            .put("graph_type", "wifi_access_point_detail")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("display_ssid", "HermesNet")
                                        .put("bssid", "AC:BC:32:12:34:56")
                                        .put("bssid_vendor", "Apple")
                                        .put("rssi_dbm", -41)
                                        .put("frequency_mhz", 5180)
                                        .put("channel", 36)
                                        .put("band", "5GHz")
                                        .put("channel_width", "80MHz")
                                        .put("wifi_standard", "802.11ac")
                                        .put("security_mode", "WPA2")
                                        .put("estimated_distance_m", 1.25),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Wi-Fi Security")
                            .put("body", "Security groups.")
                            .put("graph_type", "wifi_security_summary")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("security_mode", "WPA2")
                                        .put("network_count", 2)
                                        .put("strongest_rssi_dbm", -41)
                                        .put("bands", JSONArray().put("5GHz"))
                                        .put("channels", JSONArray().put("36"))
                                        .put("recommendation", "WPA2 AP group."),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Wi-Fi Widths")
                            .put("body", "Width groups.")
                            .put("graph_type", "wifi_channel_width_summary")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("channel_width", "80MHz")
                                        .put("channel_width_mhz", 80)
                                        .put("network_count", 1)
                                        .put("recommendation", "Wide channel group."),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)
        val apRow = cards[0].rows.single()
        val securityRow = cards[1].rows.single()
        val widthRow = cards[2].rows.single()

        assertEquals("HermesNet", apRow.label)
        assertEquals("-41 dBm", apRow.valueLabel)
        assertTrue(apRow.detail.contains("802.11ac"))
        assertTrue(apRow.detail.contains("AC:BC:32:12:34:56"))
        assertEquals("WPA2", securityRow.label)
        assertEquals("2 APs", securityRow.valueLabel)
        assertTrue(securityRow.detail.contains("ch 36"))
        assertEquals("80MHz", widthRow.label)
        assertTrue(widthRow.detail.contains("80 MHz effective"))
    }

    @Test
    fun parsesWifiSemanticAndBandCoverageRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Wi-Fi AP Semantics")
                            .put("body", "AP semantics.")
                            .put("graph_type", "wifi_access_point_semantics")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("display_ssid", "Cafe Guest")
                                        .put("semantic_label", "guest/public hotspot")
                                        .put("security_risk_label", "open_network")
                                        .put("security_mode", "Open")
                                        .put("band", "2.4GHz")
                                        .put("channel", 1)
                                        .put("rssi_dbm", -59)
                                        .put("semantic_tags", JSONArray().put("guest_public_hotspot").put("open_network"))
                                        .put("recommendation", "Treat as public."),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Wi-Fi Band Coverage")
                            .put("body", "Band rows.")
                            .put("graph_type", "wifi_band_coverage")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("band", "5GHz")
                                        .put("network_count", 2)
                                        .put("visible_channels", JSONArray().put("36").put("40"))
                                        .put("observed_widths", JSONArray().put("80MHz"))
                                        .put("observed_standards", JSONArray().put("802.11ax"))
                                        .put("strongest_rssi_dbm", -42)
                                        .put("recommended_channel", 36)
                                        .put("recommended_score", 88)
                                        .put("recommendation", "Compare wide-channel contention."),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)
        val semanticRow = cards[0].rows.single()
        val bandRow = cards[1].rows.single()

        assertEquals("Cafe Guest", semanticRow.label)
        assertTrue(semanticRow.valueLabel.contains("guest/public hotspot"))
        assertTrue(semanticRow.valueLabel.contains("open network"))
        assertTrue(semanticRow.detail.contains("Treat as public"))
        assertEquals("5GHz", bandRow.label)
        assertEquals("2 APs observed", bandRow.valueLabel)
        assertTrue(bandRow.detail.contains("best ch 36 88/100"))
        assertTrue(bandRow.fraction > 0.8f)
    }

    @Test
    fun parsesAgentEnvironmentRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Agent Environment")
                            .put("body", "Capability matrix.")
                            .put("graph_type", "agent_capability_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "soc_backend")
                                        .put("label", "SOC and LiteRT backend policy")
                                        .put("ready", true)
                                        .put("value_label", "ARM MediaTek/Mali")
                                        .put("detail", "MediaTek | Mali | arm64-v8a")
                                        .put("recommendation", "GPU-first with CPU fallback.")
                                        .put("fraction", 0.95),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Kai Parity")
                            .put("body", "Parity rows.")
                            .put("graph_type", "kai_parity_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "kai_parity")
                                        .put("label", "Autonomous heartbeat")
                                        .put("ready", true)
                                        .put("value_label", "30s interval")
                                        .put("detail", "Operator heartbeat/status rows.")
                                        .put("recommendation", "Use for self-checks.")
                                        .put("fraction", 0.9),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Kai Operations")
                            .put("body", "Operations rows.")
                            .put("graph_type", "kai_operations_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "kai_operations")
                                        .put("label", "Tool and MCP bridge route")
                                        .put("ready", true)
                                        .put("value_label", "tool_catalog")
                                        .put("detail", "Terminal, file, UI, diagnostics, and memory tools.")
                                        .put("recommendation", "Call tool_catalog first.")
                                        .put("fraction", 0.85),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Tool Sandbox Status")
                            .put("body", "Agent tool sandbox rows.")
                            .put("graph_type", "agent_tool_sandbox_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_tool_sandbox")
                                        .put("label", "Terminal/Linux workspace surface")
                                        .put("ready", true)
                                        .put("value_label", "terminal_tool")
                                        .put("detail", "App-sandboxed terminal workspace with permission gates.")
                                        .put("recommendation", "Use native tools first.")
                                        .put("sandbox_scope", "app-private workspace")
                                        .put("permission_gate", "app storage")
                                        .put("host_access", "no host filesystem")
                                        .put("remote_dispatch_capable", false)
                                        .put("mcp_parity_status", "Kai Linux sandbox analogue")
                                        .put("fraction", 0.8),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "MCP Tool Servers")
                            .put("body", "Kai MCP registry rows.")
                            .put("graph_type", "mcp_tool_server_registry")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "mcp_tool_server_registry")
                                        .put("label", "Context7 documentation server")
                                        .put("ready", false)
                                        .put("value_label", "external docs MCP needed")
                                        .put("detail", "source_action=mcp_tool_server_registry_report; Streamable HTTP MCP bridge required.")
                                        .put("recommendation", "Disclose that Context7 parity needs an external MCP server.")
                                        .put("fraction", 0.35),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "MCP Routing Policy")
                            .put("body", "MCP route rows.")
                            .put("graph_type", "mcp_tool_server_routes")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "mcp_tool_server_route")
                                        .put("label", "Prefer native Hermes tools first")
                                        .put("ready", true)
                                        .put("value_label", "native tools")
                                        .put("detail", "Use native tools before external MCP servers.")
                                        .put("recommendation", "Call tool_catalog first.")
                                        .put("fraction", 0.96),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Upgrade Objective Matrix")
                            .put("body", "Full upgrade audit.")
                            .put("graph_type", "agent_upgrade_objective_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_upgrade_objective")
                                        .put("label", "MediaTek and non-Adreno backend compatibility")
                                        .put("ready", true)
                                        .put("value_label", "7/8 row(s)")
                                        .put("detail", "physical_device_validation_required=true; accelerator_preflight_report is needed.")
                                        .put("recommendation", "Open accelerator preflight before GPU claims.")
                                        .put("fraction", 0.84),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Upgrade Verification Routes")
                            .put("body", "Upgrade routes.")
                            .put("graph_type", "agent_upgrade_route_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_upgrade_route")
                                        .put("label", "Start with full upgrade audit")
                                        .put("ready", true)
                                        .put("value_label", "agent_capability_upgrade_report")
                                        .put("detail", "source_action=agent_capability_upgrade_report; graph_type=agent_upgrade_objective_matrix.")
                                        .put("recommendation", "Run before broad status answers.")
                                        .put("fraction", 0.96),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Agent Observation")
                            .put("body", "Observation dashboard.")
                            .put("graph_type", "agent_observation_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_observation")
                                        .put("label", "Gemma signal dashboard")
                                        .put("ready", true)
                                        .put("value_label", "single observation report")
                                        .put("detail", "Wi-Fi, Bluetooth, sensors, radio, SOC, and Kai rows.")
                                        .put("recommendation", "Use this first.")
                                        .put("fraction", 0.95),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)
        val socRow = cards[0].rows.single()
        val heartbeatRow = cards[1].rows.single()
        val operationsRow = cards[2].rows.single()
        val sandboxRow = cards[3].rows.single()
        val mcpRegistryRow = cards[4].rows.single()
        val mcpRouteRow = cards[5].rows.single()
        val upgradeObjectiveRow = cards[6].rows.single()
        val upgradeRouteRow = cards[7].rows.single()
        val observationRow = cards[8].rows.single()

        assertEquals("SOC and LiteRT backend policy", socRow.label)
        assertEquals("ARM MediaTek/Mali", socRow.valueLabel)
        assertTrue(socRow.detail.contains("soc backend"))
        assertTrue(socRow.detail.contains("GPU-first"))
        assertTrue(socRow.fraction > 0.9f)
        assertEquals("Autonomous heartbeat", heartbeatRow.label)
        assertEquals("30s interval", heartbeatRow.valueLabel)
        assertTrue(heartbeatRow.detail.contains("kai parity"))
        assertEquals("Tool and MCP bridge route", operationsRow.label)
        assertEquals("tool_catalog", operationsRow.valueLabel)
        assertTrue(operationsRow.detail.contains("kai operations"))
        assertTrue(operationsRow.detail.contains("tool_catalog"))
        assertEquals("Terminal/Linux workspace surface", sandboxRow.label)
        assertEquals("terminal_tool", sandboxRow.valueLabel)
        assertTrue(sandboxRow.detail.contains("agent tool sandbox"))
        assertTrue(sandboxRow.detail.contains("permission gates"))
        assertEquals("Context7 documentation server", mcpRegistryRow.label)
        assertEquals("external docs MCP needed", mcpRegistryRow.valueLabel)
        assertTrue(mcpRegistryRow.detail.contains("mcp tool server registry"))
        assertTrue(mcpRegistryRow.detail.contains("external MCP server"))
        assertEquals("Prefer native Hermes tools first", mcpRouteRow.label)
        assertEquals("native tools", mcpRouteRow.valueLabel)
        assertTrue(mcpRouteRow.detail.contains("mcp tool server route"))
        assertTrue(mcpRouteRow.detail.contains("tool_catalog"))
        assertEquals("MediaTek and non-Adreno backend compatibility", upgradeObjectiveRow.label)
        assertEquals("7/8 row(s)", upgradeObjectiveRow.valueLabel)
        assertTrue(upgradeObjectiveRow.detail.contains("agent upgrade objective"))
        assertTrue(upgradeObjectiveRow.detail.contains("accelerator preflight"))
        assertEquals("Start with full upgrade audit", upgradeRouteRow.label)
        assertEquals("agent_capability_upgrade_report", upgradeRouteRow.valueLabel)
        assertTrue(upgradeRouteRow.detail.contains("agent upgrade route"))
        assertTrue(upgradeRouteRow.detail.contains("agent_capability_upgrade_report"))
        assertEquals("Gemma signal dashboard", observationRow.label)
        assertEquals("single observation report", observationRow.valueLabel)
        assertTrue(observationRow.detail.contains("agent observation"))
        assertTrue(observationRow.detail.contains("Use this first"))
    }

    @Test
    fun parsesAgentSignalContextFusionRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Signal Context Fusion")
                        .put("body", "Fused signal context.")
                        .put("graph_type", "agent_signal_context_matrix")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "agent_signal_context")
                                    .put("label", "Wi-Fi channel and band context")
                                    .put("ready", true)
                                    .put("value_label", "2 AP(s), 3 band row(s)")
                                    .put("detail", "Channel rating and band coverage rows are available.")
                                    .put("recommendation", "Open source card for evidence.")
                                    .put("fraction", 0.9),
                            ),
                        ),
                ),
            )
            .toString()

        val card = extractDiagnosticCards(content).single()
        val row = card.rows.single()

        assertEquals("Signal Context Fusion", card.title)
        assertEquals("agent_signal_context_matrix", card.graphType)
        assertEquals("Wi-Fi channel and band context", row.label)
        assertEquals("2 AP(s), 3 band row(s)", row.valueLabel)
        assertTrue(row.detail.contains("agent signal context"))
        assertTrue(row.detail.contains("Open source card"))
        assertTrue(row.fraction > 0.85f)
    }

    @Test
    fun parsesAgentCardManifestRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Agent Card Manifest")
                        .put("body", "Card routes.")
                        .put("graph_type", "agent_card_manifest")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "agent_card_manifest")
                                    .put("label", "Wi-Fi Channel Graph")
                                    .put("ready", true)
                                    .put("value_label", "wifi_channel_graph via wifi_analyzer_report")
                                    .put("detail", "Card exposes channel rows.")
                                    .put("recommendation", "Open this expandable card for evidence.")
                                    .put("fraction", 0.95),
                            ),
                        ),
                ),
            )
            .toString()

        val card = extractDiagnosticCards(content).single()
        val row = card.rows.single()

        assertEquals("Agent Card Manifest", card.title)
        assertEquals("agent_card_manifest", card.graphType)
        assertEquals("Wi-Fi Channel Graph", row.label)
        assertEquals("wifi_channel_graph via wifi_analyzer_report", row.valueLabel)
        assertTrue(row.detail.contains("agent card manifest"))
        assertTrue(row.detail.contains("Open this expandable card"))
        assertTrue(row.fraction > 0.9f)
    }

    @Test
    fun parsesAgentCardPriorityPlannerRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Top Signal Cards")
                            .put("body", "Ranked cards.")
                            .put("graph_type", "agent_card_priority_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_card_priority")
                                        .put("label", "Wi-Fi channel and AP graph cards")
                                        .put("ready", true)
                                        .put("value_label", "3 AP row(s)")
                                        .put("detail", "WiFiAnalyzer-style card route with refresh_policy and permission_gate fields.")
                                        .put("recommendation", "Open this card first.")
                                        .put("open_next_action", "wifi_channel_graph")
                                        .put("refresh_policy", "passive_by_default_refresh_when_needed")
                                        .put("permission_gate", "nearby_wifi_or_location_permission")
                                        .put("fraction", 0.95),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Card Open Sequence")
                            .put("body", "Sequence.")
                            .put("graph_type", "agent_card_open_sequence")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_card_open_sequence")
                                        .put("label", "Open ranked top-card planner")
                                        .put("ready", true)
                                        .put("value_label", "agent_card_priority_report")
                                        .put("detail", "Use graph_type and open_next_action before live scans.")
                                        .put("recommendation", "Use this planner.")
                                        .put("fraction", 0.96),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Kai Interactive Parity")
                            .put("body", "Kai rows.")
                            .put("graph_type", "kai_interactive_screen_parity")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "kai_interactive_screen_parity")
                                        .put("label", "Generated screen and expandable card parity")
                                        .put("ready", true)
                                        .put("value_label", "agent_card_priority_report")
                                        .put("detail", "Hermes maps Kai-style generated screens to expandable cards.")
                                        .put("recommendation", "Compare against Hermes cards.")
                                        .put("fraction", 0.9),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals("Top Signal Cards", cards[0].title)
        assertEquals("agent_card_priority_matrix", cards[0].graphType)
        assertEquals("Wi-Fi channel and AP graph cards", cards[0].rows.single().label)
        assertEquals("3 AP row(s)", cards[0].rows.single().valueLabel)
        assertTrue(cards[0].rows.single().detail.contains("refresh_policy"))
        assertTrue(cards[0].rows.single().detail.contains("Open this card first"))
        assertEquals("Open ranked top-card planner", cards[1].rows.single().label)
        assertTrue(cards[1].rows.single().detail.contains("agent card open sequence"))
        assertEquals("Generated screen and expandable card parity", cards[2].rows.single().label)
        assertTrue(cards[2].rows.single().detail.contains("kai interactive screen parity"))
        assertEquals(0, diagnosticCardPreviewPriority(cards[0]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[1]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[2]))
    }

    @Test
    fun parsesSignalWorkflowHandoffCardsAsTopPriorityCapabilityRows() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Signal Workflow Handoff")
                            .put("body", "Handoff rows.")
                            .put("graph_type", "agent_signal_workflow_handoff_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_workflow_handoff")
                                        .put("label", "Open Wi-Fi Analyzer graph")
                                        .put("ready", true)
                                        .put("value_label", "3 graph row(s)")
                                        .put("detail", "Use open_next_action, refresh_policy, and permission_gate.")
                                        .put("recommendation", "Open wifi_channel_graph.")
                                        .put("open_next_action", "wifi_channel_graph")
                                        .put("graph_type", "wifi_channel_graph")
                                        .put("refresh_policy", "passive_by_default_refresh_when_needed")
                                        .put("permission_gate", "nearby_wifi_or_location_permission")
                                        .put("bridge_required", false)
                                        .put("physical_device_validation_required", false)
                                        .put("fraction", 0.92),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Next Signal Actions")
                            .put("body", "Routes.")
                            .put("graph_type", "agent_signal_next_action_routes")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_next_action_route")
                                        .put("label", "Open radio receiver advisor")
                                        .put("ready", true)
                                        .put("value_label", "radio_signal_advisor_report")
                                        .put("detail", "Bridge route with permission gate.")
                                        .put("recommendation", "Use before AM/FM claims.")
                                        .put("open_next_action", "radio_signal_advisor_report")
                                        .put("graph_type", "radio_signal_advisor_matrix")
                                        .put("refresh_policy", "passive_receiver_decision_first")
                                        .put("permission_gate", "vendor_radio_bridge_or_external_sdr_for_am_fm")
                                        .put("bridge_required", true)
                                        .put("physical_device_validation_required", false)
                                        .put("fraction", 0.78),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals("agent_signal_workflow_handoff_matrix", cards[0].graphType)
        assertEquals("Open Wi-Fi Analyzer graph", cards[0].rows.single().label)
        assertTrue(cards[0].rows.single().detail.contains("open_next_action"))
        assertTrue(cards[0].rows.single().detail.contains("Open wifi_channel_graph"))
        assertEquals("agent_signal_next_action_routes", cards[1].graphType)
        assertEquals("Open radio receiver advisor", cards[1].rows.single().label)
        assertTrue(cards[1].rows.single().detail.contains("Bridge route"))
        assertEquals(0, diagnosticCardPreviewPriority(cards[0]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[1]))
    }

    @Test
    fun parsesSignalAwarenessRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Signal Awareness")
                        .put("body", "Fused rows.")
                        .put("graph_type", "signal_awareness_matrix")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "signal_awareness")
                                    .put("label", "Bluetooth proximity metadata")
                                    .put("ready", true)
                                    .put("value_label", "scan ready")
                                    .put("detail", "Service UUID and manufacturer rows available.")
                                    .put("recommendation", "Use bluetooth_scan.")
                                    .put("fraction", 0.9),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Bluetooth proximity metadata", row.label)
        assertEquals("scan ready", row.valueLabel)
        assertTrue(row.detail.contains("Service UUID"))
        assertTrue(row.fraction > 0.8f)
    }

    @Test
    fun parsesRadioBandRowsWithPublicApiAndExternalHardwareLabels() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Radio Band Plan")
                        .put("body", "Radio rows.")
                        .put("graph_type", "radio_frequency_capability")
                        .put(
                            "rows",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("band", "Wi-Fi 2.4 GHz")
                                        .put("frequency_min_mhz", 2401.0)
                                        .put("frequency_max_mhz", 2484.0)
                                        .put("public_android_scan_supported", true)
                                        .put("access_path", "wifi_channel_utilization")
                                        .put("scan_state", "public_android_metadata_route")
                                        .put("reason", "Android exposes Wi-Fi RSSI and channel metadata."),
                                )
                                .put(
                                    JSONObject()
                                        .put("band", "External SDR / broad RF")
                                        .put("requires_external_hardware", true)
                                        .put("access_path", "USB SDR")
                                        .put("scan_state", "external_receiver_required")
                                        .put("reason", "Receiver bridge required."),
                                ),
                        ),
                ),
            )
            .toString()

        val rows = extractDiagnosticCards(content).single().rows

        assertEquals("Wi-Fi 2.4 GHz", rows[0].label)
        assertEquals("Android API", rows[0].valueLabel)
        assertTrue(rows[0].detail.contains("2401.0-2484.0 MHz"))
        assertTrue(rows[0].detail.contains("wifi_channel_utilization"))
        assertTrue(rows[0].detail.contains("public_android_metadata_route"))
        assertTrue(rows[0].fraction > 0.8f)
        assertEquals("external", rows[1].valueLabel)
        assertTrue(rows[1].detail.contains("external_receiver_required"))
        assertTrue(rows[1].fraction >= 0.4f)
    }

    @Test
    fun parsesRadioReceiverProfileRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Receiver Profiles")
                        .put("body", "Receiver schemas.")
                        .put("graph_type", "radio_receiver_profile")
                        .put(
                            "rows",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("label", "FM station receiver profile")
                                        .put("receiver_id", "fm_vendor_or_sdr")
                                        .put("source_type", "fm_broadcast")
                                        .put("frequency_min_mhz", 87.5)
                                        .put("frequency_max_mhz", 108.0)
                                        .put("vendor_bridge_possible", true)
                                        .put("requires_vendor_bridge", true)
                                        .put("scan_state", "vendor_bridge_required")
                                        .put("route_action", "radio_signal_graph")
                                        .put("access_path", "OEM Broadcast Radio HAL bridge")
                                        .put("graph_row_schema", JSONArray().put("frequency_mhz").put("rds_program_service").put("signal_dbuv_or_rssi_dbm"))
                                        .put("station_metadata_fields", JSONArray().put("frequency_mhz").put("rds_program_service"))
                                        .put("sample_fields", JSONArray().put("frequency_mhz").put("power_db"))
                                        .put("recommendation", "Use this profile as the required FM scan schema.")
                                        .put("fraction", 0.65),
                                ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("FM station receiver profile", row.label)
        assertEquals("vendor bridge", row.valueLabel)
        assertTrue(row.detail.contains("87.5-108.0 MHz"))
        assertTrue(row.detail.contains("route radio_signal_graph"))
        assertTrue(row.detail.contains("vendor_bridge_required"))
        assertTrue(row.detail.contains("schema frequency_mhz"))
        assertTrue(row.detail.contains("Use this profile"))
        assertTrue(row.fraction > 0.6f)
    }

    @Test
    fun parsesRadioSignalAdvisorRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Radio Signal Advisor")
                        .put("body", "Advisor rows.")
                        .put("graph_type", "radio_signal_advisor_matrix")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "radio_signal_advisor")
                                    .put("label", "Receiver source decision")
                                    .put("ready", true)
                                    .put("value_label", "1 sample(s)")
                                    .put("detail", "receiver=fm_vendor_or_sdr | score=92 | samples=1")
                                    .put("recommendation", "Choose the highest-ranked receiver route first.")
                                    .put("fraction", 0.92),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Receiver source decision", row.label)
        assertEquals("1 sample(s)", row.valueLabel)
        assertTrue(row.detail.contains("radio signal advisor"))
        assertTrue(row.detail.contains("Choose the highest-ranked receiver route"))
        assertTrue(row.fraction > 0.9f)
    }

    @Test
    fun parsesRadioSignalDecisionPacketRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Radio Signal Decision")
                        .put("body", "Decision packet rows.")
                        .put("graph_type", "radio_signal_decision_packet")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "radio_signal_decision_packet")
                                    .put("label", "AM/FM sample packet")
                                    .put("ready", true)
                                    .put("value_label", "1 FM / 1 AM sample(s)")
                                    .put("detail", "decision_status=am_fm_samples_available; claim_scope=AM/FM band plan plus receiver-provided samples only")
                                    .put("recommendation", "Use AM/FM graph rows only when a vendor radio bridge or SDR supplies station samples.")
                                    .put("active_refresh_action", "radio_signal_graph")
                                    .put("passive_fallback_action", "radio_signal_status")
                                    .put("fraction", 0.95),
                            ),
                        ),
                ),
            )
            .toString()

        val card = extractDiagnosticCards(content).single()
        val row = card.rows.single()

        assertEquals("radio_signal_decision_packet", card.graphType)
        assertEquals("AM/FM sample packet", row.label)
        assertEquals("1 FM / 1 AM sample(s)", row.valueLabel)
        assertTrue(row.detail.contains("radio signal decision packet"))
        assertTrue(row.detail.contains("am_fm_samples_available"))
        assertTrue(row.detail.contains("receiver-provided samples only"))
        assertEquals(3, diagnosticCardPreviewPriority(card))
    }

    @Test
    fun parsesRadioReceiverCandidateRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Radio Receiver Candidates")
                        .put("body", "Candidate rows.")
                        .put("graph_type", "radio_receiver_candidates")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "radio_receiver_candidate")
                                    .put("label", "FM station receiver profile")
                                    .put("ready", true)
                                    .put("value_label", "1 sample(s)")
                                    .put("detail", "receiver=fm_vendor_or_sdr | source=fm_broadcast | samples=1")
                                    .put("recommendation", "Use the receiver route only when metadata is available.")
                                    .put("candidate_score", 92)
                                    .put("fraction", 0.92),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("FM station receiver profile", row.label)
        assertEquals("1 sample(s)", row.valueLabel)
        assertTrue(row.detail.contains("radio receiver candidate"))
        assertTrue(row.detail.contains("Use the receiver route"))
        assertTrue(row.fraction > 0.9f)
    }

    @Test
    fun parsesRadioSignalGraphRowsForBridgeSamplesAndBandBoundaries() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "AM/FM Signal Graph")
                        .put("body", "Bridge samples.")
                        .put("graph_type", "radio_signal_graph")
                        .put(
                            "rows",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("label", "Hermes FM")
                                        .put("band", "FM broadcast")
                                        .put("frequency_mhz", 99.5)
                                        .put("receiver_id", "fm_vendor_or_sdr")
                                        .put("modulation", "fm")
                                        .put("rssi_dbm", -58)
                                        .put("snr_db", 31)
                                        .put("rds_program_service", "HERMES")
                                        .put("rds_radio_text", "Bridge supplied RDS text")
                                        .put("sample_rate_hz", 240000)
                                        .put("sampled", true)
                                        .put("scan_state", "bridge_sample_reported")
                                        .put("recommendation", "Use as a receiver-provided sample."),
                                )
                                .put(
                                    JSONObject()
                                        .put("label", "AM broadcast band")
                                        .put("band", "AM broadcast")
                                        .put("frequency_min_khz", 530)
                                        .put("frequency_max_khz", 1700)
                                        .put("receiver_id", "am_vendor_or_sdr")
                                        .put("sampled", false)
                                        .put("value_label", "external receiver required")
                                        .put("scan_state", "external_or_vendor_receiver_required"),
                                ),
                        ),
                ),
            )
            .toString()

        val rows = extractDiagnosticCards(content).single().rows

        assertEquals("Hermes FM", rows[0].label)
        assertEquals("-58 dBm", rows[0].valueLabel)
        assertTrue(rows[0].detail.contains("99.5 MHz"))
        assertTrue(rows[0].detail.contains("receiver fm_vendor_or_sdr"))
        assertTrue(rows[0].detail.contains("RDS HERMES"))
        assertTrue(rows[0].detail.contains("Bridge supplied RDS text"))
        assertTrue(rows[0].detail.contains("sample 240000 Hz"))
        assertTrue(rows[0].detail.contains("SNR 31 dB"))
        assertTrue(rows[0].fraction >= 0.6f)
        assertEquals("AM broadcast band", rows[1].label)
        assertEquals("external receiver required", rows[1].valueLabel)
        assertTrue(rows[1].detail.contains("530-1700 kHz"))
        assertTrue(rows[1].detail.contains("external_or_vendor_receiver_required"))
    }

    @Test
    fun parsesRadioBridgeSchemaRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Radio Bridge Sample Schema")
                        .put("body", "Schema rows.")
                        .put("graph_type", "radio_receiver_bridge_schema")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("label", "FM bridge sample input")
                                    .put("receiver_id", "fm_vendor_or_sdr")
                                    .put("source_type", "fm_broadcast")
                                    .put("route_action", "radio_signal_graph")
                                    .put("scan_state", "bridge_sample_schema_ready")
                                    .put("value_label", "schema ready")
                                    .put("sample_fields", JSONArray().put("frequency_mhz").put("rssi_dbm").put("rds_radio_text"))
                                    .put("direct_argument_fields", JSONArray().put("frequency_mhz").put("station_label"))
                                    .put("json_argument_keys", JSONArray().put("radio_samples").put("radio_samples_json"))
                                    .put("recommendation", "Feed FM tuner rows through radio_signal_graph."),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("FM bridge sample input", row.label)
        assertEquals("schema ready", row.valueLabel)
        assertTrue(row.detail.contains("route radio_signal_graph"))
        assertTrue(row.detail.contains("samples frequency_mhz"))
        assertTrue(row.detail.contains("args frequency_mhz"))
        assertTrue(row.detail.contains("json radio_samples"))
    }

    @Test
    fun parsesRadioWorkflowRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Radio Signal Routes")
                        .put("body", "Route rows.")
                        .put("graph_type", "radio_signal_workflow_routes")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "radio_signal_route")
                                    .put("label", "Route Wi-Fi spectrum work")
                                    .put("ready", true)
                                    .put("value_label", "wifi_channel_utilization")
                                    .put("detail", "Use Wi-Fi channel utilization for graphable RF data.")
                                    .put("recommendation", "Run wifi_channel_utilization first.")
                                    .put("fraction", 0.9),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Route Wi-Fi spectrum work", row.label)
        assertEquals("wifi_channel_utilization", row.valueLabel)
        assertTrue(row.detail.contains("radio signal route"))
        assertTrue(row.detail.contains("Run wifi_channel_utilization"))
        assertTrue(row.fraction > 0.85f)
    }

    @Test
    fun parsesWifiSignalHistoryRowsForExpandableSignalCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Wi-Fi History")
                        .put("body", "Signal history.")
                        .put("graph_type", "wifi_signal_history")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("ssid", "HermesNet")
                                    .put("bssid_vendor", "Apple")
                                    .put("current_rssi_dbm", -55)
                                    .put("average_rssi_dbm", -60)
                                    .put("min_rssi_dbm", -66)
                                    .put("max_rssi_dbm", -55)
                                    .put("trend_db", 11)
                                    .put("trend_label", "improving")
                                    .put("sample_count", 2)
                                    .put("band", "5GHz")
                                    .put("channel", 36),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("HermesNet", row.label)
        assertEquals("-55 dBm improving", row.valueLabel)
        assertTrue(row.detail.contains("2 samples"))
        assertTrue(row.detail.contains("avg -60 dBm"))
        assertTrue(row.detail.contains("improving +11 dB"))
        assertTrue(row.fraction > 0.6f)
    }

    @Test
    fun parsesBluetoothSignalHistoryRowsForExpandableSignalCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Bluetooth Signal History")
                        .put("body", "Signal history.")
                        .put("graph_type", "bluetooth_signal_history")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("device_name", "Heart Strap")
                                    .put("device_type", "le")
                                    .put("device_category", "wearable_health")
                                    .put("proximity_label", "room")
                                    .put("current_rssi_dbm", -58)
                                    .put("average_rssi_dbm", -65)
                                    .put("min_rssi_dbm", -72)
                                    .put("max_rssi_dbm", -58)
                                    .put("trend_db", 14)
                                    .put("trend_label", "approaching")
                                    .put("sample_count", 2)
                                    .put("service_uuids", JSONArray().put("0000180d-0000-1000-8000-00805f9b34fb"))
                                    .put("service_labels", JSONArray().put("Heart Rate"))
                                    .put("manufacturer_ids", JSONArray().put("0x004C"))
                                    .put("manufacturer_names", JSONArray().put("Apple")),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Heart Strap", row.label)
        assertEquals("-58 dBm approaching", row.valueLabel)
        assertTrue(row.detail.contains("wearable_health"))
        assertTrue(row.detail.contains("2 samples"))
        assertTrue(row.detail.contains("avg -65 dBm"))
        assertTrue(row.detail.contains("approaching +14 dB"))
        assertTrue(row.detail.contains("services Heart Rate"))
        assertTrue(row.detail.contains("manufacturers Apple"))
        assertTrue(row.detail.contains("manufacturers 0x004C"))
        assertTrue(row.fraction > 0.5f)
    }

    @Test
    fun parsesSensorVectorRowsFromMotionSamples() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Motion Sensors")
                        .put("body", "Sensor rows.")
                        .put("graph_type", "sensor_vector")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("sensor_type", "accelerometer")
                                    .put("sensor_label", "Accelerometer")
                                    .put("sampled", true)
                                    .put("unit", "m/s^2")
                                    .put("values", JSONArray().put(0.0).put(0.0).put(9.81)),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Accelerometer", row.label)
        assertEquals("9.81 m/s^2", row.valueLabel)
        assertTrue(row.fraction > 0.45f)
    }

    @Test
    fun parsesMotionSensorHistoryRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Motion Sensor History")
                        .put("body", "Motion trend rows.")
                        .put("graph_type", "motion_sensor_history")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("sensor_type", "accelerometer")
                                    .put("sensor_label", "Accelerometer")
                                    .put("sensor_name", "BMI160 Accelerometer")
                                    .put("vendor", "Bosch")
                                    .put("magnitude_unit", "m/s^2")
                                    .put("sample_count", 3)
                                    .put("current_magnitude", 11.18)
                                    .put("average_magnitude", 10.5)
                                    .put("min_magnitude", 9.81)
                                    .put("max_magnitude", 11.18)
                                    .put("trend_magnitude", 1.37)
                                    .put("trend_label", "increasing")
                                    .put("stability_label", "drifting")
                                    .put("current_values", JSONArray().put(0.0).put(2.0).put(11.0)),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Accelerometer", row.label)
        assertEquals("11.18 m/s^2 increasing", row.valueLabel)
        assertTrue(row.detail.contains("3 samples"))
        assertTrue(row.detail.contains("stability drifting"))
        assertTrue(row.detail.contains("range 9.81..11.18 m/s^2"))
        assertTrue(row.detail.contains("vector 0, 2, 11"))
        assertTrue(row.fraction > 0.5f)
    }

    @Test
    fun parsesMotionPoseEstimateRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Motion Pose Estimate")
                        .put("body", "Pose rows.")
                        .put("graph_type", "motion_pose_estimate")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("pose_type", "device_pose")
                                    .put("label", "Device pose estimate")
                                    .put("value_label", "face up | heading E")
                                    .put("pose_source", "accelerometer+magnetic_field")
                                    .put("source_sensors", JSONArray().put("accelerometer").put("magnetic_field"))
                                    .put("roll_degrees", 0.0)
                                    .put("pitch_degrees", 0.0)
                                    .put("tilt_degrees", 0.0)
                                    .put("azimuth_degrees", 90.0)
                                    .put("heading_label", "E")
                                    .put("confidence_label", "high")
                                    .put("workflow_hint", "Use for heading-aware workflows.")
                                    .put("fraction", 0.9),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Device pose estimate", row.label)
        assertEquals("face up | heading E", row.valueLabel)
        assertTrue(row.detail.contains("source accelerometer+magnetic_field"))
        assertTrue(row.detail.contains("sensors accelerometer, magnetic_field"))
        assertTrue(row.detail.contains("confidence high"))
        assertTrue(row.detail.contains("azimuth 90.0 deg"))
        assertTrue(row.detail.contains("heading E"))
        assertTrue(row.fraction > 0.8f)
    }

    @Test
    fun parsesSensorCapabilityRowsWithHardwareMetadata() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Sensor Hardware")
                        .put("body", "Sensor metadata rows.")
                        .put("graph_type", "sensor_capability")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("sensor_type", "gyroscope")
                                    .put("sensor_label", "Gyroscope")
                                    .put("sensor_name", "BMI160 Gyroscope")
                                    .put("vendor", "Bosch")
                                    .put("available", true)
                                    .put("unit", "rad/s")
                                    .put("maximum_range", 34.91)
                                    .put("resolution", 0.001)
                                    .put("power_ma", 0.9)
                                    .put("min_delay_us", 5000)
                                    .put("reporting_mode", "continuous")
                                    .put("wake_up", true),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Gyroscope", row.label)
        assertEquals("range 34.91 rad/s", row.valueLabel)
        assertTrue(row.detail.contains("Bosch"))
        assertTrue(row.detail.contains("200.0 Hz"))
        assertTrue(row.detail.contains("wake-up"))
        assertTrue(row.fraction > 0.8f)
    }

    @Test
    fun parsesRadioCapabilityRowsAsLimitsWhenHardwareIsExternal() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "AM/FM Radio")
                        .put("body", "Capability rows.")
                        .put("graph_type", "radio_frequency_capability")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("band", "External SDR")
                                    .put("supported", false)
                                    .put("sampled", false)
                                    .put("requires_external_hardware", true)
                                    .put("reason", "Attach an SDR."),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("External SDR", row.label)
        assertEquals("external", row.valueLabel)
        assertTrue(row.detail.contains("Attach an SDR"))
        assertTrue(row.fraction in 0.4f..0.5f)
    }

    @Test
    fun parsesMediatekSignalStackRowsAsTopPriorityCapabilityCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "MediaTek Signal Stack")
                            .put("body", "SOC and signal rows.")
                            .put("graph_type", "mediatek_signal_stack_matrix")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "mediatek_signal_stack")
                                        .put("label", "Wi-Fi analyzer evidence")
                                        .put("ready", true)
                                        .put("value_label", "2 AP / 4 channel row(s)")
                                        .put("detail", "Passive Wi-Fi Analyzer rows joined to SOC policy.")
                                        .put("recommendation", "Open wifi_analyzer_report before making live claims.")
                                        .put("fraction", 0.9),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "MediaTek Signal Routes")
                            .put("body", "Refresh routes.")
                            .put("graph_type", "mediatek_signal_refresh_routes")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "mediatek_signal_refresh_route")
                                        .put("label", "Refresh Wi-Fi and Bluetooth evidence")
                                        .put("ready", false)
                                        .put("value_label", "permission gated")
                                        .put("detail", "Refresh only the requested signal domain.")
                                        .put("recommendation", "Use passive rows until permission is available.")
                                        .put("fraction", 0.35),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "MediaTek Claim Boundaries")
                            .put("body", "Claim boundaries.")
                            .put("graph_type", "mediatek_signal_claim_boundaries")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "mediatek_signal_claim_boundary")
                                        .put("label", "Backend policy is not a live signal")
                                        .put("ready", true)
                                        .put("value_label", "separate claims")
                                        .put("detail", "Backend policy and nearby signal evidence are distinct.")
                                        .put("recommendation", "Mention evidence classes separately.")
                                        .put("fraction", 0.95),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals(3, cards.size)
        assertEquals("mediatek_signal_stack_matrix", cards[0].graphType)
        assertEquals("Wi-Fi analyzer evidence", cards[0].rows.single().label)
        assertTrue(cards[0].rows.single().detail.contains("mediatek signal stack"))
        assertEquals("mediatek_signal_refresh_routes", cards[1].graphType)
        assertEquals("Refresh Wi-Fi and Bluetooth evidence", cards[1].rows.single().label)
        assertTrue(cards[1].rows.single().detail.contains("permission"))
        assertEquals("mediatek_signal_claim_boundaries", cards[2].graphType)
        assertEquals("Backend policy is not a live signal", cards[2].rows.single().label)
        assertEquals(0, diagnosticCardPreviewPriority(cards[0]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[1]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[2]))
    }

    @Test
    fun parsesWifiAnalyzerReadinessRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Wi-Fi Analyzer Readiness")
                        .put("body", "Readiness rows.")
                        .put("graph_type", "wifi_analyzer_feature_matrix")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "wifi_analyzer_parity")
                                    .put("label", "Channel signal graph")
                                    .put("ready", true)
                                    .put("value_label", "24 channel row(s)")
                                    .put("detail", "Scores nearby channels.")
                                    .put("recommendation", "Use wifi_channel_rating.")
                                    .put("fraction", 0.92),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Channel signal graph", row.label)
        assertEquals("24 channel row(s)", row.valueLabel)
        assertTrue(row.detail.contains("wifi analyzer parity"))
        assertTrue(row.detail.contains("Use wifi_channel_rating"))
        assertTrue(row.fraction > 0.9f)
    }

    @Test
    fun parsesBluetoothAnalyzerReadinessRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Bluetooth Analyzer Readiness")
                        .put("body", "Readiness rows.")
                        .put("graph_type", "bluetooth_analyzer_feature_matrix")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "bluetooth_analyzer_parity")
                                    .put("label", "RSSI proximity graph")
                                    .put("ready", true)
                                    .put("value_label", "12 device row(s)")
                                    .put("detail", "Bluetooth RSSI proximity rows are available.")
                                    .put("recommendation", "Use bluetooth_scan.")
                                    .put("fraction", 0.91),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("RSSI proximity graph", row.label)
        assertEquals("12 device row(s)", row.valueLabel)
        assertTrue(row.detail.contains("bluetooth analyzer parity"))
        assertTrue(row.detail.contains("Use bluetooth_scan"))
        assertTrue(row.fraction > 0.9f)
    }

    @Test
    fun parsesBluetoothAdvisorRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Bluetooth Advisor")
                        .put("body", "Advisor rows.")
                        .put("graph_type", "bluetooth_signal_advisor_matrix")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "bluetooth_signal_advisor")
                                    .put("label", "Nearby device decision")
                                    .put("ready", true)
                                    .put("value_label", "-47 dBm near")
                                    .put("detail", "candidate=Hermes Heart | score=94")
                                    .put("recommendation", "Use bluetooth_device_details.")
                                    .put("fraction", 0.94),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Nearby device decision", row.label)
        assertEquals("-47 dBm near", row.valueLabel)
        assertTrue(row.detail.contains("bluetooth signal advisor"))
        assertTrue(row.detail.contains("Use bluetooth_device_details"))
        assertTrue(row.fraction > 0.9f)
    }

    @Test
    fun parsesBluetoothDeviceCandidateRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Bluetooth Device Candidates")
                        .put("body", "Candidate rows.")
                        .put("graph_type", "bluetooth_device_candidates")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "bluetooth_device_candidate")
                                    .put("label", "Hermes Heart")
                                    .put("ready", true)
                                    .put("value_label", "-47 dBm near")
                                    .put("detail", "category=wearable_health | services=Heart Rate")
                                    .put("recommendation", "Inspect details.")
                                    .put("fraction", 0.96),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Hermes Heart", row.label)
        assertEquals("-47 dBm near", row.valueLabel)
        assertTrue(row.detail.contains("bluetooth device candidate"))
        assertTrue(row.detail.contains("Inspect details"))
        assertTrue(row.fraction > 0.9f)
    }

    @Test
    fun parsesSensorAnalyzerReadinessRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Sensor Analyzer Readiness")
                        .put("body", "Readiness rows.")
                        .put("graph_type", "sensor_analyzer_feature_matrix")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "sensor_analyzer_parity")
                                    .put("label", "Gyroscope access")
                                    .put("ready", true)
                                    .put("value_label", "gyroscope ready")
                                    .put("detail", "Gyroscope rows are available.")
                                    .put("recommendation", "Use sensor_snapshot.")
                                    .put("fraction", 0.93),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Gyroscope access", row.label)
        assertEquals("gyroscope ready", row.valueLabel)
        assertTrue(row.detail.contains("sensor analyzer parity"))
        assertTrue(row.detail.contains("Use sensor_snapshot"))
        assertTrue(row.fraction > 0.9f)
    }

    @Test
    fun parsesSensorWorkflowAdvisorRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Sensor Workflow Advisor")
                        .put("body", "Advisor rows.")
                        .put("graph_type", "sensor_workflow_advisor_matrix")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "sensor_workflow_advisor")
                                    .put("label", "Accelerometer workflow readiness")
                                    .put("ready", true)
                                    .put("value_label", "Accelerometer ready")
                                    .put("detail", "sensor=accelerometer | available=true")
                                    .put("recommendation", "Use sensor_snapshot.")
                                    .put("fraction", 0.95),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Accelerometer workflow readiness", row.label)
        assertEquals("Accelerometer ready", row.valueLabel)
        assertTrue(row.detail.contains("sensor workflow advisor"))
        assertTrue(row.detail.contains("Use sensor_snapshot"))
        assertTrue(row.fraction > 0.9f)
    }

    @Test
    fun parsesSensorWorkflowCandidateRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Sensor Workflow Candidates")
                        .put("body", "Candidate rows.")
                        .put("graph_type", "sensor_workflow_candidates")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "sensor_workflow_candidate")
                                    .put("label", "Gyroscope")
                                    .put("ready", true)
                                    .put("value_label", "available")
                                    .put("detail", "type=gyroscope | power_ma=0.7")
                                    .put("recommendation", "Use for angular velocity.")
                                    .put("fraction", 0.88),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Gyroscope", row.label)
        assertEquals("available", row.valueLabel)
        assertTrue(row.detail.contains("sensor workflow candidate"))
        assertTrue(row.detail.contains("Use for angular velocity"))
        assertTrue(row.fraction > 0.8f)
    }

    @Test
    fun parsesMotionSensorQualityRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Motion Sensor Quality")
                        .put("body", "Quality rows.")
                        .put("graph_type", "motion_sensor_quality")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "motion_sensor_quality")
                                    .put("label", "IMU fusion source coverage")
                                    .put("ready", true)
                                    .put("value_label", "4/6 source(s)")
                                    .put("detail", "accelerometer=true | gyroscope=true | rotation_vector=true")
                                    .put("recommendation", "Use motion_sensor_quality before orientation workflows.")
                                    .put("quality_signal", "fusion_sources")
                                    .put("tool_action", "motion_sensor_quality")
                                    .put("fraction", 0.94),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("IMU fusion source coverage", row.label)
        assertEquals("4/6 source(s)", row.valueLabel)
        assertTrue(row.detail.contains("motion sensor quality"))
        assertTrue(row.detail.contains("Use motion_sensor_quality"))
        assertTrue(row.fraction > 0.9f)
    }

    @Test
    fun parsesMotionSensorDecisionPacketRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Motion Sensor Decision")
                        .put("body", "Decision packet rows.")
                        .put("graph_type", "motion_sensor_decision_packet")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "motion_sensor_decision_packet")
                                    .put("label", "Gyroscope and pose packet")
                                    .put("ready", true)
                                    .put("value_label", "rotation vector ready")
                                    .put("detail", "decision_status=rotation_context_available | claim_scope=orientation and angular-motion estimate")
                                    .put("recommendation", "Use rotation_vector or gyroscope plus accelerometer before heading-sensitive claims.")
                                    .put("claim_scope", "orientation and angular-motion estimate")
                                    .put("active_refresh_action", "motion_pose")
                                    .put("passive_fallback_action", "motion_sensor_quality")
                                    .put("sensor_privacy_sensitive", true)
                                    .put("fraction", 0.86),
                            ),
                        ),
                ),
            )
            .toString()

        val card = extractDiagnosticCards(content).single()
        val row = card.rows.single()

        assertEquals("motion_sensor_decision_packet", card.graphType)
        assertEquals("Gyroscope and pose packet", row.label)
        assertEquals("rotation vector ready", row.valueLabel)
        assertTrue(row.detail.contains("motion sensor decision packet"))
        assertTrue(row.detail.contains("rotation_context_available"))
        assertTrue(row.detail.contains("orientation and angular-motion estimate"))
        assertEquals(4, diagnosticCardPreviewPriority(card))
    }

    @Test
    fun parsesSocBackendRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "SOC Compatibility")
                        .put("body", "Backend rows.")
                        .put("graph_type", "soc_backend_matrix")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "soc_backend_parity")
                                    .put("label", "MediaTek/Mali/PowerVR coverage")
                                    .put("ready", true)
                                    .put("value_label", "MediaTek covered")
                                    .put("detail", "GPU probe plus CPU fallback is available.")
                                    .put("recommendation", "Use soc_compatibility_report.")
                                    .put("fraction", 0.95),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("MediaTek/Mali/PowerVR coverage", row.label)
        assertEquals("MediaTek covered", row.valueLabel)
        assertTrue(row.detail.contains("soc backend parity"))
        assertTrue(row.detail.contains("Use soc_compatibility_report"))
        assertTrue(row.fraction > 0.9f)
    }

    @Test
    fun parsesGpuBackendRiskRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "GPU Backend Risk")
                        .put("body", "Risk rows.")
                        .put("graph_type", "gpu_backend_risk_matrix")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "gpu_backend_risk")
                                    .put("label", "Live accelerator acceptance")
                                    .put("ready", true)
                                    .put("value_label", "gpu")
                                    .put("detail", "GPU accepted on MediaTek/Mali.")
                                    .put("recommendation", "Use local_backend_runtime_report.")
                                    .put("risk_level", "low")
                                    .put("risk_score", 10)
                                    .put("fraction", 0.9),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("Live accelerator acceptance", row.label)
        assertEquals("gpu", row.valueLabel)
        assertTrue(row.detail.contains("gpu backend risk"))
        assertTrue(row.detail.contains("Use local_backend_runtime_report"))
        assertTrue(row.fraction > 0.85f)
    }

    @Test
    fun parsesNonAdrenoBackendAdvisorRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Non-Adreno Backend Advisor")
                        .put("body", "Launch rows.")
                        .put("graph_type", "non_adreno_backend_advisor_matrix")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "non_adreno_backend_advisor")
                                    .put("label", "Preflight delegate order")
                                    .put("ready", true)
                                    .put("value_label", "gpu > cpu")
                                    .put("detail", "selected=local_gemma | supported_abis=arm64-v8a")
                                    .put("recommendation", "Open accelerator preflight before launch.")
                                    .put("tool_action", "accelerator_preflight_report")
                                    .put("graph_type", "accelerator_preflight_matrix")
                                    .put("fraction", 0.88),
                            ),
                        ),
                ),
            )
            .toString()

        val card = extractDiagnosticCards(content).single()
        val row = card.rows.single()

        assertEquals("non_adreno_backend_advisor_matrix", card.graphType)
        assertEquals("Preflight delegate order", row.label)
        assertEquals("gpu > cpu", row.valueLabel)
        assertTrue(row.detail.contains("non adreno backend advisor"))
        assertTrue(row.detail.contains("Open accelerator preflight before launch"))
        assertEquals(5, diagnosticCardPreviewPriority(card))
    }

    @Test
    fun parsesRuntimeBackendRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Runtime Backend Health")
                        .put("body", "Runtime rows.")
                        .put("graph_type", "runtime_backend_matrix")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "runtime_backend")
                                    .put("label", "LiteRT-LM /health accelerator")
                                    .put("ready", true)
                                    .put("value_label", "gpu")
                                    .put("detail", "GPU was accepted on ARM MediaTek/Mali.")
                                    .put("recommendation", "Use local_backend_runtime_report.")
                                    .put("source_surface", "/health")
                                    .put("health_url", "http://127.0.0.1:15436/health")
                                    .put("fraction", 0.95),
                            ),
                        ),
                ),
            )
            .toString()

        val row = extractDiagnosticCards(content).single().rows.single()

        assertEquals("LiteRT-LM /health accelerator", row.label)
        assertEquals("gpu", row.valueLabel)
        assertTrue(row.detail.contains("runtime backend"))
        assertTrue(row.detail.contains("Use local_backend_runtime_report"))
        assertTrue(row.fraction > 0.9f)
    }

    @Test
    fun parsesSignalObservationPacketRowsAsTopPriorityCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("title", "Signal Observation Packet")
                            .put("body", "Gemma-visible packet rows.")
                            .put("graph_type", "agent_signal_observation_packet")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_observation_packet")
                                        .put("label", "Wi-Fi Analyzer observation packet")
                                        .put("ready", true)
                                        .put("value_label", "8 fused evidence row(s)")
                                        .put("detail", "modality=wifi_graph; observation_status=passive_wifi_analyzer_context; graph_type=wifi_channel_graph")
                                        .put("recommendation", "Open the packet before answering what Gemma can see.")
                                        .put("fraction", 0.9),
                                ),
                            ),
                    )
                    .put(
                        JSONObject()
                            .put("title", "Observation Graph Routes")
                            .put("body", "Routes.")
                            .put("graph_type", "agent_signal_observation_graph_routes")
                            .put(
                                "rows",
                                JSONArray().put(
                                    JSONObject()
                                        .put("category", "agent_signal_observation_graph_route")
                                        .put("label", "Open Bluetooth proximity graph")
                                        .put("ready", true)
                                        .put("value_label", "bluetooth_signal_history via bluetooth_signal_history")
                                        .put("detail", "active_refresh_action=bluetooth_scan; passive_fallback_action=bluetooth_analyzer_report")
                                        .put("recommendation", "Use active refresh only when needed.")
                                        .put("fraction", 0.86),
                                ),
                            ),
                    ),
            )
            .toString()

        val cards = extractDiagnosticCards(content)

        assertEquals(2, cards.size)
        assertEquals("agent_signal_observation_packet", cards[0].graphType)
        assertEquals("Wi-Fi Analyzer observation packet", cards[0].rows.single().label)
        assertTrue(cards[0].rows.single().detail.contains("agent signal observation packet"))
        assertTrue(cards[0].rows.single().detail.contains("passive_wifi_analyzer_context"))
        assertEquals("agent_signal_observation_graph_routes", cards[1].graphType)
        assertEquals("Open Bluetooth proximity graph", cards[1].rows.single().label)
        assertEquals(0, diagnosticCardPreviewPriority(cards[0]))
        assertEquals(0, diagnosticCardPreviewPriority(cards[1]))
    }

    @Test
    fun parsesBluetoothNearbyDecisionPacketRowsForExpandableSignalCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Bluetooth Nearby Decision")
                        .put("body", "Gemma-visible Bluetooth decision packet.")
                        .put("graph_type", "bluetooth_nearby_decision_packet")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "bluetooth_nearby_decision_packet")
                                    .put("label", "Nearby Bluetooth candidate packet")
                                    .put("ready", true)
                                    .put("value_label", "-47 dBm near")
                                    .put("detail", "decision_status=candidate_available; source_graph_type=bluetooth_device_candidates; claim_scope=Android-visible Bluetooth metadata only")
                                    .put("recommendation", "Use this packet before answering what Gemma can safely infer from Bluetooth metadata.")
                                    .put("claim_scope", "Android-visible Bluetooth metadata only")
                                    .put("active_refresh_action", "bluetooth_scan")
                                    .put("passive_fallback_action", "bluetooth_signal_advisor_report")
                                    .put("fraction", 0.86),
                            ),
                        ),
                ),
            )
            .toString()

        val card = extractDiagnosticCards(content).single()
        val row = card.rows.single()

        assertEquals("bluetooth_nearby_decision_packet", card.graphType)
        assertEquals("Nearby Bluetooth candidate packet", row.label)
        assertEquals("-47 dBm near", row.valueLabel)
        assertTrue(row.detail.contains("bluetooth nearby decision packet"))
        assertTrue(row.detail.contains("candidate_available"))
        assertTrue(row.detail.contains("Android-visible Bluetooth metadata only"))
        assertEquals(2, diagnosticCardPreviewPriority(card))
    }

    @Test
    fun parsesAcceleratorPreflightRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Accelerator Preflight")
                        .put("body", "Delegate preflight rows.")
                        .put("graph_type", "accelerator_preflight_matrix")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "accelerator_preflight")
                                    .put("label", "OpenCL library visibility")
                                    .put("ready", true)
                                    .put("value_label", "visible")
                                    .put("detail", "visible=/vendor/lib64/libOpenCL.so")
                                    .put("recommendation", "Use OpenCL path visibility as a hint only.")
                                    .put("opencl_library_visible", true)
                                    .put("fraction", 0.9),
                            ),
                        ),
                ),
            )
            .toString()

        val card = extractDiagnosticCards(content).single()
        val row = card.rows.single()

        assertEquals("accelerator_preflight_matrix", card.graphType)
        assertEquals("OpenCL library visibility", row.label)
        assertEquals("visible", row.valueLabel)
        assertTrue(row.detail.contains("accelerator preflight"))
        assertTrue(row.detail.contains("Use OpenCL path visibility as a hint"))
        assertEquals(5, diagnosticCardPreviewPriority(card))
    }

    @Test
    fun parsesRadioBridgeSampleMetadataRowsForExpandableCards() {
        val content = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("title", "Radio Bridge Sample Metadata")
                        .put("body", "Receiver sample readiness.")
                        .put("graph_type", "radio_bridge_sample_metadata")
                        .put(
                            "rows",
                            JSONArray().put(
                                JSONObject()
                                    .put("category", "radio_bridge_sample_metadata")
                                    .put("label", "NOAA sample")
                                    .put("ready", true)
                                    .put("value_label", "sample ready")
                                    .put("detail", "162.55 MHz | receiver=external_sdr_bridge | span_hz=200000 | sample_rate_hz=240000")
                                    .put("recommendation", "Display as bridge-provided receiver metadata.")
                                    .put("external_sdr_sample", true)
                                    .put("metadata_completeness_score", 100)
                                    .put("fraction", 1.0),
                            ),
                        ),
                ),
            )
            .toString()

        val card = extractDiagnosticCards(content).single()
        val row = card.rows.single()

        assertEquals("radio_bridge_sample_metadata", card.graphType)
        assertEquals("NOAA sample", row.label)
        assertEquals("sample ready", row.valueLabel)
        assertTrue(row.detail.contains("radio bridge sample metadata"))
        assertTrue(row.detail.contains("span_hz=200000"))
        assertEquals(3, diagnosticCardPreviewPriority(card))
    }
}
