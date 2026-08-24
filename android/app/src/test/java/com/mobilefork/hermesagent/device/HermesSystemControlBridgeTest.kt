package com.mobilefork.hermesagent.device

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HermesSystemControlBridgeTest {
    @Test
    fun requestOwnedIntentSkipsSlowDerivedSnapshotSoStopWinsAAndIndependentBCommits() {
        val context = RuntimeEnvironment.getApplication()
        val shadowApplication = Shadows.shadowOf(context)
        while (shadowApplication.nextStartedActivity != null) {
            // Discard activity launches retained by another test in the shared Robolectric app.
        }

        val requestALock = Any()
        val requestACancelled = AtomicBoolean(false)
        val requestAReachedBoundary = CountDownLatch(1)
        val releaseRequestABoundary = CountDownLatch(1)
        val slowWriterEntered = CountDownLatch(1)
        val releaseSlowWriter = CountDownLatch(1)
        val requestAResult = AtomicReference<HermesSystemActionResult?>(null)
        val requestBResult = AtomicReference<HermesSystemActionResult?>(null)
        val derivedStateWriter: (android.content.Context) -> Unit = {
            slowWriterEntered.countDown()
            check(releaseSlowWriter.await(5, TimeUnit.SECONDS))
        }
        val requestAGate = AutomationPublicationGate { publication ->
            requestAReachedBoundary.countDown()
            check(releaseRequestABoundary.await(5, TimeUnit.SECONDS))
            synchronized(requestALock) {
                if (requestACancelled.get()) {
                    false
                } else {
                    publication()
                    true
                }
            }
        }
        val requestAWorker = thread(name = "system-intent-a", isDaemon = true) {
            requestAResult.set(
                HermesSystemControlBridge.performActionWithDerivedStateWriter(
                    context = context,
                    action = "open_wifi_panel",
                    publicationGate = requestAGate,
                    derivedDeviceStateWriter = derivedStateWriter,
                ),
            )
        }

        assertTrue(
            "request A did not reach its final Android system commit boundary",
            requestAReachedBoundary.await(5, TimeUnit.SECONDS),
        )
        synchronized(requestALock) {
            requestACancelled.set(true)
        }

        val requestBWorker = thread(name = "system-intent-b", isDaemon = true) {
            requestBResult.set(
                HermesSystemControlBridge.performActionWithDerivedStateWriter(
                    context = context,
                    action = "open_all_settings",
                    publicationGate = AutomationPublicationGate { publication ->
                        publication()
                        true
                    },
                    derivedDeviceStateWriter = derivedStateWriter,
                ),
            )
        }

        try {
            requestBWorker.join(1_000L)
            assertFalse(
                "request-owned B held its cancellation gate across the slow derived snapshot",
                requestBWorker.isAlive,
            )
            assertEquals(
                "request-owned action unexpectedly invoked the broad derived-state writer",
                1L,
                slowWriterEntered.count,
            )

            releaseRequestABoundary.countDown()
            requestAWorker.join(5_000L)
            assertFalse("request A remained alive after Stop won admission", requestAWorker.isAlive)
            assertFalse("stopped request A launched its settings intent", checkNotNull(requestAResult.get()).success)
            assertTrue("independent request B did not commit", checkNotNull(requestBResult.get()).success)

            val started = shadowApplication.nextStartedActivity
            assertEquals("request B did not produce the sole Android activity effect", Settings.ACTION_SETTINGS, started.action)
            assertNull("request A leaked a late activity launch after Stop", shadowApplication.nextStartedActivity)
        } finally {
            releaseSlowWriter.countDown()
            releaseRequestABoundary.countDown()
            requestAWorker.join(5_000L)
            requestBWorker.join(5_000L)
        }
    }

    @Test
    fun manualIntentPreservesDerivedSnapshotRefreshAfterLaunch() {
        val context = RuntimeEnvironment.getApplication()
        val shadowApplication = Shadows.shadowOf(context)
        while (shadowApplication.nextStartedActivity != null) {
            // Discard activity launches retained by another test in the shared Robolectric app.
        }
        var derivedStateWrites = 0

        val result = HermesSystemControlBridge.performActionWithDerivedStateWriter(
            context = context,
            action = "open_all_settings",
            publicationGate = null,
            derivedDeviceStateWriter = { derivedStateWrites += 1 },
        )

        assertTrue(result.success)
        assertEquals(1, derivedStateWrites)
        assertEquals(Settings.ACTION_SETTINGS, shadowApplication.nextStartedActivity.action)
    }
}
