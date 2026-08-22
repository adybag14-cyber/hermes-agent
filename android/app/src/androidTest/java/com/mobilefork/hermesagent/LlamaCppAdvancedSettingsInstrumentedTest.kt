package com.mobilefork.hermesagent

import android.app.Application
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
                SettingsScreen(
                    viewModel = viewModel,
                    initialPage = SettingsPage.Models,
                )
            }
        }
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
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("LlamaCppRuntimeLane-stable")
                .performScrollTo()
                .assertTextContains(llamaCppAdvancedText(language, "stable"), substring = true)
                .assertContentDescriptionEquals(
                    "${llamaCppAdvancedText(language, "lane")}: ${llamaCppAdvancedText(language, "stable")}",
                )
            composeRule.onNodeWithText(llamaCppAdvancedText(language, "q5_explanation"))
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("LlamaCppAdditionalArguments")
                .performScrollTo()
                .assertContentDescriptionEquals(llamaCppAdvancedText(language, "additional_arguments"))
            composeRule.onNodeWithTag("LlamaCppCacheK-q5_0").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("LlamaCppCacheV-q5_1").performScrollTo().assertIsDisplayed()
            composeRule.onAllNodesWithTag("LlamaCppCacheV-turbo3").assertCountEquals(0)

            composeRule.onNodeWithTag("LlamaCppRuntimeLane-turboquant").performScrollTo().performClick()
            composeRule.onNodeWithTag("LlamaCppCacheV-turbo3").performScrollTo().performClick()
            composeRule.onNodeWithTag("LlamaCppFlashAttention-off").performScrollTo().performClick()

            val flashValidation = llamaCppAdvancedText(language, "invalid_quantized_v_flash_off")
            composeRule.onNodeWithTag("LlamaCppAdvancedValidationError")
                .performScrollTo()
                .assertIsDisplayed()
                .assertTextEquals(flashValidation)
                .assertContentDescriptionEquals(flashValidation)
            composeRule.onNodeWithTag("ApplyLlamaCppAdvancedSettingsButton")
                .performScrollTo()
                .assertIsNotEnabled()

            composeRule.onNodeWithTag("LlamaCppFlashAttention-auto").performScrollTo().performClick()
            composeRule.onAllNodesWithTag("LlamaCppAdvancedValidationError").assertCountEquals(0)
            composeRule.onNodeWithTag("ApplyLlamaCppAdvancedSettingsButton")
                .performScrollTo()
                .assertIsEnabled()

            // A trailing blank argv line must remain in the raw draft and disable Apply.
            composeRule.onNodeWithTag("LlamaCppAdditionalArguments")
                .performScrollTo()
                .performTextReplacement("--load-mode\n")
            val argvValidation = llamaCppAdvancedText(language, "invalid_arguments")
            composeRule.onNodeWithTag("LlamaCppAdvancedValidationError")
                .performScrollTo()
                .assertTextEquals(argvValidation)
            composeRule.onNodeWithTag("ApplyLlamaCppAdvancedSettingsButton")
                .performScrollTo()
                .assertIsNotEnabled()

            composeRule.onNodeWithTag("LlamaCppAdditionalArguments")
                .performScrollTo()
                .performTextReplacement("--load-mode\nmmap")
            composeRule.onAllNodesWithTag("LlamaCppAdvancedValidationError").assertCountEquals(0)
            composeRule.onNodeWithTag("LlamaCppEffectiveArgumentsSummary")
                .performScrollTo()
                .assertTextContains("2", substring = true)

            val dangerousButton = llamaCppAdvancedText(language, "danger_button")
            composeRule.onNodeWithTag("TryLlamaCppDespiteRamWarningButton")
                .performScrollTo()
                .assertTextContains(dangerousButton)
                .assertContentDescriptionEquals(dangerousButton)
                .performClick()
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
}
