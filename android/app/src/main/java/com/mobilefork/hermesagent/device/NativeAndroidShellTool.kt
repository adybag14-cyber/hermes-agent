package com.mobilefork.hermesagent.device

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

internal interface NativeShellProcessStopHandle {
    val supportsForceDestroy: Boolean
    fun exitValue(): Int
    fun destroy()
    fun forceDestroy()
}

object NativeAndroidShellTool {
    internal data class BoundedStreamRead(
        val text: String,
        val completed: Boolean,
        val interrupted: Boolean = false,
    )

    internal data class ProcessTerminationResult(
        val failure: Throwable?,
        val interrupted: Boolean,
    )

    internal data class ProcessInventorySnapshot(
        val sameUidPids: Set<Int>,
        val ownerMarkedPids: Set<Int>,
        val unreadableSameUidPids: Set<Int> = emptySet(),
        val failure: Throwable? = null,
    )

    internal interface OwnedProcessInventory {
        fun snapshot(ownerToken: String): ProcessInventorySnapshot
        fun signalIfOwned(pid: Int, ownerToken: String, force: Boolean): Throwable?
    }

    internal data class DetachedProcessContainmentResult(
        val verified: Boolean,
        val detectedOwnedPids: Set<Int>,
        val terminatedOwnedPids: Set<Int>,
        val remainingOwnedPids: Set<Int>,
        val ambiguousNewSameUidPids: Set<Int>,
        val failure: Throwable?,
        val interrupted: Boolean,
    )

    internal data class PostStartFailureCleanupResult(
        val verified: Boolean,
        val termination: ProcessTerminationResult,
        val containment: DetachedProcessContainmentResult,
        val interrupted: Boolean,
    )

    internal data class NativeShellLifecycleDisposition(
        val cleanCancellation: Boolean,
        val unsafe: Boolean,
    )

    @Volatile
    private var unsafeExecutionDetail: String = ""
    private val executionLock = ReentrantLock(true)

    private const val PROCESS_POLL_INTERVAL_MS = 10L
    internal const val PROCESS_OWNER_ENV = "HERMES_NATIVE_EXECUTION_OWNER"
    private const val MAX_PROC_ENVIRON_BYTES = 262_144
    private const val DETACHED_PROCESS_GRACEFUL_TIMEOUT_MS = 500L
    private const val DETACHED_PROCESS_FORCE_TIMEOUT_MS = 500L

