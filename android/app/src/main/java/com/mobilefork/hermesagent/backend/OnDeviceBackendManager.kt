package com.mobilefork.hermesagent.backend

import android.content.Context
import android.os.Looper
import android.os.Process
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.models.HermesModelDownloadManager
import com.mobilefork.hermesagent.models.LocalModelRuntimeSelectionAuthority
import com.mobilefork.hermesagent.models.VerifiedLocalModelArtifacts
import java.io.File
import java.util.Locale

enum class BackendKind(val persistedValue: String) {
    NONE("none"),
    LLAMA_CPP("llama.cpp"),
    LITERT_LM("litert-lm"),
    AICORE("aicore");

    companion object {
        fun fromPersistedValue(value: String?): BackendKind {
            val normalized = value.orEmpty().trim().lowercase()
            return entries.firstOrNull { it.persistedValue == normalized } ?: NONE
        }
    }
}

data class LocalBackendStatus(
    val backendKind: BackendKind,
    val started: Boolean,
    val baseUrl: String = "",
    val modelName: String = "",
    val sourceModelPath: String = "",
    val statusMessage: String = "",
    val accelerator: String = "",
    val acceleratorFallback: String = "",
    val artifactSummary: String = "",
    val completionVerified: Boolean = false,
    val completionLatencyMs: Long = 0L,
    val requiresAppRestart: Boolean = false,
    /** Ephemeral bearer token which identifies the owned loopback llama.cpp process. */
    val apiKey: String = "",
)

object OnDeviceBackendManager {
    const val LLAMA_CPP_PORT = 15435
    const val LITERT_LM_PORT = 15436

    internal data class ModelInputSupport(
        val image: Boolean,
        val audio: Boolean,
        val policy: String,
    )

    @Volatile
    private var currentStatus: LocalBackendStatus = LocalBackendStatus(
        backendKind = BackendKind.NONE,
        started = false,
        statusMessage = "Remote provider mode",
    )

    fun currentStatus(): LocalBackendStatus = currentStatus

    fun ensureConfigured(
        context: Context,
        backendValue: String,
        dangerouslySkipRamChecks: Boolean = false,
        admissionCheck: () -> Unit = {},
    ): LocalBackendStatus = withLocalBackendOwnership {
        admissionCheck()
        val result = withBackgroundPriorityIfNeeded {
            when (BackendKind.fromPersistedValue(backendValue)) {
                BackendKind.NONE -> stopAll()
                BackendKind.LLAMA_CPP -> ensureLlamaCpp(
                    context = context,
                    dangerouslySkipRamChecks = dangerouslySkipRamChecks,
                    admissionCheck = admissionCheck,
                )
                BackendKind.LITERT_LM -> ensureLiteRtLm(context)
                BackendKind.AICORE -> ensureAICore(context)
            }
        }
        try {
            // If selection changed while a model loaded, discard that old process before
            // releasing backend ownership; a newer queued action can then start cleanly.
            admissionCheck()
            result
        } catch (error: Throwable) {
            stopAll()
            throw error
        }
    }

    /**
     * One monitor guards backend admission, shutdown, and model-file mutation.
     *
     * Lock order is HermesRuntimeManager, this OnDeviceBackendManager monitor, the short
     * LocalModelRuntimeSelectionAuthority admission monitor, then settings/download stores.
     * Code inside [block] must never call back into HermesRuntimeManager. This is internal so
     * deterministic JVM tests can prove that startup admission cannot overlap model removal.
     */
    @Synchronized
    internal fun <T> withLocalBackendOwnership(block: () -> T): T = block()

    internal data class SerializedLocalMutation<T>(
        val value: T,
        val finalStatus: LocalBackendStatus,
    )

    internal fun <T> withSerializedLocalMutation(
        mutation: (
            currentStatus: LocalBackendStatus,
            stopAllLocalBackends: () -> LocalBackendStatus,
        ) -> T,
        afterMutationWhileOwned: (finalStatus: LocalBackendStatus) -> Unit = {},
    ): SerializedLocalMutation<T> = withLocalBackendOwnership {
        try {
            val value = mutation(currentStatus, ::stopAll)
            SerializedLocalMutation(value = value, finalStatus = currentStatus)
        } finally {
            // A store/filesystem exception after a successful stop must not leave the old
            // loopback URL or bearer published when this ownership monitor is released.
            afterMutationWhileOwned(currentStatus)
        }
    }

