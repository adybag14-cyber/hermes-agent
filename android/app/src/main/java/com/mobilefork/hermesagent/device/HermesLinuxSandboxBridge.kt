package com.mobilefork.hermesagent.device

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object HermesLinuxSandboxBridge {
    private const val DEFAULT_TIMEOUT_SECONDS = 900L
    private const val RUN_TIMEOUT_SECONDS = 120L

    fun performAction(
        context: Context,
        action: String,
        distroId: String = "",
        name: String = "",
        image: String = "",
        command: String = "",
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): JSONObject {
        val state = HermesLinuxSubsystemBridge.ensureInstalled(context.applicationContext)
        val normalizedDistroId = normalizeArgumentValue(distroId)
        val normalizedName = normalizeArgumentValue(name)
        val normalizedImage = normalizeArgumentValue(image)
        return when (normalizeAction(action)) {
            "catalog" -> catalog(state)
            "status", "list" -> status(state)
            "install" -> install(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                image = normalizedImage,
                timeoutSeconds = timeoutSeconds,
            )
            "run" -> runCommand(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                command = command,
                timeoutSeconds = timeoutSeconds,
            )
            "remove" -> remove(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                timeoutSeconds = timeoutSeconds,
            )
            else -> status(state)
                .put("exit_code", 2)
                .put("error", "linux_sandbox_tool action must be catalog, status, list, install, run, or remove.")
        }
    }

    fun status(state: JSONObject): JSONObject {
        val qemuUserPath = qemuPathForState(state)
        return JSONObject()
            .put("exit_code", 0)
            .put("execution_mode", state.optString("execution_mode"))
            .put("uses_termux", state.optBoolean("uses_termux", false))
            .put("proot_available", hasPackage(state, "proot"))
            .put("proot_distro_available", hasPackage(state, "proot-distro"))
            .put("qemu_user_available", qemuUserPath.isNotBlank())
            .put("qemu_user_path", qemuUserPath)
            .put("python_available", hasPackage(state, "python"))
            .put("runtime_dir", runtimeDir(state).absolutePath)
            .put("containers_dir", containersDir(state).absolutePath)
            .put("installed_sandboxes", installedSandboxes(state))
            .put("downloadable_linux_sandboxes", HermesLinuxSandboxCatalog.distroCatalog())
            .put("recommended_linux_sandboxes", HermesLinuxSandboxCatalog.recommendedSandboxIds())
            .put("desktop_environment_catalog", HermesLinuxSandboxCatalog.desktopCatalog())
            .put("linux_sandbox_agent_summary", HermesLinuxSandboxCatalog.agentSummary())
            .put(
                "status",
                if (state.optBoolean("uses_termux", false) && hasPackage(state, "proot-distro")) {
                    "ready"
                } else {
                    "embedded_sandbox_packages_unavailable"
                },
            )
            .put(
                "agent_usage_hint",
                "Use linux_sandbox_tool action=install with distro_id=alpine-3-21 or debian-bookworm, then action=run with name and command. Terminal commands can also use proot-distro directly.",
            )
    }

    private fun catalog(state: JSONObject): JSONObject {
        return status(state).put("action", "catalog")
    }

    private fun install(
        context: Context,
        state: JSONObject,
        distroId: String,
        name: String,
        image: String,
        timeoutSeconds: Long,
    ): JSONObject {
        val selected = selectDistro(distroId = distroId, name = name, image = image)
        val sandboxName = name.ifBlank { selected.optString("name") }
        val imageRef = image.ifBlank { selected.optString("image") }
        if (imageRef.isBlank() || sandboxName.isBlank()) {
            return status(state)
                .put("exit_code", 2)
                .put("error", "install requires a known distro_id, image, or name.")
        }
        val command = installCommandFor(sandboxName = sandboxName, imageRef = imageRef)
        return runProotDistroCommand(
            context = context,
            state = state,
            action = "install",
            command = command,
            timeoutSeconds = timeoutSeconds.coerceIn(60, DEFAULT_TIMEOUT_SECONDS),
        ).put("sandbox_name", sandboxName)
            .put("image", imageRef)
            .put("distro_id", selected.optString("id"))
    }

    private fun runCommand(
        context: Context,
        state: JSONObject,
        distroId: String,
        name: String,
        command: String,
        timeoutSeconds: Long,
    ): JSONObject {
        val selected = selectDistro(distroId = distroId, name = name, image = "")
        val sandboxName = name.ifBlank { selected.optString("name") }
        if (sandboxName.isBlank()) {
            return runErrorResult(state = state, sandboxName = sandboxName, selected = selected, command = command, exitCode = 2)
                .put("exit_code", 2)
                .put("error", "run requires a sandbox name or known distro_id.")
        }
        if (command.isBlank()) {
            return runErrorResult(state = state, sandboxName = sandboxName, selected = selected, command = command, exitCode = 2)
                .put("exit_code", 2)
                .put("error", "run requires a command.")
        }
        val rootfsDir = File(File(containersDir(state), sandboxName), "rootfs")
        if (!rootfsDir.isDirectory) {
            return runErrorResult(state = state, sandboxName = sandboxName, selected = selected, command = command, exitCode = 2)
                .put("exit_code", 2)
                .put("error", "container '$sandboxName' is not installed.")
        }
        val qemuUserPath = qemuPathForState(state)
        if (qemuUserPath.isBlank()) {
            return runErrorResult(state = state, sandboxName = sandboxName, selected = selected, command = command, exitCode = 127)
                .put("exit_code", 127)
                .put("error", "run requires packaged qemu-user support for Android app-process execution.")
        }
        val shellCommand = runCommandFor(
            prefixPath = state.optString("prefix_path"),
            sandboxName = sandboxName,
            command = command,
            qemuPath = qemuUserPath,
        )
        val qemuResult = runProotDistroCommand(
            context = context,
            state = state,
            action = "run",
            command = shellCommand,
            timeoutSeconds = timeoutSeconds.coerceIn(5, DEFAULT_TIMEOUT_SECONDS).takeIf { timeoutSeconds != DEFAULT_TIMEOUT_SECONDS }
                ?: RUN_TIMEOUT_SECONDS,
            includeStatus = false,
        )
        val result = if (qemuResult.optInt("exit_code", -1) == 0) {
            qemuResult
        } else {
            runProotDistroCommand(
                context = context,
                state = state,
                action = "run",
                command = nativePrefixWorkspaceCommandFor(
                    prefixPath = state.optString("prefix_path"),
                    sandboxName = sandboxName,
                    command = command,
                    binPath = state.optString("bin_path"),
                ),
                timeoutSeconds = timeoutSeconds.coerceIn(5, DEFAULT_TIMEOUT_SECONDS).takeIf { timeoutSeconds != DEFAULT_TIMEOUT_SECONDS }
                    ?: RUN_TIMEOUT_SECONDS,
                includeStatus = false,
            )
        }
        return compactRunResult(
            state = state,
            result = result,
            selected = selected,
            sandboxName = sandboxName,
            command = command,
            rootfsDir = rootfsDir,
            qemuUserPath = qemuUserPath,
            sandboxExecutionMode = if (qemuResult.optInt("exit_code", -1) == 0) {
                "qemu_user_direct"
            } else {
                "native_prefix_workspace"
            },
            qemuExitCode = qemuResult.optInt("exit_code", -1),
            qemuError = qemuResult.optString("error"),
        )
    }

    private fun remove(
        context: Context,
        state: JSONObject,
        distroId: String,
        name: String,
        timeoutSeconds: Long,
    ): JSONObject {
        val selected = selectDistro(distroId = distroId, name = name, image = "")
        val sandboxName = name.ifBlank { selected.optString("name") }
        if (sandboxName.isBlank()) {
            return status(state)
                .put("exit_code", 2)
                .put("error", "remove requires a sandbox name or known distro_id.")
        }
        val command = removeCommandFor(sandboxName = sandboxName)
        return runProotDistroCommand(
            context = context,
            state = state,
            action = "remove",
            command = command,
            timeoutSeconds = timeoutSeconds.coerceIn(10, DEFAULT_TIMEOUT_SECONDS),
        ).put("sandbox_name", sandboxName)
            .put("distro_id", selected.optString("id"))
    }

    private fun runProotDistroCommand(
        context: Context,
        state: JSONObject,
        action: String,
        command: String,
        timeoutSeconds: Long,
        includeStatus: Boolean = true,
    ): JSONObject {
        if (!state.optBoolean("uses_termux", false) || !hasPackage(state, "proot-distro")) {
            return status(state)
                .put("exit_code", 127)
                .put("action", action)
                .put("error", "Embedded proot-distro packages are not available in this APK build.")
        }
        val result = NativeAndroidShellTool.run(
            context = context.applicationContext,
            command = command,
            timeoutSeconds = timeoutSeconds,
            includeLinuxSandboxStatus = includeStatus,
        )
        result.put("action", action)
        if (includeStatus) {
            result.put("linux_sandbox_status", status(state))
        }
        return result
    }

    private fun compactRunResult(
        state: JSONObject,
        result: JSONObject,
        selected: JSONObject,
        sandboxName: String,
        command: String,
        rootfsDir: File,
        qemuUserPath: String,
        sandboxExecutionMode: String,
        qemuExitCode: Int,
        qemuError: String,
    ): JSONObject {
        val output = JSONObject()
            .put("exit_code", result.optInt("exit_code", -1))
            .put("action", "run")
            .put("sandbox_name", sandboxName)
            .put("distro_id", selected.optString("id"))
            .put("sandbox_command", command)
            .put("sandbox_execution_mode", sandboxExecutionMode)
            .put("rootfs_path", rootfsDir.absolutePath)
            .put("qemu_user_available", qemuUserPath.isNotBlank())
            .put("qemu_user_path", qemuUserPath)
            .put("output", result.optString("output"))
            .put("error", result.optString("error"))
            .put("cwd", result.optString("cwd"))
            .put("shell", result.optString("shell"))
            .put("execution_mode", result.optString("execution_mode"))
            .put("uses_termux", result.optBoolean("uses_termux", state.optBoolean("uses_termux", false)))
        if (sandboxExecutionMode != "qemu_user_direct") {
            output
                .put("fallback_from", "qemu_user_direct")
                .put("qemu_exit_code", qemuExitCode)
                .put("qemu_error", qemuError)
                .put(
                    "sandbox_execution_note",
                    "Android app-process seccomp blocked downloaded rootfs guest execution, so Hermes ran the command with packaged native Linux tools in the sandbox rootfs working directory.",
                )
        }
        return output
    }

    private fun runErrorResult(
        state: JSONObject,
        sandboxName: String,
        selected: JSONObject,
        command: String,
        exitCode: Int,
    ): JSONObject {
        val qemuUserPath = qemuPathForState(state)
        return JSONObject()
            .put("exit_code", exitCode)
            .put("action", "run")
            .put("sandbox_name", sandboxName)
            .put("distro_id", selected.optString("id"))
            .put("sandbox_command", command)
            .put("sandbox_execution_mode", "qemu_user_direct")
            .put("qemu_user_available", qemuUserPath.isNotBlank())
            .put("qemu_user_path", qemuUserPath)
            .put("execution_mode", state.optString("execution_mode"))
            .put("uses_termux", state.optBoolean("uses_termux", false))
    }

    private fun normalizeAction(action: String): String {
        return action.trim().trim('.', ',', ':', ';').lowercase().ifBlank { "status" }
    }

    internal fun normalizeArgumentValue(value: String): String {
        return value.trim().trim('.', ',', ':', ';')
    }

    internal fun installCommandFor(sandboxName: String, imageRef: String): String {
        return "proot-distro install --name ${HermesLinuxSubsystemBridge.shellQuote(sandboxName)} ${HermesLinuxSubsystemBridge.shellQuote(imageRef)}"
    }

    internal fun runCommandFor(prefixPath: String, sandboxName: String, command: String, qemuPath: String = ""): String {
        val normalizedPrefixPath = prefixPath.trimEnd('/')
        val rootfsPath = "$normalizedPrefixPath/var/lib/proot-distro/containers/$sandboxName/rootfs"
        if (qemuPath.isNotBlank()) {
            return qemuDirectCommandFor(rootfsPath = rootfsPath, command = command, qemuPath = qemuPath)
        }
        val guestPath = "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export PATH; $command"
        return "proot -w / -r ${HermesLinuxSubsystemBridge.shellQuote(rootfsPath)} " +
            "-b /dev -b /proc -b /sys " +
            "/bin/sh -lc ${HermesLinuxSubsystemBridge.shellQuote(guestPath)}"
    }

    internal fun qemuDirectCommandFor(rootfsPath: String, command: String, qemuPath: String): String {
        val normalizedRootfsPath = rootfsPath.trimEnd('/')
        val guestPath = listOf(
            "$normalizedRootfsPath/usr/local/sbin",
            "$normalizedRootfsPath/usr/local/bin",
            "$normalizedRootfsPath/usr/sbin",
            "$normalizedRootfsPath/usr/bin",
            "$normalizedRootfsPath/sbin",
            "$normalizedRootfsPath/bin",
        ).joinToString(":")
        val guestScript = "ROOTFS=${HermesLinuxSubsystemBridge.shellQuote(normalizedRootfsPath)}; " +
            "PATH=${HermesLinuxSubsystemBridge.shellQuote(guestPath)}; " +
            "HOME=${HermesLinuxSubsystemBridge.shellQuote("$normalizedRootfsPath/root")}; " +
            "TMPDIR=${HermesLinuxSubsystemBridge.shellQuote("$normalizedRootfsPath/tmp")}; " +
            "BUSYBOX=${HermesLinuxSubsystemBridge.shellQuote("$normalizedRootfsPath/bin/busybox")}; " +
            "export PATH HOME TMPDIR; " +
            busyboxAliasPrelude() +
            command
        return "ROOTFS=${HermesLinuxSubsystemBridge.shellQuote(normalizedRootfsPath)}; " +
            "QEMU=${HermesLinuxSubsystemBridge.shellQuote(qemuPath)}; " +
            "GUEST_SCRIPT=${HermesLinuxSubsystemBridge.shellQuote(guestScript)}; " +
            "cd \"\$ROOTFS\" && " +
            "if [ -f \"\$ROOTFS/bin/busybox\" ]; then " +
            "QEMU_LD_PREFIX=\"\$ROOTFS\" \"\$QEMU\" -L \"\$ROOTFS\" \"\$ROOTFS/bin/busybox\" sh -lc \"\$GUEST_SCRIPT\"; " +
            "else " +
            "QEMU_LD_PREFIX=\"\$ROOTFS\" \"\$QEMU\" -L \"\$ROOTFS\" \"\$ROOTFS/bin/sh\" -lc \"\$GUEST_SCRIPT\"; " +
            "fi"
    }

    internal fun nativePrefixWorkspaceCommandFor(
        prefixPath: String,
        sandboxName: String,
        command: String,
        binPath: String = "",
    ): String {
        val normalizedPrefixPath = prefixPath.trimEnd('/')
        val rootfsPath = "$normalizedPrefixPath/var/lib/proot-distro/containers/$sandboxName/rootfs"
        val sandboxPath = listOf(
            binPath,
            "$normalizedPrefixPath/bin",
            "/system/bin",
            "/system/xbin",
        )
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(":")
        return "SANDBOX_ROOTFS=${HermesLinuxSubsystemBridge.shellQuote(rootfsPath)}; " +
            "export SANDBOX_ROOTFS HERMES_SANDBOX_ROOTFS=\"\$SANDBOX_ROOTFS\"; " +
            "export HOME=\"\$SANDBOX_ROOTFS/root\" TMPDIR=${HermesLinuxSubsystemBridge.shellQuote("$normalizedPrefixPath/tmp")}; " +
            "export PATH=${HermesLinuxSubsystemBridge.shellQuote(sandboxPath)}; " +
            "cd \"\$SANDBOX_ROOTFS\" && " +
            command
    }

    private fun busyboxAliasPrelude(): String {
        val applets = listOf(
            "awk",
            "cat",
            "chmod",
            "cp",
            "date",
            "df",
            "du",
            "echo",
            "env",
            "grep",
            "head",
            "id",
            "ln",
            "ls",
            "mkdir",
            "mv",
            "ps",
            "pwd",
            "rm",
            "rmdir",
            "sed",
            "sh",
            "sleep",
            "tail",
            "tar",
            "touch",
            "uname",
            "whoami",
        )
        return "if [ -f \"\$BUSYBOX\" ]; then " +
            applets.joinToString("; ") { "alias $it=\"\$BUSYBOX $it\"" } +
            "; fi; "
    }

    internal fun removeCommandFor(sandboxName: String): String {
        return "proot-distro remove ${HermesLinuxSubsystemBridge.shellQuote(sandboxName)}"
    }

    private fun selectDistro(distroId: String, name: String, image: String): JSONObject {
        return HermesLinuxSandboxCatalog.findDistro(distroId)
            ?: HermesLinuxSandboxCatalog.findDistro(name)
            ?: HermesLinuxSandboxCatalog.findDistro(image)
            ?: JSONObject()
    }

    private fun installedSandboxes(state: JSONObject): JSONArray {
        val containers = containersDir(state)
        val result = JSONArray()
        containers.listFiles()
            ?.filter { File(it, "rootfs").isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { container ->
                result.put(
                    JSONObject()
                        .put("name", container.name)
                        .put("path", container.absolutePath)
                        .put("rootfs_path", File(container, "rootfs").absolutePath)
                        .put("manifest_available", File(container, "manifest.json").isFile),
                )
            }
        return result
    }

    private fun runtimeDir(state: JSONObject): File {
        return File(state.optString("prefix_path"), "var/lib/proot-distro")
    }

    private fun containersDir(state: JSONObject): File {
        return File(runtimeDir(state), "containers")
    }

    private fun hasPackage(state: JSONObject, name: String): Boolean {
        val packages = state.optJSONArray("packages") ?: return false
        for (index in 0 until packages.length()) {
            val item = packages.optJSONObject(index) ?: continue
            if (item.optString("name") == name) {
                return true
            }
        }
        return false
    }

    private fun qemuPathForState(state: JSONObject): String {
        val qemuName = when (state.optString("android_abi")) {
            "arm64-v8a" -> "qemu-aarch64"
            "x86_64" -> "qemu-x86_64"
            else -> ""
        }
        if (qemuName.isBlank()) {
            return ""
        }
        val nativeBinPath = state.optString("native_bin_path")
        val nativeQemu = File(nativeBinPath, qemuName)
        return nativeQemu.absolutePath.takeIf { nativeQemu.canExecute() }.orEmpty()
    }
}
