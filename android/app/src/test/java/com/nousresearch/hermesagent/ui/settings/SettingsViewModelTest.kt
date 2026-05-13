package com.nousresearch.hermesagent.ui.settings

import android.content.Intent
import android.provider.Browser
import org.junit.Assert.assertEquals
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
    fun openProviderKeyPageUsesExternalBrowserForProviderSetupUrls() {
        val application = RuntimeEnvironment.getApplication()
        val viewModel = SettingsViewModel(application)

        viewModel.openProviderKeyPage("https://docs.qwencloud.com/api-reference/preparation/api-key")

        val started = Shadows.shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(
            "https://docs.qwencloud.com/api-reference/preparation/api-key",
            started.data.toString(),
        )
        assertEquals(
            application.packageName,
            started.getStringExtra(Browser.EXTRA_APPLICATION_ID),
        )
        assertTrue(viewModel.uiState.value.status.contains("in your browser"))
    }
}
