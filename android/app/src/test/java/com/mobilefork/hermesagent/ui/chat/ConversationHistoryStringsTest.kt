package com.mobilefork.hermesagent.ui.chat

import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.ConversationHistoryText
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationHistoryStringsTest {
    @Test
    fun historyActionsAndLocalOnlyDisclosureAreTranslatedInEverySupportedLanguage() {
        ConversationHistoryText.entries.forEach { text ->
            AppLanguage.entries.forEach { language ->
                val translated = text.inLanguage(language)
                assertTrue(translated.isNotBlank())
                if (language != AppLanguage.ENGLISH) assertNotEquals(text.inLanguage(AppLanguage.ENGLISH), translated)
            }
        }
    }
}
