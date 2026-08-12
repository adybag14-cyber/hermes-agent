package com.mobilefork.hermesagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilefork.hermesagent.backend.LiteRtLmOpenAiProxy
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LiteRtLmModelMatrixInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES)
        .build()

    @After
    fun tearDown() {
        OnDeviceBackendManager.stopAll()
    }

    @Test
    fun provisionedLiteRtLmModelLoadsAndAnswersLocally() {
        val args = InstrumentationRegistry.getArguments()
        val modelId = args.getString("model_id", DEFAULT_MODEL_ID)
        val modelFileName = args.getString("model_file_name", DEFAULT_MODEL_FILE_NAME)
        val matrixArtifact = VerifiedLocalModelArtifacts.findByFileName(modelFileName)
        val expectedBytes = args.getString(
            "model_bytes",
            (matrixArtifact?.expectedBytes ?: DEFAULT_MODEL_BYTES).toString(),
        ).toLong()
        val expectedSha256 = args.getString("model_sha256", matrixArtifact?.sha256.orEmpty())
        val requireModel = args.getString("require_model", "false").toBoolean()
        val modelFile = provisionedModelFile(args.getString("model_path", ""), modelFileName)

        if (!modelFile.isFile && requireModel) {
            fail("Required LiteRT-LM model is not provisioned at ${modelFile.absolutePath}")
        }
        assumeTrue("LiteRT-LM model is not provisioned at ${modelFile.absolutePath}", modelFile.isFile)
        if (expectedBytes > 0L) {
            assertEquals("$modelId LiteRT-LM model size", expectedBytes, modelFile.length())
        }
        val actualSha256 = if (expectedSha256.isNotBlank()) {
            VerifiedLocalModelArtifacts.sha256(modelFile)
        } else {
            ""
        }
        if (actualSha256.isNotBlank()) {
            assertEquals(
                "$modelId LiteRT-LM SHA-256",
                expectedSha256.lowercase(),
                actualSha256,
            )
        }

        val startedAt = System.nanoTime()
        val status = LiteRtLmOpenAiProxy.ensureRunning(
            context = context,
            modelPath = modelFile.absolutePath,
            requestedModelName = modelId,
            port = OnDeviceBackendManager.LITERT_LM_PORT,
        )
        assertTrue(status.statusMessage, status.started)
        assertTrue("LiteRT-LM must not report ready before a nonblank startup completion", status.completionVerified)
        assertTrue("Expected measured startup completion latency", status.completionLatencyMs > 0L)

        val health = executeJson(
            Request.Builder()
                .url(status.baseUrl.removeSuffix("/v1") + "/health")
                .get()
                .build()
        )
        assertEquals(health.toString(), "ok", health.optString("status"))
        assertEquals(health.toString(), "litert-lm", health.optString("backend"))
        assertTrue(health.toString(), health.optString("accelerator") in setOf("cpu", "gpu"))
        assertTrue(health.toString(), (health.optJSONArray("accelerator_attempts")?.length() ?: 0) > 0)
        assertTrue(health.toString(), health.optBoolean("completion_verified", false))
        assertTrue(health.toString(), health.optLong("completion_latency_ms", 0L) > 0L)

        val completion = executeJson(
            Request.Builder()
                .url("${status.baseUrl}/chat/completions")
                .post(completionRequestBody(modelId))
                .build()
        )
        val content = completion
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .optString("content")
        assertFalse(completion.toString(), content.isBlank())
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue("Expected positive runtime elapsed time", elapsedMs > 0L)
        val evidenceFile = ModelMatrixEvidence.emit(
            context,
            ModelMatrixEvidence.Record(
                backend = "litert-lm",
                instrumentationMethod =
                    "LiteRtLmModelMatrixInstrumentedTest#provisionedLiteRtLmModelLoadsAndAnswersLocally",
                modelId = modelId,
                publisherRepository = args.getString("model_repo", matrixArtifact?.repoId.orEmpty()),
                publisherRevision = args.getString("model_revision", matrixArtifact?.revision.orEmpty()),
                fileName = modelFileName,
                devicePath = modelFile.absolutePath,
                publisherExpectedBytes = expectedBytes,
                deviceVisibleBytes = modelFile.length(),
                expectedSha256 = expectedSha256,
                deviceSha256 = actualSha256,
                runtimeStarted = status.started,
                healthOk = health.optString("status") == "ok",
                completionNonEmpty = content.isNotBlank(),
                elapsedMs = elapsedMs,
                accelerator = health.optString("accelerator"),
                statusMessage = status.statusMessage,
                details = JSONObject()
                    .put("health_backend", health.optString("backend"))
                    .put("accelerator_attempts", health.optJSONArray("accelerator_attempts") ?: JSONArray())
                    .put("completion_characters", content.length)
                    .put("artifact_summary", status.artifactSummary),
            ),
        )
        assertTrue("Expected durable LiteRT-LM evidence at ${evidenceFile.absolutePath}", evidenceFile.isFile)
    }

    @Test
    fun releaseMatrixContainsContentAddressedLiteRtArtifacts() {
        val liteRt = VerifiedLocalModelArtifacts.releaseMatrix.filter { it.runtime == "litert-lm" }

        assertTrue("Expected at least one content-addressed LiteRT-LM artifact", liteRt.isNotEmpty())
        assertTrue(liteRt.all { it.expectedBytes > 1_000_000_000L })
        assertTrue(liteRt.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(liteRt.all { it.remoteManifestMatches })
    }

    @Test
    fun provisionedVisionLiteRtLmModelDescribesImageLocally() {
        val args = InstrumentationRegistry.getArguments()
        val modelId = args.getString("vision_model_id", DEFAULT_VISION_MODEL_ID)
        val modelFileName = args.getString("vision_model_file_name", DEFAULT_VISION_MODEL_FILE_NAME)
        val modelFile = File(context.filesDir, "hermes-home/downloads/models/$modelFileName")

        assumeTrue("LiteRT-LM vision model is not provisioned at ${modelFile.absolutePath}", modelFile.isFile)

        val status = LiteRtLmOpenAiProxy.ensureRunning(
            context = context,
            modelPath = modelFile.absolutePath,
            requestedModelName = modelId,
            port = OnDeviceBackendManager.LITERT_LM_PORT,
            inferenceConfig = LiteRtLmOpenAiProxy.InferenceConfig(supportImage = true),
        )
        assertTrue(status.statusMessage, status.started)

        val health = executeJson(
            Request.Builder()
                .url(status.baseUrl.removeSuffix("/v1") + "/health")
                .get()
                .build()
        )
        assertEquals(health.toString(), "gpu", health.optString("vision_accelerator"))

        val completion = executeJson(
            Request.Builder()
                .url("${status.baseUrl}/chat/completions")
                .post(visionCompletionRequestBody(modelId))
                .build()
        )
        val content = completion
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .optString("content")
        assertFalse("Expected nonblank image description from $modelId", content.isBlank())
    }

    @Test
    fun provisionedTextOnlyLiteRtLmModelRejectsImageRequestsClearly() {
        val args = InstrumentationRegistry.getArguments()
        val modelId = args.getString("model_id", DEFAULT_MODEL_ID)
        val modelFileName = args.getString("model_file_name", DEFAULT_MODEL_FILE_NAME)
        val modelFile = File(context.filesDir, "hermes-home/downloads/models/$modelFileName")

        assumeTrue("LiteRT-LM model is not provisioned at ${modelFile.absolutePath}", modelFile.isFile)

        val status = LiteRtLmOpenAiProxy.ensureRunning(
            context = context,
            modelPath = modelFile.absolutePath,
            requestedModelName = modelId,
            port = OnDeviceBackendManager.LITERT_LM_PORT,
        )
        assertTrue(status.statusMessage, status.started)

        client.newCall(
            Request.Builder()
                .url("${status.baseUrl}/chat/completions")
                .post(visionCompletionRequestBody(modelId))
                .build()
        ).execute().use { response ->
            val body = response.body?.string().orEmpty()
            assertEquals(body, 400, response.code)
            assertTrue(body, body.contains("image input requires a LiteRT-LM model started with image support"))
        }
    }

    private fun completionRequestBody(modelId: String) = JSONObject()
        .put("model", modelId)
        .put(
            "messages",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", "Reply with exactly one short word: ok")
            )
        )
        .put("stream", false)
        .toString()
        .toRequestBody(JSON_MEDIA_TYPE)

    private fun visionCompletionRequestBody(modelId: String) = JSONObject()
        .put("model", modelId)
        .put(
            "messages",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "content",
                        JSONArray()
                            .put(JSONObject().put("type", "text").put("text", "Describe the image in one short sentence."))
                            .put(
                                JSONObject()
                                    .put("type", "image_url")
                                    .put("image_url", JSONObject().put("url", bluePixelDataUrl())),
                            ),
                    )
            )
        )
        .put("stream", false)
        .toString()
        .toRequestBody(JSON_MEDIA_TYPE)

    private fun executeJson(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            assertTrue(body, response.isSuccessful)
            return JSONObject(body)
        }
    }

    private fun provisionedModelFile(explicitPath: String, fileName: String): File {
        return explicitPath.trim().takeIf { it.isNotEmpty() }?.let(::File)
            ?: File(context.filesDir, "hermes-home/downloads/models/$fileName")
    }

    private fun bluePixelDataUrl(): String {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        return try {
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        private const val DEFAULT_MODEL_ID = "gemma-4-E2B-it"
        private const val DEFAULT_MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val DEFAULT_VISION_MODEL_ID = "gemma-3n-E2B-it-int4"
        private const val DEFAULT_VISION_MODEL_FILE_NAME = "gemma-3n-E2B-it-int4.litertlm"
        private const val DEFAULT_MODEL_BYTES = 2_583_085_056L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
