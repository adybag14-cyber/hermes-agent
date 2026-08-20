package com.mobilefork.hermesagent.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.core.graphics.Insets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesThemeTest {
    @Test
    fun lightAndDarkCanvasThresholdSelectsTheExpectedMaterialFamily() {
        assertTrue(Color(0xFFF4F5F7).luminance() >= 0.46f)
        assertTrue(Color(0xFF03090C).luminance() < 0.46f)
    }

    @Test
    fun cardShapeNormalizerPreservesEverySupportedShape() {
        assertEquals("square", normalizeThemeCardShape("square"))
        assertEquals("square", normalizeThemeCardShape("SQUARED"))
        assertEquals("soft", normalizeThemeCardShape(" soft "))
        assertEquals("rounded", normalizeThemeCardShape("rounded"))
        assertEquals("rounded", normalizeThemeCardShape("unsupported"))
    }

    @Test
    fun squareShapeIsActuallySharperThanSoftAndRounded() {
        val square = hermesShapes("square")
        val soft = hermesShapes("soft")
        val rounded = hermesShapes("rounded")

        assertTrue(square.medium != soft.medium)
        assertTrue(square.large != rounded.large)
    }

    @Test
    fun midToneCustomThemeChoosesTheHigherContrastDarkText() {
        val midTone = Color(0xFF909090)
        val darkCandidate = Color(0xFF111318)
        val lightCandidate = Color(0xFFF8FAFC)

        assertEquals(darkCandidate, readableOn(midTone))
        assertTrue(contrastRatio(midTone, darkCandidate) > contrastRatio(midTone, lightCandidate))
    }

    @Test
    fun androidViewPaletteUsesTheSameHigherContrastChoiceForMidTones() {
        assertEquals(0xFF111318.toInt(), readableViewColor(0xFF909090.toInt()))
    }

    @Test
    fun frameworkViewCornerRadiiPreserveEverySavedShapeChoice() {
        val square = hermesViewCornerRadiusDp("square")
        val soft = hermesViewCornerRadiusDp("soft")
        val rounded = hermesViewCornerRadiusDp("rounded")

        assertTrue(square < soft)
        assertTrue(soft < rounded)
    }

    @Test
    fun frameworkViewPagesUseCompactPhoneAndTabletPaddingBuckets() {
        assertEquals(12f, hermesViewHorizontalPaddingDp(320))
        assertEquals(16f, hermesViewHorizontalPaddingDp(411))
        assertEquals(24f, hermesViewHorizontalPaddingDp(800))
        assertEquals(32f, hermesViewHorizontalPaddingDp(1_000))
    }

    @Test
    fun frameworkNavigationBarColorProtectsLightCanvasesBeforeDarkIconsAreAvailable() {
        val surface = 0xFFF4F5F7.toInt()
        val lightPalette = viewPaletteForNavigationBarTest(surface = surface, lightCanvas = true)
        val darkPalette = viewPaletteForNavigationBarTest(surface = surface, lightCanvas = false)

        assertEquals(0x801B1B1B.toInt(), HERMES_LEGACY_LIGHT_NAVIGATION_BAR_SCRIM)
        assertEquals(HERMES_LEGACY_LIGHT_NAVIGATION_BAR_SCRIM, resolveHermesViewNavigationBarColor(lightPalette, 24))
        assertEquals(HERMES_LEGACY_LIGHT_NAVIGATION_BAR_SCRIM, resolveHermesViewNavigationBarColor(lightPalette, 25))
        assertEquals(surface, resolveHermesViewNavigationBarColor(lightPalette, 26))
        listOf(24, 25, 26).forEach { sdkInt ->
            assertEquals(surface, resolveHermesViewNavigationBarColor(darkPalette, sdkInt))
        }
    }

    @Test
    fun frameworkViewPageLayoutCombinesSafeInsetsAndOuterPaddingOnEveryEdge() {
        val layout = resolveHermesScrollablePageLayout(
            viewportWidthPx = 1_080,
            outerPaddingPx = 36,
            maxContentWidthPx = 2_280,
            safeInsets = Insets.of(24, 136, 18, 72),
        )

        assertEquals(966, layout.contentWidthPx)
        assertEquals(60, layout.framePaddingLeftPx)
        assertEquals(172, layout.framePaddingTopPx)
        assertEquals(54, layout.framePaddingRightPx)
        assertEquals(108, layout.framePaddingBottomPx)
    }

    @Test
    fun frameworkViewPageLayoutPreservesThe760DpWideContentCapAfterInsets() {
        val layout = resolveHermesScrollablePageLayout(
            viewportWidthPx = 3_000,
            outerPaddingPx = 96,
            maxContentWidthPx = 2_280,
            safeInsets = Insets.of(48, 0, 54, 72),
        )

        assertEquals(2_280, layout.contentWidthPx)
        assertEquals(144, layout.framePaddingLeftPx)
        assertEquals(150, layout.framePaddingRightPx)
    }

    private fun viewPaletteForNavigationBarTest(surface: Int, lightCanvas: Boolean): HermesViewPalette {
        return HermesViewPalette(
            primary = 0,
            secondary = 0,
            background = 0,
            surface = surface,
            surfaceVariant = 0,
            onPrimary = 0,
            onBackground = 0,
            onSurface = 0,
            onSurfaceVariant = 0,
            lightCanvas = lightCanvas,
            cardCornerRadiusDp = 22f,
            fontScale = 1f,
        )
    }
}
