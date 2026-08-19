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
import com.mobilefork.hermesagent.ui.chat.ChatViewModel
import com.mobilefork.hermesagent.ui.chat.NativeAgentEvent
import com.mobilefork.hermesagent.ui.chat.NativeToolCallingChatClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Release148Gemma4E2BLocalRuntimeSmokeTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        HermesRuntimeManager.stop()
        OnDeviceBackendManager.stopAll()
    }

    @Test
    fun inAppLocalRuntimeStartsAndGemmaProcessesAGuestToolCall() {
        val modelFile = findGemmaModel()
        assumeTrue(
            "Gemma 4 E2B LiteRT-LM is not provisioned (looked for $MODEL_FILE_NAME)",
            modelFile?.isFile == true,
        )
        val resolved = modelFile!!
        seedPreferredGemma(resolved)

        val runtime = HermesRuntimeManager.ensureStarted(app)
        assertTrue(runtime.error.orEmpty(), runtime.started)
        val backend = OnDeviceBackendManager.currentStatus()
        assertEquals(BackendKind.LITERT_LM, backend.backendKind)
        assertTrue(backend.statusMessage, backend.started)
        assertTrue(backend.baseUrl, backend.baseUrl.startsWith("http://127.0.0.1:"))

        assumeAlpineReady()
        val proofPath = "/tmp/hermes-148-gemma-e2b-proof"
        HermesLinuxSandboxBridge.performAction(
            context = app,
            action = "run",
            name = AlpineAgentCommandCatalog.SANDBOX_NAME,
            command = "rm -f $proofPath",
            timeoutSeconds = 60,
        )

        val prompt = "Inside the active Alpine 3.21 guest, perform this as one guest action: " +
            "printf 'HERMES_GEMMA_E2B_148_OK\\n' > $proofPath; " +
            "cat /etc/alpine-release >> $proofPath. Then report the release you observed."
        val toolNames = mutableListOf<String>()
        val toolResults = mutableListOf<String>()
        val result = NativeToolCallingChatClient(app).send(
            baseUrl = backend.baseUrl.removeSuffix("/v1"),
            modelName = backend.modelName,
            sessionId = "release-148-gemma-e2b",
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

        assertTrue("Expected a real Gemma model request: $result names=$toolNames", result.modelRequestCount > 0)
        assertTrue(
            "If Gemma emitted a tool call the app must process it: $result names=$toolNames results=$toolResults",
            result.executedToolCalls > 0,
        )
        assertFalse("Expected a non-blank post-tool reply: $result", result.content.isBlank())
        val sandboxTool = toolNames.any { name ->
            name.contains("mcp_run_in_proot") || name.contains("linux_sandbox")
        } || toolResults.any { it.contains("proot_distro_qemu") }
        assertTrue(
            "Gemma tool calls must be routed to the Alpine guest, not dropped or host-only: names=$toolNames results=$toolResults",
            sandboxTool,
        )

        val proof = HermesLinuxSandboxBridge.performAction(
            context = app,
            action = "run",
            name = AlpineAgentCommandCatalog.SANDBOX_NAME,
            command = "cat $proofPath",
            timeoutSeconds = 60,
        )
        val proofOutput = proof.optString("output")
        val guestOutput = toolResults.joinToString("\n")
        val guestEffect = proofOutput.contains("HERMES_GEMMA_E2B_148_OK") ||
            proofOutput.contains(AlpineAgentCommandCatalog.ALPINE_RELEASE_NEEDLE) ||
            guestOutput.contains(AlpineAgentCommandCatalog.ALPINE_RELEASE_NEEDLE) ||
            guestOutput.contains("HERMES_GEMMA_E2B_148_OK") ||
            result.content.contains(AlpineAgentCommandCatalog.ALPINE_RELEASE_NEEDLE)
        assertTrue(
            "Expected a guest-side Alpine effect. proof=$proof results=$toolResults reply=${result.content}",
            guestEffect,
        )
        assertEquals("proot_distro_qemu", proof.optString("sandbox_execution_mode"))
    }

    @Test
    fun chatViewModelSendStartsTheSameLocalRuntimePath() {
        val modelFile = findGemmaModel()
        assumeTrue("Gemma 4 E2B LiteRT-LM is not provisioned", modelFile?.isFile == true)
        seedPreferredGemma(modelFile!!)

        val viewModel = ChatViewModel(app)
        viewModel.startNewConversation()
        viewModel.updateInput(AlpineAgentCommandCatalog.guestPrompt("printf H148_GEMMA_UI"))
        viewModel.sendMessage()
        assertTrue(
            "ChatViewModel.sendMessage must enter the in-app send path",
            viewModel.uiState.value.isSending || viewModel.uiState.value.messages.any { it.role == "user" },
        )
        val deadline = System.currentTimeMillis() + 180_000L
        while (viewModel.uiState.value.isSending && System.currentTimeMillis() < deadline) {
            Thread.sleep(500L)
        }
        val backend = OnDeviceBackendManager.currentStatus()
        assertEquals(BackendKind.LITERT_LM, backend.backendKind)
        assertTrue(backend.statusMessage, backend.started)
        assertFalse(
            "Expected a visible assistant reply from the UI send path",
            viewModel.uiState.value.messages.none { it.role == "assistant" && it.content.isNotBlank() },
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

    private fun findGemmaModel(): File? {
        val candidates = listOf(
            File(app.filesDir, "hermes-home/downloads/models/$MODEL_FILE_NAME"),
            File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "models/$MODEL_FILE_NAME"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), MODEL_FILE_NAME),
        )
        return candidates.firstOrNull { it.isFile }
    }

    private fun seedPreferredGemma(modelFile: File) {
        val record = LocalModelDownloadRecord(
            id = "gemma-4-e2b-release-148-smoke",
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
            statusMessage = "Provisioned for release-148 Gemma 4 E2B smoke",
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
                onDeviceBackend = BackendKind.LITERT_LM.persistedValue,
            ),
        )
    }

    private companion object {
        private const val MODEL_ID = "gemma-4-E2B-it"
        private const val MODEL_REPO = "litert-community/gemma-4-E2B-it-litert-lm"
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val MODEL_SOURCE_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/gemma-4-E2B-it.litertlm"
        private const val MODEL_REVISION = "7fa1d78473894f7e736a21d920c3aa80f950c0db"
    }
}
