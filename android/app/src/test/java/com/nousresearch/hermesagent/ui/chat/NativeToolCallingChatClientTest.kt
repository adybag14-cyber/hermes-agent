package com.nousresearch.hermesagent.ui.chat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException

class NativeToolCallingChatClientTest {
    @Test
    fun skipsLocalFollowUpAfterExternalActivityHandoff() {
        val result = JSONObject()
            .put("success", true)
            .put("action", "open_uri")
            .put("external_activity_handoff", true)
            .put("message", "Started Android intent")

        assertTrue(NativeToolCallingChatClient.shouldSkipNativeFollowUpAfterToolResult(result.toString()))
    }

    @Test
    fun continuesLocalFollowUpAfterOrdinaryToolResult() {
        val result = JSONObject()
            .put("success", true)
            .put("path", "hermes-output.txt")

        assertFalse(NativeToolCallingChatClient.shouldSkipNativeFollowUpAfterToolResult(result.toString()))
    }

    @Test
    fun recoversHtmlGenerationTimeoutsWithFallbackDocument() {
        assertTrue(
            NativeToolCallingChatClient.isRecoverableHtmlGenerationFailure(
                IllegalStateException("LiteRT-LM generation timed out after 45 seconds before producing a response"),
            )
        )
        assertTrue(
            NativeToolCallingChatClient.isRecoverableHtmlGenerationFailure(
                InterruptedIOException("timeout"),
            )
        )
    }

    @Test
    fun doesNotHideNonTimeoutHtmlGenerationErrors() {
        assertFalse(
            NativeToolCallingChatClient.isRecoverableHtmlGenerationFailure(
                IllegalStateException("image input is unavailable"),
            )
        )
    }

    @Test
    fun compactsLargeJsonToolResultForLocalModelContext() {
        val largeOutput = "scroll-item\n".repeat(700)
        val result = JSONObject()
            .put("exit_code", 0)
            .put("output", largeOutput)
            .put("cwd", "/data/data/com.nousresearch.hermesagent/files")
            .toString()

        val compacted = NativeToolContextCompressor.compactToolResult(result)
        val parsed = JSONObject(compacted)

        assertTrue(parsed.getBoolean("_hermes_context_compressed"))
        assertTrue(parsed.getInt("_original_chars") > compacted.length)
        assertEquals(0, parsed.getInt("exit_code"))
        assertTrue(parsed.getString("output").contains("scroll-item"))
        assertTrue(parsed.getString("output").contains("hermes context compressed"))
        assertTrue(compacted.length < result.length)
    }

    @Test
    fun compactsDiagnosticArraysButKeepsTopRowsReadable() {
        val networks = JSONArray()
        repeat(60) { index ->
            networks.put(
                JSONObject()
                    .put("ssid", "Lab-$index")
                    .put("bssid_oui", "AC:BC:32")
                    .put("bssid_vendor", "Apple")
                    .put("rssi_dbm", -30 - index)
                    .put("signal_quality", "excellent")
                    .put("frequency_mhz", 2412 + index)
                    .put("channel", index + 1)
                    .put("channel_width", "20MHz")
                    .put("security_mode", "WPA2")
                    .put("capabilities", "[WPA2-PSK-CCMP][ESS]".repeat(8)),
            )
        }
        val channelRatings = JSONArray()
        repeat(20) { index ->
            channelRatings.put(
                JSONObject()
                    .put("band", "2.4GHz")
                    .put("channel", index + 1)
                    .put("score", 100 - index)
                    .put("rating_label", "good")
                    .put("network_count", index)
                    .put("overlap_count", index + 1)
                    .put("strongest_rssi_dbm", -40 - index)
                    .put("recommendation", "Use if this is the highest-scored row."),
            )
        }
        val result = JSONObject()
            .put("success", true)
            .put("action", "wifi_scan")
            .put("wifi_vendor_count", 1)
            .put("wifi_filter_count", 4)
            .put("wifi_networks", networks)
            .put("wifi_channel_ratings", channelRatings)
            .put(
                "wifi_vendor_summary",
                JSONArray().put(
                    JSONObject()
                        .put("vendor", "Apple")
                        .put("network_count", 60)
                        .put("strongest_rssi_dbm", -30)
                        .put("bssid_ouis", JSONArray().put("AC:BC:32"))
                        .put("recommendation", "Strong nearby vendor group."),
                ),
            )
            .put(
                "wifi_analyzer_filters",
                JSONArray().put(
                    JSONObject()
                        .put("key", "security")
                        .put("label", "Security")
                        .put("options", JSONArray().put(JSONObject().put("value", "WPA2").put("count", 60))),
                ),
            )
            .put(
                "recommended_wifi_channels",
                JSONArray().put(JSONObject().put("band", "2.4GHz").put("channel", 11).put("score", 96)),
            )
            .put("cards", JSONArray().put(JSONObject().put("title", "Wi-Fi Analyzer").put("body", "60 signals")))
            .toString()

        val compacted = NativeToolContextCompressor.compactToolResult(result)
        val parsed = JSONObject(compacted)
        val wifiNetworks = parsed.getJSONObject("wifi_networks")
        val wifiRatings = parsed.getJSONObject("wifi_channel_ratings")
        val vendorSummary = parsed.getJSONArray("wifi_vendor_summary")
        val analyzerFilters = parsed.getJSONArray("wifi_analyzer_filters")

        assertTrue(parsed.getBoolean("_hermes_context_compressed"))
        assertEquals(1, parsed.getInt("wifi_vendor_count"))
        assertEquals(4, parsed.getInt("wifi_filter_count"))
        assertEquals("array", wifiNetworks.getString("type"))
        assertEquals(60, wifiNetworks.getInt("original_count"))
        assertEquals(8, wifiNetworks.getJSONArray("items").length())
        assertEquals("Lab-0", wifiNetworks.getJSONArray("items").getJSONObject(0).getString("ssid"))
        assertEquals("Apple", wifiNetworks.getJSONArray("items").getJSONObject(0).getString("bssid_vendor"))
        assertEquals("WPA2", wifiNetworks.getJSONArray("items").getJSONObject(0).getString("security_mode"))
        assertEquals("array", wifiRatings.getString("type"))
        assertEquals(20, wifiRatings.getInt("original_count"))
        assertEquals(1, wifiRatings.getJSONArray("items").getJSONObject(0).getInt("channel"))
        assertEquals("Apple", vendorSummary.getJSONObject(0).getString("vendor"))
        assertEquals("security", analyzerFilters.getJSONObject(0).getString("key"))
        assertEquals(11, parsed.getJSONArray("recommended_wifi_channels").getJSONObject(0).getInt("channel"))
        assertEquals("Wi-Fi Analyzer", parsed.getJSONArray("cards").getJSONObject(0).getString("title"))
    }

