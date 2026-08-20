package com.mobilefork.hermesagent

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilefork.hermesagent.device.HermesLinuxSandboxBridge
import com.mobilefork.hermesagent.device.HermesLinuxSubsystemBridge
import com.mobilefork.hermesagent.device.NativeAndroidShellTool
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class LiveDebianSandboxInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun oneClickDebianRunsGuestBinariesWithoutWritableHostFallback() {
        assumeTrue(
            "Pass -e run_live_debian_sandbox true to allow the real Debian network deploy",
            InstrumentationRegistry.getArguments().getString("run_live_debian_sandbox") == "true",
        )
        val sandboxName = InstrumentationRegistry.getArguments()
            .getString("live_debian_sandbox_name")
            .orEmpty()
            .ifBlank { "hermes-debian-issue16-proof" }
        val profile = InstrumentationRegistry.getArguments()
            .getString("profile")
            .orEmpty()
            .trim()
        require(profile == "phone-compact" || profile == "tablet") {
            "Issue 16 release evidence requires -e profile phone-compact|tablet"
        }

        val identity = ReleaseDeviceEvidenceIdentity.requireBound(context)
        val releaseIdentity = JSONObject()
            .put("release_source_digest", identity.releaseSourceDigest)
            .put("candidate_apk_sha256", identity.candidateApkSha256)
            .put("instrumentation_apk_sha256", identity.instrumentationApkSha256)
            .put("evidence_run_id", identity.evidenceRunId)
            .put("package_id", identity.packageId)
            .put("version_name", identity.versionName)
            .put("version_code", identity.versionCode)
            .put("release_tag", "v${identity.versionName}")
            .put("build_variant", identity.buildVariant)
            .put("lite_rt_lm_coordinate", identity.liteRtLmCoordinate)
            .put("device_serial", identity.deviceSerial)
            .put("avd_name", identity.avdName)
            .put("device_boot_id", identity.deviceBootId)
            .put("device_model", Build.MODEL)
            .put("build_fingerprint", Build.FINGERPRINT)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("profile", profile)

        val linuxState = HermesLinuxSubsystemBridge.ensureInstalled(context)
        val androidAbi = linuxState.optString("android_abi")
        val packagedAssetPath = "hermes-linux/$androidAbi/manifest.json"
        val packagedAssetDigest = runCatching { sha256Asset(packagedAssetPath) }.getOrDefault("")
        val stateAssetDigest = linuxState.optString("asset_manifest_sha256")
        val nativeLibraryDir = linuxState.optString("native_library_dir")
        val prootRoute = trustedNativeRoute(
            routePath = linuxState.optString("native_proot_path"),
            nativeLibraryDir = nativeLibraryDir,
            expectedFileName = "libhermes_exec_bin_proot.so",
        )
        val qemuRoute = trustedNativeRoute(
            routePath = linuxState.optString("native_qemu_aarch64_path"),
            nativeLibraryDir = nativeLibraryDir,
            expectedFileName = "libhermes_exec_bin_qemu_aarch64.so",
        )
        val coreutilsRoute = trustedNativeRoute(
            routePath = File(linuxState.optString("native_bin_path"), "printenv").absolutePath,
            nativeLibraryDir = nativeLibraryDir,
            expectedFileName = "libhermes_exec_bin_coreutils.so",
        )
        val hostPrintenvCommand = "printenv HERMES_ANDROID_PROOT_EXECUTABLE"
        val hostPrintenv = runCatching {
            NativeAndroidShellTool.run(
                context = context,
                command = hostPrintenvCommand,
                timeoutSeconds = 20,
                includeLinuxSandboxStatus = false,
            )
        }.getOrElse { errorResult("host_printenv", it) }
        val packagedAssetsPresent =
            packagedAssetDigest.matches(SHA256) && packagedAssetDigest == stateAssetDigest &&
                linuxState.optString("execution_mode") == "embedded_termux" &&
                linuxState.optBoolean("uses_termux", false) &&
                linuxState.optString("asset_refresh_error").isBlank()
        val packagedRuntime = JSONObject()
            .put("packaged_asset_path", packagedAssetPath)
            .put("packaged_asset_sha256", packagedAssetDigest)
            .put("packaged_asset_skipped", !packagedAssetsPresent)
            .put("packaged_assets_present", packagedAssetsPresent)
            .put("execution_mode", linuxState.optString("execution_mode"))
            .put("uses_termux", linuxState.optBoolean("uses_termux", false))
            .put("android_abi", androidAbi)
            .put("asset_manifest_sha256", stateAssetDigest)
            .put("asset_refresh_error", linuxState.optString("asset_refresh_error"))
            .put("native_execution_route", linuxState.optString("native_execution_route"))
            .put("proot_direct_exec_patch_ready", linuxState.optBoolean("proot_direct_exec_patch_ready", false))
            .put("host_printenv_command", hostPrintenvCommand)
            .put("host_printenv_exit_code", hostPrintenv.optInt("exit_code", -1))
            .put("host_printenv_stdout", hostPrintenv.optString("output"))
            .put("host_printenv_stderr", hostPrintenv.optString("error"))
            .put("proot_executable", hostPrintenv.optString("output").trim())
            .put(
                "trusted_native_routes",
                JSONObject()
                    .put("proot", prootRoute)
                    .put("qemu_user", qemuRoute)
                    .put("coreutils", coreutilsRoute),
            )

        val deploy = sandboxAction(
            action = "deploy",
            sandboxName = sandboxName,
            timeoutSeconds = 900,
        )
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("issue16_deploy_result", deploy.toString()) },
        )

        val updateResult = deploy.optJSONObject("update_result") ?: JSONObject()
        val guestCaBundle = updateResult.optJSONObject("guest_ca_bundle") ?: JSONObject()
        val sandbox = JSONObject()
            .put("name", sandboxName)
            .put("fresh_requested", true)
            .put("sandbox_existed_before", deploy.optBoolean("sandbox_existed_before", true))
            .put("deploy_exit_code", deploy.optInt("exit_code", -1))
            .put("deployment_completed", deploy.optBoolean("deployment_completed", false))
            .put("failed_phase", deploy.optString("failed_phase"))
            .put("sandbox_state", deploy.optString("sandbox_state"))
            .put("sandbox_preserved_for_retry", deploy.optBoolean("sandbox_preserved_for_retry", false))
            .put("update_exit_code", updateResult.optInt("exit_code", -1))
            .put("update_command", updateResult.optString("update_command"))
            .put("requested_timeout_seconds", 900)
            .put("guest_ca_bundle", guestCaBundle)

        val expectedGuestPath = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        val pathCommand = "printf '%s\\n' \"\$PATH\""
        val idRouteCommand = "command -v id"
        val unameRouteCommand = "command -v uname"
        val curlRouteCommand = "command -v curl"
        val idCommand = "id"
        val unameCommand = "uname -a"
        val curlVersionCommand = "curl --version"
        val httpsCommand = "curl -fsS https://example.com/ >/dev/null && printf 'HTTPS_OK\\n'"
        val pathResult = runGuest(sandboxName, pathCommand)
        val idRouteResult = runGuest(sandboxName, idRouteCommand)
        val unameRouteResult = runGuest(sandboxName, unameRouteCommand)
        val curlRouteResult = runGuest(sandboxName, curlRouteCommand)
        val idResult = runGuest(sandboxName, idCommand)
        val unameResult = runGuest(sandboxName, unameCommand)
        val curlVersionResult = runGuest(sandboxName, curlVersionCommand)
        val httpsResult = runGuest(sandboxName, httpsCommand)
        val observedGuestPath = pathResult.optString("output").trim()
        val guestRouting = JSONObject()
            .put("expected_path", expectedGuestPath)
            .put("observed_path", observedGuestPath)
            .put("path_command", pathCommand)
            .put("path_exit_code", pathResult.optInt("exit_code", -1))
            .put(
                "guest_only_path",
                pathResult.optInt("exit_code", -1) == 0 && observedGuestPath == expectedGuestPath &&
                    !observedGuestPath.contains("/data/"),
            )
            .put("id_route", commandEvidence(idRouteCommand, idRouteResult))
            .put("uname_route", commandEvidence(unameRouteCommand, unameRouteResult))
            .put("curl_route", commandEvidence(curlRouteCommand, curlRouteResult))
            .put("id_path", idRouteResult.optString("output").trim())
            .put("uname_path", unameRouteResult.optString("output").trim())
            .put("curl_path", curlRouteResult.optString("output").trim())
        val commands = JSONObject()
            .put("id", commandEvidence(idCommand, idResult))
            .put("uname", commandEvidence(unameCommand, unameResult))
            .put("curl_version", commandEvidence(curlVersionCommand, curlVersionResult))
            .put("https", commandEvidence(httpsCommand, httpsResult))

        val validationErrors = JSONArray()
        fun requireEvidence(condition: Boolean, message: String) {
            if (!condition) validationErrors.put(message)
        }
        requireEvidence(identity.versionName.matches(SEMVER), "release version is not semver")
        requireEvidence(packagedAssetsPresent, "full packaged Linux asset manifest is absent or mismatched")
        requireEvidence(!packagedRuntime.optBoolean("packaged_asset_skipped", true), "packaged Linux assets were skipped")
        requireEvidence(
            linuxState.optString("native_execution_route") == "apk_native_library_direct",
            "native route is not apk_native_library_direct",
        )
        requireEvidence(linuxState.optBoolean("proot_direct_exec_patch_ready", false), "proot direct-exec patch is not ready")
        requireEvidence(prootRoute.optBoolean("trusted", false), "proot route is not trusted APK-native code")
        requireEvidence(qemuRoute.optBoolean("trusted", false), "qemu-user route is not trusted APK-native code")
        requireEvidence(coreutilsRoute.optBoolean("trusted", false), "coreutils route is not trusted APK-native code")
        requireEvidence(hostPrintenv.optInt("exit_code", -1) == 0, "packaged coreutils printenv failed")
        requireEvidence(
            hostPrintenv.optString("output").trim() == prootRoute.optString("path"),
            "printenv did not resolve the trusted packaged proot path",
        )
        requireEvidence(!deploy.optBoolean("sandbox_existed_before", true), "sandbox was not fresh")
        requireEvidence(deploy.optInt("exit_code", -1) == 0, "one-click deploy failed")
        requireEvidence(deploy.optBoolean("deployment_completed", false), "one-click deploy did not complete")
        requireEvidence(updateResult.optInt("exit_code", -1) == 0, "Debian update/curl install failed")
        requireEvidence(guestCaBundle.optInt("exit_code", -1) == 0, "guest CA bundle provisioning failed")
        requireEvidence(guestCaBundle.optInt("certificate_count", 0) > 0, "guest CA bundle is empty")
        requireEvidence(guestCaBundle.optString("path").isNotBlank(), "guest CA bundle path is missing")
        requireEvidence(guestCaBundle.optString("source").isNotBlank(), "guest CA bundle source is missing")
        requireEvidence(guestCaBundle.optString("sha256").matches(SHA256), "guest CA bundle SHA-256 is missing")
        requireEvidence(guestRouting.optBoolean("guest_only_path", false), "guest PATH is not isolated")
        requireEvidence(idRouteResult.optInt("exit_code", -1) == 0, "guest id route failed")
        requireEvidence(unameRouteResult.optInt("exit_code", -1) == 0, "guest uname route failed")
        requireEvidence(curlRouteResult.optInt("exit_code", -1) == 0, "guest curl route failed")
        requireEvidence(guestRouting.optString("id_path") == "/usr/bin/id", "id did not route to /usr/bin/id")
        requireEvidence(guestRouting.optString("uname_path") == "/usr/bin/uname", "uname did not route to /usr/bin/uname")
        requireEvidence(guestRouting.optString("curl_path") == "/usr/bin/curl", "curl did not route to /usr/bin/curl")
        requireEvidence(idResult.optInt("exit_code", -1) == 0, "id exited nonzero")
        requireEvidence(idResult.optString("output").contains("uid=0(root)"), "id did not report guest root")
        requireEvidence(unameResult.optInt("exit_code", -1) == 0, "uname exited nonzero")
        requireEvidence(unameResult.optString("output").contains("GNU/Linux"), "uname did not report GNU/Linux")
        requireEvidence(curlVersionResult.optInt("exit_code", -1) == 0, "curl --version exited nonzero")
        requireEvidence(curlVersionResult.optString("output").contains("curl "), "curl version output is missing")
        requireEvidence(httpsResult.optInt("exit_code", -1) == 0, "HTTPS curl exited nonzero")
        requireEvidence(httpsResult.optString("output").contains("HTTPS_OK"), "HTTPS curl did not emit HTTPS_OK")
        listOf(pathResult, idRouteResult, unameRouteResult, curlRouteResult, idResult, unameResult, curlVersionResult, httpsResult)
            .forEach { result ->
                requireEvidence(!result.optString("output").contains("Permission denied"), "guest stdout contains Permission denied")
                requireEvidence(!result.optString("error").contains("Permission denied"), "guest stderr contains Permission denied")
                requireEvidence(
                    result.optString("sandbox_execution_mode") == "proot_distro_qemu",
                    "guest command did not use proot_distro_qemu",
                )
            }

        val proofPassedBeforeCleanup = validationErrors.length() == 0
        val cleanupAction = if (proofPassedBeforeCleanup) "uninstall" else "stop"
        val cleanupResult = sandboxAction(
            action = cleanupAction,
            sandboxName = sandboxName,
        )
        val cleanupStatus = sandboxAction(
            action = "status",
            sandboxName = sandboxName,
        )
        val sandboxPresentAfterCleanup = installedSandboxNames(cleanupStatus).contains(sandboxName)
        val agentShellDisabled = !cleanupStatus.optBoolean("agent_shell_enabled", true)
        val cleanRemoved = cleanupResult.optInt("exit_code", -1) == 0 &&
            cleanupStatus.optInt("exit_code", -1) == 0 && agentShellDisabled && !sandboxPresentAfterCleanup
        val cleanPreserved = cleanupResult.optInt("exit_code", -1) == 0 &&
            cleanupStatus.optInt("exit_code", -1) == 0 && agentShellDisabled && sandboxPresentAfterCleanup
        val cleanupDisposition = when {
            proofPassedBeforeCleanup && cleanRemoved -> "sandbox_removed_stopped"
            !proofPassedBeforeCleanup && cleanPreserved -> "sandbox_preserved_stopped"
            !proofPassedBeforeCleanup && agentShellDisabled && !sandboxPresentAfterCleanup -> "sandbox_absent_stopped"
            else -> "cleanup_incomplete"
        }
        val cleanup = JSONObject()
            .put("action", cleanupAction)
            .put("exit_code", cleanupResult.optInt("exit_code", -1))
            .put("status_exit_code", cleanupStatus.optInt("exit_code", -1))
            .put("agent_shell_enabled", cleanupStatus.optBoolean("agent_shell_enabled", true))
            .put("active_sandbox_name", cleanupStatus.optString("active_sandbox_name"))
            .put("sandbox_name", sandboxName)
            .put("sandbox_present", sandboxPresentAfterCleanup)
            .put("sandbox_preserved", sandboxPresentAfterCleanup)
            .put("sandbox_removed", !sandboxPresentAfterCleanup)
            .put("disposition", cleanupDisposition)
        if (proofPassedBeforeCleanup) {
            requireEvidence(cleanRemoved, "successful proof did not uninstall the disposable sandbox and disable agent shell")
        } else if (deploy.optBoolean("sandbox_present_after_deploy", false)) {
            requireEvidence(cleanPreserved, "failed proof did not stop and preserve the incomplete sandbox")
        } else {
            requireEvidence(agentShellDisabled, "failed proof cleanup did not disable agent shell")
        }

        val passed = proofPassedBeforeCleanup && validationErrors.length() == 0
        val evidence = JSONObject()
            .put("schema", "hermes-android-issue-16-debian-sandbox-v1")
            .put("issue_number", 16)
            .put("result", if (passed) "pass" else "fail")
            .put("overall_exit_code", if (passed) 0 else 1)
            .put("release_identity", releaseIdentity)
            .put("packaged_runtime", packagedRuntime)
            .put("sandbox", sandbox)
            .put("guest_routing", guestRouting)
            .put("commands", commands)
            .put("cleanup", cleanup)
            .put("validation_errors", validationErrors)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("issue16_runtime_proof", evidence.toString()) },
        )
        assertTrue(evidence.toString(2), passed)
    }

    private fun runGuest(sandboxName: String, command: String): JSONObject {
        return sandboxAction(
            action = "run",
            sandboxName = sandboxName,
            command = command,
            timeoutSeconds = 180,
        )
    }

    private fun sandboxAction(
        action: String,
        sandboxName: String,
        command: String = "",
        timeoutSeconds: Long = 900,
    ): JSONObject {
        return runCatching {
            HermesLinuxSandboxBridge.performAction(
                context = context,
                action = action,
                distroId = "debian-bookworm",
                name = sandboxName,
                command = command,
                timeoutSeconds = timeoutSeconds,
            )
        }.getOrElse { errorResult(action, it) }
    }

    private fun errorResult(action: String, error: Throwable): JSONObject {
        return JSONObject()
            .put("action", action)
            .put("exit_code", -1)
            .put("error", error.message ?: error.javaClass.simpleName)
    }

    private fun commandEvidence(command: String, result: JSONObject): JSONObject {
        return JSONObject()
            .put("command", command)
            .put("exit_code", result.optInt("exit_code", -1))
            .put("stdout", result.optString("output"))
            .put("stderr", result.optString("error"))
            .put("sandbox_execution_mode", result.optString("sandbox_execution_mode"))
    }

    private fun trustedNativeRoute(
        routePath: String,
        nativeLibraryDir: String,
        expectedFileName: String,
    ): JSONObject {
        if (routePath.isBlank() || nativeLibraryDir.isBlank()) {
            return JSONObject()
                .put("route_path", routePath)
                .put("path", "")
                .put("trusted", false)
        }
        val route = File(routePath)
        val resolved = runCatching { route.canonicalFile }.getOrNull()
        val trustedRoot = runCatching { File(nativeLibraryDir).canonicalFile }.getOrNull()
        val trusted = resolved != null && trustedRoot != null && route.exists() &&
            resolved.isFile && resolved.canExecute() && resolved.parentFile == trustedRoot &&
            resolved.name == expectedFileName
        return JSONObject()
            .put("route_path", route.absolutePath)
            .put("path", resolved?.absolutePath.orEmpty())
            .put("expected_file_name", expectedFileName)
            .put("exists", resolved?.isFile == true)
            .put("executable", resolved?.canExecute() == true)
            .put("trusted", trusted)
    }

    private fun sha256Asset(assetPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(assetPath).buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun installedSandboxNames(status: JSONObject): Set<String> {
        val installed = status.optJSONArray("installed_sandboxes") ?: return emptySet()
        return buildSet {
            for (index in 0 until installed.length()) {
                val name = installed.optJSONObject(index)?.optString("name").orEmpty()
                if (name.isNotBlank()) add(name)
            }
        }
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
    private val SEMVER = Regex("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?")
}