    fun run(
        context: Context,
        command: String,
        timeoutSeconds: Long = 60,
        includeLinuxSandboxStatus: Boolean = true,
        packageHttpClient: OkHttpClient? = null,
        cancellationRequested: () -> Boolean = { false },
    ): JSONObject {
        var acquired = false
        try {
            executionLock.lockInterruptibly()
            acquired = true
            return runLocked(
                context = context,
                command = command,
                timeoutSeconds = timeoutSeconds,
                includeLinuxSandboxStatus = includeLinuxSandboxStatus,
                packageHttpClient = packageHttpClient,
                cancellationRequested = cancellationRequested,
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            if (cancellationRequested()) {
                throw CancellationException("Native shell command was stopped while waiting for the execution lane")
                    .apply { initCause(error) }
            }
            throw error
        } finally {
            if (acquired) executionLock.unlock()
        }
    }

    private fun runLocked(
        context: Context,
        command: String,
        timeoutSeconds: Long,
        includeLinuxSandboxStatus: Boolean,
        packageHttpClient: OkHttpClient?,
        cancellationRequested: () -> Boolean,
    ): JSONObject {
        val appContext = context.applicationContext
        if (cancellationRequested()) {
            throw CancellationException("Native shell command was stopped before setup")
        }
        if (unsafeExecutionDetail.isNotBlank()) {
            return JSONObject()
                .put("exit_code", 125)
                .put("output", "")
                .put("error", unsafeExecutionDetail)
                .put("execution_mode", "blocked_unsafe_previous_execution")
                .put("requires_app_restart", true)
                .put("process_unwind_verified", false)
        }
        // Route Termux-style host package manager before spawning a shell.
        if (HermesTermuxPackageManager.isPkgCommand(command)) {
            val pkgResult = HermesTermuxPackageManager.performCliCommand(
                context = appContext,
                commandLine = command,
                httpClient = packageHttpClient,
            )
            val state = HermesLinuxSubsystemBridge.ensureInstalled(appContext)
            val message = pkgResult.optString("message")
                .ifBlank { pkgResult.optString("error") }
                .ifBlank { pkgResult.toString() }
            val result = JSONObject()
                .put("exit_code", pkgResult.optInt("exit_code", if (pkgResult.optBoolean("ok", false)) 0 else 1))
                .put("output", message + "\n" + pkgResult.toString(2))
                .put("error", if (pkgResult.optBoolean("ok", false)) "" else pkgResult.optString("error"))
                .put("cwd", state.optString("home_path"))
                .put("shell", "hermes-pkg")
                .put("execution_mode", "host_pkg_manager")
                .put("uses_termux", state.optBoolean("uses_termux", false))
                .put("host_pkg_result", pkgResult)
                .put(
                    "package_manager_status",
                    "hermes_host_pkg",
                )
                .put(
                    "package_management_hint",
                    "Host suite packages use Hermes pkg (Termux main mirrors). " +
                        "Guest sandboxes use linux_sandbox_tool action=update (apt/apk).",
                )
            if (includeLinuxSandboxStatus) {
                result
                    .put("downloadable_linux_sandboxes", HermesLinuxSandboxCatalog.distroCatalog())
                    .put("recommended_linux_sandboxes", HermesLinuxSandboxCatalog.recommendedSandboxIds())
                    .put("desktop_environment_catalog", HermesLinuxSandboxCatalog.desktopCatalog())
                    .put("linux_sandbox_agent_summary", HermesLinuxSandboxCatalog.agentSummary())
                    .put("linux_sandbox_status", HermesLinuxSandboxBridge.status(state))
            }
            return result
        }

        val state = HermesLinuxSubsystemBridge.ensureInstalled(appContext)
        if (cancellationRequested()) {
            throw CancellationException("Native shell command was stopped before launch")
        }
        val homeDir = File(state.getString("home_path")).apply { mkdirs() }
        val tmpDir = File(state.getString("tmp_path")).apply { mkdirs() }
        val shellPath = resolveShellPath(state)
        val effectiveCommand = HermesLinuxSubsystemBridge.commandWithEmbeddedToolAliases(state, command)
        val environment = HermesLinuxSubsystemBridge.buildRunEnvironment(state).toMutableMap().apply {
            this["HOME"] = homeDir.absolutePath
            this["TMPDIR"] = tmpDir.absolutePath
            this["PATH"] = listOf(
                state.optString("bin_path"),
                "/system/bin",
                "/system/xbin",
            )
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(":")
        }
        val executionOwnerToken = newProcessOwnerToken()
        val ownedProcessInventory = ownedProcessInventory()
        val processInventoryBaseline = ownedProcessInventory.snapshot(executionOwnerToken)
        val inventoryPreflightFailure = processInventoryPreflightFailure(processInventoryBaseline)
        if (inventoryPreflightFailure != null) {
            val detail = inventoryPreflightFailure.message
                ?: inventoryPreflightFailure.javaClass.simpleName
            return JSONObject()
                .put("exit_code", 125)
                .put("output", "")
                .put(
                    "error",
                    "Hermes could not establish a safe same-UID process baseline ($detail). " +
                        "No native shell command was started.",
                )
                .put("cwd", homeDir.absolutePath)
                .put("shell", shellPath)
                .put("execution_mode", state.optString("execution_mode"))
                .put("uses_termux", state.optBoolean("uses_termux", false))
                .put("native_execution_route", state.optString("native_execution_route"))
                .put("execution_launch_failed", true)
                .put("process_inventory_available", false)
                .put("process_unwind_verified", false)
                .put("detached_process_cleanup_verified", false)
                .put("requires_app_restart", false)
        }
        environment[PROCESS_OWNER_ENV] = executionOwnerToken

        var process: Process? = null
        var processHandle: NativeShellProcessStopHandle? = null
        var launchFailure: Throwable? = null
        var completed = false
        var callerInterrupted = false
        var waitFailure: Throwable? = null
        var lifecycleFailure: Throwable? = null
        var termination = ProcessTerminationResult(failure = null, interrupted = false)
        var parentCleanupAttempted = false
        var detachedContainment = DetachedProcessContainmentResult(
            verified = false,
            detectedOwnedPids = emptySet(),
            terminatedOwnedPids = emptySet(),
            remainingOwnedPids = emptySet(),
            ambiguousNewSameUidPids = emptySet(),
            failure = IllegalStateException("detached native shell process containment did not run"),
            interrupted = false,
        )
        var containmentAttempted = false
        var executor: ExecutorService? = null
        var stdout: Future<String>? = null
        var stderr: Future<String>? = null
        var stdoutRead = BoundedStreamRead("", completed = false)
        var stderrRead = BoundedStreamRead("", completed = false)
        var readerExecutorStopped = true
        var processInputClosed = false
        var processErrorClosed = false
        var processOutputClosed = false
        try {
            withNativeShellProcessOwnership(
                start = {
                    if (cancellationRequested()) {
                        throw CancellationException("Native shell command was stopped before launch")
                    }
                    ProcessBuilder(shellInvocation(shellPath, effectiveCommand))
                        .directory(homeDir)
                        .apply {
                            environment().putAll(environment)
                        }
                        .start()
                },
                action = { startedProcess, startedHandle ->
                    process = startedProcess
                    processHandle = startedHandle
                    val readerExecutor = Executors.newFixedThreadPool(2)
                    executor = readerExecutor
                    stdout = readerExecutor.submit(Callable {
                        startedProcess.inputStream.bufferedReader().use { it.readText() }
                    })
                    stderr = readerExecutor.submit(Callable {
                        startedProcess.errorStream.bufferedReader().use { it.readText() }
                    })
                    completed = try {
                        waitForProcessExitInterruptibly(
                            current = startedHandle,
                            timeoutMs = TimeUnit.SECONDS.toMillis(timeoutSeconds.coerceAtLeast(0L)),
                        )
                    } catch (error: Throwable) {
                        waitFailure = error
                        if (error is InterruptedException) {
                            callerInterrupted = true
                        }
                        false
                    }
                    if (!completed) {
                        termination = terminateOwnedProcess(startedHandle)
                        parentCleanupAttempted = true
                        callerInterrupted = callerInterrupted || termination.interrupted
                    }
                    detachedContainment = containDetachedOwnedProcesses(
                        baseline = processInventoryBaseline,
                        ownerToken = executionOwnerToken,
                        inventory = ownedProcessInventory,
                    )
                    containmentAttempted = true
                    callerInterrupted = callerInterrupted || detachedContainment.interrupted
                    stdoutRead = readStreamWithin(requireNotNull(stdout))
                    stderrRead = readStreamWithin(requireNotNull(stderr))
                    callerInterrupted = callerInterrupted || stdoutRead.interrupted || stderrRead.interrupted
                },
                cleanup = { startedProcess, startedHandle ->
                    if (!parentCleanupAttempted && !containmentAttempted && !completed) {
                        val emergencyCleanup = cleanupAfterPostStartFailure(
                            current = startedHandle,
                            baseline = processInventoryBaseline,
                            ownerToken = executionOwnerToken,
                            inventory = ownedProcessInventory,
                        )
                        termination = emergencyCleanup.termination
                        detachedContainment = emergencyCleanup.containment
                        parentCleanupAttempted = true
                        containmentAttempted = true
                        callerInterrupted = callerInterrupted || emergencyCleanup.interrupted
                    } else if (!parentCleanupAttempted && !completed) {
                        termination = runCatching { terminateOwnedProcess(startedHandle) }.getOrElse { error ->
                            ProcessTerminationResult(failure = error, interrupted = false)
                        }
                        parentCleanupAttempted = true
                        callerInterrupted = callerInterrupted || termination.interrupted
                    }
                    if (!containmentAttempted) {
                        detachedContainment = runCatching {
                            containDetachedOwnedProcesses(
                                baseline = processInventoryBaseline,
                                ownerToken = executionOwnerToken,
                                inventory = ownedProcessInventory,
                            )
                        }.getOrElse { error ->
                            DetachedProcessContainmentResult(
                                verified = false,
                                detectedOwnedPids = emptySet(),
                                terminatedOwnedPids = emptySet(),
                                remainingOwnedPids = emptySet(),
                                ambiguousNewSameUidPids = emptySet(),
                                failure = error,
                                interrupted = false,
                            )
                        }
                        containmentAttempted = true
                        callerInterrupted = callerInterrupted || detachedContainment.interrupted
                    }
                    stdout?.cancel(true)
                    stderr?.cancel(true)
                    processInputClosed = runCatching { startedProcess.inputStream.close() }.isSuccess
                    processErrorClosed = runCatching { startedProcess.errorStream.close() }.isSuccess
                    processOutputClosed = runCatching { startedProcess.outputStream.close() }.isSuccess
                    val activeExecutor = executor
                    if (activeExecutor != null) {
                        activeExecutor.shutdownNow()
                        readerExecutorStopped = try {
                            activeExecutor.awaitTermination(1, TimeUnit.SECONDS)
                        } catch (_: InterruptedException) {
                            callerInterrupted = true
                            false
                        } catch (_: Throwable) {
                            false
                        }
                    }
                },
            )
        } catch (error: Throwable) {
            if (process == null) {
                launchFailure = error
            } else {
                val existingFailure = lifecycleFailure
                lifecycleFailure = if (existingFailure == null) {
                    error
                } else {
                    existingFailure.apply {
                        if (error !== this) addSuppressed(error)
                    }
                }
                if (error is InterruptedException) {
                    callerInterrupted = true
                }
            }
        }
        restoreInterruptAfterOwnedCleanup(callerInterrupted)

        val observedLaunchFailure = launchFailure
        if (observedLaunchFailure != null) {
            val detail = observedLaunchFailure.message ?: observedLaunchFailure.javaClass.simpleName
            val permissionDenied = detail.contains("permission denied", ignoreCase = true) ||
                detail.contains("EACCES", ignoreCase = true)
            val exitCode = if (permissionDenied) 126 else 1
            val result = JSONObject()
                .put("exit_code", exitCode)
                .put("output", "")
                .put(
                    "error",
                    if (permissionDenied) {
                        "$detail\n${executionDeniedHint(state, command)}"
                    } else {
                        detail
                    },
                )
                .put("cwd", homeDir.absolutePath)
                .put("shell", shellPath)
                .put("execution_mode", state.optString("execution_mode"))
                .put("uses_termux", state.optBoolean("uses_termux", false))
                .put("native_execution_route", state.optString("native_execution_route"))
                .put("execution_launch_failed", true)
            if (permissionDenied) {
                result.put("execution_denial_hint", executionDeniedHint(state, command))
            }
            return result
        }
        val startedProcess = requireNotNull(process) { "native shell process was not retained after launch" }
        val startedHandle = requireNotNull(processHandle) { "native shell process handle was not retained after launch" }

        val unwindVerified = termination.failure == null &&
            runCatching { !isOwnedProcessAlive(startedHandle) }.getOrDefault(false) &&
            detachedContainment.verified
        val outputCaptureVerified = stdoutRead.completed && stderrRead.completed
        val streamCleanupVerified = processInputClosed &&
            processErrorClosed &&
            processOutputClosed &&
            readerExecutorStopped
        val expectedCancellation = callerInterrupted &&
            cancellationRequested() &&
            (waitFailure == null || waitFailure is InterruptedException)
        // A deadline is clean only when no interruption was observed anywhere in wait/cleanup.
        // Otherwise an unrelated interrupt could be mislabeled as a verified timeout.
        val timedOut = !completed &&
            waitFailure == null &&
            lifecycleFailure == null &&
            !callerInterrupted
        val lifecycleDisposition = nativeShellLifecycleDisposition(
            expectedCancellation = expectedCancellation,
            completed = completed,
            lifecycleFailure = lifecycleFailure,
            unwindVerified = unwindVerified,
            outputCaptureVerified = outputCaptureVerified,
            streamCleanupVerified = streamCleanupVerified,
            timedOut = timedOut,
        )
        val cleanCancellation = lifecycleDisposition.cleanCancellation
        val unsafe = lifecycleDisposition.unsafe
        if (unsafe) {
            val reason = listOfNotNull(
                waitFailure?.let { "wait failed: ${it.message ?: it.javaClass.simpleName}" },
                lifecycleFailure?.let { "lifecycle failed: ${it.message ?: it.javaClass.simpleName}" },
                termination.failure?.let { "termination failed: ${it.message ?: it.javaClass.simpleName}" },
                detachedContainment.failure?.let {
                    "detached process containment failed: ${it.message ?: it.javaClass.simpleName}"
                },
                "stdout reader remained open".takeIf { !stdoutRead.completed },
                "stderr reader remained open".takeIf { !stderrRead.completed },
                "stream reader executor remained alive".takeIf { !readerExecutorStopped },
            ).joinToString("; ").ifBlank { "the command exceeded its ${timeoutSeconds}s deadline" }
            unsafeExecutionDetail =
                "A previous native shell command did not unwind safely ($reason). " +
                    "Hermes will not start another command because PRoot/QEMU descendants cannot be excluded. " +
                    "Force stop and reopen Hermes before retrying."
        }
        val detachedProcessRejected = detachedContainment.detectedOwnedPids.isNotEmpty()
        val exitCode = nativeShellExitCode(
            cancelled = cleanCancellation,
            timedOut = timedOut,
            cleanupUnsafe = unsafe,
            detachedProcessDetected = detachedProcessRejected,
            processExitCode = if (!unsafe && !cleanCancellation && completed) startedProcess.exitValue() else null,
        )
        val output = stdoutRead.text
        val detachedRejectionDetail = if (detachedProcessRejected) {
            "Detached/background native shell processes are unsupported; Hermes stopped the owned process(es) " +
                "before returning: ${detachedContainment.detectedOwnedPids.joinToString(",")}"
        } else {
            ""
        }
        val error = when {
            cleanCancellation -> listOf(stderrRead.text.trim(), "Native shell command was cancelled.")
                .filter { it.isNotBlank() }
                .joinToString("\n")
            unsafe || detachedProcessRejected -> {
                listOf(stderrRead.text.trim(), detachedRejectionDetail, unsafeExecutionDetail)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
            }
            else -> stderrRead.text
        }

        val result = JSONObject()
            .put("exit_code", exitCode)
            .put("output", output)
            .put("error", error)
            .put("cwd", homeDir.absolutePath)
            .put("shell", shellPath)
            .put("execution_mode", state.optString("execution_mode"))
            .put("uses_termux", state.optBoolean("uses_termux", false))
            .put("native_execution_route", state.optString("native_execution_route"))
            .put("native_direct_command_count", state.optInt("native_direct_command_count", 0))
            .put("available_package_count", state.optJSONArray("packages")?.length() ?: 0)
            .put("cancelled", cleanCancellation)
            .put("timed_out", timedOut)
            .put("process_unwind_verified", unwindVerified)
            .put("detached_process_cleanup_verified", detachedContainment.verified)
            .put("detached_process_detected_count", detachedContainment.detectedOwnedPids.size)
            .put("detached_process_terminated_count", detachedContainment.terminatedOwnedPids.size)
            .put("detached_process_remaining_count", detachedContainment.remainingOwnedPids.size)
            .put("detached_process_rejected", detachedProcessRejected)
            .put(
                "ambiguous_new_same_uid_process_count",
                detachedContainment.ambiguousNewSameUidPids.size,
            )
            .put(
                "stream_cleanup_verified",
                streamCleanupVerified,
            )
            .put("output_capture_verified", outputCaptureVerified)
            .put("requires_app_restart", unsafe)
            .put(
                "package_manager_status",
                if (state.optBoolean("uses_termux", false)) "embedded_prefix_packages_available" else "android_system_shell_fallback",
            )
            .put(
                "package_management_hint",
                if (state.optBoolean("uses_termux", false)) {
                    "Host suite: use pkg list/search for discovery; package changes require a signed Hermes APK. " +
                        "Guest sandboxes: linux_sandbox_tool action=update (apt/apk). " +
                        "Packaged prefix commands are on PATH; proot-distro catalog is in downloadable_linux_sandboxes."
                } else {
                    "Embedded package prefix is unavailable; this run used Android's system shell only."
                },
            )
        if (exitCode == 126) {
            val hint = executionDeniedHint(state, command)
            result.put("error", listOf(error.trim(), hint).filter { it.isNotBlank() }.joinToString("\n"))
            result.put("execution_denial_hint", hint)
            result.put("android_exec_policy", state.optString("android_exec_policy"))
        }
        if (includeLinuxSandboxStatus) {
            result
                .put("downloadable_linux_sandboxes", HermesLinuxSandboxCatalog.distroCatalog())
                .put("recommended_linux_sandboxes", HermesLinuxSandboxCatalog.recommendedSandboxIds())
                .put("desktop_environment_catalog", HermesLinuxSandboxCatalog.desktopCatalog())
                .put("linux_sandbox_agent_summary", HermesLinuxSandboxCatalog.agentSummary())
                .put("linux_sandbox_status", HermesLinuxSandboxBridge.status(state))
        }
        return result
    }

    internal fun newProcessOwnerToken(): String = UUID.randomUUID().toString()

    internal fun nativeShellExitCode(
        cancelled: Boolean,
        timedOut: Boolean,
        cleanupUnsafe: Boolean,
        detachedProcessDetected: Boolean,
        processExitCode: Int?,
    ): Int {
        return when {
            cleanupUnsafe -> 125
            detachedProcessDetected -> 125
            cancelled -> 130
            timedOut -> 124
            else -> requireNotNull(processExitCode) { "completed native shell requires an exit code" }
        }
    }

    internal fun nativeShellLifecycleDisposition(
        expectedCancellation: Boolean,
        completed: Boolean,
        lifecycleFailure: Throwable?,
        unwindVerified: Boolean,
        outputCaptureVerified: Boolean,
        streamCleanupVerified: Boolean,
        timedOut: Boolean = false,
    ): NativeShellLifecycleDisposition {
        val cleanCancellation = expectedCancellation &&
            lifecycleFailure == null &&
            unwindVerified &&
            streamCleanupVerified
        val cleanTimeout = timedOut &&
            !expectedCancellation &&
            lifecycleFailure == null &&
            unwindVerified &&
            outputCaptureVerified &&
            streamCleanupVerified
        val unsafe = !cleanCancellation && !cleanTimeout && (
            lifecycleFailure != null ||
                !completed ||
                !unwindVerified ||
                !outputCaptureVerified ||
                !streamCleanupVerified
            )
        return NativeShellLifecycleDisposition(
            cleanCancellation = cleanCancellation,
            unsafe = unsafe,
        )
    }

    internal fun <T> withExecutionPermitForTest(block: () -> T): T {
        executionLock.lockInterruptibly()
        return try {
            block()
        } finally {
            executionLock.unlock()
        }
    }

    internal fun isExecutionThreadQueuedForTest(thread: Thread): Boolean =
        executionLock.hasQueuedThread(thread)

    internal fun ownedProcessInventory(): OwnedProcessInventory = ProcfsOwnedProcessInventory

    internal fun processInventoryPreflightFailure(
        baseline: ProcessInventorySnapshot,
    ): Throwable? {
        return baseline.failure
            ?: baseline.ownerMarkedPids.takeIf { it.isNotEmpty() }?.let { collision ->
                IllegalStateException(
                    "native shell owner token unexpectedly matched existing PIDs " +
                        collision.sorted().joinToString(","),
                )
            }
    }

    internal fun cleanupAfterPostStartFailure(
        current: NativeShellProcessStopHandle,
        baseline: ProcessInventorySnapshot,
        ownerToken: String,
        inventory: OwnedProcessInventory,
        gracefulTimeoutMs: Long = 1_000L,
        forcedTimeoutMs: Long = 1_000L,
        detachedGracefulTimeoutMs: Long = DETACHED_PROCESS_GRACEFUL_TIMEOUT_MS,
        detachedForcedTimeoutMs: Long = DETACHED_PROCESS_FORCE_TIMEOUT_MS,
    ): PostStartFailureCleanupResult {
        val termination = runCatching {
            terminateOwnedProcess(
                current = current,
                gracefulTimeoutMs = gracefulTimeoutMs,
                forcedTimeoutMs = forcedTimeoutMs,
            )
        }.getOrElse { error ->
            ProcessTerminationResult(failure = error, interrupted = false)
        }
        val containment = runCatching {
            containDetachedOwnedProcesses(
                baseline = baseline,
                ownerToken = ownerToken,
                inventory = inventory,
                gracefulTimeoutMs = detachedGracefulTimeoutMs,
                forcedTimeoutMs = detachedForcedTimeoutMs,
            )
        }.getOrElse { error ->
            DetachedProcessContainmentResult(
                verified = false,
                detectedOwnedPids = emptySet(),
                terminatedOwnedPids = emptySet(),
                remainingOwnedPids = emptySet(),
                ambiguousNewSameUidPids = emptySet(),
                failure = error,
                interrupted = false,
            )
        }
        return PostStartFailureCleanupResult(
            verified = termination.failure == null && containment.verified,
            termination = termination,
            containment = containment,
            interrupted = termination.interrupted || containment.interrupted,
        )
    }

    internal fun containDetachedOwnedProcesses(
        baseline: ProcessInventorySnapshot,
        ownerToken: String,
        inventory: OwnedProcessInventory,
        gracefulTimeoutMs: Long = DETACHED_PROCESS_GRACEFUL_TIMEOUT_MS,
        forcedTimeoutMs: Long = DETACHED_PROCESS_FORCE_TIMEOUT_MS,
    ): DetachedProcessContainmentResult {
        var interrupted = Thread.interrupted()
        val failures = mutableListOf<Throwable>()
        baseline.failure?.let(failures::add)
        val detectedOwnedPids = mutableSetOf<Int>()

        fun capture(): ProcessInventorySnapshot {
            val snapshot = runCatching { inventory.snapshot(ownerToken) }.getOrElse { error ->
                ProcessInventorySnapshot(
                    sameUidPids = emptySet(),
                    ownerMarkedPids = emptySet(),
                    failure = error,
                )
            }
            detectedOwnedPids += snapshot.ownerMarkedPids
            snapshot.failure?.let(failures::add)
            return snapshot
        }

        fun cleanupPhase(force: Boolean, timeoutMs: Long): ProcessInventorySnapshot {
            val signaledPids = mutableSetOf<Int>()
            val deadlineNanos = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
            var current = capture()
            while (true) {
                (current.ownerMarkedPids - signaledPids).sorted().forEach { pid ->
                    inventory.signalIfOwned(pid, ownerToken, force)?.let(failures::add)
                    signaledPids += pid
                }
                current = capture()
                if (current.ownerMarkedPids.isEmpty()) return current
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) return current
                try {
                    Thread.sleep(pollSleepMillis(remainingNanos))
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }

        cleanupPhase(force = false, timeoutMs = gracefulTimeoutMs)
        cleanupPhase(force = true, timeoutMs = forcedTimeoutMs)
        val finalSnapshot = capture()
        val remainingOwnedPids = finalSnapshot.ownerMarkedPids.toSortedSet()
        val ambiguousNewSameUidPids = (
            finalSnapshot.sameUidPids -
                baseline.sameUidPids -
                finalSnapshot.ownerMarkedPids
            ).toSortedSet()
        if (remainingOwnedPids.isNotEmpty()) {
            failures += IllegalStateException(
                "owner-marked native shell PIDs remained alive after cleanup: " +
                    remainingOwnedPids.joinToString(","),
            )
        }
        if (ambiguousNewSameUidPids.isNotEmpty()) {
            failures += IllegalStateException(
                "new same-UID PIDs survived without a verifiable owner marker: " +
                    ambiguousNewSameUidPids.joinToString(","),
            )
        }
        val failure = failures.reduceOrNull { primary, next ->
            primary.apply { if (next !== primary) addSuppressed(next) }
        }
        return DetachedProcessContainmentResult(
            verified = failure == null,
            detectedOwnedPids = detectedOwnedPids.toSortedSet(),
            terminatedOwnedPids = (detectedOwnedPids - remainingOwnedPids).toSortedSet(),
            remainingOwnedPids = remainingOwnedPids,
            ambiguousNewSameUidPids = ambiguousNewSameUidPids,
            failure = failure,
            interrupted = interrupted,
        )
    }

    internal fun terminateOwnedProcess(
        current: NativeShellProcessStopHandle,
        gracefulTimeoutMs: Long = 1_000L,
        forcedTimeoutMs: Long = 1_000L,
    ): ProcessTerminationResult {
        var interrupted = Thread.interrupted()
        val initiallyAlive = runCatching { isOwnedProcessAlive(current) }.getOrElse { error ->
            return ProcessTerminationResult(failure = error, interrupted = interrupted)
        }
        if (!initiallyAlive) {
            return ProcessTerminationResult(failure = null, interrupted = interrupted)
        }
        val failures = mutableListOf<Throwable>()
        runCatching { current.destroy() }.exceptionOrNull()?.let(failures::add)
        val graceful = waitForOwnedProcessWithoutRestoringInterrupt(current, gracefulTimeoutMs)
        interrupted = interrupted || graceful.interrupted
        graceful.failure?.let(failures::add)
        val aliveAfterGrace = runCatching { isOwnedProcessAlive(current) }.getOrElse { error ->
            failures += error
            true
        }
        if (!graceful.exited || aliveAfterGrace) {
            if (!current.supportsForceDestroy) {
                failures += IllegalStateException(
                    "native shell process remained alive after graceful termination; " +
                        "forced termination requires Android 8.0 (API 26)",
                )
            } else {
                runCatching { current.forceDestroy() }.exceptionOrNull()?.let(failures::add)
                val forced = waitForOwnedProcessWithoutRestoringInterrupt(current, forcedTimeoutMs)
                interrupted = interrupted || forced.interrupted
                forced.failure?.let(failures::add)
                val aliveAfterForce = runCatching { isOwnedProcessAlive(current) }.getOrElse { error ->
                    failures += error
                    true
                }
                if (!forced.exited || aliveAfterForce) {
                    failures += IllegalStateException("native shell process remained alive after forced termination")
                }
            }
        }
        return ProcessTerminationResult(
            failure = failures.reduceOrNull { primary, next ->
                primary.apply { addSuppressed(next) }
            },
            interrupted = interrupted,
        )
    }

    private data class OwnedProcessWaitResult(
        val exited: Boolean,
        val interrupted: Boolean,
        val failure: Throwable?,
    )

    private fun waitForOwnedProcessWithoutRestoringInterrupt(
        current: NativeShellProcessStopHandle,
        timeoutMs: Long,
    ): OwnedProcessWaitResult {
        var interrupted = Thread.interrupted()
        val boundedTimeoutMs = timeoutMs.coerceAtLeast(0L)
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundedTimeoutMs)
        while (true) {
            try {
                if (!isOwnedProcessAlive(current)) {
                    return OwnedProcessWaitResult(exited = true, interrupted = interrupted, failure = null)
                }
            } catch (_: InterruptedException) {
                interrupted = true
            } catch (error: Throwable) {
                return OwnedProcessWaitResult(
                    exited = false,
                    interrupted = interrupted,
                    failure = error,
                )
            }
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) {
                return OwnedProcessWaitResult(exited = false, interrupted = interrupted, failure = null)
            }
            try {
                Thread.sleep(pollSleepMillis(remainingNanos))
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    }

    private fun waitForProcessExitInterruptibly(
        current: NativeShellProcessStopHandle,
        timeoutMs: Long,
    ): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        while (isOwnedProcessAlive(current)) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) return false
            Thread.sleep(pollSleepMillis(remainingNanos))
        }
        return true
    }

    private fun isOwnedProcessAlive(current: NativeShellProcessStopHandle): Boolean = try {
        current.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun pollSleepMillis(remainingNanos: Long): Long {
        val roundedUpMs = (remainingNanos + 999_999L) / 1_000_000L
        return roundedUpMs.coerceIn(1L, PROCESS_POLL_INTERVAL_MS)
    }

    internal class AttachableNativeShellProcessStopHandle : NativeShellProcessStopHandle {
        private var process: Process? = null

        internal fun attach(startedProcess: Process) {
            process = startedProcess
        }

        private fun attachedProcess(): Process = checkNotNull(process) {
            "native shell process handle has not been attached"
        }

        override val supportsForceDestroy: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

        override fun exitValue(): Int = attachedProcess().exitValue()

        override fun destroy() = attachedProcess().destroy()

        override fun forceDestroy() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                destroyProcessForciblyApi26(attachedProcess())
            } else {
                error("forced process termination requires Android 8.0 (API 26)")
            }
        }
    }

    internal fun <T> withNativeShellProcessOwnership(
        start: () -> Process,
        action: (Process, NativeShellProcessStopHandle) -> T,
        cleanup: (Process, NativeShellProcessStopHandle) -> Unit,
        handleFactory: () -> AttachableNativeShellProcessStopHandle = {
            AttachableNativeShellProcessStopHandle()
        },
    ): T {
        // Allocate cleanup authority before invoking start(). Once start returns, this
        // finally scope owns the Process even if handle attachment or setup fails.
        val handle = handleFactory()
        var process: Process? = null
        try {
            val startedProcess = start()
            process = startedProcess
            handle.attach(startedProcess)
            return action(startedProcess, handle)
        } finally {
            val ownedProcess = process
            if (ownedProcess != null) {
                cleanup(ownedProcess, handle)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun destroyProcessForciblyApi26(process: Process) {
        process.destroyForcibly()
    }

    private object ProcfsOwnedProcessInventory : OwnedProcessInventory {
        private val procRoot = File("/proc")

        override fun snapshot(ownerToken: String): ProcessInventorySnapshot {
            val entries = procRoot.listFiles()
                ?: return ProcessInventorySnapshot(
                    sameUidPids = emptySet(),
                    ownerMarkedPids = emptySet(),
                    failure = IllegalStateException("could not enumerate /proc"),
                )
            val sameUidPids = mutableSetOf<Int>()
            val ownerMarkedPids = mutableSetOf<Int>()
            val unreadableSameUidPids = mutableSetOf<Int>()
            val failures = mutableListOf<Throwable>()
            val ownPid = android.os.Process.myPid()
            val ownUid = android.os.Process.myUid()
            entries.forEach { entry ->
                val pid = entry.name.toIntOrNull()?.takeIf { it > 1 && it != ownPid }
                    ?: return@forEach
                val processUid = runCatching { Os.stat(entry.absolutePath).st_uid }.getOrElse { error ->
                    if (entry.exists()) {
                        failures += IllegalStateException("could not inspect /proc/$pid ownership", error)
                    }
                    return@forEach
                }
                if (processUid != ownUid) return@forEach
                sameUidPids += pid
                val marked = runCatching { processHasOwnerToken(pid, ownerToken) }.getOrElse {
                    if (entry.exists()) unreadableSameUidPids += pid
                    false
                }
                if (marked) ownerMarkedPids += pid
            }
            return ProcessInventorySnapshot(
                sameUidPids = sameUidPids,
                ownerMarkedPids = ownerMarkedPids,
                unreadableSameUidPids = unreadableSameUidPids,
                failure = failures.reduceOrNull { primary, next ->
                    primary.apply { addSuppressed(next) }
                },
            )
        }

        override fun signalIfOwned(
            pid: Int,
            ownerToken: String,
            force: Boolean,
        ): Throwable? {
            val processDir = File(procRoot, pid.toString())
            if (!processDir.exists()) return null
            return runCatching {
                require(pid > 1 && pid != android.os.Process.myPid()) {
                    "refusing to signal protected PID $pid"
                }
                require(Os.stat(processDir.absolutePath).st_uid == android.os.Process.myUid()) {
                    "refusing to signal PID $pid because its UID changed"
                }
                require(processHasOwnerToken(pid, ownerToken)) {
                    "refusing to signal PID $pid because its owner marker changed"
                }
                Os.kill(pid, if (force) OsConstants.SIGKILL else OsConstants.SIGTERM)
            }.exceptionOrNull()?.takeUnless { !processDir.exists() }
        }

        private fun processHasOwnerToken(pid: Int, ownerToken: String): Boolean {
            val environmentBytes = readProcFileBounded(File(procRoot, "$pid/environ"))
            val expectedEntry = "$PROCESS_OWNER_ENV=$ownerToken".toByteArray(Charsets.UTF_8)
            var entryStart = 0
            for (index in 0..environmentBytes.size) {
                if (index == environmentBytes.size || environmentBytes[index] == 0.toByte()) {
                    if (index - entryStart == expectedEntry.size) {
                        var matches = true
                        for (offset in expectedEntry.indices) {
                            if (environmentBytes[entryStart + offset] != expectedEntry[offset]) {
                                matches = false
                                break
                            }
                        }
                        if (matches) return true
                    }
                    entryStart = index + 1
                }
            }
            return false
        }

        private fun readProcFileBounded(file: File): ByteArray {
            return file.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= MAX_PROC_ENVIRON_BYTES) {
                        "process environment exceeded the bounded inspection limit"
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }
    }

    internal fun readStreamWithin(
        future: Future<String>,
        timeoutMs: Long = 1_000L,
    ): BoundedStreamRead = try {
        BoundedStreamRead(future.get(timeoutMs, TimeUnit.MILLISECONDS), completed = true)
    } catch (_: InterruptedException) {
        BoundedStreamRead("", completed = false, interrupted = true)
    } catch (_: Throwable) {
        BoundedStreamRead("", completed = false)
    }

    internal fun restoreInterruptAfterOwnedCleanup(interrupted: Boolean) {
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }

    internal fun resolveShellPath(state: JSONObject): String {
        if (state.optString("execution_mode") == "android_system_shell") {
            return "/system/bin/sh"
        }
        val configured = state.optString("shell_path", state.optString("bash_path")).trim()
        if (configured.startsWith("/system/")) {
            return configured
        }
        if (configured.isNotBlank()) {
            val shellFile = File(configured)
            if (shellFile.isFile && shellFile.canExecute()) {
                return shellFile.absolutePath
            }
        }
        return "/system/bin/sh"
    }

    internal fun shellInvocation(shellPath: String, command: String): List<String> {
        val shellName = File(shellPath).name.lowercase()
        val commandFlag = if (shellName.contains("bash")) "-lc" else "-c"
        return listOf(shellPath, commandFlag, command)
    }

    internal fun executionDeniedHint(state: JSONObject, command: String): String {
        val route = state.optString("native_execution_route").ifBlank { "unknown" }
        val prefix = state.optString("prefix_path")
        val mentionsWritablePrefix = prefix.isNotBlank() && command.contains(prefix)
        val routeDetail = if (mentionsWritablePrefix) {
            "The command names a writable prefix path directly."
        } else {
            "The selected executable route was $route."
        }
        return "Android found the command but refused to execute it. $routeDetail " +
            "Downloaded ELF files in app data cannot be made executable with chmod on Android 10+. " +
            "Use the packaged command name or update Hermes so it can repair the APK-native route; " +
            "do not grant broad storage permission or retry chmod."
    }
}
