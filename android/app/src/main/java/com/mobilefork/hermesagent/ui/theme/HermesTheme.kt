package com.mobilefork.hermesagent.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.mobilefork.hermesagent.data.AppSettings

data class HermesThemeConfig(
    val primaryHex: String = AppSettings.DEFAULT_THEME_PRIMARY_HEX,
    val secondaryHex: String = AppSettings.DEFAULT_THEME_SECONDARY_HEX,
    val backgroundHex: String = AppSettings.DEFAULT_THEME_BACKGROUND_HEX,
    val surfaceHex: String = AppSettings.DEFAULT_THEME_SURFACE_HEX,
    val surfaceVariantHex: String = AppSettings.DEFAULT_THEME_SURFACE_VARIANT_HEX,
    val cardShape: String = "rounded",
    val fontScale: Float = 1.0f,
)

private val HermesDarkColors = darkColorScheme(
    primary = Color(0xFF24D6A3),
    onPrimary = Color(0xFF001F17),
    primaryContainer = Color(0xFF0C2A25),
    onPrimaryContainer = Color(0xFFB9FFE9),
    secondary = Color(0xFFF1B84B),
    onSecondary = Color(0xFF291900),
    secondaryContainer = Color(0xFF332817),
    onSecondaryContainer = Color(0xFFFFE0A3),
    background = Color(0xFF03090C),
    onBackground = Color(0xFFF2F3F5),
    surface = Color(0xFF0A1418),
    onSurface = Color(0xFFF2F3F5),
    surfaceVariant = Color(0xFF111E22),
    onSurfaceVariant = Color(0xFFC3CFD1),
    outline = Color(0xFF6A858B),
    outlineVariant = Color(0xFF355057),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0C0C),
)

/**
 * Applies every saved theme value to a complete, contrast-aware Material 3 scheme.
 *
 * The translucent surface roles create Hermes' own refractive-panel language. They
 * deliberately use simple Android gradients and alpha compositing rather than any
 * copied platform material, image, shader, or proprietary asset.
 */
