package com.mobilefork.hermesagent.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellThemeShapeTest {
    @Test
    fun shellDoesNotDowngradeSavedSquareShapeToRounded() {
        assertEquals("square", normalizeShellThemeCardShape("square"))
        assertEquals("square", normalizeShellThemeCardShape("squared"))
        assertEquals("soft", normalizeShellThemeCardShape("soft"))
        assertEquals("rounded", normalizeShellThemeCardShape("rounded"))
    }
}
