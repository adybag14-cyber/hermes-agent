package com.mobilefork.hermesagent.models

import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import java.util.concurrent.atomic.AtomicLong

internal class RuntimeSelectionSupersededException :
    IllegalStateException("A newer local-runtime selection superseded this action")

/**
 * Process-wide authority for settings saves and local-model handoffs.
 *
 * Epoch invalidation shares the monitor with short settings/pointer/UI transactions, so a newer
 * action cannot overtake an already admitted older durable commit. Potentially long model,
 * runtime, Python startup, filesystem, and network work never holds this monitor. The final
 * credential/config file commit is the exception: [PythonRuntimeWriteAuthority] holds the monitor
 * only around that bounded write so an older writer cannot land after a newer durable selection.
 * The monitor is always acquired before
 * AppSettingsStore's cache lock or LocalModelDownloadStore's store lock. Code which already owns
 * either store lock must use only [isCurrent] or [requireCurrent].
 */
internal object LocalModelRuntimeSelectionAuthority {
    private val generation = AtomicLong(0L)
    private val actionMonitor = Any()

    fun beginAction(): Long = synchronized(actionMonitor) {
        generation.incrementAndGet()
    }

    /**
     * Begin a newer action only while a short authoritative precondition still holds. The
     * predicate must not perform slow work and may acquire only locks below [actionMonitor].
     */
    fun beginActionIf(admitted: () -> Boolean): Long? = synchronized(actionMonitor) {
        if (!admitted()) return@synchronized null
        generation.incrementAndGet()
    }

    fun invalidate(): Long = beginAction()

    fun isCurrent(candidate: Long): Boolean = generation.get() == candidate

    fun currentGeneration(): Long = generation.get()

    fun requireCurrent(candidate: Long) {
        if (!isCurrent(candidate)) throw RuntimeSelectionSupersededException()
    }

    fun <T> withCurrent(candidate: Long, action: () -> T): T {
        return synchronized(actionMonitor) {
            requireCurrent(candidate)
            action()
        }
    }

    internal fun <T> withAdmissionCheck(
        admissionCheck: () -> Unit,
        action: () -> T,
    ): T {
        return synchronized(actionMonitor) {
            admissionCheck()
            val result = action()
            admissionCheck()
            result
        }
    }

    fun runIfCurrent(candidate: Long, action: () -> Unit): Boolean {
        return synchronized(actionMonitor) {
            if (!isCurrent(candidate)) return@synchronized false
            action()
            true
        }
    }

    /**
     * Run potentially long work without holding [actionMonitor]. Callers which create a runtime
     * resource must also enforce [requireCurrent] while owning that runtime's own serialization
     * lock and clean up a stale result before releasing ownership. This outer pre/post gate keeps
     * all subsequent effects and UI publications from an invalidated action suppressed.
     */
    fun <T> performLongIfCurrent(
        candidate: Long,
        cleanupStaleResultWhileOwned: (T) -> Unit = {},
        action: () -> T,
    ): T {
        requireCurrent(candidate)
        val result = action()
        if (!isCurrent(candidate)) {
            cleanupStaleResultWhileOwned(result)
            throw RuntimeSelectionSupersededException()
        }
        return result
    }
}

/**
 * Commit one backend/lane/preferred-model contract without ever publishing a non-blank pointer
 * against partially updated settings.
 *
 * SharedPreferences cannot atomically span the settings and download files, so this uses a
 * fail-closed three-stage protocol under the process-wide authority monitor:
 *
 * 1. clear the prior preferred pointer;
 * 2. durably commit the backend and required lane;
 * 3. publish the target pointer.
 *
 * Any stage failure leaves either the previous complete pair or a blank pointer, never a newly
 * preferred model paired with stale/incompatible runtime settings.
 */
