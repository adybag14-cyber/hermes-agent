package com.mobilefork.hermesagent.ui.chat

import android.content.Context
import com.mobilefork.hermesagent.api.ChatContentPart
import com.mobilefork.hermesagent.api.ChatMessage
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class NativeToolChatSendResult(
    val content: String,
    val executedToolCalls: Int = 0,
    val modelRequestCount: Int = 0,
)

internal data class NativeDirectToolAuthority(
    val toolName: String? = null,
    private val parsedArguments: JSONObject = JSONObject(),
    val source: Source = Source.NONE,
) {
    enum class Source {
        NONE,
        TYPED,
        CLOSED_NATURAL_DIAGNOSTIC,
        CLOSED_NATURAL_READ_ONLY_TERMINAL,
    }

    val toolNames: Set<String>
        get() = toolName?.let(::setOf) ?: emptySet()

    val isTypedInvocation: Boolean
        get() = source == Source.TYPED

    fun allows(candidate: String): Boolean = candidate == toolName

    fun arguments(): JSONObject = JSONObject(parsedArguments.toString())
}

/**
 * Single authority parser for every pre-model native side effect.
 *
 * Typed calls must begin with exactly one canonical tool token (optionally after a short
 * invocation verb), and the remainder must fully match that tool's structured argument grammar.
 * Consumers receive only the parsed tool and arguments; they never rescan free-form prose. Two
 * deliberately closed natural forms remain direct: the fixed read-only terminal allowlist and
 * the localized device-status/all-features diagnostics shortcuts.
 */
