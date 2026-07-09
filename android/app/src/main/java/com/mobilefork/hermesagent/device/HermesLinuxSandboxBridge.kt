package com.mobilefork.hermesagent.device

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object HermesLinuxSandboxBridge {
    private const val DEFAULT_TIMEOUT_SECONDS = 900L
    private const val RUN_TIMEOUT_SECONDS = 120L
    private const val AGENT_CONTROL_FILE_NAME = "hermes-agent-shell-control.json"

    fun performAction(
        context: Context,
        action: String,
        distroId: String = "",
        name: String = "",
        image: String = "",
        command: String = "",
        mirrorProfile: String = "",
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): JSONObject {
        val state = HermesLinuxSubsystemBridge.ensureInstalled(context.applicationContext)
        val normalizedDistroId = normalizeArgumentValue(distroId)
        val normalizedName = normalizeArgumentValue(name)
        val normalizedImage = normalizeArgumentValue(image)
        val normalizedMirrorProfile = normalizeArgumentValue(mirrorProfile)
        return when (normalizeAction(action)) {
            "catalog" -> catalog(state, context)
            "status", "list" -> status(state, context)
            "download", "install" -> install(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                image = normalizedImage,
                timeoutSeconds = timeoutSeconds,
            )
            "update", "upgrade", "refresh" -> updateSandbox(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                timeoutSeconds = timeoutSeconds,
            )
            "deploy", "bootstrap", "one_click_deploy", "one-click-deploy" -> deploySandbox(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                mirrorProfile = normalizedMirrorProfile,
                timeoutSeconds = timeoutSeconds,
            )
            "set_mirror", "switch_mirror", "mirror" -> setMirror(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                mirrorProfile = normalizedMirrorProfile,
                timeoutSeconds = timeoutSeconds,
            )
            "start", "launch", "enable" -> startAgentShell(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
            )
            "stop", "close", "disable" -> stopAgentShell(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
            )
            "run" -> runCommand(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                command = command,
                timeoutSeconds = timeoutSeconds,
            )
            "remove", "uninstall", "delete" -> remove(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                timeoutSeconds = timeoutSeconds,
            )
            else -> status(state, context)
                .put("exit_code", 2)
                .put("error", "linux_sandbox_tool action must be catalog, status, list, download/install, deploy, update, set_mirror, start, stop, run, or uninstall/remove.")
        }
    }

    fun status(state: JSONObject, context: Context? = null): JSONObject {
        val qemuUserPath = qemuPathForState(state)
        val control = context?.let { readAgentControl(it) } ?: defaultAgentControl()
        val installed = installedSandboxes(state)
        // With zero installed sandboxes, do not report AI shell as enabled — it misleads the Device UI.
        val agentShellEnabled = if (installed.length() == 0) {
            false
        } else {
            control.optBoolean("agent_shell_enabled", false)
        }
        return JSONObject()
            .put("exit_code", 0)
            .put("execution_mode", state.optString("execution_mode"))
            .put("uses_termux", state.optBoolean("uses_termux", false))
            .put("proot_available", hasPackage(state, "proot"))
            .put("proot_distro_available", hasPackage(state, "proot-distro"))
            .put("qemu_user_available", qemuUserPath.isNotBlank())
            .put("qemu_user_path", qemuUserPath)
            .put("python_available", hasPackage(state, "python"))
            .put("app_private_storage_root", context?.let { appPrivateStorageRoot(it).absolutePath }.orEmpty())
            .put("agent_control_file", context?.let { agentControlFile(it).absolutePath }.orEmpty())
            .put("agent_shell_enabled", agentShellEnabled)
            .put("active_sandbox_name", control.optString("active_sandbox_name"))
            .put("active_distro_id", control.optString("active_distro_id"))
            .put(
                "agent_shell_policy",
                if (agentShellEnabled) {
                    "enabled: AI agents may use linux_sandbox_tool/mcp_run_in_proot when a sandbox is installed."
                } else if (installed.length() == 0) {
                    "disabled: no proot sandbox is installed yet. Use action=deploy or download first."
                } else {
                    "disabled: AI agents must not run proot sandbox commands until action=start is called."
                },
            )
            .put("runtime_dir", runtimeDir(state).absolutePath)
            .put("containers_dir", containersDir(state).absolutePath)
            .put("installed_sandboxes", installed)
            .put("downloadable_linux_sandboxes", HermesLinuxSandboxCatalog.distroCatalog())
            .put("recommended_linux_sandboxes", HermesLinuxSandboxCatalog.recommendedSandboxIds())
            .put("mirror_profiles", HermesLinuxSandboxCatalog.mirrorProfiles())
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
                "Use linux_sandbox_tool action=deploy for one-click Debian sandbox setup, action=download with distro_id=alpine-3-21 or debian-bookworm, action=set_mirror with mirror_profile=china|aliyun|tsinghua to switch domestic package sources, action=start to allow agent use, action=run with name and command, action=update to refresh packages, action=stop/close to prevent agent shell use, or action=uninstall to remove it. mcp_run_in_proot is an alias for action=run. Do not claim /system/bin/sh cannot update when a proot sandbox is available.",
            )
    }

    private fun catalog(state: JSONObject, context: Context): JSONObject {
        return status(state, context).put("action", "catalog")
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
            return status(state, context)
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
            .put("app_private_storage_root", appPrivateStorageRoot(context).absolutePath)
            .put("next_actions", JSONArray().put("start").put("run").put("update").put("uninstall"))
    }

    private fun updateSandbox(
        context: Context,
        state: JSONObject,
        distroId: String,
        name: String,
        timeoutSeconds: Long,
    ): JSONObject {
        val selected = selectDistro(distroId = distroId, name = name, image = "")
        val activeName = readAgentControl(context).optString("active_sandbox_name")
        val sandboxName = name.ifBlank { selected.optString("name").ifBlank { activeName } }
        if (sandboxName.isBlank()) {
            return status(state, context)
                .put("exit_code", 2)
                .put("action", "update")
                .put("error", "update requires a sandbox name, known distro_id, or active sandbox.")
        }
        val rootfsDir = File(File(containersDir(state), sandboxName), "rootfs")
        if (!rootfsDir.isDirectory) {
            return status(state, context)
                .put("exit_code", 2)
                .put("action", "update")
                .put("sandbox_name", sandboxName)
                .put("distro_id", selected.optString("id"))
                .put("error", "container '$sandboxName' is not installed.")
        }
        val packageManager = selected.optString("package_manager")
        val updateCommand = updateCommandFor(packageManager)
        return runCommand(
            context = context,
            state = state,
            distroId = distroId,
            name = sandboxName,
            command = updateCommand,
            timeoutSeconds = timeoutSeconds.coerceIn(60, DEFAULT_TIMEOUT_SECONDS),
            respectAgentControl = false,
        ).put("action", "update")
            .put("sandbox_name", sandboxName)
            .put("distro_id", selected.optString("id"))
            .put("package_manager", packageManager)
            .put("update_command", updateCommand)
    }

    private fun deploySandbox(
        context: Context,
        state: JSONObject,
        distroId: String,
        name: String,
        mirrorProfile: String,
        timeoutSeconds: Long,
    ): JSONObject {
        val selected = selectDistro(
            distroId = distroId.ifBlank { "debian-bookworm" },
            name = name.ifBlank { "hermes-debian" },
            image = "",
        )
        val sandboxName = name.ifBlank { selected.optString("name").ifBlank { "hermes-debian" } }
        val rootfsDir = File(File(containersDir(state), sandboxName), "rootfs")
        val installResult = if (!rootfsDir.isDirectory) {
            install(
                context = context,
                state = state,
                distroId = selected.optString("id"),
                name = sandboxName,
                image = selected.optString("image"),
                timeoutSeconds = timeoutSeconds.coerceIn(60, DEFAULT_TIMEOUT_SECONDS),
            )
        } else {
            status(state, context)
                .put("action", "deploy")
                .put("sandbox_name", sandboxName)
                .put("distro_id", selected.optString("id"))
                .put("message", "Sandbox already installed; continuing with start/update.")
        }
        if (installResult.optInt("exit_code", -1) != 0) {
            return installResult.put("action", "deploy")
        }
        val startResult = startAgentShell(
            context = context,
            state = state,
            distroId = selected.optString("id"),
            name = sandboxName,
        )
        if (startResult.optInt("exit_code", -1) != 0) {
            return startResult.put("action", "deploy")
        }
        val mirrorResult = if (mirrorProfile.isNotBlank()) {
            setMirror(
                context = context,
                state = state,
                distroId = selected.optString("id"),
                name = sandboxName,
                mirrorProfile = mirrorProfile,
                timeoutSeconds = timeoutSeconds,
            )
        } else {
            JSONObject().put("exit_code", 0).put("action", "set_mirror").put("skipped", true)
        }
        val updateResult = updateSandbox(
            context = context,
            state = state,
            distroId = selected.optString("id"),
            name = sandboxName,
            timeoutSeconds = timeoutSeconds,
        )
        return status(state, context)
            .put("action", "deploy")
            .put("sandbox_name", sandboxName)
            .put("distro_id", selected.optString("id"))
            .put("install_result", installResult)
            .put("start_result", startResult)
            .put("mirror_result", mirrorResult)
            .put("update_result", updateResult)
            .put("exit_code", updateResult.optInt("exit_code", -1))
            .put("message", "One-click Linux sandbox deployment completed.")
            .put("next_actions", JSONArray().put("run").put("update").put("set_mirror").put("stop").put("uninstall"))
    }

    private fun setMirror(
        context: Context,
        state: JSONObject,
        distroId: String,
        name: String,
        mirrorProfile: String,
        timeoutSeconds: Long,
    ): JSONObject {
        val selected = selectDistro(distroId = distroId, name = name, image = "")
        val activeName = readAgentControl(context).optString("active_sandbox_name")
        val sandboxName = name.ifBlank { selected.optString("name").ifBlank { activeName } }
        val profile = mirrorProfile.ifBlank { "china" }
        if (sandboxName.isBlank()) {
            return status(state, context)
                .put("exit_code", 2)
                .put("action", "set_mirror")
                .put("error", "set_mirror requires a sandbox name, known distro_id, or active sandbox.")
        }
        val rootfsDir = File(File(containersDir(state), sandboxName), "rootfs")
        if (!rootfsDir.isDirectory) {
            return status(state, context)
                .put("exit_code", 2)
                .put("action", "set_mirror")
                .put("sandbox_name", sandboxName)
                .put("distro_id", selected.optString("id"))
                .put("error", "container '$sandboxName' is not installed.")
        }
        val packageManager = selected.optString("package_manager")
        val mirrorCommand = HermesLinuxSandboxCatalog.mirrorCommandFor(packageManager, profile)
        return runCommand(
            context = context,
            state = state,
            distroId = distroId,
            name = sandboxName,
            command = mirrorCommand,
            timeoutSeconds = timeoutSeconds.coerceIn(30, DEFAULT_TIMEOUT_SECONDS),
            respectAgentControl = false,
        ).put("action", "set_mirror")
            .put("sandbox_name", sandboxName)
            .put("distro_id", selected.optString("id"))
            .put("package_manager", packageManager)
            .put("mirror_profile", profile)
            .put("mirror_command", mirrorCommand)
    }

    private fun startAgentShell(
        context: Context,
        state: JSONObject,
        distroId: String,
        name: String,
    ): JSONObject {
        val selected = selectDistro(distroId = distroId, name = name, image = "")
        val installed = installedSandboxes(state)
        val sandboxName = name.ifBlank {
            selected.optString("name").ifBlank {
                installed.optJSONObject(0)?.optString("name").orEmpty()
            }
        }
        if (sandboxName.isBlank()) {
            return status(state, context)
                .put("exit_code", 2)
                .put("action", "start")
                .put("error", "start requires an installed sandbox. Use action=download first.")
        }
        val rootfsDir = File(File(containersDir(state), sandboxName), "rootfs")
        if (!rootfsDir.isDirectory) {
            return status(state, context)
                .put("exit_code", 2)
                .put("action", "start")
                .put("sandbox_name", sandboxName)
                .put("distro_id", selected.optString("id"))
                .put("error", "container '$sandboxName' is not installed.")
        }
        val control = defaultAgentControl()
            .put("agent_shell_enabled", true)
            .put("active_sandbox_name", sandboxName)
            .put("active_distro_id", selected.optString("id"))
            .put("updated_at_epoch_ms", System.currentTimeMillis())
        writeAgentControl(context, control)
        return status(state, context)
            .put("action", "start")
            .put("sandbox_name", sandboxName)
            .put("distro_id", selected.optString("id"))
            .put("message", "Agent proot sandbox use is enabled.")
    }

    private fun stopAgentShell(
        context: Context,
        state: JSONObject,
        distroId: String,
        name: String,
    ): JSONObject {
        val selected = selectDistro(distroId = distroId, name = name, image = "")
        val prior = readAgentControl(context)
        val sandboxName = name.ifBlank { selected.optString("name").ifBlank { prior.optString("active_sandbox_name") } }
        val control = JSONObject(prior.toString())
            .put("agent_shell_enabled", false)
            .put("active_sandbox_name", sandboxName)
            .put("active_distro_id", selected.optString("id").ifBlank { prior.optString("active_distro_id") })
            .put("updated_at_epoch_ms", System.currentTimeMillis())
        writeAgentControl(context, control)
        return status(state, context)
            .put("action", "stop")
            .put("sandbox_name", sandboxName)
            .put("distro_id", selected.optString("id"))
            .put("message", "Agent proot sandbox use is disabled until action=start.")
    }

    private fun runCommand(
        context: Context,
        state: JSONObject,
        distroId: String,
        name: String,
        command: String,
        timeoutSeconds: Long,
        respectAgentControl: Boolean = true,
    ): JSONObject {
        val selected = selectDistro(distroId = distroId, name = name, image = "")
        val control = readAgentControl(context)
        val activeName = control.optString("active_sandbox_name")
        val sandboxName = name.ifBlank { selected.optString("name").ifBlank { activeName } }
        if (respectAgentControl && !control.optBoolean("agent_shell_enabled", true)) {
            return runErrorResult(state = state, sandboxName = sandboxName, selected = selected, command = command, exitCode = 125)
                .put("exit_code", 125)
                .put("error", "Agent proot sandbox use is stopped. Call linux_sandbox_tool action=start before running commands.")
                .put("agent_shell_enabled", false)
                .put("active_sandbox_name", activeName)
        }
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
            return status(state, context)
                .put("exit_code", 2)
                .put("error", "remove requires a sandbox name or known distro_id.")
        }
        val command = removeCommandFor(sandboxName = sandboxName)
        val result = runProotDistroCommand(
            context = context,
            state = state,
            action = "remove",
            command = command,
            timeoutSeconds = timeoutSeconds.coerceIn(10, DEFAULT_TIMEOUT_SECONDS),
        ).put("sandbox_name", sandboxName)
            .put("distro_id", selected.optString("id"))
        val control = readAgentControl(context)
        if (control.optString("active_sandbox_name") == sandboxName) {
            writeAgentControl(
                context,
                JSONObject(control.toString())
                    .put("agent_shell_enabled", false)
                    .put("active_sandbox_name", "")
                    .put("active_distro_id", "")
                    .put("updated_at_epoch_ms", System.currentTimeMillis()),
            )
        }
        return result
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
            return status(state, context)
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
            result.put("linux_sandbox_status", status(state, context))
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

    internal fun updateCommandFor(packageManager: String): String {
        return when (packageManager.trim().lowercase()) {
            "apt" -> "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y upgrade"
            "apk" -> "apk update && apk upgrade"
            "pacman" -> "pacman -Syu --noconfirm"
            "dnf" -> "dnf -y upgrade --refresh"
            "xbps" -> "xbps-install -Syu"
            "zypper" -> "zypper --non-interactive refresh && zypper --non-interactive update"
            else -> "if command -v apt-get >/dev/null 2>&1; then apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y upgrade; " +
                "elif command -v apk >/dev/null 2>&1; then apk update && apk upgrade; " +
                "elif command -v dnf >/dev/null 2>&1; then dnf -y upgrade --refresh; " +
                "elif command -v pacman >/dev/null 2>&1; then pacman -Syu --noconfirm; " +
                "elif command -v zypper >/dev/null 2>&1; then zypper --non-interactive refresh && zypper --non-interactive update; " +
                "elif command -v xbps-install >/dev/null 2>&1; then xbps-install -Syu; " +
                "else echo 'No supported package manager found' >&2; exit 127; fi"
        }
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

    private fun defaultAgentControl(): JSONObject {
        return JSONObject()
            .put("agent_shell_enabled", true)
            .put("active_sandbox_name", "")
            .put("active_distro_id", "")
    }

    private fun readAgentControl(context: Context): JSONObject {
        val file = agentControlFile(context)
        if (!file.isFile) {
            return defaultAgentControl()
        }
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }
            .getOrDefault(defaultAgentControl())
    }

    private fun writeAgentControl(context: Context, control: JSONObject) {
        val file = agentControlFile(context)
        file.parentFile?.mkdirs()
        file.writeText(control.toString(), Charsets.UTF_8)
    }

    private fun appPrivateStorageRoot(context: Context): File {
        return context.getExternalFilesDir(null)?.parentFile ?: context.filesDir
    }

    private fun agentControlFile(context: Context): File {
        return context.getExternalFilesDir(null)
            ?.let { File(it, "hermes-home/$AGENT_CONTROL_FILE_NAME") }
            ?: File(context.filesDir, "hermes-home/$AGENT_CONTROL_FILE_NAME")
    }
}
