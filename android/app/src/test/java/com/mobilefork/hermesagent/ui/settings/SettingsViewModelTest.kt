package com.mobilefork.hermesagent.ui.settings

import android.content.Intent
import android.provider.Browser
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