    @Test
    fun compactsBluetoothAndRadioDiagnosticRowsWithoutDroppingSignalMetadata() {
        val devices = JSONArray()
        repeat(30) { index ->
            devices.put(
                JSONObject()
                    .put("device_name", "Beacon-$index")
                    .put("address", "AA:BB:CC:00:00:$index")
                    .put("rssi_dbm", -40 - index)
                    .put("device_type", "le")
                    .put("device_category", "wearable_health")
                    .put("service_uuids", JSONArray().put("0000180d-0000-1000-8000-00805f9b34fb"))
                    .put("manufacturer_ids", JSONArray().put("0x004C"))
                    .put("proximity_label", "near")
                    .put("scan_record", "ff".repeat(200)),
            )
        }
        val result = JSONObject()
            .put("success", true)
            .put("action", "bluetooth_scan")
            .put("bluetooth_metadata_count", 3)
            .put("bluetooth_service_uuid_count", 1)
            .put("bluetooth_manufacturer_id_count", 1)
            .put("bluetooth_devices", devices)
            .put(
                "bluetooth_metadata_summary",
                JSONArray().put(
                    JSONObject()
                        .put("summary_type", "manufacturer_id")
                        .put("label", "0x004C")
                        .put("count", 30)
                        .put("strongest_rssi_dbm", -40)
                        .put("recommendation", "Manufacturer data advertised nearby."),
                ),
            )
            .put("cards", JSONArray().put(JSONObject().put("title", "Bluetooth Nearby").put("body", "30 devices")))
            .toString()

        val compacted = NativeToolContextCompressor.compactToolResult(result)
        val parsed = JSONObject(compacted)
        val compactedDevices = parsed.getJSONObject("bluetooth_devices")
        val metadataSummary = parsed.getJSONArray("bluetooth_metadata_summary")

        assertTrue(parsed.getBoolean("_hermes_context_compressed"))
        assertEquals(3, parsed.getInt("bluetooth_metadata_count"))
        assertEquals(1, parsed.getInt("bluetooth_service_uuid_count"))
        assertEquals(1, parsed.getInt("bluetooth_manufacturer_id_count"))
        assertEquals(30, compactedDevices.getInt("original_count"))
        assertEquals("Beacon-0", compactedDevices.getJSONArray("items").getJSONObject(0).getString("device_name"))
        assertEquals(-40, compactedDevices.getJSONArray("items").getJSONObject(0).getInt("rssi_dbm"))
        assertEquals("wearable_health", compactedDevices.getJSONArray("items").getJSONObject(0).getString("device_category"))
        assertEquals("near", compactedDevices.getJSONArray("items").getJSONObject(0).getString("proximity_label"))
        assertEquals("0x004C", metadataSummary.getJSONObject(0).getString("label"))
    }

    @Test
    fun compactsCompletedNativeToolRoundsButKeepsLatestAssistantBlock() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "tools enabled"))
            .put(JSONObject().put("role", "user").put("content", "Scroll TikTok, reply to DMs, then draft email"))
        repeat(6) { index ->
            messages
                .put(
                    assistantToolCall(
                        id = "call_$index",
                        name = "android_ui_tool",
                    ),
                )
                .put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", "call_$index")
                        .put("name", "android_ui_tool")
                        .put(
                            "content",
                            NativeToolContextCompressor.compactToolResult(
                                "Scrolled feed $index\n" + "visible row $index ".repeat(1_800),
                            ),
                        ),
                )
        }

        val compacted = NativeToolContextCompressor.compactMessages(messages)

        assertTrue(compacted.length() < messages.length())
        assertEquals("system", compacted.getJSONObject(0).getString("role"))
        assertEquals("user", compacted.getJSONObject(1).getString("role"))
        assertTrue(compacted.getJSONObject(2).getString("content").contains("compacted prior native tool context"))
        val latestAssistant = compacted.getJSONObject(compacted.length() - 2)
        assertEquals("assistant", latestAssistant.getString("role"))
        assertEquals("call_5", latestAssistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("tool", compacted.getJSONObject(compacted.length() - 1).getString("role"))
    }

    private fun assistantToolCall(id: String, name: String): JSONObject {
        return JSONObject()
            .put("role", "assistant")
            .put("content", JSONObject.NULL)
            .put(
                "tool_calls",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", id)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", name)
                                    .put("arguments", JSONObject().put("action", "scroll_forward").toString()),
                            ),
                    ),
            )
    }
}
