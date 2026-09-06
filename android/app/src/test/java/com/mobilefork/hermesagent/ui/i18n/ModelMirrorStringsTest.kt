package com.mobilefork.hermesagent.ui.i18n

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ModelMirrorStringsTest {
    @Test
    fun everySupportedLanguageHasItsOwnMirrorAndLicenceCopy() {
        val english = hermesStringsFor(AppLanguage.ENGLISH)
        val englishCopy = listOf(english.modelScopeMirrorButton(), english.modelScopeMirrorNote(),
            english.modelScopeResearchNotice(), english.modelScopeLicencesButton())
        for (language in AppLanguage.entries) {
            val strings = hermesStringsFor(language)
            val copy = listOf(strings.modelScopeMirrorButton(), strings.modelScopeMirrorNote(),
                strings.modelScopeResearchNotice(), strings.modelScopeLicencesButton())
            copy.forEach { assertFalse(it.isBlank()) }
            if (language != AppLanguage.ENGLISH) {
                copy.zip(englishCopy).forEach { (actual, fallback) -> assertNotEquals(fallback, actual) }
            }
        }
    }
}
