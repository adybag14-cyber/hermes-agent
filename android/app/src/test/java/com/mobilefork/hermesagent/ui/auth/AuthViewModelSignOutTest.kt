package com.mobilefork.hermesagent.ui.auth

import com.mobilefork.hermesagent.models.LocalModelRuntimeSelectionAuthority
import com.mobilefork.hermesagent.models.RuntimeSelectionSupersededException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AuthViewModelSignOutTest {
    @Test
    fun durableSessionIsClearedOnlyAfterPythonStartsAndPersistedAuthIsCleared() {
        val generation = LocalModelRuntimeSelectionAuthority.beginAction()
        val events = mutableListOf<String>()

        completeRuntimeProviderSignOut(
            selectionGeneration = generation,
            preparePython = { events += "python-started" },
            clearPersistedPythonAuth = { events += "python-auth-cleared" },
            clearDurableSession = { events += "durable-session-cleared" },
        )

        assertEquals(
            listOf("python-started", "python-auth-cleared", "durable-session-cleared"),
            events,
        )
    }

    @Test
    fun pythonAuthClearFailureKeepsTheDurableSessionForRetry() {
        val generation = LocalModelRuntimeSelectionAuthority.beginAction()
        val durableClearCalls = AtomicInteger(0)

        assertThrows(IllegalStateException::class.java) {
            completeRuntimeProviderSignOut(
                selectionGeneration = generation,
                preparePython = {},
                clearPersistedPythonAuth = { error("fixture auth clear failure") },
                clearDurableSession = { durableClearCalls.incrementAndGet() },
            )
        }

        assertEquals(0, durableClearCalls.get())
    }

    @Test
    fun newerSelectionDuringPythonStartupRejectsSignOutBeforeEitherClear() {
        val olderGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        val startupEntered = CountDownLatch(1)
        val releaseStartup = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val pythonAuthClearCalls = AtomicInteger(0)
        val durableClearCalls = AtomicInteger(0)
        val failure = AtomicReference<Throwable?>(null)
        var worker: Thread? = null

        try {
            worker = Thread {
                try {
                    completeRuntimeProviderSignOut(
                        selectionGeneration = olderGeneration,
                        preparePython = {
                            startupEntered.countDown()
                            check(releaseStartup.await(5, TimeUnit.SECONDS))
                        },
                        clearPersistedPythonAuth = { pythonAuthClearCalls.incrementAndGet() },
                        clearDurableSession = { durableClearCalls.incrementAndGet() },
                    )
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    finished.countDown()
                }
            }.apply { isDaemon = true; start() }
            assertTrue(startupEntered.await(5, TimeUnit.SECONDS))

            LocalModelRuntimeSelectionAuthority.beginAction()
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS))
            releaseStartup.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))

            assertTrue(failure.get() is RuntimeSelectionSupersededException)
            assertEquals(0, pythonAuthClearCalls.get())
            assertEquals(0, durableClearCalls.get())
        } finally {
            releaseStartup.countDown()
            worker?.join(1_000)
        }
    }
}
