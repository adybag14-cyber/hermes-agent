package com.mobilefork.hermesagent.device

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HermesLinuxSandboxMutationGateRobotest {
    private lateinit var context: Context
    private lateinit var stateFile: File
    private lateinit var controlFile: File
    private lateinit var testPrefix: File
    private var originalState: ByteArray? = null
    private var originalControl: ByteArray? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        stateFile = File(context.filesDir, "hermes-home/linux/linux-subsystem-state.json")
        controlFile = context.getExternalFilesDir(null)
            ?.let { File(it, "hermes-home/hermes-agent-shell-control.json") }
            ?: File(context.filesDir, "hermes-home/hermes-agent-shell-control.json")
        testPrefix = File(context.filesDir, "request-owned-sandbox-gate-test")
        originalState = stateFile.takeIf { it.isFile }?.readBytes()
        originalControl = controlFile.takeIf { it.isFile }?.readBytes()
        stateFile.delete()
        controlFile.delete()
        testPrefix.deleteRecursively()
    }

    @After
    fun tearDown() {
        testPrefix.deleteRecursively()
        restoreFile(stateFile, originalState)
        restoreFile(controlFile, originalControl)
    }

    @Test
    fun requestOwnedStatusReadsSnapshotOnlyAndNeverInitializesRuntime() {
        assertFalse(stateFile.exists())

        val result = HermesLinuxSandboxBridge.performAction(
            context = context,
            action = "status",
            publicationGate = acceptingGate(),
        )

        assertEquals(0, result.optInt("exit_code", -1))
        assertEquals("embedded_sandbox_runtime_not_initialized", result.optString("status"))
        assertFalse(result.optBoolean("runtime_state_available", true))
        assertTrue(result.optBoolean("request_owned", false))
        assertFalse("chat status initialized or repaired the host runtime", stateFile.exists())
    }

    @Test
    fun requestOwnedGuestProcessActionsFailClosedBeforeRuntimeOrLayerMutation() {
        val unsafeActions = listOf(
            "install",
            "deploy",
            "update",
            "set_mirror",
            "run",
            "uninstall",
        )

        unsafeActions.forEach { action ->
            val result = HermesLinuxSandboxBridge.performAction(
                context = context,
                action = action,
                distroId = "alpine-3-21",
                command = "touch must-not-run",
                publicationGate = acceptingGate(),
            )
            assertEquals(action, 126, result.optInt("exit_code", -1))
            assertTrue(action, result.optBoolean("request_owned_operation_blocked", false))
            assertEquals(action, "request_owned_proot_blocked", result.optString("sandbox_execution_mode"))
        }

        assertFalse("a blocked guest action initialized the runtime", stateFile.exists())
        assertFalse("a blocked guest action created a layer/container prefix", testPrefix.exists())
    }

    @Test
    fun requestABlockedAtControlCommitThenStoppedCannotOverwriteIndependentB() {
        writeRuntimeSnapshot("sandbox-a", "sandbox-b")
        val aAtCommit = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val aCancelled = AtomicBoolean(false)
        val aFailure = AtomicReference<Throwable?>()
        val workerA = thread(name = "sandbox-control-request-a") {
            try {
                HermesLinuxSandboxBridge.performAction(
                    context = context,
                    action = "start",
                    name = "sandbox-a",
                    cancellationRequested = { aCancelled.get() },
                    publicationGate = AutomationPublicationGate { publication ->
                        aAtCommit.countDown()
                        check(releaseA.await(5, TimeUnit.SECONDS))
                        if (aCancelled.get()) {
                            false
                        } else {
                            publication()
                            true
                        }
                    },
                )
            } catch (error: Throwable) {
                aFailure.set(error)
            }
        }

        assertTrue("request A never reached its bounded commit", aAtCommit.await(5, TimeUnit.SECONDS))
        val resultB = HermesLinuxSandboxBridge.performAction(
            context = context,
            action = "start",
            name = "sandbox-b",
            publicationGate = acceptingGate(),
        )
        assertEquals(0, resultB.optInt("exit_code", -1))
        assertEquals("sandbox-b", JSONObject(controlFile.readText()).optString("active_sandbox_name"))

        aCancelled.set(true)
        releaseA.countDown()
        workerA.join(5_000)

        assertFalse("request A did not unwind", workerA.isAlive)
        assertNotNull("request A unexpectedly published", aFailure.get())
        assertTrue(aFailure.get() is CancellationException)
        assertEquals(
            "cancelled request A overwrote request B",
            "sandbox-b",
            JSONObject(controlFile.readText()).optString("active_sandbox_name"),
        )
        assertTrue(
            "a staged control file escaped request cleanup",
            controlFile.parentFile?.listFiles().orEmpty().none { it.name.endsWith(".tmp") },
        )
    }

    @Test
    fun requestOwnedLogcatScanNeverReachesPrivilegedDispatchAndManualBRemainsIndependent() {
        val privilegedDispatches = AtomicInteger(0)
        val cancelled = AtomicBoolean(false)
        val runner: (Context, String, Int) -> String = { _, _, _ ->
            privilegedDispatches.incrementAndGet()
            JSONObject().put("success", true).put("output", "manual-b").toString()
        }
        val requestGate = AutomationPublicationGate {
            error("request-owned logcat must fail before any publication or privileged dispatch")
        }

        val blocked = JSONObject(
            HermesLogcatWatcherBridge.scanOnceJson(
                context = context,
                cancellationRequested = { cancelled.get() },
                publicationGate = requestGate,
                privilegedShellRunner = runner,
            ),
        )
        assertTrue(blocked.optBoolean("request_owned_privileged_dispatch_blocked", false))
        assertEquals(0, privilegedDispatches.get())

        cancelled.set(true)
        var stoppedFailure: Throwable? = null
        try {
            HermesLogcatWatcherBridge.scanOnceJson(
                context = context,
                cancellationRequested = { cancelled.get() },
                publicationGate = requestGate,
                privilegedShellRunner = runner,
            )
        } catch (error: Throwable) {
            stoppedFailure = error
        }
        assertTrue(stoppedFailure is CancellationException)
        assertEquals("Stop allowed request A to dispatch Shizuku shell", 0, privilegedDispatches.get())

        val manualB = JSONObject(
            HermesLogcatWatcherBridge.dispatchPrivilegedLogcatShellJson(
                context = context,
                command = "logcat -d -t 10",
                timeoutSeconds = 15,
                publicationGate = null,
                privilegedShellRunner = runner,
            ),
        )
        assertTrue(manualB.optBoolean("success", false))
        assertEquals("request A interfered with independent manual B", 1, privilegedDispatches.get())
    }

    private fun writeRuntimeSnapshot(vararg sandboxNames: String) {
        sandboxNames.forEach { name ->
            File(testPrefix, "var/lib/proot-distro/containers/$name/rootfs").mkdirs()
        }
        controlFile.parentFile?.mkdirs()
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(
            JSONObject()
                .put("execution_mode", "embedded_termux")
                .put("uses_termux", true)
                .put("android_abi", "arm64-v8a")
                .put("termux_arch", "aarch64")
                .put("prefix_path", testPrefix.absolutePath)
                .put(
                    "packages",
                    JSONArray()
                        .put(JSONObject().put("name", "proot"))
                        .put(JSONObject().put("name", "proot-distro")),
                )
                .toString(),
        )
    }

    private fun acceptingGate(): AutomationPublicationGate {
        return AutomationPublicationGate { publication ->
            publication()
            true
        }
    }

    private fun restoreFile(file: File, content: ByteArray?) {
        if (content == null) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        file.writeBytes(content)
    }
}
