package com.mobilefork.hermesagent.ui.chat

import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.LocalBackendStatus
import com.mobilefork.hermesagent.data.AppSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ChatReadinessStripTest {
    @Test
    fun stoppedPythonIsReportedAsIdleBecauseTheStripDoesNotStartIt() {
        assertEquals(
            "idle",
            pythonReadinessLabel(pythonReady = false, remoteReadyWithoutPython = false),
        )
    }

    @Test
    fun runningAndOptionalPythonStatesRemainDistinct() {
        assertEquals(
            "up",
            pythonReadinessLabel(pythonReady = true, remoteReadyWithoutPython = false),
        )
        assertEquals(
            "optional",
            pythonReadinessLabel(pythonReady = false, remoteReadyWithoutPython = true),
        )
    }

    @Test
    fun everyReadinessRefreshLoadsTheLatestPersistedBackendSelection() {
        val application = RuntimeEnvironment.getApplication()
        val settingsStore = AppSettingsStore(application)
        val originalSettings = settingsStore.load()

        try {
            settingsStore.save(
                originalSettings.copy(
                    onDeviceBackend = BackendKind.LLAMA_CPP.persistedValue,
                    provider = "openrouter",
                    model = "local-before-switch",
                ),
            )
            val viewModel = ChatReadinessViewModel(application)
            val localSettings = viewModel.loadCurrentSettingsForRefresh()
            assertEquals(BackendKind.LLAMA_CPP.persistedValue, localSettings.onDeviceBackend)
            val staleLocalStatus = LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = true,
                modelName = "local-before-switch",
            )
            assertTrue(
                chatReadinessUiState(
                    settings = localSettings,
                    local = staleLocalStatus,
                    pythonReady = false,
                    memoryCount = 0,
                    hasDirectCredential = true,
                ).ready,
            )

            settingsStore.save(
                settingsStore.load().copy(
                    onDeviceBackend = BackendKind.NONE.persistedValue,
                    provider = "openai",
                    model = "remote-after-switch",
                ),
            )
            val refreshed = viewModel.loadCurrentSettingsForRefresh()
            assertEquals(BackendKind.NONE.persistedValue, refreshed.onDeviceBackend)
            assertEquals("openai", refreshed.provider)
            assertEquals("remote-after-switch", refreshed.model)
            val blockedByStaleLocal = chatReadinessUiState(
                settings = refreshed,
                local = staleLocalStatus,
                pythonReady = false,
                memoryCount = 0,
                hasDirectCredential = true,
            )
            assertFalse(blockedByStaleLocal.ready)
            assertTrue(blockedByStaleLocal.line.contains("waiting for llama.cpp to stop"))

            val remoteReady = chatReadinessUiState(
                settings = refreshed,
                local = LocalBackendStatus(backendKind = BackendKind.NONE, started = false),
                pythonReady = false,
                memoryCount = 0,
                hasDirectCredential = true,
            )
            assertTrue(remoteReady.ready)
            assertTrue(remoteReady.line.contains("remote openai · remote-after-switch"))
        } finally {
            settingsStore.save(originalSettings)
        }
    }
}