@Composable
fun HermesTheme(
    config: HermesThemeConfig = HermesThemeConfig(),
    content: @Composable () -> Unit,
) {
    val primary = parseThemeColor(config.primaryHex, HermesDarkColors.primary).opaque()
    val secondary = parseThemeColor(config.secondaryHex, HermesDarkColors.secondary).opaque()
    val background = parseThemeColor(config.backgroundHex, HermesDarkColors.background).opaque()
    val surface = parseThemeColor(config.surfaceHex, HermesDarkColors.surface).opaque()
    val surfaceVariant = parseThemeColor(config.surfaceVariantHex, HermesDarkColors.surfaceVariant).opaque()
    val lightCanvas = background.luminance() >= 0.46f
    val contentTone = readableOn(background)
    val surfaceContent = readableOn(surface)
    val variantContent = readableOn(surfaceVariant)
    val outline = lerp(surfaceVariant, variantContent, if (lightCanvas) 0.42f else 0.50f).opaque()
    val outlineVariant = lerp(surfaceVariant, variantContent, if (lightCanvas) 0.22f else 0.28f).opaque()
    val primaryContainer = lerp(surface, primary, if (lightCanvas) 0.18f else 0.24f).opaque()
    val secondaryContainer = lerp(surface, secondary, if (lightCanvas) 0.16f else 0.22f).opaque()
    val tertiary = lerp(primary, secondary, 0.48f).opaque()
    val tertiaryContainer = lerp(surfaceVariant, tertiary, if (lightCanvas) 0.16f else 0.24f).opaque()
    val error = if (lightCanvas) Color(0xFFB3261E) else Color(0xFFFF6B6B)

    val colors = (if (lightCanvas) lightColorScheme() else darkColorScheme()).copy(
        primary = primary,
        onPrimary = readableOn(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = readableOn(primaryContainer),
        secondary = secondary,
        onSecondary = readableOn(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = readableOn(secondaryContainer),
        tertiary = tertiary,
        onTertiary = readableOn(tertiary),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = readableOn(tertiaryContainer),
        background = background,
        onBackground = contentTone,
        surface = surface.copy(alpha = 0.84f),
        onSurface = surfaceContent,
        surfaceVariant = surfaceVariant.copy(alpha = 0.72f),
        onSurfaceVariant = variantContent,
        surfaceTint = primary,
        inverseSurface = if (lightCanvas) Color(0xFF202428) else Color(0xFFE9EEF0),
        inverseOnSurface = if (lightCanvas) Color(0xFFF4F7F8) else Color(0xFF1A1E21),
        inversePrimary = lerp(primary, readableOn(primary), 0.28f),
        outline = outline,
        outlineVariant = outlineVariant,
        error = error,
        onError = readableOn(error),
        errorContainer = lerp(surface, error, 0.22f),
        onErrorContainer = readableOn(lerp(surface, error, 0.22f)),
        scrim = Color.Black,
        surfaceDim = surface.copy(alpha = 0.78f),
        surfaceBright = lerp(surface, contentTone, if (lightCanvas) 0.04f else 0.12f).copy(alpha = 0.90f),
        surfaceContainerLowest = surface.copy(alpha = 0.62f),
        surfaceContainerLow = lerp(surface, surfaceVariant, 0.18f).copy(alpha = 0.68f),
        surfaceContainer = lerp(surface, surfaceVariant, 0.30f).copy(alpha = 0.74f),
        surfaceContainerHigh = lerp(surface, surfaceVariant, 0.48f).copy(alpha = 0.80f),
        surfaceContainerHighest = surfaceVariant.copy(alpha = 0.86f),
    )
    val glassTokens = hermesGlassTokens(
        primary = primary,
        secondary = secondary,
        background = background,
        surface = surface,
        surfaceVariant = surfaceVariant,
        onSurface = surfaceContent,
        lightCanvas = lightCanvas,
    )
    val view = LocalView.current
    SideEffect {
        view.context.findActivity()?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightCanvas
                isAppearanceLightNavigationBars = lightCanvas
            }
        }
    }

    CompositionLocalProvider(LocalHermesGlassTokens provides glassTokens) {
        MaterialTheme(
            colorScheme = colors,
            shapes = hermesShapes(config.cardShape),
            typography = scaledTypography(config.fontScale),
            content = content,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun scaledTypography(rawScale: Float): Typography {
    val scale = rawScale.coerceIn(0.8f, 1.3f)
    val base = Typography()
    fun TextStyle.scaled() = copy(
        fontSize = fontSize * scale,
        lineHeight = lineHeight * scale,
    )
    return base.copy(
        displayLarge = base.displayLarge.scaled(),
        displayMedium = base.displayMedium.scaled(),
        displaySmall = base.displaySmall.scaled(),
        headlineLarge = base.headlineLarge.scaled(),
        headlineMedium = base.headlineMedium.scaled(),
        headlineSmall = base.headlineSmall.scaled(),
        titleLarge = base.titleLarge.scaled(),
        titleMedium = base.titleMedium.scaled(),
        titleSmall = base.titleSmall.scaled(),
        bodyLarge = base.bodyLarge.scaled(),
        bodyMedium = base.bodyMedium.scaled(),
        bodySmall = base.bodySmall.scaled(),
        labelLarge = base.labelLarge.scaled(),
        labelMedium = base.labelMedium.scaled(),
        labelSmall = base.labelSmall.scaled(),
    )
}

fun normalizeThemeHex(value: String, fallback: String): String {
    val trimmed = value.trim()
    val raw = if (trimmed.startsWith("#")) trimmed.drop(1) else trimmed
    if (!Regex("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}").matches(raw)) {
        return fallback
    }
    return "#${raw.uppercase()}"
}

internal fun normalizeThemeCardShape(value: String): String = when (value.trim().lowercase()) {
    "square", "squared" -> "square"
    "soft" -> "soft"
    else -> "rounded"
}

private fun parseThemeColor(value: String, fallback: Color): Color {
    val normalized = normalizeThemeHex(value, "")
    if (normalized.isBlank()) return fallback
    return runCatching { Color(android.graphics.Color.parseColor(normalized)) }.getOrDefault(fallback)
}

internal fun readableOn(color: Color): Color {
    val background = color.opaque()
    val darkCandidate = Color(0xFF111318)
    val lightCandidate = Color(0xFFF8FAFC)
    val darkContrast = contrastRatio(background, darkCandidate)
    val lightContrast = contrastRatio(background, lightCandidate)
    return if (darkContrast >= lightContrast) darkCandidate else lightCandidate
}

internal fun contrastRatio(first: Color, second: Color): Float {
    val firstLuminance = first.opaque().luminance()
    val secondLuminance = second.opaque().luminance()
    val lighter = maxOf(firstLuminance, secondLuminance)
    val darker = minOf(firstLuminance, secondLuminance)
    return (lighter + 0.05f) / (darker + 0.05f)
}

private fun Color.opaque(): Color = copy(alpha = 1f)

internal fun hermesShapes(cardShape: String): Shapes {
    return when (normalizeThemeCardShape(cardShape)) {
        "square" -> Shapes(
            extraSmall = RoundedCornerShape(1.dp),
            small = RoundedCornerShape(2.dp),
            medium = RoundedCornerShape(4.dp),
            large = RoundedCornerShape(6.dp),
            extraLarge = RoundedCornerShape(8.dp),
        )
        "soft" -> Shapes(
            extraSmall = RoundedCornerShape(5.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(18.dp),
            extraLarge = RoundedCornerShape(24.dp),
        )
        else -> Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(34.dp),
        )
    }
}
