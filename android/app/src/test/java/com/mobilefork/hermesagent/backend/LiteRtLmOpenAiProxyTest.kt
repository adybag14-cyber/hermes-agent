package com.mobilefork.hermesagent.backend

import com.google.ai.edge.litertlm.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlin.io.path.pathString

class LiteRtLmOpenAiProxyTest {
    private class FakeStartupCandidate(
        private val completion: () -> LiteRtLmOpenAiProxy.StartupCompletionCanary,
        private val initializationFailure: Throwable? = null,
        private val initializationBlock: (() -> Unit)? = null,
        private val closeBlock: (() -> Unit)? = null,
        private val closeFailure: Throwable? = null,
    ) : LiteRtLmOpenAiProxy.StartupEngineCandidate {
        @Volatile var initialized = false
        @Volatile var cancelled = false
        @Volatile var closed = false
        @Volatile var closeStarted = false

        override fun initialize() {
            initialized = true
            initializationBlock?.invoke()
            initializationFailure?.let { throw it }
        }

        override fun completionCanary(timeoutMs: Long): LiteRtLmOpenAiProxy.StartupCompletionCanary {
            assertTrue("Expected a positive bounded timeout", timeoutMs > 0L)
            return completion()
        }

        override fun cancelCompletion() {
            cancelled = true
        }

        override fun close() {
            closeStarted = true
            closeBlock?.invoke()
            closeFailure?.let { throw it }
            closed = true
        }
    }

    @After
    fun clearNativeStartupGuardAfterTest() {
        LiteRtLmOpenAiProxy.resetNativeStartupUnwindForTests()
    }

    @Test
    fun startupCanaryContentRequiresRealNonblankModelText() {
        assertEquals("OK", LiteRtLmOpenAiProxy.responseText(Message.model("  OK  ")))
        assertTrue(LiteRtLmOpenAiProxy.responseText(Message.model(" \n\t ")).isBlank())
    }

    @Test
    fun nativeResourceOwnershipIsPublishedBeforeInitializationCanThrow() {
        val resource = Any()
        var owned: Any? = null

        val failure = runCatching {
            LiteRtLmOpenAiProxy.constructOwnedNativeResource(
                create = { resource },
                assignOwner = { owned = it },
                initialize = { error("native initialize failed after allocation") },
            )
        }.exceptionOrNull()

        assertTrue(failure.orEmptyMessage().contains("native initialize failed"))
        assertSame("Cleanup must retain the constructed native resource", resource, owned)
    }

