package com.mobilefork.hermesagent

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.device.HermesProviderSetupWebActivity
import com.mobilefork.hermesagent.device.HermesTaskerConditionBridge
import com.mobilefork.hermesagent.device.HermesTaskerConditionEditActivity
import com.mobilefork.hermesagent.device.HermesTaskerEventBridge
import com.mobilefork.hermesagent.device.HermesTaskerEventEditActivity
import com.mobilefork.hermesagent.device.HermesTaskerPluginEditActivity
import com.mobilefork.hermesagent.ui.boot.BootUiState
import com.mobilefork.hermesagent.ui.device.DevicePage
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.HermesStrings
import com.mobilefork.hermesagent.ui.i18n.hermesStringsFor
import com.mobilefork.hermesagent.ui.settings.AppearanceThemePreset
import com.mobilefork.hermesagent.ui.settings.LocalModelDownloadsViewModel
import com.mobilefork.hermesagent.ui.settings.RecommendedLocalModelPreset
import com.mobilefork.hermesagent.ui.settings.SettingsPage
import com.mobilefork.hermesagent.ui.settings.appearanceCardShapes
import com.mobilefork.hermesagent.ui.settings.appearanceThemePresets
import com.mobilefork.hermesagent.ui.settings.recommendedLocalModelCardTestTag
import com.mobilefork.hermesagent.ui.shell.AppSection
import com.mobilefork.hermesagent.ui.shell.AppShellScreen
import com.mobilefork.hermesagent.ui.theme.hermesLocalizedContext
import com.mobilefork.hermesagent.ui.theme.hermesViewBackdropDrawable
import com.mobilefork.hermesagent.ui.theme.hermesViewPalette
import com.mobilefork.hermesagent.ui.theme.hermesViewPanelDrawable
import com.mobilefork.hermesagent.ui.theme.resolveHermesViewNavigationBarColor
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Headed completion evidence bound to the canonical release identity. Run this class once on a
 * phone AVD and once on a tablet AVD with the expected_ui_profile instrumentation argument, then pull
 * files/hermes-ui-visuals after each run. Every PNG has a semantics or View-hierarchy companion
 * carrying the exact profile, language, saved palette, card shape and Hermes UI font scale.
 */
