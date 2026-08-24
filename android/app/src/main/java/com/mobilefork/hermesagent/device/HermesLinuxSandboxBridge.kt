package com.mobilefork.hermesagent.device

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

object HermesLinuxSandboxBridge {
    private const val DEFAULT_TIMEOUT_SECONDS = 900L
    private const val RUN_TIMEOUT_SECONDS = 120L
    private const val GUEST_COMMAND_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    private const val AGENT_CONTROL_FILE_NAME = "hermes-agent-shell-control.json"
    private val defaultLayerHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private data class DockerHubImage(
        val repo: String,
        val tag: String,
    )

    fun performAction(
        context: Context,
        action: String,
        distroId: String = "",
        name: String = "",
        image: String = "",
        command: String = "",
        mirrorProfile: String = "",
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        layerHttpClient: OkHttpClient = defaultLayerHttpClient,
        cancellationRequested: () -> Boolean = { false },
        publicationGate: AutomationPublicationGate? = null,
    ): JSONObject {
        throwIfCancellationRequested(cancellationRequested)
        val normalizedAction = normalizeAction(action)
        val normalizedDistroId = normalizeArgumentValue(distroId)
        val normalizedName = normalizeArgumentValue(name)
        val normalizedImage = normalizeArgumentValue(image)
        val normalizedMirrorProfile = normalizeArgumentValue(mirrorProfile)
        if (publicationGate != null && normalizedAction in REQUEST_OWNED_UNCANCELLABLE_ACTIONS) {
            return requestOwnedProcessActionBlocked(
                action = normalizedAction,
                distroId = normalizedDistroId,
                name = normalizedName,
                image = normalizedImage,
                command = command,
            )
        }
        val state = if (publicationGate == null) {
            // Manual and background callers retain the historical setup/repair behavior.
            HermesLinuxSubsystemBridge.ensureInstalled(context.applicationContext)
        } else {
            // Chat status and bounded control changes must never install, repair, delete, or
            // rewrite the host runtime merely by observing it.
            HermesLinuxSubsystemBridge.readStateSnapshot(context.applicationContext)
                ?: return requestOwnedRuntimeUnavailable(
                    context = context.applicationContext,
                    action = normalizedAction,
                    distroId = normalizedDistroId,
                    name = normalizedName,
                )
        }
        throwIfCancellationRequested(cancellationRequested)
        return when (normalizedAction) {
            "catalog" -> if (publicationGate == null) {
                catalog(state, context)
            } else {
                requestOwnedStatusFromSnapshot(state, "catalog")
            }
            "status", "list" -> if (publicationGate == null) {
                status(state, context)
            } else {
                requestOwnedStatusFromSnapshot(state, normalizedAction)
            }
            "download", "install" -> install(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                image = normalizedImage,
                timeoutSeconds = timeoutSeconds,
                layerHttpClient = layerHttpClient,
                cancellationRequested = cancellationRequested,
                publicationGate = publicationGate,
            )
            "update", "upgrade", "refresh" -> updateSandbox(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                timeoutSeconds = timeoutSeconds,
                cancellationRequested = cancellationRequested,
            )
            "deploy", "bootstrap", "one_click_deploy", "one-click-deploy" -> deploySandbox(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                mirrorProfile = normalizedMirrorProfile,
                timeoutSeconds = timeoutSeconds,
                layerHttpClient = layerHttpClient,
                cancellationRequested = cancellationRequested,
                publicationGate = publicationGate,
            )
            "set_mirror", "switch_mirror", "mirror" -> setMirror(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                mirrorProfile = normalizedMirrorProfile,
                timeoutSeconds = timeoutSeconds,
                cancellationRequested = cancellationRequested,
            )
            "start", "launch", "enable" -> startAgentShell(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                cancellationRequested = cancellationRequested,
                publicationGate = publicationGate,
            )
            "stop", "close", "disable" -> stopAgentShell(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                cancellationRequested = cancellationRequested,
                publicationGate = publicationGate,
            )
            "run" -> runCommand(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                command = command,
                timeoutSeconds = timeoutSeconds,
                cancellationRequested = cancellationRequested,
            )
            "remove", "uninstall", "delete" -> remove(
                context = context,
                state = state,
                distroId = normalizedDistroId,
                name = normalizedName,
                timeoutSeconds = timeoutSeconds,
                cancellationRequested = cancellationRequested,
            )
            else -> status(state, context)
                .put("exit_code", 2)
                .put("error", "linux_sandbox_tool action must be catalog, status, list, download/install, deploy, update, set_mirror, start, stop, run, or uninstall/remove.")
        }
    }

    /** Run a command explicitly entered by the user without enabling AI sandbox access. */
    fun runUserCommand(
        context: Context,
        name: String,
        command: String,
        timeoutSeconds: Long = RUN_TIMEOUT_SECONDS,
    ): JSONObject {
        val state = HermesLinuxSubsystemBridge.ensureInstalled(context.applicationContext)
        return runCommand(
            context = context.applicationContext,
            state = state,
            distroId = "",
            name = normalizeArgumentValue(name),
            command = command,
            timeoutSeconds = timeoutSeconds,
            respectAgentControl = false,
        ).put("manual_terminal_session", true)
    }