    @Synchronized
    fun stopAll(): LocalBackendStatus {
        val llamaStopFailure = LlamaCppServerController.stop()
        val liteRtStopFailure = LiteRtLmOpenAiProxy.stop()
        currentStatus = when {
            llamaStopFailure != null -> llamaStopFailureStatus(BackendKind.NONE, llamaStopFailure)
            liteRtStopFailure != null -> {
                // Preserve the identity of the native runtime that could not be stopped.
                // Callers can therefore distinguish a safe transition to NONE from a
                // fail-closed result without parsing a human-readable message.
                liteRtStopFailureStatus(BackendKind.LITERT_LM, liteRtStopFailure)
            }
            else -> LocalBackendStatus(
                backendKind = BackendKind.NONE,
                started = false,
                statusMessage = "Local on-device backends stopped",
            )
        }
        return currentStatus
    }

    fun preferredDownloadSummary(context: Context, backendValue: String): String {
        val preferred = preferredCompletedDownload(context)
        return if (preferred != null) {
            "Preferred local model: ${preferred.title}"
        } else {
            "No preferred local model is selected yet. Download any repo or file and mark it as preferred to let the selected backend try it."
        }
    }

    internal fun ensureLlamaCpp(
        context: Context,
        dangerouslySkipRamChecks: Boolean = false,
        admissionCheck: () -> Unit = {},
    ): LocalBackendStatus {
        stopLiteRtLmBeforeTransition(BackendKind.LLAMA_CPP)?.let { return it }
        val preferred = preferredCompletedDownload(context)
            ?: run {
                stopLlamaCppBeforeTransition(BackendKind.LLAMA_CPP)?.let { return it }
                return LocalBackendStatus(
                    backendKind = BackendKind.LLAMA_CPP,
                    started = false,
                    statusMessage = "No preferred local model is ready for llama.cpp yet",
                ).also { currentStatus = it }
            }

        val modelFile = File(preferred.destinationPath)
        if (!modelFile.isFile) {
            stopLlamaCppBeforeTransition(BackendKind.LLAMA_CPP)?.let { return it }
            return LocalBackendStatus(
                backendKind = BackendKind.LLAMA_CPP,
                started = false,
                statusMessage = "Preferred local model is missing on disk: ${preferred.destinationPath}",
                sourceModelPath = preferred.destinationPath,
            ).also { currentStatus = it }
        }
        if (!preferred.matchesBackendArtifact(BackendKind.LLAMA_CPP)) {
            stopLlamaCppBeforeTransition(BackendKind.LLAMA_CPP)?.let { return it }
            return incompatiblePreferredDownloadStatus(preferred, BackendKind.LLAMA_CPP)
        }
        val artifactProof = verifyKnownArtifact(context, preferred, modelFile, BackendKind.LLAMA_CPP)
        if (artifactProof.error != null) {
            stopLlamaCppBeforeTransition(BackendKind.LLAMA_CPP)?.let { return it }
            return artifactVerificationFailure(preferred, BackendKind.LLAMA_CPP, artifactProof.error)
        }

        val settingsStore = AppSettingsStore(context)
        val laneReconciliation = reconcileVerifiedArtifactLlamaCppRuntimeLane(
            currentSettings = settingsStore.load(),
            verifiedArtifact = artifactProof.verifiedArtifact,
            persistRequiredLane = { requiredLane ->
                persistRequiredLlamaCppRuntimeLaneIfAdmitted(
                    settingsStore = settingsStore,
                    requiredLane = requiredLane,
                    admissionCheck = admissionCheck,
                )
            },
        )
        val settings = when (laneReconciliation) {
            is VerifiedArtifactLaneReconciliation.Ready -> laneReconciliation.settings
            is VerifiedArtifactLaneReconciliation.PersistenceFailure -> {
                stopLlamaCppBeforeTransition(BackendKind.LLAMA_CPP)?.let { return it }
                return LocalBackendStatus(
                    backendKind = BackendKind.LLAMA_CPP,
                    started = false,
                    sourceModelPath = preferred.destinationPath,
                    statusMessage =
                        "Hermes verified the preferred local model, but did not start llama.cpp " +
                            "because it could not persist the required " +
                            "${laneReconciliation.requiredLane} runtime lane. Existing settings were preserved.",
                    artifactSummary = artifactProof.summary,
                ).also { currentStatus = it }
            }
            is VerifiedArtifactLaneReconciliation.UnsupportedRequiredLane -> {
                stopLlamaCppBeforeTransition(BackendKind.LLAMA_CPP)?.let { return it }
                return LocalBackendStatus(
                    backendKind = BackendKind.LLAMA_CPP,
                    started = false,
                    sourceModelPath = preferred.destinationPath,
                    statusMessage =
                        "Hermes verified the preferred local model, but did not start llama.cpp " +
                            "because its required runtime lane " +
                            "'${laneReconciliation.requiredLane}' is unsupported. Existing settings were preserved.",
                    artifactSummary = artifactProof.summary,
                ).also { currentStatus = it }
            }
        }
        val launchConfig = LlamaCppLaunchConfig.fromPersistedValues(
            lane = settings.llamaCppRuntimeLane,
            cacheTypeK = settings.llamaCppCacheTypeK,
            cacheTypeV = settings.llamaCppCacheTypeV,
            flashAttention = settings.llamaCppFlashAttention,
            additionalArguments = settings.llamaCppAdditionalArguments,
        )
        val status = LlamaCppServerController.ensureRunning(
            context = context,
            modelPath = modelFile.absolutePath,
            requestedModelName = preferred.title,
            port = LLAMA_CPP_PORT,
            launchConfig = launchConfig,
            dangerouslySkipRamChecks = dangerouslySkipRamChecks,
        ).withArtifactProof(artifactProof.summary)
        currentStatus = status
        return status
    }

