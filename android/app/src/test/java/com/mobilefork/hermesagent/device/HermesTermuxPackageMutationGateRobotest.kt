package com.mobilefork.hermesagent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class HermesTermuxPackageMutationGateRobotest {
    @Test
    fun stoppedMirrorMutationAIsRejectedWhileIndependentRequestBCommits() {
        val context = RuntimeEnvironment.getApplication()
        val originalProfile = HermesTermuxMirrorConfig.mirrorProfile(context)
        val cancelledA = AtomicBoolean(false)
        val aReachedPublication = CountDownLatch(1)
        val releaseAPublication = CountDownLatch(1)
        val failureA = AtomicReference<Throwable?>(null)
        val gateA = AutomationPublicationGate { publication ->
            aReachedPublication.countDown()
            check(releaseAPublication.await(5, TimeUnit.SECONDS))
            if (cancelledA.get()) {
                false
            } else {
                publication()
                true
            }
        }
        val gateB = AutomationPublicationGate { publication ->
            publication()
            true
        }

        HermesTermuxMirrorConfig.setMirrorProfile(context, "default")
        try {
            val workerA = thread(name = "host-pkg-mirror-publication-a", isDaemon = true) {
                failureA.set(
                    runCatching {
                        HermesTermuxPackageManager.performAction(
                            context = context,
                            action = "set_mirror",
                            mirrorProfile = "default",
                            cancellationRequested = { cancelledA.get() },
                            publicationGate = gateA,
                            requestOwned = true,
                        )
                    }.exceptionOrNull(),
                )
            }
            assertTrue("request A never reached its host-package mutation boundary", aReachedPublication.await(5, TimeUnit.SECONDS))

            HermesTermuxPackageManager.performAction(
                context = context,
                action = "set_mirror",
                mirrorProfile = "china",
                publicationGate = gateB,
                requestOwned = true,
            )
            assertEquals("china", HermesTermuxMirrorConfig.mirrorProfile(context))

            cancelledA.set(true)
            releaseAPublication.countDown()
            workerA.join(5_000L)

            assertFalse("request A remained blocked after Stop", workerA.isAlive)
            assertTrue(failureA.get() is CancellationException)
            assertEquals("china", HermesTermuxMirrorConfig.mirrorProfile(context))
        } finally {
            releaseAPublication.countDown()
            HermesTermuxMirrorConfig.setMirrorProfile(context, originalProfile)
        }
    }
}