    fun status(state: JSONObject, context: Context? = null): JSONObject {
        val preferredGuestArch = preferredGuestArchitecture(hostArchitectureForState(state))
        val qemuUserPath = qemuPathForGuestArchitecture(state, preferredGuestArch)
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
            .put("preferred_guest_architecture", preferredGuestArch)
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

    private fun catalog(state: JSONObject, context: Context?): JSONObject {
        return status(state, context).put("action", "catalog")
    }

    private fun requestOwnedStatusFromSnapshot(state: JSONObject, action: String): JSONObject {
        // Deliberately avoid Context.getExternalFilesDir here: Android may create the app's
        // external-files hierarchy while resolving it. Chat-owned status is an observation only.
        return status(state, context = null)
            .put("action", action)
            .put("request_owned", true)
            .put("runtime_state_available", true)
            .put("agent_control_observed", false)
            .put("agent_control_file", "")
            .put("agent_shell_enabled", false)
            .put("active_sandbox_name", "")
            .put("active_distro_id", "")
            .put(
                "agent_shell_policy",
                "unknown: chat-owned status does not create or probe the external control-file hierarchy.",
            )
    }

    private fun install(
        context: Context,
        state: JSONObject,
        distroId: String,
        name: String,
        image: String,
        timeoutSeconds: Long,
        layerHttpClient: OkHttpClient,
        cancellationRequested: () -> Boolean,
        publicationGate: AutomationPublicationGate?,
    ): JSONObject {
        val selected = selectDistro(distroId = distroId, name = name, image = image)
        val sandboxName = name.ifBlank { selected.optString("name") }
        val imageRef = image.ifBlank { selected.optString("image") }
        if (imageRef.isBlank() || sandboxName.isBlank()) {
            return status(state, context)
                .put("exit_code", 2)
                .put("error", "install requires a known distro_id, image, or name.")
        }
        val guestArchitecture = preferredGuestArchitecture(hostArchitectureForState(state))
        val command = installCommandFor(
            sandboxName = sandboxName,
            imageRef = imageRef,
            architecture = guestArchitecture,
        )
        val primaryResult = runProotDistroCommand(
            context = context,
            state = state,
            action = "install",
            command = command,
            timeoutSeconds = timeoutSeconds.coerceIn(60, DEFAULT_TIMEOUT_SECONDS),
            cancellationRequested = cancellationRequested,
        )
        throwIfCancellationRequested(cancellationRequested)
        val result = if (shouldRetryInstallWithAndroidHttp(primaryResult)) {
            val cacheResult = cacheDockerLayersWithAndroidHttp(
                state = state,
                imageRef = imageRef,
                architecture = guestArchitecture,
                layerHttpClient = layerHttpClient,
                cancellationRequested = cancellationRequested,
                publicationGate = publicationGate,
            )
            throwIfCancellationRequested(cancellationRequested)
            if (cacheResult.optInt("exit_code", -1) == 0) {
                runProotDistroCommand(
                    context = context,
                    state = state,
                    action = "install",
                    command = command,
                    timeoutSeconds = timeoutSeconds.coerceIn(60, DEFAULT_TIMEOUT_SECONDS),
                    cancellationRequested = cancellationRequested,
                ).put("network_retry", "android_https_verified_layer_cache")
                    .put("android_layer_cache", cacheResult)
                    .put("initial_network_error", primaryResult.optString("error").take(2000))
            } else {
                primaryResult.put("network_retry", "android_https_layer_cache_failed")
                    .put("android_layer_cache", cacheResult)
            }
        } else {
            primaryResult
        }
        return result.put("sandbox_name", sandboxName)
            .put("image", imageRef)
            .put("sandbox_architecture", guestArchitecture)
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
        cancellationRequested: () -> Boolean,
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
        val guestCaBundle = if (packageManager == "apt") {
            ensureGuestCaBundle(rootfsDir)
        } else {
            JSONObject().put("exit_code", 0).put("skipped", true)
        }
        if (guestCaBundle.optInt("exit_code", -1) != 0) {
            return status(state, context)
                .put("exit_code", 1)
                .put("action", "update")
                .put("sandbox_name", sandboxName)
                .put("distro_id", selected.optString("id"))
                .put("package_manager", packageManager)
                .put("update_command", updateCommand)
                .put("guest_ca_bundle", guestCaBundle)
                .put("error", guestCaBundle.optString("error"))
        }
        return runCommand(
            context = context,
            state = state,
            distroId = distroId,
            name = sandboxName,
            command = updateCommand,
            timeoutSeconds = timeoutSeconds.coerceIn(60, DEFAULT_TIMEOUT_SECONDS),
            respectAgentControl = false,
            useLifecycleTimeout = true,
            cancellationRequested = cancellationRequested,
        ).put("action", "update")
            .put("sandbox_name", sandboxName)
            .put("distro_id", selected.optString("id"))
            .put("package_manager", packageManager)
            .put("update_command", updateCommand)
            .put("guest_ca_bundle", guestCaBundle)
    }

    private fun deploySandbox(
        context: Context,
        state: JSONObject,
        distroId: String,
        name: String,
        mirrorProfile: String,
        timeoutSeconds: Long,
        layerHttpClient: OkHttpClient,
        cancellationRequested: () -> Boolean,
        publicationGate: AutomationPublicationGate?,
    ): JSONObject {
        val selected = selectDistro(
            distroId = distroId.ifBlank { "debian-bookworm" },
            name = name.ifBlank { "hermes-debian" },
            image = "",
        )
        val sandboxName = name.ifBlank { selected.optString("name").ifBlank { "hermes-debian" } }
        val rootfsDir = File(File(containersDir(state), sandboxName), "rootfs")
        val sandboxExistedBefore = rootfsDir.isDirectory
        val installResult = if (!sandboxExistedBefore) {
            install(
                context = context,
                state = state,
                distroId = selected.optString("id"),
                name = sandboxName,
                image = selected.optString("image"),
                timeoutSeconds = timeoutSeconds.coerceIn(60, DEFAULT_TIMEOUT_SECONDS),
                layerHttpClient = layerHttpClient,
                cancellationRequested = cancellationRequested,
                publicationGate = publicationGate,
            )
        } else {
            status(state, context)
                .put("action", "deploy")
                .put("sandbox_name", sandboxName)
                .put("distro_id", selected.optString("id"))
                .put("message", "Sandbox already installed; continuing with start/update.")
        }
        throwIfCancellationRequested(cancellationRequested)
        if (installResult.optInt("exit_code", -1) != 0) {
            return annotateDeployDisposition(
                result = installResult.put("action", "deploy"),
                failedPhase = "install",
                sandboxExistedBefore = sandboxExistedBefore,
                sandboxPresentAfterDeploy = rootfsDir.isDirectory,
            )
        }
        val startResult = startAgentShell(
            context = context,
            state = state,
            distroId = selected.optString("id"),
            name = sandboxName,
            cancellationRequested = cancellationRequested,
            publicationGate = publicationGate,
        )
        throwIfCancellationRequested(cancellationRequested)
        if (startResult.optInt("exit_code", -1) != 0) {
            return annotateDeployDisposition(
                result = startResult.put("action", "deploy"),
                failedPhase = "start",
                sandboxExistedBefore = sandboxExistedBefore,
                sandboxPresentAfterDeploy = rootfsDir.isDirectory,
            )
        }
        val mirrorResult = if (mirrorProfile.isNotBlank()) {
            setMirror(
                context = context,
                state = state,
                distroId = selected.optString("id"),
                name = sandboxName,
                mirrorProfile = mirrorProfile,
                timeoutSeconds = timeoutSeconds,
                cancellationRequested = cancellationRequested,
            )
        } else {
            JSONObject().put("exit_code", 0).put("action", "set_mirror").put("skipped", true)
        }
        throwIfCancellationRequested(cancellationRequested)
        if (mirrorResult.optInt("exit_code", -1) != 0) {
            return annotateDeployDisposition(
                result = status(state, context)
                    .put("action", "deploy")
                    .put("sandbox_name", sandboxName)
                    .put("distro_id", selected.optString("id"))
                    .put("install_result", installResult)
                    .put("start_result", startResult)
                    .put("mirror_result", mirrorResult)
                    .put("exit_code", mirrorResult.optInt("exit_code", -1)),
                failedPhase = "set_mirror",
                sandboxExistedBefore = sandboxExistedBefore,
                sandboxPresentAfterDeploy = rootfsDir.isDirectory,
            )
        }
        val updateResult = updateSandbox(
            context = context,
            state = state,
            distroId = selected.optString("id"),
            name = sandboxName,
            timeoutSeconds = timeoutSeconds,
            cancellationRequested = cancellationRequested,
        )
        val result = status(state, context)
            .put("action", "deploy")
            .put("sandbox_name", sandboxName)
            .put("distro_id", selected.optString("id"))
            .put("install_result", installResult)
            .put("start_result", startResult)
            .put("mirror_result", mirrorResult)
            .put("update_result", updateResult)
            .put("exit_code", updateResult.optInt("exit_code", -1))
            .put("next_actions", JSONArray().put("run").put("update").put("set_mirror").put("stop").put("uninstall"))
        return annotateDeployDisposition(
            result = result,
            failedPhase = if (updateResult.optInt("exit_code", -1) == 0) "" else "update",
            sandboxExistedBefore = sandboxExistedBefore,
            sandboxPresentAfterDeploy = rootfsDir.isDirectory,
        )
    }

    internal fun annotateDeployDisposition(
        result: JSONObject,
        failedPhase: String,
        sandboxExistedBefore: Boolean,
        sandboxPresentAfterDeploy: Boolean,
    ): JSONObject {
        val completed = failedPhase.isBlank() && result.optInt("exit_code", -1) == 0
        val sandboxState = when {
            completed -> "ready"
            sandboxPresentAfterDeploy -> "preserved_incomplete"
            else -> "not_installed"
        }
        val message = when {
            completed -> "One-click Linux sandbox deployment completed."
            sandboxPresentAfterDeploy && sandboxExistedBefore ->
                "One-click Linux sandbox deployment failed during $failedPhase. The existing sandbox was preserved for inspection and retry."
            sandboxPresentAfterDeploy ->
                "One-click Linux sandbox deployment failed during $failedPhase. The new sandbox was preserved in an incomplete state for inspection and retry."
            else ->
                "One-click Linux sandbox deployment failed during $failedPhase. No sandbox rootfs remains installed."
        }
        return result
            .put("deployment_completed", completed)
            .put("failed_phase", failedPhase)
            .put("sandbox_existed_before", sandboxExistedBefore)
            .put("sandbox_present_after_deploy", sandboxPresentAfterDeploy)
            .put("sandbox_preserved_for_retry", !completed && sandboxPresentAfterDeploy)
            .put("sandbox_state", sandboxState)
            .put("message", message)
    }

    private fun setMirror(
        context: Context,
        state: JSONObject,
        distroId: String,
        name: String,
        mirrorProfile: String,
        timeoutSeconds: Long,
        cancellationRequested: () -> Boolean,
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
            cancellationRequested = cancellationRequested,
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
        cancellationRequested: () -> Boolean,
        publicationGate: AutomationPublicationGate?,
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
        throwIfCancellationRequested(cancellationRequested)
        writeAgentControl(context, control, cancellationRequested, publicationGate)
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
        cancellationRequested: () -> Boolean,
        publicationGate: AutomationPublicationGate?,
    ): JSONObject {
        val selected = selectDistro(distroId = distroId, name = name, image = "")
        val prior = readAgentControl(context)
        val sandboxName = name.ifBlank { selected.optString("name").ifBlank { prior.optString("active_sandbox_name") } }
        val control = JSONObject(prior.toString())
            .put("agent_shell_enabled", false)
            .put("active_sandbox_name", sandboxName)
            .put("active_distro_id", selected.optString("id").ifBlank { prior.optString("active_distro_id") })
            .put("updated_at_epoch_ms", System.currentTimeMillis())
        throwIfCancellationRequested(cancellationRequested)
        writeAgentControl(context, control, cancellationRequested, publicationGate)
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
        useLifecycleTimeout: Boolean = false,
        cancellationRequested: () -> Boolean = { false },
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
        val guestArchitecture = sandboxArchitecture(File(containersDir(state), sandboxName))
        val hostArchitecture = hostArchitectureForState(state)
        if (guestArchitecture.isBlank()) {
            return runErrorResult(state = state, sandboxName = sandboxName, selected = selected, command = command, exitCode = 126)
                .put("error", "container '$sandboxName' has no readable architecture metadata; reinstall it before running commands.")
        }
        if (guestArchitecture == hostArchitecture) {
            return runErrorResult(state = state, sandboxName = sandboxName, selected = selected, command = command, exitCode = 126)
                .put("sandbox_architecture", guestArchitecture)
                .put("required_sandbox_architecture", preferredGuestArchitecture(hostArchitecture))
                .put(
                    "error",
                    "container '$sandboxName' uses the device architecture ($guestArchitecture). Android 10+ blocks executing downloaded app-data binaries; preserve any data, uninstall this sandbox, and deploy it again so Hermes can install the emulated architecture.",
                )
        }
        val qemuUserPath = qemuPathForGuestArchitecture(state, guestArchitecture)
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
            timeoutSeconds = commandTimeoutSeconds(timeoutSeconds, useLifecycleTimeout),
            includeStatus = false,
            cancellationRequested = cancellationRequested,
        )
        return compactRunResult(
            state = state,
            result = qemuResult,
            selected = selected,
            sandboxName = sandboxName,
            command = command,
            rootfsDir = rootfsDir,
            qemuUserPath = qemuUserPath,
            sandboxExecutionMode = "proot_distro_qemu",
            qemuExitCode = qemuResult.optInt("exit_code", -1),
            qemuError = qemuResult.optString("error"),
        ).put("sandbox_architecture", guestArchitecture)
            .put("host_architecture", hostArchitecture)
    }

