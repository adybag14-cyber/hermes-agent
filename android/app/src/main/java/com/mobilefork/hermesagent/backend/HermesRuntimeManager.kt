package com.mobilefork.hermesagent.backend

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.ProviderPresets
import com.mobilefork.hermesagent.device.HermesLinuxSubsystemBridge
import java.io.File
import org.json.JSONObject

object HermesRuntimeManager {
    private val pythonStartLock = Any()

    @Volatile
    private var androidPythonIdentityReady = false

    data class RuntimeState(
        val started: Boolean,
        val baseUrl: String? = null,
        val lanBaseUrl: String? = null,
        val apiKey: String? = null,
        val hermesHome: String? = null,
        val modelName: String? = null,
        val probeResult: String? = null,
        val error: String? = null,
    )

    internal sealed interface BackendRouteResult<out T> {
        data class LocalStarted(val status: LocalBackendStatus) : BackendRouteResult<Nothing>
        data class LocalFailed(val status: LocalBackendStatus) : BackendRouteResult<Nothing>
        data class RemoteOwnershipFailed(val reason: String) : BackendRouteResult<Nothing>
        data class RemoteDisabled(val status: LocalBackendStatus) : BackendRouteResult<Nothing>
        data class Remote<T>(val value: T) : BackendRouteResult<T>
    }

    /**
     * The remote launcher is an injected capability and is never invoked when
     * the user explicitly selected a local backend. This makes the no-silent-
     * fallback boundary executable in a JVM test rather than a source snapshot.
     */
    internal fun <T> routeConfiguredBackend(
        selectedLocalBackend: BackendKind,
        remoteAllowed: Boolean,
        localLauncher: () -> LocalBackendStatus,
        remoteLauncher: () -> T,
        remoteStopFailure: String? = null,
    ): BackendRouteResult<T> {
        if (!remoteStopFailure.isNullOrBlank()) {
            return BackendRouteResult.RemoteOwnershipFailed(remoteStopFailure)
        }
        val localStatus = localLauncher()
        if (localStatus.requiresAppRestart) {
            return BackendRouteResult.LocalFailed(localStatus)
        }
        if (localStatus.started) return BackendRouteResult.LocalStarted(localStatus)
        if (selectedLocalBackend != BackendKind.NONE) {
            return BackendRouteResult.LocalFailed(localStatus)
        }
        if (!remoteAllowed) return BackendRouteResult.RemoteDisabled(localStatus)
        return BackendRouteResult.Remote(remoteLauncher())
    }

    @Volatile
    private var currentState: RuntimeState = RuntimeState(started = false)

    @Volatile
    private var remoteStopFailureDetail: String? = null

    fun ensurePythonStarted(context: Context) {
        if (Python.isStarted() && androidPythonIdentityReady) {
            return
        }

        val appContext = context.applicationContext
        synchronized(pythonStartLock) {
            if (!Python.isStarted()) {
                // Every public Python entry point must honor the same startup
                // order as ensureStarted: unpack/repair Linux before Chaquopy.
                // Several callers intentionally start only Python, so keeping
                // this invariant here prevents them from racing extraction.
                HermesLinuxSubsystemBridge.ensureInstalled(appContext)
                Python.start(AndroidPlatform(appContext))
            }
            if (!androidPythonIdentityReady) {
                Python.getInstance()
                    .getModule("hermes_android.runtime_env")
                    .callAttr("prepare_runtime_env", appContext.filesDir.absolutePath)
                androidPythonIdentityReady = true
            }
        }
    }

