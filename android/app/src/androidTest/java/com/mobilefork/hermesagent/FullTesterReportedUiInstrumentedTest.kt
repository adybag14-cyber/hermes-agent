package com.mobilefork.hermesagent

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.ConversationStore
import com.mobilefork.hermesagent.data.McpSettingsStore
import com.mobilefork.hermesagent.data.StoredConversationMessage
import com.mobilefork.hermesagent.ui.boot.BootUiState
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.ConversationHistoryText
import com.mobilefork.hermesagent.ui.i18n.McpRuntimeText
import com.mobilefork.hermesagent.ui.i18n.hermesStringsFor
import com.mobilefork.hermesagent.ui.i18n.historyText
import com.mobilefork.hermesagent.ui.i18n.mcpRuntimeText
import com.mobilefork.hermesagent.ui.shell.AppShellScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses the installed app shell and real persisted stores, with remote networking disabled. */
@RunWith(AndroidJUnit4::class)
class FullTesterReportedUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun historyActionsAndExplicitMcpConsentWorkInAllSixLanguages() {
        assertFalse(BuildConfig.HERMES_PLAY_EDITION)
        val settings = AppSettingsStore(app)
        val originalSettings = settings.load()
        val mcp = McpSettingsStore(app)
        val originalConsent = mcp.externalMcpAllowed()
        val conversations = ConversationStore(app)
        val originalCurrent = conversations.currentSessionId()
        val sample = conversations.createNewConversation()
        val taskText = "整理工作区的项目笔记"
        conversations.upsertMessages(sample.sessionId, listOf(
            StoredConversationMessage("fixture-greeting", "user", "你好", 1),
            StoredConversationMessage("fixture-reply", "assistant", "你好！", 2),
            StoredConversationMessage("fixture-task", "user", taskText, 3),
        ))
        val active = conversations.createNewConversation()
        try {
            settings.save(originalSettings.copy(languageTag = "zh", offlineAirplaneMode = true,
                portalEnabled = false, onDeviceBackend = "none"))
            mcp.saveExternalMcpEnabled(false)
            compose.setContent {
                AppShellScreen(BootUiState(ready = true, status = "UI fixture", probeResult = "offline UI fixture"),
                    onRetryHermes = {})
            }
            compose.onNodeWithTag("HermesChatHistoryButton").performClick()
            val chinese = hermesStringsFor(AppLanguage.CHINESE)
            compose.onNodeWithTag("HermesHistoryRow-${sample.sessionId}").performTouchInput { longClick() }
            compose.onNodeWithText(chinese.historyText(ConversationHistoryText.RENAME)).performClick()
            compose.onNodeWithTag("HermesHistoryTitleInput").performTextReplacement("本地手动标题")
            capture("zh-history-rename")
            compose.onNodeWithText(chinese.historyText(ConversationHistoryText.SAVE)).performClick()
            compose.waitUntil(5_000) { ConversationStore(app).loadConversation(sample.sessionId)?.title == "本地手动标题" }
            compose.onNodeWithTag("HermesHistoryActions-${sample.sessionId}").performClick()
            compose.onNodeWithText(chinese.historyText(ConversationHistoryText.REGENERATE)).performClick()
            compose.waitUntil(5_000) { ConversationStore(app).loadConversation(sample.sessionId)?.title == taskText }
            assertEquals(active.sessionId, ConversationStore(app).currentSessionId())
            capture("zh-history-regenerated")

            AppLanguage.entries.forEach { language ->
                val strings = hermesStringsFor(language)
                navigate("Settings")
                compose.onNodeWithTag("HermesSettingsContentList").performScrollToIndex(0)
                compose.onNodeWithTag("HermesSettingsPage_Overview").performClick()
                scroll("SettingsLanguage-${language.tag}")
                compose.onNodeWithTag("SettingsLanguage-${language.tag}").performClick()
                compose.waitUntil(5_000) { settings.load().languageTag == language.tag }
                compose.onNodeWithTag("HermesSettingsContentList").performScrollToIndex(0)
                compose.onNodeWithTag("HermesSettingsPage_Tools").performClick()
                scroll("McpExternalEnabled")
                compose.onNodeWithTag("McpExternalEnabled").performClick()
                compose.onNodeWithText(strings.mcpRuntimeText(McpRuntimeText.DISCLOSURE)).assertIsDisplayed()
                assertFalse("Opening the dialog must not grant consent", mcp.externalMcpAllowed())
                capture("${language.tag}-mcp-consent")
                compose.onNodeWithText(strings.mcpCancel()).performClick()
                assertFalse(mcp.externalMcpAllowed())
                navigate("Hermes")
                if (compose.onAllNodesWithTag("HermesHistoryActions-${sample.sessionId}").fetchSemanticsNodes().isEmpty()) {
                    compose.onNodeWithTag("HermesChatHistoryButton").performClick()
                }
                compose.onNodeWithTag("HermesHistoryActions-${sample.sessionId}").performClick()
                compose.onNodeWithText(strings.historyText(ConversationHistoryText.RENAME)).assertIsDisplayed()
                compose.onNodeWithText(strings.historyText(ConversationHistoryText.REGENERATE)).assertIsDisplayed()
                compose.onNodeWithText(strings.historyText(ConversationHistoryText.DELETE)).assertIsDisplayed()
                capture("${language.tag}-history-actions")
                compose.onNodeWithText(strings.historyText(ConversationHistoryText.RENAME)).performClick()
                compose.onNodeWithText(strings.historyText(ConversationHistoryText.CANCEL)).performClick()
            }
            val strings = hermesStringsFor(AppLanguage.fromTag(settings.load().languageTag))
            compose.onNodeWithTag("HermesHistoryActions-${sample.sessionId}").performClick()
            compose.onNodeWithText(strings.historyText(ConversationHistoryText.DELETE)).performClick()
            compose.onNodeWithText(strings.historyText(ConversationHistoryText.DELETE_CONFIRM)).assertIsDisplayed()
            compose.onAllNodesWithText(strings.historyText(ConversationHistoryText.DELETE)).onLast().performClick()
            compose.waitUntil(5_000) { ConversationStore(app).loadConversation(sample.sessionId) == null }
            assertNull(ConversationStore(app).loadConversation(sample.sessionId))
            assertEquals("Deleting an inactive chat changed the active chat", active.sessionId,
                ConversationStore(app).currentSessionId())
            assertTrue("The title regeneration check must stay offline", settings.load().offlineAirplaneMode)
            capture("history-delete-complete")
        } finally {
            ConversationStore(app).apply {
                clearConversation(sample.sessionId)
                clearConversation(active.sessionId)
                switchConversation(originalCurrent)
            }
            mcp.saveExternalMcpEnabled(originalConsent)
            settings.save(originalSettings)
        }
    }

    private fun navigate(section: String) {
        val destination = "HermesNav$section"
        if (compose.onAllNodesWithTag(destination).fetchSemanticsNodes().isEmpty()) {
            val drawer = if (compose.onAllNodesWithTag("HermesShellDrawerButton").fetchSemanticsNodes().isNotEmpty())
                "HermesShellDrawerButton" else "HermesChatDrawerButton"
            compose.onNodeWithTag(drawer).performClick()
        }
        compose.onNodeWithTag(destination).performClick()
        compose.waitForIdle()
    }

    private fun scroll(tag: String) {
        compose.onNodeWithTag("HermesSettingsContentList").performScrollToNode(hasTestTag(tag))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // A dialog's Android Window fade is not driven by the Compose clock.
        // Settle that native transition before capturing the complete display.
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 5_000)
        android.os.SystemClock.sleep(250)
        val directory = File(app.getExternalFilesDir(null), "v157-tester-ui").apply { mkdirs() }
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        File(directory, "$name.txt").writeText(roots.fetchSemanticsNodes().indices.joinToString("\n") {
            roots[it].printToString()
        })
        File(directory, "$name.png").outputStream().use {
            check(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
