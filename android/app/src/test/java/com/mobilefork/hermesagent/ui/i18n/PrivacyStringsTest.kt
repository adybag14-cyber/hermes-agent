package com.mobilefork.hermesagent.ui.i18n

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyStringsTest {
    @Test
    fun disclosureAndEditionFlowsTranslateEveryUserDecision() {
        fun values(language: AppLanguage): List<String> {
            val strings = hermesStringsFor(language)
            return listOf(strings.accessibilityDisclosureTitle(), strings.accessibilityDisclosureBody(),
                strings.acceptAccessibilityDisclosure(), strings.revokeAccessibilityDisclosure(),
                strings.remoteProcessingTitle(), strings.remoteProcessingDisclosure(),
                strings.acceptRemoteProcessing(), strings.revokeRemoteProcessing(),
                strings.playEditionSummary(), PlaySettingsText.saved(language),
                PlaySettingsText.packagedEngine(language), PlaySettingsText.conventionalCache(language),
                VoicePrivacyText.title(language), VoicePrivacyText.body(language), VoicePrivacyText.accept(language),
                LocalPrivacyText.policy(language), LocalPrivacyText.delete(language),
                LocalPrivacyText.warning(language), LocalPrivacyText.accountScope(language),
                LocalPrivacyText.deletionDenied(language), strings.settingsPageLabel("Privacy"))
        }
        val english = values(AppLanguage.ENGLISH)
        AppLanguage.entries.filter { it != AppLanguage.ENGLISH }.forEach { language ->
            values(language).forEachIndexed { index, translated ->
                assertTrue(translated.isNotBlank())
                assertNotEquals("$language disclosure $index", english[index], translated)
            }
        }
    }

    @Test
    fun everyPrivacyActionAndDisclosureHasSixNonEmptyTranslations() {
        PrivacyText.entries.forEach { text ->
            val english = text.inLanguage(AppLanguage.ENGLISH)
            AppLanguage.entries.forEach { language ->
                val translated = text.inLanguage(language)
                assertTrue(translated.isNotBlank())
                // Violence is spelled identically in English and French, an actual translation.
                if (language != AppLanguage.ENGLISH && !(language == AppLanguage.FRENCH && text == PrivacyText.VIOLENCE)) {
                    assertNotEquals("$text: $language", english, translated)
                }
            }
        }
    }
}
