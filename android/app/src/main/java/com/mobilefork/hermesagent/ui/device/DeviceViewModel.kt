package com.mobilefork.hermesagent.ui.device

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobilefork.hermesagent.data.DeviceCapabilityStore
import com.mobilefork.hermesagent.device.DeviceStateWriter
import com.mobilefork.hermesagent.device.HermesAccessibilityController
import com.mobilefork.hermesagent.device.HermesAutomationBridge
import com.mobilefork.hermesagent.device.HermesCrashLogStore
import com.mobilefork.hermesagent.device.HermesGlobalAction
import com.mobilefork.hermesagent.device.HermesLinuxSandboxBridge
import com.mobilefork.hermesagent.device.HermesLinuxSandboxCatalog
import com.mobilefork.hermesagent.device.HermesLinuxSubsystemBridge
import com.mobilefork.hermesagent.device.HermesSystemControlBridge
import com.mobilefork.hermesagent.device.HermesTermuxPackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class WorkspaceFileUi(
    val name: String,
    val sizeLabel: String,
    val modifiedLabel: String,
)

data class DeviceUiState(
    val workspacePath: String = "",
    val workspaceFiles: List<WorkspaceFileUi> = emptyList(),
    val sharedFolderLabel: String = "No shared folder granted yet",
    val sharedFolderUri: String = "",
    val linuxEnabled: Boolean = false,
    val linuxAndroidAbi: String = "",
    val linuxTermuxArch: String = "",
    val linuxPrefixPath: String = "",
    val linuxBashPath: String = "",
    val linuxHomePath: String = "",
    val linuxTmpPath: String = "",
    val linuxPackageCount: Int = 0,
    val hostPkgProotVersion: String = "",
    val hostPkgProotDistroVersion: String = "",
    val hostPkgMirrorProfile: String = "default",
    val hostPkgIndexPackageCount: Int = 0,
    val hostPkgInstalledCount: Int = 0,
    val sandboxStorageRoot: String = "",
    val sandboxAgentEnabled: Boolean = false,
    val activeSandboxName: String = "",
    val installedSandboxCount: Int = 0,
    val installedSandboxNames: List<String> = emptyList(),
    val accessibilityEnabled: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val wifiEnabled: Boolean = false,
    val activeNetworkLabel: String = "Offline",
    val airplaneModeEnabled: Boolean = false,
    val activeNetworkMetered: Boolean = false,
    val dataSaverEnabled: Boolean = false,
    val bluetoothSupported: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val bluetoothPermissionGranted: Boolean = false,
    val pairedBluetoothDevices: List<String> = emptyList(),
    val usbHostSupported: Boolean = false,
    val usbDeviceCount: Int = 0,
    val usbDevices: List<String> = emptyList(),
    val nfcSupported: Boolean = false,
    val nfcEnabled: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val notificationPermissionGranted: Boolean = true,
    val backgroundPersistenceEnabled: Boolean = false,
    val runtimeServiceRunning: Boolean = false,
    val floatingButtonEnabled: Boolean = false,
    val floatingButtonRunning: Boolean = false,
    val operatorStandbyReady: Boolean = false,
    val automationCount: Int = 0,
    val enabledAutomationCount: Int = 0,
    val externalTriggerCount: Int = 0,
    val remoteDispatchCount: Int = 0,
    val recentAutomationRunCount: Int = 0,
    val lastAutomationRunLabel: String = "",
    val lastAutomationRunResult: String = "",
    val lastAutomationRunSuccess: Boolean? = null,
    val lastDispatchTaskName: String = "",
    val lastDispatchSource: String = "",
    val lastDispatchChannel: String = "",
    val operatorModelProvider: String = "",
    val operatorModelName: String = "",
    val operatorVisionCapable: Boolean = false,
    val diagnosticsLogStatusLabel: String = "No crash captured",
    val diagnosticsLogCapturedAtLabel: String = "",
    val diagnosticsLogExceptionType: String = "",
    val diagnosticsLogPreviewLines: List<String> = emptyList(),
    val diagnosticsLogExportFileName: String = "hermes-diagnostics-logs.txt",
    val diagnosticsLogExportReady: Boolean = true,
    val lastCrashPresent: Boolean = false,
    val resizableWindowSupport: Boolean = true,
    val freeformWindowSupported: Boolean = false,
    val status: DeviceOperationStatus? = null,
)

