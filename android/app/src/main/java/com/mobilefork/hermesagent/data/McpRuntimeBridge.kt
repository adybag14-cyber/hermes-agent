package com.mobilefork.hermesagent.data

import android.content.Context
import com.chaquo.python.Python
import com.mobilefork.hermesagent.BuildConfig
import com.mobilefork.hermesagent.HermesApplication
import java.io.File
import org.json.JSONObject

enum class McpRuntimePhase { DISABLED, NEEDS_RUNTIME, READY, PARTIAL, FAILED, RESTART_REQUIRED }

data class McpRuntimeStatus(
    val phase: McpRuntimePhase = McpRuntimePhase.DISABLED,
    val connectedServers: Int = 0,
    val totalServers: Int = 0,
    val tools: Int = 0,
) {
    companion object {
        fun fromJson(raw: String): McpRuntimeStatus {
            val json = JSONObject(raw)
            if (json.optBoolean("requires_app_restart")) return McpRuntimeStatus(McpRuntimePhase.RESTART_REQUIRED)
            if (json.optString("reason") in setOf("python_agent_runtime_not_running", "owned_mcp_runtime_not_ready")) {
                return McpRuntimeStatus(McpRuntimePhase.NEEDS_RUNTIME)
            }
            if (!json.optBoolean("enabled")) return McpRuntimeStatus(McpRuntimePhase.DISABLED)
            if (!json.optBoolean("synced")) return McpRuntimeStatus(McpRuntimePhase.FAILED)
            val servers = json.optJSONArray("servers") ?: return McpRuntimeStatus(McpRuntimePhase.FAILED)
            val count = servers.length()
            if (count > 8 || json.optInt("server_count", -1) != count) return McpRuntimeStatus(McpRuntimePhase.FAILED)
            val connected = (0 until count).count { servers.optJSONObject(it)?.optBoolean("connected") == true }
            val phase = if (connected == count) McpRuntimePhase.READY else McpRuntimePhase.PARTIAL
            return McpRuntimeStatus(phase, connected, count, json.optInt("tool_count").coerceAtLeast(0))
        }
    }
}

object McpRuntimeBridge {
    @JvmStatic
    fun externalMcpAllowed(): Boolean = !BuildConfig.HERMES_PLAY_EDITION &&
        McpSettingsStore(HermesApplication.instance).externalMcpAllowed()

    fun currentStatus(context: Context): McpRuntimeStatus {
        if (BuildConfig.HERMES_PLAY_EDITION || !McpSettingsStore(context).externalMcpAllowed()) return McpRuntimeStatus()
        if (!Python.isStarted()) return McpRuntimeStatus(McpRuntimePhase.NEEDS_RUNTIME)
        return runCatching {
            McpRuntimeStatus.fromJson(Python.getInstance().getModule("hermes_android.mcp_bridge")
                .callAttr("current_android_mcp_status").toString())
        }.getOrElse { McpRuntimeStatus(McpRuntimePhase.FAILED) }
    }

    fun reloadIntoRuntime(context: Context): McpRuntimeStatus {
        if (BuildConfig.HERMES_PLAY_EDITION) return McpRuntimeStatus()
        if (!Python.isStarted()) {
            return McpRuntimeStatus(
                if (McpSettingsStore(context).externalMcpAllowed()) McpRuntimePhase.NEEDS_RUNTIME else McpRuntimePhase.DISABLED,
            )
        }
        val hermesHome = File(context.applicationContext.filesDir, "hermes-home").absolutePath
        return runCatching {
            val result = Python.getInstance()
                .getModule("hermes_android.mcp_bridge")
                .callAttr("reload_android_mcp_config", hermesHome)
                .toString()
            McpRuntimeStatus.fromJson(result)
        }.getOrElse { McpRuntimeStatus(McpRuntimePhase.FAILED) }
    }
}
