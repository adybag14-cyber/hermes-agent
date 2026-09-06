package com.mobilefork.hermesagent

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.device.HermesLinuxSandboxBridge
import com.mobilefork.hermesagent.models.VerifiedLocalModelArtifacts
import com.mobilefork.hermesagent.ui.chat.NativeAgentEvent
import com.mobilefork.hermesagent.ui.chat.NativeToolCallingChatClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in experimental observations, never release certification or a skipped-model pass. */
@RunWith(AndroidJUnit4::class)
class LocalModelExperimentInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val evidenceDirectory: File get() = File(context.filesDir, "model-experiments").apply { mkdirs() }

    @Test
    fun installAlpineAndVerifyManualShell() {
        assertTrue("An experimental APK is required", BuildConfig.HERMES_CHAQUOPY_LAB)
        val report = JSONObject().put("release_certified", false).put("model_agency_verified", false)
        val install = HermesLinuxSandboxBridge.performAction(
            context, "install", distroId = "alpine-3-21", name = "hermes-lab-alpine", timeoutSeconds = 900,
        )
        report.put("install", install)
        File(evidenceDirectory, "alpine-infrastructure.json").writeText(report.toString(2))
        assertEquals(install.toString(), 0, install.optInt("exit_code", -1))
        val result = HermesLinuxSandboxBridge.runUserCommand(
            context, "hermes-lab-alpine", "cat /etc/os-release", timeoutSeconds = 60,
        )
        report.put("manual_command", result)
        report.put("manual_shell_verified", result.optInt("exit_code", -1) == 0 && result.toString().contains("Alpine"))
        File(evidenceDirectory, "alpine-infrastructure.json").writeText(report.toString(2))
        assertTrue(report.toString(), report.getBoolean("manual_shell_verified"))
    }

    @Test
    fun exactModelLoadsRepliesAndRecordsOptionalAgency() {
        assertTrue("An experimental APK is required", BuildConfig.HERMES_CHAQUOPY_LAB)
        val args = InstrumentationRegistry.getArguments()
        val caseId = requireNotNull(args.getString("case_id"))
        require(caseId.matches(Regex("[A-Za-z0-9._-]{1,100}")))
        val fileName = requireNotNull(args.getString("model_file_name"))
        require(File(fileName).name == fileName)
        val model = File(context.filesDir, "hermes-home/downloads/models/$fileName")
        val expectedBytes = requireNotNull(args.getString("model_bytes")).toLong()
        val expectedSha = requireNotNull(args.getString("model_sha256"))
        require(expectedSha.matches(Regex("[0-9a-f]{64}")))
        val repo = requireNotNull(args.getString("model_repo"))
        val revision = requireNotNull(args.getString("model_revision"))
        require(revision.matches(Regex("[0-9a-f]{40}")))
        val backend = if (args.getString("backend") == "llama.cpp") BackendKind.LLAMA_CPP else BackendKind.LITERT_LM
        val lane = args.getString("lane", "stable")
        val cache = args.getString("cache_type", "f16")
        val requestedAccelerator = args.getString("accelerator", "cpu")
        require(requestedAccelerator in setOf("cpu", "gpu", "npu"))
        val sandboxPrompt = args.getString("sandbox_prompt", "")
        val reportFile = File(evidenceDirectory, "$caseId.json")
        val report = JSONObject()
            .put("case_id", caseId).put("release_certified", false).put("status", "starting")
            .put("model_repo", repo).put("model_revision", revision).put("model_file", fileName)
            .put("model_bytes", expectedBytes).put("model_sha256", expectedSha)
            .put("backend", backend.persistedValue).put("requested_runtime_lane", lane)
            .put("requested_cache_k", cache).put("requested_cache_v", cache)
            .put("requested_accelerator", requestedAccelerator).put("app_package", BuildConfig.APPLICATION_ID)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("litertlm_coordinate", BuildConfig.HERMES_LITERTLM_COORDINATE)
            .put("android_api", Build.VERSION.SDK_INT).put("android_abi", Build.SUPPORTED_ABIS.first())
            .put("model_agency_verified", false)
        reportFile.writeText(report.toString(2))
        assertTrue("Required model is missing: $fileName", model.isFile)
        assertEquals(expectedBytes, model.length())
        assertEquals(expectedSha, VerifiedLocalModelArtifacts.sha256(model))
        val settingsStore = AppSettingsStore(context)
        val originalSettings = settingsStore.load()
        val downloads = LocalModelDownloadStore(context)
        val originalPreferred = downloads.preferredDownloadId()
        val startedAt = System.nanoTime()
        var apiKey = ""
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES).callTimeout(6, TimeUnit.MINUTES).build()
        try {
            OnDeviceBackendManager.stopAll()
            val record = LocalModelDownloadRecord(
                id = "model-experiment-$caseId", title = caseId,
                sourceUrl = "https://huggingface.co/$repo/resolve/$revision/$fileName",
                repoOrUrl = repo, filePath = fileName, revision = revision,
                runtimeFlavor = if (backend == BackendKind.LITERT_LM) "LiteRT-LM" else "llama.cpp",
                destinationFileName = fileName, destinationPath = model.absolutePath,
                downloadManagerId = -1, totalBytes = expectedBytes, downloadedBytes = expectedBytes,
                status = "completed", statusMessage = "Hash-verified experimental fixture", supportsResume = false,
            )
            downloads.upsertDownload(record)
            downloads.setPreferredDownloadId(record.id)
            settingsStore.save(originalSettings.copy(
                provider = "custom", baseUrl = "", model = caseId, onDeviceBackend = backend.persistedValue,
                localModelAccelerator = requestedAccelerator, liteRtLmSpeculativeDecodingMode = "disabled",
                localModelMaxTokens = 512, llamaCppRuntimeLane = lane,
                llamaCppCacheTypeK = cache, llamaCppCacheTypeV = cache,
                llamaCppFlashAttention = if (cache.startsWith("turbo")) "on" else "auto",
                llamaCppAdditionalArguments = emptyList(),
            ))
            val status = OnDeviceBackendManager.ensureConfigured(context, backend.persistedValue)
            apiKey = status.apiKey
            report.put("runtime_started", status.started).put("accelerator", status.accelerator)
            report.put("startup_elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
            reportFile.writeText(report.toString(2))
            assertTrue(status.statusMessage, status.started)
            assertEquals("A fallback is not proof of the requested accelerator", requestedAccelerator, status.accelerator)
            val runtimeSnapshot = com.mobilefork.hermesagent.device.LocalModelRuntimeDiagnostics.readSnapshot(context)
            report.put("effective_runtime", runtimeSnapshot)
            if (backend == BackendKind.LLAMA_CPP) {
                val effective = requireNotNull(runtimeSnapshot)
                assertEquals(lane, effective.getString("runtime_lane"))
                assertEquals(cache, effective.getString("cache_type_k"))
                assertEquals(cache, effective.getString("cache_type_v"))
            }
            fun request(path: String, body: JSONObject? = null): JSONObject {
                val builder = Request.Builder().url(path)
                if (apiKey.isNotEmpty()) builder.header("Authorization", "Bearer $apiKey")
                if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
                client.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    assertTrue("HTTP ${response.code}: $text", response.isSuccessful)
                    return JSONObject(text)
                }
            }
            val health = request(status.baseUrl.removeSuffix("/v1") + "/health")
            report.put("health_status", health.optString("status"))
            assertEquals(health.toString(), "ok", health.optString("status"))
            val completionStart = System.nanoTime()
            val completion = request(status.baseUrl + "/chat/completions", JSONObject()
                .put("model", status.modelName).put("temperature", 0.2).put("max_tokens", 512).put("stream", false)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "What is 17 + 25? Give the answer briefly."))))
            val content = (completion.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").opt("content") as? String).orEmpty().trim()
            report.put("completion", content)
            report.put("completion_elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - completionStart))
            report.put("completion_nonempty", content.isNotEmpty())
            reportFile.writeText(report.toString(2))
            assertTrue("Reasoning-only or empty assistant content is not a reply", content.isNotEmpty())
            if (sandboxPrompt.isNotBlank()) {
                val enabled = HermesLinuxSandboxBridge.performAction(context, "start", distroId = "alpine-3-21", name = "hermes-lab-alpine")
                assertEquals(enabled.toString(), 0, enabled.optInt("exit_code", -1))
                val events = mutableListOf<NativeAgentEvent>()
                val result = NativeToolCallingChatClient(context).send(
                    baseUrl = status.baseUrl.removeSuffix("/v1"), modelName = status.modelName,
                    apiKey = status.apiKey, providerId = backend.persistedValue,
                    sessionId = "experiment-$caseId", userText = sandboxPrompt, onEvent = events::add,
                )
                val toolResult = runCatching { JSONObject(result.lastToolResult) }.getOrNull()
                val agency = result.modelRequestCount > 0 && result.executedToolCalls > 0 &&
                    toolResult?.optInt("exit_code", -1) == 0
                report.put("agency_prompt", sandboxPrompt).put("agency_reply", result.content)
                    .put("model_request_count", result.modelRequestCount).put("tool_call_count", result.executedToolCalls)
                    .put("last_tool_result", result.lastToolResult).put("model_agency_verified", agency)
                reportFile.writeText(report.toString(2))
                if (args.getString("require_agency", "false").toBoolean()) {
                    assertTrue("Model-generated successful sandbox execution required: $report", agency)
                }
            }
            report.put("status", "passed")
        } catch (failure: Throwable) {
            val detail = failure.message.orEmpty().let { if (apiKey.isEmpty()) it else it.replace(apiKey, "<redacted>") }
            report.put("status", "failed").put("failure", detail)
            throw failure
        } finally {
            report.put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
            reportFile.writeText(report.toString(2))
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            OnDeviceBackendManager.stopAll()
            if (sandboxPrompt.isNotBlank()) {
                HermesLinuxSandboxBridge.performAction(context, "stop", distroId = "alpine-3-21", name = "hermes-lab-alpine")
            }
            settingsStore.save(originalSettings)
            downloads.setPreferredDownloadId(originalPreferred)
        }
    }
}
