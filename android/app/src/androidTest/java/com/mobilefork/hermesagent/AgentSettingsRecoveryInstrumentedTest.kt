package com.mobilefork.hermesagent

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.LocalHermesStrings
import com.mobilefork.hermesagent.ui.i18n.hermesStringsFor
import com.mobilefork.hermesagent.ui.i18n.modelSettingsText
import com.mobilefork.hermesagent.ui.settings.SettingsDisclosure
import com.mobilefork.hermesagent.ui.settings.SettingsPage
import com.mobilefork.hermesagent.ui.settings.SettingsScreen
import com.mobilefork.hermesagent.ui.settings.SettingsViewModel
import com.mobilefork.hermesagent.ui.theme.HermesTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Complements the real DocumentsUI test; no model-inference claims or provider credentials. */
@RunWith(AndroidJUnit4::class)
class AgentSettingsRecoveryInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val owner = ViewModelStore()
    private lateinit var original: AppSettings

    @Before fun prepareOfflineSettings() {
        val store = AppSettingsStore(app)
        original = store.load()
        store.save(original.copy(languageTag = "en", offlineAirplaneMode = true,
            portalEnabled = false, onDeviceBackend = "none"))
    }

    @After fun restoreSettings() {
        owner.clear()
        AppSettingsStore(app).save(original)
    }

    private fun model(): SettingsViewModel = SettingsViewModel(app).also { owner.put("settings", it) }

    @Test fun modelNavigationReturnsToThePickerAndSurvivesStateRestoration() {
        val viewModel = model()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            HermesTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { SettingsScreen(viewModel = viewModel) }
            }
        }
        compose.onNodeWithTag("HermesSettingsPage_Models").performClick().assertIsSelected()
        compose.onNodeWithTag("HermesSettingsContentList")
            .performScrollToNode(hasTestTag("ModelSettings-advanced"))
        compose.onNodeWithTag("HermesSettingsPage_Theme").assertIsDisplayed().performClick()
        compose.onNodeWithTag("HermesSettingsPage_Models").performClick()
        compose.onNodeWithTag("HermesImportModelButton").assertIsDisplayed().assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("HermesSettingsPage_Models").assertIsSelected()
        compose.onNodeWithTag("HermesImportModelButton").assertIsDisplayed()
    }

    @Test fun disclosureRestoresItsStateAndAnnouncesItInEverySupportedLanguage() {
        val language = mutableStateOf(AppLanguage.ENGLISH)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            HermesTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    CompositionLocalProvider(LocalHermesStrings provides hermesStringsFor(language.value)) {
                        SettingsDisclosure("recovery-disclosure", "Response settings", "Optional preferences") {
                            androidx.compose.material3.Text("Actual settings content")
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("recovery-disclosure")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                modelSettingsText(language.value, "collapsed")))
        compose.onNodeWithText("Actual settings content").assertDoesNotExist()
        compose.onNodeWithTag("recovery-disclosure").performClick()
        restoration.emulateSavedInstanceStateRestore()
        for (nextLanguage in AppLanguage.entries) {
            compose.runOnIdle { language.value = nextLanguage }
            compose.onNodeWithTag("recovery-disclosure")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                    modelSettingsText(nextLanguage, "expanded")))
            compose.onNodeWithText("Actual settings content").assertIsDisplayed()
        }
        compose.onNodeWithTag("recovery-disclosure").performClick()
        compose.onNodeWithText("Actual settings content").assertDoesNotExist()
    }

    @Test fun appearanceAndOfflineControlsExposeTheirPurposeToAccessibilityServices() {
        val viewModel = model()
        val strings = hermesStringsFor(AppLanguage.ENGLISH)
        compose.setContent {
            HermesTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) { SettingsScreen(viewModel = viewModel) }
            }
        }
        compose.onNodeWithTag("HermesSettingsContentList")
            .performScrollToNode(hasContentDescription(strings.offlineAirplaneModeTitle()) and isToggleable())
        compose.onNode(hasContentDescription(strings.offlineAirplaneModeTitle()) and isToggleable())
            .assertIsDisplayed().assertIsOn()
        compose.onNodeWithTag("HermesSettingsPage_Theme").performClick()
        compose.onNodeWithTag("HermesSettingsContentList")
            .performScrollToNode(hasContentDescription(strings.keywordHighlightingTitle()) and isToggleable())
        compose.onNode(hasContentDescription(strings.keywordHighlightingTitle()) and isToggleable()).assertIsDisplayed()
        compose.onNodeWithTag("HermesSettingsContentList").performScrollToNode(hasTestTag("UiFontScaleSlider"))
        compose.onNodeWithTag("UiFontScaleSlider")
            .assertContentDescriptionEquals(strings.uiFontSizeLabel(viewModel.uiState.value.uiFontScale))
    }

    @Test fun narrowLargeTextLayoutKeepsTheModelImportActionReachable() {
        val viewModel = model()
        compose.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, 1.5f)) {
                HermesTheme {
                    Box(Modifier.width(320.dp).fillMaxHeight().safeDrawingPadding()) {
                        SettingsScreen(viewModel = viewModel, initialPage = SettingsPage.Models)
                    }
                }
            }
        }
        compose.onNodeWithTag("HermesImportModelButton").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("HermesSettingsPage_Models").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("HermesSettingsContentList")
            .performScrollToNode(hasTestTag("ModelSettings-generation"))
        compose.onNodeWithTag("ModelSettings-generation").performClick()
        compose.onNodeWithTag("HermesSettingsPage_Models").performClick()
        compose.onNodeWithTag("HermesImportModelButton").performScrollTo().assertIsDisplayed()
    }
}
