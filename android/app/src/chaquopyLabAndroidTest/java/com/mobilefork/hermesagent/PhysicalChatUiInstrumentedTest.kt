package com.mobilefork.hermesagent

import android.content.Context
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.PowerManager
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.models.VerifiedLocalModelArtifacts
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real MainActivity and Send button, not a synthetic ready-state shell. */
@RunWith(AndroidJUnit4::class)
class PhysicalChatUiInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun qwenReplyAppearsAfterSendingThroughTheActualChatUi() {
        assertTrue(BuildConfig.HERMES_CHAQUOPY_LAB)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.mobilefork.hermesagent.lab", context.packageName)
        assertTrue("Unlock the phone before physical UI validation",
            !(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked)
        assertTrue("The physical screen must be awake",
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive)
        val settings = AppSettingsStore(context)
        val original = settings.load()
        val downloads = LocalModelDownloadStore(context)
        val originalDownloads = downloads.loadDownloads()
        val originalPreferred = downloads.preferredDownloadId()
        val artifact = VerifiedLocalModelArtifacts.require("unsloth/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B-Q4_K_M.gguf")
        val record = LocalModelDownloadRecord(
            id = "physical-ui-qwen-fixture", title = artifact.fileName,
            sourceUrl = "https://huggingface.co/${artifact.repoId}/resolve/${artifact.revision}/${artifact.fileName}",
            repoOrUrl = artifact.repoId, filePath = artifact.fileName, revision = artifact.revision,
            runtimeFlavor = "GGUF", destinationFileName = artifact.fileName,
            destinationPath = File(context.filesDir, "hermes-home/downloads/models/${artifact.fileName}").absolutePath,
            downloadManagerId = -1, totalBytes = artifact.expectedBytes, downloadedBytes = artifact.expectedBytes,
            status = "completed", supportsResume = false,
        )
        val verified = VerifiedLocalModelArtifacts.verify(File(record.destinationPath), artifact)
        assertTrue(verified.detail, verified.valid)
        var scenario: ActivityScenario<MainActivity>? = null
        val evidence = File(context.filesDir, "model-experiments/physical-chat-ui").apply { mkdirs() }
        try {
            downloads.upsertDownload(record)
            downloads.setPreferredDownloadId(record.id)
            settings.save(original.copy(
                provider = "custom", baseUrl = "", model = artifact.fileName, onDeviceBackend = "llama.cpp",
                languageTag = "en", offlineAirplaneMode = true, localModelAccelerator = "cpu",
                llamaCppRuntimeLane = "stable", llamaCppCacheTypeK = "f16", llamaCppCacheTypeV = "f16",
                llamaCppFlashAttention = "auto", llamaCppAdditionalArguments = emptyList(),
            ))
            val status = OnDeviceBackendManager.ensureConfigured(context, "llama.cpp")
            assertTrue(status.statusMessage, status.started)
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            compose.onNodeWithTag("HermesChatPageActionsButton").performClick()
            compose.onNodeWithText("New chat").performClick()
            val answer = hasText("42") and hasAnyAncestor(hasTestTag("HermesChatMessageList"))
            compose.onAllNodes(answer).assertCountEquals(0)
            compose.onNodeWithTag("HermesChatInput").assertIsDisplayed()
                .performTextInput("What is 17 + 25? Reply with only the number.")
            compose.onNodeWithTag("HermesChatSendButton").assertIsEnabled().performClick()
            compose.waitUntil(90_000) {
                compose.onAllNodes(answer).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodes(answer)[0].assertIsDisplayed()
            File(evidence, "reply.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            File(evidence, "semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
            val focus = shell("dumpsys window").lineSequence().firstOrNull { it.contains("mCurrentFocus=") }.orEmpty()
            assertTrue("The lab app must own the visible window: $focus", focus.contains(context.packageName))
            val display = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(evidence, "reply-display.png").outputStream().use { display.compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithTag("HermesChatDrawerButton").performClick()
            compose.onNodeWithTag("HermesNavSettings").performClick()
            compose.onNodeWithTag("HermesSettingsContentList").performScrollToIndex(0)
            compose.onNodeWithTag("HermesSettingsPage_Models").performClick()
            compose.waitForIdle()
            val bounds = compose.onNodeWithTag("HermesSettingsContentList").fetchSemanticsNode().boundsInRoot
            val x = (bounds.left + bounds.width * 0.85f).toInt()
            val top = (bounds.top + bounds.height * 0.25f).toInt()
            val bottom = (bounds.top + bounds.height * 0.80f).toInt()
            shell("dumpsys gfxinfo ${context.packageName} reset")
            repeat(4) {
                shell("input swipe $x $bottom $x $top 350")
                SystemClock.sleep(150)
                shell("input swipe $x $top $x $bottom 350")
                SystemClock.sleep(150)
            }
            val graphics = shell("dumpsys gfxinfo ${context.packageName} framestats")
            File(evidence, "gfxinfo.txt").writeText(graphics)
            val frames = Regex("Total frames rendered:\\s*(\\d+)").find(graphics)?.groupValues?.get(1)?.toInt() ?: 0
            assertTrue("Too few rendered frames for a UI baseline: $frames", frames >= 100)
            File(evidence, "report.json").writeText(JSONObject().put("status", "passed")
                .put("release_certified", false).put("app_package", context.packageName)
                .put("model_sha256", verified.actualSha256).put("actual_main_activity", true)
                .put("send_button_clicked", true).put("answer_visible", true)
                .put("scroll_frames", frames).put("performance_scope", "instrumented-settings-scroll-baseline").toString(2))
        } finally {
            scenario?.close()
            OnDeviceBackendManager.stopAll()
            settings.save(original)
            downloads.saveDownloads(originalDownloads)
            downloads.setPreferredDownloadId(originalPreferred)
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).bufferedReader().use { it.readText() }
}