    private fun remove(
        context: Context,
        state: JSONObject,
        distroId: String,
        name: String,
        timeoutSeconds: Long,
        cancellationRequested: () -> Boolean,
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
            cancellationRequested = cancellationRequested,
        ).put("sandbox_name", sandboxName)
            .put("distro_id", selected.optString("id"))
        throwIfCancellationRequested(cancellationRequested)
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
        cancellationRequested: () -> Boolean,
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
            cancellationRequested = cancellationRequested,
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
            .put("native_execution_route", result.optString("native_execution_route"))
            .put("uses_termux", result.optBoolean("uses_termux", state.optBoolean("uses_termux", false)))
        if (result.optInt("exit_code", -1) != 0) {
            output
                .put("qemu_exit_code", qemuExitCode)
                .put("qemu_error", qemuError)
                .put("execution_denial_hint", result.optString("execution_denial_hint"))
                .put("android_exec_policy", result.optString("android_exec_policy"))
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
        val qemuUserPath = qemuPathForGuestArchitecture(
            state,
            preferredGuestArchitecture(hostArchitectureForState(state)),
        )
        return JSONObject()
            .put("exit_code", exitCode)
            .put("action", "run")
            .put("sandbox_name", sandboxName)
            .put("distro_id", selected.optString("id"))
            .put("sandbox_command", command)
            .put("sandbox_execution_mode", "proot_distro_qemu")
            .put("qemu_user_available", qemuUserPath.isNotBlank())
            .put("qemu_user_path", qemuUserPath)
            .put("execution_mode", state.optString("execution_mode"))
            .put("uses_termux", state.optBoolean("uses_termux", false))
    }

