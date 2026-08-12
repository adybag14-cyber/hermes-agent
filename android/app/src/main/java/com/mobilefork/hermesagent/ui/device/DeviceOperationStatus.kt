package com.mobilefork.hermesagent.ui.device

import com.mobilefork.hermesagent.device.HermesGlobalAction
import com.mobilefork.hermesagent.device.HermesSystemActionResult
import org.json.JSONObject

/**
 * Language-neutral state for the short operation headline shown on the Device screen.
 *
 * User-visible copy is deliberately rendered later by [HermesStrings][com.mobilefork.hermesagent.ui.i18n.HermesStrings]
 * so an operation which is already visible is translated again when the app language changes.
 * Untrusted/native error text is retained only as diagnostic detail, never as the headline.
 */
sealed interface DeviceOperationStatus {
    val diagnosticDetail: String?
        get() = null

    data object LinuxSuiteProvisioning : DeviceOperationStatus
    data object LinuxSuiteReady : DeviceOperationStatus
    data object LinuxSuiteInstalling : DeviceOperationStatus

    data class LinuxSuiteInstalled(
        val architecture: String,
        val packageCount: Int,
    ) : DeviceOperationStatus

    data class LinuxSuiteFailed(
        val stage: LinuxSuiteFailureStage,
        override val diagnosticDetail: String?,
    ) : DeviceOperationStatus

    data class SandboxRunning(
        val action: String,
        val distroId: String,
    ) : DeviceOperationStatus

    data class SandboxCompleted(
        val action: String,
        val distroId: String,
        val sandboxName: String,
        val exitCode: Int,
        override val diagnosticDetail: String? = null,
    ) : DeviceOperationStatus

    data class SandboxFailed(
        val action: String,
        val distroId: String,
        val exitCode: Int? = null,
        override val diagnosticDetail: String?,
    ) : DeviceOperationStatus

    data class HostPackageRunning(
        val action: String,
    ) : DeviceOperationStatus

    data class HostPackageCompleted(
        val action: String,
        val prootVersion: String = "",
        val prootDistroVersion: String = "",
        override val diagnosticDetail: String? = null,
    ) : DeviceOperationStatus

    data class HostPackageFailed(
        val action: String,
        override val diagnosticDetail: String?,
    ) : DeviceOperationStatus

    data class DocumentImported(val fileName: String) : DeviceOperationStatus

    data class ImportFailed(
        override val diagnosticDetail: String?,
    ) : DeviceOperationStatus

    data class SharedFolderSaved(val label: String) : DeviceOperationStatus
    data object SharedFolderCleared : DeviceOperationStatus
    data class WorkspaceFileExported(val fileName: String) : DeviceOperationStatus

    data class WorkspaceExportFailed(
        override val diagnosticDetail: String?,
    ) : DeviceOperationStatus

    data object DiagnosticsExported : DeviceOperationStatus

    data class DiagnosticsExportFailed(
        override val diagnosticDetail: String?,
    ) : DeviceOperationStatus

    data object DiagnosticsCleared : DeviceOperationStatus
    data class AccessibilityActionCompleted(val action: HermesGlobalAction) : DeviceOperationStatus
    data object AccessibilityEnableRequired : DeviceOperationStatus
    data object AccessibilityNotConnected : DeviceOperationStatus

    data class PermissionResult(
        val permission: DevicePermission,
        val granted: Boolean,
    ) : DeviceOperationStatus

    data class SystemControlResult(
        val action: String,
        val succeeded: Boolean,
        override val diagnosticDetail: String? = null,
    ) : DeviceOperationStatus
}

enum class LinuxSuiteFailureStage {
    Provisioning,
    Installation,
}

enum class DevicePermission {
    Notifications,
    Bluetooth,
}

internal fun deviceDiagnosticDetail(error: Throwable): String =
    (error.message ?: error.javaClass.simpleName)
        .trim()
        .ifBlank { error.javaClass.simpleName }
        .take(MAX_DEVICE_DIAGNOSTIC_DETAIL_CHARS)

internal fun sandboxStatusFromResult(
    action: String,
    distroId: String,
    sandboxName: String,
    result: JSONObject,
): DeviceOperationStatus {
    val exitCode = result.optInt("exit_code", -1)
    val succeeded = result.optBoolean("ok", exitCode == 0) && exitCode == 0
    val detail = diagnosticDetailFromResult(result, includeMessage = !succeeded)
    return if (succeeded) {
        DeviceOperationStatus.SandboxCompleted(
            action = action,
            distroId = distroId,
            sandboxName = sandboxName,
            exitCode = exitCode,
            diagnosticDetail = detail,
        )
    } else {
        DeviceOperationStatus.SandboxFailed(
            action = action,
            distroId = distroId,
            exitCode = exitCode.takeIf { it >= 0 },
            diagnosticDetail = detail,
        )
    }
}

internal fun hostPackageStatusFromResult(
    action: String,
    result: JSONObject,
): DeviceOperationStatus {
    val exitCode = result.optInt("exit_code", -1)
    val succeeded = result.optBoolean("ok", exitCode == 0) && exitCode == 0
    val detail = diagnosticDetailFromResult(result, includeMessage = !succeeded)
    return if (succeeded) {
        DeviceOperationStatus.HostPackageCompleted(
            action = action,
            prootVersion = result.optString("proot_version"),
            prootDistroVersion = result.optString("proot_distro_version"),
            diagnosticDetail = detail,
        )
    } else {
        DeviceOperationStatus.HostPackageFailed(
            action = action,
            diagnosticDetail = detail,
        )
    }
}

internal fun systemControlStatusFromResult(result: HermesSystemActionResult): DeviceOperationStatus =
    DeviceOperationStatus.SystemControlResult(
        action = result.action,
        succeeded = result.success,
        diagnosticDetail = result.message
            .trim()
            .takeIf { !result.success && it.isNotBlank() }
            ?.take(MAX_DEVICE_DIAGNOSTIC_DETAIL_CHARS),
    )

private fun diagnosticDetailFromResult(result: JSONObject, includeMessage: Boolean): String? {
    val installResult = result.optJSONObject("install_result")
    return listOf(
        result.optString("message").takeIf { includeMessage }.orEmpty(),
        result.optString("error"),
        installResult?.optString("error").orEmpty(),
        installResult?.optString("stderr").orEmpty(),
        result.optString("stderr"),
    )
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(" · ")
        .take(MAX_DEVICE_DIAGNOSTIC_DETAIL_CHARS)
        .takeIf { it.isNotBlank() }
}

private const val MAX_DEVICE_DIAGNOSTIC_DETAIL_CHARS = 480
