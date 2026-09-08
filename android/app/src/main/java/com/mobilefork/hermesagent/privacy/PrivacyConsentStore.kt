package com.mobilefork.hermesagent.privacy

import android.content.Context
import com.mobilefork.hermesagent.BuildConfig

/** Versioned consent is separate from Android's permission switch and never inferred from it. */
class PrivacyConsentStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("privacy-consent-v1", Context.MODE_PRIVATE)

    fun accessibilityAllowed(): Boolean = !BuildConfig.HERMES_PLAY_EDITION &&
        preferences.getInt("accessibility-disclosure", 0) == ACCESSIBILITY_REVISION

    fun acceptAccessibility() {
        check(!BuildConfig.HERMES_PLAY_EDITION) { "Accessibility automation is unavailable in the Play edition" }
        check(preferences.edit().putInt("accessibility-disclosure", ACCESSIBILITY_REVISION)
            .putLong("accessibility-accepted-at", System.currentTimeMillis()).commit())
    }

    fun revokeAccessibility() {
        check(preferences.edit().remove("accessibility-disclosure").remove("accessibility-accepted-at").commit())
    }

    companion object { const val ACCESSIBILITY_REVISION = 1 }
}
