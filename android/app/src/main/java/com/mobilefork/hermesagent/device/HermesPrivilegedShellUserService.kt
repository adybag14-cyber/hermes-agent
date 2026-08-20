package com.mobilefork.hermesagent.device

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class PrivilegedShellCompletionDecision(
    val unsafe: Boolean,
    val detachedProcessRejected: Boolean,
    val exitCode: Int,
    val success: Boolean,
)

internal fun privilegedShellCompletionDecision(
    finishedWithinTimeout: Boolean,
    processUnwindVerified: Boolean,
    readersCompleted: Boolean,
    detachedProcessDetected: Boolean,
    processExitCode: Int?,
): PrivilegedShellCompletionDecision {
    val unsafe = !finishedWithinTimeout || !processUnwindVerified || !readersCompleted
    val exitCode = when {
        !finishedWithinTimeout -> 124
        unsafe -> 125
        detachedProcessDetected -> 125
        else -> requireNotNull(processExitCode) { "completed privileged shell requires an exit code" }
    }
    return PrivilegedShellCompletionDecision(
        unsafe = unsafe,
        detachedProcessRejected = detachedProcessDetected,
        exitCode = exitCode,
        success = !unsafe && !detachedProcessDetected && exitCode == 0,
    )
}

class HermesPrivilegedShellUserService : IHermesPrivilegedShellService.Stub {
    constructor() : super()
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    @Volatile
    private var unsafeProcessDetail: String = ""

    init {
        scheduleProcessExit(IDLE_PROCESS_EXIT_DELAY_MS, "hermes-shizuku-service-idle-exit")
    }

