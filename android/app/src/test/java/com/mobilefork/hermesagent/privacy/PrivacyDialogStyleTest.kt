package com.mobilefork.hermesagent.privacy

import androidx.compose.ui.graphics.Color
import com.mobilefork.hermesagent.ui.privacy.opaquePrivacyDialogColor
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyDialogStyleTest {
    @Test fun prominentDialogsPreserveSelectedColorWithoutShowingUnderlyingText() {
        for (color in listOf(Color(0xCC0A1418), Color(0x44F5ECD8), Color(0xFF102938))) {
            val opaque = opaquePrivacyDialogColor(color)
            assertEquals(1f, opaque.alpha, 0f)
            assertEquals(color.red, opaque.red, 0f)
            assertEquals(color.green, opaque.green, 0f)
            assertEquals(color.blue, opaque.blue, 0f)
        }
    }
}
