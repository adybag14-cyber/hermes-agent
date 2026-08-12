package com.mobilefork.hermesagent.ui.theme

import android.content.Context
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import kotlin.math.pow

/** Theme snapshot for Android Views, RemoteViews, overlays, and notifications. */
data class HermesViewPalette(
    val primary: Int,
    val secondary: Int,
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val onPrimary: Int,
    val onBackground: Int,
    val onSurface: Int,
)

fun hermesViewPalette(context: Context): HermesViewPalette {
    val settings = AppSettingsStore(context.applicationContext).load()
    val primary = parseOpaqueColor(settings.themePrimaryHex, AppSettings.DEFAULT_THEME_PRIMARY_HEX)
    val secondary = parseOpaqueColor(settings.themeSecondaryHex, AppSettings.DEFAULT_THEME_SECONDARY_HEX)
    val background = parseOpaqueColor(settings.themeBackgroundHex, AppSettings.DEFAULT_THEME_BACKGROUND_HEX)
    val surface = parseOpaqueColor(settings.themeSurfaceHex, AppSettings.DEFAULT_THEME_SURFACE_HEX)
    val surfaceVariant = parseOpaqueColor(settings.themeSurfaceVariantHex, AppSettings.DEFAULT_THEME_SURFACE_VARIANT_HEX)
    return HermesViewPalette(
        primary = primary,
        secondary = secondary,
        background = background,
        surface = surface,
        surfaceVariant = surfaceVariant,
        onPrimary = readableViewColor(primary),
        onBackground = readableViewColor(background),
        onSurface = readableViewColor(surface),
    )
}

@ColorInt
private fun parseOpaqueColor(value: String, fallback: String): Int {
    val resolved = runCatching { Color.parseColor(value.trim()) }
        .getOrElse { Color.parseColor(fallback) }
    return ColorUtils.setAlphaComponent(resolved, 255)
}

@ColorInt
internal fun readableViewColor(@ColorInt color: Int): Int {
    // Literal ARGB values keep this pure helper usable in local JVM tests;
    // android.graphics.Color channel helpers are framework stubs outside Robolectric.
    val darkCandidate = 0xFF111318.toInt()
    val lightCandidate = 0xFFF8FAFC.toInt()
    val background = color or 0xFF000000.toInt()
    val darkContrast = viewContrastRatio(darkCandidate, background)
    val lightContrast = viewContrastRatio(lightCandidate, background)
    return if (darkContrast >= lightContrast) darkCandidate else lightCandidate
}

private fun viewContrastRatio(first: Int, second: Int): Double {
    val lighter = maxOf(viewRelativeLuminance(first), viewRelativeLuminance(second))
    val darker = minOf(viewRelativeLuminance(first), viewRelativeLuminance(second))
    return (lighter + 0.05) / (darker + 0.05)
}

private fun viewRelativeLuminance(color: Int): Double {
    fun linearChannel(shift: Int): Double {
        val encoded = ((color ushr shift) and 0xFF) / 255.0
        return if (encoded <= 0.04045) encoded / 12.92 else ((encoded + 0.055) / 1.055).pow(2.4)
    }

    return 0.2126 * linearChannel(16) +
        0.7152 * linearChannel(8) +
        0.0722 * linearChannel(0)
}
