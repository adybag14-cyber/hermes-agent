package com.mobilefork.hermesagent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.device.HermesLinuxSandboxBridge
import com.mobilefork.hermesagent.models.HermesModelDownloadManager
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
class Gemma4AlpineToolInstrumentedTest {
    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        HermesRuntimeManager.stop()
        OnDeviceBackendManager.stopAll()
    }

    @Test
    fun gemmaModelRoundInvokesToolAndCreatesProofInsideAlpine() {
        val modelFile = File(app.filesDir, MODEL_RELATIVE_PATH)
        assumeTrue("Gemma 4 model is not provisioned at ${modelFile.absolutePath}", modelFile.isFile)
        assertEquals(MODEL_BYTES, modelFile.length())

        val sandboxStatus = HermesLinuxSandboxBridge.performAction(app, action = "status")
        val alpine = sandboxStatus.optJSONArray("installed_sandboxes")
            ?.let { sandboxes ->
                (0 until sandboxes.length())
                    .mapNotNull { sandboxes.optJSONObject(it) }
                    .firstOrNull { it.optString("name") == SANDBOX_NAME }
            }
        assumeTrue("A runnable $SANDBOX_NAME sandbox is required: $sandboxStatus", alpine != null)
        assertTrue(sandboxStatus.toString(2), alpine?.optBoolean("android_execution_supported") == true)

        val start = HermesLinuxSandboxBridge.performAction(app, action = "start", name = SANDBOX_NAME)
        assertEquals(start.toString(2), 0, start.optInt("exit_code", -1))
        val cleanup = HermesLinuxSandboxBridge.performAction(
            context = app,
            action = "run",
            name = SANDBOX_NAME,
            command = "rm -f $PROOF_PATH",
            timeoutSeconds = 60,
        )
        assertEquals(cleanup.toString(2), 0, cleanup.optInt("exit_code", -1))

        val downloads = LocalModelDownloadStore(app)
        val imported = HermesModelDownloadManager.refreshDownloads(app, downloads)
            .firstOrNull { it.destinationPath == modelFile.absolutePath }
        assertTrue("Legacy app-private model was not auto-imported", imported != null)
        assertEquals("completed", imported?.status)
        downloads.setPreferredDownloadId(imported?.id.orEmpty())
        AppSettingsStore(app).save(
            AppSettings(
                provider = "custom",
                baseUrl = "",
                model = MODEL_ID,
                onDeviceBackend = BackendKind.LITERT_LM.persistedValue,
            ),
        )

        val runtime = HermesRuntimeManager.ensureStarted(app)
        assertTrue(runtime.error.orEmpty(), runtime.started)
        val backend = OnDeviceBackendManager.currentStatus()
        assertEquals(BackendKind.LITERT_LM, backend.backendKind)
        assertTrue(backend.statusMessage, backend.started)

        val result = NativeToolCallingChatClient(app).send(
            baseUrl = backend.baseUrl.removeSuffix("/v1"),
            modelName = backend.modelName,
            sessionId = "gemma4-alpine-tool-proof",
            userText = "Inside the active Alpine 3.21 guest, perform this as one guest action: " +
                "printf 'HERMES_GEMMA_ALPINE_TOOL_OK\\n' > $PROOF_PATH; " +
                "cat /etc/alpine-release >> $PROOF_PATH. Then report the release you observed.",
        )

        assertTrue("Expected at least one real Gemma model request: $result", result.modelRequestCount > 0)
        assertTrue("Expected Gemma to invoke a sandbox tool: $result", result.executedToolCalls > 0)
        assertFalse("Expected a visible post-tool reply", result.content.isBlank())

        val proof = HermesLinuxSandboxBridge.performAction(
            context = app,
            action = "run",
            name = SANDBOX_NAME,
            command = "cat $PROOF_PATH",
            timeoutSeconds = 60,
        )
        assertEquals(proof.toString(2), 0, proof.optInt("exit_code", -1))
        assertTrue(proof.toString(2), proof.optString("output").contains("HERMES_GEMMA_ALPINE_TOOL_OK"))
        assertTrue(proof.toString(2), proof.optString("output").contains("3.21"))
        assertEquals("proot_distro_qemu", proof.optString("sandbox_execution_mode"))
    }

    private companion object {
        private const val MODEL_ID = "gemma-4-E2B-it"
        private const val MODEL_RELATIVE_PATH = "hermes-home/downloads/models/gemma-4-E2B-it.litertlm"
        private const val MODEL_BYTES = 2_583_085_056L
        private const val SANDBOX_NAME = "hermes-alpine"
        private const val PROOF_PATH = "/tmp/hermes-gemma-alpine-proof"
    }
}