    @Synchronized
    override fun runCommand(command: String, timeoutSeconds: Int): String {
        val normalizedCommand = command.trim()
        if (normalizedCommand.isBlank()) {
            return finish(
                JSONObject()
                .put("success", false)
                .put("exit_code", 2)
                .put("error", "run_privileged_shell requires a non-empty command"),
            )
        }
        if (normalizedCommand.indexOf('\u0000') >= 0) {
            return finish(
                JSONObject()
                .put("success", false)
                .put("exit_code", 2)
                .put("error", "run_privileged_shell command must not contain NUL bytes"),
            )
        }
        if (unsafeProcessDetail.isNotBlank()) {
            return finish(
                JSONObject()
                    .put("success", false)
                    .put("exit_code", 125)
                    .put("error", unsafeProcessDetail)
                    .put("timed_out", false)
                    .put("process_unwind_verified", false)
                    .put("requires_service_restart", true)
                    .put("privilege_context", "shizuku_user_service"),
            )
        }

        val timeout = timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)
        val executionOwnerToken = NativeAndroidShellTool.newProcessOwnerToken()
        val ownedProcessInventory = NativeAndroidShellTool.ownedProcessInventory()
        val processInventoryBaseline = ownedProcessInventory.snapshot(executionOwnerToken)
        NativeAndroidShellTool.processInventoryPreflightFailure(processInventoryBaseline)?.let { failure ->
            val detail = failure.message ?: failure.javaClass.simpleName
            return finish(
                JSONObject()
                    .put("success", false)
                    .put("exit_code", 125)
                    .put(
                        "error",
                        "Hermes could not establish a safe privileged same-UID process baseline ($detail). " +
                            "No privileged shell command was started.",
                    )
                    .put("timed_out", false)
                    .put("process_inventory_available", false)
                    .put("process_unwind_verified", false)
                    .put("detached_process_cleanup_verified", false)
                    .put("requires_service_restart", false)
                    .put("privilege_context", "shizuku_user_service"),
            )
        }
        var callerInterrupted = false
        var processUnwindVerified: Boolean? = null
        var streamCleanupVerified: Boolean? = null
        var requiresServiceRestart: Boolean? = null
        var startedProcess: Process? = null
        var startedProcessHandle: NativeShellProcessStopHandle? = null
        var startedReadersDone: CountDownLatch? = null
        var ownershipCleanupCompleted = false
        val payload = runCatching {
            val process = ProcessBuilder("/system/bin/sh", "-c", normalizedCommand)
                .apply {
                    environment()[NativeAndroidShellTool.PROCESS_OWNER_ENV] = executionOwnerToken
                }
                .start()
            startedProcess = process
            val processHandle = ownedShellProcessStopHandle(process)
            startedProcessHandle = processHandle
            val stdout = AtomicReference("")
            val stderr = AtomicReference("")
            val readersDone = CountDownLatch(2)
            startedReadersDone = readersDone
            Thread {
                try {
                    stdout.set(readLimited(process.inputStream))
                } finally {
                    readersDone.countDown()
                }
            }.apply {
                name = "hermes-shizuku-stdout"
                isDaemon = true
                start()
            }
            Thread {
                try {
                    stderr.set(readLimited(process.errorStream))
                } finally {
                    readersDone.countDown()
                }
            }.apply {
                name = "hermes-shizuku-stderr"
                isDaemon = true
                start()
            }

            val waitResult = awaitOwnedShellProcess(
                current = processHandle,
                waitTimeoutMs = TimeUnit.SECONDS.toMillis(timeout.toLong()),
            )
            val detachedContainment = NativeAndroidShellTool.containDetachedOwnedProcesses(
                baseline = processInventoryBaseline,
                ownerToken = executionOwnerToken,
                inventory = ownedProcessInventory,
            )
            ownershipCleanupCompleted = true
            callerInterrupted = waitResult.interrupted || detachedContainment.interrupted
            val unwindVerified = waitResult.processUnwindVerified && detachedContainment.verified
            processUnwindVerified = unwindVerified
            val cleanupDetails = listOfNotNull(
                waitResult.cleanupFailure?.let { failure ->
                    "Privileged shell process cleanup failed: " +
                        (failure.message ?: failure.javaClass.simpleName)
                },
                detachedContainment.failure?.let { failure ->
                    "Privileged detached-process containment failed: " +
                        (failure.message ?: failure.javaClass.simpleName)
                },
            )
            val detachedRejectionDetail = detachedContainment.detectedOwnedPids
                .takeIf { it.isNotEmpty() }
                ?.let { detectedPids ->
                    "Detached/background privileged shell processes are unsupported; Hermes stopped the " +
                        "owned process(es) before returning: ${detectedPids.joinToString(",")}"
                }
                .orEmpty()
            val readersCompleted = try {
                readersDone.await(2, TimeUnit.SECONDS)
            } catch (error: InterruptedException) {
                callerInterrupted = true
                false
            }
            streamCleanupVerified = readersCompleted
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            val finished = waitResult.finishedWithinTimeout
            val completion = privilegedShellCompletionDecision(
                finishedWithinTimeout = finished,
                processUnwindVerified = unwindVerified,
                readersCompleted = readersCompleted,
                detachedProcessDetected = detachedContainment.detectedOwnedPids.isNotEmpty(),
                processExitCode = if (finished) process.exitValue() else null,
            )
            val unsafe = completion.unsafe
            requiresServiceRestart = unsafe
            if (unsafe) {
                unsafeProcessDetail = buildString {
                    val reasons = listOfNotNull(
                        waitResult.waitFailure?.let {
                            "wait failed: ${it.message ?: it.javaClass.simpleName}"
                        },
                        "the privileged command exceeded its ${timeout}s deadline".takeIf {
                            !finished && waitResult.waitFailure == null
                        },
                        cleanupDetails.takeIf { it.isNotEmpty() }?.joinToString("; "),
                        "privileged stream readers did not reach EOF".takeIf { !readersCompleted },
                    )
                    append(reasons.joinToString("; ").ifBlank { "Privileged shell cleanup could not be verified." })
                    append(" Hermes blocked further commands until this transient Shizuku service exits.")
                }
            }
            waitResult.waitFailure?.let { throw it }
            val exitCode = completion.exitCode
            val capturedError = stderr.get()
            val cleanupDetail = cleanupDetails.joinToString("\n")
            val reportedError = if (cleanupDetail.isBlank() && detachedRejectionDetail.isBlank() && !unsafe) {
                capturedError
            } else {
                buildString {
                    append(capturedError)
                    if (isNotEmpty() && this[length - 1] != '\n') append('\n')
                    if (cleanupDetail.isNotBlank()) append(cleanupDetail)
                    if (detachedRejectionDetail.isNotBlank()) {
                        if (isNotEmpty() && this[length - 1] != '\n') append('\n')
                        append(detachedRejectionDetail)
                    }
                    if (unsafeProcessDetail.isNotBlank()) {
                        if (isNotEmpty() && this[length - 1] != '\n') append('\n')
                        append(unsafeProcessDetail)
                    }
                }
            }
            JSONObject()
                .put("success", completion.success)
                .put("exit_code", exitCode)
                .put("output", stdout.get())
                .put("error", reportedError)
                .put("timed_out", !finished)
                .put("process_unwind_verified", unwindVerified)
                .put("detached_process_cleanup_verified", detachedContainment.verified)
                .put("detached_process_detected_count", detachedContainment.detectedOwnedPids.size)
                .put("detached_process_terminated_count", detachedContainment.terminatedOwnedPids.size)
                .put("detached_process_remaining_count", detachedContainment.remainingOwnedPids.size)
                .put("detached_process_rejected", completion.detachedProcessRejected)
                .put(
                    "ambiguous_new_same_uid_process_count",
                    detachedContainment.ambiguousNewSameUidPids.size,
                )
                .put("stream_cleanup_verified", readersCompleted)
                .put("requires_service_restart", unsafe)
                .put("uid", android.os.Process.myUid())
                .put("privilege_context", "shizuku_user_service")
        }.getOrElse { error ->
            val process = startedProcess
            if (process != null) {
                requiresServiceRestart = true
                var emergencyCleanup: NativeAndroidShellTool.PostStartFailureCleanupResult? = null
                if (!ownershipCleanupCompleted) {
                    val processHandle = startedProcessHandle ?: ownedShellProcessStopHandle(process)
                    emergencyCleanup = NativeAndroidShellTool.cleanupAfterPostStartFailure(
                        current = processHandle,
                        baseline = processInventoryBaseline,
                        ownerToken = executionOwnerToken,
                        inventory = ownedProcessInventory,
                    )
                    ownershipCleanupCompleted = true
                    callerInterrupted = callerInterrupted || emergencyCleanup.interrupted
                    processUnwindVerified = emergencyCleanup.verified
                }
                runCatching { process.inputStream.close() }
                runCatching { process.errorStream.close() }
                runCatching { process.outputStream.close() }
                val readersStopped = try {
                    startedReadersDone?.await(2, TimeUnit.SECONDS) ?: true
                } catch (_: InterruptedException) {
                    callerInterrupted = true
                    false
                }
                streamCleanupVerified = (streamCleanupVerified ?: true) && readersStopped
                val cleanupReasons = listOfNotNull(
                    emergencyCleanup?.termination?.failure?.let {
                        "parent cleanup failed: ${it.message ?: it.javaClass.simpleName}"
                    },
                    emergencyCleanup?.containment?.failure?.let {
                        "descendant containment failed: ${it.message ?: it.javaClass.simpleName}"
                    },
                    "stream readers did not stop".takeIf { streamCleanupVerified != true },
                )
                unsafeProcessDetail = buildString {
                    append(
                        "Privileged shell setup or execution failed after the process started " +
                            "(${error.message ?: error.javaClass.simpleName})",
                    )
                    if (cleanupReasons.isNotEmpty()) {
                        append(": ")
                        append(cleanupReasons.joinToString("; "))
                    }
                    append(". Hermes blocked further commands until this transient Shizuku service exits.")
                }
            }
            JSONObject()
                .put("success", false)
                .put("exit_code", -1)
                .put(
                    "error",
                    listOf(
                        error.message ?: error.javaClass.simpleName,
                        unsafeProcessDetail,
                    ).filter { it.isNotBlank() }.joinToString("\n"),
                )
                .put("privilege_context", "shizuku_user_service")
                .apply {
                    processUnwindVerified?.let { verified ->
                        put("process_unwind_verified", verified)
                        put("requires_service_restart", !verified)
                    }
                    streamCleanupVerified?.let { verified ->
                        put("stream_cleanup_verified", verified)
                        if (!verified) put("requires_service_restart", true)
                    }
                    requiresServiceRestart?.let { required ->
                        put("requires_service_restart", required)
                    }
                }
        }
        NativeAndroidShellTool.restoreInterruptAfterOwnedCleanup(callerInterrupted)
        return finish(payload)
    }

    private fun finish(payload: JSONObject): String {
        scheduleProcessExit(PROCESS_EXIT_DELAY_MS, "hermes-shizuku-service-exit")
        return payload.toString()
    }

    private fun scheduleProcessExit(delayMs: Long, threadName: String) {
        Thread {
            try {
                Thread.sleep(delayMs)
            } finally {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }.apply {
            name = threadName
            isDaemon = true
            start()
        }
    }

    private fun readLimited(input: InputStream): String {
        return input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val output = ByteArrayOutputStream()
            var truncated = false
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                val remaining = MAX_CAPTURE_BYTES - output.size()
                if (remaining <= 0) {
                    truncated = true
                    break
                }
                output.write(buffer, 0, minOf(read, remaining))
                if (read > remaining) {
                    truncated = true
                    break
                }
            }
            buildString {
                append(output.toByteArray().toString(Charsets.UTF_8))
                if (truncated) {
                    append("\n[truncated]")
                }
            }
        }
    }

    private companion object {
        private const val MAX_CAPTURE_BYTES = 16_384
        private const val MAX_TIMEOUT_SECONDS = 120
        private const val PROCESS_EXIT_DELAY_MS = 750L
        private const val IDLE_PROCESS_EXIT_DELAY_MS = 180_000L
    }
}
