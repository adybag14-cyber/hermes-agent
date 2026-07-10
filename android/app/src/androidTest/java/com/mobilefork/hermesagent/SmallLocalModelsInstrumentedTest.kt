package com.mobilefork.hermesagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilefork.hermesagent.backend.BackendKind
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Smoke-loads every small local model that is provisioned under
 * files/hermes-home/downloads/models/. Missing models are skipped so the suite
 * stays green when only a subset is available on the device.
 */
@RunWith(AndroidJUnit4::class)
class SmallLocalModelsInstrumentedTest {
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
    fun gemma4E2bLiteRtAnswersWhenProvisioned() {
        runLiteRtModel(
            modelId = "gemma-4-E2B-it",
            fileName = "gemma-4-E2B-it.litertlm",
            expectedBytes = 2_583_085_056L,
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
        )
    }

    @Test
    fun gemma3_1bLiteRtAnswersWhenProvisioned() {
        runLiteRtModel(
            modelId = "gemma3-1b-it-int4",
            fileName = "gemma3-1b-it-int4.litertlm",
            expectedBytes = null,
            repo = "litert-community/Gemma3-1B-IT",
        )
    }

    @Test
    fun miniCpm1bLiteRtAnswersWhenProvisioned() {
        runLiteRtModel(
            modelId = "MiniCPM5-1B",
            fileName = "MiniCPM5-1B.litertlm",
            expectedBytes = null,
            repo = "Tdamre/MiniCPM5-1B-litert-lm",
        )
    }

    @Test
    fun qwen25_1_5bLiteRtAnswersWhenProvisioned() {
        runLiteRtModel(
            modelId = "Qwen2.5-1.5B-Instruct",
            fileName = "Qwen2.5-1.5B-Instruct.litertlm",
            expectedBytes = null,
            repo = "litert-community/Qwen2.5-1.5B-Instruct",
        )
    }

    @Test
    fun qwen35_0_8bGgufAnswersWhenProvisioned() {
        val candidates = listOf(
            "Qwen3.5-0.8B-Q4_K_M.gguf",
            "Qwen_Qwen3.5-0.8B-Q4_K_M.gguf",
        )
        val modelFile = candidates
            .map { File(context.filesDir, "hermes-home/downloads/models/$it") }
            .firstOrNull { it.isFile }
        assumeTrue("Qwen 0.8B GGUF not provisioned", modelFile != null)
        val file = modelFile!!
        seedPreferred(
            modelId = "Qwen3.5-0.8B-Q4_K_M",
            fileName = file.name,
            modelFile = file,
            backend = BackendKind.LLAMA_CPP,
            repo = "unsloth/Qwen3.5-0.8B-GGUF",
        )
        val status = OnDeviceBackendManager.ensureConfigured(
            context = context,
            backendValue = BackendKind.LLAMA_CPP.persistedValue,
        )
        assertTrue(status.statusMessage, status.started)
        assertEquals(BackendKind.LLAMA_CPP, status.backendKind)
        val content = chatOnce(status.baseUrl, "Qwen3.5-0.8B-Q4_K_M")
        assertFalse(content, content.isBlank())
    }

    private fun runLiteRtModel(
        modelId: String,
        fileName: String,
        expectedBytes: Long?,
        repo: String,
    ) {
        val modelFile = File(context.filesDir, "hermes-home/downloads/models/$fileName")
        assumeTrue("$fileName not provisioned at ${modelFile.absolutePath}", modelFile.isFile)
        if (expectedBytes != null) {
            assertEquals("$fileName size", expectedBytes, modelFile.length())
        } else {
            assertTrue("$fileName too small", modelFile.length() > 50_000_000L)
        }
        seedPreferred(
            modelId = modelId,
            fileName = fileName,
            modelFile = modelFile,
            backend = BackendKind.LITERT_LM,
            repo = repo,
        )
        val status = OnDeviceBackendManager.ensureConfigured(
            context = context,
            backendValue = BackendKind.LITERT_LM.persistedValue,
        )
        assertTrue(status.statusMessage, status.started)
        assertEquals(BackendKind.LITERT_LM, status.backendKind)
        val content = chatOnce(status.baseUrl, modelId)
        assertFalse(content, content.isBlank())
    }

    private fun seedPreferred(
        modelId: String,
        fileName: String,
        modelFile: File,
        backend: BackendKind,
        repo: String,
    ) {
        val record = LocalModelDownloadRecord(
            id = "small-local-$modelId",
            title = modelId,
            sourceUrl = "https://huggingface.co/$repo/resolve/main/$fileName",
            repoOrUrl = repo,
            filePath = fileName,
            revision = "main",
            runtimeFlavor = if (backend == BackendKind.LITERT_LM) "LiteRT-LM" else "llama.cpp",
            destinationFileName = fileName,
            destinationPath = modelFile.absolutePath,
            downloadManagerId = -1L,
            totalBytes = modelFile.length(),
            downloadedBytes = modelFile.length(),
            status = "completed",
            statusMessage = "Provisioned for multi-model smoke",
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
                model = modelId,
                onDeviceBackend = backend.persistedValue,
            ),
        )
    }

    private fun chatOnce(baseUrl: String, modelId: String): String {
        val body = JSONObject()
            .put("model", modelId)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "Say the single word: ok"),
                ),
            )
            .put("temperature", 0.2)
            .put("max_tokens", 64)
            .put("stream", false)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val response = client.newCall(
            Request.Builder()
                .url("$baseUrl/chat/completions")
                .post(body)
                .build(),
        ).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            assertTrue("HTTP ${it.code}: $text", it.isSuccessful)
            val message = JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
            // Some small models/backends return only reasoning/tool fields; accept any non-blank text.
            val content = sequenceOf(
                message.optString("content"),
                message.optString("reasoning"),
                message.optString("reasoning_content"),
                text,
            ).map { part -> part.trim() }.firstOrNull { part -> part.isNotEmpty() }.orEmpty()
            assertTrue("empty chat response: $text", content.isNotBlank())
            return content
        }
    }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
