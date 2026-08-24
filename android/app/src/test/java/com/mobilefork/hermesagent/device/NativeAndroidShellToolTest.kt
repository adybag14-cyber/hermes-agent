package com.mobilefork.hermesagent.device

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.FutureTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class NativeAndroidShellToolTest {
    @Test
    fun nonTerminatingNativeShellFailsClosedAfterGracefulAndForcedStop() {
        val handle = object : NativeShellProcessStopHandle {
            var gracefulStops = 0
            var forcedStops = 0

            override val supportsForceDestroy: Boolean = true

            override fun exitValue(): Int = throw IllegalThreadStateException("still alive")

            override fun destroy() {
                gracefulStops += 1
            }

            override fun forceDestroy() {
                forcedStops += 1
            }
        }

        val result = NativeAndroidShellTool.terminateOwnedProcess(
            current = handle,
            gracefulTimeoutMs = 0L,
            forcedTimeoutMs = 0L,
        )

        assertTrue(result.failure is IllegalStateException)
        assertTrue(result.failure?.message.orEmpty().contains("remained alive"))
        assertFalse(result.interrupted)
        assertEquals(1, handle.gracefulStops)
        assertEquals(1, handle.forcedStops)
        assertTrue(runCatching { handle.exitValue() }.exceptionOrNull() is IllegalThreadStateException)
    }

    @Test
    fun api24NonTerminatingNativeShellNeverCallsUnavailableForcedDestroyAndFailsClosed() {
        val handle = object : NativeShellProcessStopHandle {
            var gracefulStops = 0
            var forcedStops = 0

            override val supportsForceDestroy: Boolean = false

            override fun exitValue(): Int = throw IllegalThreadStateException("still alive")

            override fun destroy() {
                gracefulStops += 1
            }

            override fun forceDestroy() {
                forcedStops += 1
            }
        }

        val result = NativeAndroidShellTool.terminateOwnedProcess(
            current = handle,
            gracefulTimeoutMs = 0L,
            forcedTimeoutMs = 0L,
        )

        assertTrue(result.failure is IllegalStateException)
        assertTrue(result.failure?.message.orEmpty().contains("requires Android 8.0 (API 26)"))
        assertEquals(1, handle.gracefulStops)
        assertEquals(0, handle.forcedStops)
    }

    @Test
    fun api24GracefulDestroyUsesExitValuePollingAndCompletesWithoutForce() {
        val handle = object : NativeShellProcessStopHandle {
            var alive = true
            var gracefulStops = 0
            var forcedStops = 0

            override val supportsForceDestroy: Boolean = false

            override fun exitValue(): Int {
                if (alive) throw IllegalThreadStateException("still alive")
                return 0
            }

            override fun destroy() {
                gracefulStops += 1
                alive = false
            }

            override fun forceDestroy() {
                forcedStops += 1
            }
        }

        val result = NativeAndroidShellTool.terminateOwnedProcess(
            current = handle,
            gracefulTimeoutMs = 0L,
            forcedTimeoutMs = 0L,
        )

        assertEquals(null, result.failure)
        assertEquals(1, handle.gracefulStops)
        assertEquals(0, handle.forcedStops)
        assertEquals(0, handle.exitValue())
    }

    @Test
    fun successfulParentDetachmentTerminatesOwnerMarkedChildBeforeUnwindIsVerified() {
        val baseline = NativeAndroidShellTool.ProcessInventorySnapshot(
            sameUidPids = setOf(100),
            ownerMarkedPids = emptySet(),
        )
        val inventory = FakeOwnedProcessInventory(
            sameUidPids = setOf(100, 200),
            ownerMarkedPids = setOf(200),
            exitOnGracefulSignal = true,
        )

        val result = NativeAndroidShellTool.containDetachedOwnedProcesses(
            baseline = baseline,
            ownerToken = "owner-token",
            inventory = inventory,
            gracefulTimeoutMs = 0L,
            forcedTimeoutMs = 0L,
        )

        assertTrue(result.verified)
        assertEquals(setOf(200), result.detectedOwnedPids)
        assertEquals(setOf(200), result.terminatedOwnedPids)
        assertTrue(result.remainingOwnedPids.isEmpty())
        assertEquals(listOf(200 to false), inventory.signals)
        assertEquals(
            125,
            NativeAndroidShellTool.nativeShellExitCode(
                cancelled = false,
                timedOut = false,
                cleanupUnsafe = !result.verified,
                detachedProcessDetected = result.detectedOwnedPids.isNotEmpty(),
                processExitCode = 0,
            ),
        )
    }

    @Test
    fun verifiedCancellationReturns130WithoutPoisoningTheFollowingCommandLane() {
        val cancelled = NativeAndroidShellTool.nativeShellLifecycleDisposition(
            expectedCancellation = true,
            completed = false,
            lifecycleFailure = null,
            unwindVerified = true,
            outputCaptureVerified = false,
            streamCleanupVerified = true,
        )

        assertTrue(cancelled.cleanCancellation)
        assertFalse(cancelled.unsafe)
        assertEquals(
            130,
            NativeAndroidShellTool.nativeShellExitCode(
                cancelled = cancelled.cleanCancellation,
                timedOut = false,
                cleanupUnsafe = cancelled.unsafe,
                detachedProcessDetected = false,
                processExitCode = null,
            ),
        )

        val followingCommand = NativeAndroidShellTool.nativeShellLifecycleDisposition(
            expectedCancellation = false,
            completed = true,
            lifecycleFailure = null,
            unwindVerified = true,
            outputCaptureVerified = true,
            streamCleanupVerified = true,
        )
        assertFalse("A verified Stop must not poison the subsequent command lane", followingCommand.unsafe)
        assertEquals(
            0,
            NativeAndroidShellTool.nativeShellExitCode(
                cancelled = followingCommand.cleanCancellation,
                timedOut = false,
                cleanupUnsafe = followingCommand.unsafe,
                detachedProcessDetected = false,
                processExitCode = 0,
            ),
        )
    }

    @Test
    fun verifiedTimeoutReturns124ButUnsafeTimeoutKeeps125Precedence() {
        val cleanTimeout = NativeAndroidShellTool.nativeShellLifecycleDisposition(
            expectedCancellation = false,
            completed = false,
            lifecycleFailure = null,
            unwindVerified = true,
            outputCaptureVerified = true,
            streamCleanupVerified = true,
            timedOut = true,
        )
        assertFalse(cleanTimeout.cleanCancellation)
        assertFalse(cleanTimeout.unsafe)
        assertEquals(
            124,
            NativeAndroidShellTool.nativeShellExitCode(
                cancelled = false,
                timedOut = true,
                cleanupUnsafe = false,
                detachedProcessDetected = false,
                processExitCode = null,
            ),
        )

        val unsafeTimeout = NativeAndroidShellTool.nativeShellLifecycleDisposition(
            expectedCancellation = false,
            completed = false,
            lifecycleFailure = null,
            unwindVerified = false,
            outputCaptureVerified = true,
            streamCleanupVerified = true,
            timedOut = true,
        )
        assertTrue(unsafeTimeout.unsafe)
        assertEquals(
            125,
            NativeAndroidShellTool.nativeShellExitCode(
                cancelled = false,
                timedOut = true,
                cleanupUnsafe = true,
                detachedProcessDetected = false,
                processExitCode = null,
            ),
        )
        assertEquals(
            125,
            NativeAndroidShellTool.nativeShellExitCode(
                cancelled = false,
                timedOut = true,
                cleanupUnsafe = false,
                detachedProcessDetected = true,
                processExitCode = null,
            ),
        )

        val unexpectedInterrupt = NativeAndroidShellTool.nativeShellLifecycleDisposition(
            expectedCancellation = false,
            completed = false,
            lifecycleFailure = null,
            unwindVerified = true,
            outputCaptureVerified = true,
            streamCleanupVerified = true,
            timedOut = false,
        )
        assertTrue("An unrelated cleanup interrupt must never be relabeled a clean timeout", unexpectedInterrupt.unsafe)
    }

    @Test
    fun waitingExecutionLaneIsInterruptibleAndDoesNotLeakIntoTheNextOwner() {
        val aEntered = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val bBodyRan = AtomicBoolean(false)
        val bInterrupted = AtomicBoolean(false)
        val a = thread(name = "native-shell-owner-a") {
            NativeAndroidShellTool.withExecutionPermitForTest {
                aEntered.countDown()
                check(releaseA.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(aEntered.await(5, TimeUnit.SECONDS))
        val b = thread(name = "native-shell-owner-b") {
            try {
                NativeAndroidShellTool.withExecutionPermitForTest {
                    bBodyRan.set(true)
                }
            } catch (_: InterruptedException) {
                bInterrupted.set(true)
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!NativeAndroidShellTool.isExecutionThreadQueuedForTest(b) && System.nanoTime() < deadline) {
            Thread.yield()
        }
        assertTrue("B never queued behind A", NativeAndroidShellTool.isExecutionThreadQueuedForTest(b))
        b.interrupt()
        b.join(2_000L)
        assertFalse("interrupted waiter remained parked behind unrelated A", b.isAlive)
        assertTrue(bInterrupted.get())
        assertFalse(bBodyRan.get())

        releaseA.countDown()
        a.join(2_000L)
        assertFalse(a.isAlive)
        val cInterrupted = NativeAndroidShellTool.withExecutionPermitForTest {
            Thread.currentThread().isInterrupted
        }
        assertFalse("A/B interrupt state leaked into the next command owner", cInterrupted)
    }

    @Test
    fun cancelledLayerDownloadRemovesPartialFileWithoutCancellingAnotherRequestClient() {
        val serverA = MockWebServer()
        val serverB = MockWebServer()
        serverA.start()
        serverB.start()
        val root = Files.createTempDirectory("hermes-layer-cancel").toFile()
        try {
            val slowBody = "x".repeat(4_096)
            serverA.enqueue(
                MockResponse()
                    .setBody(slowBody)
                    .throttleBody(1, 1, TimeUnit.SECONDS),
            )
            serverB.enqueue(
                // On the Windows JDK, cancelling a throttled fixed-length MockWebServer body can
                // remove the OkHttp call while leaving SocketInputStream.read parked until its
                // read timeout. NO_RESPONSE keeps B deterministically active and still proves
                // that A's request-owned cancellation does not reach B.
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),
            )
            // Use different bounded read timeouts for the two exact request clients. OkHttp call
            // cancellation is not guaranteed to wake a Windows SocketInputStream.read immediately;
            // A's timeout remains longer than the exact cancel assertion, so the test cannot
            // pass from a natural timeout. B's longer timeout keeps its independent blocked read
            // alive through A's bounded Windows socket-unwind interval.
            val clientA = OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val clientB = OkHttpClient.Builder()
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val targetA = File(root, "layer-a")
            val targetB = File(root, "layer-b")
            val sentinelA = "previously-verified-layer"
            targetA.writeText(sentinelA)
            val cancelledA = AtomicBoolean(false)
            val cancelledB = AtomicBoolean(false)
            val failureA = AtomicReference<Throwable?>(null)
            val failureB = AtomicReference<Throwable?>(null)
            val workerA = thread(name = "sandbox-layer-a") {
                failureA.set(
                    runCatching {
                        HermesLinuxSandboxBridge.downloadVerifiedLayer(
                            request = Request.Builder().url(serverA.url("/layer-a")).build(),
                            target = targetA,
                            expectedHex = "0".repeat(64),
                            expectedSize = slowBody.length.toLong(),
                            layerHttpClient = clientA,
                            cancellationRequested = cancelledA::get,
                        )
                    }.exceptionOrNull(),
                )
            }
            val workerB = thread(name = "sandbox-layer-b") {
                failureB.set(
                    runCatching {
                        HermesLinuxSandboxBridge.downloadVerifiedLayer(
                            request = Request.Builder().url(serverB.url("/layer-b")).build(),
                            target = targetB,
                            expectedHex = "0".repeat(64),
                            expectedSize = slowBody.length.toLong(),
                            layerHttpClient = clientB,
                            cancellationRequested = cancelledB::get,
                        )
                    }.exceptionOrNull(),
                )
            }

            assertTrue(serverA.takeRequest(5, TimeUnit.SECONDS) != null)
            assertTrue(serverB.takeRequest(5, TimeUnit.SECONDS) != null)
            val callA = clientA.dispatcher.runningCalls().single()
            val callB = clientB.dispatcher.runningCalls().single()
            cancelledA.set(true)
            clientA.dispatcher.cancelAll()
            workerA.interrupt()
            assertTrue("Exact sandbox layer call A was not cancelled", callA.isCanceled())
            assertFalse("Cancelling A also cancelled sandbox layer call B", callB.isCanceled())
            workerA.join(7_000L)

            assertFalse("Cancelled sandbox layer A remained alive", workerA.isAlive)
            assertTrue(failureA.get() != null)
            assertEquals("Cancellation replaced or deleted the existing verified layer", sentinelA, targetA.readText())
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".${targetA.name}.") })
            assertTrue("Cancelling layer A also stopped layer B", workerB.isAlive)
            assertEquals(null, failureB.get())

            cancelledB.set(true)
            clientB.dispatcher.cancelAll()
            workerB.interrupt()
            assertTrue("Exact sandbox layer call B was not cancelled", callB.isCanceled())
            workerB.join(17_000L)
            assertFalse(
                "Cancelled sandbox layer B remained alive; state=${workerB.state}; " +
                    "runningCalls=${clientB.dispatcher.runningCallsCount()}; " +
                    "failure=${failureB.get()}; stack=${workerB.stackTrace.joinToString()}",
                workerB.isAlive,
            )
            assertTrue(failureB.get() != null)
            assertFalse(targetB.exists())
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".${targetB.name}.") })
        } finally {
            serverA.shutdown()
            serverB.shutdown()
            root.deleteRecursively()
        }
    }

    @Test
    fun cachedLayerHashStopsBetweenChunksWithoutMutatingTheFile() {
        val root = Files.createTempDirectory("hermes-layer-hash-cancel").toFile()
        try {
            val target = File(root, "cached-layer")
            target.writeBytes(ByteArray(DEFAULT_BUFFER_SIZE * 4) { index -> (index % 251).toByte() })
            val original = target.readBytes()
            var checks = 0

            val failure = runCatching {
                HermesLinuxSandboxBridge.sha256File(target) {
                    checks += 1
                    checks > 1
                }
            }.exceptionOrNull()

            assertTrue(failure is InterruptedIOException)
            assertTrue("Hash cancellation was not checked between chunks", checks > 1)
            assertTrue("Hash cancellation mutated the cached layer", original.contentEquals(target.readBytes()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nativeSelfTestCancellationEscapesRowAndPreventsFollowingRows() {
        val cancelled = AtomicBoolean(false)
        val firstRows = java.util.concurrent.atomic.AtomicInteger(0)
        val laterRows = java.util.concurrent.atomic.AtomicInteger(0)

        val failure = runCatching {
            HermesDeviceDiagnosticsBridge.nativeSelfTestRowForTest(
                cancellationRequested = cancelled::get,
            ) {
                firstRows.incrementAndGet()
                cancelled.set(true)
            }
            HermesDeviceDiagnosticsBridge.nativeSelfTestRowForTest(
                cancellationRequested = cancelled::get,
            ) {
                laterRows.incrementAndGet()
            }
        }.exceptionOrNull()

        assertTrue(failure is java.util.concurrent.CancellationException)
        assertEquals(1, firstRows.get())
        assertEquals("Cancellation was swallowed and the next self-test row executed", 0, laterRows.get())

        cancelled.set(true)
        val neverEntered = AtomicBoolean(false)
        assertTrue(
            runCatching {
                HermesDeviceDiagnosticsBridge.nativeSelfTestRowForTest(cancelled::get) {
                    neverEntered.set(true)
                }
            }.exceptionOrNull() is java.util.concurrent.CancellationException,
        )
        assertFalse(neverEntered.get())
    }

    @Test
    fun survivingDetachedChildPreventsSuccessfulUnwindClaimAfterBoundedSignals() {
        val baseline = NativeAndroidShellTool.ProcessInventorySnapshot(
            sameUidPids = setOf(100),
            ownerMarkedPids = emptySet(),
        )
        val inventory = FakeOwnedProcessInventory(
            sameUidPids = setOf(100, 200),
            ownerMarkedPids = setOf(200),
        )

        val result = NativeAndroidShellTool.containDetachedOwnedProcesses(
            baseline = baseline,
            ownerToken = "owner-token",
            inventory = inventory,
            gracefulTimeoutMs = 0L,
            forcedTimeoutMs = 0L,
        )

        assertFalse(result.verified)
        assertEquals(setOf(200), result.remainingOwnedPids)
        assertTrue(result.failure?.message.orEmpty().contains("remained alive"))
        assertEquals(listOf(200 to false, 200 to true), inventory.signals)
    }

    @Test
    fun detachedChildIgnoringGracefulTimeoutCanBeForceStoppedAndRechecked() {
        val baseline = NativeAndroidShellTool.ProcessInventorySnapshot(
            sameUidPids = setOf(100),
            ownerMarkedPids = emptySet(),
        )
        val inventory = FakeOwnedProcessInventory(
            sameUidPids = setOf(100, 200),
            ownerMarkedPids = setOf(200),
            exitOnForcedSignal = true,
        )

        val result = NativeAndroidShellTool.containDetachedOwnedProcesses(
            baseline = baseline,
            ownerToken = "owner-token",
            inventory = inventory,
            gracefulTimeoutMs = 0L,
            forcedTimeoutMs = 0L,
        )

        assertTrue(result.verified)
        assertEquals(setOf(200), result.terminatedOwnedPids)
        assertTrue(result.remainingOwnedPids.isEmpty())
        assertEquals(listOf(200 to false, 200 to true), inventory.signals)
    }

    @Test
    fun unmarkedNewSameUidSurvivorFailsClosedWithoutRiskingAnUnownedKill() {
        val baseline = NativeAndroidShellTool.ProcessInventorySnapshot(
            sameUidPids = setOf(100),
            ownerMarkedPids = emptySet(),
        )
        val inventory = FakeOwnedProcessInventory(
            sameUidPids = setOf(100, 201),
            ownerMarkedPids = emptySet(),
        )

        val result = NativeAndroidShellTool.containDetachedOwnedProcesses(
            baseline = baseline,
            ownerToken = "owner-token",
            inventory = inventory,
            gracefulTimeoutMs = 0L,
            forcedTimeoutMs = 0L,
        )

        assertFalse(result.verified)
        assertEquals(setOf(201), result.ambiguousNewSameUidPids)
        assertTrue(result.failure?.message.orEmpty().contains("without a verifiable owner marker"))
        assertTrue(inventory.signals.isEmpty())
    }

    @Test
    fun interruptedOwnershipAuditCompletesBeforeCallerInterruptIsRestored() {
        val baseline = NativeAndroidShellTool.ProcessInventorySnapshot(
            sameUidPids = setOf(100),
            ownerMarkedPids = emptySet(),
        )
        val inventory = FakeOwnedProcessInventory(
            sameUidPids = setOf(100),
            ownerMarkedPids = emptySet(),
        )

        try {
            Thread.currentThread().interrupt()

            val result = NativeAndroidShellTool.containDetachedOwnedProcesses(
                baseline = baseline,
                ownerToken = "owner-token",
                inventory = inventory,
                gracefulTimeoutMs = 0L,
                forcedTimeoutMs = 0L,
            )

            assertTrue(result.verified)
            assertTrue(result.interrupted)
            assertFalse(Thread.currentThread().isInterrupted)

            NativeAndroidShellTool.restoreInterruptAfterOwnedCleanup(result.interrupted)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun nativeShellHandleAllocationFailureOccursBeforeProcessLaunch() {
        var launchAttempts = 0
        var cleanupCalls = 0

        val failure = runCatching {
            NativeAndroidShellTool.withNativeShellProcessOwnership(
                start = {
                    launchAttempts += 1
                    FakeProcess()
                },
                action = { _, _ -> Unit },
                cleanup = { _, _ -> cleanupCalls += 1 },
                handleFactory = {
                    throw OutOfMemoryError("injected handle allocation failure")
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is OutOfMemoryError)
        assertEquals(0, launchAttempts)
        assertEquals(0, cleanupCalls)
    }

    @Test
    fun injectedPostStartSetupFailureCleansParentAndDescendantBeforeRetryIsPoisoned() {
        val process = FakeProcess()
        val baseline = NativeAndroidShellTool.ProcessInventorySnapshot(
            sameUidPids = setOf(100),
            ownerMarkedPids = emptySet(),
        )
        val inventory = FakeOwnedProcessInventory(
            sameUidPids = setOf(100, 200),
            ownerMarkedPids = setOf(200),
            exitOnGracefulSignal = true,
        )
        var cleanupResult: NativeAndroidShellTool.PostStartFailureCleanupResult? = null

        val setupFailure = runCatching {
            NativeAndroidShellTool.withNativeShellProcessOwnership(
                start = { process },
                action = { _, _ ->
                    throw IllegalStateException("injected reader executor allocation failure")
                },
                cleanup = { _, processHandle ->
                    cleanupResult = NativeAndroidShellTool.cleanupAfterPostStartFailure(
                        current = processHandle,
                        baseline = baseline,
                        ownerToken = "owner-token",
                        inventory = inventory,
                        gracefulTimeoutMs = 0L,
                        forcedTimeoutMs = 0L,
                        detachedGracefulTimeoutMs = 0L,
                        detachedForcedTimeoutMs = 0L,
                    )
                },
            )
        }.exceptionOrNull()
        val cleanup = requireNotNull(cleanupResult)

        assertTrue(setupFailure is IllegalStateException)
        assertTrue(cleanup.verified)
        assertFalse(process.alive)
        assertEquals(1, process.gracefulStops)
        assertEquals(setOf(200), cleanup.containment.terminatedOwnedPids)

        val retryGate = PrivilegedShellRetryGate()
        var admittedDispatches = 0
        retryGate.executeAdmitted {
            admittedDispatches += 1
            JSONObject()
                .put("success", false)
                .put("exit_code", 125)
                .put("error", "reader setup failed after process start")
                .put("requires_service_restart", true)
        }
        val blockedRetry = retryGate.executeAdmitted {
            admittedDispatches += 1
            JSONObject().put("success", true)
        }

        assertEquals(1, admittedDispatches)
        assertEquals(125, blockedRetry.optInt("exit_code"))
        assertTrue(blockedRetry.optBoolean("requires_service_restart"))
    }

    @Test
    fun preInterruptedCallerCompletesGracefulAndForcedOwnershipChecksBeforeRestoringInterrupt() {
        val events = mutableListOf<String>()
        val handle = object : NativeShellProcessStopHandle {
            var alive = true

            override val supportsForceDestroy: Boolean = true

            override fun exitValue(): Int {
                if (alive) throw IllegalThreadStateException("still alive")
                return 0
            }

            override fun destroy() {
                events += "destroy"
            }

            override fun forceDestroy() {
                events += "forceDestroy"
                alive = false
            }
        }

        try {
            Thread.currentThread().interrupt()

            val result = NativeAndroidShellTool.terminateOwnedProcess(
                current = handle,
                gracefulTimeoutMs = 0L,
                forcedTimeoutMs = 0L,
            )

            assertEquals(listOf("destroy", "forceDestroy"), events)
            assertEquals(null, result.failure)
            assertTrue(result.interrupted)
            assertFalse(Thread.currentThread().isInterrupted)

            NativeAndroidShellTool.restoreInterruptAfterOwnedCleanup(result.interrupted)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun readerWhichNeverProducesEofReturnsBoundedIncompleteResult() {
        val unread = FutureTask<String> { "unreachable" }

        val result = NativeAndroidShellTool.readStreamWithin(unread, timeoutMs = 0L)

        assertFalse(result.completed)
        assertEquals("", result.text)
    }

    @Test
    fun shellInvocationUsesLoginCommandForPackagedBash() {
        val invocation = NativeAndroidShellTool.shellInvocation(
            shellPath = "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/arm64-v8a/prefix/bin/bash",
            command = "echo hello",
        )

        assertEquals("-lc", invocation[1])
        assertEquals("echo hello", invocation[2])
    }

    @Test
    fun resolveShellPathFallsBackToAndroidSystemShellWhenPackagedShellIsMissing() {
        val state = JSONObject()
            .put("shell_path", File("missing-bash").absolutePath)

        assertEquals("/system/bin/sh", NativeAndroidShellTool.resolveShellPath(state))
    }

    @Test
    fun resolveShellPathHonorsPersistedAndroidSystemFallback() {
        val state = JSONObject()
            .put("execution_mode", "android_system_shell")
            .put("shell_path", "/data/app/example/lib/x86_64/libhermes_android_bash.so")

        assertEquals("/system/bin/sh", NativeAndroidShellTool.resolveShellPath(state))
    }

    @Test
    fun linuxSandboxCatalogIncludesRecommendedMobileDistros() {
        val catalog = HermesLinuxSandboxCatalog.distroCatalog()
        val ids = buildSet {
            for (index in 0 until catalog.length()) {
                add(catalog.getJSONObject(index).getString("id"))
            }
        }

        assertTrue(ids.contains("debian-bookworm"))
        assertTrue(ids.contains("ubuntu-24-04"))
        assertTrue(ids.contains("alpine-3-21"))
        assertTrue(ids.contains("archlinux"))
        assertTrue(ids.contains("opensuse-tumbleweed"))
        assertTrue(HermesLinuxSandboxCatalog.agentSummary().getJSONArray("desktops").length() >= 3)
    }

    @Test
    fun linuxSandboxCatalogFindsDistroAliases() {
        val alpine = HermesLinuxSandboxCatalog.findDistro("hermes-alpine")

        assertEquals("alpine-3-21", alpine?.getString("id"))
        assertEquals("proot-distro install --name hermes-alpine alpine:3.21", alpine?.getString("install_command"))
    }

    @Test
    fun linuxSandboxCatalogIncludesMirrorProfiles() {
        val mirrors = HermesLinuxSandboxCatalog.mirrorProfiles()
        val ids = buildSet {
            for (index in 0 until mirrors.length()) {
                add(mirrors.getJSONObject(index).getString("id"))
            }
        }
        assertTrue(ids.contains("default"))
        assertTrue(ids.contains("china"))
        assertTrue(ids.contains("aliyun"))
        assertTrue(HermesLinuxSandboxCatalog.mirrorCommandFor("apt", "china").contains("mirrors.aliyun.com"))
        assertTrue(HermesLinuxSandboxCatalog.mirrorCommandFor("apk", "tsinghua").contains("mirrors.tuna.tsinghua.edu.cn"))
    }

    @Test
    fun linuxSandboxBridgeBuildsPackageUpdateCommands() {
        assertEquals(
            "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y upgrade && " +
                "DEBIAN_FRONTEND=noninteractive apt-get -y --no-install-recommends install curl",
            HermesLinuxSandboxBridge.updateCommandFor("apt"),
        )
        assertEquals(
            "zypper --non-interactive refresh && zypper --non-interactive update",
            HermesLinuxSandboxBridge.updateCommandFor("zypper"),
        )
        assertTrue(HermesLinuxSandboxBridge.updateCommandFor("").contains("command -v apt-get"))
    }

    @Test
    fun linuxSandboxLifecycleUpdateKeepsFullTimeoutContract() {
        assertEquals(120L, HermesLinuxSandboxBridge.commandTimeoutSeconds(900, useLifecycleTimeout = false))
        assertEquals(900L, HermesLinuxSandboxBridge.commandTimeoutSeconds(900, useLifecycleTimeout = true))
        assertEquals(180L, HermesLinuxSandboxBridge.commandTimeoutSeconds(180, useLifecycleTimeout = false))
    }

    @Test
    fun linuxSandboxBridgeSeedsGuestTrustBundleFromAndroidPemRoots() {
        val testRoot = Files.createTempDirectory("hermes-guest-ca-test").toFile()
        try {
            val source = File(testRoot, "android-cacerts").apply { mkdirs() }
            File(source, "bbbbbbbb.0").writeText(
                "ignored\n-----BEGIN CERTIFICATE-----\nBBBB\n-----END CERTIFICATE-----\n",
            )
            File(source, "aaaaaaaa.0").writeText(
                "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n",
            )
            val rootfs = File(testRoot, "rootfs").apply { mkdirs() }

            val result = HermesLinuxSandboxBridge.ensureGuestCaBundle(rootfs, listOf(source))

            assertEquals(result.toString(2), 0, result.optInt("exit_code", -1))
            assertEquals(2, result.optInt("certificate_count"))
            assertEquals(source.absolutePath, result.optString("source"))
            assertEquals(
                "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n" +
                    "-----BEGIN CERTIFICATE-----\nBBBB\n-----END CERTIFICATE-----\n",
                File(rootfs, "etc/ssl/certs/ca-certificates.crt").readText(),
            )

            val destination = File(rootfs, "etc/ssl/certs/ca-certificates.crt")
            destination.writeText("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n")
            val repaired = HermesLinuxSandboxBridge.ensureGuestCaBundle(rootfs, listOf(source))
            assertEquals(repaired.toString(2), 0, repaired.optInt("exit_code", -1))
            assertEquals(source.absolutePath, repaired.optString("source"))
            assertEquals(2, repaired.optInt("certificate_count"))
            assertEquals(2, repaired.optInt("android_certificate_count"))
            assertEquals(1, repaired.optInt("previous_certificate_count"))
            assertTrue(repaired.optBoolean("replaced_truncated_guest_bundle"))
            assertEquals(
                "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n" +
                    "-----BEGIN CERTIFICATE-----\nBBBB\n-----END CERTIFICATE-----\n",
                destination.readText(),
            )

            File(source, "aaaaaaaa.0").writeText("")
            val preserved = HermesLinuxSandboxBridge.ensureGuestCaBundle(rootfs, listOf(source))
            assertEquals(0, preserved.optInt("exit_code", -1))
            assertEquals("existing_guest_bundle", preserved.optString("source"))
            assertEquals(2, preserved.optInt("certificate_count"))
            assertEquals(1, preserved.optInt("android_certificate_count"))

            File(source, "bbbbbbbb.0").writeText("")
            val unverified = HermesLinuxSandboxBridge.ensureGuestCaBundle(rootfs, listOf(source))
            assertEquals(1, unverified.optInt("exit_code", -1))
            assertEquals(2, unverified.optInt("existing_certificate_count"))
            assertTrue(unverified.optString("error").contains("trust-root count"))
            assertEquals(2, Regex("-----BEGIN CERTIFICATE-----").findAll(destination.readText()).count())
        } finally {
            testRoot.deleteRecursively()
        }
    }

    @Test
    fun linuxSandboxFailedDeployReportsPreservedIncompleteStateForRetry() {
        val result = HermesLinuxSandboxBridge.annotateDeployDisposition(
            result = JSONObject().put("exit_code", 124),
            failedPhase = "update",
            sandboxExistedBefore = false,
            sandboxPresentAfterDeploy = true,
        )

        assertFalse(result.optBoolean("deployment_completed", true))
        assertEquals("update", result.optString("failed_phase"))
        assertFalse(result.optBoolean("sandbox_existed_before", true))
        assertTrue(result.optBoolean("sandbox_present_after_deploy"))
        assertTrue(result.optBoolean("sandbox_preserved_for_retry"))
        assertEquals("preserved_incomplete", result.optString("sandbox_state"))
        assertTrue(result.optString("message").contains("new sandbox was preserved"))
    }

    @Test
    fun linuxSandboxInstallRetriesOnlyTransientTlsRecordFailuresWithAndroidHttp() {
        assertTrue(
            HermesLinuxSandboxBridge.shouldRetryInstallWithAndroidHttp(
                JSONObject().put("exit_code", 1).put("error", "SSL: RECORD_LAYER_FAILURE"),
            ),
        )
        assertTrue(
            HermesLinuxSandboxBridge.shouldRetryInstallWithAndroidHttp(
                JSONObject().put("exit_code", 1).put("error", "UNEXPECTED_EOF_WHILE_READING"),
            ),
        )
        assertTrue(
            HermesLinuxSandboxBridge.shouldRetryInstallWithAndroidHttp(
                JSONObject().put("exit_code", 1).put("error", "certificate verify failed"),
            ).not(),
        )
        assertEquals(
            "aca76fef1f67058b",
            HermesLinuxSandboxBridge.dockerManifestCacheKey("alpine:3.21", "x86_64"),
        )
        assertEquals(
            "b632145ecd134a4c",
            HermesLinuxSandboxBridge.dockerManifestCacheKey("alpine:3.21", "aarch64"),
        )
    }

    @Test
    fun linuxSandboxBridgeBuildsQuotedInstallAndRunCommands() {
        assertEquals(
            "proot-distro install --name 'hermes-alpine' --architecture 'aarch64' 'alpine:3.21'",
            HermesLinuxSandboxBridge.installCommandFor("hermes-alpine", "alpine:3.21", "aarch64"),
        )
        assertEquals("aarch64", HermesLinuxSandboxBridge.preferredGuestArchitecture("x86_64"))
        assertEquals("x86_64", HermesLinuxSandboxBridge.preferredGuestArchitecture("arm64-v8a"))
        val runCommand = HermesLinuxSandboxBridge.runCommandFor(
            prefixPath = "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/x86_64/prefix",
            sandboxName = "hermes-alpine",
            command = "printf 'hello world'",
            qemuPath = "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/x86_64/native-exec/bin/qemu-x86_64",
        )

        assertTrue(runCommand.startsWith("HERMES_SANDBOX_ROOTFS="))
        assertTrue(runCommand.contains("qemu-x86_64"))
        assertTrue(runCommand.contains("proot-distro run 'hermes-alpine'"))
        assertTrue(runCommand.contains("--emulator"))
        assertTrue(runCommand.contains("/bin/sh -lc"))
        val guestPath = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        val guestCommand =
            "PATH=${HermesLinuxSubsystemBridge.shellQuote(guestPath)}; " +
                "export PATH; printf 'hello world'"
        assertTrue(
            runCommand.contains(
                "/bin/sh -lc ${HermesLinuxSubsystemBridge.shellQuote(guestCommand)}",
            ),
        )
        assertFalse(runCommand.contains("PATH=/data/user/0/com.mobilefork.hermesagent"))
        assertTrue(runCommand.contains("hermes-alpine/rootfs"))
        assertTrue(runCommand.contains("printf"))
        assertTrue(runCommand.contains("hello world"))

    }

    @Test
    fun linuxSandboxBridgeTrimsPromptPunctuationFromSelectors() {
        assertEquals(
            "alpine-3-21",
            HermesLinuxSandboxBridge.normalizeArgumentValue(" alpine-3-21. "),
        )
        assertEquals(
            "hermes-alpine",
            HermesLinuxSandboxBridge.normalizeArgumentValue("hermes-alpine;"),
        )
        assertEquals(
            "alpine:3.21",
            HermesLinuxSandboxBridge.normalizeArgumentValue("alpine:3.21,"),
        )
    }

    @Test
    fun embeddedAliasPreludeRoutesProotDistroThroughPackagedPython() {
        val state = JSONObject()
            .put("uses_termux", true)
            .put("prefix_path", "/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix")
            .put("home_path", "/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/home")
            .put("tmp_path", "/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/tmp")
            .put("app_package_name", "com.nousresearch.hermesagent")
            .put("native_library_dir", "/data/app/example/lib/x86_64")
            .put("lib_path", "/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/lib")
            .put("python_path", "/data/app/example/lib/x86_64/libhermes_exec_bin_python3_14.so")
            .put("python_lib_path", "/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/lib/python3.14")
            .put("native_proot_path", "/data/app/example/lib/x86_64/libhermes_exec_bin_proot.so")
            .put("native_execution_route", "apk_native_library_direct")

        val command = HermesLinuxSubsystemBridge.commandWithEmbeddedToolAliases(state, "proot-distro list")

        assertTrue(command.contains("TERMUX_APP__PACKAGE_NAME='com.nousresearch.hermesagent'"))
        assertTrue(command.contains("TERMUX__PREFIX='/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix'"))
        assertTrue(command.contains("PROOT_TMP_DIR='/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/tmp'"))
        assertTrue(command.contains("PROOT_LOADER='/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/libexec/proot/loader'"))
        assertTrue(command.contains("PROOT_LOADER_32='/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/libexec/proot/loader32'"))
        assertTrue(command.contains("PROOT_NO_SECCOMP='1'"))
        assertTrue(command.contains("LD_LIBRARY_PATH='/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/lib:/data/app/example/lib/x86_64'"))
        assertTrue(command.contains("/data/app/example/lib/x86_64/libhermes_exec_bin_python3_14.so'"))
        assertTrue(command.contains("HERMES_ANDROID_PROOT_EXECUTABLE='/data/app/example/lib/x86_64/libhermes_exec_bin_proot.so'"))
        assertTrue(command.contains("python3.13").not())
        assertTrue(command.contains("proot-distro() { case \"\${1:-}\" in login|sh|run)"))
        assertTrue(command.contains("\"${'$'}_pd_cmd\" -e \"LD_LIBRARY_PATH=${'$'}LD_LIBRARY_PATH\" -e \"PROOT_TMP_DIR=${'$'}PROOT_TMP_DIR\" -e \"PROOT_LOADER=${'$'}PROOT_LOADER\" -e \"PROOT_LOADER_32=${'$'}PROOT_LOADER_32\" -e \"PROOT_NO_SECCOMP=${'$'}PROOT_NO_SECCOMP\""))
        assertTrue(command.endsWith("; proot-distro list"))
    }

    @Test
    fun embeddedEnvironmentPublishesOnlyDirectPackagedProotPath() {
        val state = JSONObject()
            .put("prefix_path", "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/arm64-v8a/prefix")
            .put("native_proot_path", "/data/app/example/lib/arm64/libhermes_exec_bin_proot.so")
            .put("native_command_env_path", "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/arm64-v8a/native-command-functions.sh")

        val environment = HermesLinuxSubsystemBridge.buildRunEnvironment(state)

        assertEquals(
            "/data/app/example/lib/arm64/libhermes_exec_bin_proot.so",
            environment["HERMES_ANDROID_PROOT_EXECUTABLE"],
        )
        assertEquals(
            "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/arm64-v8a/native-command-functions.sh",
            environment["HERMES_ANDROID_NATIVE_COMMAND_ENV"],
        )
    }

    @Test
    fun sandboxQemuPrefersDirectApkNativeLibrary() {
        val qemu = File.createTempFile("hermes-qemu-direct-", ".so")
        try {
            qemu.writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
            qemu.setExecutable(true, false)
            val state = JSONObject()
                .put("native_qemu_x86_64_path", qemu.absolutePath)
                .put("prefix_path", File(qemu.parentFile, "prefix").absolutePath)

            assertEquals(
                qemu.absolutePath,
                HermesLinuxSandboxBridge.qemuPathForGuestArchitecture(state, "x86_64"),
            )
        } finally {
            qemu.delete()
        }
    }

    @Test
    fun sandboxQemuRejectsLegacyShimResolvedIntoWritablePrefix() {
        val root = createTempDir(prefix = "hermes-qemu-prefix-")
        try {
            val prefix = File(root, "prefix").apply { mkdirs() }
            val writableBin = File(prefix, "bin").apply { mkdirs() }
            File(writableBin, "qemu-x86_64").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
                setExecutable(true, false)
            }
            val state = JSONObject()
                .put("prefix_path", prefix.absolutePath)
                .put("native_bin_path", writableBin.absolutePath)

            val resolved = HermesLinuxSandboxBridge.qemuPathForGuestArchitecture(state, "x86_64")

            assertTrue(resolved.isBlank())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sandboxWritablePrefixContainmentDoesNotMatchSiblingWithSameNamePrefix() {
        val root = kotlin.io.path.createTempDirectory("hermes-prefix-boundary-").toFile()
        try {
            val prefix = File(root, "prefix").apply { mkdirs() }
            val inside = File(prefix, "bin/qemu-x86_64")
            val sibling = File(root, "prefix-sibling/bin/qemu-x86_64")

            assertTrue(HermesLinuxSandboxBridge.isInsideDirectory(inside, prefix))
            assertFalse(HermesLinuxSandboxBridge.isInsideDirectory(sibling, prefix))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exit126HintRejectsChmodAndBroadStorageWorkarounds() {
        val prefix = "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/arm64-v8a/prefix"
        val hint = NativeAndroidShellTool.executionDeniedHint(
            JSONObject()
                .put("prefix_path", prefix)
                .put("native_execution_route", "apk_native_library_direct"),
            "$prefix/bin/curl --version",
        )

        assertTrue(hint.contains("writable prefix path"))
        assertTrue(hint.contains("cannot be made executable with chmod"))
        assertTrue(hint.contains("do not grant broad storage permission"))
    }

    private class FakeProcess : Process() {
        var alive = true
        var gracefulStops = 0
        private val processInput = ByteArrayInputStream(ByteArray(0))
        private val processError = ByteArrayInputStream(ByteArray(0))
        private val processOutput = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = processOutput

        override fun getInputStream(): InputStream = processInput

        override fun getErrorStream(): InputStream = processError

        override fun waitFor(): Int {
            if (alive) throw IllegalThreadStateException("still alive")
            return 0
        }

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("still alive")
            return 0
        }

        override fun destroy() {
            gracefulStops += 1
            alive = false
        }
    }

    private class FakeOwnedProcessInventory(
        sameUidPids: Set<Int>,
        ownerMarkedPids: Set<Int>,
        private val exitOnGracefulSignal: Boolean = false,
        private val exitOnForcedSignal: Boolean = false,
    ) : NativeAndroidShellTool.OwnedProcessInventory {
        private val currentSameUidPids = sameUidPids.toMutableSet()
        private val currentOwnerMarkedPids = ownerMarkedPids.toMutableSet()
        val signals = mutableListOf<Pair<Int, Boolean>>()

        override fun snapshot(ownerToken: String): NativeAndroidShellTool.ProcessInventorySnapshot {
            return NativeAndroidShellTool.ProcessInventorySnapshot(
                sameUidPids = currentSameUidPids.toSet(),
                ownerMarkedPids = currentOwnerMarkedPids.toSet(),
            )
        }

        override fun signalIfOwned(pid: Int, ownerToken: String, force: Boolean): Throwable? {
            if (pid !in currentOwnerMarkedPids) {
                return IllegalStateException("PID $pid is no longer owner-marked")
            }
            signals += pid to force
            if ((!force && exitOnGracefulSignal) || (force && exitOnForcedSignal)) {
                currentOwnerMarkedPids -= pid
                currentSameUidPids -= pid
            }
            return null
        }
    }
}
