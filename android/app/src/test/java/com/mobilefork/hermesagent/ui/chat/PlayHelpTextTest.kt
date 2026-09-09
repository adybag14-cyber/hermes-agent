package com.mobilefork.hermesagent.ui.chat

import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.PlaySettingsText
import com.mobilefork.hermesagent.ui.i18n.hermesStringsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayHelpTextTest {
    @Test
    fun playHelpDescribesPackagedInferenceAndExistingCredentialsInAllLanguages() {
        val english = hermesStringsFor(AppLanguage.ENGLISH)
        AppLanguage.entries.forEach { language ->
            val strings = hermesStringsFor(language)
            assertEquals(strings.llamaCppDescription, PlaySettingsText.localModelHelp(strings, false))
            assertEquals(strings.remoteFallbackDescription(), PlaySettingsText.remoteProviderHelp(strings, false))
            assertNotEquals(strings.llamaCppDescription, PlaySettingsText.localModelHelp(strings, true))
            assertNotEquals(strings.remoteFallbackDescription(), PlaySettingsText.remoteProviderHelp(strings, true))
            if (language != AppLanguage.ENGLISH) {
                assertNotEquals(PlaySettingsText.localModelHelp(english, true), PlaySettingsText.localModelHelp(strings, true))
                assertNotEquals(PlaySettingsText.remoteProviderHelp(english, true), PlaySettingsText.remoteProviderHelp(strings, true))
            }
        }
        assertTrue(PlaySettingsText.localModelHelp(english, true).contains("packaged"))
        assertTrue(PlaySettingsText.remoteProviderHelp(english, true).contains("existing API credential"))
    }
}
