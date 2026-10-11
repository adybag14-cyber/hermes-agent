package com.mobilefork.hermesagent

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilefork.hermesagent.backend.LlamaCppServerController
import com.mobilefork.hermesagent.device.LocalModelRuntimeDiagnostics
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Real API37 probe. No fake RAM readings, OS-limit disabling, or remote provider. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37)
class Android17MemoryInvestigationTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun exits(): JSONArray {
        val manager = context.getSystemService(ActivityManager::class.java)
        return JSONArray().apply {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, 8).forEach { info ->
                put(JSONObject().put("reason", info.reason).put("status", info.status)
                    .put("description", info.description).put("timestamp", info.timestamp)
                    .put("pss_kib", info.pss).put("rss_kib", info.rss))
            }
        }
    }

    private fun save(name: String, result: JSONObject) {
        val folder = File(context.getExternalFilesDir(null), "android17-memory-investigation")
        check(folder.isDirectory || folder.mkdirs())
        File(folder, name).writeText(result.toString(2))
    }

    @Test fun currentMemoryIsSystemTelemetryNotJavaHeapClass() {
        val result = JSONObject(LocalModelRuntimeDiagnostics.exportSupportSnapshot(context))
            .put("device_release", Build.VERSION.RELEASE)
            .put("device_fingerprint", Build.FINGERPRINT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("previous_exits", exits())
        save("memory-before-model.json", result)
        assertTrue(Build.VERSION.SDK_INT >= 37)
        assertEquals("ActivityManager.MemoryInfo", result.getString("memory_source"))
        assertTrue(result.getJSONObject("current_memory").getLong("total_bytes") > 0)
    }

    @Test fun realGgufAdmissionCompletionAndStopOnAndroid17() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("agentModelPath").orEmpty()
        assumeTrue("Explicit hash-pinned model fixture required for this investigation", path.isNotEmpty())
        val expected = args.getString("agentModelSha256").orEmpty()
        require(Regex("[a-f0-9]{64}").matches(expected))
        val bypass = args.getString("agentRamBypass") == "true"
        val occupyPreferredPort = args.getString("agentOccupyPreferredPort") == "true"
        val model = File(path)
        require(model.isFile && model.length() > 0)
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        assertEquals(expected, digest.digest().joinToString("") { "%02x".format(it) })
        val result = JSONObject().put("schema", "agent-api37-model-probe-v1")
            .put("android_sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
            .put("model_name", model.name).put("model_bytes", model.length()).put("sha256", expected)
            .put("ram_bypass_requested", bypass).put("before", JSONObject(LocalModelRuntimeDiagnostics.exportSupportSnapshot(context)))
            .put("previous_exits", exits()).put("inference_passed", false)
        val name = if (occupyPreferredPort) "model-port-conflict.json" else if (bypass) "model-bypass.json" else "model-normal.json"
        assertNull("Existing owned model could not stop", LlamaCppServerController.stop())
        val occupied = if (occupyPreferredPort) ServerSocket(18791, 1, InetAddress.getByName("127.0.0.1")) else null
        try {
            val status = LlamaCppServerController.ensureRunning(
                context = context, modelPath = model.absolutePath,
                requestedModelName = model.nameWithoutExtension, port = 18791,
                dangerouslySkipRamChecks = bypass,
            )
            if (occupyPreferredPort) {
                assertTrue(status.statusMessage, status.started)
                assertNotEquals(18791, URI(status.baseUrl).port)
                val reused = LlamaCppServerController.ensureRunning(
                    context = context, modelPath = model.absolutePath,
                    requestedModelName = model.nameWithoutExtension, port = 18791,
                    dangerouslySkipRamChecks = bypass,
                )
                assertTrue(reused.statusMessage, reused.started)
                assertEquals(status.baseUrl, reused.baseUrl)
                assertEquals("The existing owned process must be reused", status.apiKey, reused.apiKey)
                result.put("occupied_preferred_port", 18791).put("selected_port", URI(status.baseUrl).port)
            }
            result.put("started", status.started).put("status", status.statusMessage)
                .put("completion_verified", status.completionVerified)
                .put("after_start", JSONObject(LocalModelRuntimeDiagnostics.exportSupportSnapshot(context)))
            save(name, result)
            assertTrue(status.statusMessage, status.started)
            assertTrue(status.statusMessage, status.completionVerified)
            // Reuse the production release probe's deterministic, non-thinking request.
            val body = LlamaCppServerController.releaseMatrixCompletionPayload(status.modelName)
                .put("messages", JSONArray().put(JSONObject().put("role", "user")
                    .put("content", "What is the capital of France? Answer with just the city name.")))
            // Match the production canary's socket budget. A call timeout alone leaves
            // OkHttp's unrelated ten-second default read timeout active.
            val client = OkHttpClient.Builder().readTimeout(150, TimeUnit.SECONDS)
                .callTimeout(180, TimeUnit.SECONDS).build()
            val request = Request.Builder().url(status.baseUrl + "/chat/completions")
                .header("Authorization", "Bearer ${status.apiKey}")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                assertTrue("Completion returned HTTP ${response.code}", response.isSuccessful)
                val answer = JSONObject(responseText).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content")
                result.put("answer", answer)
                assertTrue("Expected a factual answer, got: $answer", answer.contains("Paris", ignoreCase = true))
            }
            result.put("inference_passed", true)
        } finally {
            val stopError = LlamaCppServerController.stop()
            if (occupied != null) {
                result.put("unrelated_listener_preserved", !occupied.isClosed)
                occupied.close()
            }
            result.put("stop_succeeded", stopError == null)
                .put("after_stop", JSONObject(LocalModelRuntimeDiagnostics.exportSupportSnapshot(context)))
                .put("latest_exits", exits())
            save(name, result)
            assertNull("Owned model stop failed", stopError)
            if (occupied != null) {
                assertTrue("The unrelated listener must survive model shutdown", result.getBoolean("unrelated_listener_preserved"))
            }
        }
    }
}