    private fun ensureLiteRtLm(context: Context): LocalBackendStatus {
        stopLlamaCppBeforeTransition(BackendKind.LITERT_LM)?.let { return it }
        val preferred = preferredCompletedDownload(context)
            ?: run {
                stopLiteRtLmBeforeTransition(BackendKind.LITERT_LM)?.let { return it }
                return LocalBackendStatus(
                    backendKind = BackendKind.LITERT_LM,
                    started = false,
                    statusMessage = "No preferred local model is ready for LiteRT-LM yet",
                ).also { currentStatus = it }
            }

        val modelFile = File(preferred.destinationPath)
        if (!modelFile.isFile) {
            stopLiteRtLmBeforeTransition(BackendKind.LITERT_LM)?.let { return it }
            return LocalBackendStatus(
                backendKind = BackendKind.LITERT_LM,
                started = false,
                statusMessage = "Preferred local model is missing on disk: ${preferred.destinationPath}",
                sourceModelPath = preferred.destinationPath,
            ).also { currentStatus = it }
        }
        if (!preferred.matchesBackendArtifact(BackendKind.LITERT_LM)) {
            stopLiteRtLmBeforeTransition(BackendKind.LITERT_LM)?.let { return it }
            return incompatiblePreferredDownloadStatus(preferred, BackendKind.LITERT_LM)
        }
        val artifactProof = verifyKnownArtifact(context, preferred, modelFile, BackendKind.LITERT_LM)
        if (artifactProof.error != null) {
            stopLiteRtLmBeforeTransition(BackendKind.LITERT_LM)?.let { return it }
            return artifactVerificationFailure(preferred, BackendKind.LITERT_LM, artifactProof.error)
        }

        val status = LiteRtLmOpenAiProxy.ensureRunning(
            context = context,
            modelPath = modelFile.absolutePath,
            requestedModelName = preferred.title,
            port = LITERT_LM_PORT,
            inferenceConfig = inferenceConfigFor(preferred, AppSettingsStore(context).load()),
        ).withArtifactProof(artifactProof.summary)
        currentStatus = status
        return status
    }

