package com.mobilefork.hermesagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.LiteRtLmOpenAiProxy
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class Gemma3LocalInferenceInstrumentedTest {
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
    fun provisionedGemma3LiteRtLmModelLoadsAndAnswersLocally() {
        val modelFile = File(context.filesDir, MODEL_RELATIVE_PATH)
        assumeTrue("Gemma3 LiteRT-LM model is not provisioned at ${modelFile.absolutePath}", modelFile.isFile)

        seedPreferredGemma3Model(modelFile)

        val status = LiteRtLmOpenAiProxy.ensureRunning(
            context = context,
            modelPath = modelFile.absolutePath,
            requestedModelName = MODEL_ID,
            port = OnDeviceBackendManager.LITERT_LM_PORT,
        )
        assertTrue(status.statusMessage, status.started)

        val completion = executeJson(
            Request.Builder()
                .url("${status.baseUrl}/chat/completions")
                .post(completionRequestBody())
                .build(),
        )
        val content = completion
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .optString("content")
        assertFalse(completion.toString(), content.isBlank())
    }

    private fun seedPreferredGemma3Model(modelFile: File) {
        val record = LocalModelDownloadRecord(
            id = "gemma3-1b-litertlm-local-smoke",
            title = MODEL_ID,
            sourceUrl = MODEL_SOURCE_URL,
            repoOrUrl = MODEL_REPO,
            filePath = MODEL_FILE_NAME,
            revision = MODEL_REVISION,
            runtimeFlavor = "LiteRT-LM",
            destinationFileName = MODEL_FILE_NAME,
            destinationPath = modelFile.absolutePath,
            downloadManagerId = -1L,
            totalBytes = modelFile.length(),
            downloadedBytes = modelFile.length(),
            status = "completed",
            statusMessage = "Provisioned for local instrumentation",
            supportsResume = false,
        )
        LocalModelDownloadStore(context).apply {
            upsertDownload(record)
            setPreferredDownloadId(record.id)
        }
        AppSettingsStore(context).save(
            AppSettings(
                provider = "custom",
                baseUrl = "",
                model = MODEL_ID,
                onDeviceBackend = BackendKind.LITERT_LM.persistedValue,
            ),
        )
    }

    private fun completionRequestBody() = JSONObject()
        .put("model", MODEL_ID)
        .put(
            "messages",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", "Reply with exactly one short word: ok"),
            ),
        )
        .put("temperature", 0.0)
        .put("max_tokens", 64)
        .put("timeout_ms", 300_000L)
        .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
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

    private companion object {
        private const val MODEL_ID = "gemma3-1b-it-int4"
        private const val MODEL_REPO = "litert-community/Gemma3-1B-IT"
        private const val MODEL_FILE_NAME = "gemma3-1b-it-int4.litertlm"
        private const val MODEL_RELATIVE_PATH = "hermes-home/downloads/models/$MODEL_FILE_NAME"
        private const val MODEL_SOURCE_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm"
        private const val MODEL_REVISION = "main"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}