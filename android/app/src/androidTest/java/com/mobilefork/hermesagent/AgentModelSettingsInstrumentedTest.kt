package com.mobilefork.hermesagent

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.settings.SettingsPage
import com.mobilefork.hermesagent.ui.settings.SettingsScreen
import com.mobilefork.hermesagent.ui.settings.SettingsViewModel
import com.mobilefork.hermesagent.ui.theme.HermesTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** UI validation on the installed APK; this does not claim model inference qualification. */
@RunWith(AndroidJUnit4::class)
class AgentModelSettingsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val owner = ViewModelStore()
    private lateinit var original: AppSettings

    @Before fun prepareOfflineFixture() {
        val store = AppSettingsStore(app)
        original = store.load()
        store.save(original.copy(languageTag = "en", offlineAirplaneMode = true,
            portalEnabled = false, onDeviceBackend = "none"))
    }

    @After fun restoreSettings() {
        owner.clear()
        AppSettingsStore(app).save(original)
    }

    private fun showSettings(): SettingsViewModel {
        val vm = SettingsViewModel(app)
        owner.put("settings", vm)
        compose.setContent {
            HermesTheme {
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    SettingsScreen(viewModel = vm, initialPage = SettingsPage.Models)
                }
            }
        }
        compose.onNodeWithTag("HermesImportModelButton").assertIsDisplayed().assertIsEnabled()
        return vm
    }

    @Test fun modelFirstNavigationIsVisibleAndLocalizedInEveryLanguage() {
        val vm = showSettings()
        for (language in AppLanguage.entries) {
            compose.runOnIdle { vm.selectLanguage(language) }
            compose.onNodeWithTag("HermesSettingsPage_Models").performClick().assertIsSelected()
            compose.onNodeWithTag("HermesImportModelButton").assertIsDisplayed().assertIsEnabled()
            capture("models-${language.tag}")
            compose.onNodeWithTag("HermesSettingsContentList")
                .performScrollToNode(hasTestTag("ModelSettings-generation"))
            compose.onNodeWithTag("HermesSettingsPage_Models").performClick()
            compose.onNodeWithTag("HermesImportModelButton").assertIsDisplayed()
        }
        compose.runOnIdle { vm.selectLanguage(AppLanguage.ENGLISH) }
        for (page in listOf(SettingsPage.Overview, SettingsPage.Theme, SettingsPage.Privacy)) {
            compose.onNodeWithTag("HermesSettingsPage_${page.name}").performClick().assertIsSelected()
            capture("settings-${page.name.lowercase()}")
        }
    }

    @Test fun importOpensAndroidDocumentsUiOfflineAndCanBeCancelled() {
        showSettings()
        compose.onNodeWithTag("HermesImportModelButton").performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var pickerVisible = false
        while (SystemClock.elapsedRealtime() < deadline && !pickerVisible) {
            pickerVisible = automation.rootInActiveWindow?.packageName?.toString()
                ?.contains("documentsui", ignoreCase = true) == true
            if (!pickerVisible) SystemClock.sleep(100)
        }
        assertTrue("The system file picker must open while Agent is offline", pickerVisible)
        capture("system-file-picker")
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitForIdle()
        compose.onNodeWithTag("HermesImportModelButton").assertIsDisplayed().assertIsEnabled()
        assertEquals("none", AppSettingsStore(app).load().onDeviceBackend)
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250) // Allow the rendered frame, not just Compose semantics, to reach SurfaceFlinger.
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(app.getExternalFilesDir(null), "agent-settings-validation").apply { mkdirs() }
        val file = File(directory, "$name.png")
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        assertTrue(file.length() > 1000)
    }
}