    @Test
    fun verifiedEngineSelection_closesFailedGpuAndFallsBackToCpuCompletion() {
        val gpu = FakeStartupCandidate(completion = { error("GPU generation failed") })
        val cpu = FakeStartupCandidate(
            completion = { LiteRtLmOpenAiProxy.StartupCompletionCanary("cpu response", 37L) },
        )

        val selection = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("gpu/standard") { gpu },
                LiteRtLmOpenAiProxy.StartupEngineAttempt("cpu/standard") { cpu },
            ),
            timeoutMs = 1_000L,
        )

        assertTrue(selection.verified)
        assertEquals("cpu/standard", selection.selectedLabel)
        assertEquals(37L, selection.completionLatencyMs)
        assertSame(cpu, selection.candidate)
        assertTrue(gpu.initialized)
        assertTrue(gpu.closed)
        assertFalse(cpu.closed)
        assertTrue(selection.attempts.any { it.startsWith("gpu/standard: failed") })
        assertTrue(selection.attempts.any { it == "cpu/standard: completion canary passed (37 ms)" })
    }

    @Test
    fun verifiedEngineSelection_rejectsBlankCompletionAndClosesCandidate() {
        val blank = FakeStartupCandidate(
            completion = { LiteRtLmOpenAiProxy.StartupCompletionCanary(" \n\t", 12L) },
        )

        val selection = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            listOf(LiteRtLmOpenAiProxy.StartupEngineAttempt("gpu/standard") { blank }),
        )

        assertFalse(selection.verified)
        assertNull(selection.candidate)
        assertTrue(blank.closed)
        assertTrue(selection.attempts.any { it.contains("blank model content") })
    }

    @Test
    fun verifiedEngineSelection_canaryCleanupFailureNeverMarksUnsafeCandidateReady() {
        val unsafe = FakeStartupCandidate(
            completion = {
                throw LiteRtLmOpenAiProxy.NativeGenerationCleanupException(
                    "startup canary conversation cleanup failed",
                    IllegalStateException("native Conversation.close failed"),
                )
            },
        )
        val safe = FakeStartupCandidate(
            completion = { LiteRtLmOpenAiProxy.StartupCompletionCanary("ready", 8L) },
        )

        val selection = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("gpu/cleanup-failed") { unsafe },
                LiteRtLmOpenAiProxy.StartupEngineAttempt("cpu/standard") { safe },
            ),
        )

        assertTrue(unsafe.closed)
        assertTrue(selection.attempts.any { it.contains("startup canary conversation cleanup failed") })
        assertTrue(selection.verified)
        assertSame(safe, selection.candidate)
    }

    @Test
    fun verifiedEngineSelection_timeoutCancelsAndClosesBeforeTryingNextCandidate() {
        val timedOut = FakeStartupCandidate(completion = { throw TimeoutException("bounded timeout") })
        val cpu = FakeStartupCandidate(
            completion = { LiteRtLmOpenAiProxy.StartupCompletionCanary("ready", 9L) },
        )

        val selection = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("gpu/standard") { timedOut },
                LiteRtLmOpenAiProxy.StartupEngineAttempt("cpu/standard") { cpu },
            ),
        )

        assertTrue(selection.verified)
        assertTrue(timedOut.cancelled)
        assertTrue(timedOut.closed)
        assertSame(cpu, selection.candidate)
    }

    @Test
    fun verifiedEngineSelection_timesOutUninterruptibleInitializationWithoutStartingAnotherEngine() {
        val initializationStarted = CountDownLatch(1)
        val releaseBlockedInitialization = CountDownLatch(1)
        val blocked = FakeStartupCandidate(
            completion = { LiteRtLmOpenAiProxy.StartupCompletionCanary("never reached", 1L) },
            initializationBlock = {
                initializationStarted.countDown()
                var released = false
                while (!released) {
                    try {
                        releaseBlockedInitialization.await()
                        released = true
                    } catch (_: InterruptedException) {
                        // Simulate an uninterruptible JNI call which ignores Future.cancel(true).
                    }
                }
            },
        )
        val cpu = FakeStartupCandidate(
            completion = { LiteRtLmOpenAiProxy.StartupCompletionCanary("ready", 11L) },
        )

        val selectionResult = AtomicReference<LiteRtLmOpenAiProxy.StartupEngineSelection>()
        val caller = Thread {
            selectionResult.set(
                LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
                    candidateAttempts = listOf(
                        LiteRtLmOpenAiProxy.StartupEngineAttempt("gpu/mtp") { blocked },
                        LiteRtLmOpenAiProxy.StartupEngineAttempt("cpu/standard") { cpu },
                    ),
                    timeoutMs = 1_000L,
                    initializationTimeoutMs = 250L,
                    totalTimeoutMs = 2_000L,
                )
            )
        }
        caller.start()
        try {
            assertTrue(
                "The fake native initialization must start before its deadline",
                initializationStarted.await(1L, java.util.concurrent.TimeUnit.SECONDS),
            )
            caller.join(2_000L)
            assertFalse("The deadline caller must return while native initialization remains blocked", caller.isAlive)
            val selection = checkNotNull(selectionResult.get())

            assertFalse(selection.verified)
            assertFalse(blocked.cancelled)
            assertFalse(cpu.initialized)
            assertTrue(
                selection.attempts.toString(),
                selection.attempts.any { it.contains("initialization timed out") },
            )

            val blockedRetry = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
                candidateAttempts = listOf(
                    LiteRtLmOpenAiProxy.StartupEngineAttempt("cpu/retry") { cpu },
                ),
                timeoutMs = 1_000L,
                initializationTimeoutMs = 1_000L,
            )
            assertFalse(blockedRetry.verified)
            assertFalse(cpu.initialized)
            assertTrue(blockedRetry.attempts.single().contains("prior native LiteRT-LM startup is still unwinding"))
        } finally {
            releaseBlockedInitialization.countDown()
            caller.join(2_000L)
            val completedSelection = selectionResult.get()
            if (!caller.isAlive && completedSelection?.candidate === blocked && !blocked.closed) {
                blocked.close()
            }
            awaitClosed(blocked, "The abandoned candidate must close before the test resets native ownership")
        }

        val recovered = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            candidateAttempts = listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("cpu/retry") { cpu },
            ),
            timeoutMs = 1_000L,
            initializationTimeoutMs = 1_000L,
        )
        assertTrue(recovered.verified)
        assertSame(cpu, recovered.candidate)
    }

    @Test
    fun verifiedEngineSelection_timeoutDuringWorkerAdmissionNeverClosesBesideInitialization() {
        val workerAdmitted = CountDownLatch(1)
        val releaseAdmission = CountDownLatch(1)
        val candidate = FakeStartupCandidate(
            completion = { LiteRtLmOpenAiProxy.StartupCompletionCanary("not selected", 1L) },
        )

        val result = AtomicReference<LiteRtLmOpenAiProxy.StartupEngineSelection>()
        val caller = Thread {
            result.set(
                LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
                    candidateAttempts = listOf(
                        LiteRtLmOpenAiProxy.StartupEngineAttempt("gpu/admission-race") { candidate },
                    ),
                    initializationTimeoutMs = 200L,
                    onInitializationWorkerAdmittedForTests = {
                        workerAdmitted.countDown()
                        awaitIgnoringInterrupts(releaseAdmission)
                    },
                )
            )
        }
        caller.start()
        assertTrue(workerAdmitted.await(1L, java.util.concurrent.TimeUnit.SECONDS))
        caller.join(1_000L)
        assertFalse("The deadline caller must return while the admitted worker remains blocked", caller.isAlive)
        val selection = result.get()
        assertFalse(selection.verified)
        assertFalse("Cleanup must not race a worker admitted to native initialization", candidate.closed)
        assertFalse(candidate.initialized)

        val blockedRetry = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("cpu/retry") {
                    error("Retry must remain blocked while the admitted worker owns the candidate")
                },
            ),
        )
        assertFalse(blockedRetry.verified)

        releaseAdmission.countDown()
        awaitClosed(candidate, "The admitted worker must initialize, then own candidate cleanup")
        assertTrue(candidate.initialized)
    }

    @Test
    fun generationCoordinator_timeoutKeepsConversationOwnedAndBlocksRetryUntilWorkerExits() {
        val coordinator = LiteRtLmOpenAiProxy.NativeGenerationCoordinator()
        val generationStarted = CountDownLatch(1)
        val releaseGeneration = CountDownLatch(1)
        val conversationClosed = AtomicBoolean(false)
        val timeoutFailure = AtomicReference<Throwable?>()

        val caller = Thread {
            timeoutFailure.set(
                runCatching {
                    coordinator.runBounded(timeoutMs = 25L) {
                        generationStarted.countDown()
                        try {
                            awaitIgnoringInterrupts(releaseGeneration)
                            "late completion"
                        } finally {
                            conversationClosed.set(true)
                        }
                    }
                }.exceptionOrNull()
            )
        }
        caller.start()
        assertTrue(generationStarted.await(1L, java.util.concurrent.TimeUnit.SECONDS))
        caller.join(1_000L)

        assertFalse("The bounded HTTP caller must not remain stuck in native generation", caller.isAlive)
        assertTrue(timeoutFailure.get().orEmptyMessage().contains("generation timed out"))
        assertFalse("The caller must not close a Conversation beside live native JNI", conversationClosed.get())
        assertEquals("running_or_unwinding", coordinator.snapshot().state)
        assertFalse(coordinator.snapshot().completionAvailable)
        val busyHealth = LiteRtLmOpenAiProxy.generationHealthState(true, coordinator.snapshot())
        assertEquals("busy", busyHealth.status)
        assertFalse(busyHealth.completionAvailable)

        val blockedRetry = runCatching {
            coordinator.runBounded(timeoutMs = 100L) { "must not overlap" }
        }.exceptionOrNull()
        assertTrue(blockedRetry.orEmptyMessage().contains("prior LiteRT-LM completion is still running"))

        releaseGeneration.countDown()
        awaitAtomicTrue(conversationClosed, "The native worker must own Conversation cleanup")
        awaitGenerationState(coordinator, "idle")
        val recoveredHealth = LiteRtLmOpenAiProxy.generationHealthState(true, coordinator.snapshot())
        assertEquals("ok", recoveredHealth.status)
        assertTrue(recoveredHealth.completionAvailable)
        val recovered = coordinator.runBounded(timeoutMs = 1_000L) { "recovered" }
        assertEquals("recovered", recovered)
    }

    @Test
    fun generationCoordinator_lateNativeFailureAfterTimeoutRequiresRestart() {
        val coordinator = LiteRtLmOpenAiProxy.NativeGenerationCoordinator()
        val generationStarted = CountDownLatch(1)
        val releaseGeneration = CountDownLatch(1)
        val timeoutFailure = AtomicReference<Throwable?>()

        val caller = Thread {
            timeoutFailure.set(
                runCatching {
                    coordinator.runBounded(timeoutMs = 25L) {
                        generationStarted.countDown()
                        awaitIgnoringInterrupts(releaseGeneration)
                        error("late JNI send failure")
                    }
                }.exceptionOrNull()
            )
        }
        caller.start()
        assertTrue(generationStarted.await(1L, java.util.concurrent.TimeUnit.SECONDS))
        caller.join(1_000L)
        assertFalse(caller.isAlive)
        assertTrue(timeoutFailure.get().orEmptyMessage().contains("generation timed out"))

        releaseGeneration.countDown()
        val restartFailure = awaitGenerationRestartRequired(coordinator)
        assertTrue(restartFailure.orEmptyMessage().contains("late JNI send failure"))
        assertEquals("restart_required", coordinator.snapshot().state)
        assertFalse(coordinator.snapshot().completionAvailable)
        val restartHealth = LiteRtLmOpenAiProxy.generationHealthState(true, coordinator.snapshot())
        assertEquals("restart_required", restartHealth.status)
        assertFalse(restartHealth.completionAvailable)
    }

    @Test
    fun generationCoordinator_cleanupFailureRequiresRestartWithoutTryingAnotherCompletion() {
        val coordinator = LiteRtLmOpenAiProxy.NativeGenerationCoordinator()

        val firstFailure = runCatching {
            coordinator.runBounded(timeoutMs = 1_000L) {
                throw LiteRtLmOpenAiProxy.NativeGenerationCleanupException(
                    "conversation cleanup failed",
                    IllegalStateException("native Conversation.close failed"),
                )
            }
        }.exceptionOrNull()
        assertTrue(firstFailure.orEmptyMessage().contains("conversation cleanup failed"))

        val restartFailure = runCatching {
            coordinator.runBounded(timeoutMs = 1_000L) { "must not run" }
        }.exceptionOrNull()
        assertTrue(restartFailure.orEmptyMessage().contains("requires an app restart"))
        assertTrue(restartFailure.orEmptyMessage().contains("conversation cleanup failed"))
        assertEquals("restart_required", coordinator.snapshot().state)
    }

    @Test
    fun generationCoordinator_shutdownWaitsForWorkerOwnedConversationCleanup() {
        val coordinator = LiteRtLmOpenAiProxy.NativeGenerationCoordinator()
        val generationStarted = CountDownLatch(1)
        val releaseGeneration = CountDownLatch(1)
        val conversationClosed = AtomicBoolean(false)
        val engineClosed = AtomicBoolean(false)

        val generation = Thread {
            coordinator.runBounded(timeoutMs = 5_000L) {
                generationStarted.countDown()
                try {
                    awaitIgnoringInterrupts(releaseGeneration)
                } finally {
                    conversationClosed.set(true)
                }
            }
        }
        generation.start()
        assertTrue(generationStarted.await(1L, java.util.concurrent.TimeUnit.SECONDS))

        val shutdown = Thread {
            coordinator.beginShutdownAndAwaitIdle()
            engineClosed.set(true)
        }
        shutdown.start()
        awaitGenerationState(coordinator, "shutting_down")
        assertFalse("Engine.close must not overlap live native generation", engineClosed.get())
        assertFalse(conversationClosed.get())

        releaseGeneration.countDown()
        generation.join(1_000L)
        shutdown.join(1_000L)
        assertFalse(generation.isAlive)
        assertFalse(shutdown.isAlive)
        assertTrue(conversationClosed.get())
        assertTrue(engineClosed.get())
    }

    @Test
    fun verifiedEngineSelection_timesOutUninterruptibleCanaryAndBlocksRetryUntilWorkerExits() {
        val releaseBlockedCanary = CountDownLatch(1)
        val blocked = FakeStartupCandidate(
            completion = {
                var released = false
                while (!released) {
                    try {
                        releaseBlockedCanary.await()
                        released = true
                    } catch (_: InterruptedException) {
                        // Simulate a native completion call which ignores cancellation.
                    }
                }
                LiteRtLmOpenAiProxy.StartupCompletionCanary("late response", 500L)
            },
        )
        val retryCreateCount = AtomicInteger(0)
        val retry = FakeStartupCandidate(
            completion = { LiteRtLmOpenAiProxy.StartupCompletionCanary("ready", 7L) },
        )

        val selection = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            candidateAttempts = listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("gpu/standard") { blocked },
                LiteRtLmOpenAiProxy.StartupEngineAttempt("cpu/standard") {
                    retryCreateCount.incrementAndGet()
                    retry
                },
            ),
            timeoutMs = 25L,
            initializationTimeoutMs = 1_000L,
        )

        assertFalse(selection.verified)
        assertEquals(0, retryCreateCount.get())
        assertFalse(blocked.closed)

        val blockedRetry = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            candidateAttempts = listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("cpu/retry") {
                    retryCreateCount.incrementAndGet()
                    retry
                },
            ),
        )
        assertFalse(blockedRetry.verified)
        assertEquals(0, retryCreateCount.get())

        releaseBlockedCanary.countDown()
        awaitClosed(blocked, "The abandoned canary candidate must close on its own worker")

        val recovered = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            candidateAttempts = listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("cpu/retry") {
                    retryCreateCount.incrementAndGet()
                    retry
                },
            ),
        )
        assertTrue(recovered.verified)
        assertEquals(1, retryCreateCount.get())
    }

    @Test
    fun verifiedEngineSelection_interruptedWaitRetainsNativeOwnershipUntilWorkerExits() {
        val initializationStarted = CountDownLatch(1)
        val releaseBlockedInitialization = CountDownLatch(1)
        val blocked = FakeStartupCandidate(
            completion = { LiteRtLmOpenAiProxy.StartupCompletionCanary("never reached", 1L) },
            initializationBlock = {
                initializationStarted.countDown()
                var released = false
                while (!released) {
                    try {
                        releaseBlockedInitialization.await()
                        released = true
                    } catch (_: InterruptedException) {
                        // Simulate JNI which ignores interruption.
                    }
                }
            },
        )
        val result = AtomicReference<LiteRtLmOpenAiProxy.StartupEngineSelection>()
        val callerRemainedInterrupted = AtomicBoolean(false)
        val caller = Thread {
            result.set(
                LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
                    candidateAttempts = listOf(
                        LiteRtLmOpenAiProxy.StartupEngineAttempt("gpu/standard") { blocked },
                    ),
                    timeoutMs = 1_000L,
                    initializationTimeoutMs = 10_000L,
                )
            )
            callerRemainedInterrupted.set(Thread.currentThread().isInterrupted)
        }
        caller.start()
        assertTrue(initializationStarted.await(1, java.util.concurrent.TimeUnit.SECONDS))
        caller.interrupt()
        caller.join(1_000L)

        assertFalse("The interrupted selection caller must unwind promptly", caller.isAlive)
        assertFalse(result.get().verified)
        assertTrue(callerRemainedInterrupted.get())
        assertFalse(blocked.closed)

        val blockedRetry = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("retry") {
                    error("Retry must not construct an engine while JNI is still running")
                },
            ),
        )
        assertFalse(blockedRetry.verified)

        releaseBlockedInitialization.countDown()
        awaitClosed(blocked, "Interrupted native work must close only after its worker exits")
    }

    @Test
    fun verifiedEngineSelection_totalBudgetStopsBeforeConstructingLaterCandidate() {
        val first = FakeStartupCandidate(
            completion = { LiteRtLmOpenAiProxy.StartupCompletionCanary("never reached", 1L) },
        )
        val laterCreateCount = AtomicInteger(0)

        val selection = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            candidateAttempts = listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("slow/create") {
                    Thread.sleep(40L)
                    first
                },
                LiteRtLmOpenAiProxy.StartupEngineAttempt("must/not/create") {
                    laterCreateCount.incrementAndGet()
                    FakeStartupCandidate(completion = {
                        LiteRtLmOpenAiProxy.StartupCompletionCanary("unexpected", 1L)
                    })
                },
            ),
            timeoutMs = 1_000L,
            initializationTimeoutMs = 1_000L,
            totalTimeoutMs = 10L,
        )

        assertFalse(selection.verified)
        assertTrue(first.closed)
        assertEquals(0, laterCreateCount.get())
        assertTrue(selection.attempts.any { it.contains("total startup budget exhausted") })
    }

    @Test
    fun verifiedEngineSelection_sharedDeadlineDoesNotResetForTextOnlyFallbackMatrix() {
        val startupStartedAt = System.nanoTime()
        val multimodal = FakeStartupCandidate(
            completion = { LiteRtLmOpenAiProxy.StartupCompletionCanary("never reached", 1L) },
        )
        val textOnlyCreateCount = AtomicInteger(0)

        val multimodalSelection = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            candidateAttempts = listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("gpu/multimodal") {
                    Thread.sleep(40L)
                    multimodal
                },
            ),
            timeoutMs = 1_000L,
            initializationTimeoutMs = 1_000L,
            totalTimeoutMs = 10L,
            startupStartedAtNanos = startupStartedAt,
        )
        assertFalse(multimodalSelection.verified)
        assertTrue(multimodal.closed)

        val textOnlySelection = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            candidateAttempts = listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("cpu/text-only") {
                    textOnlyCreateCount.incrementAndGet()
                    FakeStartupCandidate(completion = {
                        LiteRtLmOpenAiProxy.StartupCompletionCanary("unexpected", 1L)
                    })
                },
            ),
            timeoutMs = 1_000L,
            initializationTimeoutMs = 1_000L,
            totalTimeoutMs = 10L,
            startupStartedAtNanos = startupStartedAt,
        )

        assertFalse(textOnlySelection.verified)
        assertEquals(0, textOnlyCreateCount.get())
        assertTrue(textOnlySelection.attempts.single().contains("total startup budget exhausted"))
    }

    @Test
    fun verifiedEngineSelection_allFailuresReturnNotReadyWithoutCandidateLeak() {
        val first = FakeStartupCandidate(completion = { error("first failure") })
        val second = FakeStartupCandidate(
            completion = { LiteRtLmOpenAiProxy.StartupCompletionCanary("never reached", 1L) },
            initializationFailure = IllegalStateException("second initialization failed"),
        )

        val selection = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("gpu/standard") { first },
                LiteRtLmOpenAiProxy.StartupEngineAttempt("cpu/standard") { second },
            ),
        )

        assertFalse(selection.verified)
        assertNull(selection.candidate)
        assertEquals("", selection.selectedLabel)
        assertEquals(0L, selection.completionLatencyMs)
        assertTrue(first.closed)
        assertTrue(second.closed)
        assertEquals(4, selection.attempts.size)
        assertTrue(selection.failure?.message.orEmpty().contains("second initialization failed"))
    }

    @Test
    fun verifiedEngineSelection_blockingCleanupReturnsBoundedAndBlocksRetryUntilCloseFinishes() {
        val releaseClose = CountDownLatch(1)
        val failed = FakeStartupCandidate(
            completion = { error("completion failed before cleanup") },
            closeBlock = {
                var released = false
                while (!released) {
                    try {
                        releaseClose.await()
                        released = true
                    } catch (_: InterruptedException) {
                        // Simulate a native close which ignores Future.cancel(true).
                    }
                }
            },
        )
        val laterCreateCount = AtomicInteger(0)
        val startedAt = System.nanoTime()

        val selection = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            candidateAttempts = listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("gpu/cleanup-blocked") { failed },
                LiteRtLmOpenAiProxy.StartupEngineAttempt("cpu/must-not-start") {
                    laterCreateCount.incrementAndGet()
                    FakeStartupCandidate(completion = {
                        LiteRtLmOpenAiProxy.StartupCompletionCanary("unexpected", 1L)
                    })
                },
            ),
            timeoutMs = 1_000L,
            initializationTimeoutMs = 1_000L,
            cleanupTimeoutMs = 25L,
        )
        val elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertFalse(selection.verified)
        assertTrue("Caller must not wait for an unbounded close ($elapsedMs ms)", elapsedMs < 1_000L)
        assertTrue(failed.closeStarted)
        assertFalse(failed.closed)
        assertEquals(0, laterCreateCount.get())

        val blockedRetry = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("cpu/retry") {
                    laterCreateCount.incrementAndGet()
                    error("Retry must not construct while cleanup owns the prior engine")
                },
            ),
        )
        assertFalse(blockedRetry.verified)
        assertEquals(0, laterCreateCount.get())

        releaseClose.countDown()
        awaitClosed(failed, "Blocked native close must eventually clear the retry guard")
        val recovered = FakeStartupCandidate(
            completion = { LiteRtLmOpenAiProxy.StartupCompletionCanary("ready", 5L) },
        )
        val recoveredSelection = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            listOf(LiteRtLmOpenAiProxy.StartupEngineAttempt("cpu/recovered") { recovered }),
        )
        assertTrue(recoveredSelection.verified)
    }

    @Test
    fun verifiedEngineSelection_cleanupFailurePoisonsRetryInsteadOfStartingSecondEngine() {
        val failed = FakeStartupCandidate(
            completion = { error("completion failed before cleanup") },
            closeFailure = IllegalStateException("native Engine.close failed"),
        )
        val selection = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            candidateAttempts = listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("gpu/cleanup-fails") { failed },
            ),
            cleanupTimeoutMs = 250L,
        )

        assertFalse(selection.verified)
        assertTrue(failed.closeStarted)
        assertFalse(failed.closed)
        assertTrue(selection.attempts.single { it.contains(": failed") }.contains("restart Hermes"))

        val retryCreateCount = AtomicInteger(0)
        val blockedRetry = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("cpu/retry") {
                    retryCreateCount.incrementAndGet()
                    error("Retry must remain blocked after cleanup failure")
                },
            ),
        )
        assertFalse(blockedRetry.verified)
        assertEquals(0, retryCreateCount.get())
        assertTrue(blockedRetry.attempts.single().contains("cleanup failed"))
    }

    @Test
    fun startupProbes_skipOpenClForForcedCpuAndCapabilitiesWhenMtpDisabled() {
        val openClInvocations = AtomicInteger(0)
        val capabilitiesInvocations = AtomicInteger(0)

        val probes = LiteRtLmOpenAiProxy.resolveStartupProbes(
            preferredAccelerator = "cpu",
            speculativeDecodingMode = LiteRtLmOpenAiProxy.SpeculativeDecodingMode.DISABLED,
            openClProbe = {
                openClInvocations.incrementAndGet()
                true
            },
            capabilitiesProbe = {
                capabilitiesInvocations.incrementAndGet()
                true
            },
        )

        assertFalse(probes.openClAvailable)
        assertFalse(probes.speculativeDecodingSupported)
        assertEquals(0, openClInvocations.get())
        assertEquals(0, capabilitiesInvocations.get())
        assertTrue(probes.attempts.any { it.contains("skipped for cpu") })
        assertTrue(probes.attempts.any { it.contains("speculative decoding is disabled") })
    }

    @Test
    fun startupProbes_uninterruptibleOpenClProbeBlocksRetryUntilWorkerExits() {
        val probeStarted = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val laterInvocations = AtomicInteger(0)
        val startedAt = System.nanoTime()

        val failure = runCatching {
            LiteRtLmOpenAiProxy.resolveStartupProbes(
                preferredAccelerator = "auto",
                speculativeDecodingMode = LiteRtLmOpenAiProxy.SpeculativeDecodingMode.DISABLED,
                probeTimeoutMs = 25L,
                openClProbe = {
                    probeStarted.countDown()
                    awaitIgnoringInterrupts(releaseProbe)
                    true
                },
                capabilitiesProbe = { error("Capabilities must be skipped") },
            )
        }.exceptionOrNull()
        val elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(probeStarted.await(1L, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("Probe wait must be bounded ($elapsedMs ms)", elapsedMs < 1_000L)
        assertTrue(failure?.message.orEmpty().contains("OpenCL probe timed out"))

        val blockedRetry = runCatching {
            LiteRtLmOpenAiProxy.resolveStartupProbes(
                preferredAccelerator = "auto",
                speculativeDecodingMode = LiteRtLmOpenAiProxy.SpeculativeDecodingMode.DISABLED,
                openClProbe = {
                    laterInvocations.incrementAndGet()
                    false
                },
                capabilitiesProbe = { false },
            )
        }.exceptionOrNull()
        assertEquals(0, laterInvocations.get())
        assertTrue(blockedRetry?.message.orEmpty().contains("prior native startup is still unwinding"))

        releaseProbe.countDown()
        awaitProbeGuardClear()
        val recovered = LiteRtLmOpenAiProxy.resolveStartupProbes(
            preferredAccelerator = "auto",
            speculativeDecodingMode = LiteRtLmOpenAiProxy.SpeculativeDecodingMode.DISABLED,
            openClProbe = {
                laterInvocations.incrementAndGet()
                false
            },
            capabilitiesProbe = { false },
        )
        assertFalse(recovered.openClAvailable)
        assertEquals(1, laterInvocations.get())
    }

    @Test
    fun startupProbes_uninterruptibleCapabilitiesProbeClosesBeforeRetry() {
        val releaseCapabilities = CountDownLatch(1)
        val capabilitiesClosed = AtomicBoolean(false)
        val failure = runCatching {
            LiteRtLmOpenAiProxy.resolveStartupProbes(
                preferredAccelerator = "cpu",
                speculativeDecodingMode = LiteRtLmOpenAiProxy.SpeculativeDecodingMode.AUTO,
                probeTimeoutMs = 25L,
                openClProbe = { error("OpenCL must be skipped for CPU") },
                capabilitiesProbe = {
                    try {
                        awaitIgnoringInterrupts(releaseCapabilities)
                        true
                    } finally {
                        capabilitiesClosed.set(true)
                    }
                },
            )
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("capabilities probe timed out"))
        assertFalse(capabilitiesClosed.get())
        releaseCapabilities.countDown()
        awaitProbeGuardClear()
        assertTrue(capabilitiesClosed.get())
    }

    @Test
    fun startupProbes_lateCapabilitiesCloseFailureRequiresRestart() {
        val releaseCapabilities = CountDownLatch(1)
        val throwingNow = CountDownLatch(1)
        val failure = runCatching {
            LiteRtLmOpenAiProxy.resolveStartupProbes(
                preferredAccelerator = "cpu",
                speculativeDecodingMode = LiteRtLmOpenAiProxy.SpeculativeDecodingMode.AUTO,
                probeTimeoutMs = 25L,
                openClProbe = { error("OpenCL must be skipped for CPU") },
                capabilitiesProbe = {
                    awaitIgnoringInterrupts(releaseCapabilities)
                    throwingNow.countDown()
                    error("late Capabilities.close failed")
                },
            )
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("capabilities probe timed out"))

        releaseCapabilities.countDown()
        assertTrue(throwingNow.await(1L, java.util.concurrent.TimeUnit.SECONDS))
        val blocked = awaitRestartRequiredGuard()
        assertTrue(blocked.attempts.single().contains("late Capabilities.close failed"))
        assertTrue(blocked.attempts.single().contains("restart Hermes"))
    }

    @Test
    fun startupProbes_ordinaryProbeFailureDegradesToUnavailableWithoutPoisoningStartup() {
        val probes = LiteRtLmOpenAiProxy.resolveStartupProbes(
            preferredAccelerator = "auto",
            speculativeDecodingMode = LiteRtLmOpenAiProxy.SpeculativeDecodingMode.AUTO,
            openClProbe = { error("OpenCL loader rejected library") },
            capabilitiesProbe = { error("Capabilities unavailable") },
        )

        assertFalse(probes.openClAvailable)
        assertFalse(probes.speculativeDecodingSupported)
        assertTrue(probes.attempts.any { it.contains("OpenCL loader rejected library") })
        assertTrue(probes.attempts.any { it.contains("Capabilities unavailable") })
    }

    @Test
    fun startupProbes_capabilitiesCloseFailurePoisonsStartupBeforeEngineConstruction() {
        val closeAttempted = AtomicBoolean(false)
        val failure = runCatching {
            LiteRtLmOpenAiProxy.resolveStartupProbes(
                preferredAccelerator = "cpu",
                speculativeDecodingMode = LiteRtLmOpenAiProxy.SpeculativeDecodingMode.AUTO,
                openClProbe = { error("OpenCL must be skipped for CPU") },
                capabilitiesProbe = {
                    LiteRtLmOpenAiProxy.useOwnedNativeProbeResource(
                        create = { Any() },
                        query = { true },
                        close = {
                            closeAttempted.set(true)
                            error("Capabilities.close failed")
                        },
                    )
                },
            )
        }.exceptionOrNull()

        assertTrue(closeAttempted.get())
        assertTrue(failure.orEmptyMessage().contains("cleanup failed"))
        val candidateCreations = AtomicInteger(0)
        val blocked = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("must-not-start") {
                    candidateCreations.incrementAndGet()
                    error("Engine construction must remain blocked after native probe cleanup failure")
                },
            ),
        )
        assertFalse(blocked.verified)
        assertEquals(0, candidateCreations.get())
        assertTrue(blocked.attempts.single().contains("Capabilities.close failed"))
    }

    @Test
    fun startupProbes_sharedBudgetExpiresBeforeInvokingAnyLaterProbe() {
        val invocations = AtomicInteger(0)
        val startedAt = System.nanoTime()
        Thread.sleep(30L)

        val failure = runCatching {
            LiteRtLmOpenAiProxy.resolveStartupProbes(
                preferredAccelerator = "auto",
                speculativeDecodingMode = LiteRtLmOpenAiProxy.SpeculativeDecodingMode.AUTO,
                startupStartedAtNanos = startedAt,
                totalTimeoutMs = 10L,
                openClProbe = {
                    invocations.incrementAndGet()
                    true
                },
                capabilitiesProbe = {
                    invocations.incrementAndGet()
                    true
                },
            )
        }.exceptionOrNull()

        assertEquals(0, invocations.get())
        assertTrue(failure?.message.orEmpty().contains("total startup budget exhausted"))
    }

    @Test
    fun nativeShutdown_timeoutReturnsBoundedAndBlocksReplacementUntilOldEngineExits() {
        val shutdownStarted = CountDownLatch(1)
        val releaseShutdown = CountDownLatch(1)
        val startedAt = System.nanoTime()

        val failure = LiteRtLmOpenAiProxy.runBoundedNativeShutdownForTests(timeoutMs = 25L) {
            shutdownStarted.countDown()
            awaitIgnoringInterrupts(releaseShutdown)
        }
        val elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue(shutdownStarted.await(1L, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("Native shutdown wait must be bounded ($elapsedMs ms)", elapsedMs < 1_000L)
        assertTrue(failure?.message.orEmpty().contains("shutdown timed out"))
        val replacementCreated = AtomicInteger(0)
        val blocked = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("replacement") {
                    replacementCreated.incrementAndGet()
                    error("Replacement engine must not overlap old native shutdown")
                },
            ),
        )
        assertFalse(blocked.verified)
        assertEquals(0, replacementCreated.get())
        val repeatedStop = LiteRtLmOpenAiProxy.stop()
        assertTrue(repeatedStop?.message.orEmpty().contains("still unwinding"))

        releaseShutdown.countDown()
        awaitProbeGuardClear()
        assertNull(LiteRtLmOpenAiProxy.stop())
    }

    @Test
    fun nativeShutdown_timeoutBeforeWorkerAdmissionTransfersShutdownOwnershipExactlyOnce() {
        val workerReachedAdmissionGate = CountDownLatch(1)
        val releaseAdmissionGate = CountDownLatch(1)
        val shutdownStarted = CountDownLatch(1)
        val releaseShutdown = CountDownLatch(1)
        val shutdownInvocations = AtomicInteger(0)
        val result = AtomicReference<Throwable?>()

        val caller = Thread {
            result.set(
                LiteRtLmOpenAiProxy.runBoundedNativeShutdownForTests(
                    timeoutMs = 100L,
                    onWorkerBeforeAdmission = {
                        workerReachedAdmissionGate.countDown()
                        awaitIgnoringInterrupts(releaseAdmissionGate)
                    },
                ) {
                    shutdownInvocations.incrementAndGet()
                    shutdownStarted.countDown()
                    awaitIgnoringInterrupts(releaseShutdown)
                }
            )
        }
        caller.start()
        assertTrue(workerReachedAdmissionGate.await(1L, java.util.concurrent.TimeUnit.SECONDS))
        caller.join(1_000L)
        assertFalse(caller.isAlive)
        assertTrue(result.get()?.message.orEmpty().contains("shutdown timed out"))
        assertTrue(shutdownStarted.await(1L, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, shutdownInvocations.get())
        assertTrue(LiteRtLmOpenAiProxy.stop()?.message.orEmpty().contains("still unwinding"))

        releaseAdmissionGate.countDown()
        Thread.sleep(25L)
        assertEquals("The cancelled worker must not invoke shutdown a second time", 1, shutdownInvocations.get())
        releaseShutdown.countDown()
        awaitProbeGuardClear()
        assertNull(LiteRtLmOpenAiProxy.stop())
    }

    @Test
    fun nativeShutdown_beforeAdmissionCleanupFailureRemainsRestartRequired() {
        val workerReachedAdmissionGate = CountDownLatch(1)
        val releaseAdmissionGate = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val result = AtomicReference<Throwable?>()

        val caller = Thread {
            result.set(
                LiteRtLmOpenAiProxy.runBoundedNativeShutdownForTests(
                    timeoutMs = 100L,
                    onWorkerBeforeAdmission = {
                        workerReachedAdmissionGate.countDown()
                        awaitIgnoringInterrupts(releaseAdmissionGate)
                    },
                ) {
                    cleanupStarted.countDown()
                    awaitIgnoringInterrupts(releaseCleanup)
                    error("pre-admission Engine.close failed")
                }
            )
        }
        caller.start()
        assertTrue(workerReachedAdmissionGate.await(1L, java.util.concurrent.TimeUnit.SECONDS))
        caller.join(1_000L)
        assertFalse(caller.isAlive)
        assertTrue(result.get()?.message.orEmpty().contains("shutdown timed out"))
        assertTrue(cleanupStarted.await(1L, java.util.concurrent.TimeUnit.SECONDS))

        releaseAdmissionGate.countDown()
        releaseCleanup.countDown()
        val blocked = awaitRestartRequiredGuard()
        assertTrue(blocked.attempts.single().contains("pre-admission Engine.close failed"))
        assertTrue(LiteRtLmOpenAiProxy.stop()?.message.orEmpty().contains("still unwinding"))
    }

    @Test
    fun nativeShutdown_failureRequiresRestartBeforeReplacementEngine() {
        val failure = LiteRtLmOpenAiProxy.runBoundedNativeShutdownForTests(timeoutMs = 250L) {
            error("Engine.close failed")
        }
        assertTrue(failure?.message.orEmpty().contains("Engine.close failed"))

        val replacementCreated = AtomicInteger(0)
        val blocked = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(
            listOf(
                LiteRtLmOpenAiProxy.StartupEngineAttempt("replacement") {
                    replacementCreated.incrementAndGet()
                    error("Replacement engine must remain blocked after shutdown failure")
                },
            ),
        )
        assertFalse(blocked.verified)
        assertEquals(0, replacementCreated.get())
        assertTrue(blocked.attempts.single().contains("restart Hermes"))
        assertTrue(LiteRtLmOpenAiProxy.stop()?.message.orEmpty().contains("still unwinding"))
    }

    @Test
    fun nativeShutdown_lateFailureAfterTimeoutKeepsRestartRequiredGuard() {
        val releaseShutdown = CountDownLatch(1)
        val throwingNow = CountDownLatch(1)
        val timeout = LiteRtLmOpenAiProxy.runBoundedNativeShutdownForTests(timeoutMs = 25L) {
            awaitIgnoringInterrupts(releaseShutdown)
            throwingNow.countDown()
            error("late Engine.close failed")
        }
        assertTrue(timeout?.message.orEmpty().contains("shutdown timed out"))

        releaseShutdown.countDown()
        assertTrue(throwingNow.await(1L, java.util.concurrent.TimeUnit.SECONDS))
        val blocked = awaitRestartRequiredGuard()
        assertFalse(blocked.verified)
        assertTrue(blocked.attempts.single().contains("late Engine.close failed"))
        assertTrue(blocked.attempts.single().contains("restart Hermes"))
        assertTrue(LiteRtLmOpenAiProxy.stop()?.message.orEmpty().contains("still unwinding"))
    }

    @Test
    fun validateModelArtifact_acceptsLiteRtLmHeader() {
        val file = tempModelFile("gemma-4-E2B-it.litertlm", "LITERTLM".toByteArray())

        assertNull(validateModelArtifact(file))
    }

    @Test
    fun validateModelArtifact_rejectsWebTaskFlatBufferBeforeEngineStart() {
        val file = tempModelFile(
            "gemma-4-E2B-it-web.task",
            byteArrayOf(0, 0, 0, 0, 'T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte()),
        )

        val error = validateModelArtifact(file).orEmpty()

        assertTrue(error, error.contains("web/browser .task FlatBuffer"))
        assertTrue(error, error.contains("download the .litertlm artifact instead"))
    }

    @Test
    fun validateModelArtifact_rejectsBrokenLiteRtLmFileWithZipHeader() {
        val file = tempModelFile("gemma-4-E4B-it.litertlm", byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0, 0, 0))

        val error = validateModelArtifact(file)

        assertEquals(
            "gemma-4-E4B-it.litertlm is not a valid LiteRT-LM bundle. Download the .litertlm artifact from the LiteRT-LM repo.",
            error,
        )
    }

    @Test
    fun memorySafeModalityDecision_keepsSmallModelMultimodalEnabled() {
        val decision = LiteRtLmOpenAiProxy.memorySafeModalityDecision(
            totalRamBytes = 4_000_000_000L,
            modelBytes = 1_000_000_000L,
            requestedImage = true,
            requestedAudio = true,
        )

        assertTrue(decision.supportImage)
        assertTrue(decision.supportAudio)
        assertTrue(decision.policy, decision.policy.contains("requested image and audio"))
    }

    @Test
    fun memorySafeModalityDecision_startsLargeGemma4TextOnlyOnFourGbDevice() {
        val decision = LiteRtLmOpenAiProxy.memorySafeModalityDecision(
            totalRamBytes = 4_000_000_000L,
            modelBytes = 2_583_085_056L,
            requestedImage = true,
            requestedAudio = true,
        )

        assertFalse(decision.supportImage)
        assertFalse(decision.supportAudio)
        assertTrue(decision.policy, decision.policy.startsWith("text-only memory guard"))
        assertTrue(decision.policy, decision.policy.contains("8.0GB RAM recommended"))
    }

    @Test
    fun memorySafeModalityDecision_keepsGemma4E2bMultimodalOnEightGbDevice() {
        val decision = LiteRtLmOpenAiProxy.memorySafeModalityDecision(
            totalRamBytes = 8_000_000_000L,
            modelBytes = 2_583_085_056L,
            requestedImage = true,
            requestedAudio = true,
        )

        assertTrue(decision.supportImage)
        assertTrue(decision.supportAudio)
    }

    @Test
    fun memorySafeModalityDecision_requiresMoreRamForE4bMultimodal() {
        val decision = LiteRtLmOpenAiProxy.memorySafeModalityDecision(
            totalRamBytes = 10_000_000_000L,
            modelBytes = 3_654_467_584L,
            requestedImage = true,
            requestedAudio = true,
        )

        assertFalse(decision.supportImage)
        assertFalse(decision.supportAudio)
        assertTrue(decision.policy, decision.policy.contains("12.0GB RAM recommended"))
    }

    @Test
    fun gpuBackendPolicy_disablesGpuOnX86EmulatorBuilds() {
        val policy = LiteRtLmOpenAiProxy.decideGpuBackendPolicy(
            isTranslatedArm64OnX86 = false,
            supportedAbis = listOf("x86_64"),
            openClAvailable = true,
            hardwareIdentity = "google sdk_gphone64_x86_64",
        )

        assertFalse(policy.enabled)
        assertTrue(policy.openClAvailable)
        assertEquals("disabled: x86 emulator/device build", policy.description)
    }

    @Test
    fun gpuBackendPolicy_attemptsGpuOnQualcommAdrenoArmDevices() {
        val policy = LiteRtLmOpenAiProxy.decideGpuBackendPolicy(
            isTranslatedArm64OnX86 = false,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            openClAvailable = false,
            hardwareIdentity = "qualcomm snapdragon adreno",
        )

        assertTrue(policy.enabled)
        assertFalse(policy.openClAvailable)
        assertEquals("qualcomm_snapdragon", policy.socFamily)
        assertEquals("adreno", policy.gpuFamily)
        assertEquals(listOf("gpu", "cpu"), policy.backendOrder)
        assertTrue(policy.deviceIdentity.contains("adreno"))
        assertTrue(policy.description, policy.description.contains("ARM Qualcomm Snapdragon/Adreno"))
        assertTrue(policy.description, policy.description.contains("CPU fallback"))
    }

    @Test
    fun gpuBackendPolicy_doesNotSilentlyMapUnsupportedNpuPreferenceToGpu() {
        val policy = LiteRtLmOpenAiProxy.decideGpuBackendPolicy(
            isTranslatedArm64OnX86 = false,
            supportedAbis = listOf("arm64-v8a"),
            openClAvailable = true,
            hardwareIdentity = "qualcomm snapdragon adreno",
            preferredAccelerator = "npu",
        )

        assertFalse(policy.enabled)
        assertEquals(listOf("cpu"), policy.backendOrder)
        assertTrue(policy.description, policy.description.contains("does not implement a separate NPU delegate"))
    }

    @Test
    fun gpuBackendPolicy_attemptsGpuOnMediatekMaliArmDevices() {
        val policy = LiteRtLmOpenAiProxy.decideGpuBackendPolicy(
            isTranslatedArm64OnX86 = false,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            openClAvailable = false,
            hardwareIdentity = "MediaTek Dimensity 1200 mt6893 Mali-G77",
        )

        assertTrue(policy.enabled)
        assertFalse(policy.openClAvailable)
        assertEquals("mediatek", policy.socFamily)
        assertEquals("mali", policy.gpuFamily)
        assertEquals(listOf("gpu", "cpu"), policy.backendOrder)
        assertTrue(policy.description, policy.description.contains("ARM MediaTek/Mali"))
        assertTrue(policy.description, policy.description.contains("CPU fallback"))
        assertTrue(policy.nativeAbiStrategy, policy.nativeAbiStrategy.contains("PowerVR/IMG"))
    }

    @Test
    fun gpuBackendPolicy_attemptsGpuOnMediatekPowerVrArmDevices() {
        val policy = LiteRtLmOpenAiProxy.decideGpuBackendPolicy(
            isTranslatedArm64OnX86 = false,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
            openClAvailable = true,
            hardwareIdentity = "MediaTek Helio P35 mt6765 PowerVR Rogue GE8320",
        )

        assertTrue(policy.enabled)
        assertTrue(policy.openClAvailable)
        assertEquals("mediatek", policy.socFamily)
        assertEquals("powervr_img", policy.gpuFamily)
        assertEquals(listOf("gpu", "cpu"), policy.backendOrder)
        assertTrue(policy.description, policy.description.contains("OpenCL library was loadable"))
        assertTrue(policy.description, policy.description.contains("ARM MediaTek/PowerVR/IMG"))
    }

    @Test
    fun speculativeDecodingDecision_autoEnablesCapabilityBackedGemma4OnArm64() {
        val decision = LiteRtLmOpenAiProxy.decideSpeculativeDecoding(
            capabilitiesSupported = true,
            modelName = "gemma-4-E2B-it.litertlm",
            modelBytes = 2_583_085_056L,
            totalRamBytes = 8_000_000_000L,
            isX86Device = false,
            mode = LiteRtLmOpenAiProxy.SpeculativeDecodingMode.AUTO,
        )

        assertTrue(decision.supported)
        assertTrue(decision.enabled)
        assertTrue(decision.policy, decision.policy.contains("capabilities advertise"))
    }

    @Test
    fun speculativeDecodingDecision_autoRejectsE4bFilenameWhenCapabilitiesProbeFails() {
        val decision = LiteRtLmOpenAiProxy.decideSpeculativeDecoding(
            capabilitiesSupported = false,
            modelName = "gemma-4-E4B-it.litertlm",
            modelBytes = 3_654_467_584L,
            totalRamBytes = 16_000_000_000L,
            isX86Device = false,
            mode = LiteRtLmOpenAiProxy.SpeculativeDecodingMode.AUTO,
        )

        assertFalse(decision.supported)
        assertFalse(decision.enabled)
        assertTrue(decision.policy, decision.policy.contains("does not advertise"))
    }

    @Test
    fun speculativeDecodingDecision_keepsMtpOffOnX86Emulator() {
        val decision = LiteRtLmOpenAiProxy.decideSpeculativeDecoding(
            capabilitiesSupported = true,
            modelName = "gemma-4-E2B-it.litertlm",
            modelBytes = 2_583_085_056L,
            totalRamBytes = 16_000_000_000L,
            isX86Device = true,
            mode = LiteRtLmOpenAiProxy.SpeculativeDecodingMode.AUTO,
        )

        assertTrue(decision.supported)
        assertFalse(decision.enabled)
        assertEquals("disabled: x86 emulator/device build", decision.policy)
    }

    @Test
    fun speculativeDecodingDecision_runtimeDisabledOverridesSupportedModel() {
        val decision = LiteRtLmOpenAiProxy.decideSpeculativeDecoding(
            capabilitiesSupported = true,
            modelName = "gemma-4-E2B-it.litertlm",
            modelBytes = 2_583_085_056L,
            totalRamBytes = 16_000_000_000L,
            isX86Device = false,
            mode = LiteRtLmOpenAiProxy.SpeculativeDecodingMode.DISABLED,
        )

        assertTrue(decision.supported)
        assertFalse(decision.enabled)
        assertEquals("disabled: runtime setting disabled Gemma 4 MTP", decision.policy)
    }

    @Test
    fun speculativeDecodingDecision_rejectsUnsupportedNonGemmaModel() {
        val decision = LiteRtLmOpenAiProxy.decideSpeculativeDecoding(
            capabilitiesSupported = false,
            modelName = "qwen3-0.6b-it.litertlm",
            modelBytes = 800_000_000L,
            totalRamBytes = 16_000_000_000L,
            isX86Device = false,
            mode = LiteRtLmOpenAiProxy.SpeculativeDecodingMode.ENABLED,
        )

        assertFalse(decision.supported)
        assertFalse(decision.enabled)
        assertTrue(decision.policy, decision.policy.contains("does not advertise support"))
    }

    @Test
    fun engineTokenBudget_clampsLargeGemma4ContextOnX86Emulator() {
        val budget = LiteRtLmOpenAiProxy.decideEngineTokenBudget(
            requestedMaxTokens = 4_000,
            requestedMaxContextLength = 32_000,
            totalRamBytes = 8_000_000_000L,
            modelBytes = 2_583_085_056L,
            isX86Device = true,
        )

        assertEquals(2_048, budget.value)
        assertTrue(budget.policy, budget.policy.contains("x86 emulator/device"))
    }

    @Test
    fun engineTokenBudget_keepsArmGemma4MemorySafeContext() {
        val budget = LiteRtLmOpenAiProxy.decideEngineTokenBudget(
            requestedMaxTokens = 4_000,
            requestedMaxContextLength = 32_000,
            totalRamBytes = 8_000_000_000L,
            modelBytes = 2_583_085_056L,
            isX86Device = false,
        )

        assertEquals(4_096, budget.value)
        assertTrue(budget.policy, budget.policy.contains("clamped requested context window"))
    }

    @Test
    fun engineTokenBudget_usesLiveHeadroomToClampVeryLargeModel() {
        val budget = LiteRtLmOpenAiProxy.decideEngineTokenBudget(
            requestedMaxTokens = 4_000,
            requestedMaxContextLength = 32_000,
            totalRamBytes = 16_000_000_000L,
            modelBytes = 6_500_000_000L,
            isX86Device = false,
            availableRamBytes = 10_000_000_000L,
            memoryThresholdBytes = 500_000_000L,
            lowMemory = false,
        )

        assertEquals(2_048, budget.value)
        assertTrue(budget.policy, budget.policy.contains("clamped requested context window"))
    }

    @Test
    fun runtimeFailureExplainsNativeOutOfMemoryRecovery() {
        val message = LiteRtLmOpenAiProxy.actionableRuntimeFailure(
            OutOfMemoryError("Failed to allocate memory for delegate"),
            "LiteRT-LM",
        )

        assertTrue(message, message.contains("exhausted available memory"))
        assertTrue(message, message.contains("choose a smaller model artifact"))
    }

    @Test
    fun gemma4DefaultsDoNotRequestThirtyTwoThousandTokensForTwelveBArtifact() {
        assertEquals(2_048, OnDeviceBackendManager.gemma4DefaultContextTokens(6_500_000_000L))
        assertEquals(4_096, OnDeviceBackendManager.gemma4DefaultContextTokens(2_583_085_056L))
    }

    @Test
    fun engineTokenBudget_usesSmallDefaultOnX86WhenModelHasBackendDefault() {
        val budget = LiteRtLmOpenAiProxy.decideEngineTokenBudget(
            requestedMaxTokens = -1,
            requestedMaxContextLength = -1,
            totalRamBytes = 16_000_000_000L,
            modelBytes = 2_583_085_056L,
            isX86Device = true,
        )

        assertEquals(2_048, budget.value)
        assertTrue(budget.policy, budget.policy.contains("x86 emulator/device"))
    }

    @Test
    fun engineTokenBudget_keepsLowRamX86EmulatorConservative() {
        val budget = LiteRtLmOpenAiProxy.decideEngineTokenBudget(
            requestedMaxTokens = 4_000,
            requestedMaxContextLength = 32_000,
            totalRamBytes = 3_000_000_000L,
            modelBytes = 2_583_085_056L,
            isX86Device = true,
        )

        assertEquals(512, budget.value)
        assertTrue(budget.policy, budget.policy.contains("x86 emulator/device"))
    }

    @Test
    fun engineTokenBudget_preservesBackendDefaultOnArmWhenUnspecified() {
        val budget = LiteRtLmOpenAiProxy.decideEngineTokenBudget(
            requestedMaxTokens = -1,
            requestedMaxContextLength = -1,
            totalRamBytes = 16_000_000_000L,
            modelBytes = 2_583_085_056L,
            isX86Device = false,
        )

        assertNull(budget.value)
        assertEquals("backend default", budget.policy)
    }

    private fun validateModelArtifact(file: File): String? {
        val method = LiteRtLmOpenAiProxy::class.java.getDeclaredMethod(
            "validateModelArtifact",
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(LiteRtLmOpenAiProxy, file.absolutePath) as String?
    }

    private fun tempModelFile(name: String, header: ByteArray): File {
        val dir = File(createTempDirectory(prefix = "hermes-litertlm-test-").pathString)
        return File(dir, name).apply {
            writeBytes(header + ByteArray(16) { 1 })
            deleteOnExit()
            dir.deleteOnExit()
        }
    }

    private fun awaitClosed(candidate: FakeStartupCandidate, message: String) {
        val closeDeadline = System.nanoTime() + 2_000_000_000L
        while (
            (!candidate.closed || LiteRtLmOpenAiProxy.nativeStartupUnwindActiveForTests()) &&
            System.nanoTime() < closeDeadline
        ) {
            Thread.sleep(5L)
        }
        assertTrue(message, candidate.closed)
        assertFalse(
            "$message; native ownership guard must also be clear",
            LiteRtLmOpenAiProxy.nativeStartupUnwindActiveForTests(),
        )
    }

    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        var released = false
        while (!released) {
            try {
                latch.await()
                released = true
            } catch (_: InterruptedException) {
                // Simulate JNI which does not observe Future.cancel(true).
            }
        }
    }

    private fun awaitAtomicTrue(value: AtomicBoolean, message: String) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!value.get() && System.nanoTime() < deadline) {
            Thread.sleep(5L)
        }
        assertTrue(message, value.get())
    }

    private fun awaitGenerationRestartRequired(
        coordinator: LiteRtLmOpenAiProxy.NativeGenerationCoordinator,
    ): Throwable? {
        val deadline = System.nanoTime() + 2_000_000_000L
        var latest: Throwable? = null
        while (System.nanoTime() < deadline) {
            latest = runCatching {
                coordinator.runBounded(timeoutMs = 100L) { "must not run" }
            }.exceptionOrNull()
            if (latest.orEmptyMessage().contains("requires an app restart")) {
                return latest
            }
            Thread.sleep(5L)
        }
        assertTrue(
            "Native generation failure did not retain a restart-required guard: ${latest.orEmptyMessage()}",
            false,
        )
        return latest
    }

    private fun awaitGenerationState(
        coordinator: LiteRtLmOpenAiProxy.NativeGenerationCoordinator,
        expected: String,
    ) {
        val deadline = System.nanoTime() + 2_000_000_000L
        var actual = coordinator.snapshot().state
        while (actual != expected && System.nanoTime() < deadline) {
            Thread.sleep(5L)
            actual = coordinator.snapshot().state
        }
        assertEquals("Native generation coordinator did not reach the expected state", expected, actual)
    }

    private fun Throwable?.orEmptyMessage(): String = this?.message.orEmpty()

    private fun awaitProbeGuardClear() {
        val deadline = System.nanoTime() + 2_000_000_000L
        var cleared = false
        while (!cleared && System.nanoTime() < deadline) {
            cleared = runCatching {
                LiteRtLmOpenAiProxy.resolveStartupProbes(
                    preferredAccelerator = "cpu",
                    speculativeDecodingMode = LiteRtLmOpenAiProxy.SpeculativeDecodingMode.DISABLED,
                    openClProbe = { error("OpenCL must be skipped") },
                    capabilitiesProbe = { error("Capabilities must be skipped") },
                )
            }.isSuccess
            if (!cleared) Thread.sleep(5L)
        }
        assertTrue("Native startup unwind guard did not clear after the owning worker exited", cleared)
    }

    private fun awaitRestartRequiredGuard(): LiteRtLmOpenAiProxy.StartupEngineSelection {
        val deadline = System.nanoTime() + 2_000_000_000L
        var latest = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(emptyList())
        while (
            latest.attempts.singleOrNull()?.contains("cleanup failed") != true &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(5L)
            latest = LiteRtLmOpenAiProxy.selectCompletionVerifiedEngine(emptyList())
        }
        assertTrue(
            "Native shutdown failure did not retain a restart-required guard",
            latest.attempts.singleOrNull().orEmpty().contains("cleanup failed"),
        )
        return latest
    }
}
