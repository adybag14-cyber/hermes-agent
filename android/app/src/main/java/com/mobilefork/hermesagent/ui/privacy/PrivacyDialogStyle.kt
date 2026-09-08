package com.mobilefork.hermesagent.ui.privacy

import androidx.compose.ui.graphics.Color

/** Consent and destructive-action text must not compete with text behind a glass-themed window. */
internal fun opaquePrivacyDialogColor(surface: Color): Color = surface.copy(alpha = 1f)