    private fun normalizeAction(action: String): String {
        return action.trim().trim('.', ',', ':', ';').lowercase().ifBlank { "status" }
    }

    private fun requestOwnedProcessActionBlocked(
        action: String,
        distroId: String,
        name: String,
        image: String,
        command: String,
    ): JSONObject {
        val selected = selectDistro(distroId = distroId, name = name, image = image)
        val sandboxName = name.ifBlank { selected.optString("name") }
        return JSONObject()
            .put("exit_code", 126)
            .put("success", false)
            .put("action", action)
            .put("distro_id", selected.optString("id").ifBlank { distroId })
            .put("sandbox_name", sandboxName)
            .put("image", image.ifBlank { selected.optString("image") })
            .put("sandbox_command", command)
            .put("sandbox_execution_mode", "request_owned_proot_blocked")
            .put("request_owned", true)
            .put("request_owned_operation_blocked", true)
            .put(
                "error",
                "Hermes blocked this chat-owned Linux guest process before dispatch because its filesystem/package mutations cannot be committed atomically with Stop. Use the manual Device Linux sandbox controls for install, guest commands, mirror changes, updates, or removal.",
            )
    }

    private fun requestOwnedRuntimeUnavailable(
        context: Context,
        action: String,
        distroId: String,
        name: String,
    ): JSONObject {
        val selected = selectDistro(distroId = distroId, name = name, image = "")
        val readOnlyStatus = JSONObject()
            .put("exit_code", if (action in READ_ONLY_ACTIONS) 0 else 127)
            .put("success", action in READ_ONLY_ACTIONS)
            .put("action", action)
            .put("request_owned", true)
            .put("runtime_state_available", false)
            .put("execution_mode", "uninitialized")
            .put("uses_termux", false)
            .put("proot_available", false)
            .put("proot_distro_available", false)
            .put("qemu_user_available", false)
            .put("agent_shell_enabled", false)
            .put("active_sandbox_name", "")
            .put("active_distro_id", "")
            .put("app_private_storage_root", context.filesDir.absolutePath)
            .put("agent_control_file", "")
            .put("runtime_dir", "")
            .put("containers_dir", "")
            .put("installed_sandboxes", JSONArray())
            .put("downloadable_linux_sandboxes", HermesLinuxSandboxCatalog.distroCatalog())
            .put("recommended_linux_sandboxes", HermesLinuxSandboxCatalog.recommendedSandboxIds())
            .put("mirror_profiles", HermesLinuxSandboxCatalog.mirrorProfiles())
            .put("desktop_environment_catalog", HermesLinuxSandboxCatalog.desktopCatalog())
            .put("linux_sandbox_agent_summary", HermesLinuxSandboxCatalog.agentSummary())
            .put("status", "embedded_sandbox_runtime_not_initialized")
        if (action !in READ_ONLY_ACTIONS) {
            readOnlyStatus
                .put("distro_id", selected.optString("id").ifBlank { distroId })
                .put("sandbox_name", name.ifBlank { selected.optString("name") })
                .put(
                    "error",
                    "The embedded Linux runtime has not been initialized. Chat-owned sandbox actions do not install or repair it implicitly; initialize it from the Device screen first.",
                )
        }
        return readOnlyStatus
    }

