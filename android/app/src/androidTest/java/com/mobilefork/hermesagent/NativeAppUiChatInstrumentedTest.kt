package com.mobilefork.hermesagent

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.ConversationStore
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.device.HermesLinuxSubsystemBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeAppUiChatInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        HermesRuntimeManager.stop()
        OnDeviceBackendManager.stopAll()
    }

    @Test
    fun mainActivityChatUiUsesGemma4ToRunNativeTerminalTool() {
        val modelFile = File(app.filesDir, MODEL_RELATIVE_PATH)
        assumeTrue("Gemma 4 LiteRT-LM model is not provisioned at ${modelFile.absolutePath}", modelFile.isFile)
        assertEquals("Gemma 4 LiteRT-LM model size", MODEL_BYTES, modelFile.length())

        seedPreferredGemma4Model(modelFile)
        ConversationStore(app).clearAll()
        val workspace = File(HermesLinuxSubsystemBridge.ensureInstalled(app).getString("home_path"))
        val toolFile = File(workspace, "hermes-ui-tool-smoke.txt")
        toolFile.delete()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(timeoutMillis = BOOT_TIMEOUT_MS) {
                composeRule.onAllNodesWithText("Hermes Fork Chat").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Hermes Fork Chat").assertIsDisplayed()
            composeRule.onNodeWithTag("HermesChatInput").assertIsDisplayed()

            composeRule.onNodeWithTag("HermesChatInput").performTextInput(
                "Use terminal_tool to run exactly this command: " +
                    "printf ui-tool-ok > \$HOME/hermes-ui-tool-smoke.txt && " +
                    "cat \$HOME/hermes-ui-tool-smoke.txt. " +
                    "After terminal_tool returns, reply with the command output.",
            )
            composeRule.onNodeWithTag("HermesChatSendButton").performClick()

            composeRule.waitUntil(timeoutMillis = CHAT_TIMEOUT_MS) {
                toolFile.isFile && toolFile.readText() == "ui-tool-ok"
            }
            composeRule.waitUntil(timeoutMillis = CHAT_TIMEOUT_MS) {
                ConversationStore(app)
                    .currentConversationMessages()
                    .lastOrNull { it.role == "assistant" }
                    ?.content
                    ?.contains("ui-tool-ok") == true
            }
            composeRule.waitUntil(timeoutMillis = CHAT_TIMEOUT_MS) {
                composeRule.onAllNodesWithText("ui-tool-ok", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }
    }

    @Test
    fun mainActivityMiniCpmNaturalTerminalRequestShowsCollapsibleTimeline() {
        val modelFile = File(app.filesDir, MINICPM_MODEL_RELATIVE_PATH)
        assumeTrue("MiniCPM LiteRT-LM model is not provisioned at ${modelFile.absolutePath}", modelFile.isFile)
        assertEquals("MiniCPM LiteRT-LM model size", MINICPM_MODEL_BYTES, modelFile.length())

        seedPreferredMiniCpmModel(modelFile)
        ConversationStore(app).clearAll()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(timeoutMillis = BOOT_TIMEOUT_MS) {
                composeRule.onAllNodesWithText("Hermes Fork Chat").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("HermesChatInput").performTextInput(
                "Could you please run pwd and tell me the current working directory?",
            )
            composeRule.onNodeWithTag("HermesChatSendButton").performClick()

            composeRule.waitUntil(timeoutMillis = CHAT_TIMEOUT_MS) {
                val roles = ConversationStore(app).currentConversationMessages().map { message -> message.role }
                "tool_call" in roles && "process_log" in roles && "assistant" in roles
            }
            val processLog = ConversationStore(app).currentConversationMessages()
                .last { message -> message.role == "process_log" }
                .content
            assertTrue(
                "Expected the working directory from a successful terminal result, got $processLog",
                processLog.contains("/files/hermes-home/native-shell/home"),
            )
            assertFalse("System-shell fallback must not hit a missing Termux dependency: $processLog", processLog.contains("CANNOT LINK EXECUTABLE"))
            composeRule.onNodeWithTag("HermesAgentEvent_tool_call").assertIsDisplayed()
            composeRule.onNodeWithTag("HermesAgentEvent_process_log").assertIsDisplayed()

            composeRule.onNodeWithTag("HermesToggleIntermediateSteps").performClick()
            composeRule.waitUntil {
                composeRule.onAllNodesWithTag("HermesAgentEvent_tool_call").fetchSemanticsNodes().isEmpty() &&
                    composeRule.onAllNodesWithTag("HermesAgentEvent_process_log").fetchSemanticsNodes().isEmpty()
            }
            composeRule.onNodeWithTag("HermesToggleIntermediateSteps").performClick()
            composeRule.onNodeWithTag("HermesAgentEvent_tool_call").assertIsDisplayed()
        }
    }

    private fun seedPreferredGemma4Model(modelFile: File) {
        val record = LocalModelDownloadRecord(
            id = "gemma-4-e2b-litertlm-native-ui-smoke",
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
            statusMessage = "Provisioned for native UI instrumentation",
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

    private fun seedPreferredMiniCpmModel(modelFile: File) {
        val record = LocalModelDownloadRecord(
            id = "minicpm5-1b-litertlm-native-ui-regression",
            title = MINICPM_MODEL_ID,
            sourceUrl = "https://huggingface.co/$MINICPM_MODEL_REPO/resolve/main/$MINICPM_MODEL_FILE_NAME",
            repoOrUrl = MINICPM_MODEL_REPO,
            filePath = MINICPM_MODEL_FILE_NAME,
            revision = "main",
            runtimeFlavor = "LiteRT-LM",
            destinationFileName = MINICPM_MODEL_FILE_NAME,
            destinationPath = modelFile.absolutePath,
            downloadManagerId = -1L,
            totalBytes = MINICPM_MODEL_BYTES,
            downloadedBytes = MINICPM_MODEL_BYTES,
            status = "completed",
            statusMessage = "Provisioned for MiniCPM native UI regression",
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
                model = MINICPM_MODEL_ID,
                onDeviceBackend = BackendKind.LITERT_LM.persistedValue,
            ),
        )
    }

    private companion object {
        private const val MODEL_ID = "gemma-4-E2B-it"
        private const val MODEL_REPO = "litert-community/gemma-4-E2B-it-litert-lm"
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val MODEL_RELATIVE_PATH = "hermes-home/downloads/models/$MODEL_FILE_NAME"
        private const val MODEL_SOURCE_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/gemma-4-E2B-it.litertlm"
        private const val MODEL_REVISION = "7fa1d78473894f7e736a21d920c3aa80f950c0db"
        private const val MODEL_BYTES = 2_583_085_056L
        private const val MINICPM_MODEL_ID = "MiniCPM5-1B"
        private const val MINICPM_MODEL_REPO = "Tdamre/MiniCPM5-1B-litert-lm"
        private const val MINICPM_MODEL_FILE_NAME = "MiniCPM5-1B-web.litertlm"
        private const val MINICPM_MODEL_RELATIVE_PATH = "hermes-home/downloads/models/$MINICPM_MODEL_FILE_NAME"
        private const val MINICPM_MODEL_BYTES = 1_103_486_896L
        private const val BOOT_TIMEOUT_MS = 180_000L
        private const val CHAT_TIMEOUT_MS = 900_000L
    }
}
