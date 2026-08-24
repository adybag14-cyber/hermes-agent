package com.mobilefork.hermesagent.backend

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.ProviderPresets
import com.mobilefork.hermesagent.models.PythonRuntimeWriteAuthority
import com.mobilefork.hermesagent.models.LocalModelRuntimeSelectionAuthority
import com.mobilefork.hermesagent.models.RuntimeSelectionSupersededException
import com.mobilefork.hermesagent.device.HermesLinuxSubsystemBridge
import java.io.File
import org.json.JSONObject

internal class ExpectedLocalBackendSupersededException :
    IllegalStateException("The activity local-runtime selection changed before startup")

object HermesRuntimeManager {
    private val pythonStartLock = Any()

    @Volatile
    private var androidPythonIdentityReady = false

    data class RuntimeState(
        val started: Boolean,
        val baseUrl: String? = null,
        val lanBaseUrl: String? = null,
        val apiKey: String? = null,
        /** NONE identifies a remote/Python runtime; local states must retain their owner. */
        val localBackendKind: BackendKind = BackendKind.NONE,
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
        data class SelectionSuperseded(
            val expected: BackendKind,
            val observed: BackendKind,
        ) : BackendRouteResult<Nothing>
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
        dangerouslySkipRamChecks: Boolean = false,
        localLauncher: (dangerouslySkipRamChecks: Boolean) -> LocalBackendStatus,
        remoteLauncher: () -> T,
        remoteStopFailure: String? = null,
    ): BackendRouteResult<T> {
        if (!remoteStopFailure.isNullOrBlank()) {
            return BackendRouteResult.RemoteOwnershipFailed(remoteStopFailure)
        }
        val localStatus = localLauncher(dangerouslySkipRamChecks)
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

    /** A backend-bound route which has no remote-launch capability. */
    internal fun routeExpectedLocalBackend(
        selectedLocalBackend: BackendKind,
        expectedLocalBackend: BackendKind,
        dangerouslySkipRamChecks: Boolean = false,
        localLauncher: (dangerouslySkipRamChecks: Boolean) -> LocalBackendStatus,
    ): BackendRouteResult<Nothing> {
        if (selectedLocalBackend != expectedLocalBackend) {
            return BackendRouteResult.SelectionSuperseded(
                expected = expectedLocalBackend,
                observed = selectedLocalBackend,
            )
        }
        val localStatus = localLauncher(dangerouslySkipRamChecks)
        return when {
            localStatus.requiresAppRestart -> BackendRouteResult.LocalFailed(localStatus)
            localStatus.started -> BackendRouteResult.LocalStarted(localStatus)
            else -> BackendRouteResult.LocalFailed(localStatus)
        }
    }

    /** Atomically bind a passive launch to both its generation and persisted backend. */
    internal fun validateExpectedLocalBackendAdmission(
        expectedLocalBackend: BackendKind,
        admissionCheck: () -> Unit,
        loadPersistedBackend: () -> BackendKind,
    ) {
        LocalModelRuntimeSelectionAuthority.withAdmissionCheck(admissionCheck) {
            if (loadPersistedBackend() != expectedLocalBackend) {
                throw ExpectedLocalBackendSupersededException()
            }
        }
    }

    /** Retire a local result before a superseded passive launch can return or publish it. */
    internal fun retireSupersededExpectedLocalBackend(
        runtimeState: RuntimeState,
        stopAllLocalBackends: () -> LocalBackendStatus,
    ): RuntimeState = runtimeStateAfterLocalOperation(runtimeState, stopAllLocalBackends())

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
    fun ensureStarted(
        context: Context,
        dangerouslySkipRamChecks: Boolean = false,
        admissionCheck: () -> Unit = {},
    ): RuntimeState = ensureStartedLocked(
        context = context,
        dangerouslySkipRamChecks = dangerouslySkipRamChecks,
        admissionCheck = admissionCheck,
        expectedLocalBackend = null,
    )

    /**
     * Start only the supported local backend observed by an earlier foreground decision.
     *
     * Runtime ownership is held while the persisted selection is revalidated. A superseded
     * selection is a no-op, and this path never has authority to launch the remote runtime.
     */
    @Synchronized
    fun ensureExpectedLocalBackendStarted(
        context: Context,
        expectedLocalBackend: BackendKind,
        selectionGeneration: Long,
    ): RuntimeState {
        if (
            expectedLocalBackend != BackendKind.LLAMA_CPP &&
            expectedLocalBackend != BackendKind.LITERT_LM
        ) {
            return currentState
        }
        return try {
            ensureStartedLocked(
                context = context,
                dangerouslySkipRamChecks = false,
                admissionCheck = {
                    LocalModelRuntimeSelectionAuthority.requireCurrent(selectionGeneration)
                },
                expectedLocalBackend = expectedLocalBackend,
            )
        } catch (_: ExpectedLocalBackendSupersededException) {
            currentState
        } catch (_: RuntimeSelectionSupersededException) {
            currentState
        }
    }

    private fun ensureStartedLocked(
        context: Context,
        dangerouslySkipRamChecks: Boolean,
        admissionCheck: () -> Unit,
        expectedLocalBackend: BackendKind?,
    ): RuntimeState {
        val appContext = context.applicationContext
        val effectiveAdmissionCheck = {
            if (expectedLocalBackend != null) {
                // Generation admission and the persisted-backend read are one authoritative
                // transaction. A newer Settings/model action cannot land between them.
                validateExpectedLocalBackendAdmission(
                    expectedLocalBackend = expectedLocalBackend,
                    admissionCheck = admissionCheck,
                    loadPersistedBackend = {
                        BackendKind.fromPersistedValue(
                            AppSettingsStore(appContext).load().onDeviceBackend,
                        )
                    },
                )
            } else {
                admissionCheck()
            }
        }
        effectiveAdmissionCheck()
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
        val observedLocalStatus = OnDeviceBackendManager.currentStatus()
        if (observedLocalStatus.requiresAppRestart) {
            currentState = localFailureState(appContext, selectedLocalBackend, observedLocalStatus)
            return currentState
        }
        val existingLocalStatus = localStatusBeforeCachedRemoteReuse(
            selectedLocalBackend = selectedLocalBackend,
            observedLocalStatus = observedLocalStatus,
            stopAllLocalBackends = OnDeviceBackendManager::stopAll,
        )
        if (existingLocalStatus.requiresAppRestart) {
            currentState = localFailureState(appContext, selectedLocalBackend, existingLocalStatus)
            return currentState
        }
        if (
            shouldReuseCachedRemoteRuntime(
                selectedLocalBackend = selectedLocalBackend,
                localBackendStatus = existingLocalStatus,
                runtimeState = currentState,
            )
        ) {
            return currentState
        }

        return try {
            HermesLinuxSubsystemBridge.ensureInstalled(appContext)
            refreshPythonRuntimeEnvironment(appContext)
            val selectedBackendAtRouting = expectedLocalBackend?.let {
                BackendKind.fromPersistedValue(
                    AppSettingsStore(appContext).load().onDeviceBackend,
                )
            } ?: selectedLocalBackend
            val localLauncher: (Boolean) -> LocalBackendStatus = { oneShotRamAuthority ->
                OnDeviceBackendManager.ensureConfigured(
                    appContext,
                    expectedLocalBackend?.persistedValue ?: settings.onDeviceBackend,
                    dangerouslySkipRamChecks = oneShotRamAuthority,
                    admissionCheck = effectiveAdmissionCheck,
                )
            }
            val route: BackendRouteResult<RuntimeState> = if (expectedLocalBackend != null) {
                routeExpectedLocalBackend(
                    selectedLocalBackend = selectedBackendAtRouting,
                    expectedLocalBackend = expectedLocalBackend,
                    dangerouslySkipRamChecks = dangerouslySkipRamChecks,
                    localLauncher = localLauncher,
                )
            } else {
                routeConfiguredBackend(
                    selectedLocalBackend = selectedBackendAtRouting,
                    remoteAllowed = !settings.offlineAirplaneMode,
                    dangerouslySkipRamChecks = dangerouslySkipRamChecks,
                    localLauncher = localLauncher,
                    remoteLauncher = {
                        startRemoteRuntime(
                            appContext,
                            settings.provider,
                            settings.model,
                            settings.baseUrl,
                            effectiveAdmissionCheck,
                        )
                    },
                    remoteStopFailure = remoteStopFailureDetail,
                )
            }
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
                is BackendRouteResult.SelectionSuperseded -> currentState
                is BackendRouteResult.Remote -> route.value
            }
            if (route !is BackendRouteResult.SelectionSuperseded) {
                effectiveAdmissionCheck()
            }
            currentState
        } catch (exc: Throwable) {
            if (exc is ExpectedLocalBackendSupersededException) {
                // A direct persisted-backend change may not carry the captured generation.
                // Retire any local process which could have been created before the final
                // expected-backend check, but never touch or launch a remote runtime here.
                currentState = retireSupersededExpectedLocalBackend(
                    runtimeState = currentState,
                    stopAllLocalBackends = OnDeviceBackendManager::stopAll,
                )
                return currentState
            }
            if (exc is RuntimeSelectionSupersededException) {
                // The guard is checked while Hermes runtime ownership is still held. Remove any
                // process produced by the stale load before a newer queued selection enters.
                val localStop = OnDeviceBackendManager.stopAll()
                currentState = runtimeStateAfterLocalOperation(currentState, localStop)
                if (!localStop.requiresAppRestart && expectedLocalBackend == null) {
                    stopRemoteRuntime()
                }
                throw exc
            }
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
            apiKey = status.apiKey.takeIf { it.isNotBlank() },
            localBackendKind = status.backendKind,
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
            localBackendKind = selectedLocalBackend,
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
        admissionCheck: () -> Unit,
    ): RuntimeState {
        ensurePythonStarted(context)
        refreshPythonRuntimeEnvironment(context)
        val effectiveBaseUrl = ProviderPresets.runtimeConfigBaseUrl(provider, baseUrl)
        PythonRuntimeWriteAuthority.writeWithAdmissionCheck(admissionCheck) {
            Python.getInstance().getModule("hermes_android.config_bridge").callAttr(
                "write_runtime_config",
                provider,
                model,
                effectiveBaseUrl,
            )
        }
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
        val localStopStatus = stopLocalRuntime()
        if (localStopStatus.requiresAppRestart) {
            return currentState
        }
        return stopRemoteRuntime()
    }

    /**
     * Stop only app-owned native backends and invalidate any local-origin URL/key atomically.
     *
     * Runtime-manager coordination is always acquired before OnDeviceBackendManager ownership.
     * No callback holding the on-device monitor may call back into this object.
    */
    @Synchronized
    fun stopLocalRuntime(admissionCheck: () -> Unit = {}): LocalBackendStatus {
        admissionCheck()
        val result = OnDeviceBackendManager.withSerializedLocalMutation(
            mutation = { _, stopAllLocalBackends -> stopAllLocalBackends() },
            afterMutationWhileOwned = { finalStatus ->
                // Both ownership locks are still held here, so Settings cannot start a new
                // backend between process shutdown and local URL/key invalidation.
                currentState = runtimeStateAfterLocalOperation(currentState, finalStatus)
            },
        )
        admissionCheck()
        return result.value
    }

    /**
     * Serialize a model-file/store mutation against backend startup using the same lock order as
     * [ensureStarted]: HermesRuntimeManager, then OnDeviceBackendManager.
     */
    @Synchronized
    internal fun <T> withSerializedLocalBackendMutation(
        mutation: (
            currentStatus: LocalBackendStatus,
            stopAllLocalBackends: () -> LocalBackendStatus,
        ) -> T,
        admissionCheck: () -> Unit = {},
    ): T {
        admissionCheck()
        val result = OnDeviceBackendManager.withSerializedLocalMutation(
            mutation = mutation,
            afterMutationWhileOwned = { finalStatus ->
                currentState = runtimeStateAfterLocalOperation(currentState, finalStatus)
            },
        )
        admissionCheck()
        return result.value
    }

    /** Stop only the embedded Python/remote server, preserving any selected native model runtime. */
    @Synchronized
    fun stopRemoteRuntime(admissionCheck: () -> Unit = {}): RuntimeState {
        admissionCheck()
        val stopped = try {
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
        admissionCheck()
        return stopped
    }

    @Synchronized
    fun restartAfterRemoteStop(
        context: Context,
        dangerouslySkipRamChecks: Boolean = false,
        admissionCheck: () -> Unit = {},
    ): RuntimeState {
        admissionCheck()
        val stopState = stopRemoteRuntime(admissionCheck)
        return continueAfterSuccessfulRemoteStop(stopState) {
            ensureStarted(
                context.applicationContext,
                dangerouslySkipRamChecks = dangerouslySkipRamChecks,
                admissionCheck = admissionCheck,
            )
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

    internal fun runtimeStateAfterLocalOperation(
        previous: RuntimeState,
        localStatus: LocalBackendStatus,
    ): RuntimeState {
        if (localStatus.requiresAppRestart) {
            return RuntimeState(
                started = false,
                localBackendKind = localStatus.backendKind,
                error = localStatus.statusMessage.ifBlank {
                    "The local native runtime did not stop safely. Force stop and reopen Hermes."
                },
            )
        }
        if (
            localStatus.backendKind == BackendKind.NONE &&
            !localStatus.started &&
            previous.localBackendKind != BackendKind.NONE
        ) {
            return RuntimeState(started = false)
        }
        return previous
    }

    /**
     * A NONE selection is an ownership transition, not merely a routing preference. Stop every
     * app-owned native backend before a cached remote state can bypass normal backend routing.
     */
    internal fun localStatusBeforeCachedRemoteReuse(
        selectedLocalBackend: BackendKind,
        observedLocalStatus: LocalBackendStatus,
        stopAllLocalBackends: () -> LocalBackendStatus,
    ): LocalBackendStatus {
        return if (selectedLocalBackend == BackendKind.NONE) {
            stopAllLocalBackends()
        } else {
            observedLocalStatus
        }
    }

    internal fun shouldReuseCachedRemoteRuntime(
        selectedLocalBackend: BackendKind,
        localBackendStatus: LocalBackendStatus,
        runtimeState: RuntimeState,
    ): Boolean {
        return selectedLocalBackend == BackendKind.NONE &&
            localBackendStatus.backendKind == BackendKind.NONE &&
            !localBackendStatus.started &&
            !localBackendStatus.requiresAppRestart &&
            runtimeState.localBackendKind == BackendKind.NONE &&
            runtimeState.started &&
            runtimeState.error == null &&
            !runtimeState.baseUrl.isNullOrBlank() &&
            !runtimeState.apiKey.isNullOrBlank()
    }

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
