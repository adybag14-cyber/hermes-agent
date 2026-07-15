package com.mobilefork.hermesagent.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme

/** Shared emerald-noir canvas used by every top-level Hermes destination. */
@Composable
fun HermesBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to colors.primary.copy(alpha = 0.11f),
                        0.22f to colors.background,
                        0.72f to colors.background,
                        1.0f to colors.surface.copy(alpha = 0.96f),
                    ),
                ),
            ),
        content = content,
    )
}

fun hermesPanelColor(): Color = Color.Transparent
