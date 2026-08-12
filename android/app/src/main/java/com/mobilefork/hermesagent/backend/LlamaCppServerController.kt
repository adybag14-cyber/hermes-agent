package com.mobilefork.hermesagent.backend

import android.content.Context
import android.system.Os
import android.system.OsConstants
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
            currentProcess.isAlive &&
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

        stop()
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
                stop()
                LocalModelRuntimeDiagnostics.finishAttempt(
                    context = context,
                    attemptId = attemptId,
                    status = "failed",
                    stage = "server_readiness",
                    detail = failure,
                )
                return LocalBackendStatus(
                    backendKind = BackendKind.LLAMA_CPP,
                    started = false,
                    sourceModelPath = modelPath,
                    statusMessage = failure,
                    artifactSummary = inspection.summary,
                )
            }
            val modelName = actualModelName(port, requestedModelName)
            val canary = runCompletionCanary(port, modelName)
            if (!canary.verified) {
                val failure = "llama.cpp opened /v1/models but failed the required chat completion canary: ${canary.detail}"
                stop()
                LocalModelRuntimeDiagnostics.finishAttempt(
                    context = context,
                    attemptId = attemptId,
                    status = "failed",
                    stage = "completion_canary",
                    detail = failure,
                    accelerator = "cpu",
                    completionLatencyMs = canary.elapsedMs,
                )
                return LocalBackendStatus(
                    backendKind = BackendKind.LLAMA_CPP,
                    started = false,
                    sourceModelPath = modelPath,
                    statusMessage = failure,
                    accelerator = "cpu",
                    artifactSummary = inspection.summary,
                    completionVerified = false,
                    completionLatencyMs = canary.elapsedMs,
                )
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
            stop()
            val failure = LiteRtLmOpenAiProxy.actionableRuntimeFailure(error, "llama.cpp")
            LocalModelRuntimeDiagnostics.finishAttempt(
                context = context,
                attemptId = attemptId,
                status = "failed",
                stage = "server_start",
                detail = failure,
            )
            LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = false,
                sourceModelPath = modelPath,
                statusMessage = failure,
                artifactSummary = inspection.summary,
            )
        }
    }

    @Synchronized
    fun stop() {
        process?.let { current ->
            runCatching {
                current.destroy()
                if (!current.waitFor(1200, TimeUnit.MILLISECONDS)) {
                    current.destroyForcibly()
                    current.waitFor(1200, TimeUnit.MILLISECONDS)
                }
            }
        }
        process = null
        activeModelPath = ""
        activeModelName = ""
        recentLog = ""
        activeCompletionVerified = false
        activeCompletionLatencyMs = 0L
        activeArtifactSummary = ""
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
        val payload = JSONObject()
            .put("model", modelName)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "Reply with exactly this word and nothing else: OK"),
                ),
            )
            .put("temperature", 0)
            .put("max_tokens", 64)
            .put("stream", false)
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
        return "--ctx-size $ctxSize --parallel 1 --threads $threads --batch-size 64 --ubatch-size 64 --no-warmup"
    }

    internal fun contextSizeForModel(modelPath: String): Int {
        val lower = modelPath.lowercase(Locale.US)
        return when {
            "0.8b" in lower || "0-8b" in lower || "0_8b" in lower -> 1024
            "0.6b" in lower || "0-6b" in lower || "0_6b" in lower -> 1024
            else -> 2048
        }
    }

    private fun devicePageSizeBytes(): Long {
        return runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L)
    }

    private const val ANDROID_16K_PAGE_SIZE_BYTES = 16_384L
    private const val LLAMA_CPP_READY_CHECKS = 720
    private const val BIONIC_LLAMA_SERVER_NAME = "llama-server-bionic"
    private const val LEGACY_BIONIC_SPAWN_LLAMA_SERVER_LIBRARY_NAME = "libhermes_android_llama_server_bionic_spawn.so"
    private const val ANDROID_SYSTEM_SHELL_PATH = "/system/bin/sh"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
}
