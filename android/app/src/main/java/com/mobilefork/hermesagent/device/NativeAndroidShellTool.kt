package com.mobilefork.hermesagent.device

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object NativeAndroidShellTool {
    fun run(
        context: Context,
        command: String,
        timeoutSeconds: Long = 60,
        includeLinuxSandboxStatus: Boolean = true,
    ): JSONObject {
        val state = HermesLinuxSubsystemBridge.ensureInstalled(context.applicationContext)
        val homeDir = File(state.getString("home_path")).apply { mkdirs() }
        val tmpDir = File(state.getString("tmp_path")).apply { mkdirs() }
        val shellPath = resolveShellPath(state)
        val effectiveCommand = HermesLinuxSubsystemBridge.commandWithEmbeddedToolAliases(state, command)
        val environment = HermesLinuxSubsystemBridge.buildRunEnvironment(state).toMutableMap().apply {
            this["HOME"] = homeDir.absolutePath
            this["TMPDIR"] = tmpDir.absolutePath
            this["PATH"] = listOf(
                state.optString("bin_path"),
                "/system/bin",
                "/system/xbin",
            )
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(":")
        }

        val process = ProcessBuilder(shellInvocation(shellPath, effectiveCommand))
            .directory(homeDir)
            .apply {
                environment().putAll(environment)
            }
            .start()

        val executor = Executors.newFixedThreadPool(2)
        val stdout = executor.submit(Callable {
            process.inputStream.bufferedReader().use { it.readText() }
        })
        val stderr = executor.submit(Callable {
            process.errorStream.bufferedReader().use { it.readText() }
        })

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroy()
        }
        val exitCode = if (completed) process.exitValue() else 124
        val output = stdout.get(1, TimeUnit.SECONDS)
        val error = stderr.get(1, TimeUnit.SECONDS)
        executor.shutdownNow()

        val result = JSONObject()
            .put("exit_code", exitCode)
            .put("output", output)
            .put("error", error)
            .put("cwd", homeDir.absolutePath)
            .put("shell", shellPath)
            .put("execution_mode", state.optString("execution_mode"))
            .put("uses_termux", state.optBoolean("uses_termux", false))
            .put("available_package_count", state.optJSONArray("packages")?.length() ?: 0)
            .put(
                "package_manager_status",
                if (state.optBoolean("uses_termux", false)) "embedded_prefix_packages_available" else "android_system_shell_fallback",
            )
            .put(
                "package_management_hint",
                if (state.optBoolean("uses_termux", false)) {
                    "Use packaged prefix commands from PATH. For downloadable Linux sandboxes, use the proot-distro-compatible catalog in downloadable_linux_sandboxes and keep rootfs data in app-private storage."
                } else {
                    "Embedded package prefix is unavailable; this run used Android's system shell only."
                },
            )
        if (includeLinuxSandboxStatus) {
            result
                .put("downloadable_linux_sandboxes", HermesLinuxSandboxCatalog.distroCatalog())
                .put("recommended_linux_sandboxes", HermesLinuxSandboxCatalog.recommendedSandboxIds())
                .put("desktop_environment_catalog", HermesLinuxSandboxCatalog.desktopCatalog())
                .put("linux_sandbox_agent_summary", HermesLinuxSandboxCatalog.agentSummary())
                .put("linux_sandbox_status", HermesLinuxSandboxBridge.status(state))
        }
        return result
    }

    internal fun resolveShellPath(state: JSONObject): String {
        val configured = state.optString("shell_path", state.optString("bash_path")).trim()
        if (configured.startsWith("/system/")) {
            return configured
        }
        if (configured.isNotBlank()) {
            val shellFile = File(configured)
            if (shellFile.isFile && shellFile.canExecute()) {
                return shellFile.absolutePath
            }
        }
        return "/system/bin/sh"
    }

    internal fun shellInvocation(shellPath: String, command: String): List<String> {
        val shellName = File(shellPath).name.lowercase()
        val commandFlag = if (shellName.contains("bash")) "-lc" else "-c"
        return listOf(shellPath, commandFlag, command)
    }
}
