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
}