internal fun persistPreferredModelRuntimeSelection(
    settingsStore: AppSettingsStore,
    downloadStore: LocalModelDownloadStore,
    recordId: String,
    backendKind: BackendKind,
    requiredLlamaCppRuntimeLane: String?,
    selectionGeneration: Long = LocalModelRuntimeSelectionAuthority.beginAction(),
    clearSupersededPendingAutoStart: Boolean = true,
    expectedPendingAutoStartRecordId: String? = null,
    afterSettingsCommitted: () -> Unit = {},
): Boolean {
    return LocalModelRuntimeSelectionAuthority.withCurrent(selectionGeneration) {
        val pendingAutoStartId = downloadStore.pendingAutoStartRecordId()
        if (
            expectedPendingAutoStartRecordId != null &&
            pendingAutoStartId != expectedPendingAutoStartRecordId
        ) {
            return@withCurrent false
        }
        if (
            clearSupersededPendingAutoStart &&
            pendingAutoStartId.isNotBlank() &&
            pendingAutoStartId != recordId &&
            !downloadStore.clearPendingAutoStartRecordId(pendingAutoStartId)
        ) {
            return@withCurrent false
        }
        LocalModelRuntimeSelectionAuthority.requireCurrent(selectionGeneration)

        val priorPreferredId = downloadStore.preferredDownloadId()
        if (
            priorPreferredId.isNotBlank() &&
            !downloadStore.clearPreferredDownloadId(priorPreferredId)
        ) {
            return@withCurrent false
        }
        LocalModelRuntimeSelectionAuthority.requireCurrent(selectionGeneration)

        settingsStore.update { current ->
            LocalModelRuntimeSelectionAuthority.requireCurrent(selectionGeneration)
            current.copy(
                onDeviceBackend = backendKind.persistedValue,
                llamaCppRuntimeLane = requiredLlamaCppRuntimeLane ?: current.llamaCppRuntimeLane,
            )
        }
        afterSettingsCommitted()
        LocalModelRuntimeSelectionAuthority.requireCurrent(selectionGeneration)
        val published = downloadStore.setPreferredDownloadId(recordId)
        if (!LocalModelRuntimeSelectionAuthority.isCurrent(selectionGeneration)) {
            if (published) {
                runCatching { downloadStore.clearPreferredDownloadId(recordId) }
            }
            throw RuntimeSelectionSupersededException()
        }
        published
    }
}

internal fun updateRuntimeSelectionSettings(
    settingsStore: AppSettingsStore,
    selectionGeneration: Long,
    transform: (AppSettings) -> AppSettings,
): AppSettings {
    return LocalModelRuntimeSelectionAuthority.withCurrent(selectionGeneration) {
        settingsStore.update(transform)
    }
}

internal fun clearPendingAutoStartForGeneration(
    downloadStore: LocalModelDownloadStore,
    selectionGeneration: Long,
): Boolean {
    return LocalModelRuntimeSelectionAuthority.withCurrent(selectionGeneration) {
        val pendingId = downloadStore.pendingAutoStartRecordId()
        pendingId.isBlank() || downloadStore.clearPendingAutoStartRecordId(pendingId)
    }
}

/**
 * Clear a pending Download & Start intent only when a newer explicit runtime tuple contradicts
 * it. Callers must already hold [LocalModelRuntimeSelectionAuthority.withCurrent], which keeps
 * this compare-and-clear in the same short authoritative transaction as the newer selection.
 */
internal fun clearContradictoryPendingAutoStart(
    downloadStore: LocalModelDownloadStore,
    selectedBackend: BackendKind,
    selectedLlamaCppRuntimeLane: String,
): Boolean {
    val pendingId = downloadStore.pendingAutoStartRecordId()
    if (pendingId.isBlank()) return true
    val pendingRecord = downloadStore.findDownload(pendingId)
    val compatible = pendingRecord?.let { record ->
        val pendingBackend = backendKindForDownload(record)
        if (pendingBackend != selectedBackend || selectedBackend == BackendKind.NONE) {
            false
        } else {
            val requiredLane = requiredLlamaCppRuntimeLaneForDownload(record)
            requiredLane == null ||
                AppSettings.normalizeLlamaCppRuntimeLane(selectedLlamaCppRuntimeLane) == requiredLane
        }
    } ?: false
    return compatible || downloadStore.clearPendingAutoStartRecordId(pendingId)
}

private fun backendKindForDownload(record: LocalModelDownloadRecord): BackendKind? {
    return when (record.runtimeFlavor.trim().lowercase()) {
        "gguf", "llama.cpp", "llama-cpp", "llama_cpp" -> BackendKind.LLAMA_CPP
        "litert-lm", "litert_lm", "litertlm", "litert lm" -> BackendKind.LITERT_LM
        else -> null
    }
}

private fun requiredLlamaCppRuntimeLaneForDownload(record: LocalModelDownloadRecord): String? {
    val artifact = VerifiedLocalModelArtifacts.find(record.repoOrUrl, record.filePath) ?: return null
    if (!record.revision.equals(artifact.revision, ignoreCase = true)) return null
    return artifact.requiredLlamaCppRuntimeLane?.let(AppSettings::normalizeLlamaCppRuntimeLane)
}