    private fun throwIfCancellationRequested(cancellationRequested: () -> Boolean) {
        if (cancellationRequested()) {
            throw CancellationException("Linux sandbox operation was stopped")
        }
    }

    internal fun normalizeArgumentValue(value: String): String {
        return value.trim().trim('.', ',', ':', ';')
    }

    internal fun installCommandFor(sandboxName: String, imageRef: String, architecture: String = ""): String {
        val architectureArg = architecture.trim().takeIf { it.isNotBlank() }
            ?.let { " --architecture ${HermesLinuxSubsystemBridge.shellQuote(it)}" }
            .orEmpty()
        return "proot-distro install --name ${HermesLinuxSubsystemBridge.shellQuote(sandboxName)}$architectureArg ${HermesLinuxSubsystemBridge.shellQuote(imageRef)}"
    }

    internal fun shouldRetryInstallWithAndroidHttp(result: JSONObject): Boolean {
        if (result.optBoolean("cancelled", false) || result.optInt("exit_code", 0) == 130) return false
        if (result.optInt("exit_code", 0) == 0) return false
        val detail = (result.optString("error") + "\n" + result.optString("output")).uppercase()
        return detail.contains("RECORD_LAYER_FAILURE") ||
            detail.contains("UNEXPECTED_EOF_WHILE_READING")
    }

    internal fun dockerManifestCacheKey(imageRef: String, arch: String): String? {
        val image = parseDockerHubImage(imageRef) ?: return null
        return sha256Hex("${image.repo}:${image.tag}_${arch}".toByteArray(Charsets.UTF_8)).take(16)
    }

