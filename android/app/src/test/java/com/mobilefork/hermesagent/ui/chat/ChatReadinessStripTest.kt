package com.mobilefork.hermesagent.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatReadinessStripTest {
    @Test
    fun stoppedPythonIsReportedAsIdleBecauseTheStripDoesNotStartIt() {
        assertEquals(
            "idle",
            pythonReadinessLabel(pythonReady = false, remoteReadyWithoutPython = false),
        )
    }

    @Test
    fun runningAndOptionalPythonStatesRemainDistinct() {
        assertEquals(
            "up",
            pythonReadinessLabel(pythonReady = true, remoteReadyWithoutPython = false),
        )
        assertEquals(
            "optional",
            pythonReadinessLabel(pythonReady = false, remoteReadyWithoutPython = true),
        )
    }
}