internal object NativeDirectToolAuthorityParser {
    private val typedPrefixRegex = Regex(
        pattern = """^\s*(?:(?:please|kindly)\s+)?(?:(?:use|run|call|execute|invoke)\s+(?:the\s+)?)?(${typedToolTokens().joinToString("|") { Regex.escape(it) }})\b([\s\S]*)$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val keyValueRegex = Regex(
        pattern = """([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(\"(?:\\.|[^\"])*\"|'(?:\\.|[^'])*'|[^\s,;!?]+)""",
    )
    fun parse(userText: String): NativeDirectToolAuthority {
        val typedMatch = typedPrefixRegex.matchEntire(userText) ?: typedPrefixRegex.matchEntire(userText.trim())
        if (typedMatch != null) {
            val toolName = canonicalToolName(typedMatch.groupValues[1].lowercase())
            val arguments = parseTypedArguments(toolName, typedMatch.groupValues[2])
                ?: return NativeDirectToolAuthority()
            return NativeDirectToolAuthority(
                toolName = toolName,
                parsedArguments = arguments,
                source = NativeDirectToolAuthority.Source.TYPED,
            )
        }

        val actionText = normalizeClosedNaturalText(userText)
        if (isClosedNaturalDiagnosticRequest(actionText)) {
            val action = if ("all features" in actionText || "全部功能全测试" in actionText) {
                "agent_native_tool_self_test_report"
            } else {
                "status"
            }
            return NativeDirectToolAuthority(
                toolName = "android_device_diagnostics_tool",
                parsedArguments = JSONObject().put("action", action),
                source = NativeDirectToolAuthority.Source.CLOSED_NATURAL_DIAGNOSTIC,
            )
        }
        val readOnlyCommand = closedNaturalReadOnlyTerminalCommand(actionText)
        if (readOnlyCommand != null) {
            return NativeDirectToolAuthority(
                toolName = "terminal_tool",
                parsedArguments = JSONObject().put("command", readOnlyCommand),
                source = NativeDirectToolAuthority.Source.CLOSED_NATURAL_READ_ONLY_TERMINAL,
            )
        }
        return NativeDirectToolAuthority()
    }

    private fun parseTypedArguments(toolName: String, rawRemainder: String): JSONObject? {
        val remainder = rawRemainder.trim()
        val arguments = when {
            toolName == "terminal_tool" -> {
                parseTerminalInvocation(remainder)?.let { JSONObject().put("command", it) }
                    ?: parseTerminalKeyValueArguments(remainder)
            }
            toolName == "file_write_tool" -> {
                parseFileWriteInvocation(remainder)?.let { (path, content) ->
                    JSONObject().put("path", path).put("content", content)
                } ?: parseClosedKeyValueArguments(remainder)
            }
            else -> parseClosedKeyValueArguments(remainder)
        } ?: return null
        return arguments.takeIf { validateTypedArguments(toolName, it) }
    }

    private fun parseTerminalInvocation(remainder: String): String? {
        val match = Regex(
            pattern = """^(?:to\s+)?(?:run|execute)(?:\s+exactly(?:\s+this\s+command)?)?(?:\s+command)?\s*(?::\s*|\s+)(.+)$""",
            option = RegexOption.IGNORE_CASE,
        ).matchEntire(remainder) ?: return null
        val command = match.groupValues[1].trim().trimEnd('.').trim()
        return command.takeIf {
            it.lowercase() in setOf("pwd", "whoami", "date", "id", "ls -la", "uname -a")
        }
    }

    private fun parseTerminalKeyValueArguments(remainder: String): JSONObject? {
        val match = Regex(
            pattern = """^command\s*=\s*(?:\"((?:\\.|[^\"])*)\"|'((?:\\.|[^'])*)')(?:\s+(?:and\s+)?timeout_seconds\s*=\s*(\d+))?\.?$""",
            option = RegexOption.IGNORE_CASE,
        ).matchEntire(remainder) ?: return null
        val command = match.groupValues[1].ifBlank { match.groupValues[2] }
        if (command.isBlank()) return null
        return JSONObject().put("command", command).apply {
            val rawTimeout = match.groupValues[3]
            if (rawTimeout.isNotBlank()) {
                val timeout = rawTimeout.toIntOrNull() ?: return null
                put("timeout_seconds", timeout)
            }
        }
    }

    private fun parseFileWriteInvocation(remainder: String): Pair<String, String>? {
        val match = Regex(
            pattern = """^(?:to\s+)?(?:write|create|save)\s+(?:\"([^\"]+)\"|'([^']+)'|([^\s]+))\s+with\s+content\s+(?:\"([^\"]*)\"|'([^']*)'|([^\s.]+))\.?$""",
            option = RegexOption.IGNORE_CASE,
        ).matchEntire(remainder) ?: return null
        val path = match.groupValues.slice(1..3).firstOrNull { it.isNotBlank() }.orEmpty()
        val content = match.groupValues.slice(4..6).firstOrNull { it.isNotBlank() }.orEmpty()
        return if (path.isNotBlank() && content.isNotBlank()) path to content else null
    }

    private fun parseClosedKeyValueArguments(rawRemainder: String): JSONObject? {
        var remainder = rawRemainder.trim()
        if (remainder.isBlank() || remainder == ".") return JSONObject()
        if (remainder.endsWith('.')) remainder = remainder.dropLast(1).trimEnd()
        remainder = remainder.replaceFirst(Regex("""^(?:with\s+)""", RegexOption.IGNORE_CASE), "")
        if (remainder.isBlank()) return JSONObject()

        val result = JSONObject()
        val seen = mutableSetOf<String>()
        var cursor = 0
        while (cursor < remainder.length) {
            val separator = Regex("""^\s*(?:(?:,|\band\b)\s*)?""", RegexOption.IGNORE_CASE)
                .find(remainder.substring(cursor))
                ?: return null
            cursor += separator.value.length
            val match = keyValueRegex.find(remainder, cursor) ?: return null
            if (match.range.first != cursor) return null
            val key = match.groupValues[1].lowercase()
            if (!seen.add(key)) return null
            result.put(key, jsonScalar(match.groupValues[2]))
            cursor = match.range.last + 1
        }
        return result
    }

    private fun validateTypedArguments(toolName: String, arguments: JSONObject): Boolean {
        val keys = arguments.keys().asSequence().toSet()
        val allowedKeys = TOOL_ARGUMENT_KEYS[toolName] ?: return false
        if (!allowedKeys.containsAll(keys)) return false
        val requiredKeys = TOOL_REQUIRED_KEYS[toolName].orEmpty()
        if (requiredKeys.any { key -> !arguments.has(key) || arguments.optString(key).isBlank() }) return false
        return when (toolName) {
            "terminal_tool" -> validTimeoutSeconds(arguments)
            "linux_sandbox_tool" ->
                arguments.optString("action").lowercase() in SANDBOX_ACTIONS && validTimeoutSeconds(arguments)
            "mcp_run_in_proot" -> arguments.optString("command").isNotBlank() && validTimeoutSeconds(arguments)
            "android_device_diagnostics_tool" -> NativeToolCallingChatClient
                .isSupportedDirectAndroidDiagnosticsAction(arguments.optString("action"))
            else -> true
        }
    }

    private fun validTimeoutSeconds(arguments: JSONObject): Boolean {
        if (!arguments.has("timeout_seconds")) return true
        val value = arguments.opt("timeout_seconds")
        return value is Int && value in 1..900
    }

    private fun jsonScalar(rawValue: String): Any {
        if ((rawValue.startsWith('"') && rawValue.endsWith('"')) ||
            (rawValue.startsWith('\'') && rawValue.endsWith('\''))
        ) {
            return rawValue.substring(1, rawValue.length - 1)
        }
        return when (rawValue.lowercase()) {
            "true" -> true
            "false" -> false
            else -> rawValue.toIntOrNull() ?: rawValue.toDoubleOrNull() ?: rawValue
        }
    }

    private fun normalizeClosedNaturalText(userText: String): String {
        return userText
            .lowercase()
            .replace('\u2010', '-')
            .replace('\u2011', '-')
            .replace('\u2013', '-')
            .replace('\u2019', '\'')
            .trim()
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun isClosedNaturalDiagnosticRequest(actionText: String): Boolean {
        val normalized = actionText.trimEnd('.', '!', '?', '。', '！', '？')
        return normalized in setOf(
            "run a full all features test for hermes native tools",
            "run the full all features test for hermes native tools",
            "perform a full all features test for hermes native tools",
            "全部功能全测试",
            "check my device status",
            "检查我的设备状态",
            "comprueba el estado de mi dispositivo",
            "prüfe meinen gerätestatus",
            "verifique o status do meu dispositivo",
            "vérifie l'état de mon appareil",
            "vérifie l'etat de mon appareil",
        )
    }

    private fun closedNaturalReadOnlyTerminalCommand(actionText: String): String? {
        val normalized = actionText.trimEnd('.', '!', '?', '。', '！', '？')
        return when {
            normalized in setOf(
                "what time is it",
                "what's the time",
                "what is the time",
                "tell me what time it is",
                "tell me the time",
                "show the current time",
                "run a command to tell me what time it is",
                "what is the current date",
                "what's the current date",
                "tell me the current date",
                "show the current date",
                "run the date command and tell me the time",
                "运行 date 命令并告诉我时间",
                "ejecuta date y dime la hora",
                "führe date aus und nenne mir die uhrzeit",
                "execute date e diga a hora",
                "exécute date et donne-moi l'heure",
                "exécute date et donne moi l'heure",
            ) -> "date"
            normalized in setOf(
                "who is the current user",
                "tell me the current user",
                "show the current user",
                "run whoami",
                "whoami",
            ) -> "whoami"
            normalized in setOf(
                "what is the current directory",
                "tell me the current directory",
                "show the current directory",
                "run pwd",
                "pwd",
                "运行 pwd 命令",
                "ejecuta el comando pwd",
                "führe den befehl pwd aus",
                "fuehre den befehl pwd aus",
                "execute o comando pwd",
                "exécute la commande pwd",
                "execute la commande pwd",
            ) -> "pwd"
            normalized in setOf(
                "list files here",
                "show files here",
                "show the directory contents",
                "run ls -la",
                "ls -la",
            ) -> "ls -la"
            normalized in setOf(
                "show system information",
                "show the kernel version",
                "run uname -a",
                "uname -a",
            ) -> "uname -a"
            else -> null
        }
    }

    private fun canonicalToolName(name: String): String = when (name) {
        "terminal", "shell", "mcp_send_terminal_input" -> "terminal_tool"
        "linux_sandbox", "proot_distro_tool", "proot-distro", "proot_distro" -> "linux_sandbox_tool"
        "mcp_run_in_proot" -> "mcp_run_in_proot"
        "host_pkg_tool", "termux_pkg_tool", "pkg_tool", "hermes_pkg_tool" -> "linux_host_pkg_tool"
        "write_file", "file_tool" -> "file_write_tool"
        "android_system_action", "system_tool", "settings_tool", "phone_tool" -> "android_system_tool"
        "device_diagnostics_tool", "diagnostics_tool", "resource_tool", "wifi_analyzer_tool",
        "bluetooth_scanner_tool", "bluetooth_analyzer_tool", "sensor_tool", "sensor_analyzer_tool",
        "camera_tool", "radio_signal_tool", "rf_coexistence_tool", "soc_backend_tool",
        "runtime_stability_tool", "device_performance_tool", "mcp_tool_server_tool", "mcp_registry_tool" ->
            "android_device_diagnostics_tool"
        "hymemory_tool", "hindsight_memory_tool", "memory_tool", "recall_tool", "retain_tool" -> "hy_memory_tool"
        "memory_search", "memory_add", "memory_delete", "memory_list" -> name
        "automation_tool", "tasker_tool", "kai_task_tool" -> "android_automation_tool"
        "ui_tool", "screen_tool", "accessibility_tool" -> "android_ui_tool"
        else -> name
    }

    private fun typedToolTokens(): List<String> = listOf(
        "terminal_tool", "terminal", "shell", "mcp_send_terminal_input",
        "linux_sandbox_tool", "linux_sandbox", "mcp_run_in_proot", "proot_distro_tool", "proot-distro", "proot_distro",
        "linux_host_pkg_tool", "host_pkg_tool", "termux_pkg_tool", "pkg_tool", "hermes_pkg_tool",
        "file_write_tool", "write_file", "file_tool",
        "android_system_tool", "android_system_action", "system_tool", "settings_tool", "phone_tool",
        "android_device_diagnostics_tool", "device_diagnostics_tool", "diagnostics_tool", "resource_tool",
        "wifi_analyzer_tool", "bluetooth_scanner_tool", "bluetooth_analyzer_tool", "sensor_tool",
        "sensor_analyzer_tool", "camera_tool", "radio_signal_tool", "rf_coexistence_tool", "soc_backend_tool",
        "runtime_stability_tool", "device_performance_tool", "mcp_tool_server_tool", "mcp_registry_tool",
        "hy_memory_tool", "hymemory_tool", "hindsight_memory_tool", "memory_tool", "recall_tool", "retain_tool",
        "memory_search", "memory_add", "memory_delete", "memory_list",
        "android_automation_tool", "automation_tool", "tasker_tool", "kai_task_tool",
        "android_ui_tool", "ui_tool", "screen_tool", "accessibility_tool",
        "schedule_task", "list_tasks", "cancel_task",
    )

    private val diagnosticArgumentKeys = setOf(
        "action", "refresh", "include_snapshot", "sample", "include_scan", "save_file",
        "include_hidden", "hidden_only", "limit", "max_results", "timeout_ms", "detail_limit",
        "min_rssi_dbm", "max_rssi_dbm", "export_format", "format", "scan_mode", "filter_band",
        "filter_security", "filter_signal", "filter_ssid", "filter_bssid", "filter_vendor",
        "filter_device_name", "filter_bluetooth_address", "filter_bluetooth_service",
        "filter_bluetooth_manufacturer", "filter_bluetooth_category", "filter_bluetooth_proximity",
        "sensor_types", "radio_samples_json", "radio_bridge_samples_json", "receiver_samples_json",
        "sdr_samples_json", "sdr_spectrum_samples_json", "spectrum_samples_json", "waterfall_rows_json",
        "sample_source", "receiver_id", "station_label", "frequency_mhz", "frequency_khz",
        "frequency_hz", "center_frequency_hz", "span_hz", "sample_rate_hz", "bin_width_hz",
        "bandwidth_hz", "rssi_dbm", "power_db", "snr_db", "modulation", "rds_program_service",
        "rds_radio_text", "message", "position", "hide_after_ms",
    )

    private val TOOL_ARGUMENT_KEYS = mapOf(
        "terminal_tool" to setOf("command", "timeout_seconds"),
        "mcp_run_in_proot" to setOf("command", "distro_id", "name", "timeout_seconds"),
        "linux_sandbox_tool" to setOf(
            "action", "distro_id", "name", "image", "mirror_profile", "command", "timeout_seconds",
        ),
        "linux_host_pkg_tool" to setOf("action", "packages", "package", "query", "mirror_profile"),
        "file_write_tool" to setOf("path", "content", "append"),
        "android_system_tool" to setOf(
            "action", "command", "package_name", "permission", "enabled", "setting_namespace",
            "setting_name", "setting_value", "dnd_mode", "user_id", "network_types_bitmask", "slot_id",
            "timeout_seconds",
        ),
        "android_device_diagnostics_tool" to diagnosticArgumentKeys,
        "hy_memory_tool" to setOf(
            "action", "content", "facts", "query", "memory_id", "tags", "category", "source", "limit",
            "max_chars", "max_entries",
        ),
        "memory_search" to setOf("query", "limit"),
        "memory_add" to setOf("content", "tags", "category", "source"),
        "memory_delete" to setOf("memory_id"),
        "memory_list" to setOf("limit"),
        "android_ui_tool" to setOf(
            "action", "raw_action", "action_text", "prediction", "vlm_prediction", "open_gui_action",
            "opengui_action", "screen_hash", "text_contains", "content_description_contains", "view_id",
            "package_name", "app_name", "class_name", "value", "index", "limit", "x", "y", "x1", "y1",
            "x2", "y2", "coordinate_space", "duration_ms", "direction", "distance_px", "save_file",
            "include_base64", "include_snapshot", "include_screenshot", "max_image_edge_px",
        ),
        "schedule_task" to setOf(
            "task", "title", "task_id", "time", "at", "interval_minutes", "every_minutes", "days_of_week",
            "enabled",
        ),
        "list_tasks" to setOf("limit"),
        "cancel_task" to setOf("task_id"),
        "android_automation_tool" to setOf(
            "action", "id", "task_id", "task", "label", "command", "path", "content", "append",
            "intent_task_action", "data_uri", "intent_action", "system_action", "ui_action", "shizuku_action",
            "notification_action", "notification_id", "notification_title", "notification_text", "status_text",
            "progress_value", "progress_max", "progress_indeterminate", "package_name", "class_name",
            "permission", "enabled", "target_enabled", "trigger", "time", "days_of_week", "name", "value",
            "clipboard_text", "toast_text", "latitude", "longitude", "radius_meters", "tasker_xml",
            "tasker_data_uri", "bundle_json", "settings_json",
        ),
    )

    private val TOOL_REQUIRED_KEYS = mapOf(
        "terminal_tool" to setOf("command"),
        "mcp_run_in_proot" to setOf("command"),
        "linux_sandbox_tool" to setOf("action"),
        "linux_host_pkg_tool" to setOf("action"),
        "file_write_tool" to setOf("path", "content"),
        "android_system_tool" to setOf("action"),
        "android_device_diagnostics_tool" to setOf("action"),
        "hy_memory_tool" to setOf("action"),
        "memory_search" to setOf("query"),
        "memory_add" to setOf("content"),
        "memory_delete" to setOf("memory_id"),
        "android_ui_tool" to setOf("action"),
        "schedule_task" to setOf("task"),
        "cancel_task" to setOf("task_id"),
        "android_automation_tool" to setOf("action"),
    )

    internal fun allowedArgumentKeys(toolName: String): Set<String> =
        TOOL_ARGUMENT_KEYS[toolName].orEmpty()

    internal fun requiredArgumentKeys(toolName: String): Set<String> =
        TOOL_REQUIRED_KEYS[toolName].orEmpty()

    private val SANDBOX_ACTIONS = setOf(
        "catalog", "status", "list", "download", "install", "deploy", "update", "upgrade", "set_mirror",
        "start", "enable", "stop", "close", "disable", "run", "uninstall", "remove",
    )
}

data class NativeAgentEvent(
    val type: AgentEventType,
    val title: String,
    val content: String,
)

/**
 * A request-scoped native operation with atomic prepared-to-claimed and claimed-to-executing
 * boundaries. Cancellation which wins before [executeClaimed] enters makes the body unreachable
 * and completes immediately; cancellation after execution starts is delivered only to this
 * operation's client and completion waits for the body's cleanup boundary.
 */
internal class NativeToolChatOperation<T>(
    private val onCancel: () -> Unit,
    private val executeBlock: () -> T,
    private val laneGuard: NativeToolOperationLaneGuard? = null,
) {
    private enum class State {
        PREPARED,
        CLAIMED,
        EXECUTING,
        CANCELLED,
        FINISHED,
    }

    private val state = AtomicReference(State.PREPARED)
    private val cancellationRequested = AtomicBoolean(false)
    private val completion = CountDownLatch(1)

    fun cancel(): Boolean {
        while (true) {
            when (val snapshot = state.get()) {
                State.PREPARED -> {
                    if (state.compareAndSet(snapshot, State.CANCELLED)) {
                        try {
                            requestCancellationOnce()
                        } finally {
                            // No worker can start after PREPARED -> CANCELLED, so cleanup is
                            // complete as soon as the request-local cancellation hook returns.
                            completion.countDown()
                        }
                        return true
                    }
                }
                State.CLAIMED -> {
                    if (state.compareAndSet(snapshot, State.CANCELLED)) {
                        try {
                            requestCancellationOnce()
                        } finally {
                            // The request owned the start claim but never entered its body. No
                            // process/callback cleanup exists, so unwind is verified immediately.
                            completion.countDown()
                        }
                        return true
                    }
                }
                State.EXECUTING -> {
                    requestCancellationOnce()
                    return true
                }
                State.CANCELLED, State.FINISHED -> return false
            }
        }
    }

    fun claimStart(): Boolean {
        laneGuard?.requireHealthy()
        return state.compareAndSet(State.PREPARED, State.CLAIMED)
    }

    fun executeClaimed(): T {
        if (!state.compareAndSet(State.CLAIMED, State.EXECUTING)) {
            throw CancellationException("Native request was stopped before it started")
        }
        return try {
            if (cancellationRequested.get()) {
                throw CancellationException("Native request was stopped before it started")
            }
            executeBlock()
        } finally {
            state.compareAndSet(State.EXECUTING, State.FINISHED)
            // executeBlock includes tool-specific process/callback cleanup. Reaching this exact
            // boundary is therefore the operation-owned unwind contract awaited by Stop.
            completion.countDown()
        }
    }

    /**
     * Await only this operation's worker. A timeout is an unverifiable cleanup boundary, so the
     * shared native lane is poisoned and future operations fail closed until process restart.
     */
    fun awaitCompletion(timeoutMs: Long): Boolean {
        val completed = try {
            completion.await(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            laneGuard?.poison(
                "A stopped native operation did not finish its owned cleanup within ${timeoutMs.coerceAtLeast(0L)}ms.",
            )
        }
        return completed
    }

    fun execute(): T {
        if (!claimStart()) {
            throw CancellationException("Native request was stopped before it started")
        }
        return executeClaimed()
    }

    private fun requestCancellationOnce() {
        if (cancellationRequested.compareAndSet(false, true)) {
            onCancel()
        }
    }
}

internal class NativeToolOperationLaneGuard {
    private val poisonDetail = AtomicReference<String?>(null)

    fun requireHealthy() {
        val detail = poisonDetail.get() ?: return
        throw IllegalStateException(
            "$detail Hermes will not start another native tool operation because request-owned " +
                "process or callback cleanup cannot be verified. Force stop and reopen Hermes before retrying.",
        )
    }

    fun poison(detail: String) {
        poisonDetail.compareAndSet(null, detail)
    }

    internal fun poisonDetailForTest(): String? = poisonDetail.get()
}

internal object NativeToolChatSender {
    private val operationLaneGuard = NativeToolOperationLaneGuard()
    fun extractTypedDirectToolName(prompt: String): String? {
        val authority = NativeDirectToolAuthorityParser.parse(prompt)
        return authority.toolName?.takeIf { authority.isTypedInvocation }
    }

    fun prepareDirectTyped(
        context: Context,
        prompt: String,
    ): NativeToolChatOperation<NativeToolChatSendResult?> {
        val authority = NativeDirectToolAuthorityParser.parse(prompt)
        return prepareOperation(context) { client ->
            if (!authority.isTypedInvocation) return@prepareOperation null
            client.executeExplicitDirectToolRequest(authority)?.let { result ->
                NativeToolChatSendResult(
                    content = result.content,
                    executedToolCalls = result.executedToolCalls,
                    modelRequestCount = result.modelRequestCount,
                )
            }
        }
    }

    fun extractDirectDiagnosticsArguments(prompt: String): JSONObject? {
        val authority = NativeDirectToolAuthorityParser.parse(prompt)
        if (!authority.allows("android_device_diagnostics_tool")) return null
        return authority.arguments().takeIf { it.optString("action").isNotBlank() }
    }

    fun extractDirectReadOnlyTerminalCommand(prompt: String): String? {
        val authority = NativeDirectToolAuthorityParser.parse(prompt)
        if (authority.source != NativeDirectToolAuthority.Source.CLOSED_NATURAL_READ_ONLY_TERMINAL) return null
        return authority.arguments().optString("command").takeIf { it.isNotBlank() }
    }

    fun extractDirectLinuxSandboxPrompt(prompt: String): Boolean {
        val authority = NativeDirectToolAuthorityParser.parse(prompt)
        return authority.isTypedInvocation &&
            (authority.allows("linux_sandbox_tool") || authority.allows("mcp_run_in_proot")) &&
            (
                authority.arguments().optString("action").isNotBlank() ||
                    authority.arguments().optString("command").isNotBlank()
            )
    }

    fun prepareDirectLinuxSandbox(
        context: Context,
        prompt: String,
    ): NativeToolChatOperation<NativeToolChatSendResult?> {
        val authority = NativeDirectToolAuthorityParser.parse(prompt)
        return prepareOperation(context) { client ->
            client.executeExplicitLinuxSandboxRequest(authority)?.let { result ->
                NativeToolChatSendResult(
                    content = result.content,
                    executedToolCalls = result.executedToolCalls,
                    modelRequestCount = result.modelRequestCount,
                )
            }
        }
    }

    fun prepareDirectReadOnlyTerminal(
        context: Context,
        prompt: String,
    ): NativeToolChatOperation<NativeToolChatSendResult?> {
        val authority = NativeDirectToolAuthorityParser.parse(prompt)
        return prepareOperation(context) { client ->
            client.executeSafeNaturalTerminalRequest(authority)?.let { result ->
                NativeToolChatSendResult(
                    content = result.content,
                    executedToolCalls = result.executedToolCalls,
                    modelRequestCount = result.modelRequestCount,
                )
            }
        }
    }

    fun prepareSend(
        context: Context,
        baseUrl: String,
        modelName: String,
        apiKey: String? = null,
        providerId: String = "",
        sessionId: String,
        userText: String,
        userContentParts: List<ChatContentPart>,
        priorMessages: List<ChatMessage>,
        relevantMemoryContext: String,
        onEvent: (NativeAgentEvent) -> Unit = {},
    ): NativeToolChatOperation<NativeToolChatSendResult> {
        return prepareOperation(context) { client ->
            val result = client.send(
                baseUrl = baseUrl,
                modelName = modelName,
                apiKey = apiKey,
                providerId = providerId,
                sessionId = sessionId,
                userText = userText,
                userContentParts = userContentParts,
                priorMessages = priorMessages,
                relevantMemoryContext = relevantMemoryContext,
                onEvent = onEvent,
            )
            NativeToolChatSendResult(
                content = result.content,
                executedToolCalls = result.executedToolCalls,
                modelRequestCount = result.modelRequestCount,
            )
        }
    }

    private fun <T> prepareOperation(
        context: Context,
        execute: (NativeToolCallingChatClient) -> T,
    ): NativeToolChatOperation<T> {
        val cancelled = AtomicBoolean(false)
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.MINUTES)
            .addInterceptor { chain ->
                if (cancelled.get()) {
                    throw InterruptedIOException("Native request was stopped before network registration")
                }
                chain.proceed(chain.request())
            }
            .build()
        val client = NativeToolCallingChatClient(
            context = context.applicationContext,
            httpClient = httpClient,
        )
        return NativeToolChatOperation(
            onCancel = {
                cancelled.set(true)
                client.cancel()
            },
            executeBlock = { execute(client) },
            laneGuard = operationLaneGuard,
        )
    }
}
