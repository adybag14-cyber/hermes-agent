package com.mobilefork.hermesagent.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
}