    private fun cacheDockerLayersWithAndroidHttp(
        state: JSONObject,
        imageRef: String,
        architecture: String,
        layerHttpClient: OkHttpClient,
        cancellationRequested: () -> Boolean,
        publicationGate: AutomationPublicationGate?,
    ): JSONObject {
        return try {
            throwIfCancellationRequested(cancellationRequested)
            val image = parseDockerHubImage(imageRef)
                ?: return JSONObject()
                    .put("exit_code", 2)
                    .put("error", "Android HTTPS layer fallback currently supports Docker Hub image references only.")
            val prefixPath = state.optString("prefix_path")
            val arch = architecture
            val cacheKey = dockerManifestCacheKey(imageRef, arch)
                ?: return JSONObject().put("exit_code", 2).put("error", "Invalid Docker Hub image reference.")
            val cacheRoot = File(prefixPath, "var/lib/proot-distro/cache")
            val manifestFile = File(File(cacheRoot, "oci_manifests"), "$cacheKey.json")
            if (!manifestFile.isFile) {
                return JSONObject()
                    .put("exit_code", 2)
                    .put("error", "proot-distro did not leave a verified manifest cache for $imageRef ($arch).")
            }
            val cachedManifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            if (cachedManifest.optString("repo") != image.repo) {
                return JSONObject()
                    .put("exit_code", 2)
                    .put("error", "Cached manifest repository does not match ${image.repo}.")
            }
            val layers = cachedManifest.optJSONObject("manifest")?.optJSONArray("layers")
                ?: return JSONObject().put("exit_code", 2).put("error", "Cached manifest has no layer list.")
            val layerDir = File(cacheRoot, "oci_layers").apply { mkdirs() }
            val missing = mutableListOf<JSONObject>()
            for (index in 0 until layers.length()) {
                throwIfCancellationRequested(cancellationRequested)
                val layer = layers.optJSONObject(index) ?: continue
                val digest = layer.optString("digest")
                val expectedHex = validatedSha256Digest(digest)
                    ?: return JSONObject().put("exit_code", 2).put("error", "Manifest contains an invalid layer digest.")
                val target = File(layerDir, digest.replace(':', '_'))
                if (!target.isFile ||
                    target.length() != layer.optLong("size", -1L) ||
                    sha256File(target, cancellationRequested) != expectedHex
                ) {
                    throwIfCancellationRequested(cancellationRequested)
                    // Preserve the last durable cache entry until a fully downloaded and
                    // verified replacement can be promoted under the request gate.
                    missing += layer
                }
            }
            if (missing.isEmpty()) {
                return JSONObject()
                    .put("exit_code", 0)
                    .put("transport", "android_okhttp")
                    .put("downloaded_layer_count", 0)
                    .put("verified_layer_count", layers.length())
            }

            val tokenUrl = "https://auth.docker.io/token".toHttpUrl().newBuilder()
                .addQueryParameter("service", "registry.docker.io")
                .addQueryParameter("scope", "repository:${image.repo}:pull")
                .build()
            val token = executeTextRequest(
                request = Request.Builder().url(tokenUrl).get().build(),
                layerHttpClient = layerHttpClient,
            )
                .let { JSONObject(it).optString("token").ifBlank { JSONObject(it).optString("access_token") } }
            if (token.isBlank()) {
                return JSONObject().put("exit_code", 1).put("error", "Docker Hub did not return an anonymous pull token.")
            }

            var downloadedCount = 0
            missing.forEach { layer ->
                throwIfCancellationRequested(cancellationRequested)
                val digest = layer.getString("digest")
                val expectedHex = validatedSha256Digest(digest)
                    ?: error("Manifest contains an invalid layer digest.")
                val expectedSize = layer.optLong("size", -1L)
                val target = File(layerDir, digest.replace(':', '_'))
                downloadVerifiedLayer(
                    request = Request.Builder()
                        .url("https://registry-1.docker.io/v2/${image.repo}/blobs/$digest")
                        .header("Authorization", "Bearer $token")
                        .get()
                        .build(),
                    target = target,
                    expectedHex = expectedHex,
                    expectedSize = expectedSize,
                    layerHttpClient = layerHttpClient,
                    cancellationRequested = cancellationRequested,
                    publicationGate = publicationGate,
                )
                downloadedCount += 1
            }
            JSONObject()
                .put("exit_code", 0)
                .put("transport", "android_okhttp")
                .put("downloaded_layer_count", downloadedCount)
                .put("verified_layer_count", layers.length())
                .put("manifest_cache", manifestFile.absolutePath)
        } catch (error: Exception) {
            JSONObject()
                .put("exit_code", 1)
                .put("transport", "android_okhttp")
                .put("error", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun executeTextRequest(request: Request, layerHttpClient: OkHttpClient): String {
        return layerHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} ${response.message} for ${request.url.host}")
            }
            response.body?.string() ?: error("Empty HTTP response from ${request.url.host}")
        }
    }

