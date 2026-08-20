package com.mobilefork.hermesagent.ui.i18n

import com.mobilefork.hermesagent.ui.settings.LocalModelDownloadsViewModel
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RecommendedLocalModelI18nTest {
    @Test
    fun everyCurrentRecommendedModelHasLocalizedDescriptionAndCompatibilityLabel() {
        AppLanguage.entries.filterNot { it == AppLanguage.ENGLISH }.forEach { language ->
            val strings = hermesStringsFor(language)
            LocalModelDownloadsViewModel.recommendedModelPresets.forEach { preset ->
                assertNotEquals(
                    "$language must localize the ${preset.id} description",
                    preset.description,
                    strings.recommendedLocalModelDescription(preset.id, preset.description),
                )
                assertNotEquals(
                    "$language must localize the ${preset.id} compatibility label",
                    preset.testedLabel,
                    strings.recommendedLocalModelTestedLabel(preset.id, preset.testedLabel),
                )
            }
        }
    }
}
