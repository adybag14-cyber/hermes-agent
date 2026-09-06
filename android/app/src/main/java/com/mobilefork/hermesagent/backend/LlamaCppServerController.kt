package com.mobilefork.hermesagent.backend

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.mobilefork.hermesagent.device.HermesLinuxSubsystemBridge
import com.mobilefork.hermesagent.device.LocalModelRuntimeDiagnostics
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit

internal interface LlamaProcessStopHandle {
    val supportsForceDestroy: Boolean
    fun exitValue(): Int
    fun destroy()
    fun forceDestroy()
}

object LlamaCppServerController {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(750, TimeUnit.MILLISECONDS)
        .readTimeout(750, TimeUnit.MILLISECONDS)
        .writeTimeout(750, TimeUnit.MILLISECONDS)
        .build()
    private val canaryHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile private var process: Process? = null
    @Volatile private var activeModelPath: String = ""
    @Volatile private var activeModelName: String = ""
    @Volatile private var recentLog: String = ""
    @Volatile private var activeCompletionVerified: Boolean = false
    @Volatile private var activeCompletionDetail: String = ""
    @Volatile private var activeCompletionLatencyMs: Long = 0L
    @Volatile private var activeArtifactSummary: String = ""
    @Volatile private var activeLaunchFingerprint: String = ""
    @Volatile private var activeApiKey: String = ""