    /** Fail closed until Hermes ships and verifies a real Android NPU delegate. */
    private fun ensureAICore(@Suppress("UNUSED_PARAMETER") context: Context): LocalBackendStatus {
        stopLlamaCppBeforeTransition(BackendKind.AICORE)?.let { return it }
        stopLiteRtLmBeforeTransition(BackendKind.AICORE)?.let { return it }
        val status = LocalBackendStatus(
            backendKind = BackendKind.AICORE,
            started = false,
            statusMessage = AICoreBackendController.getBackendDescription(),
        )
        currentStatus = status
        return status
    }

    private fun stopLiteRtLmBeforeTransition(targetBackend: BackendKind): LocalBackendStatus? {
        val failure = LiteRtLmOpenAiProxy.stop() ?: return null
        return liteRtStopFailureStatus(targetBackend, failure).also { currentStatus = it }
    }

    private fun stopLlamaCppBeforeTransition(targetBackend: BackendKind): LocalBackendStatus? {
        val failure = LlamaCppServerController.stop() ?: return null
        return llamaStopFailureStatus(targetBackend, failure).also { currentStatus = it }
    }

    internal fun llamaStopFailureStatus(
        targetBackend: BackendKind,
        failure: Throwable,
    ): LocalBackendStatus = LocalBackendStatus(
        backendKind = BackendKind.LLAMA_CPP,
        started = false,
        statusMessage =
            "The existing llama.cpp process did not stop safely " +
                "(${failure.message?.lineSequence()?.firstOrNull().orEmpty().ifBlank { failure.javaClass.simpleName }}). " +
                "Hermes did not start ${targetBackend.persistedValue} or report llama.cpp as stopped. " +
                "Force stop and reopen Hermes before retrying.",
        requiresAppRestart = true,
    )

    internal fun liteRtStopFailureStatus(
        targetBackend: BackendKind,
        failure: Throwable,
    ): LocalBackendStatus = LocalBackendStatus(
        // The requested target never started; the unsafe runtime still belongs to
        // LiteRT-LM, and callers use that identity to distinguish this from a safe
        // transition to NONE.
        backendKind = BackendKind.LITERT_LM,
        started = false,
        statusMessage =
            "The existing LiteRT-LM runtime did not stop safely " +
                "(${failure.message?.lineSequence()?.firstOrNull().orEmpty().ifBlank { failure.javaClass.simpleName }}). " +
                "Hermes did not start ${targetBackend.persistedValue} or report the native runtime as stopped. " +
                "Force stop and reopen Hermes before retrying.",
        requiresAppRestart = true,
    )

    private fun preferredCompletedDownload(context: Context): LocalModelDownloadRecord? {
        val store = LocalModelDownloadStore(context)
        val refreshed = HermesModelDownloadManager.refreshDownloads(context, store)
        val preferredId = store.preferredDownloadId().ifBlank { return null }
        val preferred = refreshed.firstOrNull { it.id == preferredId } ?: store.findDownload(preferredId) ?: return null
        return preferred.takeIf { it.status == "completed" }
    }

    private fun LocalModelDownloadRecord.matchesBackendArtifact(backendKind: BackendKind): Boolean {
        val lower = destinationPath.lowercase(Locale.US)
        return when (backendKind) {
            BackendKind.LLAMA_CPP -> lower.endsWith(".gguf")
            BackendKind.LITERT_LM -> isLiteRtLmArtifactPath(lower)
            BackendKind.AICORE -> isLiteRtLmArtifactPath(lower)
            BackendKind.NONE -> true
        }
    }

    private fun isLiteRtLmArtifactPath(lowerPath: String): Boolean {
        return lowerPath.endsWith(".litertlm") ||
            (lowerPath.endsWith(".task") && !isLiteRtWebTaskArtifact(lowerPath))
    }

    private fun isLiteRtWebTaskArtifact(lowerPath: String): Boolean {
        return lowerPath.endsWith(".task") && (
            lowerPath.endsWith("-web.task") ||
                lowerPath.endsWith("_web.task") ||
                "-web." in lowerPath ||
                "_web." in lowerPath ||
                "/web/" in lowerPath
            )
    }

