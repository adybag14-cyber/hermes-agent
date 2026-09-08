package com.mobilefork.hermesagent.privacy

import android.provider.Settings
import com.mobilefork.hermesagent.BuildConfig
import com.mobilefork.hermesagent.device.HermesAccessibilityController
import com.mobilefork.hermesagent.device.HermesAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class PrivacyConsentStoreTest {
    @Test
    fun androidPermissionIsNotConsentAndRevocationDisconnectsController() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${app.packageName}/${HermesAccessibilityService::class.java.name}")
        val store = PrivacyConsentStore(app)
        assertFalse(store.accessibilityAllowed())
        val service = Robolectric.buildService(HermesAccessibilityService::class.java).create().get()
        HermesAccessibilityController.bind(service)
        assertFalse(HermesAccessibilityController.isServiceConnected())
        if (BuildConfig.HERMES_PLAY_EDITION) {
            assertThrows(IllegalStateException::class.java) { store.acceptAccessibility() }
        } else {
            store.acceptAccessibility()
            assertTrue(PrivacyConsentStore(app).accessibilityAllowed())
            assertTrue(HermesAccessibilityController.isServiceConnected())
            HermesAccessibilityController.revokeConsent(app)
            assertFalse(PrivacyConsentStore(app).accessibilityAllowed())
            assertFalse(HermesAccessibilityController.isServiceConnected())
            assertEquals("", HermesAccessibilityController.currentForegroundPackageName())
        }
        HermesAccessibilityController.unbind(service)
    }

    @Test
    fun futureOrObsoleteDisclosureVersionDoesNotAuthorizeDataAccess() {
        val app = RuntimeEnvironment.getApplication()
        val preferences = app.getSharedPreferences("privacy-consent-v1", 0)
        listOf(-1, 0, PrivacyConsentStore.ACCESSIBILITY_REVISION + 1).forEach { version ->
            preferences.edit().putInt("accessibility-disclosure", version).commit()
            assertFalse(PrivacyConsentStore(app).accessibilityAllowed())
        }
    }
}
