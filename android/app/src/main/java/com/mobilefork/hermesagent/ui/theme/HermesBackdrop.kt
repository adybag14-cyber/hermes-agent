package com.mobilefork.hermesagent.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Semantic colors for Hermes' original translucent, refractive panel language. */
@Immutable
data class HermesGlassTokens(
    val panel: Color,
    val elevatedPanel: Color,
    val border: Color,
    val highlight: Color,
    val accentGlow: Color,
    val secondaryGlow: Color,
    val backdropTop: Color,
    val backdropMiddle: Color,
    val backdropBottom: Color,
)

val LocalHermesGlassTokens = staticCompositionLocalOf {
    HermesGlassTokens(
        panel = Color(0xD90A1418),
        elevatedPanel = Color(0xEE111E22),
        border = Color(0x66355057),
        highlight = Color(0x24FFFFFF),
        accentGlow = Color(0x3324D6A3),
        secondaryGlow = Color(0x24F1B84B),
        backdropTop = Color(0xFF06211C),
        backdropMiddle = Color(0xFF03090C),
        backdropBottom = Color(0xFF071217),
    )
}

internal fun hermesGlassTokens(
    primary: Color,
    secondary: Color,
    background: Color,
    surface: Color,
    surfaceVariant: Color,
    onSurface: Color,
    lightCanvas: Boolean,
): HermesGlassTokens {
    return HermesGlassTokens(
        panel = surface.copy(alpha = if (lightCanvas) 0.76f else 0.68f),
        elevatedPanel = surfaceVariant.copy(alpha = if (lightCanvas) 0.86f else 0.80f),
        border = lerp(surfaceVariant, onSurface, if (lightCanvas) 0.24f else 0.34f).copy(alpha = 0.72f),
        highlight = onSurface.copy(alpha = if (lightCanvas) 0.18f else 0.12f),
        accentGlow = primary.copy(alpha = if (lightCanvas) 0.18f else 0.24f),
        secondaryGlow = secondary.copy(alpha = if (lightCanvas) 0.12f else 0.18f),
        backdropTop = lerp(background, primary, if (lightCanvas) 0.08f else 0.16f),
        backdropMiddle = background,
        backdropBottom = lerp(background, secondary, if (lightCanvas) 0.05f else 0.09f),
    )
}

/** Shared canvas used by every top-level Hermes destination. */
@Composable
fun HermesBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = LocalHermesGlassTokens.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to tokens.backdropTop,
                        0.44f to tokens.backdropMiddle,
                        1.0f to tokens.backdropBottom,
                    ),
                ),
            )
            .background(
                Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to tokens.accentGlow,
                        0.56f to Color.Transparent,
                    ),
                    radius = 920f,
                ),
            ),
        content = content,
    )
}

/** Transparent top-level surface color; panels use Material surface-container roles. */
fun hermesPanelColor(): Color = Color.Transparent
