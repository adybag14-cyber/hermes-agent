package com.mobilefork.hermesagent.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class HermesGlobalAction(val label: String, val actionId: Int) {
    Home("Home", AccessibilityService.GLOBAL_ACTION_HOME),
    Back("Back", AccessibilityService.GLOBAL_ACTION_BACK),
    Recents("Recents", AccessibilityService.GLOBAL_ACTION_RECENTS),
    Notifications("Notifications", AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),
    QuickSettings("Quick settings", AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS),
}

data class HermesScreenMetrics(
    val width: Int,
    val height: Int,
    val density: Float,
)

internal enum class HermesGestureDispatchStatus(val wireValue: String) {
    Completed("completed"),
    Cancelled("cancelled"),
    Rejected("rejected"),
    TimedOut("timed_out"),
    Interrupted("interrupted"),
}

internal data class HermesGestureDispatchResult(
    val status: HermesGestureDispatchStatus,
) {
    val completed: Boolean get() = status == HermesGestureDispatchStatus.Completed
}

internal fun interface HermesGestureResultSink {
    fun report(completed: Boolean)
}

internal class HermesGestureDispatchOperation(
    private val timeoutMs: Long,
) {
    private val completion = AtomicReference<HermesGestureDispatchStatus?>(null)
    private val completionLatch = CountDownLatch(1)

    @Volatile
    private var dispatchAccepted: Boolean = false

    internal fun finishAdmission(accepted: Boolean) {
        dispatchAccepted = accepted
    }

    internal fun report(completed: Boolean) {
        val status = if (completed) {
            HermesGestureDispatchStatus.Completed
        } else {
            HermesGestureDispatchStatus.Cancelled
        }
        if (completion.compareAndSet(null, status)) {
            completionLatch.countDown()
        }
    }

    fun awaitTerminalResult(): HermesGestureDispatchResult {
        if (!dispatchAccepted) {
            return HermesGestureDispatchResult(HermesGestureDispatchStatus.Rejected)
        }
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1L))
        var interrupted = false
        var terminalCallbackReceived = false
        while (!terminalCallbackReceived) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) break
            try {
                terminalCallbackReceived = completionLatch.await(remainingNanos, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                // Do not release request ownership while Android may still deliver this gesture.
                // Remember interruption, clear it by entering this catch, and finish the bounded
                // containment wait before restoring the flag for the caller.
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return when {
            interrupted -> HermesGestureDispatchResult(HermesGestureDispatchStatus.Interrupted)
            terminalCallbackReceived -> HermesGestureDispatchResult(
                completion.get() ?: HermesGestureDispatchStatus.Cancelled,
            )
            else -> {
                // The physical stroke itself is duration-bounded. The timeout used by production
                // includes that complete duration plus a callback grace window, so no accepted
                // touch remains scheduled when request ownership is released here.
                HermesGestureDispatchResult(HermesGestureDispatchStatus.TimedOut)
            }
        }
    }
}

object HermesAccessibilityController {
    @Volatile
    private var service: HermesAccessibilityService? = null
    @Volatile
    private var lastForegroundPackageName: String = ""

    fun bind(service: HermesAccessibilityService) {
        this.service = service
    }

    fun unbind(service: HermesAccessibilityService) {
        if (this.service === service) {
            this.service = null
        }
    }

    fun isServiceConnected(): Boolean = service != null

    fun isServiceEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        val expected = ComponentName(context, HermesAccessibilityService::class.java).flattenToString()
        return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun performAction(action: HermesGlobalAction): Boolean {
        return service?.performGlobalAction(action.actionId) == true
    }

    fun screenMetrics(): HermesScreenMetrics? {
        val metrics = service?.resources?.displayMetrics ?: return null
        return HermesScreenMetrics(
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            density = metrics.density,
        )
    }

    fun performTap(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        return dispatchGesture(path, durationMs.coerceAtLeast(1L))
    }

    internal fun performTapForCompletion(
        x: Float,
        y: Float,
        durationMs: Long,
    ): HermesGestureDispatchOperation {
        val path = Path().apply {
            moveTo(x, y)
        }
        return dispatchGestureForCompletion(path, durationMs.coerceAtLeast(1L))
    }

    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        return dispatchGesture(path, durationMs.coerceAtLeast(1L))
    }

    internal fun performSwipeForCompletion(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
    ): HermesGestureDispatchOperation {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        return dispatchGestureForCompletion(path, durationMs.coerceAtLeast(1L))
    }

    fun currentService(): HermesAccessibilityService? = service

    fun rememberForegroundPackage(packageName: String): Boolean {
        val trimmed = packageName.trim()
        if (trimmed.isBlank() || trimmed == lastForegroundPackageName) {
            return false
        }
        lastForegroundPackageName = trimmed
        return true
    }

    fun currentForegroundPackageName(): String = lastForegroundPackageName

    private fun dispatchGesture(path: Path, durationMs: Long): Boolean {
        val connectedService = service ?: return false
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return connectedService.dispatchGesture(gesture, null, null)
    }

    private fun dispatchGestureForCompletion(
        path: Path,
        durationMs: Long,
    ): HermesGestureDispatchOperation {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        val timeoutMs = (durationMs + GESTURE_COMPLETION_GRACE_MS)
            .coerceIn(MIN_GESTURE_COMPLETION_TIMEOUT_MS, MAX_GESTURE_COMPLETION_TIMEOUT_MS)
        return beginGestureDispatch(timeoutMs) { sink ->
            val connectedService = service ?: return@beginGestureDispatch false
            connectedService.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        sink.report(completed = true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        sink.report(completed = false)
                    }
                },
                null,
            )
        }
    }

    internal fun awaitGestureDispatchCompletion(
        timeoutMs: Long,
        dispatch: (HermesGestureResultSink) -> Boolean,
    ): HermesGestureDispatchResult {
        return beginGestureDispatch(timeoutMs, dispatch).awaitTerminalResult()
    }

    internal fun beginGestureDispatch(
        timeoutMs: Long,
        dispatch: (HermesGestureResultSink) -> Boolean,
    ): HermesGestureDispatchOperation {
        val operation = HermesGestureDispatchOperation(timeoutMs)
        val sink = HermesGestureResultSink { completed -> operation.report(completed) }
        val accepted = runCatching { dispatch(sink) }.getOrDefault(false)
        operation.finishAdmission(accepted)
        return operation
    }

    private const val GESTURE_COMPLETION_GRACE_MS = 2_000L
    private const val MIN_GESTURE_COMPLETION_TIMEOUT_MS = 2_001L
    private const val MAX_GESTURE_COMPLETION_TIMEOUT_MS = 7_000L
}
