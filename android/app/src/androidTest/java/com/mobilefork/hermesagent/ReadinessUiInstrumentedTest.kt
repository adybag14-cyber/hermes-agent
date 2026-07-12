package com.mobilefork.hermesagent

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.data.AppSettingsStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadinessUiInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun stoppedPythonIsRenderedAsIdleWithoutColdStartingIt() {
        assumeTrue(
            "Pass -e run_live_readiness true in a fresh instrumentation process",
            InstrumentationRegistry.getArguments().getString("run_live_readiness") == "true",
        )
        assertFalse("Instrumentation process must begin with Python stopped", Python.isStarted())

        val settingsStore = AppSettingsStore(app)
        val originalSettings = settingsStore.load()
        settingsStore.save(
            originalSettings.copy(
                provider = "custom",
                baseUrl = "",
                onDeviceBackend = BackendKind.LITERT_LM.persistedValue,
            ),
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                composeRule.waitUntil(timeoutMillis = 60_000L) {
                    composeRule.onAllNodesWithText("Python idle", substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("HermesChatReadinessStrip").assertIsDisplayed()
                composeRule.onAllNodesWithText("Python booting", substring = true)
                    .fetchSemanticsNodes()
                    .let { nodes -> assertTrue(nodes.isEmpty()) }
                assertFalse("Readiness polling cold-started Python", Python.isStarted())
            }
        } finally {
            settingsStore.save(originalSettings)
        }
    }
}