    private fun inferenceConfigFor(
        preferred: LocalModelDownloadRecord,
        settings: AppSettings,
    ): LiteRtLmOpenAiProxy.InferenceConfig {
        val lower = preferred.modelIdentityText()
        val inputSupport = inferredInputSupport(preferred)
        val modelBytes = runCatching { File(preferred.destinationPath).length() }.getOrDefault(0L)
        val modelDefaults = when {
            "gemma-4" in lower || "gemma4" in lower -> LiteRtLmOpenAiProxy.InferenceConfig(
                topK = 64,
                topP = 0.95f,
                temperature = 1.0f,
                maxTokens = 1024,
                maxContextLength = gemma4DefaultContextTokens(modelBytes),
            )
            "qwen3-0.6b" in lower || "qwen3-0-6b" in lower -> LiteRtLmOpenAiProxy.InferenceConfig(
                topK = 64,
                topP = 0.95f,
                temperature = 1.0f,
                maxTokens = 1024,
            )
            "qwen2.5-1.5b" in lower || "qwen2-5-1-5b" in lower -> LiteRtLmOpenAiProxy.InferenceConfig(
                topK = 20,
                topP = 0.8f,
                temperature = 0.7f,
                maxTokens = 4096,
            )
            "gemma3-1b" in lower || "gemma-3-1b" in lower || "gemma3_1b" in lower -> LiteRtLmOpenAiProxy.InferenceConfig(
                topK = 40,
                topP = 0.95f,
                temperature = 0.7f,
                maxTokens = 1024,
                maxContextLength = 4096,
            )
            "minicpm" in lower -> LiteRtLmOpenAiProxy.InferenceConfig(
                topK = 40,
                topP = 0.9f,
                temperature = 0.7f,
                maxTokens = 1024,
                maxContextLength = 4096,
            )
            "qwen3.5-0.8" in lower || "qwen3-5-0-8" in lower || "0.8b" in lower -> LiteRtLmOpenAiProxy.InferenceConfig(
                topK = 40,
                topP = 0.9f,
                temperature = 0.7f,
                maxTokens = 512,
                maxContextLength = 2048,
            )
            else -> LiteRtLmOpenAiProxy.InferenceConfig()
        }
        return LiteRtLmOpenAiProxy.InferenceConfig(
            topK = AppSettings.normalizeLocalModelTopK(settings.localModelTopK),
            topP = AppSettings.normalizeLocalModelTopP(settings.localModelTopP),
            temperature = AppSettings.normalizeLocalModelTemperature(settings.localModelTemperature),
            maxTokens = AppSettings.normalizeLocalModelMaxTokens(settings.localModelMaxTokens)
                .takeIf { it > 0 }
                ?: modelDefaults.maxTokens,
            maxContextLength = modelDefaults.maxContextLength,
            supportImage = inputSupport.image,
            supportAudio = inputSupport.audio,
            preferredAccelerator = AppSettings.normalizeLocalModelAccelerator(settings.localModelAccelerator),
            speculativeDecodingMode = speculativeDecodingModeFor(settings),
        )
    }

    private fun speculativeDecodingModeFor(settings: AppSettings): LiteRtLmOpenAiProxy.SpeculativeDecodingMode {
        return when (settings.liteRtLmSpeculativeDecodingMode.lowercase(Locale.US)) {
            "enabled", "on", "force" -> LiteRtLmOpenAiProxy.SpeculativeDecodingMode.ENABLED
            "disabled", "off" -> LiteRtLmOpenAiProxy.SpeculativeDecodingMode.DISABLED
            else -> LiteRtLmOpenAiProxy.SpeculativeDecodingMode.AUTO
        }
    }

    internal fun gemma4DefaultContextTokens(modelBytes: Long): Int {
        return when {
            modelBytes >= 6_000_000_000L -> 2_048
            modelBytes >= 3_000_000_000L -> 2_048
            else -> 4_096
        }
    }

    internal fun inferredInputSupport(preferred: LocalModelDownloadRecord): ModelInputSupport {
        // Download records currently carry source identity and runtime flavor, but no
        // content-addressed modality capability proof. Model names such as "gemma-4",
        // "vision", or "audio" are not evidence that this exact bundle contains the
        // corresponding adapters. Start every downloaded LiteRT-LM artifact text-only
        // until the verified-artifact registry and headed release matrix explicitly bind
        // image/audio support for the exact revision, byte count, and SHA-256.
        return ModelInputSupport(
            image = false,
            audio = false,
            policy = "text-only: exact image/audio adapter capabilities are not release-certified",
        )
    }

