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
        val appContext = context.applicationContext
        // Route Termux-style host package manager before spawning a shell.
        if (HermesTermuxPackageManager.isPkgCommand(command)) {
            val pkgResult = HermesTermuxPackageManager.performCliCommand(appContext, command)
            val state = HermesLinuxSubsystemBridge.ensureInstalled(appContext)
            val message = pkgResult.optString("message")
                .ifBlank { pkgResult.optString("error") }
                .ifBlank { pkgResult.toString() }
            val result = JSONObject()
                .put("exit_code", pkgResult.optInt("exit_code", if (pkgResult.optBoolean("ok", false)) 0 else 1))
                .put("output", message + "\n" + pkgResult.toString(2))
                .put("error", if (pkgResult.optBoolean("ok", false)) "" else pkgResult.optString("error"))
                .put("cwd", state.optString("home_path"))
                .put("shell", "hermes-pkg")
                .put("execution_mode", "host_pkg_manager")
                .put("uses_termux", state.optBoolean("uses_termux", false))
                .put("host_pkg_result", pkgResult)
                .put(
                    "package_manager_status",
                    "hermes_host_pkg",
                )
                .put(
                    "package_management_hint",
                    "Host suite packages use Hermes pkg (Termux main mirrors). " +
                        "Guest sandboxes use linux_sandbox_tool action=update (apt/apk).",
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

        val state = HermesLinuxSubsystemBridge.ensureInstalled(appContext)
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

        val process = runCatching {
            ProcessBuilder(shellInvocation(shellPath, effectiveCommand))
                .directory(homeDir)
                .apply {
                    environment().putAll(environment)
                }
                .start()
        }.getOrElse { launchError ->
            val detail = launchError.message ?: launchError.javaClass.simpleName
            val permissionDenied = detail.contains("permission denied", ignoreCase = true) ||
                detail.contains("EACCES", ignoreCase = true)
            val exitCode = if (permissionDenied) 126 else 1
            val result = JSONObject()
                .put("exit_code", exitCode)
                .put("output", "")
                .put(
                    "error",
                    if (permissionDenied) {
                        "$detail\n${executionDeniedHint(state, command)}"
                    } else {
                        detail
                    },
                )
                .put("cwd", homeDir.absolutePath)
                .put("shell", shellPath)
                .put("execution_mode", state.optString("execution_mode"))
                .put("uses_termux", state.optBoolean("uses_termux", false))
                .put("native_execution_route", state.optString("native_execution_route"))
                .put("execution_launch_failed", true)
            if (permissionDenied) {
                result.put("execution_denial_hint", executionDeniedHint(state, command))
            }
            return result
        }

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
            .put("native_execution_route", state.optString("native_execution_route"))
            .put("native_direct_command_count", state.optInt("native_direct_command_count", 0))
            .put("available_package_count", state.optJSONArray("packages")?.length() ?: 0)
            .put(
                "package_manager_status",
                if (state.optBoolean("uses_termux", false)) "embedded_prefix_packages_available" else "android_system_shell_fallback",
            )
            .put(
                "package_management_hint",
                if (state.optBoolean("uses_termux", false)) {
                    "Host suite: use pkg list/search for discovery; package changes require a signed Hermes APK. " +
                        "Guest sandboxes: linux_sandbox_tool action=update (apt/apk). " +
                        "Packaged prefix commands are on PATH; proot-distro catalog is in downloadable_linux_sandboxes."
                } else {
                    "Embedded package prefix is unavailable; this run used Android's system shell only."
                },
            )
        if (exitCode == 126) {
            val hint = executionDeniedHint(state, command)
            result.put("error", listOf(error.trim(), hint).filter { it.isNotBlank() }.joinToString("\n"))
            result.put("execution_denial_hint", hint)
            result.put("android_exec_policy", state.optString("android_exec_policy"))
        }
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
        if (state.optString("execution_mode") == "android_system_shell") {
            return "/system/bin/sh"
        }
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

    internal fun executionDeniedHint(state: JSONObject, command: String): String {
        val route = state.optString("native_execution_route").ifBlank { "unknown" }
        val prefix = state.optString("prefix_path")
        val mentionsWritablePrefix = prefix.isNotBlank() && command.contains(prefix)
        val routeDetail = if (mentionsWritablePrefix) {
            "The command names a writable prefix path directly."
        } else {
            "The selected executable route was $route."
        }
        return "Android found the command but refused to execute it. $routeDetail " +
            "Downloaded ELF files in app data cannot be made executable with chmod on Android 10+. " +
            "Use the packaged command name or update Hermes so it can repair the APK-native route; " +
            "do not grant broad storage permission or retry chmod."
    }
}
