package com.mobilefork.hermesagent.ui.chat

import com.mobilefork.hermesagent.device.NativeAndroidShellTool
import com.mobilefork.hermesagent.device.NativeShellProcessStopHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class NativeToolChatSenderTest {
    private class BlockingOwnedProcess(
        private val cleanupEntered: CountDownLatch,
        private val releaseCleanup: CountDownLatch,
    ) : NativeShellProcessStopHandle {
        private val alive = AtomicBoolean(true)

        override val supportsForceDestroy: Boolean = true

        override fun exitValue(): Int {
            if (alive.get()) throw IllegalThreadStateException("owned process is still alive")
            return 0
        }

        override fun destroy() {
            cleanupEntered.countDown()
            check(releaseCleanup.await(5, TimeUnit.SECONDS))
            alive.set(false)
        }

        override fun forceDestroy() {
            alive.set(false)
        }

        fun isAlive(): Boolean = alive.get()
    }

    @Test
    fun closedTypedAuthorityBindsOnlyTheLeadingToolAndCarriesParsedArguments() {
        val terminal = NativeDirectToolAuthorityParser.parse("terminal_tool command=\"pwd\"")
        assertEquals(setOf("terminal_tool"), terminal.toolNames)
        assertEquals("pwd", terminal.arguments().getString("command"))

        val naturalTerminal = NativeDirectToolAuthorityParser.parse("Use terminal_tool to run pwd")
        assertEquals(setOf("terminal_tool"), naturalTerminal.toolNames)
        assertEquals("pwd", naturalTerminal.arguments().getString("command"))

        val file = NativeDirectToolAuthorityParser.parse("file_write_tool path=notes.txt content=hello")
        assertEquals(setOf("file_write_tool"), file.toolNames)
        assertEquals("notes.txt", file.arguments().getString("path"))
        assertEquals("hello", file.arguments().getString("content"))

        val diagnostic = NativeDirectToolAuthorityParser.parse(
            "Run android_device_diagnostics_tool action=wifi_scan refresh=false",
        )
        assertEquals(setOf("android_device_diagnostics_tool"), diagnostic.toolNames)
        assertEquals("wifi_scan", diagnostic.arguments().getString("action"))
        assertFalse(diagnostic.arguments().getBoolean("refresh"))

        val sandbox = NativeDirectToolAuthorityParser.parse(
            "Call linux_sandbox_tool with action=deploy and distro_id=alpine-3-21.",
        )
        assertEquals(setOf("linux_sandbox_tool"), sandbox.toolNames)
        assertEquals("deploy", sandbox.arguments().getString("action"))
        assertEquals("alpine-3-21", sandbox.arguments().getString("distro_id"))

        val confusedDeputy = NativeDirectToolAuthorityParser.parse(
            "terminal_tool run command: printf file_write_tool write escalated.txt with content pwned",
        )
        assertTrue(confusedDeputy.toolNames.isEmpty())

        val quotedTerminalPayload = NativeDirectToolAuthorityParser.parse(
            "terminal_tool command=\"printf file_write_tool write escalated.txt with content pwned\"",
        )
        assertEquals(setOf("terminal_tool"), quotedTerminalPayload.toolNames)
        assertEquals(
            "printf file_write_tool write escalated.txt with content pwned",
            quotedTerminalPayload.arguments().getString("command"),
        )

        val exactTimeout = NativeDirectToolAuthorityParser.parse(
            "terminal_tool command=\"sleep 2\" timeout_seconds=1",
        )
        assertEquals(setOf("terminal_tool"), exactTimeout.toolNames)
        assertEquals(1, exactTimeout.arguments().getInt("timeout_seconds"))

        listOf(
            "terminal_tool command=\"pwd\" timeout_seconds=0",
            "terminal_tool command=\"pwd\" timeout_seconds=901",
            "terminal_tool command=\"pwd\" timeout_seconds=2147483648",
            "linux_sandbox_tool action=status timeout_seconds=0",
            "linux_sandbox_tool action=status timeout_seconds=901",
            "linux_sandbox_tool action=status timeout_seconds=2147483648",
        ).forEach { prompt ->
            assertTrue(
                "invalid/overflow timeout unexpectedly retained direct authority: $prompt",
                NativeDirectToolAuthorityParser.parse(prompt).toolNames.isEmpty(),
            )
        }
    }

    @Test
    fun contradictoryMetaQuotedAndMultilingualProseHasNoTypedAuthority() {
        listOf(
            "Use terminal_tool? No, do not. run command: printf pwned",
            "Use file_write_tool? No, do not. write \"negated.txt\" with content pwned",
            "Use linux_sandbox_tool? No, do not. action=uninstall distro_id=alpine-3-21",
            "terminal_tool must not run command: printf pwned",
            "file_write_tool must not write negated.txt with content pwned",
            "linux_sandbox_tool must not action=uninstall distro_id=alpine-3-21",
            "Use terminal_tool but not this command: printf pwned",
            "Use terminal_tool to run touch pwned.txt but do not execute it",
            "terminal_tool run command: rm -f victim.txt but not now",
            "Use terminal_tool? No, no lo ejecutes. run command: printf pwned",
            "Use terminal_tool? Nein, nicht ausführen. run command: printf pwned",
            "Use terminal_tool? Não execute. run command: printf pwned",
            "Use terminal_tool? Non, ne l'exécute pas. run command: printf pwned",
            "Use terminal_tool? 不要执行。run command: printf pwned",
            "Explain what terminal_tool command=\"pwd\" does",
            "Show me what my friend meant when she said open the browser.",
            "`terminal_tool command=\"pwd\"`",
            "```text\nterminal_tool command=\"pwd\"\n```",
            "\"android_device_diagnostics_tool action=wifi_scan\"",
            "Create a game. Use file_write_tool path=game.html content=x. Then use android_automation_tool action=open_uri.",
            "file_write_tool path=pwned.txt content=pwned do_not_write=true",
            "linux_sandbox_tool action=install distro_id=alpine-3-21 do_not_execute=true",
        ).forEach { prompt ->
            assertTrue("unexpected direct authority for: $prompt", NativeDirectToolAuthorityParser.parse(prompt).toolNames.isEmpty())
        }
    }

    @Test
    fun directSenderRoutesConsumeOnlyClosedAuthorityArguments() {
        assertEquals(
            "wifi_scan",
            NativeToolChatSender.extractDirectDiagnosticsArguments(
                "android_device_diagnostics_tool action=wifi_scan",
            )?.getString("action"),
        )
        assertEquals(
            null,
            NativeToolChatSender.extractDirectDiagnosticsArguments(
                "Do not use android_device_diagnostics_tool action=wifi_scan",
            ),
        )
        assertEquals(
            null,
            NativeToolChatSender.extractDirectDiagnosticsArguments(
                "Explain what an all features test does",
            ),
        )
        assertEquals("date", NativeToolChatSender.extractDirectReadOnlyTerminalCommand("What time is it?"))
        assertEquals(
            null,
            NativeToolChatSender.extractDirectReadOnlyTerminalCommand(
                "Use terminal_tool? No, do not. run command: date",
            ),
        )
        assertTrue(
            NativeToolChatSender.extractDirectLinuxSandboxPrompt(
                "linux_sandbox_tool action=run distro_id=alpine-3-21 command=uname",
            ),
        )
        assertFalse(
            NativeToolChatSender.extractDirectLinuxSandboxPrompt(
                "Use linux_sandbox_tool? No, do not. action=uninstall distro_id=alpine-3-21",
            ),
        )
    }

    @Test
    fun stopAInPreRegistrationGapPreventsAStartAndCannotCancelOrOverwriteB() {
        val coordinator = ChatSendRequestCoordinator()
        val aPassedOwnershipCheck = CountDownLatch(1)
        val releaseARegistration = CountDownLatch(1)
        val bStarted = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val startsA = AtomicInteger(0)
        val startsB = AtomicInteger(0)
        val cancelsA = AtomicInteger(0)
        val cancelsB = AtomicInteger(0)
        val aRejectedBeforeStart = AtomicBoolean(false)
        val visibleTerminal = AtomicReference("initial")

        val operationA = NativeToolChatOperation(
            onCancel = { cancelsA.incrementAndGet() },
            executeBlock = {
                startsA.incrementAndGet()
                "A result"
            },
        )
        val requestA = coordinator.begin("session-a", "assistant-a") {}!!
        assertTrue(coordinator.attachNetwork(requestA) { operationA.cancel() })
        val aThread = thread(name = "native-a-pre-registration") {
            // This is the old vulnerable gap: A has passed the coordinator check but has not
            // published/entered its native client yet.
            assertTrue(coordinator.isActive(requestA))
            aPassedOwnershipCheck.countDown()
            releaseARegistration.await(5, TimeUnit.SECONDS)
            val claimed = coordinator.claimWorkStartIfActive(requestA, operationA::claimStart)
            val result = if (claimed) {
                runCatching { operationA.executeClaimed() }
            } else {
                operationA.cancel()
                Result.failure(CancellationException("A lost request ownership before registration"))
            }
            aRejectedBeforeStart.set(result.exceptionOrNull() is CancellationException)
            result.getOrNull()?.let { content ->
                coordinator.finishIfActive(requestA) {
                    visibleTerminal.set(content)
                    true
                }
            }
        }
        assertTrue(aPassedOwnershipCheck.await(5, TimeUnit.SECONDS))

        assertEquals(
            requestA,
            coordinator.stopActive { visibleTerminal.set("A stopped") },
        )
        assertEquals(1, cancelsA.get())
        assertEquals(0, startsA.get())

        val operationB = NativeToolChatOperation(
            onCancel = { cancelsB.incrementAndGet() },
            executeBlock = {
                startsB.incrementAndGet()
                bStarted.countDown()
                releaseB.await(5, TimeUnit.SECONDS)
                "B late result"
            },
        )
        val requestB = coordinator.begin("session-b", "assistant-b") {
            visibleTerminal.set("B active")
        }!!
        assertTrue(coordinator.attachNetwork(requestB) { operationB.cancel() })
        val bThread = thread(name = "native-b-running") {
            assertTrue(coordinator.claimWorkStartIfActive(requestB, operationB::claimStart))
            val content = operationB.executeClaimed()
            coordinator.finishIfActive(requestB) {
                visibleTerminal.set(content)
                true
            }
        }
        assertTrue(bStarted.await(5, TimeUnit.SECONDS))

        // Let the retired A try to enter while B owns the coordinator. Its sticky pre-start
        // cancellation must reject the body and leave B's handle/state untouched.
        releaseARegistration.countDown()
        aThread.join(5_000L)
        assertFalse(aThread.isAlive)
        assertTrue(aRejectedBeforeStart.get())
        assertEquals(0, startsA.get())
        assertEquals(0, cancelsB.get())
        assertTrue(coordinator.isActive(requestB))
        assertEquals("B active", visibleTerminal.get())

        assertEquals(
            requestB,
            coordinator.stopActive { visibleTerminal.set("B stopped") },
        )
        assertEquals(1, cancelsA.get())
        assertEquals(1, cancelsB.get())
        releaseB.countDown()
        bThread.join(5_000L)

        assertFalse(bThread.isAlive)
        assertEquals(1, startsB.get())
        assertEquals("B stopped", visibleTerminal.get())
        assertFalse(coordinator.finishIfActive(requestA) { true })
        assertFalse(coordinator.finishIfActive(requestB) { true })
    }

    @Test
    fun stopAfterClaimButBeforeExecutionCompletesImmediatelyWithoutPoisoningFollowingRequest() {
        val guard = NativeToolOperationLaneGuard()
        val coordinator = ChatSendRequestCoordinator(nativeUnwindTimeoutMs = 0L)
        val cancelsA = AtomicInteger(0)
        val startsA = AtomicInteger(0)
        val terminalA = AtomicBoolean(false)
        val operationA = NativeToolChatOperation(
            onCancel = { cancelsA.incrementAndGet() },
            executeBlock = {
                startsA.incrementAndGet()
                "A must not execute"
            },
            laneGuard = guard,
        )
        val requestA = coordinator.begin("claimed-a", "assistant-a") {}!!
        assertTrue(
            coordinator.attachNetwork(
                request = requestA,
                cancel = { operationA.cancel() },
                awaitNativeUnwind = operationA::awaitCompletion,
            ),
        )
        assertTrue(coordinator.claimWorkStartIfActive(requestA, operationA::claimStart))

        assertEquals(requestA, coordinator.stopActive { terminalA.set(true) })
        assertTrue(terminalA.get())
        assertEquals(1, cancelsA.get())
        assertEquals(0, startsA.get())
        assertEquals(null, guard.poisonDetailForTest())
        assertTrue(runCatching { operationA.executeClaimed() }.exceptionOrNull() is CancellationException)

        val startsB = AtomicInteger(0)
        val operationB = NativeToolChatOperation(
            onCancel = {},
            executeBlock = {
                startsB.incrementAndGet()
                "B completed"
            },
            laneGuard = guard,
        )
        val requestB = coordinator.begin("claimed-b", "assistant-b") {}!!
        assertTrue(coordinator.claimWorkStartIfActive(requestB, operationB::claimStart))
        assertEquals("B completed", operationB.executeClaimed())
        assertTrue(coordinator.finishIfActive(requestB) { true })
        assertEquals(1, startsB.get())
        assertEquals(null, guard.poisonDetailForTest())
    }

    @Test
    fun stopWaitsForExactOwnedProcessCleanupBeforeTerminalAndReplacementB() {
        val coordinator = ChatSendRequestCoordinator(nativeUnwindTimeoutMs = 2_000L)
        val cancellationRequested = AtomicBoolean(false)
        val operationStarted = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val process = BlockingOwnedProcess(cleanupEntered, releaseCleanup)
        val cleanupVerified = AtomicBoolean(false)
        val lateCallbackRejected = AtomicBoolean(false)
        val terminalPersisted = AtomicBoolean(false)
        val replacementB = AtomicReference<ChatSendRequestCoordinator.Request?>()
        lateinit var requestA: ChatSendRequestCoordinator.Request
        val operation = NativeToolChatOperation(
            onCancel = { cancellationRequested.set(true) },
            executeBlock = {
                operationStarted.countDown()
                while (!cancellationRequested.get()) Thread.yield()
                lateCallbackRejected.set(!coordinator.mutateIfActive(requestA) {})
                val cleanup = NativeAndroidShellTool.terminateOwnedProcess(
                    current = process,
                    gracefulTimeoutMs = 1_000L,
                    forcedTimeoutMs = 1_000L,
                )
                cleanupVerified.set(cleanup.failure == null && !process.isAlive())
                "A result"
            },
        )
        requestA = coordinator.begin("owned-process-a", "assistant-a") {}!!
        assertTrue(
            coordinator.attachNetwork(
                request = requestA,
                cancel = { operation.cancel() },
                awaitNativeUnwind = operation::awaitCompletion,
            ),
        )
        val worker = thread(name = "native-owned-process-a") {
            assertTrue(coordinator.claimWorkStartIfActive(requestA, operation::claimStart))
            val content = operation.executeClaimed()
            coordinator.finishIfActive(requestA) { error("late A result persisted: $content") }
        }
        assertTrue(operationStarted.await(5, TimeUnit.SECONDS))

        val stopThread = thread(name = "native-owned-process-stop") {
            coordinator.stopActive {
                assertTrue("terminal preceded verified owned-process cleanup", cleanupVerified.get())
                terminalPersisted.set(true)
            }
        }
        assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
        val replacementThread = thread(name = "native-owned-process-b") {
            replacementB.set(coordinator.begin("owned-process-b", "assistant-b") {})
        }

        assertFalse("Stop terminalized while A cleanup was still blocked", terminalPersisted.get())
        assertFalse("replacement B was admitted before A cleanup", replacementThread.joinAndReport(100L))
        releaseCleanup.countDown()
        worker.join(5_000L)
        stopThread.join(5_000L)
        replacementThread.join(5_000L)

        assertFalse(worker.isAlive)
        assertFalse(stopThread.isAlive)
        assertFalse(replacementThread.isAlive)
        assertTrue(cleanupVerified.get())
        assertTrue("late native callback could still mutate retired request A", lateCallbackRejected.get())
        assertTrue(terminalPersisted.get())
        assertTrue(replacementB.get() != null)
    }

    @Test
    fun unverifiableOperationUnwindPoisonsAndFailClosesTheNativeLane() {
        val guard = NativeToolOperationLaneGuard()
        val coordinator = ChatSendRequestCoordinator(nativeUnwindTimeoutMs = 25L)
        val cancellationRequested = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val terminalPersisted = AtomicBoolean(false)
        val operationA = NativeToolChatOperation(
            onCancel = { cancellationRequested.set(true) },
            executeBlock = {
                started.countDown()
                while (!cancellationRequested.get()) Thread.yield()
                releaseCleanup.await(5, TimeUnit.SECONDS)
                "late A"
            },
            laneGuard = guard,
        )
        val requestA = coordinator.begin("poison-a", "assistant-a") {}!!
        assertTrue(
            coordinator.attachNetwork(
                request = requestA,
                cancel = { operationA.cancel() },
                awaitNativeUnwind = operationA::awaitCompletion,
            ),
        )
        val worker = thread(name = "native-unverifiable-a", isDaemon = true) {
            assertTrue(operationA.claimStart())
            operationA.executeClaimed()
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val stopFailure = runCatching {
            coordinator.stopActive { terminalPersisted.set(true) }
        }.exceptionOrNull()

        assertTrue(stopFailure?.message.orEmpty().contains("cleanup could not be verified"))
        assertTrue(terminalPersisted.get())
        assertTrue(guard.poisonDetailForTest().orEmpty().contains("did not finish"))
        val operationB = NativeToolChatOperation(
            onCancel = {},
            executeBlock = { "must not run" },
            laneGuard = guard,
        )
        val bFailure = runCatching { operationB.claimStart() }.exceptionOrNull()
        assertTrue(bFailure?.message.orEmpty().contains("will not start another native tool operation"))

        releaseCleanup.countDown()
        worker.join(5_000L)
        assertFalse(worker.isAlive)
    }

    private fun Thread.joinAndReport(timeoutMs: Long): Boolean {
        join(timeoutMs)
        return !isAlive
    }
}
