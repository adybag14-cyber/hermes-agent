package com.mobilefork.hermesagent.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PythonRuntimeWriteAuthorityTest {
    @Test
    fun newerDurableSelectionWaitsForAnAdmittedOlderPythonWriteThenRemainsLast() {
        val olderGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        val olderWriteEntered = CountDownLatch(1)
        val releaseOlderWrite = CountDownLatch(1)
        val olderFinished = CountDownLatch(1)
        val newerAttempted = CountDownLatch(1)
        val newerDurableSelection = CountDownLatch(1)
        val newerFinished = CountDownLatch(1)
        val olderFailure = AtomicReference<Throwable?>(null)
        val newerFailure = AtomicReference<Throwable?>(null)
        val writes = mutableListOf<String>()
        var olderThread: Thread? = null
        var newerThread: Thread? = null

        try {
            olderThread = Thread {
                try {
                    PythonRuntimeWriteAuthority.writeIfCurrent(olderGeneration) {
                        olderWriteEntered.countDown()
                        check(releaseOlderWrite.await(5, TimeUnit.SECONDS))
                        writes += "older"
                    }
                } catch (error: Throwable) {
                    olderFailure.set(error)
                } finally {
                    olderFinished.countDown()
                }
            }.apply { isDaemon = true; start() }
            assertTrue(olderWriteEntered.await(5, TimeUnit.SECONDS))

            newerThread = Thread {
                try {
                    newerAttempted.countDown()
                    val newerGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
                    // This represents the newer durable AppSettings commit which must not be
                    // followed by an older Python credential/config write.
                    newerDurableSelection.countDown()
                    PythonRuntimeWriteAuthority.writeIfCurrent(newerGeneration) {
                        writes += "newer"
                    }
                } catch (error: Throwable) {
                    newerFailure.set(error)
                } finally {
                    newerFinished.countDown()
                }
            }.apply { isDaemon = true; start() }

            assertTrue(newerAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(
                "newer durable selection overtook an admitted Python write",
                newerDurableSelection.await(150, TimeUnit.MILLISECONDS),
            )
            releaseOlderWrite.countDown()
            assertTrue(olderFinished.await(5, TimeUnit.SECONDS))
            assertTrue(newerDurableSelection.await(5, TimeUnit.SECONDS))
            assertTrue(newerFinished.await(5, TimeUnit.SECONDS))
            assertNull(olderFailure.get())
            assertNull(newerFailure.get())
            assertEquals(listOf("older", "newer"), writes)
        } finally {
            releaseOlderWrite.countDown()
            olderThread?.join(1_000)
            newerThread?.join(1_000)
        }
    }

    @Test
    fun writerDelayedBehindANewerSelectionIsRejectedBeforeMutation() {
        val olderGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        val newerGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        val writes = mutableListOf<String>()

        PythonRuntimeWriteAuthority.writeIfCurrent(newerGeneration) {
            writes += "newer"
        }
        assertThrows(RuntimeSelectionSupersededException::class.java) {
            PythonRuntimeWriteAuthority.writeIfCurrent(olderGeneration) {
                writes += "older"
            }
        }

        assertEquals(listOf("newer"), writes)
    }
}
