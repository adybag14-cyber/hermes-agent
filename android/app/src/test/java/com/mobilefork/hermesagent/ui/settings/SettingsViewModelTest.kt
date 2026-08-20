package com.mobilefork.hermesagent.ui.settings

import android.content.Intent
import android.provider.Browser
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.backend.LocalBackendStatus
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SettingsViewModelTest {
    @Test
    fun settingsSaveSurfacesUnsafeLocalShutdownInsteadOfStartingRemoteRuntime() {
        val message = "LiteRT-LM did not stop safely. Force stop and reopen Hermes."

        assertEquals(
            message,
            settingsSaveUnsafeTransitionMessage(
                LocalBackendStatus(
                    backendKind = BackendKind.LITERT_LM,
                    started = false,
                    statusMessage = message,
                    requiresAppRestart = true,
                ),
            ),
        )
        assertNull(
            settingsSaveUnsafeTransitionMessage(
                LocalBackendStatus(backendKind = BackendKind.NONE, started = false),
            ),
        )

        assertEquals(
            message,
            settingsRuntimeTransitionFailureMessage(
                backendKind = BackendKind.NONE,
                offlineAirplaneMode = false,
                localBackendStatus = LocalBackendStatus(
                    backendKind = BackendKind.LITERT_LM,
                    started = false,
                    statusMessage = message,
                    requiresAppRestart = true,
                ),
                runtimeState = HermesRuntimeManager.RuntimeState(
                    started = true,
                    baseUrl = "http://127.0.0.1:15436/v1",
                ),
            ),
        )
        assertEquals(
            "Final local startup failed",
            settingsRuntimeTransitionFailureMessage(
                backendKind = BackendKind.LITERT_LM,
                offlineAirplaneMode = false,
                localBackendStatus = LocalBackendStatus(
                    backendKind = BackendKind.LITERT_LM,
                    started = false,
                    statusMessage = "Final local startup failed",
                ),
                runtimeState = HermesRuntimeManager.RuntimeState(started = false),
            ),
        )
        assertEquals(
            "Remote restart failed",
            settingsRuntimeTransitionFailureMessage(
                backendKind = BackendKind.NONE,
                offlineAirplaneMode = false,
                localBackendStatus = LocalBackendStatus(backendKind = BackendKind.NONE, started = false),
                runtimeState = HermesRuntimeManager.RuntimeState(
                    started = false,
                    error = "Remote restart failed",
                ),
            ),
        )
        assertEquals(
            "Local runtime publication failed",
            settingsRuntimeTransitionFailureMessage(
                backendKind = BackendKind.LITERT_LM,
                offlineAirplaneMode = false,
                localBackendStatus = LocalBackendStatus(
                    backendKind = BackendKind.LITERT_LM,
                    started = true,
                    modelName = "experimental-local-model",
                    baseUrl = "http://127.0.0.1:15436/v1",
                ),
                runtimeState = HermesRuntimeManager.RuntimeState(
                    started = false,
                    error = "Local runtime publication failed",
                ),
            ),
        )
    }

    @Test
    fun saveAgentPersonaPersistsCustomSystemPromptWithoutSecrets() {
        val application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        store.save(AppSettings())
        val viewModel = SettingsViewModel(application)

        viewModel.updateCustomSystemPrompt("Stay concise and prefer local diagnostics first.")
        viewModel.saveAgentPersona()

        assertEquals(
            "Stay concise and prefer local diagnostics first.",
            store.load().customSystemPrompt,
        )
        assertTrue(viewModel.uiState.value.status.contains("Agent persona saved"))
    }

    @Test
    fun saveModelGenerationConfigPersistsSamplingAndApiOptIn() {
        val application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        store.save(AppSettings())
        val viewModel = SettingsViewModel(application)

        viewModel.updateLocalModelMaxTokens(3072)
        viewModel.updateLocalModelTopK(72)
        viewModel.updateLocalModelTopP(0.85f)
        viewModel.updateLocalModelTemperature(0.6f)
        viewModel.updateLocalModelAccelerator("gpu")
        viewModel.updateApiGenerationKnobsEnabled(true)
        viewModel.updateCustomSystemPrompt("Prefer concise local model replies.")
        viewModel.saveModelGenerationConfig()

        val reloaded = store.load()
        assertEquals(3072, reloaded.localModelMaxTokens)
        assertEquals(72, reloaded.localModelTopK)
        assertEquals(0.85f, reloaded.localModelTopP, 0.0001f)
        assertEquals(0.6f, reloaded.localModelTemperature, 0.0001f)
        assertEquals("gpu", reloaded.localModelAccelerator)
        assertTrue(reloaded.apiGenerationKnobsEnabled)
        assertEquals("Prefer concise local model replies.", reloaded.customSystemPrompt)
        assertTrue(viewModel.uiState.value.status.contains("Model configuration saved"))
    }

    @Test
    fun everyAppearancePresetPopulatesTheDraftAndPersistsAllFivePaletteFields() {
        val application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        val original = store.load()

        try {
            store.save(
                original.copy(
                    themePrimaryHex = "#123456",
                    themeSecondaryHex = "#234567",
                    themeBackgroundHex = "#345678",
                    themeSurfaceHex = "#456789",
                    themeSurfaceVariantHex = "#56789A",
                ),
            )
            val viewModel = SettingsViewModel(application)

            appearanceThemePresets.forEach { preset ->
                assertTrue(
                    "${preset.id} must start from a different persisted palette",
                    !store.load().matchesPalette(preset),
                )

                viewModel.applyThemePreset(preset)
                assertUiStateMatchesPalette("${preset.id} draft", preset, viewModel.uiState.value)

                viewModel.saveAppearance()
                store.invalidateCache()
                assertSettingsMatchPalette("${preset.id} persisted", preset, store.load())
            }
        } finally {
            store.save(original)
        }
    }

    @Test
    fun everyShapeAndFontStateUpdatesDraftAndPersistsExactPair() {
        val application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        val original = store.load()
        val canonicalTargets = appearanceCardShapes.map { shape ->
            ShapeFontTarget(shape, expectedRegressionFontScale(shape))
        }
        val targets = canonicalTargets.filterNot { it.shape == "rounded" } +
            canonicalTargets.single { it.shape == "rounded" }

        try {
            store.save(
                original.copy(
                    themeCardShape = "rounded",
                    uiFontScale = AppSettings.DEFAULT_UI_FONT_SCALE,
                ),
            )
            val viewModel = SettingsViewModel(application)

            targets.forEach { target ->
                val draftBefore = viewModel.uiState.value
                val storedBefore = store.load()
                assertTrue(
                    "$target must start from a different draft pair",
                    !draftBefore.matches(target),
                )
                assertTrue(
                    "$target must start from a different persisted pair",
                    !storedBefore.matches(target),
                )

                viewModel.updateThemeCardShape(target.shape)
                viewModel.updateUiFontScale(target.fontScale)

                val draft = viewModel.uiState.value
                assertEquals("$target draft shape", target.shape, draft.themeCardShape)
                assertEquals("$target draft font scale", target.fontScale, draft.uiFontScale, 0.0001f)

                store.invalidateCache()
                val beforeSave = store.load()
                assertEquals(
                    "$target visible shape action must persist the selected shape",
                    target.shape,
                    beforeSave.themeCardShape,
                )
                assertTrue(
                    "$target pair must not be fully persisted before Save",
                    !beforeSave.matches(target),
                )

                viewModel.saveAppearance()
                store.invalidateCache()
                val persisted = store.load()
                assertEquals("$target persisted shape", target.shape, persisted.themeCardShape)
                assertEquals(
                    "$target persisted font scale",
                    target.fontScale,
                    persisted.uiFontScale,
                    0.0001f,
                )
            }
        } finally {
            store.save(original)
        }
    }

    @Test
    fun completedDownloadRuntimeHandoffIsDurableBeforeTheMethodReturns() {
        val application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(application)
        val original = store.load()

        try {
            store.save(original.copy(onDeviceBackend = BackendKind.NONE.persistedValue))
            val viewModel = SettingsViewModel(application)

            assertTrue(viewModel.startLocalRuntimeForFlavor("LiteRT-LM"))

            store.invalidateCache()
            assertEquals(BackendKind.LITERT_LM.persistedValue, store.load().onDeviceBackend)
        } finally {
            store.save(original)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun openProviderKeyPageUsesExternalBrowserForProviderSetupUrls() {
        val application = RuntimeEnvironment.getApplication()
        val viewModel = SettingsViewModel(application)

        viewModel.openProviderKeyPage("https://docs.qwencloud.com/api-reference/preparation/api-key")

        val started = Shadows.shadowOf(application).nextStartedActivity
        val wrapped = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        assertEquals(Intent.ACTION_VIEW, wrapped?.action)
        assertEquals(
            "https://docs.qwencloud.com/api-reference/preparation/api-key",
            wrapped?.data.toString(),
        )
        assertEquals(
            application.packageName,
            wrapped?.getStringExtra(Browser.EXTRA_APPLICATION_ID),
        )
        assertNull(wrapped?.`package`)
        assertTrue(viewModel.uiState.value.status.contains("in your browser"))
    }

    private fun assertUiStateMatchesPalette(
        stage: String,
        preset: AppearanceThemePreset,
        state: SettingsUiState,
    ) {
        assertEquals("$stage primary", preset.primaryHex, state.themePrimaryHex)
        assertEquals("$stage secondary", preset.secondaryHex, state.themeSecondaryHex)
        assertEquals("$stage background", preset.backgroundHex, state.themeBackgroundHex)
        assertEquals("$stage surface", preset.surfaceHex, state.themeSurfaceHex)
        assertEquals("$stage surface variant", preset.surfaceVariantHex, state.themeSurfaceVariantHex)
    }

    private fun assertSettingsMatchPalette(
        stage: String,
        preset: AppearanceThemePreset,
        settings: AppSettings,
    ) {
        assertEquals("$stage primary", preset.primaryHex, settings.themePrimaryHex)
        assertEquals("$stage secondary", preset.secondaryHex, settings.themeSecondaryHex)
        assertEquals("$stage background", preset.backgroundHex, settings.themeBackgroundHex)
        assertEquals("$stage surface", preset.surfaceHex, settings.themeSurfaceHex)
        assertEquals("$stage surface variant", preset.surfaceVariantHex, settings.themeSurfaceVariantHex)
    }

    private fun AppSettings.matchesPalette(preset: AppearanceThemePreset): Boolean {
        return themePrimaryHex.equals(preset.primaryHex, ignoreCase = true) &&
            themeSecondaryHex.equals(preset.secondaryHex, ignoreCase = true) &&
            themeBackgroundHex.equals(preset.backgroundHex, ignoreCase = true) &&
            themeSurfaceHex.equals(preset.surfaceHex, ignoreCase = true) &&
            themeSurfaceVariantHex.equals(preset.surfaceVariantHex, ignoreCase = true)
    }

    private data class ShapeFontTarget(
        val shape: String,
        val fontScale: Float,
    )

    private fun expectedRegressionFontScale(shape: String): Float = when (shape) {
        "rounded" -> AppSettings.DEFAULT_UI_FONT_SCALE
        "soft" -> AppSettings.MIN_UI_FONT_SCALE
        "square" -> AppSettings.MAX_UI_FONT_SCALE
        else -> error("No shape/font regression scale is defined for canonical shape '$shape'")
    }

    private fun SettingsUiState.matches(target: ShapeFontTarget): Boolean {
        return themeCardShape == target.shape &&
            kotlin.math.abs(uiFontScale - target.fontScale) < 0.0001f
    }

    private fun AppSettings.matches(target: ShapeFontTarget): Boolean {
        return themeCardShape == target.shape &&
            kotlin.math.abs(uiFontScale - target.fontScale) < 0.0001f
    }
}
