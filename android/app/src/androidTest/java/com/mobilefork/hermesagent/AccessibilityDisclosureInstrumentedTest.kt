package com.mobilefork.hermesagent

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilefork.hermesagent.device.HermesAccessibilityController
import com.mobilefork.hermesagent.privacy.AccessibilityDisclosureActivity
import com.mobilefork.hermesagent.privacy.PrivacyConsentStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityDisclosureInstrumentedTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test fun decliningDoesNotGrantConsentAndAcceptingDoesNotGrantAndroidPermission() {
        assertFalse("The accessibility flow belongs only to the full edition", BuildConfig.HERMES_PLAY_EDITION)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PrivacyConsentStore(context)
        store.revokeAccessibility()
        val originalServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        Intents.init()
        try {
            ActivityScenario.launch<AccessibilityDisclosureActivity>(AccessibilityDisclosureActivity.intent(context)).use { scenario ->
                ui.onNodeWithTag("AccessibilityDisclosure").assertIsDisplayed()
                ui.onNodeWithTag("AccessibilityConsentDecline").performClick()
                ui.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
                assertFalse(store.accessibilityAllowed())
                assertFalse(HermesAccessibilityController.isServiceConnected())
            }
            ActivityScenario.launch<AccessibilityDisclosureActivity>(AccessibilityDisclosureActivity.intent(context)).use { scenario ->
                ui.onNodeWithTag("AccessibilityConsentAccept").performClick()
                ui.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
                assertTrue(store.accessibilityAllowed())
                Intents.intended(hasAction(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                assertEquals(originalServices, Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
                HermesAccessibilityController.revokeConsent(context)
                assertFalse(store.accessibilityAllowed())
                assertFalse(HermesAccessibilityController.isServiceConnected())
            }
        } finally { Intents.release(); store.revokeAccessibility() }
    }
}
