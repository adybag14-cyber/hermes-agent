package com.mobilefork.hermesagent

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.backend.LlamaCppServerController
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.device.LocalModelRuntimeDiagnostics
import com.mobilefork.hermesagent.ui.chat.NativeToolCallingChatClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Exact, content-addressed Android gate for the legacy-metadata Nanbeige GGUF
 * which originally failed v0.13.149 with "unknown model architecture".
 *
 * The outer release harness must provision the immutable publisher artifact
 * and pass its app-readable path with `-e model_path ...`. A passing record
 * requires the packaged experimental executable, Turbo3 K/V cache, effective
 * Flash Attention, `/v1/models`, the controller canary, and a second nonempty
 * chat completion all to succeed on the headed device.
 */
@RunWith(AndroidJUnit4::class)
class NanbeigeTurboQuantInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    private var originalSettings: AppSettings? = null
    private var originalDownloads: List<LocalModelDownloadRecord>? = null
    private var originalPreferredDownloadId: String? = null
    private var originalPendingAutoStartRecordId: String? = null
    private var cleanShutdownCompleted = false

    @After
    fun tearDown() {
        try {
            if (!cleanShutdownCompleted) {
                val stopState = HermesRuntimeManager.stop()
                assertFalse(stopState.error.orEmpty(), stopState.started)
                assertTrue(stopState.error.orEmpty(), stopState.error.isNullOrBlank())
                val local = OnDeviceBackendManager.currentStatus()
                assertFalse(local.statusMessage, local.started)
                assertFalse(local.statusMessage, local.requiresAppRestart)
            }
        } finally {
            originalSettings?.let { AppSettingsStore(context).save(it) }
            originalDownloads?.let { downloads ->
                LocalModelDownloadStore(context).apply {
                    saveDownloads(downloads)
                    setPreferredDownloadId(originalPreferredDownloadId.orEmpty())
                    setPendingAutoStartRecordId(originalPendingAutoStartRecordId.orEmpty())
                }
            }
            originalSettings = null
            originalDownloads = null
            originalPreferredDownloadId = null
            originalPendingAutoStartRecordId = null
            cleanShutdownCompleted = false
        }
    }

    @Test
    fun exactPublisherQ4KmStartsWithTurbo3AndAnswers() {
        val args = InstrumentationRegistry.getArguments()
        val developmentEvidence = args.getString(ARG_DEVELOPMENT_EVIDENCE, "false")
            .orEmpty()
            .trim()
            .equals("true", ignoreCase = true)
        val modelPath = args.getString("model_path", "").orEmpty().trim()
        if (modelPath.isBlank()) {
            fail("Required -e model_path for $FILE_NAME was not provided")
        }
        val modelFile = File(modelPath)
        assertTrue("Required Nanbeige model is missing at ${modelFile.absolutePath}", modelFile.isFile)
        assertEquals("Publisher byte count changed", EXPECTED_BYTES, modelFile.length())
        val deviceSha256 = ReleaseDeviceEvidenceIdentity.sha256(modelFile)
        assertEquals("Publisher SHA-256 changed", EXPECTED_SHA256, deviceSha256)
        assertPackagedExperimentalLicenseNotices()

        seedAppModelSelection(modelFile)
        val persisted = AppSettingsStore(context).load()
        assertEquals(BackendKind.LLAMA_CPP.persistedValue, persisted.onDeviceBackend)
        assertEquals("turboquant", persisted.llamaCppRuntimeLane)
        assertEquals("turbo3", persisted.llamaCppCacheTypeK)
        assertEquals("turbo3", persisted.llamaCppCacheTypeV)
        assertEquals("on", persisted.llamaCppFlashAttention)
        assertEquals(EXPERT_ARGUMENTS, persisted.llamaCppAdditionalArguments)

        val startedAt = System.nanoTime()
        val runtime = HermesRuntimeManager.ensureStarted(context)
        assertTrue(runtime.error.orEmpty(), runtime.started)
        assertTrue("Owned llama.cpp runtime must publish an ephemeral bearer token", !runtime.apiKey.isNullOrBlank())
        val status = OnDeviceBackendManager.currentStatus()
        assertTrue(status.statusMessage, status.started)
        assertTrue(status.statusMessage, status.completionVerified)
        assertTrue(status.statusMessage, status.completionLatencyMs > 0L)
        assertTrue(status.statusMessage, status.statusMessage.contains("experimental TurboQuant"))
        assertEquals("cpu", status.accelerator)
        assertEquals(modelFile.absolutePath, status.sourceModelPath)
        assertEquals(runtime.baseUrl, status.baseUrl)
        assertEquals(runtime.apiKey, status.apiKey)

        val publicModelsCode = client.newCall(
            Request.Builder()
                .url("${status.baseUrl}/models")
                .get()
                .build(),
        ).execute().use { response -> response.code }
        assertEquals("Pinned llama.cpp exposes GET /v1/models as public metadata", 200, publicModelsCode)

        val unauthorizedChatCode = client.newCall(
            Request.Builder()
                .url("${status.baseUrl}/chat/completions")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        ).execute().use { response -> response.code }
        assertTrue(
            "The data-bearing chat endpoint must reject an unowned loopback caller (HTTP $unauthorizedChatCode)",
            unauthorizedChatCode == 401 || unauthorizedChatCode == 403,
        )

        val models = executeJson(
            Request.Builder()
                .url("${status.baseUrl}/models")
                .header("Authorization", "Bearer ${status.apiKey}")
                .get()
                .build(),
        )
        val servedModels = models.optJSONArray("data") ?: JSONArray()
        assertTrue("Expected a nonempty llama.cpp /v1/models response: $models", servedModels.length() > 0)

        val appChat = NativeToolCallingChatClient(context).send(
            baseUrl = requireNotNull(runtime.baseUrl),
            modelName = requireNotNull(runtime.modelName),
            apiKey = requireNotNull(runtime.apiKey),
            sessionId = "nanbeige-turboquant-release-matrix",
            userText = "Reply with a short visible greeting. Do not call a tool.",
            providerId = BackendKind.LLAMA_CPP.persistedValue,
        )
        val messageContent = appChat.content.trim()
        assertFalse("Expected a real nonblank answer through the app's native chat client", messageContent.isBlank())
        assertFalse("The app chat must not fall back to its empty-response placeholder", messageContent == "Done.")
        assertTrue("Expected at least one real native model request", appChat.modelRequestCount > 0)

        val diagnostics = requireNotNull(LocalModelRuntimeDiagnostics.readSnapshot(context)) {
            "Expected a persistent local-runtime diagnostics breadcrumb"
        }
        assertEquals("ready", diagnostics.optString("status"))
        assertEquals("llama.cpp-turboquant", diagnostics.optString("backend"))
        assertEquals("turboquant", diagnostics.optString("runtime_lane"))
        assertEquals("turbo3", diagnostics.optString("cache_type_k"))
        assertEquals("turbo3", diagnostics.optString("cache_type_v"))
        assertEquals("on", diagnostics.optString("flash_attention"))
        assertEquals(EXPERT_ARGUMENTS.size, diagnostics.optInt("additional_argv_count", -1))
        assertTrue(
            "Expected a one-way expert-argv identity",
            diagnostics.optString("additional_argv_sha256").matches(Regex("[0-9a-f]{64}")),
        )
        assertFalse("Diagnostics must not expose raw expert argv", diagnostics.toString().contains("threads-batch"))

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue("Expected positive runtime elapsed time", elapsedMs > 0L)
        AppSettingsStore(context).save(
            AppSettingsStore(context).load().copy(
                onDeviceBackend = BackendKind.NONE.persistedValue,
                offlineAirplaneMode = true,
            ),
        )
        val switchedRuntime = HermesRuntimeManager.ensureStarted(context)
        assertFalse(
            "Selecting no on-device backend must not preserve the previous authenticated local runtime",
            switchedRuntime.started,
        )
        assertTrue("Expected an explicit no-runtime status", !switchedRuntime.error.isNullOrBlank())
        assertTrue("Stale local URL survived the backend switch", switchedRuntime.baseUrl.isNullOrBlank())
        assertTrue("Stale local bearer token survived the backend switch", switchedRuntime.apiKey.isNullOrBlank())
        val switchedLocal = OnDeviceBackendManager.currentStatus()
        assertFalse(switchedLocal.statusMessage, switchedLocal.started)
        assertEquals(BackendKind.NONE, switchedLocal.backendKind)
        assertTrue(
            "Owned llama.cpp loopback port remained unavailable after selecting no local backend",
            waitForLoopbackPortRelease(OnDeviceBackendManager.LLAMA_CPP_PORT),
        )

        val stopState = HermesRuntimeManager.stop()
        assertFalse(stopState.error.orEmpty(), stopState.started)
        assertTrue(stopState.error.orEmpty(), stopState.error.isNullOrBlank())
        val stoppedLocal = OnDeviceBackendManager.currentStatus()
        assertFalse(stoppedLocal.statusMessage, stoppedLocal.started)
        assertFalse(stoppedLocal.statusMessage, stoppedLocal.requiresAppRestart)
        assertTrue(
            "Owned llama.cpp loopback port remained unavailable after stop",
            waitForLoopbackPortRelease(OnDeviceBackendManager.LLAMA_CPP_PORT),
        )
        cleanShutdownCompleted = true

        val evidenceRecord = ModelMatrixEvidence.Record(
                backend = "llama.cpp-turboquant",
                instrumentationMethod =
                    "NanbeigeTurboQuantInstrumentedTest#exactPublisherQ4KmStartsWithTurbo3AndAnswers",
                modelId = MODEL_ID,
                publisherRepository = PUBLISHER_REPOSITORY,
                publisherRevision = PUBLISHER_REVISION,
                fileName = FILE_NAME,
                devicePath = modelFile.absolutePath,
                publisherExpectedBytes = EXPECTED_BYTES,
                deviceVisibleBytes = modelFile.length(),
                expectedSha256 = EXPECTED_SHA256,
                deviceSha256 = deviceSha256,
                runtimeStarted = status.started,
                healthOk = servedModels.length() > 0,
                completionNonEmpty = messageContent.isNotBlank(),
                elapsedMs = elapsedMs,
                accelerator = status.accelerator,
                statusMessage = status.statusMessage,
                details = JSONObject()
                    .put("runtime_lane", "turboquant")
                    .put("cache_type_k", "turbo3")
                    .put("cache_type_v", "turbo3")
                    .put("flash_attention", "on")
                    .put("runtime_entrypoint", "persisted-settings-hermes-runtime-manager-native-chat-client")
                    .put("loopback_port", OnDeviceBackendManager.LLAMA_CPP_PORT)
                    .put("loopback_bearer_required", true)
                    .put("public_models_http_code", publicModelsCode)
                    .put("unauthorized_chat_http_code", unauthorizedChatCode)
                    .put("additional_argv_count", EXPERT_ARGUMENTS.size)
                    .put("additional_argv_sha256", diagnostics.optString("additional_argv_sha256"))
                    .put("served_model_count", servedModels.length())
                    .put("completion_characters", messageContent.length)
                    .put("native_chat_model_request_count", appChat.modelRequestCount)
                    .put("native_chat_executed_tool_calls", appChat.executedToolCalls)
                    .put("startup_completion_canary_verified", status.completionVerified)
                    .put("startup_completion_canary_ms", status.completionLatencyMs)
                    .put("settings_switch_to_none_stopped_local", true)
                    .put("clean_shutdown", true)
                    .put("loopback_port_released", true)
                    .put("artifact_summary", status.artifactSummary),
        )
        val evidenceFile = if (developmentEvidence) {
            emitDevelopmentEvidence(evidenceRecord)
        } else {
            ModelMatrixEvidence.emit(context, evidenceRecord)
        }
        assertTrue("Expected durable Nanbeige evidence at ${evidenceFile.absolutePath}", evidenceFile.isFile)
    }

    /**
     * Records an exact installed-candidate proof while this feature is still in an intentionally
     * dirty development tree. This cannot satisfy the committed release-evidence gate: the
     * release path above remains the default and still requires [ReleaseDeviceEvidenceIdentity].
     */
    private fun emitDevelopmentEvidence(record: ModelMatrixEvidence.Record): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val outputDirectory = File(context.filesDir, DEVELOPMENT_EVIDENCE_DIRECTORY).apply { mkdirs() }
        val outputFile = File(outputDirectory, "nanbeige-turboquant-${System.currentTimeMillis()}.json")
        val payload = JSONObject()
            .put("schema", DEVELOPMENT_EVIDENCE_SCHEMA)
            .put("release_evidence", false)
            .put("result", "passed")
            .put("package_id", BuildConfig.APPLICATION_ID)
            .put("version_name", BuildConfig.VERSION_NAME)
            .put("version_code", BuildConfig.VERSION_CODE)
            .put("app_apk_sha256", ReleaseDeviceEvidenceIdentity.sha256(File(context.applicationInfo.sourceDir)))
            .put(
                "instrumentation_apk_sha256",
                ReleaseDeviceEvidenceIdentity.sha256(File(instrumentation.context.applicationInfo.sourceDir)),
            )
            .put("backend", record.backend)
            .put("instrumentation_method", record.instrumentationMethod)
            .put("model_id", record.modelId)
            .put("publisher_repository", record.publisherRepository)
            .put("publisher_revision", record.publisherRevision)
            .put("file_name", record.fileName)
            .put("device_path", record.devicePath)
            .put("publisher_expected_bytes", record.publisherExpectedBytes)
            .put("device_visible_bytes", record.deviceVisibleBytes)
            .put("expected_sha256", record.expectedSha256.lowercase())
            .put("device_sha256", record.deviceSha256.lowercase())
            .put("runtime_started", record.runtimeStarted)
            .put("health_ok", record.healthOk)
            .put("completion_nonempty", record.completionNonEmpty)
            .put("elapsed_ms", record.elapsedMs)
            .put("accelerator", record.accelerator)
            .put("status_message", record.statusMessage)
            .put(
                "device_serial_argument",
                InstrumentationRegistry.getArguments().getString("device_serial", ""),
            )
            .put("avd_name", readSystemProperty("ro.boot.qemu.avd_name"))
            .put("device_boot_id", File("/proc/sys/kernel/random/boot_id").readText().trim())
            .put("details", JSONObject(record.details.toString()))

        outputFile.writeText(payload.toString(2), Charsets.UTF_8)
        assertTrue("Unable to persist development evidence at ${outputFile.absolutePath}", outputFile.isFile)
        val compact = payload.toString()
        instrumentation.addResults(Bundle().apply { putString(DEVELOPMENT_EVIDENCE_RESULT_KEY, compact) })
        println("$DEVELOPMENT_EVIDENCE_LOG_PREFIX$compact")
        return outputFile
    }

    private fun readSystemProperty(name: String): String {
        val process = ProcessBuilder("/system/bin/getprop", name)
            .redirectErrorStream(true)
            .start()
        val value = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        check(process.waitFor() == 0 && value.isNotBlank()) {
            "Unable to read required Android system property $name"
        }
        return value
    }

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            assertTrue(body, response.isSuccessful)
            return JSONObject(body)
        }
    }

    private fun assertPackagedExperimentalLicenseNotices() {
        EXPECTED_LICENSE_ASSETS.forEach { (assetPath, expected) ->
            val digest = MessageDigest.getInstance("SHA-256")
            var byteCount = 0L
            context.assets.open(assetPath).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    byteCount += read
                }
            }
            assertEquals("Packaged notice byte count changed for $assetPath", expected.first, byteCount)
            assertEquals(
                "Packaged notice SHA-256 changed for $assetPath",
                expected.second,
                digest.digest().joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                },
            )
        }
    }

    private fun seedAppModelSelection(modelFile: File) {
        val settingsStore = AppSettingsStore(context)
        val downloadStore = LocalModelDownloadStore(context)
        originalSettings = settingsStore.load()
        originalDownloads = downloadStore.loadDownloads()
        originalPreferredDownloadId = downloadStore.preferredDownloadId()
        originalPendingAutoStartRecordId = downloadStore.pendingAutoStartRecordId()

        val record = LocalModelDownloadRecord(
            id = BACKEND_MANAGER_RECORD_ID,
            title = MODEL_ID,
            sourceUrl = "https://huggingface.co/$PUBLISHER_REPOSITORY/resolve/$PUBLISHER_REVISION/$FILE_NAME",
            repoOrUrl = PUBLISHER_REPOSITORY,
            filePath = FILE_NAME,
            revision = PUBLISHER_REVISION,
            runtimeFlavor = "GGUF",
            destinationFileName = FILE_NAME,
            destinationPath = modelFile.absolutePath,
            downloadManagerId = -1L,
            totalBytes = modelFile.length(),
            downloadedBytes = modelFile.length(),
            status = "completed",
            statusMessage = "Provisioned for content-addressed Nanbeige TurboQuant instrumentation",
            supportsResume = false,
        )
        downloadStore.apply {
            upsertDownload(record)
            setPreferredDownloadId(record.id)
            setPendingAutoStartRecordId("")
        }
        settingsStore.save(
            requireNotNull(originalSettings).copy(
                provider = "custom",
                model = MODEL_ID,
                offlineAirplaneMode = true,
                onDeviceBackend = BackendKind.LLAMA_CPP.persistedValue,
                llamaCppRuntimeLane = "turboquant",
                llamaCppCacheTypeK = "turbo3",
                llamaCppCacheTypeV = "turbo3",
                llamaCppFlashAttention = "on",
                llamaCppAdditionalArguments = EXPERT_ARGUMENTS,
                localModelToolMode = "small",
            ),
        )
    }

    private fun waitForLoopbackPortRelease(port: Int): Boolean {
        repeat(40) {
            if (LlamaCppServerController.isLoopbackPortAvailable(port)) return true
            Thread.sleep(100)
        }
        return false
    }

    private companion object {
        private const val MODEL_ID = "Tdamre/Nanbeige4.2-3B-GGUF"
        private const val PUBLISHER_REPOSITORY = "Tdamre/Nanbeige4.2-3B-GGUF"
        private const val PUBLISHER_REVISION = "128d8e87d69f9c1a30c37e40530c69deda96475d"
        private const val FILE_NAME = "Nanbeige4.2-3B-Q4_K_M.gguf"
        private const val EXPECTED_BYTES = 2_574_807_840L
        private const val EXPECTED_SHA256 =
            "99c7bfb88907f7eee0a04c4314f1c46bca391819478d8cb90b3e164f09576489"
        private const val BACKEND_MANAGER_RECORD_ID = "model-matrix-nanbeige-turboquant"
        private const val ARG_DEVELOPMENT_EVIDENCE = "hermes_development_evidence"
        private const val DEVELOPMENT_EVIDENCE_SCHEMA = "hermes-model-development-evidence-v1"
        private const val DEVELOPMENT_EVIDENCE_DIRECTORY = "hermes-model-development-evidence"
        private const val DEVELOPMENT_EVIDENCE_RESULT_KEY = "HERMES_MODEL_DEVELOPMENT_EVIDENCE"
        private const val DEVELOPMENT_EVIDENCE_LOG_PREFIX = "HERMES_MODEL_DEVELOPMENT_EVIDENCE "
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EXPERT_ARGUMENTS = listOf("--threads-batch", "3")
        private val EXPECTED_LICENSE_ASSETS = mapOf(
            "hermes-experimental-llama/LICENSE.txt" to Pair(
                1_078L,
                "94f29bbed6a22c35b992c5c6ebf0e7c92f13b836b90f36f461c9cf2f0f1d010d",
            ),
            "hermes-experimental-llama/licenses/LICENSE-jsonhpp.txt" to Pair(
                1_075L,
                "c0d068392ea65358b798b8c165103560f06e9e3b38c4ab4e2d8810a7b931af86",
            ),
            "hermes-experimental-llama/licenses/LICENSE-cpp-httplib.txt" to Pair(
                1_075L,
                "4b45cbe16d7b71b89ae6127e26e0d90a029198ca5e958ad8e3d0b8bbed364d8b",
            ),
        )
    }
}
