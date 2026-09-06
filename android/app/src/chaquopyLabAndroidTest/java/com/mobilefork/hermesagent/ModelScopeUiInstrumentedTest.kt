package com.mobilefork.hermesagent

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.models.VerifiedLocalModelArtifacts
import com.mobilefork.hermesagent.models.VerifiedLocalModelMirrors
import com.mobilefork.hermesagent.ui.boot.BootUiState
import com.mobilefork.hermesagent.ui.i18n.*
import com.mobilefork.hermesagent.ui.settings.LocalModelDownloadsViewModel
import com.mobilefork.hermesagent.ui.shell.AppShellScreen
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in headed lab check; no claim of full release UI certification. */
@RunWith(AndroidJUnit4::class)
class ModelScopeUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun languageControlsRenderMirrorActionsAndLicenceWarningsInEveryLanguage() {
        assertTrue(BuildConfig.HERMES_CHAQUOPY_LAB)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettingsStore(context)
        val original = settings.load()
        val evidence = File(context.filesDir, "model-experiments/modelscope-ui").apply { mkdirs() }
        val captures = JSONArray()
        try {
            settings.save(AppSettings(onDeviceBackend = "none", languageTag = "en", portalEnabled = false))
            compose.setContent {
                AppShellScreen(
                    bootUiState = BootUiState(status = "ModelScope UI lab", ready = true, probeResult = "UI only"),
                    onRetryHermes = {},
                )
            }
            compose.waitForIdle()
            val drawer = if (compose.onAllNodesWithTag("HermesShellDrawerButton").fetchSemanticsNodes().isNotEmpty()) {
                "HermesShellDrawerButton"
            } else "HermesChatDrawerButton"
            compose.onNodeWithTag(drawer).performClick()
            compose.onNodeWithTag("HermesNavSettings").performClick()
            val list = compose.onNodeWithTag("HermesSettingsContentList")
            val presets = LocalModelDownloadsViewModel.recommendedModelPresets.filter { preset ->
                VerifiedLocalModelArtifacts.find(preset.repoOrUrl, preset.filePath)?.let {
                    VerifiedLocalModelMirrors.forArtifact(it)
                } != null
            }
            assertTrue("No mirror-backed presets rendered", presets.isNotEmpty())
            for (language in AppLanguage.entries) {
                val strings = hermesStringsFor(language)
                list.performScrollToIndex(0)
                compose.onNodeWithTag("HermesSettingsPage_Overview").performClick()
                list.performScrollToNode(hasTestTag("SettingsLanguage-${language.tag}"))
                compose.onNodeWithTag("SettingsLanguage-${language.tag}").performClick()
                compose.waitUntil(5_000) { settings.load().languageTag == language.tag }
                list.performScrollToIndex(0)
                compose.onNodeWithTag("HermesSettingsPage_Models").performClick()
                for (preset in presets) {
                    val artifact = VerifiedLocalModelArtifacts.require(preset.repoOrUrl, preset.filePath)
                    val mirror = requireNotNull(VerifiedLocalModelMirrors.forArtifact(artifact))
                    val buttonTag = "ModelScopeMirror-${preset.id}"
                    list.performScrollToNode(hasTestTag(buttonTag))
                    compose.onNodeWithTag(buttonTag).performScrollTo().assertIsDisplayed()
                        .assertTextEquals(strings.modelScopeMirrorButton())
                    compose.onNodeWithTag("ModelScopeNote-${preset.id}").assertTextEquals(strings.modelScopeMirrorNote())
                    if (mirror.researchOnly) {
                        compose.onNodeWithTag("ModelScopeResearch-${preset.id}").assertTextEquals(strings.modelScopeResearchNotice())
                    }
                    compose.onNodeWithTag("ModelScopeLicences-${preset.id}").performScrollTo().assertIsDisplayed()
                        .assertTextEquals(strings.modelScopeLicencesButton())
                    val name = "${language.tag}-${preset.id}"
                    File(evidence, "$name.png").outputStream().use {
                        compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    File(evidence, "$name.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
                    captures.put(JSONObject().put("language", language.tag).put("preset", preset.id)
                        .put("screenshot", "$name.png").put("research_notice_visible", mirror.researchOnly))
                }
            }
            File(evidence, "report.json").writeText(JSONObject().put("status", "passed")
                .put("release_certified", false).put("captures", captures).toString(2))
        } finally {
            settings.save(original)
        }
    }
}