    @Synchronized
    fun ensureStarted(context: Context): RuntimeState {
        val appContext = context.applicationContext
        remoteStopFailureDetail?.let { detail ->
            currentState = RuntimeState(
                started = false,
                hermesHome = File(appContext.filesDir, "hermes-home").absolutePath,
                error = "$detail Force stop and reopen Hermes before starting another backend.",
            )
            return currentState
        }
        val settings = AppSettingsStore(appContext).load()
        val selectedLocalBackend = BackendKind.fromPersistedValue(settings.onDeviceBackend)
        val existingLocalStatus = OnDeviceBackendManager.currentStatus()
        if (existingLocalStatus.requiresAppRestart) {
            currentState = localFailureState(appContext, selectedLocalBackend, existingLocalStatus)
            return currentState
        }
        if (
            selectedLocalBackend == BackendKind.NONE &&
            currentState.started &&
            currentState.error == null &&
            !currentState.baseUrl.isNullOrBlank() &&
            !currentState.apiKey.isNullOrBlank()
        ) {
            return currentState
        }

        return try {
            HermesLinuxSubsystemBridge.ensureInstalled(appContext)
            refreshPythonRuntimeEnvironment(appContext)
            val route = routeConfiguredBackend(
                selectedLocalBackend = selectedLocalBackend,
                remoteAllowed = !settings.offlineAirplaneMode,
                localLauncher = {
                    OnDeviceBackendManager.ensureConfigured(appContext, settings.onDeviceBackend)
                },
                remoteLauncher = { startRemoteRuntime(appContext, settings.provider, settings.model, settings.baseUrl) },
                remoteStopFailure = remoteStopFailureDetail,
            )
            currentState = when (route) {
                is BackendRouteResult.LocalStarted -> localRuntimeState(appContext, route.status)
                is BackendRouteResult.LocalFailed -> localFailureState(appContext, selectedLocalBackend, route.status)
                is BackendRouteResult.RemoteOwnershipFailed -> RuntimeState(
                    started = false,
                    hermesHome = File(appContext.filesDir, "hermes-home").absolutePath,
                    error = "${route.reason} Force stop and reopen Hermes before starting another backend.",
                )
                is BackendRouteResult.RemoteDisabled -> RuntimeState(
                    started = false,
                    hermesHome = File(appContext.filesDir, "hermes-home").absolutePath,
                    error = route.status.statusMessage.ifBlank {
                        "Offline airplane mode is on and no on-device backend is ready."
                    },
                )
                is BackendRouteResult.Remote -> route.value
            }
            currentState
        } catch (exc: Throwable) {
            currentState = RuntimeState(
                started = false,
                error = exc.message ?: exc.toString(),
            )
            currentState
        }
    }

    private fun localRuntimeState(context: Context, status: LocalBackendStatus): RuntimeState {
        val completionProof = if (status.completionVerified) {
            "; completion_verified=true; completion_latency_ms=${status.completionLatencyMs}"
        } else {
            ""
        }
        return RuntimeState(
            started = true,
            baseUrl = status.baseUrl,
            hermesHome = File(context.filesDir, "hermes-home").absolutePath,
            modelName = status.modelName,
            probeResult = "native-android-${status.backendKind.persistedValue}; " +
                "accelerator=${status.accelerator.ifBlank { "unknown" }}$completionProof",
        )
    }

    private fun localFailureState(
        context: Context,
        selectedLocalBackend: BackendKind,
        status: LocalBackendStatus,
    ): RuntimeState {
        val reason = status.statusMessage.ifBlank {
            "Selected local backend ${selectedLocalBackend.persistedValue} did not start."
        }
        val failureBoundary = if (status.requiresAppRestart) {
            "Remote provider startup was not attempted because the previous local runtime " +
                "did not stop safely. Force stop and reopen Hermes before retrying."
        } else {
            "Remote provider startup was not attempted because a local backend is explicitly selected."
        }
        return RuntimeState(
            started = false,
            hermesHome = File(context.filesDir, "hermes-home").absolutePath,
            modelName = status.modelName.ifBlank { null },
            probeResult = "native-android-${selectedLocalBackend.persistedValue}; started=false; remote_fallback=false",
            error = "$reason $failureBoundary",
        )
    }

