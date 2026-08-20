package com.mobilefork.hermesagent.device

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.ScrollView
import com.mobilefork.hermesagent.R
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.theme.hermesLocalizedContext
import com.mobilefork.hermesagent.ui.theme.hermesViewPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HermesExternalUiLocalizationTest {
    @Test
    fun everyManifestedHermesFrameworkPageUsesTheSharedGlassContainer() {
        val app = RuntimeEnvironment.getApplication()
        val providerIntent = Intent(app, HermesProviderSetupWebActivity::class.java)
            .putExtra(HermesProviderSetupWebActivity.EXTRA_URL, "")
            .putExtra(HermesProviderSetupWebActivity.EXTRA_TITLE, "")
        val activities = listOf(
            Robolectric.buildActivity(HermesTaskerPluginEditActivity::class.java).setup().get(),
            Robolectric.buildActivity(HermesTaskerConditionEditActivity::class.java).setup().get(),
            Robolectric.buildActivity(HermesTaskerEventEditActivity::class.java).setup().get(),
            Robolectric.buildActivity(HermesProviderSetupWebActivity::class.java, providerIntent).setup().get(),
        )

        activities.forEach { activity ->
            val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
            val page = contentRoot.getChildAt(0)
            assertTrue("${activity::class.java.simpleName} must use a scrollable responsive page", page is ScrollView)
            assertTrue(
                "${activity::class.java.simpleName} must use the saved-theme gradient",
                page.background is GradientDrawable,
            )
            val responsiveFrame = (page as ScrollView).getChildAt(0) as ViewGroup
            val glassPanel = responsiveFrame.getChildAt(0)
            assertTrue(
                "${activity::class.java.simpleName} must put its controls on a glass panel",
                glassPanel.background is GradientDrawable,
            )
        }
        activities.forEach(Activity::finish)
    }

    @Test
    fun providerToolbarStacksOnCompactPhonesAndUsesTheTabletRow() {
        assertEquals(android.widget.LinearLayout.VERTICAL, providerToolbarOrientation(360))
        assertEquals(android.widget.LinearLayout.HORIZONTAL, providerToolbarOrientation(800))
    }

    @Test
    fun taskerChoicesAndProviderFailuresFollowThePersistedAppLanguage() {
        val app = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(app)
        val original = store.load()
        try {
            store.save(original.copy(languageTag = AppLanguage.ENGLISH.tag))
            val englishContext = app.hermesLocalizedContext()
            val englishConditions = HermesTaskerConditionBridge.conditionChoices(englishContext).map { it.label }
            val englishEvents = HermesTaskerEventBridge.eventChoices(englishContext).map { it.label }
            val englishBlurbs = defaultTaskerBlurbs(englishContext)
            val englishFailures = listOf(
                englishContext.getString(R.string.hermes_provider_setup_offline_blocked),
                englishContext.getString(R.string.hermes_provider_setup_webview_unavailable, "missing"),
                englishContext.getString(R.string.hermes_provider_setup_webview_load_failed, "network error"),
                englishContext.getString(R.string.hermes_provider_setup_webview_http_error, 503),
            )

            AppLanguage.entries.filterNot { it == AppLanguage.ENGLISH }.forEach { language ->
                store.save(original.copy(languageTag = language.tag))
                val localizedContext = app.hermesLocalizedContext()
                val localizedConditions = HermesTaskerConditionBridge.conditionChoices(localizedContext).map { it.label }
                val localizedEvents = HermesTaskerEventBridge.eventChoices(localizedContext).map { it.label }
                val localizedBlurbs = defaultTaskerBlurbs(localizedContext)
                val localizedFailures = listOf(
                    localizedContext.getString(R.string.hermes_provider_setup_offline_blocked),
                    localizedContext.getString(R.string.hermes_provider_setup_webview_unavailable, "missing"),
                    localizedContext.getString(R.string.hermes_provider_setup_webview_load_failed, "network error"),
                    localizedContext.getString(R.string.hermes_provider_setup_webview_http_error, 503),
                )

                assertEquals(englishConditions.size, localizedConditions.size)
                assertEquals(englishEvents.size, localizedEvents.size)
                assertTrue(
                    "$language condition choices must all be localized",
                    localizedConditions.zip(englishConditions).all { (localized, english) -> localized != english },
                )
                assertTrue(
                    "$language event choices must all be localized",
                    localizedEvents.zip(englishEvents).all { (localized, english) -> localized != english },
                )
                assertEquals(englishBlurbs.size, localizedBlurbs.size)
                assertTrue(
                    "$language Tasker result blurbs must all be localized",
                    localizedBlurbs.zip(englishBlurbs).all { (localized, english) -> localized != english },
                )
                assertTrue(
                    "$language must localize every provider failure message",
                    localizedFailures.zip(englishFailures).all { (localized, english) -> localized != english },
                )
            }
        } finally {
            store.save(original)
        }
    }

    @Test
    fun customTaskerBlurbsArePreservedVerbatim() {
        val context = RuntimeEnvironment.getApplication().hermesLocalizedContext()
        val custom = "  User-authored Tasker label  "
        assertEquals(
            custom,
            HermesTaskerPluginBridge.buildResultIntent(context, "automation-id", custom)
                .getStringExtra(HermesTaskerPluginBridge.EXTRA_STRING_BLURB),
        )
        assertEquals(
            custom,
            HermesTaskerConditionBridge.buildResultIntent(
                context = context,
                conditionType = HermesTaskerConditionBridge.CONDITION_SHIZUKU_AVAILABLE,
                label = custom,
            ).getStringExtra(HermesTaskerConditionBridge.EXTRA_STRING_BLURB),
        )
        assertEquals(
            custom,
            HermesTaskerEventBridge.buildResultIntent(
                context = context,
                eventType = HermesTaskerEventBridge.EVENT_SHIZUKU_AVAILABLE,
                label = custom,
            ).getStringExtra(HermesTaskerEventBridge.EXTRA_STRING_BLURB),
        )
    }

    @Test
    fun frameworkViewPaletteCarriesEveryPersistedAppearanceKnob() {
        val app = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(app)
        val original = store.load()
        try {
            store.save(
                original.copy(
                    themePrimaryHex = "#112233",
                    themeSecondaryHex = "#445566",
                    themeBackgroundHex = "#F4F5F7",
                    themeSurfaceHex = "#E8EAED",
                    themeSurfaceVariantHex = "#DADCE0",
                    themeCardShape = "square",
                    uiFontScale = 1.2f,
                ),
            )

            val palette = hermesViewPalette(app)
            assertEquals(0xFF112233.toInt(), palette.primary)
            assertEquals(0xFF445566.toInt(), palette.secondary)
            assertEquals(0xFFF4F5F7.toInt(), palette.background)
            assertEquals(0xFFE8EAED.toInt(), palette.surface)
            assertEquals(0xFFDADCE0.toInt(), palette.surfaceVariant)
            assertTrue(palette.lightCanvas)
            assertEquals(4f, palette.cardCornerRadiusDp)
            assertEquals(1.2f, palette.fontScale)
        } finally {
            store.save(original)
        }
    }

    private fun defaultTaskerBlurbs(context: android.content.Context): List<String> {
        return buildList {
            add(
                requireNotNull(
                    HermesTaskerPluginBridge.buildResultIntent(context, "automation-id", "")
                        .getStringExtra(HermesTaskerPluginBridge.EXTRA_STRING_BLURB),
                ),
            )
            listOf(
                HermesTaskerConditionBridge.CONDITION_SHIZUKU_AVAILABLE,
                HermesTaskerConditionBridge.CONDITION_SHIZUKU_UNAVAILABLE,
                HermesTaskerConditionBridge.CONDITION_AUTOMATION_ENABLED,
                HermesTaskerConditionBridge.CONDITION_AUTOMATION_DISABLED,
                HermesTaskerConditionBridge.CONDITION_AUTOMATION_LAST_SUCCESS,
                HermesTaskerConditionBridge.CONDITION_AUTOMATION_LAST_FAILED,
                HermesTaskerConditionBridge.CONDITION_VARIABLE_SET,
                HermesTaskerConditionBridge.CONDITION_VARIABLE_EQUALS,
            ).forEach { conditionType ->
                add(
                    requireNotNull(
                        HermesTaskerConditionBridge.buildResultIntent(
                            context = context,
                            conditionType = conditionType,
                            automationId = "automation-id",
                            variableName = "READY",
                            expectedValue = "yes",
                        ).getStringExtra(HermesTaskerConditionBridge.EXTRA_STRING_BLURB),
                    ),
                )
            }
            listOf(
                HermesTaskerEventBridge.EVENT_AUTOMATION_FINISHED,
                HermesTaskerEventBridge.EVENT_AUTOMATION_SUCCEEDED,
                HermesTaskerEventBridge.EVENT_AUTOMATION_FAILED,
                HermesTaskerEventBridge.EVENT_SHIZUKU_AVAILABLE,
                HermesTaskerEventBridge.EVENT_SHIZUKU_UNAVAILABLE,
            ).forEach { eventType ->
                add(
                    requireNotNull(
                        HermesTaskerEventBridge.buildResultIntent(
                            context = context,
                            eventType = eventType,
                            automationId = "automation-id",
                        ).getStringExtra(HermesTaskerEventBridge.EXTRA_STRING_BLURB),
                    ),
                )
            }
        }
    }
}
