package com.mobilefork.hermesagent.play

import com.mobilefork.hermesagent.BuildConfig
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object PlayForegroundLifetime {
    private val lock = Any()
    private var visibleActivities = 0
    private var generation = 0L
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun started() = synchronized(lock) {
        visibleActivities += 1
        generation += 1
    }

    fun stopped() {
        val stoppedGeneration = synchronized(lock) {
            check(visibleActivities > 0)
            visibleActivities -= 1
            ++generation
        }
        if (!BuildConfig.HERMES_PLAY_EDITION) return
        cleanupScope.launch {
            HermesRuntimeManager.withSerializedLocalBackendMutation(mutation = { _, stop ->
                // Recheck after acquiring runtime ownership: a stale stop cannot stop a newly visible app.
                val shouldStop = synchronized(lock) { visibleActivities == 0 && generation == stoppedGeneration }
                if (shouldStop) stop()
            })
        }
    }

    fun requireForeground() {
        if (!BuildConfig.HERMES_PLAY_EDITION) return
        synchronized(lock) {
            check(visibleActivities > 0) { "Play inference is foreground-only; return to the app and retry" }
        }
    }
}
