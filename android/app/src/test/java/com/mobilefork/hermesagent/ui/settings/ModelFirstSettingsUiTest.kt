package com.mobilefork.hermesagent.ui.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.ViewModelStore
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.modelSettingsText
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import com.mobilefork.hermesagent.data.AppSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class ModelFirstSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val owner = ViewModelStore()
    private lateinit var originalSettings: AppSettings
    @Before fun saveOriginalSettings() {
        originalSettings = AppSettingsStore(RuntimeEnvironment.getApplication()).load()
    }
    @After fun closeViewModels() {
        owner.clear()
        AppSettingsStore(RuntimeEnvironment.getApplication()).save(originalSettings)
    }

    @Test
    fun modelPickerIsVisibleBeforeRuntimeSettingsAndAfterChangingTabs() {
        val application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        store.save(store.load().copy(offlineAirplaneMode = true, languageTag = "en"))
        val vm = SettingsViewModel(application)
        owner.put("settings", vm)
        val localModels = LocalModelDownloadsViewModel(application, huggingFaceTokenLoader = { "" })
        val fixtureOwner = object : ViewModelStoreOwner { override val viewModelStore = owner }
        ViewModelProvider(fixtureOwner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == LocalModelDownloadsViewModel::class.java)
                return localModels as T
            }
        })[LocalModelDownloadsViewModel::class.java]
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides fixtureOwner) {
                MaterialTheme { SettingsScreen(viewModel = vm, initialPage = SettingsPage.Models) }
            }
        }
        compose.onNodeWithTag("HermesImportModelButton").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("HermesSettingsContentList")
            .performScrollToNode(hasTestTag("ModelSettings-generation"))
        compose.onNodeWithTag("ModelSettings-generation").assertIsDisplayed()
        compose.onNodeWithTag("HermesSettingsPage_Models").performClick()
        compose.onNodeWithTag("HermesImportModelButton").assertIsDisplayed()
        compose.onNodeWithTag("HermesSettingsPage_Theme").performClick()
        compose.onNodeWithTag("HermesSettingsPage_Models").performClick().assertIsSelected()
        compose.onNodeWithTag("HermesImportModelButton").assertIsDisplayed()
    }

    @Test
    fun disclosureAnnouncesItsStateAndOnlyExposesControlsWhenExpanded() {
        compose.setContent {
            MaterialTheme {
                SettingsDisclosure("settings-test", "Runtime", "Optional performance settings") {
                    Text("Child control")
                }
            }
        }
        val toggle = compose.onNodeWithTag("settings-test")
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            modelSettingsText(AppLanguage.ENGLISH, "collapsed")))
        compose.onNodeWithText("Child control").assertDoesNotExist()
        toggle.performClick()
        toggle.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            modelSettingsText(AppLanguage.ENGLISH, "expanded")))
        compose.onNodeWithText("Child control").assertIsDisplayed()
        toggle.performClick()
        compose.onNodeWithText("Child control").assertDoesNotExist()
    }
    @Test
    fun offlineImportLaunchesTheSystemDocumentPickerAndCancellationIsHarmlessAtLargeFontSize() {
        val application = RuntimeEnvironment.getApplication()
        val settings = AppSettingsStore(application)
        settings.save(settings.load().copy(offlineAirplaneMode = true, onDeviceBackend = "none"))
        var pickerIntent: Intent? = null
        var imports = 0
        val vm = LocalModelDownloadsViewModel(application = application,
            huggingFaceTokenLoader = { "" }, localModelFileImporter = { _, _, _ ->
                imports++
                error("A cancelled picker must not import")
            })
        owner.put("downloads", vm)
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>,
                    input: I, options: ActivityOptionsCompat?) {
                    pickerIntent = contract.createIntent(application, input)
                    dispatchResult(requestCode, Activity.RESULT_CANCELED, null)
                }
            }
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner,
                LocalDensity provides Density(1f, 1.5f)) {
                MaterialTheme {
                    LocalModelDownloadsSection(dataSaverMode = false, offlineAirplaneMode = true,
                        onDataSaverModeChange = {}, selectedBackend = "none", onRuntimeFlavorSelected = {},
                        onRequiredLlamaCppRuntimeLane = {}, onCompletedDownloadReady = { _, _ -> false },
                        viewModel = vm)
                }
            }
        }
        compose.onNodeWithTag("HermesImportModelButton").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, pickerIntent?.action)
            assertEquals("*/*", pickerIntent?.type)
            assertTrue(pickerIntent?.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
            assertEquals(0, imports)
            assertFalse(vm.uiState.value.isImporting)
            assertEquals("none", settings.load().onDeviceBackend)
        }
    }

}
