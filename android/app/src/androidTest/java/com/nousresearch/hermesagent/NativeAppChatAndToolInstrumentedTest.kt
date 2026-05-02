package com.nousresearch.hermesagent

import android.app.Application
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nousresearch.hermesagent.backend.BackendKind
import com.nousresearch.hermesagent.backend.HermesRuntimeManager
import com.nousresearch.hermesagent.backend.OnDeviceBackendManager
import com.nousresearch.hermesagent.data.AppSettings
import com.nousresearch.hermesagent.data.AppSettingsStore
import com.nousresearch.hermesagent.data.LocalModelDownloadRecord
import com.nousresearch.hermesagent.data.LocalModelDownloadStore
import com.nousresearch.hermesagent.device.HermesLinuxSubsystemBridge
import com.nousresearch.hermesagent.device.NativeAndroidShellTool
import com.nousresearch.hermesagent.ui.chat.ChatViewModel
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

@RunWith(AndroidJUnit4::class)
class NativeAppChatAndToolInstrumentedTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        HermesRuntimeManager.stop()
        OnDeviceBackendManager.stopAll()
    }

    @Test
    fun nativeAppChatUsesGemma4AndEmbeddedToolsCanWriteWorkspaceFiles() {
        val modelFile = File(app.filesDir, MODEL_RELATIVE_PATH)
        assumeTrue("Gemma 4 LiteRT-LM model is not provisioned at ${modelFile.absolutePath}", modelFile.isFile)
        assertEquals("Gemma 4 LiteRT-LM model size", MODEL_BYTES, modelFile.length())
        seedPreferredGemma4Model(modelFile)

        val runtime = HermesRuntimeManager.ensureStarted(app)
        assertTrue(runtime.error.orEmpty(), runtime.started)
        assertTrue("baseUrl=${runtime.baseUrl}", runtime.baseUrl.orEmpty().startsWith("http://127.0.0.1:"))
        assertTrue("modelName=${runtime.modelName}", runtime.modelName.orEmpty().isNotBlank())
        val backendStatus = OnDeviceBackendManager.currentStatus()
        assertEquals(BackendKind.LITERT_LM, backendStatus.backendKind)
        assertEquals(MODEL_ID, backendStatus.modelName)
        assertEquals(modelFile.absolutePath, backendStatus.sourceModelPath)

        val viewModel = ChatViewModel(app)
        viewModel.startNewConversation()
        viewModel.updateInput("Reply with one short word confirming local Android Hermes chat works.")
        viewModel.sendMessage()

        val reply = waitForAssistantReply(viewModel)
        assertFalse("Expected a nonblank assistant reply from native app chat", reply.isBlank())

        val linuxState = HermesLinuxSubsystemBridge.ensureInstalled(app)
        val workspace = File(linuxState.getString("home_path"))

        val modelToolFile = File(workspace, "hermes-model-tool-smoke.txt")
        modelToolFile.delete()
        viewModel.startNewConversation()
        viewModel.updateInput(
            "Use terminal_tool to run exactly this command: " +
                "printf model-tool-ok > \"\$HOME/hermes-model-tool-smoke.txt\" && " +
                "cat \"\$HOME/hermes-model-tool-smoke.txt\". " +
                "After terminal_tool returns, reply with the command output.",
        )
        viewModel.sendMessage()
        val toolReply = waitForAssistantReply(viewModel)
        assertFalse("Expected a nonblank assistant reply after native app tool calling", toolReply.isBlank())
        assertTrue("Expected native chat tool call to create ${modelToolFile.absolutePath}", modelToolFile.isFile)
        assertEquals("model-tool-ok", modelToolFile.readText())

        val terminalResult = NativeAndroidShellTool.run(
            context = app,
            command = "printf app-tool-ok > \"\$HOME/hermes-app-tool-smoke.txt\" && cat \"\$HOME/hermes-app-tool-smoke.txt\" && printf '\\n' && pwd",
            timeoutSeconds = 20,
        )
        assertEquals(terminalResult.toString(), 0, terminalResult.optInt("exit_code", -1))
        assertTrue(terminalResult.toString(), terminalResult.optString("output").contains("app-tool-ok"))
        assertEquals("app-tool-ok", File(workspace, "hermes-app-tool-smoke.txt").readText())
    }

    private fun waitForAssistantReply(viewModel: ChatViewModel): String {
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(15)
        var latestReply = ""
        var latestError = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = viewModel.uiState.value
            latestReply = state.messages.lastOrNull { it.role == "assistant" }?.content.orEmpty()
            latestError = state.error
            if (!state.isSending && latestReply.isNotBlank()) {
                return latestReply
            }
            if (!state.isSending && latestError.isNotBlank()) {
                break
            }
            Thread.sleep(1_000)
        }
        assertTrue("Chat did not complete. Last reply='$latestReply' error='$latestError'", latestReply.isNotBlank())
        return latestReply
    }

    private fun seedPreferredGemma4Model(modelFile: File) {
        val record = LocalModelDownloadRecord(
            id = "gemma-4-e2b-litertlm-native-app-smoke",
            title = MODEL_ID,
            sourceUrl = MODEL_SOURCE_URL,
            repoOrUrl = MODEL_REPO,
            filePath = MODEL_FILE_NAME,
            revision = MODEL_REVISION,
            runtimeFlavor = "LiteRT-LM",
            destinationFileName = MODEL_FILE_NAME,
            destinationPath = modelFile.absolutePath,
            downloadManagerId = -1L,
            totalBytes = MODEL_BYTES,
            downloadedBytes = MODEL_BYTES,
            status = "completed",
            statusMessage = "Provisioned for native app instrumentation",
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
            )
        )
    }

    private companion object {
        private const val MODEL_ID = "gemma-4-E2B-it"
        private const val MODEL_REPO = "litert-community/gemma-4-E2B-it-litert-lm"
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val MODEL_RELATIVE_PATH = "hermes-home/downloads/models/$MODEL_FILE_NAME"
        private const val MODEL_SOURCE_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        private const val MODEL_REVISION = "84b6978eff6e4eea02825bc2ee4ea48579f13109"
        private const val MODEL_BYTES = 2_583_085_056L
    }
}
