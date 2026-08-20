package com.mobilefork.hermesagent.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesShellProcessCompatTest {
    @Test
    fun unsafePrivilegedResultPoisonsRetryGateBeforeSecondServiceDispatch() {
        val gate = PrivilegedShellRetryGate()
        var bindAttempts = 0
        var serviceDispatches = 0
        fun invoke(dispatch: () -> JSONObject): JSONObject {
            gate.blockedResultOrNull()?.let { return it }
            bindAttempts += 1
            return gate.executeAdmitted(dispatch)
        }

        val first = invoke {
            serviceDispatches += 1
            JSONObject()
                .put("success", false)
                .put("exit_code", 125)
                .put("error", "privileged descendant remained alive")
                .put("requires_service_restart", true)
        }
        val second = invoke {
            serviceDispatches += 1
            JSONObject().put("success", true).put("exit_code", 0)
        }

        assertFalse(first.optBoolean("success", true))
        assertEquals(1, bindAttempts)
        assertEquals(1, serviceDispatches)
        assertFalse(second.optBoolean("success", true))
        assertEquals(125, second.optInt("exit_code"))
        assertTrue(second.optBoolean("requires_service_restart"))
        assertTrue(second.optString("error").contains("will not bind another Shizuku user service"))
        assertTrue(second.optString("error").contains("Restart Shizuku"))
    }

    @Test
    fun admittedPrivilegedTransportFailureAlsoPoisonsRetryGate() {
        val gate = PrivilegedShellRetryGate()
        var serviceDispatches = 0

        val failure = runCatching {
            gate.executeAdmitted {
                serviceDispatches += 1
                throw IllegalStateException("binder died after dispatch")
            }
        }.exceptionOrNull()
        val retry = gate.executeAdmitted {
            serviceDispatches += 1
            JSONObject().put("success", true).put("exit_code", 0)
        }

        assertTrue(failure is IllegalStateException)
        assertEquals(1, serviceDispatches)
        assertFalse(retry.optBoolean("success", true))
        assertTrue(retry.optBoolean("requires_service_restart"))
        assertTrue(retry.optString("error").contains("binder or transport failure"))
    }

    @Test
    fun privilegedNaturalParentSuccessFailsClosedWhenDetachedOwnershipIsUnverified() {
        val result = privilegedShellCompletionDecision(
            finishedWithinTimeout = true,
            processUnwindVerified = false,
            readersCompleted = true,
            detachedProcessDetected = false,
            processExitCode = 0,
        )

        assertTrue(result.unsafe)
        assertFalse(result.success)
        assertEquals(125, result.exitCode)
    }

    @Test
    fun privilegedTimeoutRemainsTimeoutAfterOwnedCleanup() {
        val result = privilegedShellCompletionDecision(
            finishedWithinTimeout = false,
            processUnwindVerified = true,
            readersCompleted = true,
            detachedProcessDetected = false,
            processExitCode = null,
        )

        assertTrue(result.unsafe)
        assertFalse(result.success)
        assertEquals(124, result.exitCode)
    }

    @Test
    fun privilegedReaderWithoutEofCannotReportSuccessfulCleanup() {
        val result = privilegedShellCompletionDecision(
            finishedWithinTimeout = true,
            processUnwindVerified = true,
            readersCompleted = false,
            detachedProcessDetected = false,
            processExitCode = 0,
        )

        assertTrue(result.unsafe)
        assertFalse(result.success)
        assertEquals(125, result.exitCode)
    }

    @Test
    fun privilegedCleanedDetachedProcessIsRejectedWithoutRestartPoison() {
        val result = privilegedShellCompletionDecision(
            finishedWithinTimeout = true,
            processUnwindVerified = true,
            readersCompleted = true,
            detachedProcessDetected = true,
            processExitCode = 0,
        )

        assertFalse(result.unsafe)
        assertTrue(result.detachedProcessRejected)
        assertFalse(result.success)
        assertEquals(125, result.exitCode)
    }

    @Test
    fun alreadyExitedProcessCompletesWithoutCleanupSignals() {
        val handle = FakeShellProcessHandle(
            initiallyAlive = false,
            supportsForceDestroy = false,
        )

        val result = awaitOwnedShellProcess(
            current = handle,
            waitTimeoutMs = 0L,
            gracefulTimeoutMs = 0L,
            forcedTimeoutMs = 0L,
        )

        assertTrue(result.finishedWithinTimeout)
        assertTrue(result.processUnwindVerified)
        assertEquals(null, result.waitFailure)
        assertEquals(null, result.cleanupFailure)
        assertEquals(0, handle.gracefulStops)
        assertEquals(0, handle.forcedStops)
    }

    @Test
    fun api24TimedOutProcessCanExitGracefullyWithoutCallingUnavailableForce() {
        val handle = FakeShellProcessHandle(
            initiallyAlive = true,
            supportsForceDestroy = false,
            exitOnGracefulStop = true,
        )

        val result = awaitOwnedShellProcess(
            current = handle,
            waitTimeoutMs = 0L,
            gracefulTimeoutMs = 0L,
            forcedTimeoutMs = 0L,
        )

        assertFalse(result.finishedWithinTimeout)
        assertTrue(result.processUnwindVerified)
        assertEquals(null, result.waitFailure)
        assertEquals(null, result.cleanupFailure)
        assertEquals(1, handle.gracefulStops)
        assertEquals(0, handle.forcedStops)
        assertEquals(0, handle.exitValue())
    }

    @Test
    fun api24TimedOutProcessThatIgnoresGracefulStopFailsClosedWithoutForce() {
        val handle = FakeShellProcessHandle(
            initiallyAlive = true,
            supportsForceDestroy = false,
        )

        val result = awaitOwnedShellProcess(
            current = handle,
            waitTimeoutMs = 0L,
            gracefulTimeoutMs = 0L,
            forcedTimeoutMs = 0L,
        )

        assertFalse(result.finishedWithinTimeout)
        assertFalse(result.processUnwindVerified)
        assertEquals(null, result.waitFailure)
        assertTrue(result.cleanupFailure is IllegalStateException)
        assertTrue(result.cleanupFailure?.message.orEmpty().contains("requires Android 8.0 (API 26)"))
        assertEquals(1, handle.gracefulStops)
        assertEquals(0, handle.forcedStops)
    }

    @Test
    fun api26TimedOutProcessUsesForceOnlyAfterGracefulStopFails() {
        val handle = FakeShellProcessHandle(
            initiallyAlive = true,
            supportsForceDestroy = true,
            exitOnForcedStop = true,
        )

        val result = awaitOwnedShellProcess(
            current = handle,
            waitTimeoutMs = 0L,
            gracefulTimeoutMs = 0L,
            forcedTimeoutMs = 0L,
        )

        assertFalse(result.finishedWithinTimeout)
        assertTrue(result.processUnwindVerified)
        assertEquals(null, result.waitFailure)
        assertEquals(null, result.cleanupFailure)
        assertEquals(1, handle.gracefulStops)
        assertEquals(1, handle.forcedStops)
        assertEquals(0, handle.exitValue())
    }

    private class FakeShellProcessHandle(
        initiallyAlive: Boolean,
        override val supportsForceDestroy: Boolean,
        private val exitOnGracefulStop: Boolean = false,
        private val exitOnForcedStop: Boolean = false,
    ) : NativeShellProcessStopHandle {
        private var alive = initiallyAlive
        var gracefulStops: Int = 0
            private set
        var forcedStops: Int = 0
            private set

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("still alive")
            return 0
        }

        override fun destroy() {
            gracefulStops += 1
            if (exitOnGracefulStop) alive = false
        }

        override fun forceDestroy() {
            forcedStops += 1
            if (exitOnForcedStop) alive = false
        }
    }
}