    @Synchronized
    internal fun ensureRunning(
        context: Context,
        modelPath: String,
        requestedModelName: String,
        port: Int,
        launchConfig: LlamaCppLaunchConfig = LlamaCppLaunchConfig(),
        dangerouslySkipRamChecks: Boolean = false,
    ): LocalBackendStatus {
        val launchValidation = launchConfig.validate()
        if (!launchValidation.valid) {
            return LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = false,
                sourceModelPath = modelPath,
                statusMessage = diagnosticsSafeDetail(
                    "llama.cpp launch configuration is invalid: ${launchValidation.error}",
                    launchConfig,
                ),
            )
        }
        val launchFingerprint = launchConfig.fingerprint()
        val currentProcess = process
        if (
            currentProcess != null &&
            isProcessAlive(currentProcess) &&
            activeModelPath == modelPath &&
            activeLaunchFingerprint == launchFingerprint &&
            activeCompletionVerified &&
            activeCompletionDetail.isNotBlank() &&
            activeApiKey.isNotBlank() &&
            checkReady(port, activeApiKey)
        ) {
            return LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = true,
                baseUrl = "http://127.0.0.1:$port/v1",
                modelName = actualModelName(port, requestedModelName, activeApiKey),
                sourceModelPath = modelPath,
                statusMessage = cachedCompletionStatusMessage(
                    laneDisplayLabel = launchConfig.lane.displayLabel(),
                    completionDetail = activeCompletionDetail,
                    completionLatencyMs = activeCompletionLatencyMs,
                ),
                accelerator = "cpu",
                artifactSummary = activeArtifactSummary,
                completionVerified = true,
                completionLatencyMs = activeCompletionLatencyMs,
                apiKey = activeApiKey,
            )
        }

        stop()?.let { failure ->
            return LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = false,
                sourceModelPath = modelPath,
                statusMessage = diagnosticsSafeDetail(
                    llamaStopFailureMessage("another llama.cpp model", failure),
                    launchConfig,
                ),
                requiresAppRestart = true,
            )
        }
        val modelFile = File(modelPath)
        val inspection = GgufArtifactInspector.inspect(modelFile)
        if (!inspection.valid) {
            return LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = false,
                sourceModelPath = modelPath,
                artifactSummary = inspection.summary,
                statusMessage = "llama.cpp rejected this artifact before launch: ${inspection.error}",
            )
        }
        val memory = LocalModelRuntimeDiagnostics.captureMemory(context)
        val requestedContext = contextSizeForModel(modelPath)
        val preflight = LocalModelRuntimeDiagnostics.evaluatePreflight(
            backend = launchConfig.lane.diagnosticsBackendLabel(),
            modelBytes = modelFile.length(),
            requestedContextTokens = requestedContext,
            memory = memory,
            dangerouslySkipRamChecks = dangerouslySkipRamChecks,
        )
        val attemptId = LocalModelRuntimeDiagnostics.beginAttempt(
            context = context,
            backend = launchConfig.lane.diagnosticsBackendLabel(),
            modelFile = modelFile,
            requestedAccelerator = "cpu",
            requestedContextTokens = requestedContext,
            effectiveContextTokens = preflight.effectiveContextTokens,
            memory = memory,
            preflight = preflight,
            runtimeLaunch = diagnosticsBreadcrumbFor(launchConfig),
        )
        if (!preflight.allowed) {
            LocalModelRuntimeDiagnostics.finishAttempt(
                context = context,
                attemptId = attemptId,
                status = "blocked",
                stage = "memory_preflight",
                detail = diagnosticsSafeDetail(preflight.detail, launchConfig),
            )
            return LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = false,
                sourceModelPath = modelPath,
                artifactSummary = inspection.summary,
                statusMessage = "llama.cpp memory preflight blocked this model: ${preflight.detail}",
            )
        }
        if (!isLoopbackPortAvailable(port)) {
            val detail =
                "Hermes did not start llama.cpp because 127.0.0.1:$port is already in use by an unowned process"
            LocalModelRuntimeDiagnostics.finishAttempt(
                context = context,
                attemptId = attemptId,
                status = "failed",
                stage = "loopback_port_ownership",
                detail = detail,
            )
            return LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = false,
                sourceModelPath = modelPath,
                artifactSummary = inspection.summary,
                statusMessage = "$detail. Force stop the conflicting app or choose the Stable/LiteRT-LM path after the port is free.",
            )
        }
        val linuxState = HermesLinuxSubsystemBridge.ensureInstalled(context)
        val shellPath = shellPathForState(linuxState)
        val prefixPath = linuxState.optString("prefix_path")
        val homePath = linuxState.optString("home_path")
        val llamaServerPath = selectLlamaServerPath(context, linuxState, launchConfig.lane)
        if (shellPath.isBlank() || prefixPath.isBlank()) {
            LocalModelRuntimeDiagnostics.finishAttempt(
                context = context,
                attemptId = attemptId,
                status = "failed",
                stage = "runtime_discovery",
                detail = diagnosticsSafeDetail(
                    "The embedded Linux suite is not ready yet for llama.cpp",
                    launchConfig,
                ),
            )
            return LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = false,
                sourceModelPath = modelPath,
                statusMessage = "The embedded Linux suite is not ready yet for llama.cpp",
                artifactSummary = inspection.summary,
            )
        }
        if (llamaServerPath.isBlank() || !File(llamaServerPath).canExecute()) {
            val fallbackReason = linuxState.optString("fallback_reason").ifBlank {
                "embedded Linux shell could not be launched"
            }
            val shellModeHint = if (linuxState.optString("execution_mode") == "android_system_shell") {
                " Native Android shell fallback reason: $fallbackReason."
            } else {
                ""
            }
            val executableLabel = if (launchConfig.lane == LlamaCppRuntimeLane.TURBOQUANT) {
                "The experimental TurboQuant llama.cpp executable is not packaged for this Android ABI"
            } else {
                "llama.cpp executable is not available at $llamaServerPath"
            }
            val alternative = if (launchConfig.lane == LlamaCppRuntimeLane.TURBOQUANT) {
                "Use the stable llama.cpp lane or a LiteRT-LM .litertlm model instead."
            } else {
                "Use a LiteRT-LM .litertlm model for fully native local inference."
            }
            val detail = "$executableLabel.$shellModeHint $alternative"
            LocalModelRuntimeDiagnostics.finishAttempt(
                context = context,
                attemptId = attemptId,
                status = "failed",
                stage = "runtime_discovery",
                detail = diagnosticsSafeDetail(detail, launchConfig),
            )
            return LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = false,
                sourceModelPath = modelPath,
                statusMessage = detail,
                artifactSummary = inspection.summary,
            )
        }

        val launchApiKey = generateLoopbackApiKey()
        val command = shellCommandForLaunch(
            llamaServerPath = llamaServerPath,
            modelPath = modelPath,
            port = port,
            contextSizeOverride = preflight.effectiveContextTokens,
            launchConfig = launchConfig,
            apiKey = launchApiKey,
        )

        // Runtime installation/discovery above can take long enough for another process to
        // claim the fixed loopback port after the initial fail-fast check. Recheck at the
        // actual spawn boundary so Hermes never certifies or sends the per-process token to
        // a listener it did not launch.
        if (!isLoopbackPortAvailable(port)) {
            val detail =
                "Hermes did not start llama.cpp because 127.0.0.1:$port became occupied by an unowned process before launch"
            LocalModelRuntimeDiagnostics.finishAttempt(
                context = context,
                attemptId = attemptId,
                status = "failed",
                stage = "loopback_port_ownership",
                detail = detail,
            )
            return LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = false,
                sourceModelPath = modelPath,
                artifactSummary = inspection.summary,
                statusMessage = "$detail. Force stop the conflicting app or choose the Stable/LiteRT-LM path after the port is free.",
            )
        }

        return try {
            val shellArgs = if (shellPath.endsWith("/sh")) {
                listOf(shellPath, "-c", command)
            } else {
                listOf(shellPath, "-lc", command)
            }
            val startedProcess = ProcessBuilder(shellArgs)
                .directory(File(homePath.ifBlank { prefixPath }))
                .redirectErrorStream(true)
                .apply {
                    environment().putAll(HermesLinuxSubsystemBridge.buildRunEnvironment(linuxState))
                }
                .start()
            process = startedProcess
            activeModelPath = modelPath
            activeModelName = requestedModelName
            activeLaunchFingerprint = launchFingerprint
            activeApiKey = launchApiKey
            drainLogs(startedProcess)
            if (!waitUntilReady(port, startedProcess, launchApiKey)) {
                val errorTail = recentLog.takeLast(600)
                val exitDetail = processExitDetail(startedProcess)
                val failure = when {
                    errorTail.isNotBlank() -> "llama.cpp failed to become ready$exitDetail: $errorTail"
                    else -> "llama.cpp failed to become ready$exitDetail"
                }
                val status = failureStatusAfterStop(
                    modelPath = modelPath,
                    artifactSummary = inspection.summary,
                    detail = failure,
                    launchConfig = launchConfig,
                )
                LocalModelRuntimeDiagnostics.finishAttempt(
                    context = context,
                    attemptId = attemptId,
                    status = "failed",
                    stage = "server_readiness",
                    detail = diagnosticsSafeDetail(status.statusMessage, launchConfig),
                )
                return status
            }
            if (!isProcessAlive(startedProcess)) {
                val status = failureStatusAfterStop(
                    modelPath = modelPath,
                    artifactSummary = inspection.summary,
                    detail = "llama.cpp readiness responded but the owned server process exited before verification",
                    launchConfig = launchConfig,
                )
                LocalModelRuntimeDiagnostics.finishAttempt(
                    context = context,
                    attemptId = attemptId,
                    status = "failed",
                    stage = "owned_process_identity",
                    detail = diagnosticsSafeDetail(status.statusMessage, launchConfig),
                )
                return status
            }
            if (!protectedEndpointRequiresApiKey(port)) {
                val status = failureStatusAfterStop(
                    modelPath = modelPath,
                    artifactSummary = inspection.summary,
                    detail = "llama.cpp did not enforce its per-process API key on the protected chat endpoint",
                    launchConfig = launchConfig,
                )
                LocalModelRuntimeDiagnostics.finishAttempt(
                    context = context,
                    attemptId = attemptId,
                    status = "failed",
                    stage = "api_key_enforcement",
                    detail = diagnosticsSafeDetail(status.statusMessage, launchConfig),
                )
                return status
            }
            val modelName = actualModelName(port, requestedModelName, launchApiKey)
            val canary = runCompletionCanary(
                port = port,
                modelName = modelName,
                lane = launchConfig.lane,
                apiKey = launchApiKey,
            )
            if (!canary.verified) {
                val failure = "llama.cpp opened /v1/models but failed the required chat completion canary: ${canary.detail}"
                val status = failureStatusAfterStop(
                    modelPath = modelPath,
                    artifactSummary = inspection.summary,
                    detail = failure,
                    launchConfig = launchConfig,
                    accelerator = "cpu",
                    completionLatencyMs = canary.elapsedMs,
                )
                LocalModelRuntimeDiagnostics.finishAttempt(
                    context = context,
                    attemptId = attemptId,
                    status = "failed",
                    stage = "completion_canary",
                    detail = diagnosticsSafeDetail(status.statusMessage, launchConfig),
                    accelerator = "cpu",
                    completionLatencyMs = canary.elapsedMs,
                )
                return status
            }
            if (!isProcessAlive(startedProcess)) {
                val status = failureStatusAfterStop(
                    modelPath = modelPath,
                    artifactSummary = inspection.summary,
                    detail = "llama.cpp completion responded but the owned server process exited before publication",
                    launchConfig = launchConfig,
                    accelerator = "cpu",
                    completionLatencyMs = canary.elapsedMs,
                )
                LocalModelRuntimeDiagnostics.finishAttempt(
                    context = context,
                    attemptId = attemptId,
                    status = "failed",
                    stage = "owned_process_identity",
                    detail = diagnosticsSafeDetail(status.statusMessage, launchConfig),
                    accelerator = "cpu",
                    completionLatencyMs = canary.elapsedMs,
                )
                return status
            }
            activeCompletionVerified = true
            activeCompletionDetail = canary.detail
            activeCompletionLatencyMs = canary.elapsedMs
            activeArtifactSummary = inspection.summary
            val status = LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = true,
                baseUrl = "http://127.0.0.1:$port/v1",
                modelName = modelName,
                sourceModelPath = modelPath,
                statusMessage = "llama.cpp ${launchConfig.lane.displayLabel()} lane is serving locally from ${llamaServerOriginLabel(linuxState, launchConfig.lane)}${llamaServerCompatibilitySuffix(llamaServerPath)}; ${inspection.summary}; completion canary passed with ${canary.detail} in ${canary.elapsedMs} ms. ${preflight.detail}",
                accelerator = "cpu",
                artifactSummary = inspection.summary,
                completionVerified = true,
                completionLatencyMs = canary.elapsedMs,
                apiKey = launchApiKey,
            )
            LocalModelRuntimeDiagnostics.finishAttempt(
                context = context,
                attemptId = attemptId,
                status = "ready",
                stage = "completion_verified",
                detail = diagnosticsSafeDetail(status.statusMessage, launchConfig),
                accelerator = "cpu",
                completionVerified = true,
                completionLatencyMs = canary.elapsedMs,
            )
            status
        } catch (error: Throwable) {
            val failure = LiteRtLmOpenAiProxy.actionableRuntimeFailure(error, "llama.cpp")
            val status = failureStatusAfterStop(
                modelPath = modelPath,
                artifactSummary = inspection.summary,
                detail = failure,
                launchConfig = launchConfig,
            )
            LocalModelRuntimeDiagnostics.finishAttempt(
                context = context,
                attemptId = attemptId,
                status = "failed",
                stage = "server_start",
                detail = diagnosticsSafeDetail(status.statusMessage, launchConfig),
            )
            status
        }
    }

    @Synchronized
    fun stop(): Throwable? {
        val current = process
        if (current != null) {
            val failure = stopOwnedProcess(llamaProcessStopHandle(current))
            if (failure != null) {
                // Retain the exact Process handle and identity. A later stop can retry,
                // while callers must fail closed instead of overlapping another runtime.
                return failure
            }
        }
        process = null
        clearActiveState()
        return null
    }

    private fun clearActiveState() {
        activeModelPath = ""
        activeModelName = ""
        recentLog = ""
        activeCompletionVerified = false
        activeCompletionDetail = ""
        activeCompletionLatencyMs = 0L
        activeArtifactSummary = ""
        activeLaunchFingerprint = ""
        activeApiKey = ""
    }

    internal fun failureStatusAfterStop(
        modelPath: String,
        artifactSummary: String,
        detail: String,
        launchConfig: LlamaCppLaunchConfig,
        accelerator: String = "",
        completionLatencyMs: Long = 0L,
    ): LocalBackendStatus {
        val stopFailure = stop()
        val unsafeStatusMessage = if (stopFailure == null) {
            detail
        } else {
            "$detail ${llamaStopFailureMessage("a replacement backend", stopFailure)}"
        }
        return LocalBackendStatus(
            backendKind = BackendKind.LLAMA_CPP,
            started = false,
            sourceModelPath = modelPath,
            // Native parser/load errors can echo an expert argv token. Sanitize before the
            // status leaves the controller, not only when diagnostics are written to disk.
            statusMessage = diagnosticsSafeDetail(unsafeStatusMessage, launchConfig),
            accelerator = accelerator,
            artifactSummary = artifactSummary,
            completionVerified = false,
            completionLatencyMs = completionLatencyMs,
            requiresAppRestart = stopFailure != null,
        )
    }

    private fun llamaStopFailureMessage(target: String, failure: Throwable): String {
        val reason = failure.message?.lineSequence()?.firstOrNull().orEmpty()
            .ifBlank { failure.javaClass.simpleName }
        return "The existing llama.cpp process did not stop safely ($reason). Hermes did not start $target. Force stop and reopen Hermes before retrying."
    }

    internal fun stopOwnedProcess(
        current: LlamaProcessStopHandle,
        gracefulTimeoutMs: Long = 1_200L,
        forcedTimeoutMs: Long = 1_200L,
    ): Throwable? {
        return try {
            if (!isOwnedProcessAlive(current)) return null
            current.destroy()
            val exitedGracefully = waitForOwnedProcess(current, gracefulTimeoutMs)
            if (!exitedGracefully || isOwnedProcessAlive(current)) {
                if (!current.supportsForceDestroy) {
                    return IllegalStateException(
                        "llama.cpp process remained alive after graceful termination; " +
                            "forced termination requires Android 8.0 (API 26)",
                    )
                }
                current.forceDestroy()
                if (!waitForOwnedProcess(current, forcedTimeoutMs) || isOwnedProcessAlive(current)) {
                    return IllegalStateException("llama.cpp process remained alive after forced termination")
                }
            }
            if (isOwnedProcessAlive(current)) {
                IllegalStateException("llama.cpp process reported alive after termination")
            } else {
                null
            }
        } catch (error: Throwable) {
            if (error is InterruptedException) {
                Thread.currentThread().interrupt()
            }
            error
        }
    }

    private fun waitForOwnedProcess(current: LlamaProcessStopHandle, timeoutMs: Long): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        while (isOwnedProcessAlive(current)) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return false
            Thread.sleep(pollSleepMillis(remainingNanos))
        }
        return true
    }

    internal fun isOwnedProcessAlive(current: LlamaProcessStopHandle): Boolean = try {
        current.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun pollSleepMillis(remainingNanos: Long): Long {
        val roundedUpMs = (remainingNanos + 999_999L) / 1_000_000L
        return roundedUpMs.coerceIn(1L, PROCESS_POLL_INTERVAL_MS)
    }

    private fun llamaProcessStopHandle(process: Process): LlamaProcessStopHandle {
        return object : LlamaProcessStopHandle {
            override val supportsForceDestroy: Boolean
                get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

            override fun exitValue(): Int = process.exitValue()

            override fun destroy() = process.destroy()

            override fun forceDestroy() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    destroyProcessForciblyApi26(process)
                } else {
                    error("forced process termination requires Android 8.0 (API 26)")
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun destroyProcessForciblyApi26(process: Process) {
        process.destroyForcibly()
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    internal fun shellPathForState(linuxState: JSONObject): String {
        return if (linuxState.optString("execution_mode") == "android_system_shell") {
            ANDROID_SYSTEM_SHELL_PATH
        } else {
            linuxState.optString("shell_path").ifBlank { linuxState.optString("bash_path") }
        }
    }

    private fun selectLlamaServerPath(
        context: Context,
        linuxState: JSONObject,
        lane: LlamaCppRuntimeLane,
    ): String {
        if (lane == LlamaCppRuntimeLane.TURBOQUANT) {
            return HermesLinuxSubsystemBridge.experimentalLlamaServerPath(context)
        }
        val defaultPath = linuxState.optString("native_llama_server_path").ifBlank { "llama-server" }
        val bionicSpawnPath = bionicLlamaServerPath(context, linuxState)
        if (linuxState.optString("execution_mode") == "android_system_shell" && bionicSpawnPath.isFile) {
            return bionicSpawnPath.absolutePath
        }
        val pageSize = devicePageSizeBytes()
        if (pageSize < ANDROID_16K_PAGE_SIZE_BYTES) {
            return defaultPath
        }
        return if (bionicSpawnPath.isFile) bionicSpawnPath.absolutePath else defaultPath
    }

    private fun bionicLlamaServerPath(context: Context, linuxState: JSONObject): File {
        return File(
            linuxState.optString("bionic_llama_server_path").ifBlank {
                val nativeDir = linuxState.optString("native_library_dir")
                    .ifBlank { context.applicationInfo.nativeLibraryDir.orEmpty() }
                File(nativeDir, LEGACY_BIONIC_SPAWN_LLAMA_SERVER_LIBRARY_NAME).absolutePath
            }
        )
    }

    private fun llamaServerOriginLabel(
        linuxState: JSONObject,
        lane: LlamaCppRuntimeLane,
    ): String {
        if (lane == LlamaCppRuntimeLane.TURBOQUANT) {
            return "the packaged experimental TurboQuant Android lane"
        }
        return if (linuxState.optString("execution_mode") == "android_system_shell") {
            "Android's extracted native-library directory"
        } else {
            "the embedded Linux suite"
        }
    }

    private fun llamaServerCompatibilitySuffix(llamaServerPath: String): String {
        return if (llamaServerPath.endsWith(BIONIC_LLAMA_SERVER_NAME) ||
            llamaServerPath.endsWith(LEGACY_BIONIC_SPAWN_LLAMA_SERVER_LIBRARY_NAME)
        ) {
            " using the Android 16 KB page-size libc posix_spawn compatibility launcher"
        } else {
            ""
        }
    }

    private fun drainLogs(startedProcess: Process) {
        Thread {
            runCatching {
                BufferedReader(InputStreamReader(startedProcess.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        recentLog = (recentLog + "\n" + line).takeLast(4000)
                    }
                }
            }
        }.start()
    }

    private fun waitUntilReady(port: Int, candidate: Process, apiKey: String): Boolean {
        repeat(LLAMA_CPP_READY_CHECKS) {
            if (!isProcessAlive(candidate)) {
                return false
            }
            if (checkReady(port, apiKey)) {
                return true
            }
            if (!isProcessAlive(candidate)) {
                return false
            }
            Thread.sleep(250)
        }
        return false
    }

    private fun isProcessAlive(candidate: Process): Boolean =
        isOwnedProcessAlive(llamaProcessStopHandle(candidate))

    private fun checkReady(port: Int, apiKey: String): Boolean {
        val request = authenticatedRequestBuilder(
            url = "http://127.0.0.1:$port/v1/models",
            apiKey = apiKey,
        ).get().build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = response.body?.string().orEmpty()
                JSONObject(body).optJSONArray("data")?.length()?.let { it > 0 } == true
            }
        }.getOrDefault(false)
    }

    private fun protectedEndpointRequiresApiKey(port: Int): Boolean {
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/v1/chat/completions")
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                isApiKeyRejectionStatus(response.code)
            }
        }.getOrDefault(false)
    }

    internal fun isApiKeyRejectionStatus(statusCode: Int): Boolean =
        statusCode == 401 || statusCode == 403

    internal fun cachedCompletionStatusMessage(
        laneDisplayLabel: String,
        completionDetail: String,
        completionLatencyMs: Long,
    ): String = "llama.cpp $laneDisplayLabel lane is serving locally; " +
        "GGUF metadata and a real chat completion canary are verified; " +
        "completion canary passed with $completionDetail in $completionLatencyMs ms"

    private data class CompletionCanary(
        val verified: Boolean,
        val detail: String,
        val elapsedMs: Long,
    )

    private fun runCompletionCanary(
        port: Int,
        modelName: String,
        lane: LlamaCppRuntimeLane,
        apiKey: String,
    ): CompletionCanary {
        val payload = startupCompletionCanaryPayload(modelName, lane)
        val request = authenticatedRequestBuilder(
            url = "http://127.0.0.1:$port/v1/chat/completions",
            apiKey = apiKey,
        )
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val startedAt = System.nanoTime()
        return runCatching {
            canaryHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                if (!response.isSuccessful) {
                    return@use CompletionCanary(
                        verified = false,
                        detail = "HTTP ${response.code}: ${body.take(400)}",
                        elapsedMs = elapsedMs,
                    )
                }
                val message = JSONObject(body)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                val content = completionMessageText(message)
                if (content.isBlank()) {
                    CompletionCanary(
                        verified = false,
                        detail = "HTTP 200 contained no nonblank choices[0].message.content",
                        elapsedMs = elapsedMs,
                    )
                } else {
                    CompletionCanary(
                        verified = true,
                        detail = "nonblank message.content (${content.length} characters)",
                        elapsedMs = elapsedMs,
                    )
                }
            }
        }.getOrElse { error ->
            CompletionCanary(
                verified = false,
                detail = error.message ?: error.javaClass.simpleName,
                elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
            )
        }
    }

    internal fun completionMessageText(message: JSONObject?): String =
        (message?.opt("content") as? String).orEmpty().trim()

    internal fun startupCompletionCanaryPayload(
        modelName: String,
        lane: LlamaCppRuntimeLane = LlamaCppRuntimeLane.STABLE,
    ): JSONObject {
        return nonThinkingCompletionPayload(
            modelName = modelName,
            prompt = "Reply with exactly this word and nothing else: OK",
            lane = lane,
        )
    }

    internal fun releaseMatrixCompletionPayload(
        modelName: String,
        lane: LlamaCppRuntimeLane = LlamaCppRuntimeLane.STABLE,
    ): JSONObject {
        return nonThinkingCompletionPayload(
            modelName = modelName,
            prompt = "Reply with one short word: hello",
            lane = lane,
        )
    }

    private fun nonThinkingCompletionPayload(
        modelName: String,
        prompt: String,
        lane: LlamaCppRuntimeLane,
    ): JSONObject {
        return JSONObject()
            .put("model", modelName)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", prompt),
                ),
            )
            .put("temperature", 0)
            .put("max_tokens", 64)
            .put("stream", false)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            .apply {
                if (lane == LlamaCppRuntimeLane.TURBOQUANT) {
                    // Nanbeige can otherwise spend a short canary entirely in reasoning_content,
                    // which is not a usable assistant response for Hermes' OpenAI-compatible path.
                    put("reasoning_format", "none")
                }
            }
    }

    private fun processExitDetail(candidate: Process): String {
        return runCatching { " (process exit ${candidate.exitValue()})" }
            .getOrDefault(" (process remained alive but never became healthy)")
    }

    private fun actualModelName(port: Int, fallback: String, apiKey: String): String {
        val request = authenticatedRequestBuilder(
            url = "http://127.0.0.1:$port/v1/models",
            apiKey = apiKey,
        ).get().build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use fallback
                }
                val data = JSONObject(body).optJSONArray("data")
                data?.optJSONObject(0)?.optString("id")?.ifBlank { fallback } ?: fallback
            }
        }.getOrDefault(fallback)
    }

    internal fun launchOptionsForModel(
        modelPath: String,
        availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
        contextSizeOverride: Int? = null,
    ): String {
        return defaultLaunchArgumentTokens(
            modelPath = modelPath,
            availableProcessors = availableProcessors,
            contextSizeOverride = contextSizeOverride,
        ).joinToString(" ")
    }

    internal fun launchArgumentTokensForModel(
        modelPath: String,
        availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
        contextSizeOverride: Int? = null,
        launchConfig: LlamaCppLaunchConfig = LlamaCppLaunchConfig(),
    ): List<String> {
        val validation = launchConfig.validate()
        require(validation.valid) { validation.error }
        return defaultLaunchArgumentTokens(
            modelPath = modelPath,
            availableProcessors = availableProcessors,
            contextSizeOverride = contextSizeOverride,
        ) + launchConfig.advancedArgumentTokens()
    }

    internal fun shellCommandForLaunch(
        llamaServerPath: String,
        modelPath: String,
        port: Int,
        availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
        contextSizeOverride: Int? = null,
        launchConfig: LlamaCppLaunchConfig = LlamaCppLaunchConfig(),
        apiKey: String = "",
    ): String {
        val argumentTokens = buildList {
            add(llamaServerPath)
            add("--model")
            add(modelPath)
            add("--host")
            add("127.0.0.1")
            add("--port")
            add(port.toString())
            if (apiKey.isNotBlank()) {
                add("--api-key")
                add(apiKey)
            }
            addAll(
                launchArgumentTokensForModel(
                    modelPath = modelPath,
                    availableProcessors = availableProcessors,
                    contextSizeOverride = contextSizeOverride,
                    launchConfig = launchConfig,
                ),
            )
        }
        return "exec " + argumentTokens.joinToString(" ") { token -> shellQuote(token) }
    }

    private fun defaultLaunchArgumentTokens(
        modelPath: String,
        availableProcessors: Int,
        contextSizeOverride: Int?,
    ): List<String> {
        val ctxSize = contextSizeOverride?.takeIf { it > 0 } ?: contextSizeForModel(modelPath)
        val threads = availableProcessors.coerceIn(1, 4)
        // --jinja is required for GGUF chat-template tool calling (Qwen3.5 / Bonsai Q1_0).
        return listOf(
            "--ctx-size",
            ctxSize.toString(),
            "--parallel",
            "1",
            "--threads",
            threads.toString(),
            "--batch-size",
            "64",
            "--ubatch-size",
            "64",
            "--no-warmup",
            "--jinja",
        )
    }

    private fun LlamaCppRuntimeLane.displayLabel(): String = when (this) {
        LlamaCppRuntimeLane.STABLE -> "stable"
        LlamaCppRuntimeLane.TURBOQUANT -> "experimental TurboQuant"
    }

    private fun LlamaCppRuntimeLane.diagnosticsBackendLabel(): String = when (this) {
        LlamaCppRuntimeLane.STABLE -> "llama.cpp"
        LlamaCppRuntimeLane.TURBOQUANT -> "llama.cpp-turboquant"
    }

    internal fun diagnosticsBreadcrumbFor(
        launchConfig: LlamaCppLaunchConfig,
    ): LocalModelRuntimeDiagnostics.RuntimeLaunchBreadcrumb {
        val validation = launchConfig.validate()
        require(validation.valid) { validation.error }
        return LocalModelRuntimeDiagnostics.RuntimeLaunchBreadcrumb(
            lane = launchConfig.lane.persistedValue,
            cacheTypeK = launchConfig.cacheTypeK.trim().lowercase(Locale.US),
            cacheTypeV = launchConfig.cacheTypeV.trim().lowercase(Locale.US),
            flashAttention = launchConfig.flashAttention.trim().lowercase(Locale.US),
            launchFingerprintSha256 = launchConfig.fingerprint(),
            additionalArgvCount = launchConfig.additionalArguments.size,
            additionalArgvSha256 = sha256ArgumentVector(launchConfig.additionalArguments),
        )
    }

    /**
     * Hashes an argv vector without ever joining it into diagnostics or status text.
     * Length prefixes and NUL separators make token boundaries unambiguous.
     */
    private fun sha256ArgumentVector(arguments: List<String>): String {
        val canonical = arguments.joinToString(separator = "\u0000") { value ->
            "${value.length}:$value"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
    }

    private fun authenticatedRequestBuilder(url: String, apiKey: String): Request.Builder {
        require(apiKey.isNotBlank()) { "A per-process llama.cpp API key is required" }
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
    }

    private fun generateLoopbackApiKey(): String {
        val bytes = ByteArray(32)
        SECURE_RANDOM.nextBytes(bytes)
        return bytes.joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
    }

    internal fun isLoopbackPortAvailable(port: Int): Boolean {
        if (port !in 1..65535) return false
        return runCatching {
            ServerSocket().use { socket ->
                // The owned process may leave completed loopback connections in TIME_WAIT.
                // SO_REUSEADDR permits that normal restart case but still rejects a live
                // listener, which is covered by launchRejectsAPortAlreadyOwnedByAnotherListener.
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            }
            true
        }.getOrDefault(false)
    }

    /** Prevents a backend error which echoes expert argv from writing those tokens to disk. */
    internal fun diagnosticsSafeDetail(
        detail: String,
        launchConfig: LlamaCppLaunchConfig,
    ): String {
        var safeDetail = detail
        launchConfig.additionalArguments
            .asSequence()
            .filter { value -> value.isNotEmpty() }
            .distinct()
            .sortedByDescending { value -> value.length }
            .forEach { value ->
                safeDetail = safeDetail.replace(value, REDACTED_ADDITIONAL_ARGV)
            }
        return safeDetail
    }

    internal fun contextSizeForModel(modelPath: String): Int {
        val lower = modelPath.lowercase(Locale.US)
        return when {
            "0.8b" in lower || "0-8b" in lower || "0_8b" in lower -> 1024
            "0.6b" in lower || "0-6b" in lower || "0_6b" in lower -> 1024
            // 2048 fits one sandbox tool + focused prompt; 4096 prefill is too slow on-device.
            "bonsai" in lower && ("27b" in lower || "q1_0" in lower || "q1-0" in lower) -> 2048
            else -> 2048
        }
    }

    private fun devicePageSizeBytes(): Long {
        return runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L)
    }

    private const val ANDROID_16K_PAGE_SIZE_BYTES = 16_384L
    private const val LLAMA_CPP_READY_CHECKS = 720
    private const val PROCESS_POLL_INTERVAL_MS = 10L
    private const val BIONIC_LLAMA_SERVER_NAME = "llama-server-bionic"
    private const val LEGACY_BIONIC_SPAWN_LLAMA_SERVER_LIBRARY_NAME = "libhermes_android_llama_server_bionic_spawn.so"
    private const val ANDROID_SYSTEM_SHELL_PATH = "/system/bin/sh"
    private const val REDACTED_ADDITIONAL_ARGV = "<redacted-additional-argv>"
    private val SECURE_RANDOM = SecureRandom()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
}
