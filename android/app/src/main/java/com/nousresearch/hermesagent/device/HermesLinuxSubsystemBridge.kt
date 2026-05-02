package com.nousresearch.hermesagent.device

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object HermesLinuxSubsystemBridge {
    private const val STATE_FILE_NAME = "linux-subsystem-state.json"
    private const val EXECUTION_MODE = "android_system_shell"
    private const val SYSTEM_SHELL = "/system/bin/sh"

    fun ensureInstalled(context: Context): JSONObject {
        val androidAbi = selectAndroidAbi()
        readState(context)?.let { state ->
            if (
                state.optString("android_abi") != androidAbi ||
                state.optString("execution_mode") != EXECUTION_MODE
            ) {
                reset(context)
                return@let
            }
            ensureWritableDirs(state)
            val shellFile = File(state.optString("shell_path", state.optString("bash_path", SYSTEM_SHELL)))
            if (shellFile.isFile && shellFile.canExecute()) {
                return state
            }
        }

        val hermesHome = File(context.filesDir, "hermes-home")
        val prefixDir = File(hermesHome, "native-shell")
        val homeDir = File(hermesHome, "workspace")
        val tmpDir = File(context.cacheDir, "hermes-tmp")
        prefixDir.mkdirs()
        homeDir.mkdirs()
        tmpDir.mkdirs()

        val state = JSONObject().apply {
            put("enabled", true)
            put("execution_mode", EXECUTION_MODE)
            put("android_abi", androidAbi)
            put("uses_termux", false)
            put("prefix_path", prefixDir.absolutePath)
            put("shell_path", SYSTEM_SHELL)
            put("bash_path", SYSTEM_SHELL)
            put("bin_path", "/system/bin")
            put("lib_path", "")
            put("home_path", homeDir.absolutePath)
            put("tmp_path", tmpDir.absolutePath)
            put("packages", JSONArray(listOf("android-system-shell", "toybox")))
        }
        stateFile(context).apply {
            parentFile?.mkdirs()
            writeText(state.toString(), Charsets.UTF_8)
        }
        return state
    }

    fun readState(context: Context): JSONObject? {
        val stateFile = stateFile(context)
        if (!stateFile.isFile) {
            return null
        }
        val rawState = stateFile.readText(Charsets.UTF_8).trim()
        if (rawState.isBlank()) {
            stateFile.delete()
            return null
        }
        return runCatching { JSONObject(rawState) }.getOrElse {
            stateFile.delete()
            null
        }
    }

    fun reset(context: Context) {
        File(context.filesDir, "hermes-home/linux").deleteRecursively()
        File(context.filesDir, "hermes-home/native-shell").deleteRecursively()
    }

    private fun stateFile(context: Context): File {
        return File(context.filesDir, "hermes-home/linux/$STATE_FILE_NAME")
    }

    private fun selectAndroidAbi(): String {
        val supportedAbis = Build.SUPPORTED_ABIS.toList()
        return supportedAbis.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
            ?: supportedAbis.firstOrNull()
            ?: "arm64-v8a"
    }

    private fun ensureWritableDirs(state: JSONObject) {
        listOf("prefix_path", "home_path", "tmp_path").forEach { key ->
            state.optString(key).takeIf { it.isNotBlank() }?.let { File(it).mkdirs() }
        }
    }
}
