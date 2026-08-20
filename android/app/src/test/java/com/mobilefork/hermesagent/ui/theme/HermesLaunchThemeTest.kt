package com.mobilefork.hermesagent.ui.theme

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import com.mobilefork.hermesagent.MainActivity
import com.mobilefork.hermesagent.R
import com.mobilefork.hermesagent.data.AppSettingsStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class HermesLaunchThemeTest {
    @Test
    @Config(sdk = [28])
    fun legacyStartingWindowPreviewIsSuppressedUntilTheSavedPaletteIsInstalled() {
        val themedContext = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.Theme_HermesAgent,
        )
        val values = themedContext.obtainStyledAttributes(
            intArrayOf(android.R.attr.windowDisablePreview),
        )
        try {
            assertTrue(values.getBoolean(0, false))
        } finally {
            values.recycle()
        }
    }

    @Test
    @Config(sdk = [31])
    fun android12StartingWindowUsesTheExplicitStaticHermesBrandContract() {
        val themedContext = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            R.style.Theme_HermesAgent,
        )
        val values = themedContext.obtainStyledAttributes(
            intArrayOf(
                android.R.attr.windowDisablePreview,
                android.R.attr.windowSplashScreenBackground,
                android.R.attr.windowSplashScreenAnimatedIcon,
            ),
        )
        try {
            assertFalse(values.getBoolean(0, true))
            assertEquals(R.color.hermes_startup_splash, values.getResourceId(1, 0))
            assertEquals(R.drawable.ic_launcher_foreground, values.getResourceId(2, 0))
        } finally {
            values.recycle()
        }
    }

    @Test
    @Config(sdk = [35])
    fun mainActivityInstallsThePersistedBackdropBeforeComposeContent() {
        val app = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(app)
        val original = store.load()
        try {
            listOf(
                original.copy(
                    themePrimaryHex = "#24D6A3",
                    themeSecondaryHex = "#F1B84B",
                    themeBackgroundHex = "#03090C",
                    themeSurfaceHex = "#0A1418",
                    themeSurfaceVariantHex = "#111E22",
                ),
                original.copy(
                    themePrimaryHex = "#1565C0",
                    themeSecondaryHex = "#8E24AA",
                    themeBackgroundHex = "#FAFBFF",
                    themeSurfaceHex = "#FFFFFF",
                    themeSurfaceVariantHex = "#E8EEF8",
                ),
            ).forEach { settings ->
                store.save(settings)
                val controller = Robolectric.buildActivity(MainActivity::class.java).create()
                val activity = controller.get()
                try {
                    val expected = hermesViewBackdropDrawable(hermesViewPalette(activity))
                    val actual = activity.window.decorView.background as? GradientDrawable
                        ?: throw AssertionError("MainActivity did not install the persisted startup gradient")
                    assertArrayEquals(requireNotNull(expected.colors), requireNotNull(actual.colors))
                } finally {
                    controller.destroy()
                }
            }
        } finally {
            store.save(original)
        }
    }

    @Test
    @Config(sdk = [24])
    fun lightCanvasUsesADarkLegacyNavigationBarScrimWhenDarkIconsAreUnavailable() {
        val app = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(app)
        val original = store.load()
        try {
            store.save(
                original.copy(
                    themeBackgroundHex = "#FAFBFF",
                    themeSurfaceHex = "#FFFFFF",
                    themeSurfaceVariantHex = "#E8EEF8",
                ),
            )
            val controller = Robolectric.buildActivity(MainActivity::class.java).create()
            try {
                assertEquals(
                    Color.argb(0x80, 0x1B, 0x1B, 0x1B),
                    controller.get().window.navigationBarColor,
                )
            } finally {
                controller.destroy()
            }
        } finally {
            store.save(original)
        }
    }
}
