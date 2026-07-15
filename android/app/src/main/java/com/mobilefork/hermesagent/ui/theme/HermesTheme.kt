package com.mobilefork.hermesagent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
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
    outline = Color(0xFF355057),
    outlineVariant = Color(0xFF20353A),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0C0C),
)

@Composable
fun HermesTheme(
    config: HermesThemeConfig = HermesThemeConfig(),
    content: @Composable () -> Unit,
) {
    val primary = parseThemeColor(config.primaryHex, HermesDarkColors.primary)
    val secondary = parseThemeColor(config.secondaryHex, HermesDarkColors.secondary)
    val background = parseThemeColor(config.backgroundHex, HermesDarkColors.background)
    val surface = parseThemeColor(config.surfaceHex, HermesDarkColors.surface)
    val surfaceVariant = parseThemeColor(config.surfaceVariantHex, HermesDarkColors.surfaceVariant)
    val colors = darkColorScheme(
        primary = primary,
        onPrimary = readableOn(primary),
        primaryContainer = surfaceVariant,
        onPrimaryContainer = readableOn(surfaceVariant),
        secondary = secondary,
        onSecondary = readableOn(secondary),
        secondaryContainer = secondary.copy(alpha = 0.18f),
        onSecondaryContainer = HermesDarkColors.onSecondaryContainer,
        background = background,
        onBackground = HermesDarkColors.onBackground,
        surface = surface,
        onSurface = HermesDarkColors.onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = HermesDarkColors.onSurfaceVariant,
        outline = HermesDarkColors.outline,
        outlineVariant = HermesDarkColors.outlineVariant,
        error = HermesDarkColors.error,
        onError = HermesDarkColors.onError,
    )
    MaterialTheme(
        colorScheme = colors,
        shapes = hermesShapes(config.cardShape),
        typography = scaledTypography(config.fontScale),
        content = content,
    )
}

private fun scaledTypography(rawScale: Float): Typography {
    val scale = rawScale.coerceIn(0.8f, 1.3f)
    val base = Typography()
    fun TextStyle.scaled() = copy(fontSize = fontSize * scale)
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

private fun parseThemeColor(value: String, fallback: Color): Color {
    val normalized = normalizeThemeHex(value, "")
    if (normalized.isBlank()) return fallback
    return runCatching { Color(android.graphics.Color.parseColor(normalized)) }.getOrDefault(fallback)
}

private fun readableOn(color: Color): Color {
    return if (color.luminance() > 0.54f) Color(0xFF121318) else Color.White
}

private fun hermesShapes(cardShape: String): Shapes {
    val normalized = cardShape.trim().lowercase()
    val small = when (normalized) {
        "square", "squared" -> 2.dp
        "soft" -> 8.dp
        else -> 12.dp
    }
    val medium = when (normalized) {
        "square", "squared" -> 4.dp
        "soft" -> 14.dp
        else -> 20.dp
    }
    val large = when (normalized) {
        "square", "squared" -> 6.dp
        "soft" -> 18.dp
        else -> 28.dp
    }
    return Shapes(
        extraSmall = RoundedCornerShape(small / 2),
        small = RoundedCornerShape(small),
        medium = RoundedCornerShape(medium),
        large = RoundedCornerShape(large),
        extraLarge = RoundedCornerShape(large + 6.dp),
    )
}
