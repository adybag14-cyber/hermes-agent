package com.mobilefork.hermesagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilefork.hermesagent.backend.LlamaCppLaunchConfig
import com.mobilefork.hermesagent.backend.LlamaCppRuntimeLane
import com.mobilefork.hermesagent.backend.LlamaCppServerController
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import com.mobilefork.hermesagent.models.VerifiedLocalModelArtifacts
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LlamaCppModelMatrixInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    @After
    fun tearDown() {
        OnDeviceBackendManager.stopAll()
    }

    @Test
    fun provisionedContentAddressedGgufStartsAndAnswers() {
        val args = InstrumentationRegistry.getArguments()
        val fileName = args.getString("model_file_name", DEFAULT_FILE_NAME)
        val artifact = VerifiedLocalModelArtifacts.findByFileName(fileName)
            ?: throw AssertionError("No release-matrix metadata for $fileName")
        val modelPath = args.getString("model_path", "").ifBlank {
            File(context.filesDir, "hermes-home/downloads/models/$fileName").absolutePath
        }
        val modelFile = File(modelPath)
        val requireModel = args.getString("require_model", "false").toBoolean()
        if (!modelFile.isFile && requireModel) {
            fail("Required GGUF model is not provisioned at ${modelFile.absolutePath}")
        }
        assumeTrue("GGUF model is not provisioned at ${modelFile.absolutePath}", modelFile.isFile)

        val verification = VerifiedLocalModelArtifacts.verify(modelFile, artifact)
        assertTrue(verification.detail, verification.valid)
        assertEquals(artifact.expectedBytes, verification.actualBytes)
        assertEquals(artifact.sha256, verification.actualSha256)

        val startedAt = System.nanoTime()
        val requiredLane = LlamaCppRuntimeLane.fromPersistedValue(
            artifact.requiredLlamaCppRuntimeLane,
        )
        val launchConfig = when (requiredLane) {
            LlamaCppRuntimeLane.TURBOQUANT -> LlamaCppLaunchConfig(
                lane = requiredLane,
                cacheTypeK = "turbo3",
                cacheTypeV = "turbo3",
                flashAttention = "on",
            )
            LlamaCppRuntimeLane.STABLE -> LlamaCppLaunchConfig(lane = requiredLane)
        }
        val status = LlamaCppServerController.ensureRunning(
            context = context,
            modelPath = modelFile.absolutePath,
            requestedModelName = artifact.modelId,
            port = OnDeviceBackendManager.LLAMA_CPP_PORT,
            launchConfig = launchConfig,
        )
        assertTrue(status.statusMessage, status.started)
        assertTrue(status.statusMessage, status.completionVerified)
        assertTrue(status.statusMessage, status.completionLatencyMs > 0L)
        assertEquals("cpu", status.accelerator)
        assertTrue("Owned llama.cpp runtime must publish an ephemeral bearer token", status.apiKey.isNotBlank())

        val models = executeJson(
            Request.Builder()
                .url("${status.baseUrl}/models")
                .header("Authorization", "Bearer ${status.apiKey}")
                .get()
                .build(),
        )
        val servedModels = models.optJSONArray("data") ?: JSONArray()
        assertTrue("Expected a nonempty llama.cpp /v1/models response: $models", servedModels.length() > 0)

        val requestJson = LlamaCppServerController.releaseMatrixCompletionPayload(status.modelName, launchConfig.lane)
        val request = Request.Builder()
            .url("${status.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${status.apiKey}")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val completion = executeJson(request)
        val messageContent = completion
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .optString("content")
            .trim()
        assertFalse(
            "Expected real nonblank choices[0].message.content: $completion",
            messageContent.isBlank(),
        )
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue("Expected positive runtime elapsed time", elapsedMs > 0L)
        val evidenceFile = ModelMatrixEvidence.emit(
            context,
            ModelMatrixEvidence.Record(
                backend = "llama.cpp",
                instrumentationMethod =
                    "LlamaCppModelMatrixInstrumentedTest#provisionedContentAddressedGgufStartsAndAnswers",
                modelId = artifact.modelId,
                publisherRepository = artifact.repoId,
                publisherRevision = artifact.revision,
                fileName = artifact.fileName,
                devicePath = modelFile.absolutePath,
                publisherExpectedBytes = artifact.expectedBytes,
                deviceVisibleBytes = verification.actualBytes,
                expectedSha256 = artifact.sha256,
                deviceSha256 = verification.actualSha256,
                runtimeStarted = status.started,
                healthOk = servedModels.length() > 0,
                completionNonEmpty = messageContent.isNotBlank(),
                elapsedMs = elapsedMs,
                accelerator = status.accelerator,
                statusMessage = status.statusMessage,
                details = JSONObject()
                    .put("served_model_count", servedModels.length())
                    .put("completion_characters", messageContent.length)
                    .put("startup_completion_canary_verified", status.completionVerified)
                    .put("startup_completion_canary_ms", status.completionLatencyMs)
                    .put("runtime_lane", launchConfig.lane.persistedValue)
                    .put("cache_type_k", launchConfig.cacheTypeK)
                    .put("cache_type_v", launchConfig.cacheTypeV)
                    .put("flash_attention", launchConfig.flashAttention)
                    .put("artifact_summary", status.artifactSummary),
            ),
        )
        assertTrue("Expected durable GGUF evidence at ${evidenceFile.absolutePath}", evidenceFile.isFile)
    }

    @Test
    fun releaseMatrixContainsContentAddressedGgufArtifacts() {
        val gguf = VerifiedLocalModelArtifacts.releaseMatrix.filter { it.runtime == "llama.cpp" }

        assertTrue("Expected at least one content-addressed GGUF artifact", gguf.isNotEmpty())
        assertTrue(gguf.all { it.fileName.endsWith(".gguf", ignoreCase = true) })
        assertTrue(gguf.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
    }

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            assertTrue(body, response.isSuccessful)
            return JSONObject(body)
        }
    }

    private companion object {
        private const val DEFAULT_FILE_NAME = "Qwen3.5-0.8B-Q4_K_M.gguf"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