class DeviceViewModel(application: Application) : AndroidViewModel(application) {
    private val capabilityStore = DeviceCapabilityStore(application)

    private val _uiState = MutableStateFlow(
        buildState(DeviceOperationStatus.LinuxSuiteProvisioning),
    )
    val uiState: StateFlow<DeviceUiState> = _uiState.asStateFlow()

    init {
        DeviceStateWriter.write(application)
        viewModelScope.launch(Dispatchers.IO) {
            val status: DeviceOperationStatus = runCatching {
                HermesLinuxSubsystemBridge.ensureInstalled(application)
                DeviceOperationStatus.LinuxSuiteReady
            }.getOrElse { error ->
                DeviceOperationStatus.LinuxSuiteFailed(
                    stage = LinuxSuiteFailureStage.Provisioning,
                    diagnosticDetail = deviceDiagnosticDetail(error),
                )
            }
            DeviceStateWriter.write(application)
            _uiState.value = buildState(status)
        }
    }

    fun refresh(status: DeviceOperationStatus? = _uiState.value.status) {
        val context = getApplication<Application>()
        DeviceStateWriter.write(context)
        _uiState.value = buildState(status)
    }

    fun performSandboxAction(
        action: String,
        distroId: String = "debian-bookworm",
        mirrorProfile: String = "",
    ) {
        val (resolvedDistroId, sandboxName) = resolveSandboxTarget(distroId)
        val context = getApplication<Application>()
        _uiState.value = _uiState.value.copy(
            status = DeviceOperationStatus.SandboxRunning(
                action = action,
                distroId = resolvedDistroId,
            ),
        )
        viewModelScope.launch(Dispatchers.IO) {
            val status: DeviceOperationStatus = runCatching {
                HermesLinuxSubsystemBridge.ensureInstalled(context)
                val result = HermesLinuxSandboxBridge.performAction(
                    context = context,
                    action = action,
                    distroId = resolvedDistroId,
                    name = sandboxName,
                    mirrorProfile = mirrorProfile,
                    timeoutSeconds = 900,
                )
                sandboxStatusFromResult(
                    action = action,
                    distroId = resolvedDistroId,
                    sandboxName = sandboxName,
                    result = result,
                )
            }.getOrElse { error ->
                DeviceOperationStatus.SandboxFailed(
                    action = action,
                    distroId = resolvedDistroId,
                    diagnosticDetail = deviceDiagnosticDetail(error),
                )
            }
            DeviceStateWriter.write(context)
            _uiState.value = buildState(status)
        }
    }

    fun installLinuxSuite() {
        val context = getApplication<Application>()
        _uiState.value = _uiState.value.copy(status = DeviceOperationStatus.LinuxSuiteInstalling)
        viewModelScope.launch(Dispatchers.IO) {
            val status: DeviceOperationStatus = runCatching {
                val state = HermesLinuxSubsystemBridge.ensureInstalled(context)
                val packageCount = state.optJSONArray("packages")?.length() ?: 0
                val arch = state.optString("termux_arch").ifBlank { state.optString("android_abi") }
                DeviceOperationStatus.LinuxSuiteInstalled(
                    architecture = arch,
                    packageCount = packageCount,
                )
            }.getOrElse { error ->
                DeviceOperationStatus.LinuxSuiteFailed(
                    stage = LinuxSuiteFailureStage.Installation,
                    diagnosticDetail = deviceDiagnosticDetail(error),
                )
            }
            DeviceStateWriter.write(context)
            _uiState.value = buildState(status)
        }
    }

    fun performHostPkgAction(action: String, packages: List<String> = emptyList()) {
        val context = getApplication<Application>()
        _uiState.value = _uiState.value.copy(
            status = DeviceOperationStatus.HostPackageRunning(action),
        )
        viewModelScope.launch(Dispatchers.IO) {
            val status: DeviceOperationStatus = runCatching {
                val result = HermesTermuxPackageManager.performAction(
                    context = context,
                    action = action,
                    packages = packages,
                )
                hostPackageStatusFromResult(action, result)
            }.getOrElse { error ->
                DeviceOperationStatus.HostPackageFailed(
                    action = action,
                    diagnosticDetail = deviceDiagnosticDetail(error),
                )
            }
            DeviceStateWriter.write(context)
            _uiState.value = buildState(status)
        }
    }

