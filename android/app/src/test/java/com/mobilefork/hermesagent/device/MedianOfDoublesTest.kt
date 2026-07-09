package com.mobilefork.hermesagent.device

import org.junit.Assert.assertEquals
import org.junit.Test

class MedianOfDoublesTest {
    @Test
    fun medianOfDoubles_returnsZeroForEmptyList() {
        assertEquals(0.0, HermesDeviceDiagnosticsBridge.medianOfDoubles(emptyList()), 0.0)
    }

    @Test
    fun medianOfDoubles_returnsMiddleValueForOddCount() {
        assertEquals(10.2, HermesDeviceDiagnosticsBridge.medianOfDoubles(listOf(9.81, 10.2, 11.18)), 0.001)
        assertEquals(5.0, HermesDeviceDiagnosticsBridge.medianOfDoubles(listOf(1.0, 5.0, 9.0)), 0.001)
    }

    @Test
    fun medianOfDoubles_averagesMiddlePairForEvenCount() {
        assertEquals(10.5, HermesDeviceDiagnosticsBridge.medianOfDoubles(listOf(9.81, 10.2, 10.8, 11.18)), 0.001)
        assertEquals(3.5, HermesDeviceDiagnosticsBridge.medianOfDoubles(listOf(1.0, 2.0, 5.0, 6.0)), 0.001)
    }

    @Test
    fun medianOfDoubles_sortsBeforeComputing() {
        assertEquals(10.5, HermesDeviceDiagnosticsBridge.medianOfDoubles(listOf(11.18, 9.81, 10.2, 10.8)), 0.001)
    }
}