@RunWith(AndroidJUnit4::class)
class HermesUiCoverageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    private val settingsStore: AppSettingsStore
        get() = AppSettingsStore(app)

    private var originalSettings: AppSettings? = null
    private var originalDownloads: List<LocalModelDownloadRecord>? = null
    private var originalPreferredDownloadId: String? = null
    private val capturedEvidence = mutableListOf<EvidenceArtifact>()

    @After
    fun tearDown() {
        val downloadStore = LocalModelDownloadStore(app)
        var restoreFailure: Throwable? = null
        fun restore(block: () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                val firstFailure = restoreFailure
                if (firstFailure == null) {
                    restoreFailure = failure
                } else {
                    firstFailure.addSuppressed(failure)
                }
            }
        }
        originalDownloads?.let { downloads -> restore { downloadStore.saveDownloads(downloads) } }
        originalPreferredDownloadId?.let { preferred -> restore { downloadStore.setPreferredDownloadId(preferred) } }
        originalSettings?.let { settings -> restore { settingsStore.save(settings) } }
        restoreFailure?.let { throw it }
    }

    @Test
    fun capturesEveryDestinationDevicePageThemeAndFrameworkActivityAtCurrentWidth() {
        val identity = releaseIdentity
        val profile = currentProfile()
        assertReleaseUiContracts()
        val prefix = "headed-${identity.evidenceRunId}-profile-$profile"
        clearEvidencePrefix(prefix)
        prepareDeterministicBaseline(AppLanguage.ENGLISH)
        setShellContent("complete-profile-$profile")
        val strings = hermesStringsFor(AppLanguage.ENGLISH)

        AppSection.entries.forEachIndexed { index, section ->
            if (section != AppSection.Hermes) {
                navigateToShellSection("HermesNav${section.name}")
            }
            val sentinels = assertSectionSentinels(section, strings)
            captureComposeEvidence(
                identity = "section:${section.name}",
                name = "$prefix-section-${index + 1}-${section.name.lowercase(Locale.ROOT)}",
                coverageKind = "app-section",
                pageId = section.name,
                language = AppLanguage.ENGLISH,
                themeId = "hermes",
                sentinels = sentinels,
            )
        }

        captureEverySettingsPage(prefix)

        navigateToShellSection("HermesNavDevice")
        DevicePage.entries.filterNot { it == DevicePage.Overview }.forEachIndexed { index, page ->
            val pageName = page.name
            val route = page.route
            val slug = page.name.lowercase(Locale.ROOT)
            val pageTag = "HermesDevicePage_$pageName"
            composeRule.onNodeWithTag(pageTag).performClick()
            composeRule.onNodeWithText(route).assertIsDisplayed()
            captureComposeEvidence(
                identity = "device:${page.name}",
                name = "$prefix-device-${index + 2}-$slug",
                coverageKind = "device-subpage",
                pageId = pageName,
                language = AppLanguage.ENGLISH,
                themeId = "hermes",
                sentinels = listOf(pageTag, route),
            )
        }

        openAppearancePage(strings)
        // Start with a palette that differs from the deterministic Hermes baseline and put Hermes
        // last. That makes every apply/save predicate transition from false to true instead of
        // letting the first iteration pass without proving either UI action executed.
        val presetEvidenceOrder = appearanceThemePresets.drop(1) + appearanceThemePresets.first()
        presetEvidenceOrder.forEachIndexed { index, preset ->
            val label = strings.appearancePresetLabel(preset.id, preset.label)
            val expected = preset.appearancePalette()
            assertAppearancePreconditionDiffers(preset, expected)
            selectAppearancePresetAndAwaitDraft(preset, expected)
            saveAppearanceAndAwait(preset, expected)
            composeRule.onNodeWithTag(appearancePresetTag(preset))
                .performScrollTo()
                .assertIsDisplayed()
            captureComposeEvidence(
                identity = "appearance-preset:${preset.id}",
                name = "$prefix-theme-preset-${index + 1}-${preset.id}",
                coverageKind = "appearance-preset",
                pageId = "Settings.Theme",
                language = AppLanguage.ENGLISH,
                themeId = preset.id,
                sentinels = listOf(label),
                verifyThemePixels = true,
            )
        }

        settingsStore.save(
            settingsStore.load().copy(
                themeBackgroundHex = SHAPE_PROOF_BACKGROUND,
                themeSurfaceHex = SHAPE_PROOF_SURFACE,
                themeSurfaceVariantHex = SHAPE_PROOF_SURFACE_VARIANT,
            ),
        )
        reloadPersistedShellSettings()
        openAppearancePage(strings)
        val shapeAndFontThemeId = "shape-proof"
        val renderedCornerDepths = mutableMapOf<String, Int>()
        THEME_SHAPE_FONT_STATES.forEachIndexed { index, state ->
            assertShapeFontPreconditionDiffers(state)
            selectShapeAndFontAndAwaitDraft(state, strings)
            saveShapeFontAndAwait(state)
            scrollAppearanceCardCornerIntoView()
            renderedCornerDepths[state.shape] = renderedAppearanceCornerDepth()
            captureComposeEvidence(
                identity = "shape:${state.shape}",
                name = "$prefix-shape-${index + 1}-${state.shape}",
                coverageKind = "rendered-card-shape",
                pageId = "Settings.Theme",
                language = AppLanguage.ENGLISH,
                themeId = shapeAndFontThemeId,
                sentinels = listOf(strings.appearanceTitle()),
            )

            val fontLabel = strings.uiFontSizeLabel(state.fontScale)
            composeRule.onNodeWithTag("UiFontScaleSlider")
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithTag("UiFontScaleValueLabel")
                .assertIsDisplayed()
                .assertTextEquals(fontLabel)
            assertRenderedFontScale(fontLabel, state.fontScale)
            captureComposeEvidence(
                identity = "font:${state.fontLabel}:${fontScaleSlug(state.fontScale)}",
                name = "$prefix-font-${index + 1}-${state.fontLabel}-${fontScaleSlug(state.fontScale)}",
                coverageKind = "rendered-font-scale",
                pageId = "Settings.Theme",
                language = AppLanguage.ENGLISH,
                themeId = shapeAndFontThemeId,
                sentinels = listOf(fontLabel, "UiFontScaleSlider"),
            )
        }
        assertTrue(
            "Rendered card corners must grow from square to soft to rounded: $renderedCornerDepths",
            requireNotNull(renderedCornerDepths["square"]) < requireNotNull(renderedCornerDepths["soft"]) &&
                requireNotNull(renderedCornerDepths["soft"]) < requireNotNull(renderedCornerDepths["rounded"]),
        )

        val customLight = settingsStore.load().copy(
            themePrimaryHex = CUSTOM_LIGHT_PRIMARY,
            themeSecondaryHex = CUSTOM_LIGHT_SECONDARY,
            themeBackgroundHex = CUSTOM_LIGHT_BACKGROUND,
            themeSurfaceHex = CUSTOM_LIGHT_SURFACE,
            themeSurfaceVariantHex = CUSTOM_LIGHT_SURFACE_VARIANT,
            themeCardShape = "rounded",
            uiFontScale = AppSettings.DEFAULT_UI_FONT_SCALE,
        )
        settingsStore.save(customLight)
        reloadPersistedShellSettings()
        navigateToShellSection("HermesNavHermes")
        composeRule.onNodeWithTag("HermesChatInput").assertIsDisplayed()
        captureComposeEvidence(
            identity = "appearance-custom-light",
            name = "$prefix-theme-custom-light",
            coverageKind = "custom-light-palette",
            pageId = "Hermes",
            language = AppLanguage.ENGLISH,
            themeId = "custom-light",
            sentinels = listOf("HermesChatInput"),
            verifyThemePixels = true,
        )

        captureAllFrameworkActivities(
            prefix = "$prefix-view",
            language = AppLanguage.ENGLISH,
            themeId = "custom-light",
        )

        assertEvidenceManifest(expectedProfileEvidenceIdentities())
        writeInventory("$prefix-inventory.txt", "complete-current-profile", capturedEvidence)
    }

    @Test
    fun legacyAppearancePresetVisiblyReplacesHermesDraftAndPersistsEveryPaletteField() {
        prepareDeterministicBaseline(AppLanguage.ENGLISH)
        setShellContent("appearance-preset-regression")
        openAppearancePage(hermesStringsFor(AppLanguage.ENGLISH))

        val legacy = appearanceThemePresets.single { it.id == "legacy" }
        val expected = legacy.appearancePalette()
        assertAppearancePreconditionDiffers(legacy, expected)
        selectAppearancePresetAndAwaitDraft(legacy, expected)
        saveAppearanceAndAwait(legacy, expected)

        assertEquals(
            "Legacy appearance preset did not remain durable after the visible Save action",
            expected,
            settingsStore.load().appearancePalette(),
        )
    }

    @Test
    fun softMinimumShapeAndFontVisiblyReplaceRoundedDefaultAndPersistTogether() {
        prepareDeterministicBaseline(AppLanguage.ENGLISH)
        settingsStore.save(
            settingsStore.load().copy(
                themeBackgroundHex = SHAPE_PROOF_BACKGROUND,
                themeSurfaceHex = SHAPE_PROOF_SURFACE,
                themeSurfaceVariantHex = SHAPE_PROOF_SURFACE_VARIANT,
            ),
        )
        setShellContent("shape-font-regression")
        val strings = hermesStringsFor(AppLanguage.ENGLISH)
        openAppearancePage(strings)

        val baseline = AppearanceShapeFont("rounded", AppSettings.DEFAULT_UI_FONT_SCALE)
        assertTrue(
            "Shape/font regression requires the deterministic rounded/default draft; " +
                shapeFontStateDiagnostic(baseline),
            appearanceDraftShapeFont().matches(baseline),
        )
        assertTrue(
            "Shape/font regression requires the deterministic rounded/default store; " +
                shapeFontStateDiagnostic(baseline),
            settingsStore.load().appearanceShapeFont().matches(baseline),
        )
        scrollAppearanceCardCornerIntoView()
        val roundedCornerDepth = renderedAppearanceCornerDepth()

        val target = THEME_SHAPE_FONT_STATES.first()
        assertShapeFontPreconditionDiffers(target)
        selectShapeAndFontAndAwaitDraft(target, strings)
        saveShapeFontAndAwait(target)
        scrollAppearanceCardCornerIntoView()
        val softCornerDepth = renderedAppearanceCornerDepth()

        assertTrue(
            "Soft/minimum shape and font did not remain durable after the visible Save action; " +
                shapeFontStateDiagnostic(target.appearanceShapeFont()),
            settingsStore.load().appearanceShapeFont().matches(target.appearanceShapeFont()),
        )
        assertTrue(
            "Rendered soft card corner must enter its surface before rounded: " +
                "soft=$softCornerDepth, rounded=$roundedCornerDepth",
            softCornerDepth < roundedCornerDepth,
        )
    }

    @Test
    fun firstAndLastRecommendedModelCardsScrollVisiblyInsideTheOversizedDownloadsSection() {
        prepareDeterministicBaseline(AppLanguage.ENGLISH)
        setShellContent("recommended-model-scroll-regression")
        val strings = hermesStringsFor(AppLanguage.ENGLISH)
        selectLanguage(AppLanguage.ENGLISH, strings)
        composeRule.onNodeWithTag("HermesSettingsPage_Models").performClick()
        composeRule.waitForIdle()
        val presets = LocalModelDownloadsViewModel.recommendedModelPresets
        scrollSettingsToTag(recommendedLocalModelCardTestTag(presets.first().id))
        listOf(presets.first(), presets.last()).distinctBy { preset -> preset.id }.forEach { preset ->
            assertRecommendedModelCardVisible(
                preset = preset,
                description = strings.recommendedLocalModelDescription(preset.id, preset.description),
                runtimeAndTestedLabel =
                    "${preset.runtimeFlavor} · ${strings.recommendedLocalModelTestedLabel(preset.id, preset.testedLabel)}",
                context = "focused English first/last-card regression",
            )
        }
    }

    @Test
    fun capturesSixLanguageRecommendedModelsAndPhoneOnlyLocalizedFrameworkActivities() {
        assumeTrue(
            "Localized framework screenshots are intentionally phone-only; current width is " +
                "${app.resources.configuration.screenWidthDp}dp",
            app.resources.configuration.screenWidthDp < TABLET_WIDTH_DP,
        )
        val identity = releaseIdentity
        val profile = currentProfile()
        assertReleaseUiContracts()
        val prefix = "headed-${identity.evidenceRunId}-localized-$profile"
        clearEvidencePrefix(prefix)
        prepareDeterministicBaseline(AppLanguage.ENGLISH)
        setShellContent("complete-localization-$profile")
        val targetPresets = LocalModelDownloadsViewModel.recommendedModelPresets
        assertEquals(
            "Recommended model evidence identities must be unique",
            targetPresets.size,
            targetPresets.map { it.id }.toSet().size,
        )

        AppLanguage.entries.forEach { language ->
            val strings = hermesStringsFor(language)
            assertComposeHostForeground("localized model Compose phase for ${language.tag}")
            selectLanguage(language, strings)
            composeRule.onNodeWithTag("HermesSettingsPage_Models").performClick()
            composeRule.waitForIdle()
            scrollSettingsToTag(recommendedLocalModelCardTestTag(targetPresets.first().id))

            targetPresets.forEach { preset ->
                val description = strings.recommendedLocalModelDescription(preset.id, preset.description)
                val testedLabel = strings.recommendedLocalModelTestedLabel(preset.id, preset.testedLabel)
                val runtimeAndTestedLabel = "${preset.runtimeFlavor} · $testedLabel"
                if (language != AppLanguage.ENGLISH) {
                    assertNotEquals("${preset.id} description leaked English in ${language.tag}", preset.description, description)
                    assertNotEquals("${preset.id} tested label leaked English in ${language.tag}", preset.testedLabel, testedLabel)
                }
                assertRecommendedModelCardVisible(
                    preset = preset,
                    description = description,
                    runtimeAndTestedLabel = runtimeAndTestedLabel,
                    context = "localized evidence for ${language.tag}",
                )
                captureComposeEvidence(
                    identity = "localized-model:${language.tag}:${preset.id}",
                    name = "$prefix-lang-${language.tag}-model-${preset.id}",
                    coverageKind = "six-language-recommended-model",
                    pageId = "Settings.Models.${preset.id}",
                    language = language,
                    themeId = "hermes",
                    sentinels = listOf(preset.title, description, runtimeAndTestedLabel),
                )
            }
        }

        // ActivityScenario owns and destroys each framework Activity. Its close() does not
        // contractually restore the createComposeRule host or re-register that host's semantics
        // root. Finish every Compose interaction first, then capture framework pages after no
        // later Compose query is required.
        AppLanguage.entries.filterNot { language -> language == AppLanguage.ENGLISH }.forEach { language ->
            settingsStore.save(settingsStore.load().copy(languageTag = language.tag))
            assertEquals(
                "Framework evidence language did not persist before ${language.tag} capture",
                language.tag,
                settingsStore.load().languageTag,
            )
            assertFrameworkLabelsDifferFromEnglish(language)
            captureAllFrameworkActivities(
                prefix = "$prefix-lang-${language.tag}-view",
                language = language,
                themeId = "hermes",
            )
        }

        assertEvidenceManifest(expectedLocalizedEvidenceIdentities(targetPresets.map { it.id }))
        writeInventory("$prefix-inventory.txt", "six-language-and-framework-localization", capturedEvidence)
    }

    private fun assertRecommendedModelCardVisible(
        preset: RecommendedLocalModelPreset,
        description: String,
        runtimeAndTestedLabel: String,
        context: String,
    ) {
        try {
            composeRule.onNodeWithTag(recommendedLocalModelCardTestTag(preset.id))
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithText(preset.title).assertIsDisplayed()
            composeRule.onNodeWithText(description).assertIsDisplayed()
            composeRule.onNodeWithText(runtimeAndTestedLabel).assertIsDisplayed()
        } catch (failure: AssertionError) {
            throw AssertionError(
                "Recommended model card ${preset.id} did not visibly render for $context; " +
                    "title='${preset.title}', description='$description', " +
                    "runtimeAndTestedLabel='$runtimeAndTestedLabel'",
                failure,
            )
        }
    }

    private fun prepareDeterministicBaseline(language: AppLanguage) {
        if (originalSettings == null) {
            originalSettings = settingsStore.load()
        }
        val downloadStore = LocalModelDownloadStore(app)
        if (originalDownloads == null) {
            originalDownloads = downloadStore.loadDownloads()
            originalPreferredDownloadId = downloadStore.preferredDownloadId()
        }
        downloadStore.apply {
            saveDownloads(emptyList())
            setPreferredDownloadId("")
        }
        settingsStore.save(
            AppSettings(
                provider = "openrouter",
                baseUrl = "https://openrouter.ai/api/v1",
                model = "anthropic/claude-sonnet-4",
                dataSaverMode = true,
                offlineAirplaneMode = true,
                portalEnabled = false,
                onDeviceBackend = BackendKind.NONE.persistedValue,
                languageTag = language.tag,
                chatDisplayMode = "compact",
                themePrimaryHex = AppSettings.DEFAULT_THEME_PRIMARY_HEX,
                themeSecondaryHex = AppSettings.DEFAULT_THEME_SECONDARY_HEX,
                themeBackgroundHex = AppSettings.DEFAULT_THEME_BACKGROUND_HEX,
                themeSurfaceHex = AppSettings.DEFAULT_THEME_SURFACE_HEX,
                themeSurfaceVariantHex = AppSettings.DEFAULT_THEME_SURFACE_VARIANT_HEX,
                themeCardShape = "rounded",
                uiFontScale = AppSettings.DEFAULT_UI_FONT_SCALE,
            ),
        )
    }

    private fun setShellContent(probeResult: String) {
        composeRule.setContent {
            AppShellScreen(
                bootUiState = BootUiState(
                    status = "Hermes backend is ready",
                    ready = true,
                    probeResult = probeResult,
                    baseUrl = "http://127.0.0.1:15436/v1",
                ),
                onRetryHermes = {},
            )
        }
        composeRule.waitForIdle()
    }

    private fun assertSectionSentinels(section: AppSection, strings: HermesStrings): List<String> {
        val visibleTitle = section.takeIf { it != AppSection.Hermes }?.title(strings)
        if (section != AppSection.Hermes) {
            composeRule.onAllNodesWithText(requireNotNull(visibleTitle))[0].assertIsDisplayed()
        }
        return when (section) {
            AppSection.Hermes -> {
                composeRule.onNodeWithTag("HermesChatInput").assertIsDisplayed()
                listOf("HermesChatInput")
            }

            AppSection.Accounts -> {
                composeRule.onNodeWithText(strings.authIntro).assertIsDisplayed()
                listOf(requireNotNull(visibleTitle), strings.authIntro)
            }

            AppSection.NousPortal -> {
                composeRule.onNodeWithText(strings.portalEmbeddedDescription).assertIsDisplayed()
                listOf(requireNotNull(visibleTitle), strings.portalEmbeddedDescription)
            }

            AppSection.Device -> {
                composeRule.onNodeWithTag("HermesDevicePageNavigation").assertIsDisplayed()
                composeRule.onNodeWithText("/device").assertIsDisplayed()
                listOf(requireNotNull(visibleTitle), "HermesDevicePageNavigation", "/device")
            }

            AppSection.Kanban -> {
                composeRule.onNodeWithTag("HermesKanbanScreen").assertIsDisplayed()
                listOf(requireNotNull(visibleTitle), "HermesKanbanScreen")
            }

            AppSection.Terminal -> {
                composeRule.onNodeWithTag("HermesManualTerminalInput").assertIsDisplayed()
                listOf(requireNotNull(visibleTitle), "HermesManualTerminalInput")
            }

            AppSection.Settings -> {
                composeRule.onNodeWithTag("HermesSettingsPageNavigation").assertIsDisplayed()
                listOf(requireNotNull(visibleTitle), "HermesSettingsPageNavigation")
            }
        }
    }

    private fun captureEverySettingsPage(prefix: String) {
        navigateToShellSection("HermesNavSettings")
        SettingsPage.entries.forEachIndexed { index, page ->
            composeRule.onNodeWithTag("HermesSettingsContentList").performScrollToIndex(0)
            val pageTag = "HermesSettingsPage_${page.name}"
            if (page != SettingsPage.Overview) {
                composeRule.onNodeWithTag(pageTag).performClick()
            }
            val sentinel = settingsPageSentinel(page)
            scrollSettingsToTag(sentinel)
            composeRule.onNodeWithTag(sentinel).assertIsDisplayed()
            captureComposeEvidence(
                identity = "settings:${page.name}",
                name = "$prefix-settings-${index + 1}-${page.name.lowercase(Locale.ROOT)}",
                coverageKind = "settings-subpage",
                pageId = "Settings.${page.name}",
                language = AppLanguage.ENGLISH,
                themeId = "hermes",
                sentinels = listOf(sentinel),
            )
        }
    }

    private fun settingsPageSentinel(page: SettingsPage): String = when (page) {
        SettingsPage.Overview -> "SettingsLanguagePicker"
        SettingsPage.Models -> "HermesImportModelButton"
        SettingsPage.Theme -> "HermesAppearanceCardTop"
        SettingsPage.Tools -> "McpExternalRuntimeUnavailable"
    }

    private fun openAppearancePage(strings: HermesStrings) {
        navigateToShellSection("HermesNavSettings")
        composeRule.onNodeWithTag("HermesSettingsContentList").performScrollToIndex(0)
        composeRule.onNodeWithTag("HermesSettingsPage_Theme").performClick()
        scrollSettingsToText(strings.appearanceTitle())
    }

    private fun reloadPersistedShellSettings() {
        // AppShell snapshots settings in Compose state. Re-enter Settings so its production
        // refresh hook consumes values persisted directly by the evidence setup.
        navigateToShellSection("HermesNavHermes")
        navigateToShellSection("HermesNavSettings")
    }

    private fun assertShapeFontPreconditionDiffers(state: ThemeShapeFontState) {
        val expected = state.appearanceShapeFont()
        val draft = appearanceDraftShapeFont()
        val stored = settingsStore.load().appearanceShapeFont()
        assertTrue(
            "Shape/font ${state.shape}/${state.fontScale} draft precondition was vacuous; " +
                shapeFontStateDiagnostic(expected),
            !draft.matches(expected),
        )
        assertTrue(
            "Shape/font ${state.shape}/${state.fontScale} store precondition was vacuous; " +
                shapeFontStateDiagnostic(expected),
            !stored.matches(expected),
        )
        assertTrue(
            "Shape ${state.shape} was already selected before its visible control action; " +
                shapeFontStateDiagnostic(expected),
            draft.shape != expected.shape,
        )
        assertTrue(
            "Font scale ${state.fontScale} was already selected before SetProgress; " +
                shapeFontStateDiagnostic(expected),
            kotlin.math.abs(draft.fontScale - expected.fontScale) >= SHAPE_FONT_TOLERANCE,
        )
    }

    private fun selectShapeAndFontAndAwaitDraft(
        state: ThemeShapeFontState,
        strings: HermesStrings,
    ) {
        val expected = state.appearanceShapeFont()
        val shapeTag = "CardShape-${state.shape}"
        val shapeNode = composeRule.onNodeWithTag(shapeTag)
        shapeNode.performScrollTo().assertIsDisplayed()
        assertEquals(
            "$shapeTag must expose an unselected state before its click; " +
                shapeFontStateDiagnostic(expected),
            false,
            shapeNode.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected),
        )
        shapeNode.performClick()
        awaitSelectedCardShape("select ${state.shape}", expected)
        shapeNode.assertIsDisplayed()
        assertEquals(
            "$shapeTag must expose a selected state after its click; " +
                shapeFontStateDiagnostic(expected),
            true,
            shapeNode.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected),
        )
        assertEquals(
            "Visible $shapeTag selection did not persist the production shape transition; " +
                shapeFontStateDiagnostic(expected),
            state.shape,
            settingsStore.load().themeCardShape,
        )

        val slider = composeRule.onNodeWithTag("UiFontScaleSlider")
        slider.performScrollTo().assertIsDisplayed()
        assertTrue(
            "UiFontScaleSlider must visibly start away from ${state.fontScale}; " +
                shapeFontStateDiagnostic(expected),
            kotlin.math.abs(appearanceDraftUiFontScale() - state.fontScale) >= SHAPE_FONT_TOLERANCE,
        )
        slider.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            assertTrue("Slider rejected ${state.fontScale}", setProgress(state.fontScale))
        }

        awaitAppearanceShapeFont("select ${state.shape}/${state.fontScale}", expected) {
            appearanceDraftShapeFont()
        }
        slider.assertIsDisplayed()
        composeRule.onNodeWithTag("UiFontScaleValueLabel")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals(strings.uiFontSizeLabel(state.fontScale))
        assertTrue(
            "Visible shape/font draft did not transition to the exact target; " +
                shapeFontStateDiagnostic(expected),
            appearanceDraftShapeFont().matches(expected),
        )
    }

    private fun saveShapeFontAndAwait(state: ThemeShapeFontState) {
        val expected = state.appearanceShapeFont()
        assertTrue(
            "Refusing to save before the visible shape/font draft matches; " +
                shapeFontStateDiagnostic(expected),
            appearanceDraftShapeFont().matches(expected),
        )
        val storedBeforeSave = settingsStore.load().appearanceShapeFont()
        assertEquals(
            "Selected card shape must be persisted before the combined Save; " +
                shapeFontStateDiagnostic(expected),
            expected.shape,
            storedBeforeSave.shape,
        )
        assertTrue(
            "Font scale must remain draft-only until Save; " +
                shapeFontStateDiagnostic(expected),
            kotlin.math.abs(storedBeforeSave.fontScale - expected.fontScale) >= SHAPE_FONT_TOLERANCE,
        )
        composeRule.onNodeWithTag("SaveAppearanceButton")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        awaitAppearanceShapeFont("save ${state.shape}/${state.fontScale}", expected) {
            settingsStore.load().appearanceShapeFont()
        }
        composeRule.waitForIdle()
        assertTrue(
            "Saved shape/font was not reflected back into the visible controls; " +
                shapeFontStateDiagnostic(expected),
            appearanceDraftShapeFont().matches(expected),
        )
    }

    private fun awaitSelectedCardShape(stage: String, expected: AppearanceShapeFont) {
        try {
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                runCatching { selectedAppearanceCardShape() == expected.shape }.getOrDefault(false)
            }
        } catch (failure: Throwable) {
            throw AssertionError(
                "Appearance $stage timed out; ${shapeFontStateDiagnostic(expected)}",
                failure,
            )
        }
        assertEquals(
            "Appearance $stage selected the wrong shape; ${shapeFontStateDiagnostic(expected)}",
            expected.shape,
            selectedAppearanceCardShape(),
        )
    }

    private fun awaitAppearanceShapeFont(
        stage: String,
        expected: AppearanceShapeFont,
        read: () -> AppearanceShapeFont,
    ) {
        try {
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                runCatching { read().matches(expected) }.getOrDefault(false)
            }
        } catch (failure: Throwable) {
            throw AssertionError(
                "Appearance $stage timed out; ${shapeFontStateDiagnostic(expected)}",
                failure,
            )
        }
        assertTrue(
            "Appearance $stage completed with the wrong shape/font; " +
                shapeFontStateDiagnostic(expected),
            read().matches(expected),
        )
    }

    private fun shapeFontStateDiagnostic(expected: AppearanceShapeFont): String {
        val draft = runCatching { appearanceDraftShapeFont().toString() }
            .getOrElse { "<unavailable:${it::class.java.simpleName}>" }
        val stored = runCatching { settingsStore.load().appearanceShapeFont().toString() }
            .getOrElse { "<unavailable:${it::class.java.simpleName}>" }
        return "expected=$expected draft=$draft stored=$stored"
    }

    private fun appearanceDraftShapeFont(): AppearanceShapeFont = AppearanceShapeFont(
        shape = selectedAppearanceCardShape(),
        fontScale = appearanceDraftUiFontScale(),
    )

    private fun selectedAppearanceCardShape(): String {
        val selected = appearanceCardShapes.filter { shape ->
            composeRule.onNodeWithTag("CardShape-$shape")
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Selected) == true
        }
        return selected.singleOrNull()
            ?: throw AssertionError("Expected exactly one selected card shape, found $selected")
    }

    private fun appearanceDraftUiFontScale(): Float {
        return composeRule.onNodeWithTag("UiFontScaleSlider")
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.ProgressBarRangeInfo)
            ?.current
            ?: throw AssertionError("UiFontScaleSlider did not expose ProgressBarRangeInfo semantics")
    }

    private fun assertAppearancePreconditionDiffers(
        preset: AppearanceThemePreset,
        expected: AppearancePalette,
    ) {
        val draft = appearanceDraftPalette()
        val stored = settingsStore.load().appearancePalette()
        assertTrue(
            "Appearance preset ${preset.id} precondition was vacuous; " +
                "expected=$expected draft=$draft stored=$stored",
            draft != expected && stored != expected,
        )
    }

    private fun selectAppearancePresetAndAwaitDraft(
        preset: AppearanceThemePreset,
        expected: AppearancePalette,
    ) {
        composeRule.onNodeWithTag(appearancePresetTag(preset))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        awaitAppearancePalette("apply preset ${preset.id}", expected) { appearanceDraftPalette() }
        assertEquals(
            "Appearance preset ${preset.id} did not populate every visible palette field; " +
                appearanceStateDiagnostic(expected),
            expected,
            appearanceDraftPalette(),
        )
    }

    private fun saveAppearanceAndAwait(
        preset: AppearanceThemePreset,
        expected: AppearancePalette,
    ) {
        assertEquals(
            "Refusing to save ${preset.id} before every visible palette field matches; " +
                appearanceStateDiagnostic(expected),
            expected,
            appearanceDraftPalette(),
        )
        assertTrue(
            "Appearance preset ${preset.id} was already persisted before Save; " +
                appearanceStateDiagnostic(expected),
            settingsStore.load().appearancePalette() != expected,
        )
        composeRule.onNodeWithTag("SaveAppearanceButton")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        awaitAppearancePalette("save preset ${preset.id}", expected) {
            settingsStore.load().appearancePalette()
        }
        composeRule.waitForIdle()
        assertEquals(
            "Saved appearance ${preset.id} was not reflected back into the visible fields; " +
                appearanceStateDiagnostic(expected),
            expected,
            appearanceDraftPalette(),
        )
    }

    private fun awaitAppearancePalette(
        stage: String,
        expected: AppearancePalette,
        read: () -> AppearancePalette,
    ) {
        try {
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                runCatching { read() == expected }.getOrDefault(false)
            }
        } catch (failure: Throwable) {
            throw AssertionError(
                "Appearance $stage timed out; ${appearanceStateDiagnostic(expected)}",
                failure,
            )
        }
        assertEquals(
            "Appearance $stage completed with the wrong palette; ${appearanceStateDiagnostic(expected)}",
            expected,
            read(),
        )
    }

    private fun appearanceStateDiagnostic(expected: AppearancePalette): String {
        val draft = runCatching { appearanceDraftPalette().toString() }
            .getOrElse { "<unavailable:${it::class.java.simpleName}>" }
        val stored = runCatching { settingsStore.load().appearancePalette().toString() }
            .getOrElse { "<unavailable:${it::class.java.simpleName}>" }
        return "expected=$expected draft=$draft stored=$stored"
    }

    private fun appearanceDraftPalette(): AppearancePalette = AppearancePalette(
        primary = editableAppearanceText("AppearancePrimaryHexField"),
        secondary = editableAppearanceText("AppearanceSecondaryHexField"),
        background = editableAppearanceText("AppearanceBackgroundHexField"),
        surface = editableAppearanceText("AppearanceSurfaceHexField"),
        surfaceVariant = editableAppearanceText("AppearanceSurfaceVariantHexField"),
    )

    private fun editableAppearanceText(testTag: String): String {
        val semantics = composeRule.onNodeWithTag(testTag).fetchSemanticsNode().config
        return semantics.getOrNull(SemanticsProperties.EditableText)?.text
            ?: throw AssertionError("$testTag did not expose EditableText semantics")
    }

    private fun selectLanguage(language: AppLanguage, strings: HermesStrings) {
        navigateToShellSection("HermesNavSettings")
        composeRule.onNodeWithTag("HermesSettingsContentList").performScrollToIndex(0)
        composeRule.onNodeWithTag("HermesSettingsPage_Overview").performClick()
        scrollSettingsToTag("SettingsLanguage-${language.tag}")
        composeRule.onNodeWithTag("SettingsLanguage-${language.tag}").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            settingsStore.load().languageTag == language.tag &&
                composeRule.onAllNodesWithText(strings.appLanguageTitle).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("HermesSettingsContentList").performScrollToIndex(0)
    }

    private fun navigateToShellSection(testTag: String) {
        val chatDrawerTag = "HermesChatDrawerButton"
        val railTag = drawerNavigationTagToRailTag(testTag)
        if (
            railTag != null &&
            composeRule.onAllNodesWithTag("HermesPersistentNavigation").fetchSemanticsNodes().isNotEmpty()
        ) {
            composeRule.onNodeWithTag("HermesPersistentNavigation").assertIsDisplayed()
            composeRule.onNodeWithTag(railTag).performScrollTo().assertIsDisplayed().performClick()
            composeRule.waitForIdle()
            return
        }
        if (
            composeRule.onAllNodesWithTag(testTag).fetchSemanticsNodes().isEmpty() &&
            composeRule.onAllNodesWithTag("HermesShellDrawerButton").fetchSemanticsNodes().isEmpty() &&
            composeRule.onAllNodesWithTag(chatDrawerTag).fetchSemanticsNodes().isEmpty()
        ) {
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithTag(testTag).fetchSemanticsNodes().isNotEmpty() ||
                    composeRule.onAllNodesWithTag("HermesShellDrawerButton").fetchSemanticsNodes().isNotEmpty() ||
                    composeRule.onAllNodesWithTag(chatDrawerTag).fetchSemanticsNodes().isNotEmpty()
            }
        }
        if (composeRule.onAllNodesWithTag(testTag).fetchSemanticsNodes().isEmpty()) {
            val drawerTag = if (
                composeRule.onAllNodesWithTag("HermesShellDrawerButton").fetchSemanticsNodes().isNotEmpty()
            ) {
                "HermesShellDrawerButton"
            } else {
                chatDrawerTag
            }
            composeRule.onNodeWithTag(drawerTag).performClick()
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithTag(testTag).fetchSemanticsNodes().isNotEmpty()
            }
        }
        composeRule.onNodeWithTag(testTag).performClick()
        composeRule.waitForIdle()
    }

    private fun drawerNavigationTagToRailTag(testTag: String): String? {
        val sectionName = testTag.removePrefix(DRAWER_NAVIGATION_TAG_PREFIX)
        return sectionName
            .takeIf { testTag.startsWith(DRAWER_NAVIGATION_TAG_PREFIX) && it.isNotBlank() }
            ?.let { "$RAIL_NAVIGATION_TAG_PREFIX$it" }
    }

    private fun scrollSettingsToText(text: String) {
        composeRule.onNodeWithTag("HermesSettingsContentList").performScrollToNode(hasText(text))
        composeRule.onNodeWithText(text).fetchSemanticsNode()
    }

    private fun scrollSettingsToTag(testTag: String) {
        composeRule.onNodeWithTag("HermesSettingsContentList").performScrollToNode(hasTestTag(testTag))
        composeRule.onNodeWithTag(testTag).fetchSemanticsNode()
    }

    private fun captureAllFrameworkActivities(
        prefix: String,
        language: AppLanguage,
        themeId: String,
    ) {
        val localizedContext = app.hermesLocalizedContext()
        captureViewActivity<HermesProviderSetupWebActivity>(
            identity = frameworkEvidenceIdentity(language, FRAMEWORK_PROVIDER),
            name = "$prefix-provider",
            intent = Intent(app, HermesProviderSetupWebActivity::class.java)
                .putExtra(HermesProviderSetupWebActivity.EXTRA_URL, "")
                .putExtra(HermesProviderSetupWebActivity.EXTRA_TITLE, ""),
            pageId = "HermesProviderSetupWebActivity",
            language = language,
            themeId = themeId,
            expectedVisibleTexts = listOf(
                localizedContext.getString(R.string.hermes_provider_setup_title),
                localizedContext.getString(R.string.hermes_provider_setup_invalid_url),
            ),
        )
        captureViewActivity<HermesTaskerPluginEditActivity>(
            identity = frameworkEvidenceIdentity(language, FRAMEWORK_TASKER_ACTION),
            name = "$prefix-tasker-action",
            intent = Intent(app, HermesTaskerPluginEditActivity::class.java),
            pageId = "HermesTaskerPluginEditActivity",
            language = language,
            themeId = themeId,
            expectedVisibleTexts = listOf(
                localizedContext.getString(R.string.hermes_tasker_plugin_title),
                localizedContext.getString(R.string.hermes_tasker_plugin_summary),
            ),
        )
        captureViewActivity<HermesTaskerConditionEditActivity>(
            identity = frameworkEvidenceIdentity(language, FRAMEWORK_TASKER_CONDITION),
            name = "$prefix-tasker-condition",
            intent = Intent(app, HermesTaskerConditionEditActivity::class.java),
            pageId = "HermesTaskerConditionEditActivity",
            language = language,
            themeId = themeId,
            expectedVisibleTexts = listOf(
                localizedContext.getString(R.string.hermes_tasker_condition_title),
                localizedContext.getString(R.string.hermes_tasker_condition_summary),
                HermesTaskerConditionBridge.conditionChoices(localizedContext).first().label,
            ),
        )
        captureViewActivity<HermesTaskerEventEditActivity>(
            identity = frameworkEvidenceIdentity(language, FRAMEWORK_TASKER_EVENT),
            name = "$prefix-tasker-event",
            intent = Intent(app, HermesTaskerEventEditActivity::class.java),
            pageId = "HermesTaskerEventEditActivity",
            language = language,
            themeId = themeId,
            expectedVisibleTexts = listOf(
                localizedContext.getString(R.string.hermes_tasker_event_title),
                localizedContext.getString(R.string.hermes_tasker_event_summary),
                HermesTaskerEventBridge.eventChoices(localizedContext).first().label,
            ),
        )
    }

    private fun assertFrameworkLabelsDifferFromEnglish(language: AppLanguage) {
        val localized = app.hermesLocalizedContext()
        val english = resourceContextFor(AppLanguage.ENGLISH)
        listOf(
            R.string.hermes_provider_setup_title,
            R.string.hermes_provider_setup_invalid_url,
            R.string.hermes_tasker_plugin_title,
            R.string.hermes_tasker_condition_title,
            R.string.hermes_tasker_event_title,
        ).forEach { resourceId ->
            assertNotEquals(
                "$resourceId must be visibly localized in ${language.tag}",
                english.getString(resourceId),
                localized.getString(resourceId),
            )
        }
        assertNotEquals(
            "Condition choice must be localized in ${language.tag}",
            HermesTaskerConditionBridge.conditionChoices(english).first().label,
            HermesTaskerConditionBridge.conditionChoices(localized).first().label,
        )
        assertNotEquals(
            "Event choice must be localized in ${language.tag}",
            HermesTaskerEventBridge.eventChoices(english).first().label,
            HermesTaskerEventBridge.eventChoices(localized).first().label,
        )
    }

    private fun resourceContextFor(language: AppLanguage): Context {
        val configuration = Configuration(app.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language.tag))
        return app.createConfigurationContext(configuration)
    }

    private fun <A : Activity> captureViewActivity(
        identity: String,
        name: String,
        intent: Intent,
        pageId: String,
        language: AppLanguage,
        themeId: String,
        expectedVisibleTexts: List<String>,
    ) {
        var viewHierarchy = ""
        var screenshot: File? = null
        ActivityScenario.launch<A>(intent).use { scenario ->
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(250L)
            assertEquals("$pageId must be resumed before capture", androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                assertEquals(BuildConfig.APPLICATION_ID, activity.packageName)
                val root = activity.window.decorView
                assertTrue("$pageId decor must be attached and shown", root.isAttachedToWindow && root.isShown)
                assertTrue("$pageId must own window focus at capture", root.hasWindowFocus())
                assertGlassChrome(activity, pageId, expectedVisibleTexts.first())
                val visibleTexts = visibleTextValues(root)
                expectedVisibleTexts.forEach { expected ->
                    assertTrue(
                        "$pageId did not visibly render '$expected'; visible=$visibleTexts",
                        visibleTexts.any { observed -> observed.contains(expected) },
                    )
                }
                viewHierarchy = buildViewHierarchyXml(root)
                screenshot = captureDecorScreenshot(name, root)
            }
            assertEquals("$pageId changed lifecycle state during capture", androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
            val capturedScreenshot = requireNotNull(screenshot) { "$pageId did not produce a decor screenshot" }
            val settings = settingsStore.load()
            val proofFile = File(outputDirectory(), "$name-ui.xml")
            proofFile.writeText(
                buildString {
                    appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                    appendLine(
                        "<hermes-ui-evidence artifact=\"${xml(name)}\" " +
                            "evidence-identity=\"${xml(identity)}\" " +
                            "coverage-kind=\"framework-view-activity\" page-id=\"${xml(pageId)}\">",
                    )
                    appendEvidenceMetadata(
                        screenshot = capturedScreenshot,
                        language = language,
                        themeId = themeId,
                        settings = settings,
                        sentinels = expectedVisibleTexts,
                        xmlMode = true,
                    )
                    append(viewHierarchy)
                    appendLine("</hermes-ui-evidence>")
                },
                Charsets.UTF_8,
            )
            assertTrue("Failed to persist framework UI hierarchy for $name", proofFile.length() > 0L)
            capturedEvidence += EvidenceArtifact(identity, capturedScreenshot.name, proofFile.name)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @Suppress("DEPRECATION")
    private fun assertGlassChrome(activity: Activity, pageId: String, expectedTitle: String) {
        val activityName = activity::class.java.simpleName
        val evidencePage = "$pageId ($activityName)"
        val palette = hermesViewPalette(activity)
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
        val page = contentRoot.getChildAt(0)
        assertTrue("$evidencePage must use the Hermes ScrollView page", page is ScrollView)
        val backdrop = page.background as? GradientDrawable
            ?: throw AssertionError("$evidencePage must use the saved-theme gradient backdrop")
        val expectedBackdrop = hermesViewBackdropDrawable(palette)
        assertArrayEquals(
            "$evidencePage rendered the wrong backdrop colours",
            requireNotNull(expectedBackdrop.colors),
            requireNotNull(backdrop.colors),
        )
        val responsiveFrame = (page as ScrollView).getChildAt(0) as? ViewGroup
        val glassPanel = responsiveFrame?.getChildAt(0)
        val panel = glassPanel?.background as? GradientDrawable
            ?: throw AssertionError("$evidencePage must place controls on a glass panel")
        val expectedPanel = hermesViewPanelDrawable(activity, palette, elevated = true)
        assertEquals(
            "$evidencePage rendered the wrong panel colour",
            requireNotNull(expectedPanel.color).defaultColor,
            requireNotNull(panel.color).defaultColor,
        )
        assertEquals(
            "$evidencePage rendered the wrong card corner radius",
            expectedPanel.cornerRadius,
            panel.cornerRadius,
            0.5f,
        )
        val title = allTextViews(page).firstOrNull { it.text.toString() == expectedTitle }
            ?: throw AssertionError("$evidencePage did not expose title '$expectedTitle'")
        val expectedTitleSizePx = 22f * activity.resources.displayMetrics.scaledDensity * palette.fontScale
        assertEquals(
            "$evidencePage did not render the persisted UI font scale",
            expectedTitleSizePx,
            title.textSize,
            1.0f,
        )

        val decor = activity.window.decorView
        val rootInsets = ViewCompat.getRootWindowInsets(decor)
            ?: throw AssertionError("$evidencePage did not expose root window insets")
        val systemBarInsets = rootInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        val displayCutoutInsets = rootInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val tappableElementInsets = rootInsets.getInsets(WindowInsetsCompat.Type.tappableElement())
        val safeInsets = rootInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val safeTop = safeInsets.top
        val usesGestureNavigation = tappableElementInsets.bottom == 0
        val platformEnforcesEdgeToEdge =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                activity.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.VANILLA_ICE_CREAM

        val insetsController = WindowCompat.getInsetsController(activity.window, decor)
        assertEquals(
            "$evidencePage status-bar icon appearance must follow saved-theme lightCanvas=${palette.lightCanvas}",
            palette.lightCanvas,
            insetsController.isAppearanceLightStatusBars,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertEquals(
                "$evidencePage navigation-bar icon appearance must follow saved-theme lightCanvas=${palette.lightCanvas}",
                palette.lightCanvas,
                insetsController.isAppearanceLightNavigationBars,
            )
        }

        if (platformEnforcesEdgeToEdge) {
            assertEquals(
                "$evidencePage must use a transparent status bar on target/API 35 edge-to-edge",
                Color.TRANSPARENT,
                activity.window.statusBarColor,
            )
            if (usesGestureNavigation) {
                assertEquals(
                    "$evidencePage must use a transparent gesture-navigation bar " +
                        "(tappableElement.bottom=${tappableElementInsets.bottom})",
                    Color.TRANSPARENT,
                    activity.window.navigationBarColor,
                )
            }
        } else {
            assertEquals(
                "$evidencePage must use the saved-theme status colour before target/API 35 edge-to-edge",
                palette.background,
                activity.window.statusBarColor,
            )
            assertEquals(
                "$evidencePage must use the contrast-safe saved-theme navigation colour " +
                    "before target/API 35 edge-to-edge",
                resolveHermesViewNavigationBarColor(palette, Build.VERSION.SDK_INT),
                activity.window.navigationBarColor,
            )
        }

        val decorBounds = Rect()
        val pageBounds = Rect()
        val titleBounds = Rect()
        assertTrue("$evidencePage decor must have visible global bounds", decor.getGlobalVisibleRect(decorBounds))
        assertTrue("$evidencePage themed ScrollView must have visible global bounds", page.getGlobalVisibleRect(pageBounds))
        assertTrue("$evidencePage title '$expectedTitle' must have visible global bounds", title.getGlobalVisibleRect(titleBounds))
        val safeTopGlobal = decorBounds.top + safeTop
        if (platformEnforcesEdgeToEdge) {
            assertEquals(
                "$evidencePage themed ScrollView must exactly cover the decor behind transparent system bars; " +
                    "decor=$decorBounds, page=$pageBounds, safeTop=$safeTopGlobal, " +
                    "systemBars.top=${systemBarInsets.top}, cutout.top=${displayCutoutInsets.top}, " +
                    "union.top=${safeInsets.top}",
                decorBounds,
                pageBounds,
            )
        }
        assertTrue(
            "$evidencePage title '$expectedTitle' must begin at or below the system-bar/cutout safe top; " +
                "titleTop=${titleBounds.top}, safeTop=$safeTopGlobal, " +
                "systemBars.top=${systemBarInsets.top}, cutout.top=${displayCutoutInsets.top}, " +
                "union.top=${safeInsets.top}",
            titleBounds.top >= safeTopGlobal,
        )
    }

    private fun allTextViews(root: View): List<TextView> {
        val result = mutableListOf<TextView>()
        fun visit(view: View) {
            if (view is TextView) result += view
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        visit(root)
        return result
    }

    private fun visibleTextValues(root: View): List<String> {
        val texts = mutableListOf<String>()
        fun visit(view: View) {
            if (view is TextView && view.text.isNotBlank()) {
                val visibleBounds = Rect()
                if (view.getGlobalVisibleRect(visibleBounds) && visibleBounds.width() > 0 && visibleBounds.height() > 0) {
                    texts += view.text.toString()
                }
            }
            if (view is ViewGroup) {
                repeat(view.childCount) { index -> visit(view.getChildAt(index)) }
            }
        }
        visit(root)
        return texts
    }

    private fun buildViewHierarchyXml(root: View): String {
        return buildString {
            appendLine("  <view-hierarchy>")
            appendViewNode(root, depth = 2)
            appendLine("  </view-hierarchy>")
        }
    }

    private fun StringBuilder.appendViewNode(view: View, depth: Int) {
        val indent = "  ".repeat(depth)
        val globalBounds = Rect()
        val actuallyVisible = view.getGlobalVisibleRect(globalBounds) && globalBounds.width() > 0 && globalBounds.height() > 0
        val resourceName = if (view.id == View.NO_ID) {
            ""
        } else {
            runCatching { view.resources.getResourceName(view.id) }.getOrDefault(view.id.toString())
        }
        val text = (view as? TextView)?.text?.toString().orEmpty()
        val hint = (view as? TextView)?.hint?.toString().orEmpty()
        append(indent)
        append("<node class=\"")
        append(xml(view.javaClass.name))
        append("\" resource-id=\"")
        append(xml(resourceName))
        append("\" text=\"")
        append(xml(text))
        append("\" hint=\"")
        append(xml(hint))
        append("\" content-desc=\"")
        append(xml(view.contentDescription?.toString().orEmpty()))
        append("\" visible=\"")
        append(actuallyVisible)
        append("\" bounds=\"[")
        append(globalBounds.left)
        append(',')
        append(globalBounds.top)
        append("][")
        append(globalBounds.right)
        append(',')
        append(globalBounds.bottom)
        append("]\" background=\"")
        append(xml(view.background?.javaClass?.name.orEmpty()))
        if (view is ViewGroup && view.childCount > 0) {
            appendLine("\">")
            repeat(view.childCount) { index -> appendViewNode(view.getChildAt(index), depth + 1) }
            append(indent)
            appendLine("</node>")
        } else {
            appendLine("\" />")
        }
    }

    private fun captureComposeEvidence(
        identity: String,
        name: String,
        coverageKind: String,
        pageId: String,
        language: AppLanguage,
        themeId: String,
        sentinels: List<String>,
        verifyThemePixels: Boolean = false,
    ) {
        composeRule.mainClock.advanceTimeBy(750L)
        composeRule.waitForIdle()
        android.os.SystemClock.sleep(250L)
        assertComposeHostForeground(name)
        sentinels.forEach { sentinel -> assertComposeSentinelDisplayed(name, sentinel) }
        val semantics = composeRule.onRoot(useUnmergedTree = true).printToString(maxDepth = 160)
        assertTrue("Hermes semantics tree $name is empty", semantics.isNotBlank())
        val screenshot = captureComposeRootScreenshot(name)
        if (verifyThemePixels) {
            assertScreenshotRendersPalette(screenshot, settingsStore.load())
        }
        val proofFile = File(outputDirectory(), "$name-semantics.txt")
        proofFile.writeText(
            buildString {
                appendLine("evidence_type=headed-ui-coverage-bound")
                appendLine("evidence_identity=$identity")
                appendLine("artifact=$name")
                appendLine("coverage_kind=$coverageKind")
                appendLine("page_id=$pageId")
                appendEvidenceMetadata(
                    screenshot = screenshot,
                    language = language,
                    themeId = themeId,
                    settings = settingsStore.load(),
                    sentinels = sentinels,
                    xmlMode = false,
                )
                appendLine()
                append(semantics)
            },
            Charsets.UTF_8,
        )
        assertTrue("Failed to persist Compose semantics for $name", proofFile.length() > 0L)
        capturedEvidence += EvidenceArtifact(identity, screenshot.name, proofFile.name)
    }

    private fun assertComposeHostForeground(artifact: String) {
        composeRule.runOnUiThread {
            val resumed = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filter { activity -> activity.packageName == BuildConfig.APPLICATION_ID }
            assertEquals("$artifact must have exactly one resumed Hermes activity", 1, resumed.size)
            val decor = resumed.single().window.decorView
            assertTrue("$artifact Compose host decor must be attached and shown", decor.isAttachedToWindow && decor.isShown)
            assertTrue("$artifact Compose host must own window focus", decor.hasWindowFocus())
        }
    }

    private fun assertComposeSentinelDisplayed(artifact: String, sentinel: String) {
        val tagged = composeRule.onAllNodesWithTag(sentinel, useUnmergedTree = true)
        val taggedCount = tagged.fetchSemanticsNodes().size
        if ((0 until taggedCount).any { index -> runCatching { tagged[index].assertIsDisplayed() }.isSuccess }) {
            return
        }
        val textNodes = composeRule.onAllNodesWithText(sentinel, useUnmergedTree = true)
        val textCount = textNodes.fetchSemanticsNodes().size
        if ((0 until textCount).any { index -> runCatching { textNodes[index].assertIsDisplayed() }.isSuccess }) {
            return
        }
        throw AssertionError("$artifact did not visibly render sentinel '$sentinel'")
    }

    private fun StringBuilder.appendEvidenceMetadata(
        screenshot: File,
        language: AppLanguage,
        themeId: String,
        settings: AppSettings,
        sentinels: List<String>,
        xmlMode: Boolean,
    ) {
        val identity = releaseIdentity
        val metadata = linkedMapOf(
            "profile" to currentProfile(),
            "language" to language.tag,
            "theme_id" to themeId,
            "theme_primary" to settings.themePrimaryHex,
            "theme_secondary" to settings.themeSecondaryHex,
            "theme_background" to settings.themeBackgroundHex,
            "theme_surface" to settings.themeSurfaceHex,
            "theme_surface_variant" to settings.themeSurfaceVariantHex,
            "card_shape" to settings.themeCardShape,
            "ui_font_scale" to settings.uiFontScale.toString(),
            "screen_width_dp" to app.resources.configuration.screenWidthDp.toString(),
            "screen_height_dp" to app.resources.configuration.screenHeightDp.toString(),
            "system_font_scale" to app.resources.configuration.fontScale.toString(),
            "package_id" to BuildConfig.APPLICATION_ID,
            "version_name" to BuildConfig.VERSION_NAME,
            "version_code" to BuildConfig.VERSION_CODE.toString(),
            "build_variant" to BuildConfig.BUILD_TYPE,
            "source_digest" to identity.releaseSourceDigest,
            "candidate_apk_sha256" to identity.candidateApkSha256,
            "instrumentation_apk_sha256" to identity.instrumentationApkSha256,
            "evidence_run_id" to identity.evidenceRunId,
            "device_serial" to identity.deviceSerial,
            "avd_name" to identity.avdName,
            "device_boot_id" to identity.deviceBootId,
            "build_fingerprint" to Build.FINGERPRINT,
            "screenshot_sha256" to ReleaseDeviceEvidenceIdentity.sha256(screenshot),
        )
        if (xmlMode) {
            appendLine("  <metadata>")
            metadata.forEach { (key, value) ->
                appendLine("    <entry key=\"${xml(key)}\" value=\"${xml(value)}\" />")
            }
            sentinels.forEach { sentinel -> appendLine("    <sentinel value=\"${xml(sentinel)}\" />") }
            appendLine("  </metadata>")
        } else {
            metadata.forEach { (key, value) -> appendLine("$key=$value") }
            sentinels.forEach { sentinel -> appendLine("sentinel=${sentinel.replace('\n', ' ')}") }
        }
    }

    private fun captureComposeRootScreenshot(name: String): File {
        val bitmap = composeRule.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        return persistVerifiedPng(name, bitmap)
    }

    private fun captureDecorScreenshot(name: String, decor: View): File {
        assertTrue("$name decor width must be positive", decor.width > 0)
        assertTrue("$name decor height must be positive", decor.height > 0)
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        return try {
            persistVerifiedPng(name, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun persistVerifiedPng(name: String, bitmap: Bitmap): File {
        val outputFile = File(outputDirectory(), "$name.png")
        assertTrue("Hermes UI screenshot $name appears blank", screenshotHasVisibleContent(bitmap))
        val compressed = FileOutputStream(outputFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        assertTrue("Failed to encode Hermes UI screenshot $name as PNG", compressed)
        assertTrue("Failed to persist Hermes UI screenshot $name", outputFile.length() > 8L)
        val signature = outputFile.inputStream().buffered().use { input -> ByteArray(8).also { input.read(it) } }
        assertArrayEquals("$name is not a PNG file", PNG_SIGNATURE, signature)
        val decoded = BitmapFactory.decodeFile(outputFile.absolutePath)
            ?: throw AssertionError("$name could not be decoded as PNG")
        try {
            assertEquals("$name decoded width changed", bitmap.width, decoded.width)
            assertEquals("$name decoded height changed", bitmap.height, decoded.height)
            assertTrue("Decoded Hermes UI screenshot $name appears blank", screenshotHasVisibleContent(decoded))
        } finally {
            decoded.recycle()
        }
        return outputFile
    }

    private fun screenshotHasVisibleContent(bitmap: Bitmap): Boolean {
        val stepX = maxOf(1, bitmap.width / 48)
        val stepY = maxOf(1, bitmap.height / 48)
        var visibleSamples = 0
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) == 0) continue
                val high = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                val low = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                if (high > 42 || high - low > 14) visibleSamples += 1
            }
        }
        return visibleSamples > 64
    }

    private fun assertScreenshotRendersPalette(screenshot: File, settings: AppSettings) {
        val bitmap = BitmapFactory.decodeFile(screenshot.absolutePath)
            ?: throw AssertionError("Could not decode ${screenshot.name} for rendered-palette verification")
        try {
            val primary = Color.parseColor(settings.themePrimaryHex)
            val background = Color.parseColor(settings.themeBackgroundHex)
            var primarySamples = 0
            var backgroundSamples = 0
            val stepX = maxOf(1, bitmap.width / 96)
            val stepY = maxOf(1, bitmap.height / 96)
            for (y in 0 until bitmap.height step stepY) {
                for (x in 0 until bitmap.width step stepX) {
                    val pixel = bitmap.getPixel(x, y)
                    if (colorDistance(pixel, primary) <= 42) primarySamples += 1
                    if (colorDistance(pixel, background) <= 52) backgroundSamples += 1
                }
            }
            assertTrue(
                "${screenshot.name} did not visibly render primary ${settings.themePrimaryHex}",
                primarySamples >= 3,
            )
            assertTrue(
                "${screenshot.name} did not visibly render background ${settings.themeBackgroundHex}",
                backgroundSamples >= 3,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun colorDistance(first: Int, second: Int): Int {
        return kotlin.math.abs(Color.red(first) - Color.red(second)) +
            kotlin.math.abs(Color.green(first) - Color.green(second)) +
            kotlin.math.abs(Color.blue(first) - Color.blue(second))
    }

    private fun scrollAppearanceCardCornerIntoView() {
        composeRule.onNodeWithTag("HermesSettingsContentList")
            .performScrollToIndex(THEME_APPEARANCE_CARD_ITEM_INDEX)
        try {
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                runCatching { appearanceCornerSamplingBandFitsViewport() }.getOrDefault(false)
            }
        } catch (failure: Throwable) {
            val listBounds = runCatching {
                composeRule.onNodeWithTag("HermesSettingsContentList").fetchSemanticsNode().boundsInRoot
            }.getOrNull()
            val cardBounds = runCatching {
                composeRule.onNodeWithTag("HermesAppearanceCard").fetchSemanticsNode().boundsInRoot
            }.getOrNull()
            throw AssertionError(
                "Appearance card corner sampling band did not enter the Settings viewport; " +
                    "list=$listBounds, card=$cardBounds",
                failure,
            )
        }
        composeRule.onNodeWithTag("HermesAppearanceCardTop").assertIsDisplayed()
    }

    private fun appearanceCornerSamplingBandFitsViewport(): Boolean {
        val listBounds = composeRule.onNodeWithTag("HermesSettingsContentList").fetchSemanticsNode().boundsInRoot
        val cardBounds = composeRule.onNodeWithTag("HermesAppearanceCard").fetchSemanticsNode().boundsInRoot
        val requiredBandPx = APPEARANCE_CORNER_MAX_DEPTH_DP * app.resources.displayMetrics.density
        return cardBounds.top >= listBounds.top &&
            cardBounds.top + requiredBandPx <= listBounds.bottom
    }

    private fun renderedAppearanceCornerDepth(): Int {
        val appearance = composeRule.onNodeWithTag("HermesAppearanceCard").fetchSemanticsNode()
        val bounds = appearance.boundsInRoot
        val rootBitmap = composeRule.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        return try {
            assertTrue(
                "Appearance card corner sampling band must be inside the captured Compose root: " +
                    "card=$bounds, root=${rootBitmap.width}x${rootBitmap.height}",
                bounds.left >= 0f &&
                    bounds.top >= 0f &&
                    bounds.right <= rootBitmap.width.toFloat() &&
                    bounds.top + (APPEARANCE_CORNER_MAX_DEPTH_DP * app.resources.displayMetrics.density) <
                    rootBitmap.height,
            )
            val density = app.resources.displayMetrics.density
            val top = bounds.top.toInt()
            val left = bounds.left.toInt()
            val right = bounds.right.toInt().coerceAtMost(rootBitmap.width - 1)
            val referenceInsetPx = (APPEARANCE_CORNER_REFERENCE_INSET_DP * density).toInt().coerceAtLeast(1)
            val referenceY = top + referenceInsetPx
            val referenceX = (left + right) / 2
            assertTrue(
                "Appearance card interior reference must remain inside its bounds: card=$bounds, " +
                    "reference=($referenceX,$referenceY)",
                referenceX in left..right && referenceY < bounds.bottom,
            )
            val exteriorInsetPx = density.toInt().coerceAtLeast(1)
            val exteriorX = left - exteriorInsetPx
            assertTrue(
                "Appearance card requires exterior pixels to the left of its corner proof: " +
                    "card=$bounds, exteriorX=$exteriorX",
                exteriorX >= 0,
            )
            val interior = rootBitmap.getPixel(referenceX, referenceY)
            val exterior = rootBitmap.getPixel(exteriorX, referenceY)
            val interiorExteriorContrast = colorDistance(interior, exterior)
            assertTrue(
                "Appearance card corner proof is vacuous because its interior and exterior " +
                    "references are indistinguishable; card=$bounds, " +
                    "contrast=$interiorExteriorContrast",
                interiorExteriorContrast > APPEARANCE_CORNER_COLOR_TOLERANCE * 3,
            )
            val maximumDepthDp = minOf(
                APPEARANCE_CORNER_MAX_DEPTH_DP,
                ((right - left) / density / 4f).toInt(),
            )
            assertTrue("Appearance card corner proof has no sampling depth: card=$bounds", maximumDepthDp > 0)
            for (depthDp in 1..maximumDepthDp) {
                val offset = (depthDp * density).toInt().coerceAtLeast(1)
                val x = left + offset
                val y = top + offset
                val sample = rootBitmap.getPixel(x, y)
                val interiorDistance = colorDistance(sample, interior)
                val exteriorDistance = colorDistance(sample, exterior)
                if (
                    interiorDistance <= APPEARANCE_CORNER_COLOR_TOLERANCE &&
                    interiorDistance < exteriorDistance
                ) {
                    return depthDp
                }
            }
            throw AssertionError(
                "Rendered appearance card never entered its surface colour at the top-left corner; " +
                    "card=$bounds, contrast=$interiorExteriorContrast",
            )
        } finally {
            rootBitmap.recycle()
        }
    }

    private fun assertRenderedFontScale(label: String, expectedScale: Float) {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        composeRule.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            action(layouts)
        }
        val renderedFontSp = layouts.single().layoutInput.style.fontSize.value
        val expectedFontSp = Typography().titleSmall.fontSize.value * expectedScale
        assertEquals(
            "$label did not render the persisted Hermes UI font scale",
            expectedFontSp,
            renderedFontSp,
            0.05f,
        )
    }

    private fun expectedProfileEvidenceIdentities(): Set<String> = buildSet {
        AppSection.entries.forEach { section -> add("section:${section.name}") }
        SettingsPage.entries.forEach { page -> add("settings:${page.name}") }
        DevicePage.entries.filterNot { it == DevicePage.Overview }.forEach { page -> add("device:${page.name}") }
        appearanceThemePresets.forEach { preset -> add("appearance-preset:${preset.id}") }
        THEME_SHAPE_FONT_STATES.forEach { state ->
            add("shape:${state.shape}")
            add("font:${state.fontLabel}:${fontScaleSlug(state.fontScale)}")
        }
        add("appearance-custom-light")
        FRAMEWORK_PAGE_IDS.forEach { pageId -> add(frameworkEvidenceIdentity(AppLanguage.ENGLISH, pageId)) }
    }

    private fun expectedLocalizedEvidenceIdentities(recommendedModelIds: List<String>): Set<String> = buildSet {
        AppLanguage.entries.forEach { language ->
            recommendedModelIds.forEach { modelId -> add("localized-model:${language.tag}:$modelId") }
            if (language != AppLanguage.ENGLISH) {
                FRAMEWORK_PAGE_IDS.forEach { pageId -> add(frameworkEvidenceIdentity(language, pageId)) }
            }
        }
    }

    private fun frameworkEvidenceIdentity(language: AppLanguage, pageId: String): String {
        return "framework:${language.tag}:$pageId"
    }

    private fun assertReleaseUiContracts() {
        val sectionNames = AppSection.entries.map { it.name }
        assertTrue("At least one app section is required", sectionNames.isNotEmpty())
        assertEquals("App section names must be unique", sectionNames.size, sectionNames.toSet().size)

        val deviceRoutes = DevicePage.entries.map { it.route }
        assertTrue("At least one Device route is required", deviceRoutes.isNotEmpty())
        assertTrue("Device routes must be absolute", deviceRoutes.all { it.startsWith("/") })
        assertEquals("Device routes must be unique", deviceRoutes.size, deviceRoutes.toSet().size)

        val presetIds = appearanceThemePresets.map { it.id }
        assertTrue("At least one appearance preset is required", presetIds.isNotEmpty())
        assertTrue("Appearance preset IDs must be nonblank", presetIds.all { it.isNotBlank() })
        assertEquals("Appearance preset IDs must be unique", presetIds.size, presetIds.toSet().size)
        val presetPalettes = appearanceThemePresets.map { it.appearancePalette() }
        assertEquals("Appearance preset palettes must be unique", presetPalettes.size, presetPalettes.toSet().size)
        val shapeFontPairs = THEME_SHAPE_FONT_STATES.map { it.appearanceShapeFont() }
        assertEquals("Shape/font evidence states must be unique", shapeFontPairs.size, shapeFontPairs.toSet().size)
        assertEquals(
            "Canonical appearance card shapes must be unique",
            appearanceCardShapes.size,
            appearanceCardShapes.toSet().size,
        )
        val evidenceShapeIds = THEME_SHAPE_FONT_STATES.map { it.shape }
        assertEquals(
            "Shape/font evidence must contain exactly one state per canonical card shape",
            appearanceCardShapes.size,
            evidenceShapeIds.size,
        )
        assertEquals(
            "Shape/font evidence must cover every visible card-shape control",
            appearanceCardShapes.toSet(),
            evidenceShapeIds.toSet(),
        )
        assertEquals(EXPECTED_LANGUAGE_TAGS, AppLanguage.entries.map { it.tag }.toSet())
    }

    private fun assertEvidenceManifest(expected: Set<String>) {
        val actual = capturedEvidence.map { it.identity }
        assertEquals("Evidence identities must be unique", actual.size, actual.toSet().size)
        assertEquals("Headed UI evidence manifest differed", expected, actual.toSet())
        assertEquals("Screenshot file names must be unique", actual.size, capturedEvidence.map { it.screenshotName }.toSet().size)
        assertEquals("Proof file names must be unique", actual.size, capturedEvidence.map { it.proofName }.toSet().size)
    }

    private fun writeInventory(name: String, coverageKind: String, artifacts: List<EvidenceArtifact>) {
        val identity = releaseIdentity
        val file = File(outputDirectory(), name)
        file.writeText(
            buildString {
                appendLine("evidence_type=headed-ui-coverage-inventory-bound")
                appendLine("coverage_kind=$coverageKind")
                appendLine("profile=${currentProfile()}")
                appendLine("capture_count=${artifacts.size}")
                appendLine("source_digest=${identity.releaseSourceDigest}")
                appendLine("candidate_apk_sha256=${identity.candidateApkSha256}")
                appendLine("instrumentation_apk_sha256=${identity.instrumentationApkSha256}")
                appendLine("evidence_run_id=${identity.evidenceRunId}")
                appendLine("device_serial=${identity.deviceSerial}")
                appendLine("avd_name=${identity.avdName}")
                appendLine("device_boot_id=${identity.deviceBootId}")
                artifacts.forEachIndexed { index, artifact ->
                    appendLine("capture.${index + 1}.identity=${artifact.identity}")
                    appendLine("capture.${index + 1}.screenshot=${artifact.screenshotName}")
                    appendLine("capture.${index + 1}.proof=${artifact.proofName}")
                }
            },
            Charsets.UTF_8,
        )
        assertTrue("Failed to persist $name", file.length() > 0L)
    }

    private fun outputDirectory(): File = File(app.filesDir, "hermes-ui-visuals").apply { mkdirs() }

    private fun clearEvidencePrefix(prefix: String) {
        outputDirectory().listFiles()
            .orEmpty()
            .filter { file -> file.name.startsWith(prefix) }
            .forEach { file -> assertTrue("Could not replace stale headed evidence ${file.name}", file.delete()) }
        capturedEvidence.clear()
    }

    private fun currentProfile(): String {
        val configuration = app.resources.configuration
        val kind = if (configuration.screenWidthDp >= TABLET_WIDTH_DP) "tablet" else "phone"
        val expected = InstrumentationRegistry.getArguments().getString(ARG_EXPECTED_UI_PROFILE).orEmpty().trim()
        require(expected == "phone" || expected == "tablet") {
            "Headed UI evidence requires -e $ARG_EXPECTED_UI_PROFILE phone|tablet"
        }
        check(kind == expected) {
            "Headed UI evidence expected $expected but executing profile is $kind " +
                "(${configuration.screenWidthDp}x${configuration.screenHeightDp}dp)"
        }
        return "$kind-${configuration.screenWidthDp}x${configuration.screenHeightDp}dp"
    }

    private val releaseIdentity: ReleaseDeviceEvidenceIdentity.Identity by lazy {
        ReleaseDeviceEvidenceIdentity.requireBound(app)
    }

    private fun xml(value: String): String {
        return value
            .filter { character -> character == '\t' || character == '\n' || character == '\r' || character.code >= 0x20 }
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "&apos;")
    }

    private fun fontScaleSlug(value: Float): String = (value * 100f).toInt().toString().padStart(3, '0')

    private data class ThemeShapeFontState(
        val shape: String,
        val fontScale: Float,
        val fontLabel: String,
    )

    private data class AppearanceShapeFont(
        val shape: String,
        val fontScale: Float,
    ) {
        fun matches(expected: AppearanceShapeFont): Boolean {
            return shape == expected.shape &&
                kotlin.math.abs(fontScale - expected.fontScale) < SHAPE_FONT_TOLERANCE
        }
    }

    private data class AppearancePalette(
        val primary: String,
        val secondary: String,
        val background: String,
        val surface: String,
        val surfaceVariant: String,
    )

    private fun AppearanceThemePreset.appearancePalette(): AppearancePalette = AppearancePalette(
        primary = primaryHex,
        secondary = secondaryHex,
        background = backgroundHex,
        surface = surfaceHex,
        surfaceVariant = surfaceVariantHex,
    )

    private fun AppSettings.appearancePalette(): AppearancePalette = AppearancePalette(
        primary = themePrimaryHex,
        secondary = themeSecondaryHex,
        background = themeBackgroundHex,
        surface = themeSurfaceHex,
        surfaceVariant = themeSurfaceVariantHex,
    )

    private fun ThemeShapeFontState.appearanceShapeFont(): AppearanceShapeFont = AppearanceShapeFont(
        shape = shape,
        fontScale = fontScale,
    )

    private fun AppSettings.appearanceShapeFont(): AppearanceShapeFont = AppearanceShapeFont(
        shape = themeCardShape,
        fontScale = uiFontScale,
    )

    private fun appearancePresetTag(preset: AppearanceThemePreset): String = "AppearancePreset-${preset.id}"

    private data class EvidenceArtifact(
        val identity: String,
        val screenshotName: String,
        val proofName: String,
    )

    companion object {
        private const val TABLET_WIDTH_DP = 600
        private const val DRAWER_NAVIGATION_TAG_PREFIX = "HermesNav"
        private const val RAIL_NAVIGATION_TAG_PREFIX = "HermesRail"
        private const val ARG_EXPECTED_UI_PROFILE = "expected_ui_profile"
        private const val FRAMEWORK_PROVIDER = "HermesProviderSetupWebActivity"
        private const val FRAMEWORK_TASKER_ACTION = "HermesTaskerPluginEditActivity"
        private const val FRAMEWORK_TASKER_CONDITION = "HermesTaskerConditionEditActivity"
        private const val FRAMEWORK_TASKER_EVENT = "HermesTaskerEventEditActivity"
        private const val CUSTOM_LIGHT_PRIMARY = "#1565C0"
        private const val CUSTOM_LIGHT_SECONDARY = "#8E24AA"
        private const val CUSTOM_LIGHT_BACKGROUND = "#FAFBFF"
        private const val CUSTOM_LIGHT_SURFACE = "#FFFFFF"
        private const val CUSTOM_LIGHT_SURFACE_VARIANT = "#E8EEF8"
        private const val SHAPE_PROOF_BACKGROUND = "#000000"
        private const val SHAPE_PROOF_SURFACE = "#000000"
        private const val SHAPE_PROOF_SURFACE_VARIANT = "#FFFFFF"
        private const val THEME_APPEARANCE_CARD_ITEM_INDEX = 1
        private const val APPEARANCE_CORNER_REFERENCE_INSET_DP = 8
        private const val APPEARANCE_CORNER_MAX_DEPTH_DP = 24
        private const val APPEARANCE_CORNER_COLOR_TOLERANCE = 42
        private const val SHAPE_FONT_TOLERANCE = 0.001f
        private val THEME_SHAPE_FONT_STATES = listOf(
            ThemeShapeFontState("soft", AppSettings.MIN_UI_FONT_SCALE, "min"),
            ThemeShapeFontState("square", AppSettings.MAX_UI_FONT_SCALE, "max"),
            ThemeShapeFontState("rounded", AppSettings.DEFAULT_UI_FONT_SCALE, "default"),
        )
        private val FRAMEWORK_PAGE_IDS = listOf(
            FRAMEWORK_PROVIDER,
            FRAMEWORK_TASKER_ACTION,
            FRAMEWORK_TASKER_CONDITION,
            FRAMEWORK_TASKER_EVENT,
        )
        private val EXPECTED_LANGUAGE_TAGS = setOf("en", "zh", "es", "de", "pt", "fr")
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A,
        )
    }
}
