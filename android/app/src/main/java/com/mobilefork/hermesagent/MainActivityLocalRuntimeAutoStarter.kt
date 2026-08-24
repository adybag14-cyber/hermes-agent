package com.mobilefork.hermesagent

import com.mobilefork.hermesagent.backend.BackendKind
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-scoped post-frame admission for a persisted, explicitly selected local runtime.
 *
 * The slow settings read and runtime startup both run inside [launchAsync]. Runtime ownership
 * remains serialized by HermesRuntimeManager when chat or Settings requests startup concurrently.
 * The claim occurs inside that process-lifetime task: an Activity destroyed before its posted
 * request runs cannot consume startup authority, and recreation can safely post another request.
 */
internal class MainActivityLocalRuntimeAutoStarter(
    private val loadSelectedBackend: () -> BackendKind,
    private val captureSelectionGeneration: () -> Long,
    private val launchAsync: (() -> Unit) -> Unit,
    private val ensureStarted: (expectedBackend: BackendKind, selectionGeneration: Long) -> Unit,
) {
    private val processLaunchHandled = AtomicBoolean(false)

    fun requestAfterFirstFrame() {
        launchAsync {
            if (!processLaunchHandled.compareAndSet(false, true)) {
                return@launchAsync
            }
            val selectionGeneration = captureSelectionGeneration()
            val selectedBackend = loadSelectedBackend()
            when (selectedBackend) {
                BackendKind.LLAMA_CPP,
                BackendKind.LITERT_LM,
                -> ensureStarted(selectedBackend, selectionGeneration)

                BackendKind.NONE,
                BackendKind.AICORE,
                -> Unit
            }
        }
    }
}
