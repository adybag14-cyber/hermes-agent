package com.mobilefork.hermesagent.ui.device

import com.mobilefork.hermesagent.device.HermesGlobalAction
import com.mobilefork.hermesagent.device.HermesSystemActionResult
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.hermesStringsFor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceOperationStatusTest {
    @Test
    fun sameVisibleStatusRetranslatesWhenLanguageChanges() {
        val visibleStatus = DeviceOperationStatus.SandboxRunning(
            action = "update",
            distroId = "debian-bookworm",
        )

        val english = hermesStringsFor(AppLanguage.ENGLISH).deviceStatusText(visibleStatus)
        val french = hermesStringsFor(AppLanguage.FRENCH).deviceStatusText(visibleStatus)
        val chinese = hermesStringsFor(AppLanguage.CHINESE).deviceStatusText(visibleStatus)

        assertNotEquals(english, french)
        assertNotEquals(english, chinese)
        assertTrue(french.contains("debian-bookworm"))
        assertTrue(chinese.contains("debian-bookworm"))
    }

    @Test
    fun everyDeviceOperationHeadlineIsAvailableInAllSixLanguages() {
        val statuses = listOf(
            DeviceOperationStatus.LinuxSuiteProvisioning,
            DeviceOperationStatus.LinuxSuiteReady,
            DeviceOperationStatus.LinuxSuiteInstalling,
            DeviceOperationStatus.LinuxSuiteInstalled("x86_64", 42),
            DeviceOperationStatus.LinuxSuiteFailed(LinuxSuiteFailureStage.Provisioning, "raw-provision-error"),
            DeviceOperationStatus.LinuxSuiteFailed(LinuxSuiteFailureStage.Installation, "raw-install-error"),
            DeviceOperationStatus.SandboxRunning("deploy", "debian-bookworm"),
            DeviceOperationStatus.SandboxCompleted("start", "debian-bookworm", "hermes-debian", 0),
            DeviceOperationStatus.SandboxFailed("update", "alpine-3-21", 17, "raw-sandbox-stderr"),
            DeviceOperationStatus.HostPackageRunning("update"),
            DeviceOperationStatus.HostPackageCompleted("upgrade", "5.1.107-65", "4.27.0"),
            DeviceOperationStatus.HostPackageFailed("install", "raw-host-error"),
            DeviceOperationStatus.DocumentImported("report.txt"),
            DeviceOperationStatus.ImportFailed("raw-import-error"),
            DeviceOperationStatus.SharedFolderSaved("Documents"),
            DeviceOperationStatus.SharedFolderCleared,
            DeviceOperationStatus.WorkspaceFileExported("report.txt"),
            DeviceOperationStatus.WorkspaceExportFailed("raw-export-error"),
            DeviceOperationStatus.DiagnosticsExported,
            DeviceOperationStatus.DiagnosticsExportFailed("raw-diagnostics-error"),
            DeviceOperationStatus.DiagnosticsCleared,
            DeviceOperationStatus.AccessibilityActionCompleted(HermesGlobalAction.QuickSettings),
            DeviceOperationStatus.AccessibilityEnableRequired,
            DeviceOperationStatus.AccessibilityNotConnected,
            DeviceOperationStatus.PermissionResult(DevicePermission.Notifications, granted = true),
            DeviceOperationStatus.PermissionResult(DevicePermission.Bluetooth, granted = false),
            DeviceOperationStatus.SystemControlResult("open_wifi_panel", succeeded = true),
            DeviceOperationStatus.SystemControlResult(
                "open_overlay_settings",
                succeeded = false,
                diagnosticDetail = "raw-system-error",
            ),
            DeviceOperationStatus.SystemControlResult("start_background_runtime", succeeded = true),
            DeviceOperationStatus.SystemControlResult(
                "start_floating_button",
                succeeded = false,
                diagnosticDetail = "raw-overlay-error",
            ),
        )
        val englishStrings = hermesStringsFor(AppLanguage.ENGLISH)

        statuses.forEach { status ->
            val englishHeadline = englishStrings.deviceStatusText(status)
            assertTrue("English headline should not be blank for $status", englishHeadline.isNotBlank())
            status.diagnosticDetail?.let { diagnostic ->
                assertFalse("Raw diagnostic must not enter the headline", englishHeadline.contains(diagnostic))
            }
            AppLanguage.entries.filterNot { it == AppLanguage.ENGLISH }.forEach { language ->
                val localized = hermesStringsFor(language).deviceStatusText(status)
                assertTrue("$language headline should not be blank for $status", localized.isNotBlank())
                assertNotEquals("$language should re-render $status", englishHeadline, localized)
                status.diagnosticDetail?.let { diagnostic ->
                    assertFalse("Raw diagnostic must not enter the $language headline", localized.contains(diagnostic))
                }
            }
        }
    }

    @Test
    fun diagnosticDetailIsLabeledButKeptOutOfLocalizedHeadline() {
        val status = DeviceOperationStatus.SandboxFailed(
            action = "update",
            distroId = "debian-bookworm",
            exitCode = 23,
            diagnosticDetail = "apt stderr: signature rejected",
        )

        AppLanguage.entries.forEach { language ->
            val strings = hermesStringsFor(language)
            val headline = strings.deviceStatusText(status)
            val detail = strings.deviceStatusDiagnosticDetail(status.diagnosticDetail.orEmpty())
            assertFalse(headline.contains("signature rejected"))
            assertTrue(detail.contains("signature rejected"))
            assertNotEquals(headline, detail)
        }
    }

    @Test
    fun resultFactoriesDriveTypedRunningCompletedAndFailedTransitions() {
        val running = DeviceUiState(
            status = DeviceOperationStatus.SandboxRunning("deploy", "debian-bookworm"),
        )
        val completed = running.copy(
            status = sandboxStatusFromResult(
                action = "deploy",
                distroId = "debian-bookworm",
                sandboxName = "hermes-debian",
                result = JSONObject()
                    .put("ok", true)
                    .put("exit_code", 0)
                    .put("message", "raw bridge success text"),
            ),
        )
        val failed = completed.copy(
            status = sandboxStatusFromResult(
                action = "update",
                distroId = "debian-bookworm",
                sandboxName = "hermes-debian",
                result = JSONObject()
                    .put("ok", false)
                    .put("exit_code", 31)
                    .put("message", "package transaction failed")
                    .put("stderr", "raw sandbox stderr"),
            ),
        )

        assertTrue(running.status is DeviceOperationStatus.SandboxRunning)
        assertTrue(completed.status is DeviceOperationStatus.SandboxCompleted)
        assertNull(completed.status?.diagnosticDetail)
        val failedStatus = failed.status as DeviceOperationStatus.SandboxFailed
        assertEquals(31, failedStatus.exitCode)
        assertTrue(failedStatus.diagnosticDetail.orEmpty().contains("raw sandbox stderr"))
        assertFalse(
            hermesStringsFor(AppLanguage.ENGLISH)
                .deviceStatusText(failedStatus)
                .contains("raw sandbox stderr"),
        )
    }

    @Test
    fun hostAndSystemResultsKeepBridgeTextAsFailureDiagnosticsOnly() {
        val hostSuccess = hostPackageStatusFromResult(
            action = "upgrade",
            result = JSONObject()
                .put("ok", true)
                .put("exit_code", 0)
                .put("message", "raw host success text")
                .put("proot_version", "5.1.107-65"),
        ) as DeviceOperationStatus.HostPackageCompleted
        val hostFailure = hostPackageStatusFromResult(
            action = "install",
            result = JSONObject()
                .put("ok", false)
                .put("exit_code", 1)
                .put("message", "signed APK update required"),
        ) as DeviceOperationStatus.HostPackageFailed
        val systemSuccess = systemControlStatusFromResult(
            HermesSystemActionResult(true, "open_wifi_panel", "Opened raw bridge target"),
        ) as DeviceOperationStatus.SystemControlResult
        val systemFailure = systemControlStatusFromResult(
            HermesSystemActionResult(false, "open_wifi_panel", "ActivityNotFoundException"),
        ) as DeviceOperationStatus.SystemControlResult

        assertNull(hostSuccess.diagnosticDetail)
        assertTrue(hostFailure.diagnosticDetail.orEmpty().contains("signed APK update required"))
        assertNull(systemSuccess.diagnosticDetail)
        assertEquals("ActivityNotFoundException", systemFailure.diagnosticDetail)
    }
}
