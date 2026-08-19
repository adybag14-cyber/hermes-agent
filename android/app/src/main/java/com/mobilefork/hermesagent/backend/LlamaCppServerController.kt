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
    @Volatile private var activeCompletionLatencyMs: Long = 0L
    @Volatile private var activeArtifactSummary: String = ""

    @Synchronized
    fun ensureRunning(
        context: Context,
        modelPath: String,
        requestedModelName: String,
        port: Int,
    ): LocalBackendStatus {
        val currentProcess = process
        if (
            currentProcess != null &&
            isProcessAlive(currentProcess) &&
            activeModelPath == modelPath &&
            activeCompletionVerified &&
            checkReady(port)
        ) {
            return LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = true,
                baseUrl = "http://127.0.0.1:$port/v1",
                modelName = actualModelName(port, requestedModelName),
                sourceModelPath = modelPath,
                statusMessage = "llama.cpp is serving locally; GGUF metadata and a real chat completion canary are verified",
                accelerator = "cpu",
                artifactSummary = activeArtifactSummary,
                completionVerified = true,
                completionLatencyMs = activeCompletionLatencyMs,
            )
        }

        stop()?.let { failure ->
            return LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = false,
                sourceModelPath = modelPath,
                statusMessage = llamaStopFailureMessage("another llama.cpp model", failure),
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
            backend = "llama.cpp",
            modelBytes = modelFile.length(),
            requestedContextTokens = requestedContext,
            memory = memory,
        )
        val attemptId = LocalModelRuntimeDiagnostics.beginAttempt(
            context = context,
            backend = "llama.cpp",
            modelFile = modelFile,
            requestedAccelerator = "cpu",
            requestedContextTokens = requestedContext,
            effectiveContextTokens = preflight.effectiveContextTokens,
            memory = memory,
            preflight = preflight,
        )
        if (!preflight.allowed) {
            LocalModelRuntimeDiagnostics.finishAttempt(
                context = context,
                attemptId = attemptId,
                status = "blocked",
                stage = "memory_preflight",
                detail = preflight.detail,
            )
            return LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = false,
                sourceModelPath = modelPath,
                artifactSummary = inspection.summary,
                statusMessage = "llama.cpp memory preflight blocked this model: ${preflight.detail}",
            )
        }
        val linuxState = HermesLinuxSubsystemBridge.ensureInstalled(context)
        val shellPath = shellPathForState(linuxState)
        val prefixPath = linuxState.optString("prefix_path")
        val homePath = linuxState.optString("home_path")
        val llamaServerPath = selectLlamaServerPath(context, linuxState)
        if (shellPath.isBlank() || prefixPath.isBlank()) {
            LocalModelRuntimeDiagnostics.finishAttempt(
                context = context,
                attemptId = attemptId,
                status = "failed",
                stage = "runtime_discovery",
                detail = "The embedded Linux suite is not ready yet for llama.cpp",
            )
            return LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = false,
                sourceModelPath = modelPath,
                statusMessage = "The embedded Linux suite is not ready yet for llama.cpp",
                artifactSummary = inspection.summary,
            )
        }
        if (!File(llamaServerPath).canExecute()) {
            val fallbackReason = linuxState.optString("fallback_reason").ifBlank {
                "embedded Linux shell could not be launched"
            }
            val shellModeHint = if (linuxState.optString("execution_mode") == "android_system_shell") {
                " Native Android shell fallback reason: $fallbackReason."
            } else {
                ""
            }
            val detail = "llama.cpp executable is not available at $llamaServerPath.$shellModeHint Use LiteRT-LM .litertlm models for fully native local inference."
            LocalModelRuntimeDiagnostics.finishAttempt(
                context = context,
                attemptId = attemptId,
                status = "failed",
                stage = "runtime_discovery",
                detail = detail,
            )
            return LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = false,
                sourceModelPath = modelPath,
                statusMessage = detail,
                artifactSummary = inspection.summary,
            )
        }

        val command = buildString {
            append("exec ")
            append(shellQuote(llamaServerPath))
            append(" ")
            append("--model ")
            append(shellQuote(modelPath))
            append(" --host 127.0.0.1 --port ")
            append(port)
            append(" ")
            append(launchOptionsForModel(modelPath, contextSizeOverride = preflight.effectiveContextTokens))
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
            drainLogs(startedProcess)
            if (!waitUntilReady(port, startedProcess)) {
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
                )
                LocalModelRuntimeDiagnostics.finishAttempt(
                    context = context,
                    attemptId = attemptId,
                    status = "failed",
                    stage = "server_readiness",
                    detail = status.statusMessage,
                )
                return status
            }
            val modelName = actualModelName(port, requestedModelName)
            val canary = runCompletionCanary(port, modelName)
            if (!canary.verified) {
                val failure = "llama.cpp opened /v1/models but failed the required chat completion canary: ${canary.detail}"
                val status = failureStatusAfterStop(
                    modelPath = modelPath,
                    artifactSummary = inspection.summary,
                    detail = failure,
                    accelerator = "cpu",
                    completionLatencyMs = canary.elapsedMs,
                )
                LocalModelRuntimeDiagnostics.finishAttempt(
                    context = context,
                    attemptId = attemptId,
                    status = "failed",
                    stage = "completion_canary",
                    detail = status.statusMessage,
                    accelerator = "cpu",
                    completionLatencyMs = canary.elapsedMs,
                )
                return status
            }
            activeCompletionVerified = true
            activeCompletionLatencyMs = canary.elapsedMs
            activeArtifactSummary = inspection.summary
            val status = LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = true,
                baseUrl = "http://127.0.0.1:$port/v1",
                modelName = modelName,
                sourceModelPath = modelPath,
                statusMessage = "llama.cpp is serving locally from ${llamaServerOriginLabel(linuxState)}${llamaServerCompatibilitySuffix(llamaServerPath)}; ${inspection.summary}; completion canary passed in ${canary.elapsedMs} ms. ${preflight.detail}",
                accelerator = "cpu",
                artifactSummary = inspection.summary,
                completionVerified = true,
                completionLatencyMs = canary.elapsedMs,
            )
            LocalModelRuntimeDiagnostics.finishAttempt(
                context = context,
                attemptId = attemptId,
                status = "ready",
                stage = "completion_verified",
                detail = status.statusMessage,
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
            )
            LocalModelRuntimeDiagnostics.finishAttempt(
                context = context,
                attemptId = attemptId,
                status = "failed",
                stage = "server_start",
                detail = status.statusMessage,
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
        activeCompletionLatencyMs = 0L
        activeArtifactSummary = ""
    }

    private fun failureStatusAfterStop(
        modelPath: String,
        artifactSummary: String,
        detail: String,
        accelerator: String = "",
        completionLatencyMs: Long = 0L,
    ): LocalBackendStatus {
        val stopFailure = stop()
        return LocalBackendStatus(
            backendKind = BackendKind.LLAMA_CPP,
            started = false,
            sourceModelPath = modelPath,
            statusMessage = if (stopFailure == null) {
                detail
            } else {
                "$detail ${llamaStopFailureMessage("a replacement backend", stopFailure)}"
            },
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

    private fun isOwnedProcessAlive(current: LlamaProcessStopHandle): Boolean = try {
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

    private fun selectLlamaServerPath(context: Context, linuxState: JSONObject): String {
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

    private fun llamaServerOriginLabel(linuxState: JSONObject): String {
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

    private fun waitUntilReady(port: Int, candidate: Process): Boolean {
        repeat(LLAMA_CPP_READY_CHECKS) {
            if (!isProcessAlive(candidate)) {
                return false
            }
            if (checkReady(port)) {
                return true
            }
            if (!isProcessAlive(candidate)) {
                return false
            }
            Thread.sleep(250)
        }
        return false
    }

    private fun isProcessAlive(candidate: Process): Boolean = try {
        candidate.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun checkReady(port: Int): Boolean {
        val request = Request.Builder().url("http://127.0.0.1:$port/v1/models").get().build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val body = response.body?.string().orEmpty()
                JSONObject(body).optJSONArray("data")?.length()?.let { it > 0 } == true
            }
        }.getOrDefault(false)
    }

    private data class CompletionCanary(
        val verified: Boolean,
        val detail: String,
        val elapsedMs: Long,
    )

    private fun runCompletionCanary(port: Int, modelName: String): CompletionCanary {
        val payload = startupCompletionCanaryPayload(modelName)
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/v1/chat/completions")
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
                val content = message?.optString("content").orEmpty().trim()
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

    internal fun startupCompletionCanaryPayload(modelName: String): JSONObject {
        return nonThinkingCompletionPayload(
            modelName = modelName,
            prompt = "Reply with exactly this word and nothing else: OK",
        )
    }

    internal fun releaseMatrixCompletionPayload(modelName: String): JSONObject {
        return nonThinkingCompletionPayload(
            modelName = modelName,
            prompt = "Reply with one short word: hello",
        )
    }

    private fun nonThinkingCompletionPayload(modelName: String, prompt: String): JSONObject {
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
    }

    private fun processExitDetail(candidate: Process): String {
        return runCatching { " (process exit ${candidate.exitValue()})" }
            .getOrDefault(" (process remained alive but never became healthy)")
    }

    private fun actualModelName(port: Int, fallback: String): String {
        val request = Request.Builder().url("http://127.0.0.1:$port/v1/models").get().build()
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
        val ctxSize = contextSizeOverride?.takeIf { it > 0 } ?: contextSizeForModel(modelPath)
        val threads = availableProcessors.coerceIn(1, 4)
        // --jinja is required for GGUF chat-template tool calling (Qwen3.5 / Bonsai Q1_0).
        return "--ctx-size $ctxSize --parallel 1 --threads $threads --batch-size 64 --ubatch-size 64 --no-warmup --jinja"
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
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
}
