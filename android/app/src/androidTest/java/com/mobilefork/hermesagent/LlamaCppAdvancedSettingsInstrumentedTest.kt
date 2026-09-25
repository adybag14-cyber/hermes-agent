package com.mobilefork.hermesagent

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.llamaCppAdvancedText
import com.mobilefork.hermesagent.ui.settings.SettingsPage
import com.mobilefork.hermesagent.ui.settings.SettingsScreen
import com.mobilefork.hermesagent.ui.settings.SettingsViewModel
import com.mobilefork.hermesagent.ui.theme.HermesTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlamaCppAdvancedSettingsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    private var originalSettings: AppSettings? = null

    @After
    fun restoreSettings() {
        originalSettings?.let { settings -> AppSettingsStore(app).save(settings) }
    }

    @Test
    fun advancedValidationAndOneShotConsentRenderInAllSixLanguages() {
        val store = AppSettingsStore(app)
        originalSettings = store.load()
        store.save(AppSettings(languageTag = AppLanguage.ENGLISH.tag))
        val viewModel = SettingsViewModel(app)

        composeRule.setContent {
            HermesTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    SettingsScreen(
                        viewModel = viewModel,
                        initialPage = SettingsPage.Models,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("HermesSettingsContentList")
            .performScrollToNode(hasTestTag("ModelSettings-advanced"))
        composeRule.onNodeWithTag("ModelSettings-advanced").performClick()
        composeRule.waitForIdle()

        AppLanguage.entries.forEach { language ->
            composeRule.runOnIdle {
                viewModel.selectLanguage(language)
                viewModel.updateLlamaCppRuntimeLane("stable")
                viewModel.updateLlamaCppCacheTypeK("default")
                viewModel.updateLlamaCppCacheTypeV("default")
                viewModel.updateLlamaCppFlashAttention("default")
                viewModel.updateLlamaCppAdditionalArguments(emptyList())
            }
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("HermesSettingsContentList")
                .performScrollToNode(hasTestTag("LlamaCppAdvancedCard"))
            composeRule.onNodeWithTag("LlamaCppAdvancedCard").assertIsDisplayed()
            composeRule.onNodeWithText(llamaCppAdvancedText(language, "title"))
                .scrollInsideSettingsViewport()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("LlamaCppRuntimeLane-stable")
                .scrollInsideSettingsViewport()
                .assertTextContains(llamaCppAdvancedText(language, "stable"), substring = true)
                .assertContentDescriptionEquals(
                    "${llamaCppAdvancedText(language, "lane")}: ${llamaCppAdvancedText(language, "stable")}",
                )
            composeRule.onNodeWithText(llamaCppAdvancedText(language, "q5_explanation"))
                .scrollInsideSettingsViewport()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("LlamaCppAdditionalArguments")
                .scrollInsideSettingsViewport()
                .assertContentDescriptionEquals(llamaCppAdvancedText(language, "additional_arguments"))
            composeRule.onNodeWithTag("LlamaCppCacheK-q5_0").scrollInsideSettingsViewport().assertIsDisplayed()
            composeRule.onNodeWithTag("LlamaCppCacheV-q5_1").scrollInsideSettingsViewport().assertIsDisplayed()
            composeRule.onAllNodesWithTag("LlamaCppCacheV-turbo3").assertCountEquals(0)

            composeRule.onNodeWithTag("LlamaCppRuntimeLane-turboquant").scrollInsideSettingsViewport().performClick()
            composeRule.onNodeWithTag("LlamaCppCacheV-turbo3").scrollInsideSettingsViewport().performClick()
            composeRule.onNodeWithTag("LlamaCppFlashAttention-off").scrollInsideSettingsViewport().performClick()

            val flashValidation = llamaCppAdvancedText(language, "invalid_quantized_v_flash_off")
            composeRule.onNodeWithTag("LlamaCppAdvancedValidationError")
                .scrollInsideSettingsViewport()
                .assertIsDisplayed()
                .assertTextEquals(flashValidation)
                .assertContentDescriptionEquals(flashValidation)
            composeRule.onNodeWithTag("ApplyLlamaCppAdvancedSettingsButton")
                .scrollInsideSettingsViewport()
                .assertIsNotEnabled()

            composeRule.onNodeWithTag("LlamaCppFlashAttention-auto").scrollInsideSettingsViewport().performClick()
            composeRule.onAllNodesWithTag("LlamaCppAdvancedValidationError").assertCountEquals(0)
            composeRule.onNodeWithTag("ApplyLlamaCppAdvancedSettingsButton")
                .scrollInsideSettingsViewport()
                .assertIsEnabled()

            // A trailing blank argv line must remain in the raw draft and disable Apply.
            composeRule.onNodeWithTag("LlamaCppAdditionalArguments")
                .scrollInsideSettingsViewport()
                .performTextReplacement("--threads-batch\n")
            val argvValidation = llamaCppAdvancedText(language, "invalid_arguments")
            composeRule.onNodeWithTag("LlamaCppAdvancedValidationError")
                .scrollInsideSettingsViewport()
                .assertTextEquals(argvValidation)
            composeRule.onNodeWithTag("ApplyLlamaCppAdvancedSettingsButton")
                .scrollInsideSettingsViewport()
                .assertIsNotEnabled()

            composeRule.onNodeWithTag("LlamaCppAdditionalArguments")
                .scrollInsideSettingsViewport()
                .performTextReplacement("--threads-batch\n4")
            composeRule.onAllNodesWithTag("LlamaCppAdvancedValidationError").assertCountEquals(0)
            composeRule.onNodeWithTag("LlamaCppEffectiveArgumentsSummary")
                .scrollInsideSettingsViewport()
                .assertTextContains("2", substring = true)

            val dangerousButton = llamaCppAdvancedText(language, "danger_button")
            composeRule.onNodeWithTag("TryLlamaCppDespiteRamWarningButton")
                .scrollInsideSettingsViewport()
                .assertTextContains(dangerousButton)
                .assertContentDescriptionEquals(dangerousButton)
                .performClick()
            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onNodeWithTag("LlamaCppDangerousRamDialog").isDisplayed()
            }
            composeRule.onNodeWithTag("LlamaCppDangerousRamDialog").assertIsDisplayed()
            composeRule.onNodeWithText(llamaCppAdvancedText(language, "danger_dialog_title")).assertIsDisplayed()
            composeRule.onNodeWithText(llamaCppAdvancedText(language, "danger_dialog_body")).assertIsDisplayed()
            composeRule.onNodeWithTag("LlamaCppDangerousRamConfirm")
                .assertIsDisplayed()
                .assertTextContains(llamaCppAdvancedText(language, "confirm"))
            composeRule.onNodeWithTag("LlamaCppDangerousRamCancel")
                .assertTextContains(llamaCppAdvancedText(language, "cancel"))
                .performClick()
            composeRule.onAllNodesWithTag("LlamaCppDangerousRamDialog").assertCountEquals(0)
        }
    }
    /** Center real controls in the content viewport, accounting for fixed tabs and the IME. */
    private fun SemanticsNodeInteraction.scrollInsideSettingsViewport(): SemanticsNodeInteraction {
        performScrollTo()
        composeRule.waitForIdle()
        val viewport = composeRule.onNodeWithTag("HermesSettingsContentList")
        val viewportBounds = viewport.fetchSemanticsNode().boundsInRoot
        val target = fetchSemanticsNode()
        val targetCenter = target.positionInRoot.y + target.size.height / 2f
        val delta = targetCenter - viewportBounds.center.y
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy -> scrollBy(0f, delta) }
        composeRule.waitForIdle()
        return this
    }

}