    fun importDocument(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val context = getApplication<Application>()
                val resolver = context.contentResolver
                val displayName = queryDisplayName(uri).ifBlank {
                    "import-${System.currentTimeMillis()}"
                }
                val target = uniqueDestination(DeviceStateWriter.workspaceDir(context), displayName)
                resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Unable to open the selected document")
                refresh(DeviceOperationStatus.DocumentImported(target.name))
            }.getOrElse { error ->
                refresh(DeviceOperationStatus.ImportFailed(deviceDiagnosticDetail(error)))
            }
        }
    }

    fun rememberSharedFolder(uri: Uri) {
        val context = getApplication<Application>()
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val label = DocumentFile.fromTreeUri(context, uri)?.name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: "Granted folder"
        capabilityStore.saveSharedFolder(uri.toString(), label)
        refresh(DeviceOperationStatus.SharedFolderSaved(label))
    }

    fun clearSharedFolder() {
        val context = getApplication<Application>()
        val stored = capabilityStore.load()
        if (stored.sharedFolderUri.isNotBlank()) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(stored.sharedFolderUri),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        capabilityStore.clearSharedFolder()
        refresh(DeviceOperationStatus.SharedFolderCleared)
    }

    fun exportWorkspaceFile(fileName: String, destinationUri: Uri) {
        viewModelScope.launch {
            runCatching {
                val context = getApplication<Application>()
                val source = File(DeviceStateWriter.workspaceDir(context), fileName)
                if (!source.isFile) {
                    throw IOException("Workspace file not found: $fileName")
                }
                context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                    source.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Unable to open export destination")
                refresh(DeviceOperationStatus.WorkspaceFileExported(fileName))
            }.getOrElse { error ->
                refresh(DeviceOperationStatus.WorkspaceExportFailed(deviceDiagnosticDetail(error)))
            }
        }
    }

    fun exportDiagnosticsLogs(destinationUri: Uri) {
        viewModelScope.launch {
            runCatching {
                val context = getApplication<Application>()
                val exportText = HermesCrashLogStore.exportLogsText(context)
                context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                    output.write(exportText.toByteArray(Charsets.UTF_8))
                } ?: throw IOException("Unable to open diagnostics log export destination")
                refresh(DeviceOperationStatus.DiagnosticsExported)
            }.getOrElse { error ->
                refresh(DeviceOperationStatus.DiagnosticsExportFailed(deviceDiagnosticDetail(error)))
            }
        }
    }

    fun clearLastCrashDiagnostics() {
        val context = getApplication<Application>()
        HermesCrashLogStore.clearLastCrash(context)
        refresh(DeviceOperationStatus.DiagnosticsCleared)
    }

    fun performGlobalAction(action: HermesGlobalAction) {
        val context = getApplication<Application>()
        val succeeded = HermesAccessibilityController.performAction(action)
        refresh(
            when {
                succeeded -> DeviceOperationStatus.AccessibilityActionCompleted(action)
                !HermesAccessibilityController.isServiceEnabled(context) -> DeviceOperationStatus.AccessibilityEnableRequired
                else -> DeviceOperationStatus.AccessibilityNotConnected
            },
        )
    }

    fun performSystemAction(action: String) {
        val context = getApplication<Application>()
        val result = HermesSystemControlBridge.performAction(context, action)
        refresh(systemControlStatusFromResult(result))
    }

    fun setBackgroundPersistence(enabled: Boolean) {
        performSystemAction(if (enabled) "start_background_runtime" else "stop_background_runtime")
    }

    fun setFloatingButtonEnabled(enabled: Boolean) {
        performSystemAction(if (enabled) "start_floating_button" else "stop_floating_button")
    }

    private fun buildState(status: DeviceOperationStatus? = null): DeviceUiState {
        val context = getApplication<Application>()
        val sharedFolder = capabilityStore.load()
        val linuxState = HermesLinuxSubsystemBridge.readState(context)
        val systemStatus = HermesSystemControlBridge.readStatus(context)
        val standbyStatus = automationStandbyStatus(context)
        val modelRoutingStatus = automationModelRoutingStatus(context)
        val crashLogStatus = HermesCrashLogStore.statusSnapshot(context)
        val sandboxStatus = linuxState?.let { HermesLinuxSandboxBridge.status(it, context) }
        val hostPkgStatus = runCatching {
            if (linuxState != null && linuxState.optBoolean("uses_termux", false)) {
                HermesTermuxPackageManager.performAction(context, "status")
            } else {
                null
            }
        }.getOrNull()
        val installedSandboxes = sandboxStatus?.optJSONArray("installed_sandboxes")
        val installedSandboxNames = buildList {
            if (installedSandboxes != null) {
                for (index in 0 until installedSandboxes.length()) {
                    val item = installedSandboxes.optJSONObject(index) ?: continue
                    val name = item.optString("name")
                    if (name.isNotBlank()) {
                        add(name)
                    }
                }
            }
        }
        val workspace = DeviceStateWriter.workspaceDir(context)
        val workspaceFiles = workspace
            .listFiles()
            .orEmpty()
            .filter { it.isFile }
            .sortedByDescending { it.lastModified() }
            .take(12)
            .map { file ->
                WorkspaceFileUi(
                    name = file.name,
                    sizeLabel = Formatter.formatShortFileSize(context, file.length()),
                    modifiedLabel = DateFormat.format("yyyy-MM-dd HH:mm", file.lastModified()).toString(),
                )
            }

        return DeviceUiState(
            workspacePath = workspace.absolutePath,
            workspaceFiles = workspaceFiles,
            sharedFolderLabel = sharedFolder.sharedFolderLabel.ifBlank { "No shared folder granted yet" },
            sharedFolderUri = sharedFolder.sharedFolderUri,
            linuxEnabled = linuxState?.optBoolean("enabled") == true,
            linuxAndroidAbi = linuxState?.optString("android_abi").orEmpty(),
            linuxTermuxArch = linuxState?.optString("termux_arch").orEmpty(),
            linuxPrefixPath = linuxState?.optString("prefix_path").orEmpty(),
            linuxBashPath = linuxState?.optString("bash_path").orEmpty().ifBlank { linuxState?.optString("shell_path").orEmpty() },
            linuxHomePath = linuxState?.optString("home_path").orEmpty(),
            linuxTmpPath = linuxState?.optString("tmp_path").orEmpty(),
            linuxPackageCount = linuxState?.optJSONArray("packages")?.length() ?: 0,
            hostPkgProotVersion = hostPkgStatus?.optString("proot_version").orEmpty(),
            hostPkgProotDistroVersion = hostPkgStatus?.optString("proot_distro_version").orEmpty(),
            hostPkgMirrorProfile = hostPkgStatus?.optString("mirror_profile").orEmpty().ifBlank { "default" },
            hostPkgIndexPackageCount = hostPkgStatus?.optInt("index_package_count", 0) ?: 0,
            hostPkgInstalledCount = hostPkgStatus?.optInt("installed_count", 0)
                ?: (linuxState?.optJSONArray("packages")?.length() ?: 0),
            sandboxStorageRoot = sandboxStatus?.optString("app_private_storage_root").orEmpty(),
            sandboxAgentEnabled = sandboxStatus?.optBoolean("agent_shell_enabled", true) == true,
            activeSandboxName = sandboxStatus?.optString("active_sandbox_name").orEmpty(),
            installedSandboxCount = installedSandboxNames.size,
            installedSandboxNames = installedSandboxNames,
            accessibilityEnabled = HermesAccessibilityController.isServiceEnabled(context),
            accessibilityConnected = HermesAccessibilityController.isServiceConnected(),
            wifiEnabled = systemStatus.wifiEnabled,
            activeNetworkLabel = systemStatus.activeNetworkLabel,
            airplaneModeEnabled = systemStatus.airplaneModeEnabled,
            activeNetworkMetered = systemStatus.activeNetworkMetered,
            dataSaverEnabled = systemStatus.dataSaverEnabled,
            bluetoothSupported = systemStatus.bluetoothSupported,
            bluetoothEnabled = systemStatus.bluetoothEnabled,
            bluetoothPermissionGranted = systemStatus.bluetoothPermissionGranted,
            pairedBluetoothDevices = systemStatus.pairedBluetoothDevices,
            usbHostSupported = systemStatus.usbHostSupported,
            usbDeviceCount = systemStatus.usbDeviceCount,
            usbDevices = systemStatus.usbDevices,
            nfcSupported = systemStatus.nfcSupported,
            nfcEnabled = systemStatus.nfcEnabled,
            overlayPermissionGranted = systemStatus.overlayPermissionGranted,
            notificationPermissionGranted = systemStatus.notificationPermissionGranted,
            backgroundPersistenceEnabled = systemStatus.backgroundPersistenceEnabled,
            runtimeServiceRunning = systemStatus.runtimeServiceRunning,
            floatingButtonEnabled = systemStatus.floatingButtonEnabled,
            floatingButtonRunning = systemStatus.floatingButtonRunning,
            operatorStandbyReady = standbyStatus.optBoolean("ready", false),
            automationCount = standbyStatus.optInt("automation_count", 0),
            enabledAutomationCount = standbyStatus.optInt("enabled_automation_count", 0),
            externalTriggerCount = standbyStatus.optInt("external_trigger_count", 0),
            remoteDispatchCount = standbyStatus.optInt("remote_dispatch_count", 0),
            recentAutomationRunCount = standbyStatus.optInt("recent_run_count", 0),
            lastAutomationRunLabel = standbyStatus.optString("last_run_label"),
            lastAutomationRunResult = standbyStatus.optString("last_run_result"),
            lastAutomationRunSuccess = when {
                standbyStatus.has("last_run_success") && !standbyStatus.isNull("last_run_success") -> standbyStatus.optBoolean("last_run_success")
                else -> null
            },
            lastDispatchTaskName = standbyStatus.optString("last_dispatch_task_name"),
            lastDispatchSource = standbyStatus.optString("last_dispatch_source"),
            lastDispatchChannel = standbyStatus.optString("last_dispatch_channel"),
            operatorModelProvider = modelRoutingStatus.optString("active_provider_label")
                .ifBlank { modelRoutingStatus.optString("active_provider") },
            operatorModelName = modelRoutingStatus.optString("active_model"),
            operatorVisionCapable = modelRoutingStatus.optBoolean("vision_capable", false),
            diagnosticsLogStatusLabel = crashLogStatus.statusLabel,
            diagnosticsLogCapturedAtLabel = crashLogStatus.capturedAtLabel,
            diagnosticsLogExceptionType = crashLogStatus.exceptionType,
            diagnosticsLogPreviewLines = crashLogStatus.previewLines,
            diagnosticsLogExportFileName = crashLogStatus.exportFileName,
            diagnosticsLogExportReady = crashLogStatus.hasLastCrash || crashLogStatus.logBytes > 0,
            lastCrashPresent = crashLogStatus.hasLastCrash,
            resizableWindowSupport = systemStatus.resizableWindowSupport,
            freeformWindowSupported = systemStatus.freeformWindowSupported,
            status = status,
        )
    }

    private fun automationStandbyStatus(context: Application): JSONObject {
        return runCatching {
            JSONObject(HermesAutomationBridge.operatorStandbyStatusJson(context))
                .optJSONObject("standby_dispatch") ?: JSONObject()
        }.getOrDefault(JSONObject())
    }

    private fun automationModelRoutingStatus(context: Application): JSONObject {
        return runCatching {
            JSONObject(HermesAutomationBridge.operatorModelRoutingStatusJson(context))
        }.getOrDefault(JSONObject())
    }

    private fun queryDisplayName(uri: Uri): String {
        val context = getApplication<Application>()
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex).orEmpty()
                }
            }
        }
        return DocumentFile.fromSingleUri(context, uri)?.name.orEmpty()
    }

    private fun uniqueDestination(directory: File, fileName: String): File {
        directory.mkdirs()
        val candidate = File(directory, fileName)
        if (!candidate.exists()) {
            return candidate
        }
        val dotIndex = fileName.lastIndexOf('.')
        val stem = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""
        var suffix = 1
        while (true) {
            val next = File(directory, "$stem-$suffix$extension")
            if (!next.exists()) {
                return next
            }
            suffix += 1
        }
    }
}

internal fun resolveSandboxTarget(distroId: String): Pair<String, String> {
    val distro = HermesLinuxSandboxCatalog.findDistro(distroId)
    val resolvedDistroId = distro?.optString("id")?.ifBlank { null } ?: distroId
    val sandboxName = distro?.optString("name")?.ifBlank { null } ?: "hermes-debian"
    return resolvedDistroId to sandboxName
}
