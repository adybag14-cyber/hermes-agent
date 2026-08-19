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
        assumeTrue(
            "llama.cpp could not boot Bonsai-27B-Q1_0.gguf: runtime=${runtime.error} backend=${backend.statusMessage}",
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

        val result = NativeToolCallingChatClient(app).send(
            baseUrl = backend.baseUrl.removeSuffix("/v1"),
            modelName = backend.modelName,
            sessionId = "release-148-bonsai-q10",
            userText = AlpineAgentCommandCatalog.guestPrompt(
                "printf 'HERMES_BONSAI_Q10_148_OK\\n' > $proofPath; cat /etc/alpine-release >> $proofPath",
            ),
        )

        assertTrue("Expected a real Bonsai model request: $result", result.modelRequestCount > 0)
        assertTrue(
            "If Bonsai emitted a tool call the app must process it: $result",
            result.executedToolCalls > 0,
        )
        assertFalse("Expected a non-blank post-tool reply", result.content.isBlank())

        val proof = HermesLinuxSandboxBridge.performAction(
            context = app,
            action = "run",
            name = AlpineAgentCommandCatalog.SANDBOX_NAME,
            command = "cat $proofPath",
            timeoutSeconds = 60,
        )
        assertEquals(proof.toString(2), 0, proof.optInt("exit_code", -1))
        assertTrue(proof.toString(2), proof.optString("output").contains("HERMES_BONSAI_Q10_148_OK"))
        assertTrue(proof.toString(2), proof.optString("output").contains(AlpineAgentCommandCatalog.ALPINE_RELEASE_NEEDLE))
        assertEquals("proot_distro_qemu", proof.optString("sandbox_execution_mode"))
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
