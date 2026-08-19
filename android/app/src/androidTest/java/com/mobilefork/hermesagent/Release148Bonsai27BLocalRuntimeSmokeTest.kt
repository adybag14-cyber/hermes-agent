package com.mobilefork.hermesagent

import android.app.Application
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.device.AlpineAgentCommandCatalog
import com.mobilefork.hermesagent.device.HermesLinuxSandboxBridge
import com.mobilefork.hermesagent.ui.chat.AgentEventType
import com.mobilefork.hermesagent.ui.chat.NativeAgentEvent
import com.mobilefork.hermesagent.ui.chat.NativeToolCallingChatClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Release148Bonsai27BLocalRuntimeSmokeTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        HermesRuntimeManager.stop()
        OnDeviceBackendManager.stopAll()
    }

    @Test
    fun inAppLocalRuntimeStartsAndBonsaiProcessesAGuestToolCall() {
        val modelFile = findBonsaiModel()
        assumeTrue(
            "Bonsai-27B-Q1_0.gguf is not provisioned on this emulator",
            modelFile?.isFile == true,
        )
        val resolved = modelFile!!
        seedPreferredBonsai(resolved)

        val runtime = HermesRuntimeManager.ensureStarted(app)
        val backend = OnDeviceBackendManager.currentStatus()
        assertTrue(
            "llama.cpp must boot Bonsai-27B-Q1_0.gguf (Q1_0 load failure is an app gap): runtime=${runtime.error} backend=${backend.statusMessage}",
            runtime.started && backend.started && backend.backendKind == BackendKind.LLAMA_CPP,
        )
        assertTrue(backend.baseUrl, backend.baseUrl.startsWith("http://127.0.0.1:"))
        assertTrue(backend.sourceModelPath, backend.sourceModelPath.endsWith(MODEL_FILE_NAME))

        assumeAlpineReady()
        val proofPath = "/tmp/hermes-148-bonsai-q10-proof"
        HermesLinuxSandboxBridge.performAction(
            context = app,
            action = "run",
            name = AlpineAgentCommandCatalog.SANDBOX_NAME,
            command = "rm -f $proofPath",
            timeoutSeconds = 60,
        )

        val marker = "HERMES_BONSAI_Q10_148_OK"
        val prompt = "Inside the active Alpine 3.21 guest, perform this as one guest action: " +
            "printf '$marker\\n' | tee $proofPath; " +
            "cat /etc/alpine-release | tee -a $proofPath"
        val toolNames = mutableListOf<String>()
        val toolResults = mutableListOf<String>()
        val result = NativeToolCallingChatClient(app).send(
            baseUrl = backend.baseUrl.removeSuffix("/v1"),
            modelName = backend.modelName,
            sessionId = "release-148-bonsai-q10",
            userText = prompt,
            onEvent = { event: NativeAgentEvent ->
                if (event.type == AgentEventType.ToolCall) {
                    toolNames += event.title
                }
                if (event.type == AgentEventType.ProcessLog || event.type == AgentEventType.ToolResult) {
                    toolResults += event.content
                }
            },
        )

        assertTrue("Expected a real Bonsai model request: $result names=$toolNames", result.modelRequestCount > 0)
        assertTrue(
            "If Bonsai emitted a tool call the app must process it: $result names=$toolNames results=$toolResults",
            result.executedToolCalls > 0,
        )
        assertFalse("Expected a non-blank post-tool reply: $result", result.content.isBlank())
        val sandboxTool = toolNames.any { name ->
            name.contains("mcp_run_in_proot") || name.contains("linux_sandbox")
        }
        assertTrue(
            "Bonsai tool calls must be routed to the Alpine guest, not dropped or host-only: names=$toolNames results=$toolResults",
            sandboxTool,
        )

        val proof = HermesLinuxSandboxBridge.performAction(
            context = app,
            action = "run",
            name = AlpineAgentCommandCatalog.SANDBOX_NAME,
            command = "cat $proofPath",
            timeoutSeconds = 60,
        )
        Release148GuestProof.assertMarkerFile(
            label = "bonsai-q10",
            proofPath = proofPath,
            proof = proof,
            marker = marker,
            toolNames = toolNames,
            rawResults = toolResults,
        )
    }

    private fun assumeAlpineReady() {
        val status = HermesLinuxSandboxBridge.performAction(app, action = "status")
        val installed = status.optJSONArray("installed_sandboxes")
        val alpine = (0 until (installed?.length() ?: 0))
            .mapNotNull { installed?.optJSONObject(it) }
            .firstOrNull { it.optString("name") == AlpineAgentCommandCatalog.SANDBOX_NAME }
        assumeTrue("A runnable hermes-alpine sandbox is required: $status", alpine != null)
        val start = HermesLinuxSandboxBridge.performAction(
            app,
            action = "start",
            name = AlpineAgentCommandCatalog.SANDBOX_NAME,
        )
        assertEquals(start.toString(2), 0, start.optInt("exit_code", -1))
    }

    private fun findBonsaiModel(): File? {
        val candidates = listOf(
            File(app.filesDir, "hermes-home/downloads/models/$MODEL_FILE_NAME"),
            File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "models/$MODEL_FILE_NAME"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), MODEL_FILE_NAME),
        )
        return candidates.firstOrNull { it.isFile }
    }

    private fun seedPreferredBonsai(modelFile: File) {
        val record = LocalModelDownloadRecord(
            id = "bonsai-27b-q1-0-release-148-smoke",
            title = MODEL_ID,
            sourceUrl = MODEL_SOURCE_URL,
            repoOrUrl = MODEL_REPO,
            filePath = MODEL_FILE_NAME,
            revision = MODEL_REVISION,
            runtimeFlavor = "GGUF",
            destinationFileName = MODEL_FILE_NAME,
            destinationPath = modelFile.absolutePath,
            downloadManagerId = -1L,
            totalBytes = modelFile.length(),
            downloadedBytes = modelFile.length(),
            status = "completed",
            statusMessage = "Provisioned for release-148 Bonsai-27B Q1_0 smoke",
            supportsResume = false,
        )
        LocalModelDownloadStore(app).apply {
            upsertDownload(record)
            setPreferredDownloadId(record.id)
        }
        AppSettingsStore(app).save(
            AppSettings(
                provider = "custom",
                baseUrl = "",
                model = MODEL_ID,
                onDeviceBackend = BackendKind.LLAMA_CPP.persistedValue,
            ),
        )
    }

    private companion object {
        private const val MODEL_ID = "Bonsai-27B-Q1_0"
        private const val MODEL_REPO = "prism-ml/Bonsai-27B-gguf"
        private const val MODEL_FILE_NAME = "Bonsai-27B-Q1_0.gguf"
        private const val MODEL_REVISION = "main"
        private const val MODEL_SOURCE_URL =
            "https://huggingface.co/prism-ml/Bonsai-27B-gguf/resolve/main/Bonsai-27B-Q1_0.gguf"
    }
}
