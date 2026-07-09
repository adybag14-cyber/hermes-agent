package com.mobilefork.hermesagent.data

import android.content.Context
import com.chaquo.python.Python
import java.io.File

object McpRuntimeBridge {
    fun reloadIntoRuntime(context: Context): String? {
        if (!Python.isStarted()) {
            return null
        }
        val hermesHome = File(context.applicationContext.filesDir, "hermes-home").absolutePath
        return runCatching {
            Python.getInstance()
                .getModule("hermes_android.mcp_bridge")
                .callAttr("reload_android_mcp_config", hermesHome)
                .toString()
        }.getOrNull()
    }
}