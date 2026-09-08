package com.mobilefork.hermesagent.privacy

import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.ui.chat.RemoteChatConsentCoordinator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class RemoteProcessingConsentTest {
    @Test
    fun declineDoesNotSendAndAcceptBindsOnlyTheDisplayedProviderEndpoint() {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettingsStore(app)
        val configured = settings.load().copy(provider = "openrouter", baseUrl = "https://openrouter.ai/api/v1")
        settings.save(configured)
        val coordinator = RemoteChatConsentCoordinator(app)
        val store = RemoteProcessingConsentStore(app)
        val target = requireNotNull(RemoteProcessingTarget.fromSettings(configured))
        assertFalse(coordinator.admit("hello", emptyList(), "session"))
        assertEquals(target, coordinator.target.value)
        coordinator.decline()
        assertNull(coordinator.accept())
        assertFalse(store.allowed(target))
        assertFalse(coordinator.admit("reviewed message", emptyList(), "session"))
        assertEquals("reviewed message", coordinator.accept()?.text)
        assertTrue(coordinator.admit("next message", emptyList(), "session"))
        settings.save(configured.copy(baseUrl = "https://different.example/v1"))
        assertFalse(coordinator.admit("not sent", emptyList(), "session"))
        assertFalse(store.allowed(requireNotNull(coordinator.target.value)))
        val previousRevision = RemoteProcessingConsentStore.revocations.value
        store.revokeAll()
        assertTrue(RemoteProcessingConsentStore.revocations.value > previousRevision)
        assertFalse(store.allowed(target))
    }

    @Test
    fun onDeviceInferenceNeedsNoRemoteConsentAndUnsafeEndpointDisplayIsRejected() {
        assertNull(RemoteProcessingTarget.fromSettings(AppSettings(onDeviceBackend = "litert-lm")))
        listOf("https://secret@example.com/v1", "https://example.com/v1?api_key=secret", "not a url").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                RemoteProcessingTarget.fromSettings(AppSettings(baseUrl = url))
            }
        }
        val canonical = RemoteProcessingTarget.fromSettings(AppSettings(baseUrl = "https://EXAMPLE.com:443/v1/"))
        val equivalent = RemoteProcessingTarget.fromSettings(AppSettings(baseUrl = "https://example.com/v1"))
        assertEquals(canonical, equivalent)
        assertEquals(
            RemoteProcessingTarget.fromSettings(AppSettings(baseUrl = "http://localhost:11434/v1")),
            RemoteProcessingTarget.fromSettings(AppSettings(baseUrl = "localhost:11434")),
        )
    }
}