    private fun LocalModelDownloadRecord.modelIdentityText(): String {
        return listOf(title, repoOrUrl, filePath, destinationFileName, destinationPath)
            .joinToString(" ")
            .lowercase(Locale.US)
    }

    internal sealed class VerifiedArtifactLaneReconciliation {
        data class Ready(
            val settings: AppSettings,
            val persistedRequiredLane: Boolean,
        ) : VerifiedArtifactLaneReconciliation()

        data class PersistenceFailure(
            val requiredLane: String,
            val cause: Throwable,
        ) : VerifiedArtifactLaneReconciliation()

        data class UnsupportedRequiredLane(
            val requiredLane: String,
        ) : VerifiedArtifactLaneReconciliation()
    }

    /**
     * Reconcile a runtime-lane requirement only after the caller has verified exact artifact
     * bytes. Unknown artifacts and verified lane-neutral artifacts retain the user's current
     * lane. A required-lane write must complete durably before its settings can reach launch.
     */
    internal fun reconcileVerifiedArtifactLlamaCppRuntimeLane(
        currentSettings: AppSettings,
        verifiedArtifact: VerifiedLocalModelArtifacts.Artifact?,
        persistRequiredLane: (String) -> AppSettings,
    ): VerifiedArtifactLaneReconciliation {
        val declaredLane = verifiedArtifact?.requiredLlamaCppRuntimeLane
        if (declaredLane == null || declaredLane.isBlank()) {
            return VerifiedArtifactLaneReconciliation.Ready(
                settings = currentSettings,
                persistedRequiredLane = false,
            )
        }
        val normalizedDeclaredLane = declaredLane.trim().lowercase(Locale.US)
        val requiredLane = when (normalizedDeclaredLane) {
            "stable" -> "stable"
            "turboquant", "experimental" -> "turboquant"
            else -> return VerifiedArtifactLaneReconciliation.UnsupportedRequiredLane(
                requiredLane = declaredLane.trim(),
            )
        }
        if (
            AppSettings.normalizeLlamaCppRuntimeLane(currentSettings.llamaCppRuntimeLane) ==
            requiredLane
        ) {
            return VerifiedArtifactLaneReconciliation.Ready(
                settings = currentSettings,
                persistedRequiredLane = false,
            )
        }

        return runCatching { persistRequiredLane(requiredLane) }.fold(
            onSuccess = { persisted ->
                if (persisted.llamaCppRuntimeLane == requiredLane) {
                    VerifiedArtifactLaneReconciliation.Ready(
                        settings = persisted,
                        persistedRequiredLane = true,
                    )
                } else {
                    VerifiedArtifactLaneReconciliation.PersistenceFailure(
                        requiredLane = requiredLane,
                        cause = IllegalStateException(
                            "Required llama.cpp runtime lane was not present after settings persistence",
                        ),
                    )
                }
            },
            onFailure = { error ->
                VerifiedArtifactLaneReconciliation.PersistenceFailure(
                    requiredLane = requiredLane,
                    cause = error,
                )
            },
        )
    }

    /**
     * Commit an exact-artifact lane repair only while its runtime selection is still current.
     *
     * The authority monitor remains held through [AppSettingsStore.update], so a newer selection
     * either invalidates this startup before the write or commits after it. The update still reads
     * the final settings snapshot, preserving unrelated fields changed since artifact verification.
     */
    internal fun persistRequiredLlamaCppRuntimeLaneIfAdmitted(
        settingsStore: AppSettingsStore,
        requiredLane: String,
        admissionCheck: () -> Unit,
    ): AppSettings {
        return LocalModelRuntimeSelectionAuthority.withAdmissionCheck(admissionCheck) {
            settingsStore.update { latest ->
                latest.copy(llamaCppRuntimeLane = requiredLane)
            }
        }
    }

    private data class ArtifactProof(
        val summary: String = "",
        val error: String? = null,
        val verifiedArtifact: VerifiedLocalModelArtifacts.Artifact? = null,
    )