    private fun startRemoteRuntime(
        context: Context,
        provider: String,
        model: String,
        baseUrl: String,
    ): RuntimeState {
        ensurePythonStarted(context)
        refreshPythonRuntimeEnvironment(context)
        val effectiveBaseUrl = ProviderPresets.runtimeConfigBaseUrl(provider, baseUrl)
        Python.getInstance().getModule("hermes_android.config_bridge").callAttr(
            "write_runtime_config",
            provider,
            model,
            effectiveBaseUrl,
        )
        val probeResult = PythonBootProbe.readProbe(context.applicationContext)
        val statusJson = Python.getInstance()
            .getModule("hermes_android.server_bridge")
            .callAttr("ensure_server", context.filesDir.absolutePath)
            .toString()
        val status = JSONObject(statusJson)
        return RuntimeState(
            started = status.optBoolean("started", false),
            baseUrl = status.optString("base_url").ifBlank { null },
            lanBaseUrl = status.optString("lan_base_url").ifBlank { null },
            apiKey = status.optString("api_server_key").ifBlank { null },
            hermesHome = status.optString("hermes_home").ifBlank { null },
            modelName = status.optString("api_server_model_name").ifBlank { null },
            probeResult = probeResult,
        )
    }

    private fun refreshPythonRuntimeEnvironment(context: Context) {
        if (!Python.isStarted()) {
            return
        }
        synchronized(pythonStartLock) {
            Python.getInstance()
                .getModule("hermes_android.runtime_env")
                .callAttr("prepare_runtime_env", context.filesDir.absolutePath)
            androidPythonIdentityReady = true
        }
    }

    @Synchronized
    fun stop(): RuntimeState {
        val localStopStatus = OnDeviceBackendManager.stopAll()
        if (localStopStatus.requiresAppRestart) {
            currentState = RuntimeState(
                started = false,
                error = localStopStatus.statusMessage.ifBlank {
                    "The local native runtime did not stop safely. Force stop and reopen Hermes."
                },
            )
            return currentState
        }
        return stopRemoteRuntime()
    }

    /** Stop only the embedded Python/remote server, preserving any selected native model runtime. */
    @Synchronized
    fun stopRemoteRuntime(): RuntimeState {
        return try {
            if (Python.isStarted()) {
                Python.getInstance()
                    .getModule("hermes_android.server_bridge")
                    .callAttr("stop_server")
            }
            remoteStopFailureDetail = null
            currentState = RuntimeState(started = false)
            currentState
        } catch (exc: Throwable) {
            val detail = exc.message ?: exc.toString()
            remoteStopFailureDetail = detail
            currentState = RuntimeState(
                started = false,
                error = "$detail Force stop and reopen Hermes before starting another backend.",
            )
            currentState
        }
    }

    @Synchronized
    fun restartAfterRemoteStop(context: Context): RuntimeState {
        val stopState = stopRemoteRuntime()
        return continueAfterSuccessfulRemoteStop(stopState) {
            ensureStarted(context.applicationContext)
        }
    }

    internal fun continueAfterSuccessfulRemoteStop(
        stopState: RuntimeState,
        restart: () -> RuntimeState,
    ): RuntimeState {
        if (stopState.error != null) return stopState
        return restart()
    }

    fun currentState(): RuntimeState = currentState

    fun remoteStopRequiresAppRestart(): Boolean = !remoteStopFailureDetail.isNullOrBlank()

    internal fun localBackendFallbackWarning(
        selectedLocalBackend: BackendKind,
        localBackendStatus: LocalBackendStatus,
    ): String? {
        if (localBackendStatus.requiresAppRestart) {
            val reason = localBackendStatus.statusMessage.ifBlank {
                "The previous local runtime did not stop safely."
            }
            return "$reason Remote fallback is disabled until Hermes is force stopped and reopened."
        }
        if (selectedLocalBackend == BackendKind.NONE || localBackendStatus.started) {
            return null
        }
        val reason = localBackendStatus.statusMessage.ifBlank {
            "Selected local backend ${selectedLocalBackend.persistedValue} did not start."
        }
        return "Local ${selectedLocalBackend.persistedValue} backend unavailable: $reason. " +
            "Remote fallback is disabled while a local backend is explicitly selected."
    }

    internal fun String?.withLocalBackendWarning(warning: String?): String? {
        val trimmedWarning = warning.orEmpty().trim()
        val trimmedProbe = orEmpty().trim()
        return when {
            trimmedWarning.isBlank() -> trimmedProbe.ifBlank { null }
            trimmedProbe.isBlank() -> trimmedWarning
            else -> "$trimmedProbe\n$trimmedWarning"
        }
    }
}
