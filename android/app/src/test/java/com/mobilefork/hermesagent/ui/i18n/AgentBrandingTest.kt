package com.mobilefork.hermesagent.ui.i18n

import android.content.res.Configuration
import com.mobilefork.hermesagent.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class AgentBrandingTest {
    @Test
    fun installedAppAndAndroidServiceLabelsUseTheIndependentBrandInEveryLanguage() {
        val application = RuntimeEnvironment.getApplication()
        for (tag in listOf("en", "zh", "es", "de", "pt", "fr")) {
            val config = Configuration(application.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            val context = application.createConfigurationContext(config)
            assertEquals("Agent", context.getString(R.string.app_name))
            for (field in R.string::class.java.fields.filter { it.type == Int::class.javaPrimitiveType }) {
                val value = context.getString(field.getInt(null))
                assertFalse("$tag/${field.name}: $value", value.contains("Hermes", ignoreCase = true))
            }
        }
    }

    @Test
    fun modelSelectionAndSafetyActionsHaveLocalizedCopy() {
        for (language in AppLanguage.entries) {
            for (key in listOf("choose", "choose_help", "import", "formats", "installed", "empty",
                "download", "download_help", "download_options", "download_options_help", "provider",
                "provider_help", "generation", "generation_help", "runtime", "runtime_help", "advanced",
                "advanced_help", "sharing", "sharing_help", "expanded", "collapsed", "remove_title",
                "remove_help", "cancel")) {
                val value = modelSettingsText(language, key)
                assertTrue("$language/$key", value.isNotBlank())
                assertFalse(value.contains("Hermes", ignoreCase = true))
            }
        }
    }
}
