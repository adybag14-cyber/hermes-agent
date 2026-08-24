package com.mobilefork.hermesagent.device

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class HermesAccessibilityUiBridgeTest {
    @Test
    fun requestOwnedGestureReleasesStopLockThenWaitsForTerminalCallbackWhileBRemainsIsolated() {
        val requestALock = Any()
        val requestACancelled = AtomicBoolean(false)
        val requestAAccepted = CountDownLatch(1)
        val requestAGateReleased = CountDownLatch(1)
        val stopStarted = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val requestACallback = AtomicReference<HermesGestureResultSink?>(null)
        val requestAResult = AtomicReference<HermesAccessibilityUiBridge.UiCommitResult?>(null)
        val realEffects = CopyOnWriteArrayList<String>()
        val requestAGate = AutomationPublicationGate { publication ->
            val published = synchronized(requestALock) {
                if (requestACancelled.get()) {
                    false
                } else {
                    publication()
                    true
                }
            }
            requestAGateReleased.countDown()
            published
        }
        val requestAWorker = thread(name = "accessibility-gesture-a", isDaemon = true) {
            requestAResult.set(
                HermesAccessibilityUiBridge.commitGestureAction(
                    publicationGate = requestAGate,
                    immediateDispatch = { error("Request-owned gesture used the manual dispatch path") },
                    requestOwnedDispatch = {
                        HermesAccessibilityController.beginGestureDispatch(2_000L) { sink ->
                            requestACallback.set(
                                HermesGestureResultSink { completed ->
                                    if (completed) realEffects += "A"
                                    sink.report(completed)
                                },
                            )
                            requestAAccepted.countDown()
                            true
                        }
                    },
                ),
            )
        }

        assertTrue("request A did not reach accepted gesture dispatch", requestAAccepted.await(5, TimeUnit.SECONDS))
        assertTrue("request A did not release its short dispatch gate", requestAGateReleased.await(5, TimeUnit.SECONDS))
        val stopWorker = thread(name = "accessibility-gesture-stop-a", isDaemon = true) {
            stopStarted.countDown()
            synchronized(requestALock) {
                requestACancelled.set(true)
            }
            stopFinished.countDown()
        }

        try {
            assertTrue("Stop did not begin", stopStarted.await(5, TimeUnit.SECONDS))
            assertTrue(
                "Stop could not publish sticky cancellation while request A awaited its gesture callback",
                stopFinished.await(1, TimeUnit.SECONDS),
            )
            assertTrue("request A unwound before its gesture reached a terminal callback", requestAWorker.isAlive)

            val requestBResult = HermesAccessibilityUiBridge.commitGestureAction(
                publicationGate = AutomationPublicationGate { publication ->
                    publication()
                    true
                },
                immediateDispatch = { error("Request-owned gesture B used the manual dispatch path") },
                requestOwnedDispatch = {
                    HermesAccessibilityController.beginGestureDispatch(1_000L) { sink ->
                        checkNotNull(requestACallback.get()).report(completed = false)
                        realEffects += "B"
                        sink.report(completed = true)
                        true
                    }
                },
            )

            requestAWorker.join(5_000L)
            stopWorker.join(5_000L)
            assertFalse("request A remained alive after B cancelled its gesture", requestAWorker.isAlive)
            assertFalse("Stop remained blocked after request A reached a terminal callback", stopWorker.isAlive)
            assertTrue("independent request B did not complete its own gesture", requestBResult.performed)
            assertEquals(HermesGestureDispatchStatus.Completed.wireValue, requestBResult.gestureCompletionStatus)

            val terminalA = checkNotNull(requestAResult.get())
            assertFalse("request A reported success from accepted dispatch alone", terminalA.performed)
            assertFalse("request A was gate-rejected instead of terminally cancelled by B", terminalA.cancelled)
            assertEquals(HermesGestureDispatchStatus.Cancelled.wireValue, terminalA.gestureCompletionStatus)
            assertEquals("request A leaked a real effect across Stop/B", listOf("B"), realEffects.toList())
        } finally {
            requestACallback.get()?.report(completed = false)
            requestAWorker.join(5_000L)
            stopWorker.join(5_000L)
        }
    }

    @Test
    fun requestOwnedGestureWithoutTerminalCallbackFailsClosedWithinItsBound() {
        val startedAtNanos = System.nanoTime()
        val result = HermesAccessibilityUiBridge.commitGestureAction(
            publicationGate = AutomationPublicationGate { publication ->
                publication()
                true
            },
            immediateDispatch = { error("Request-owned gesture used the manual dispatch path") },
            requestOwnedDispatch = {
                HermesAccessibilityController.beginGestureDispatch(25L) { true }
            },
        )
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

        assertFalse(result.performed)
        assertFalse(result.cancelled)
        assertEquals(HermesGestureDispatchStatus.TimedOut.wireValue, result.gestureCompletionStatus)
        assertTrue("gesture completion wait was not bounded: ${elapsedMs}ms", elapsedMs < 1_000L)
    }

    @Test
    fun interruptedGestureWaitStillContainsDispatchUntilItsTerminalCallback() {
        val accepted = CountDownLatch(1)
        val callback = AtomicReference<HermesGestureResultSink?>(null)
        val result = AtomicReference<HermesGestureDispatchResult?>(null)
        val worker = thread(name = "accessibility-gesture-interrupted", isDaemon = true) {
            result.set(
                HermesAccessibilityController.awaitGestureDispatchCompletion(2_000L) { sink ->
                    callback.set(sink)
                    accepted.countDown()
                    true
                },
            )
        }

        assertTrue("gesture was not accepted", accepted.await(5, TimeUnit.SECONDS))
        try {
            worker.interrupt()
            worker.join(100L)
            assertTrue("interruption released ownership while the gesture was pending", worker.isAlive)

            checkNotNull(callback.get()).report(completed = false)
            worker.join(5_000L)
            assertFalse("terminal callback did not release the interrupted gesture wait", worker.isAlive)
            assertEquals(HermesGestureDispatchStatus.Interrupted, checkNotNull(result.get()).status)
        } finally {
            callback.get()?.report(completed = false)
            worker.join(5_000L)
        }
    }

    @Test
    fun manualGesturePreservesImmediateAcceptedDispatchContract() {
        var immediateDispatches = 0
        var requestOwnedDispatches = 0

        val result = HermesAccessibilityUiBridge.commitGestureAction(
            publicationGate = null,
            immediateDispatch = {
                immediateDispatches += 1
                true
            },
            requestOwnedDispatch = {
                requestOwnedDispatches += 1
                HermesAccessibilityController.beginGestureDispatch(1L) { sink ->
                    sink.report(completed = true)
                    true
                }
            },
        )

        assertTrue(result.performed)
        assertFalse(result.cancelled)
        assertEquals("", result.gestureCompletionStatus)
        assertEquals(1, immediateDispatches)
        assertEquals(0, requestOwnedDispatches)
    }

    @Test
    fun perceptualHash64IsStableBinaryAndVisual() {
        val leftDark = splitBitmap(leftColor = Color.BLACK, rightColor = Color.WHITE)
        val leftLight = splitBitmap(leftColor = Color.WHITE, rightColor = Color.BLACK)

        val firstHash = HermesAccessibilityUiBridge.perceptualHash64(leftDark)
        val repeatedHash = HermesAccessibilityUiBridge.perceptualHash64(leftDark)
        val oppositeHash = HermesAccessibilityUiBridge.perceptualHash64(leftLight)

        assertEquals(64, firstHash.length)
        assertTrue(firstHash.all { it == '0' || it == '1' })
        assertEquals(firstHash, repeatedHash)
        assertTrue(hammingDistance(firstHash, oppositeHash) >= 32)
    }

    private fun splitBitmap(leftColor: Int, rightColor: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                bitmap.setPixel(x, y, if (x < 8) leftColor else rightColor)
            }
        }
        return bitmap
    }

    private fun hammingDistance(left: String, right: String): Int {
        assertEquals(left.length, right.length)
        return left.indices.count { index -> left[index] != right[index] }
    }
}