    internal fun downloadVerifiedLayer(
        request: Request,
        target: File,
        expectedHex: String,
        expectedSize: Long,
        layerHttpClient: OkHttpClient,
        cancellationRequested: () -> Boolean = { false },
        publicationGate: AutomationPublicationGate? = null,
    ) {
        throwIfCancellationRequested(cancellationRequested)
        val targetParent = target.parentFile ?: error("Sandbox layer cache target has no parent directory.")
        if (publicationGate == null) {
            targetParent.mkdirs()
        } else if (!targetParent.isDirectory) {
            throw InterruptedIOException("Sandbox layer cache must be initialized manually before a request-owned promotion")
        }
        val temporary = File(targetParent, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var byteCount = 0L
            layerHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code} ${response.message} while downloading ${target.name}")
                }
                val body = response.body ?: error("Docker layer response body is empty.")
                body.byteStream().use { input ->
                    FileOutputStream(temporary).buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            throwIfLayerCancellationRequested(cancellationRequested, "Sandbox layer download was stopped")
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            byteCount += read
                        }
                    }
                }
            }
            if (expectedSize >= 0 && byteCount != expectedSize) {
                error("Docker layer size mismatch: expected $expectedSize bytes, received $byteCount.")
            }
            throwIfLayerCancellationRequested(cancellationRequested, "Sandbox layer promotion was stopped")
            val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualHex != expectedHex) {
                error("Docker layer SHA-256 mismatch: expected $expectedHex, received $actualHex.")
            }
            // Slow network transfer and hashing are intentionally outside the request lock. The
            // only gated section is the bounded durable replacement of the verified cache entry.
            throwIfLayerCancellationRequested(cancellationRequested, "Sandbox layer promotion was stopped")
            publicationGate.publishValueIfActive(
                cancelledValue = {
                    throw InterruptedIOException("Sandbox layer promotion was stopped before its final commit")
                },
                publication = {
                    replaceStagedFileAtCommit(temporary, target)
                },
            )
        } finally {
            temporary.delete()
        }
    }

    private fun parseDockerHubImage(imageRef: String): DockerHubImage? {
        val value = imageRef.trim().lowercase()
        if (value.isBlank() || value.contains('@')) return null
        val firstSegment = value.substringBefore('/')
        if ('/' in value && ('.' in firstSegment || ':' in firstSegment || firstSegment == "localhost")) return null
        val lastSlash = value.lastIndexOf('/')
        val lastColon = value.lastIndexOf(':')
        val name = if (lastColon > lastSlash) value.substring(0, lastColon) else value
        val tag = if (lastColon > lastSlash) value.substring(lastColon + 1) else "latest"
        if (name.isBlank() || tag.isBlank() || !DOCKER_REPO.matches(name) || !DOCKER_TAG.matches(tag)) return null
        return DockerHubImage(repo = if ('/' in name) name else "library/$name", tag = tag)
    }

    private fun validatedSha256Digest(digest: String): String? {
        val match = SHA256_DIGEST.matchEntire(digest.lowercase()) ?: return null
        return match.groupValues[1]
    }

    internal fun sha256File(
        file: File,
        cancellationRequested: () -> Boolean = { false },
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                throwIfLayerCancellationRequested(cancellationRequested, "Sandbox layer verification was stopped")
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun throwIfLayerCancellationRequested(
        cancellationRequested: () -> Boolean,
        message: String,
    ) {
        if (cancellationRequested() || Thread.currentThread().isInterrupted) {
            throw InterruptedIOException(message)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    internal fun ensureGuestCaBundle(
        rootfsDir: File,
        androidCaDirs: List<File> = listOf(
            File("/apex/com.android.conscrypt/cacerts"),
            File("/system/etc/security/cacerts"),
        ),
    ): JSONObject {
        val destination = File(rootfsDir, "etc/ssl/certs/ca-certificates.crt")
        val existingCount = pemCertificatesIn(destination).size
        val androidTrustRoots = androidCaDirs.asSequence().mapNotNull { source ->
            if (!source.isDirectory) return@mapNotNull null
            val certificates = source.listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.isFile && it.canRead() }
                .sortedBy { it.name }
                .flatMap { pemCertificatesIn(it).asSequence() }
                .toList()
            if (certificates.isEmpty()) null else source to certificates
        }.firstOrNull()
        if (androidTrustRoots == null) {
            return JSONObject()
                .put("exit_code", 1)
                .put("path", destination.absolutePath)
                .put("existing_certificate_count", existingCount)
                .put("error", "Android system CA certificates are unavailable; refusing to provision curl without a trust-root count that can validate the guest bundle.")
        }
        val (sourceDir, certificates) = androidTrustRoots
        if (existingCount >= certificates.size) {
            return JSONObject()
                .put("exit_code", 0)
                .put("path", destination.absolutePath)
                .put("source", "existing_guest_bundle")
                .put("certificate_count", existingCount)
                .put("android_certificate_count", certificates.size)
                .put("sha256", sha256File(destination))
        }

        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}.tmp")
        return try {
            temporary.writeText(certificates.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
            temporary.setReadable(true, false)
            temporary.setWritable(true, true)
            if (destination.exists() && !destination.delete()) {
                error("Unable to replace the guest CA bundle.")
            }
            if (!temporary.renameTo(destination)) {
                error("Unable to promote the guest CA bundle.")
            }
            JSONObject()
                .put("exit_code", 0)
                .put("path", destination.absolutePath)
                .put("source", sourceDir.absolutePath)
                .put("certificate_count", certificates.size)
                .put("android_certificate_count", certificates.size)
                .put("replaced_truncated_guest_bundle", existingCount > 0)
                .put("previous_certificate_count", existingCount)
                .put("sha256", sha256File(destination))
        } catch (error: Throwable) {
            JSONObject()
                .put("exit_code", 1)
                .put("path", destination.absolutePath)
                .put("source", sourceDir.absolutePath)
                .put("error", error.message ?: error.javaClass.simpleName)
        } finally {
            temporary.delete()
        }
    }

    private fun pemCertificatesIn(file: File): List<String> {
        if (!file.isFile || !file.canRead()) return emptyList()
        val content = runCatching { file.readText(Charsets.UTF_8) }.getOrElse { return emptyList() }
        return PEM_CERTIFICATE.findAll(content).map { it.value.trim() }.toList()
    }

    private val SHA256_DIGEST = Regex("""sha256:([0-9a-f]{64})""")
    private val PEM_CERTIFICATE = Regex("""-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----""")
    private val DOCKER_REPO = Regex("""[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*""")
    private val DOCKER_TAG = Regex("""[a-z0-9_][a-z0-9_.-]{0,127}""")

    internal fun commandTimeoutSeconds(timeoutSeconds: Long, useLifecycleTimeout: Boolean): Long {
        val bounded = timeoutSeconds.coerceIn(5, DEFAULT_TIMEOUT_SECONDS)
        return if (useLifecycleTimeout || timeoutSeconds != DEFAULT_TIMEOUT_SECONDS) {
            bounded
        } else {
            RUN_TIMEOUT_SECONDS
        }
    }

    internal fun runCommandFor(prefixPath: String, sandboxName: String, command: String, qemuPath: String = ""): String {
        val normalizedPrefixPath = prefixPath.trimEnd('/')
        val rootfsPath = "$normalizedPrefixPath/var/lib/proot-distro/containers/$sandboxName/rootfs"
        val emulatorArg = qemuPath.trim().takeIf { it.isNotBlank() }
            ?.let { " --emulator ${HermesLinuxSubsystemBridge.shellQuote(it)}" }
            .orEmpty()
        val guestCommand =
            "PATH=${HermesLinuxSubsystemBridge.shellQuote(GUEST_COMMAND_PATH)}; export PATH; $command"
        return "HERMES_SANDBOX_ROOTFS=${HermesLinuxSubsystemBridge.shellQuote(rootfsPath)}; " +
            "export HERMES_SANDBOX_ROOTFS; " +
            "proot-distro run ${HermesLinuxSubsystemBridge.shellQuote(sandboxName)}$emulatorArg -- " +
            "/bin/sh -lc ${HermesLinuxSubsystemBridge.shellQuote(guestCommand)}"
    }

    internal fun removeCommandFor(sandboxName: String): String {
        return "proot-distro remove ${HermesLinuxSubsystemBridge.shellQuote(sandboxName)}"
    }

    internal fun updateCommandFor(packageManager: String): String {
        return when (packageManager.trim().lowercase()) {
            "apt" -> "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y upgrade && " +
                "DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends install curl"
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
                val architecture = sandboxArchitecture(container)
                val hostArchitecture = hostArchitectureForState(state)
                result.put(
                    JSONObject()
                        .put("name", container.name)
                        .put("path", container.absolutePath)
                        .put("rootfs_path", File(container, "rootfs").absolutePath)
                        .put("manifest_available", File(container, "manifest.json").isFile)
                        .put("architecture", architecture)
                        .put(
                            "android_execution_supported",
                            architecture.isNotBlank() && architecture != hostArchitecture,
                        ),
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

    internal fun preferredGuestArchitecture(hostArchitecture: String): String {
        return when (normalizeArchitecture(hostArchitecture)) {
            "aarch64" -> "x86_64"
            "x86_64" -> "aarch64"
            else -> ""
        }
    }

    private fun hostArchitectureForState(state: JSONObject): String {
        return normalizeArchitecture(
            state.optString("termux_arch").ifBlank { state.optString("android_abi") },
        )
    }

    private fun normalizeArchitecture(architecture: String): String {
        return when (architecture.trim().lowercase()) {
            "arm64", "arm64-v8a", "aarch64" -> "aarch64"
            "amd64", "x86_64" -> "x86_64"
            else -> architecture.trim().lowercase()
        }
    }

    private fun sandboxArchitecture(containerDir: File): String {
        val manifest = File(containerDir, "manifest.json")
        if (!manifest.isFile) return ""
        return runCatching {
            normalizeArchitecture(JSONObject(manifest.readText(Charsets.UTF_8)).optString("arch"))
        }.getOrDefault("")
    }

    internal fun qemuPathForGuestArchitecture(state: JSONObject, guestArchitecture: String): String {
        val qemuName = when (normalizeArchitecture(guestArchitecture)) {
            "aarch64" -> "qemu-aarch64"
            "x86_64" -> "qemu-x86_64"
            else -> ""
        }
        if (qemuName.isBlank()) {
            return ""
        }
        val directQemu = File(state.optString("native_${qemuName.replace('-', '_')}_path"))
        if (directQemu.isFile && directQemu.canExecute()) {
            return directQemu.absolutePath
        }
        val nativeBinPath = state.optString("native_bin_path")
        val nativeQemu = File(nativeBinPath, qemuName)
        // Legacy state may not have direct fields yet. Accept only a shim which resolves
        // outside the writable prefix; a downloaded app-data ELF would fail with 126.
        val writablePrefix = File(state.optString("prefix_path"))
        return nativeQemu.absolutePath.takeIf {
            nativeQemu.canExecute() && runCatching {
                !isInsideDirectory(nativeQemu.canonicalFile, writablePrefix.canonicalFile)
            }.getOrDefault(false)
        }.orEmpty()
    }

    internal fun isInsideDirectory(candidate: File, directory: File): Boolean {
        val candidatePath = candidate.canonicalFile.path
        val directoryPath = directory.canonicalFile.path.trimEnd(File.separatorChar)
        return candidatePath == directoryPath || candidatePath.startsWith("$directoryPath${File.separator}")
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

    private fun writeAgentControl(
        context: Context,
        control: JSONObject,
        cancellationRequested: () -> Boolean = { false },
        publicationGate: AutomationPublicationGate? = null,
    ) {
        throwIfCancellationRequested(cancellationRequested)
        val file = agentControlFile(context)
        val controlParent = file.parentFile ?: error("Linux sandbox control file has no parent directory.")
        if (publicationGate == null) {
            controlParent.mkdirs()
        } else if (!controlParent.isDirectory) {
            error("Initialize the Linux sandbox controls manually before changing them from chat.")
        }
        val temporary = File(controlParent, ".${file.name}.${System.nanoTime()}.tmp")
        try {
            temporary.writeText(control.toString(), Charsets.UTF_8)
            throwIfCancellationRequested(cancellationRequested)
            publicationGate.publishValueIfActive(
                cancelledValue = {
                    throw CancellationException("Linux sandbox control change was stopped before its final commit")
                },
                publication = {
                    replaceStagedFileAtCommit(temporary, file)
                },
            )
        } finally {
            temporary.delete()
        }
    }

    private fun appPrivateStorageRoot(context: Context): File {
        return context.getExternalFilesDir(null)?.parentFile ?: context.filesDir
    }

    private fun agentControlFile(context: Context): File {
        return context.getExternalFilesDir(null)
            ?.let { File(it, "hermes-home/$AGENT_CONTROL_FILE_NAME") }
            ?: File(context.filesDir, "hermes-home/$AGENT_CONTROL_FILE_NAME")
    }

    private val READ_ONLY_ACTIONS = setOf("catalog", "status", "list")

    private val REQUEST_OWNED_UNCANCELLABLE_ACTIONS = setOf(
        "download",
        "install",
        "deploy",
        "bootstrap",
        "one_click_deploy",
        "one-click-deploy",
        "update",
        "upgrade",
        "refresh",
        "set_mirror",
        "switch_mirror",
        "mirror",
        "run",
        "remove",
        "uninstall",
        "delete",
    )
}