    private fun verifyKnownArtifact(
        context: Context,
        preferred: LocalModelDownloadRecord,
        modelFile: File,
        backendKind: BackendKind,
    ): ArtifactProof {
        val artifact = VerifiedLocalModelArtifacts.find(preferred.repoOrUrl, preferred.filePath)
            ?: VerifiedLocalModelArtifacts.findByFileName(modelFile.name)
            ?: return ArtifactProof()
        val expectedRuntime = when (backendKind) {
            BackendKind.LLAMA_CPP -> "llama.cpp"
            BackendKind.LITERT_LM, BackendKind.AICORE -> "litert-lm"
            BackendKind.NONE -> ""
        }
        if (expectedRuntime.isNotBlank() && artifact.runtime != expectedRuntime) {
            return ArtifactProof(
                error = "Release-matrix artifact ${artifact.fileName} targets ${artifact.runtime}, not ${backendKind.persistedValue}.",
            )
        }
        val verification = VerifiedLocalModelArtifacts.verifyCached(modelFile, artifact)
        if (!verification.valid) {
            return ArtifactProof(error = verification.detail)
        }
        return ArtifactProof(
            summary = "${artifact.repoId}@${artifact.revision}/${artifact.fileName}; " +
                "${artifact.expectedBytes} bytes; SHA-256 ${artifact.sha256}; ${verification.detail}",
            verifiedArtifact = artifact,
        )
    }

    private fun LocalBackendStatus.withArtifactProof(proof: String): LocalBackendStatus {
        if (proof.isBlank()) return this
        return copy(
            artifactSummary = listOf(artifactSummary, proof)
                .filter { it.isNotBlank() }
                .joinToString(" · "),
        )
    }

    private fun artifactVerificationFailure(
        preferred: LocalModelDownloadRecord,
        backendKind: BackendKind,
        error: String,
    ): LocalBackendStatus {
        return LocalBackendStatus(
            backendKind = backendKind,
            started = false,
            sourceModelPath = preferred.destinationPath,
            statusMessage = "Content-addressed artifact verification failed: $error. Delete this file and download the pinned release-matrix revision again.",
            artifactSummary = error,
        ).also { currentStatus = it }
    }

    private inline fun <T> withBackgroundPriorityIfNeeded(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return block()
        }
        val tid = Process.myTid()
        val previousPriority = runCatching { Process.getThreadPriority(tid) }
            .getOrDefault(Process.THREAD_PRIORITY_DEFAULT)
        runCatching { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_BACKGROUND) }
        return try {
            block()
        } finally {
            runCatching { Process.setThreadPriority(tid, previousPriority) }
        }
    }

    private fun incompatiblePreferredDownloadStatus(
        preferred: LocalModelDownloadRecord,
        backendKind: BackendKind,
    ): LocalBackendStatus {
        val lower = preferred.destinationPath.lowercase(Locale.US)
        if (backendKind in setOf(BackendKind.LITERT_LM, BackendKind.AICORE) && isLiteRtWebTaskArtifact(lower)) {
            return LocalBackendStatus(
                backendKind = backendKind,
                started = false,
                sourceModelPath = preferred.destinationPath,
                statusMessage = "Preferred local model ${preferred.destinationFileName} is a web/browser .task FlatBuffer, not an Android LiteRT-LM bundle. Remove it and download the .litertlm artifact instead.",
            ).also { currentStatus = it }
        }
        val requiredExtension = when (backendKind) {
            BackendKind.LLAMA_CPP -> ".gguf"
            BackendKind.LITERT_LM -> ".litertlm or .task"
            BackendKind.AICORE -> ".litertlm or .task"
            BackendKind.NONE -> "supported"
        }
        val backendLabel = when (backendKind) {
            BackendKind.LLAMA_CPP -> "llama.cpp"
            BackendKind.LITERT_LM -> "LiteRT-LM"
            BackendKind.AICORE -> "AICore (NPU)"
            BackendKind.NONE -> "the selected backend"
        }
        return LocalBackendStatus(
            backendKind = backendKind,
            started = false,
            sourceModelPath = preferred.destinationPath,
            statusMessage = "Preferred local model ${preferred.destinationFileName} is not a $requiredExtension file, so $backendLabel cannot load it. Download a $requiredExtension artifact and mark it as preferred first.",
        ).also { currentStatus = it }
    }
}
