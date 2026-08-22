package com.mobilefork.hermesagent.models

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.text.format.Formatter
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.backend.LocalBackendStatus
import com.mobilefork.hermesagent.data.HermesNetworkPolicy
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.device.HermesAndroidHardwareProfile
import com.mobilefork.hermesagent.device.LocalModelRuntimeDiagnostics
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

data class ModelDownloadInspection(
    val title: String,
    val sourceUrl: String,
    val destinationFileName: String,
    val totalBytes: Long,
    val totalBytesLabel: String,
    val deviceRamBytes: Long,
    val deviceRamLabel: String,
    val ramWarning: String,
    val supportsResume: Boolean,
    val abiSummary: String,
    val compatibilityHint: String,
    val resolvedRevision: String = "",
)

data class ModelDownloadDraft(
    val repoOrUrl: String,
    val filePath: String,
    val revision: String,
    val runtimeFlavor: String,
)

data class ModelRemovalResult(
    val removed: Boolean,
    val statusMessage: String,
    val removedRecordIds: Set<String> = emptySet(),
    val requiresAppRestart: Boolean = false,
)

object HermesModelDownloadManager {
    private const val HUGGING_FACE_BASE = "https://huggingface.co"
    private const val HUGGING_FACE_API = "$HUGGING_FACE_BASE/api/models/"
    /**
     * Serializes model filesystem and DownloadManager mutations across every manager/store
     * instance in this process. The only valid nested order is:
     * HermesRuntimeManager -> OnDeviceBackendManager -> MODEL_EXTERNAL_IO_LOCK -> download store.
     * Paths holding this lock must never enter HermesRuntimeManager or acquire runtime ownership.
     */
    private val MODEL_EXTERNAL_IO_LOCK = Any()
    private val LITERT_ALIAS_REPOS = mapOf(
        "google/gemma-4-e2b" to "litert-community/gemma-4-E2B-it-litert-lm",
        "google/gemma-4-e2b-it" to "litert-community/gemma-4-E2B-it-litert-lm",
        "google/gemma-4-e4b" to "litert-community/gemma-4-E4B-it-litert-lm",
        "google/gemma-4-e4b-it" to "litert-community/gemma-4-E4B-it-litert-lm",
        "google/gemma-3-1b-it" to "litert-community/Gemma3-1B-IT",
        "google/gemma-3-4b-it" to "litert-community/Gemma3-4B-IT",
        "google/gemma-3n-e2b-it" to "google/gemma-3n-E2B-it-litert-lm",
        "google/gemma-3n-e4b-it" to "google/gemma-3n-E4B-it-litert-lm",
        "qwen/qwen2.5-1.5b-instruct" to "litert-community/Qwen2.5-1.5B-Instruct",
        "deepseek-ai/deepseek-r1-distill-qwen-1.5b" to "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
        "microsoft/phi-4-mini-instruct" to "litert-community/Phi-4-mini-instruct",
    )
    private val LITERT_ALIAS_REVISIONS = mapOf(
        "litert-community/gemma-4-e2b-it-litert-lm" to "7fa1d78473894f7e736a21d920c3aa80f950c0db",
        "litert-community/gemma-4-e4b-it-litert-lm" to "9695417f248178c63a9f318c6e0c56cb917cb837",
    )
    private val GGUF_RECOMMENDED_REPOS = mapOf(
        "prism-ml/bonsai-27b" to "prism-ml/Bonsai-27B-gguf",
        "prism-ml/bonsai-27b-gguf" to "prism-ml/Bonsai-27B-gguf",
        "qwen/qwen2.5-1.5b-instruct" to "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
        "deepseek-ai/deepseek-r1-distill-qwen-1.5b" to "unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF",
        "microsoft/phi-4-mini-instruct" to "bartowski/microsoft_Phi-4-mini-instruct-GGUF",
        "nvidia/llama-3.1-nemotron-nano-8b-v1" to "bartowski/nvidia_Llama-3.1-Nemotron-Nano-8B-v1-GGUF",
        "nvidia/nemotron-cascade-8b" to "bartowski/nvidia_Nemotron-Cascade-8B-GGUF",
        "nvidia/nemotron-cascade-8b-thinking" to "bartowski/nvidia_Nemotron-Cascade-8B-Thinking-GGUF",
    )
    private val LITERT_SOC_ARTIFACT_TAGS = setOf(
        "mediatek",
        "qualcomm_snapdragon",
        "google_tensor",
        "samsung_exynos",
        "unisoc",
    )
    private val LITERT_GPU_ARTIFACT_TAGS = setOf(
        "adreno",
        "mali",
        "mali_immortalis",
        "powervr_img",
        "xclipse",
    )

    private data class NetworkPolicySnapshot(
        val label: String,
        val isMetered: Boolean,
        val isRoaming: Boolean,
    )

    private data class ResolvedDownloadSource(
        val sourceUrl: String,
        val resolvedFilePath: String,
        val resolvedRepoId: String = "",
        val resolvedRevision: String = "",
        val compatibilityHint: String = "",
    )

    private data class RepoFileSelection(
        val repoId: String,
        val revision: String,
        val filePath: String,
        val compatibilityHint: String = "",
    )

    private data class LiteRtArtifactPreference(
        val socFamily: String,
        val gpuFamily: String,
    )

    fun modelsDirectory(context: Context): File {
        val externalDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve("models")
        if (externalDirectory != null && ensureWritableDirectory(externalDirectory)) {
            return externalDirectory
        }
        return File(context.filesDir, "downloads/models").apply { mkdirs() }
    }

    internal fun modelDiscoveryDirectories(context: Context): List<File> {
        return listOfNotNull(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve("models"),
            context.getExternalFilesDir(null)?.resolve("hermes-home/downloads/models"),
            File(context.filesDir, "hermes-home/downloads/models"),
            File(context.filesDir, "downloads/models"),
        ).distinctBy { directory ->
            runCatching { directory.canonicalPath }.getOrDefault(directory.absolutePath)
        }
    }

    private fun ensureWritableDirectory(directory: File): Boolean {
        return runCatching {
            directory.mkdirs()
            if (!directory.isDirectory || !directory.canWrite()) {
                return@runCatching false
            }
            val probe = File.createTempFile("hermes-write", ".tmp", directory)
            probe.delete()
            true
        }.getOrDefault(false)
    }

    fun importLocalModelFile(
        context: Context,
        store: LocalModelDownloadStore,
        sourceUri: Uri,
    ): LocalModelDownloadRecord = withModelExternalIoOperation {
        val directory = modelsDirectory(context)
        val displayName = displayNameForUri(context, sourceUri)
        val targetFile = directory.resolve(uniqueFileName(directory, sanitizeFileName(displayName)))
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("Unable to read selected model file")
        if (!targetFile.isImportableModelFile()) {
            runCatching { targetFile.delete() }
            throw IllegalArgumentException("Selected file must be a non-empty .gguf, .litertlm, or Android .task model")
        }
        val now = System.currentTimeMillis()
        val record = LocalModelDownloadRecord(
            title = targetFile.name,
            sourceUrl = sourceUri.toString(),
            repoOrUrl = "Local file",
            filePath = displayName,
            revision = "local",
            runtimeFlavor = runtimeFlavorForLocalFile(targetFile),
            destinationFileName = targetFile.name,
            destinationPath = targetFile.absolutePath,
            downloadManagerId = -1L,
            totalBytes = targetFile.length(),
            downloadedBytes = targetFile.length(),
            status = "completed",
            statusMessage = "Imported from phone files",
            supportsResume = false,
            updatedAtEpochMs = now,
        )
        try {
            store.upsertDownload(record)
        } catch (error: Throwable) {
            runCatching { targetFile.delete() }
            throw error
        }
        return record
    }

    fun inspectCandidate(context: Context, draft: ModelDownloadDraft, hfToken: String): ModelDownloadInspection {
        HermesNetworkPolicy.requireExternalNetworkAllowed(
            context,
            draft.repoOrUrl,
            actionLabel = "model inspection",
        )
        val resolvedSource = resolveDownloadSource(draft, hfToken)
        HermesNetworkPolicy.requireExternalNetworkAllowed(
            context,
            resolvedSource.sourceUrl,
            actionLabel = "model inspection",
        )
        val head = headProbe(resolvedSource.sourceUrl, hfToken)
        val totalBytes = head.contentLength.coerceAtLeast(0L)
        val verifiedArtifact = VerifiedLocalModelArtifacts.find(
            repoOrUrl = resolvedSource.resolvedRepoId.ifBlank { draft.repoOrUrl },
            filePathOrName = resolvedSource.resolvedFilePath,
        )
        if (
            verifiedArtifact != null &&
            totalBytes > 0L &&
            totalBytes != verifiedArtifact.expectedBytes
        ) {
            throw IllegalArgumentException(
                "Published artifact size changed for ${verifiedArtifact.repoId}/${verifiedArtifact.fileName}: " +
                    "expected ${verifiedArtifact.expectedBytes} bytes, HEAD reported $totalBytes. " +
                    "Refusing an unreviewed replacement; choose an exact revision or refresh the release matrix.",
            )
        }
        val memory = LocalModelRuntimeDiagnostics.captureMemory(context)
        val runtimeBackend = if (draft.runtimeFlavor.equals("LiteRT-LM", ignoreCase = true)) {
            "litert-lm"
        } else {
            "llama.cpp"
        }
        val preflight = totalBytes.takeIf { it > 0L }?.let { bytes ->
            LocalModelRuntimeDiagnostics.evaluatePreflight(
                backend = runtimeBackend,
                modelBytes = bytes,
                requestedContextTokens = -1,
                memory = memory,
            )
        }
        val ramWarning = when {
            preflight != null && !preflight.allowed ->
                "Not recommended on this device: ${preflight.detail} Downloading remains available, but a normal start will be blocked. Advanced llama.cpp settings offer an explicitly confirmed, dangerous one-shot RAM-check bypass for experiments; it does not bypass file, artifact, or backend compatibility checks."
            preflight?.level == "warning" -> "Memory warning: ${preflight.detail}"
            else -> ""
        }
        val destinationName = destinationFileName(
            repoOrUrl = draft.repoOrUrl,
            filePath = resolvedSource.resolvedFilePath,
            sourceUrl = resolvedSource.sourceUrl,
        )
        return ModelDownloadInspection(
            title = destinationName,
            sourceUrl = resolvedSource.sourceUrl,
            destinationFileName = destinationName,
            totalBytes = totalBytes,
            totalBytesLabel = if (totalBytes > 0) Formatter.formatShortFileSize(context, totalBytes) else "unknown size",
            deviceRamBytes = memory.totalBytes,
            deviceRamLabel = Formatter.formatShortFileSize(context, memory.totalBytes),
            ramWarning = ramWarning,
            supportsResume = head.acceptRanges,
            abiSummary = Build.SUPPORTED_ABIS.joinToString(),
            compatibilityHint = listOf(
                resolvedSource.compatibilityHint,
                verifiedArtifact?.let {
                    "Release-matrix artifact: ${it.expectedBytes} bytes, SHA-256 ${it.sha256}; runtime tests must verify the digest before certification."
                }.orEmpty(),
            ).filter { it.isNotBlank() }.joinToString(" · "),
            resolvedRevision = resolvedSource.resolvedRevision,
        )
    }

    fun enqueueDownload(
        context: Context,
        store: LocalModelDownloadStore,
        draft: ModelDownloadDraft,
        hfToken: String,
        dataSaverMode: Boolean,
        allowMetered: Boolean = !dataSaverMode,
        allowRoaming: Boolean = false,
    ): LocalModelDownloadRecord = withModelExternalIoOperation {
        val record = enqueueDownloadRecord(
            context = context,
            draft = draft,
            hfToken = hfToken,
            dataSaverMode = dataSaverMode,
            allowMetered = allowMetered,
            allowRoaming = allowRoaming,
        )
        try {
            store.upsertDownload(record)
        } catch (error: Throwable) {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            runCatching { downloadManager.remove(record.downloadManagerId) }
            runCatching { File(record.destinationPath).delete() }
            throw error
        }
        return record
    }

    fun restartDownloadOnMobileData(
        context: Context,
        store: LocalModelDownloadStore,
        recordId: String,
        hfToken: String,
    ): LocalModelDownloadRecord? {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return restartDownloadWithOperation(
            store = store,
            recordId = recordId,
            removeSystemDownload = { downloadId -> downloadManager.remove(downloadId) },
            deleteModelFile = File::delete,
            enqueueReplacement = { existing ->
                enqueueDownloadRecord(
                    context = context,
                    draft = ModelDownloadDraft(
                        repoOrUrl = existing.repoOrUrl,
                        filePath = existing.filePath,
                        revision = existing.revision,
                        runtimeFlavor = existing.runtimeFlavor,
                    ),
                    hfToken = hfToken,
                    dataSaverMode = false,
                    allowMetered = true,
                    allowRoaming = true,
                    recordId = existing.id,
                )
            },
        )
    }

    internal fun restartDownloadWithOperation(
        store: LocalModelDownloadStore,
        recordId: String,
        removeSystemDownload: (Long) -> Unit,
        deleteModelFile: (File) -> Boolean,
        enqueueReplacement: (LocalModelDownloadRecord) -> LocalModelDownloadRecord,
    ): LocalModelDownloadRecord? = withModelExternalIoOperation {
        val existing = store.findDownload(recordId) ?: return@withModelExternalIoOperation null
        runCatching { removeSystemDownload(existing.downloadManagerId) }
        runCatching { deleteModelFile(File(existing.destinationPath)) }
        val restarted = enqueueReplacement(existing)
        val replaced = try {
            store.replaceDownloadIfPresent(restarted)
        } catch (error: Throwable) {
            runCatching { removeSystemDownload(restarted.downloadManagerId) }
            runCatching { deleteModelFile(File(restarted.destinationPath)) }
            throw error
        }
        if (!replaced) {
            runCatching { removeSystemDownload(restarted.downloadManagerId) }
            runCatching { deleteModelFile(File(restarted.destinationPath)) }
            return@withModelExternalIoOperation null
        }
        restarted
    }

    private fun enqueueDownloadRecord(
        context: Context,
        draft: ModelDownloadDraft,
        hfToken: String,
        dataSaverMode: Boolean,
        allowMetered: Boolean,
        allowRoaming: Boolean,
        recordId: String = UUID.randomUUID().toString(),
    ): LocalModelDownloadRecord {
        HermesNetworkPolicy.requireExternalNetworkAllowed(
            context,
            draft.repoOrUrl,
            actionLabel = "model download",
        )
        val inspection = inspectCandidate(context, draft, hfToken)
        val targetFile = modelsDirectory(context).resolve(uniqueFileName(modelsDirectory(context), inspection.destinationFileName))
        val request = DownloadManager.Request(Uri.parse(inspection.sourceUrl)).apply {
            setTitle("Hermes model: ${inspection.title}")
            setDescription("Downloading a local model for Hermes")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // setVisibleInDownloadsUi is deprecated since API 33; keeping for backward compat on older devices
            @Suppress("DEPRECATION")
            setVisibleInDownloadsUi(true)
            setAllowedOverRoaming(allowRoaming)
            setAllowedOverMetered(allowMetered)
            if (looksLikeHuggingFaceResource(inspection.sourceUrl) && hfToken.isNotBlank()) {
                addRequestHeader("Authorization", "Bearer $hfToken")
            }
            setDestinationUri(Uri.fromFile(targetFile))
        }
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)
        return LocalModelDownloadRecord(
            id = recordId,
            title = inspection.title,
            sourceUrl = inspection.sourceUrl,
            repoOrUrl = draft.repoOrUrl,
            filePath = draft.filePath,
            revision = inspection.resolvedRevision.ifBlank { draft.revision },
            runtimeFlavor = draft.runtimeFlavor,
            destinationFileName = inspection.destinationFileName,
            destinationPath = targetFile.absolutePath,
            downloadManagerId = downloadId,
            totalBytes = inspection.totalBytes,
            downloadedBytes = 0L,
            status = "queued",
            statusMessage = listOf(
                queuedStatusMessage(
                    context = context,
                    dataSaverMode = dataSaverMode,
                    allowMetered = allowMetered,
                    allowRoaming = allowRoaming,
                ),
                inspection.compatibilityHint,
            ).filter { it.isNotBlank() }.joinToString(" · "),
            ramWarning = inspection.ramWarning,
            supportsResume = inspection.supportsResume,
            allowMetered = allowMetered,
            allowRoaming = allowRoaming,
        )
    }

    fun refreshDownloads(context: Context, store: LocalModelDownloadStore): List<LocalModelDownloadRecord> {
        return withModelExternalIoOperation {
            refreshDownloads(
                store = store,
                refreshSnapshot = { records ->
                    importExistingModelFiles(
                        context = context,
                        records = records.map { refreshRecord(context, it) },
                    )
                },
            )
        }
    }

    /**
     * Refresh work deliberately runs outside the store lock because DownloadManager queries and
     * model-directory scans may block. The single compare-and-set commit includes records plus
     * preferred/pending pointers. Any overlapping mutation advances the revision and forces a
     * refresh from the new state, so a stale snapshot cannot resurrect a removed record.
     */
    internal fun refreshDownloads(
        store: LocalModelDownloadStore,
        refreshSnapshot: (List<LocalModelDownloadRecord>) -> List<LocalModelDownloadRecord>,
        afterSnapshot: (List<LocalModelDownloadRecord>) -> Unit = {},
    ): List<LocalModelDownloadRecord> {
        while (true) {
            val snapshot = store.snapshot()
            afterSnapshot(snapshot.downloads)
            val refreshed = refreshSnapshot(snapshot.downloads)
            val preferredId = repairedPreferredDownloadId(
                preferredId = snapshot.preferredDownloadId,
                records = refreshed,
            )
            val committed = store.compareAndSetSnapshot(
                expectedRevision = snapshot.revision,
                downloads = refreshed,
                preferredDownloadId = preferredId,
                pendingAutoStartRecordId = snapshot.pendingAutoStartRecordId,
            )
            if (committed != null) {
                return committed.downloads
            }
        }
    }

    fun refreshRecord(context: Context, record: LocalModelDownloadRecord): LocalModelDownloadRecord {
        if (record.downloadManagerId < 0L) {
            val file = File(record.destinationPath)
            val present = file.isImportableModelFile()
            return record.copy(
                totalBytes = if (present) file.length().coerceAtLeast(record.totalBytes) else record.totalBytes,
                downloadedBytes = if (present) file.length().coerceAtLeast(record.downloadedBytes) else record.downloadedBytes,
                status = if (present) "completed" else "missing",
                statusMessage = if (present) "Existing model file is present on disk" else "Imported model file is missing on disk",
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(record.downloadManagerId)
        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return record.copy(
                    status = if (File(record.destinationPath).exists()) "completed" else "missing",
                    statusMessage = if (File(record.destinationPath).exists()) "Download file is present on disk" else "Android no longer reports this download",
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
            }
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloadedBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(record.totalBytes)
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)).orEmpty()
            val statusPair = statusLabel(context, record, status, reason)
            return record.copy(
                destinationPath = localUri.removePrefix("file://").ifBlank { record.destinationPath },
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
                status = statusPair.first,
                statusMessage = statusPair.second,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        }
        return record
    }

    fun removeDownload(
        context: Context,
        store: LocalModelDownloadStore,
        recordId: String,
        selectionGeneration: Long? = null,
    ): ModelRemovalResult {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val admissionCheck = {
            selectionGeneration?.let(LocalModelRuntimeSelectionAuthority::requireCurrent)
            Unit
        }
        return removeDownloadWithOwnership(
            store = store,
            recordId = recordId,
            selectionGeneration = selectionGeneration,
            localMutationAuthority = { mutation ->
                HermesRuntimeManager.withSerializedLocalBackendMutation(
                    admissionCheck = admissionCheck,
                    mutation = mutation,
                )
            },
            removeSystemDownload = { downloadId -> downloadManager.remove(downloadId) },
            deleteModelFile = File::delete,
        )
    }

    internal fun removeDownloadWithOwnership(
        store: LocalModelDownloadStore,
        recordId: String,
        selectionGeneration: Long? = null,
        localMutationAuthority: (
            mutation: (
                currentStatus: LocalBackendStatus,
                stopAllLocalBackends: () -> LocalBackendStatus,
            ) -> ModelRemovalResult,
        ) -> ModelRemovalResult,
        removeSystemDownload: (Long) -> Unit,
        deleteModelFile: (File) -> Boolean,
    ): ModelRemovalResult {
        return localMutationAuthority { currentStatus, stopAllLocalBackends ->
            removeDownload(
                store = store,
                recordId = recordId,
                selectionGeneration = selectionGeneration,
                currentLocalStatus = { currentStatus },
                stopAllLocalBackends = stopAllLocalBackends,
                removeSystemDownload = removeSystemDownload,
                deleteModelFile = deleteModelFile,
            )
        }
    }

    /**
     * One removal authority for every model-management surface.
     *
     * Multiple historical DownloadManager records can refer to the same physical model. Remove
     * that set in one store write so a duplicate completed record cannot resurrect a deleted model.
     * A preferred or currently-owned model is first quiesced; an unsafe native stop preserves both
     * the file and every record so the operator can force-stop the app and retry safely.
     */
    internal fun removeDownload(
        store: LocalModelDownloadStore,
        recordId: String,
        selectionGeneration: Long? = null,
        currentLocalStatus: () -> LocalBackendStatus,
        stopAllLocalBackends: () -> LocalBackendStatus,
        removeSystemDownload: (Long) -> Unit,
        deleteModelFile: (File) -> Boolean,
    ): ModelRemovalResult = withModelExternalIoOperation {
        removeDownloadUnderOperation(
            store = store,
            recordId = recordId,
            selectionGeneration = selectionGeneration,
            currentLocalStatus = currentLocalStatus,
            stopAllLocalBackends = stopAllLocalBackends,
            removeSystemDownload = removeSystemDownload,
            deleteModelFile = deleteModelFile,
        )
    }

    private fun removeDownloadUnderOperation(
        store: LocalModelDownloadStore,
        recordId: String,
        selectionGeneration: Long?,
        currentLocalStatus: () -> LocalBackendStatus,
        stopAllLocalBackends: () -> LocalBackendStatus,
        removeSystemDownload: (Long) -> Unit,
        deleteModelFile: (File) -> Boolean,
    ): ModelRemovalResult {
        val snapshot = store.snapshot()
        val records = snapshot.downloads
        val selected = records.firstOrNull { it.id == recordId }
            ?: return ModelRemovalResult(
                removed = false,
                statusMessage = "The model download record no longer exists",
            )
        val selectedCanonicalPath = selected.destinationPath.canonicalPathOrNull()
        val associatedRecords = records.filter { record ->
            record.id == selected.id ||
                (
                    selectedCanonicalPath != null &&
                        record.destinationPath.canonicalPathOrNull() == selectedCanonicalPath
                    )
        }
        val associatedRecordIds = associatedRecords.mapTo(linkedSetOf()) { it.id }
        val localStatus = currentLocalStatus()
        val activeCanonicalPath = localStatus.sourceModelPath.canonicalPathOrNull()
        val removesPreferred = snapshot.preferredDownloadId in associatedRecordIds
        val removesActiveModel = selectedCanonicalPath != null && activeCanonicalPath == selectedCanonicalPath

        if (removesPreferred || removesActiveModel) {
            val stopped = if (localStatus.requiresAppRestart) localStatus else stopAllLocalBackends()
            if (
                stopped.requiresAppRestart ||
                stopped.started ||
                stopped.backendKind != BackendKind.NONE
            ) {
                return ModelRemovalResult(
                    removed = false,
                    statusMessage = stopped.statusMessage.ifBlank {
                        "The active local runtime could not be stopped safely. Force stop and reopen Hermes before removing this model."
                    },
                    requiresAppRestart = true,
                )
            }
        }

        // Persist the fail-closed tombstone before canceling Android DownloadManager work or
        // deleting the file. If this commit fails, the external artifacts remain untouched and
        // the caller can safely retry. A later external cleanup failure may leave an orphaned
        // file, but it cannot leave a durable preferred pointer targeting a deleted artifact.
        val commitTombstone = {
            store.removeDownloadsMatching { record ->
                record.id in associatedRecordIds ||
                    (
                        selectedCanonicalPath != null &&
                            record.destinationPath.canonicalPathOrNull() == selectedCanonicalPath
                        )
            }
        }
        val removedRecordIds = if (selectionGeneration == null) {
            commitTombstone()
        } else {
            // Only this short durable tombstone shares the authority monitor. A newer Use/Start
            // which commits first makes this token stale before deletion; if this tombstone wins,
            // the newer selector observes the record missing. Slow cancel/delete remains outside.
            LocalModelRuntimeSelectionAuthority.withCurrent(selectionGeneration, commitTombstone)
        }

        val downloadIds = associatedRecords
            .map { it.downloadManagerId }
            .filter { it >= 0L }
            .distinct()
        for (downloadId in downloadIds) {
            val cancelFailure = runCatching { removeSystemDownload(downloadId) }.exceptionOrNull()
            if (cancelFailure != null) {
                return ModelRemovalResult(
                    removed = true,
                    statusMessage = "Removed the model selection, but Hermes could not cancel its Android download: " +
                        (cancelFailure.message ?: cancelFailure.javaClass.simpleName),
                    removedRecordIds = associatedRecordIds + removedRecordIds,
                )
            }
        }

        val modelFile = selected.destinationPath.takeIf { it.isNotBlank() }?.let(::File)
        val fileRemoved = modelFile == null || !modelFile.exists() || runCatching {
            deleteModelFile(modelFile) && !modelFile.exists()
        }.getOrDefault(false)
        if (!fileRemoved) {
            return ModelRemovalResult(
                removed = true,
                statusMessage = "Removed the model selection, but Hermes could not delete " +
                    modelFile?.name.orEmpty().ifBlank { "the model file" },
                removedRecordIds = associatedRecordIds + removedRecordIds,
            )
        }
        return ModelRemovalResult(
            removed = true,
            statusMessage = "Removed ${selected.title}",
            removedRecordIds = associatedRecordIds + removedRecordIds,
        )
    }

    fun setPreferredDownload(store: LocalModelDownloadStore, recordId: String): Boolean {
        return store.setPreferredDownloadId(recordId)
    }

    private fun importExistingModelFiles(
        context: Context,
        records: List<LocalModelDownloadRecord>,
    ): List<LocalModelDownloadRecord> {
        val now = System.currentTimeMillis()
        val current = records.toMutableList()
        val knownPaths = current
            .mapNotNull { record -> record.destinationPath.canonicalPathOrNull() }
            .toMutableSet()
        modelDiscoveryDirectories(context)
            .asSequence()
            .flatMap { directory -> directory.listFiles().orEmpty().asSequence() }
            .filter { it.isImportableModelFile() }
            .sortedBy { it.name.lowercase(Locale.US) }
            .forEach { file ->
                val canonicalPath = file.absolutePath.canonicalPathOrNull() ?: return@forEach
                if (canonicalPath in knownPaths) {
                    val index = current.indexOfFirst { it.destinationPath.canonicalPathOrNull() == canonicalPath }
                    if (index >= 0 && current[index].status != "completed") {
                        val size = file.length().coerceAtLeast(current[index].totalBytes)
                        current[index] = current[index].copy(
                            totalBytes = size,
                            downloadedBytes = size,
                            status = "completed",
                            statusMessage = "Existing model file is present on disk",
                            updatedAtEpochMs = now,
                        )
                    }
                    return@forEach
                }
                val size = file.length()
                current += LocalModelDownloadRecord(
                    title = file.name,
                    sourceUrl = file.toURI().toString(),
                    repoOrUrl = "Local file",
                    filePath = file.name,
                    revision = "local",
                    runtimeFlavor = runtimeFlavorForLocalFile(file),
                    destinationFileName = file.name,
                    destinationPath = file.absolutePath,
                    downloadManagerId = -1L,
                    totalBytes = size,
                    downloadedBytes = size,
                    status = "completed",
                    statusMessage = "Imported existing model file from disk",
                    supportsResume = false,
                    updatedAtEpochMs = now,
                )
                knownPaths += canonicalPath
            }
        return current.sortedByDescending { it.updatedAtEpochMs }
    }

    internal fun repairedPreferredDownloadId(
        preferredId: String,
        records: List<LocalModelDownloadRecord>,
        observedFileBytes: (LocalModelDownloadRecord) -> Long = { record ->
            File(record.destinationPath).takeIf { it.isFile }?.length() ?: 0L
        },
    ): String {
        fun ready(record: LocalModelDownloadRecord): Boolean {
            return record.status == "completed" &&
                isImportableModelPath(record.destinationPath, observedFileBytes(record))
        }

        val explicitPreferred = records.firstOrNull { it.id == preferredId }
        if (explicitPreferred != null && ready(explicitPreferred)) {
            // Preserve an explicit operator choice, including an experimental
            // custom import. Only automatic repair is release-certified-only.
            return explicitPreferred.id
        }

        // A refresh may repair records, but it must never publish a new runtime selection.
        // Backend/lane/preferred-model handoffs are owned by LocalModelRuntimeSelectionAuthority.
        return ""
    }

    private fun File.isImportableModelFile(): Boolean {
        if (!isFile || length() <= 0L) {
            return false
        }
        val lower = absolutePath.lowercase(Locale.US)
        return lower.endsWith(".gguf") ||
            lower.endsWith(".litertlm") ||
            (lower.endsWith(".task") && !isLiteRtWebTaskArtifact(lower))
    }

    private fun runtimeFlavorForLocalFile(file: File): String {
        return if (file.name.lowercase(Locale.US).endsWith(".gguf")) "GGUF" else "LiteRT-LM"
    }

    private fun displayNameForUri(context: Context, sourceUri: Uri): String {
        var displayName = sourceUri.lastPathSegment?.substringAfterLast('/').orEmpty()
        context.contentResolver.query(sourceUri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                displayName = cursor.getString(nameIndex).orEmpty().ifBlank { displayName }
            }
        }
        return displayName.ifBlank { "model.bin" }
    }

    private fun isImportableModelPath(path: String, bytes: Long): Boolean {
        if (bytes <= 0L) return false
        val lower = path.lowercase(Locale.US)
        return lower.endsWith(".gguf") ||
            lower.endsWith(".litertlm") ||
            (lower.endsWith(".task") && !isLiteRtWebTaskArtifact(lower))
    }

    private fun String.fileNamePart(): String {
        return substringAfterLast('/').substringAfterLast('\\')
    }

    private fun String.canonicalPathOrNull(): String? {
        if (isBlank()) {
            return null
        }
        return runCatching { File(this).canonicalPath }.getOrNull()
    }

    private fun resolveDownloadSource(draft: ModelDownloadDraft, hfToken: String): ResolvedDownloadSource {
        val trimmed = draft.repoOrUrl.trim()
        val explicitFilePath = draft.filePath.trim().trim('/')
        val requestedRevision = draft.revision.trim().ifBlank { "main" }
        val liteRtArtifactPreference = liteRtArtifactPreferenceForCurrentDevice()
        parseHuggingFaceReference(trimmed)?.let { reference ->
            val resolvedRevision = reference.revision ?: requestedRevision
            val explicitOrReferencedPath = explicitFilePath.ifBlank { reference.filePath.orEmpty() }
            val selection = selectRepoFileForDownload(
                repoId = reference.repoId,
                revision = resolvedRevision,
                runtimeFlavor = draft.runtimeFlavor,
                hfToken = hfToken,
                explicitFilePath = explicitOrReferencedPath,
                liteRtArtifactPreference = liteRtArtifactPreference,
            )
            val pinnedRevision = pinnedArtifactRevision(
                repoId = selection.repoId,
                filePath = selection.filePath,
                selectedRevision = selection.revision,
                requestedRevision = resolvedRevision,
            )
            return ResolvedDownloadSource(
                sourceUrl = "$HUGGING_FACE_BASE/${selection.repoId}/resolve/$pinnedRevision/${selection.filePath}?download=true",
                resolvedFilePath = selection.filePath,
                resolvedRepoId = selection.repoId,
                resolvedRevision = pinnedRevision,
                compatibilityHint = selection.compatibilityHint,
            )
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return ResolvedDownloadSource(
                sourceUrl = trimmed,
                resolvedFilePath = explicitFilePath,
                resolvedRepoId = parseHuggingFaceReference(trimmed)?.repoId.orEmpty(),
                resolvedRevision = parseHuggingFaceReference(trimmed)?.revision.orEmpty(),
                compatibilityHint = compatibilityHintForFile(explicitFilePath.ifBlank { trimmed }, draft.runtimeFlavor, explicitSelection = true),
            )
        }
        val repo = trimmed.removePrefix("hf://").trim('/').ifBlank {
            throw IllegalArgumentException("Enter a Hugging Face repo or a direct model URL")
        }
        val selection = selectRepoFileForDownload(
            repoId = repo,
            revision = requestedRevision,
            runtimeFlavor = draft.runtimeFlavor,
            hfToken = hfToken,
            explicitFilePath = explicitFilePath,
            liteRtArtifactPreference = liteRtArtifactPreference,
        )
        val pinnedRevision = pinnedArtifactRevision(
            repoId = selection.repoId,
            filePath = selection.filePath,
            selectedRevision = selection.revision,
            requestedRevision = requestedRevision,
        )
        return ResolvedDownloadSource(
            sourceUrl = "$HUGGING_FACE_BASE/${selection.repoId}/resolve/$pinnedRevision/${selection.filePath}?download=true",
            resolvedFilePath = selection.filePath,
            resolvedRepoId = selection.repoId,
            resolvedRevision = pinnedRevision,
            compatibilityHint = selection.compatibilityHint,
        )
    }

    private fun parseHuggingFaceReference(repoOrUrl: String): HuggingFaceReference? {
        val trimmed = repoOrUrl.trim()
        if (trimmed.startsWith("hf://")) {
            val repoId = trimmed.removePrefix("hf://").trim('/').ifBlank { return null }
            return HuggingFaceReference(repoId = repoId)
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return HuggingFaceReference(repoId = trimmed.trim('/').ifBlank { return null })
        }
        val uri = Uri.parse(trimmed)
        val host = uri.host.orEmpty().lowercase(Locale.US)
        if (!host.contains("huggingface.co") && !host.contains("hf.co")) {
            return null
        }
        val segments = uri.pathSegments.filter { it.isNotBlank() }
        if (segments.size < 2) {
            return null
        }
        val repoId = "${segments[0]}/${segments[1]}"
        if (segments.size >= 5 && segments[2] in setOf("blob", "resolve")) {
            val filePath = segments.drop(4).joinToString("/")
            return HuggingFaceReference(
                repoId = repoId,
                revision = segments[3].ifBlank { null },
                filePath = filePath.ifBlank { null },
            )
        }
        return HuggingFaceReference(repoId = repoId)
    }

    private fun pinnedArtifactRevision(
        repoId: String,
        filePath: String,
        selectedRevision: String,
        requestedRevision: String,
    ): String {
        if (!requestedRevision.equals("main", ignoreCase = true)) {
            return selectedRevision
        }
        return VerifiedLocalModelArtifacts.find(repoId, filePath)?.revision ?: selectedRevision
    }

    private fun selectRepoFileForDownload(
        repoId: String,
        revision: String,
        runtimeFlavor: String,
        hfToken: String,
        explicitFilePath: String,
        liteRtArtifactPreference: LiteRtArtifactPreference = liteRtArtifactPreferenceForCurrentDevice(),
    ): RepoFileSelection {
        if (explicitFilePath.isNotBlank()) {
            return RepoFileSelection(
                repoId = repoId,
                revision = revision,
                filePath = explicitFilePath,
                compatibilityHint = compatibilityHintForFile(explicitFilePath, runtimeFlavor, explicitSelection = true),
            )
        }
        val siblings = loadRepoFiles(repoId = repoId, revision = revision, hfToken = hfToken)
        val runtimeNative = findCompatibleRepoFile(siblings, runtimeFlavor, liteRtArtifactPreference)
        if (runtimeNative != null) {
            return RepoFileSelection(
                repoId = repoId,
                revision = revision,
                filePath = runtimeNative,
            )
        }
        if (runtimeFlavor.equals("LiteRT-LM", ignoreCase = true)) {
            val aliasRepoId = liteRtAlias(repoId)
            if (!aliasRepoId.isNullOrBlank() && aliasRepoId.lowercase(Locale.US) != repoId.lowercase(Locale.US)) {
                val aliasRevision = LITERT_ALIAS_REVISIONS[aliasRepoId.lowercase(Locale.US)] ?: "main"
                val aliasRuntimeNative = findCompatibleRepoFile(
                    loadRepoFiles(repoId = aliasRepoId, revision = aliasRevision, hfToken = hfToken),
                    runtimeFlavor,
                    liteRtArtifactPreference,
                )
                if (aliasRuntimeNative != null) {
                    return RepoFileSelection(
                        repoId = aliasRepoId,
                        revision = aliasRevision,
                        filePath = aliasRuntimeNative,
                        compatibilityHint = "huggingface.co/$repoId does not publish a native LiteRT-LM artifact, so Hermes will download ${aliasRuntimeNative.substringAfterLast('/')} from the mobile-ready repo $aliasRepoId.",
                    )
                }
            }
            throw IllegalArgumentException(noLiteRtArtifactMessage(repoId, aliasRepoId))
        }
        val fallback = findFallbackRepoFile(siblings)
            ?: throw IllegalArgumentException(noDownloadableArtifactMessage(repoId, runtimeFlavor))
        return RepoFileSelection(
            repoId = repoId,
            revision = revision,
            filePath = fallback,
            compatibilityHint = compatibilityHintForFile(fallback, runtimeFlavor, explicitSelection = false),
        )
    }

    private fun findCompatibleRepoFile(
        paths: List<String>,
        runtimeFlavor: String,
        liteRtArtifactPreference: LiteRtArtifactPreference = liteRtArtifactPreferenceForCurrentDevice(),
    ): String? {
        return paths
            .filter { isCompatibleRepoFile(it, runtimeFlavor) }
            .sortedWith(
                compareBy<String> { compatibleFileRank(it, runtimeFlavor, liteRtArtifactPreference) }
                    .thenBy { it.lowercase(Locale.US) },
            )
            .firstOrNull()
    }

    private fun noLiteRtArtifactMessage(repoId: String, aliasRepoId: String?): String {
        return if (!aliasRepoId.isNullOrBlank()) {
            "huggingface.co/$repoId does not publish a .litertlm or .task file. Hermes recommends $aliasRepoId for LiteRT-LM, or you can enter an exact .litertlm/.task file path."
        } else {
            "huggingface.co/$repoId does not publish a .litertlm or .task file. Enter an exact .litertlm/.task file path or choose a repo that ships LiteRT-LM artifacts."
        }
    }

    private fun noDownloadableArtifactMessage(repoId: String, runtimeFlavor: String): String {
        val recommendedRepoId = ggufRecommendedRepo(repoId)
        return if (runtimeFlavor.equals("GGUF", ignoreCase = true) && !recommendedRepoId.isNullOrBlank()) {
            "Unable to infer a downloadable model artifact from huggingface.co/$repoId for $runtimeFlavor. Try the runtime-native repo $recommendedRepoId, enter an exact file path inside the repo, or paste a direct model URL to try a specific artifact."
        } else {
            "Unable to infer a downloadable model artifact from huggingface.co/$repoId for $runtimeFlavor. Enter an exact file path inside the repo or paste a direct model URL to try a specific artifact."
        }
    }

    private fun compatibilityHintForFile(
        filePath: String,
        runtimeFlavor: String,
        explicitSelection: Boolean,
    ): String {
        if (filePath.isBlank() || isCompatibleRepoFile(filePath, runtimeFlavor)) {
            return ""
        }
        val prefix = if (explicitSelection) {
            "Using the exact file you selected"
        } else {
            "No clear $runtimeFlavor artifact was found, so Hermes selected ${filePath.substringAfterLast('/')}"
        }
        return "$prefix. Downloading is allowed; the selected backend will decide at load time whether it can run this file."
    }

    private fun findFallbackRepoFile(paths: List<String>): String? {
        return paths
            .filter { looksLikeLikelyModelArtifact(it) }
            .sortedWith(compareBy<String> { fallbackFileRank(it) }.thenBy { it.lowercase(Locale.US) })
            .firstOrNull()
    }

    private fun looksLikeLikelyModelArtifact(path: String): Boolean {
        return fallbackFileRank(path) < Int.MAX_VALUE
    }

    private fun fallbackFileRank(path: String): Int {
        val lower = path.lowercase(Locale.US)
        if (isClearlyNonModelArtifact(lower)) {
            return Int.MAX_VALUE
        }
        val shardPenalty = if (Regex("(-\\d{5}-of-\\d{5}|\\.part\\d+of\\d+|\\.part\\d+)").containsMatchIn(lower)) 40 else 0
        val baseRank = when {
            lower.endsWith(".gguf") -> 0
            lower.endsWith(".litertlm") -> 1
            lower.endsWith(".safetensors") -> 2
            lower.endsWith(".bin") -> if ("model" in lower || "weights" in lower) 3 else 6
            lower.endsWith(".onnx") -> 7
            lower.endsWith(".tflite") -> 8
            lower.endsWith(".task") -> 9
            lower.endsWith(".pte") || lower.endsWith(".ptl") -> 10
            lower.endsWith(".pt") || lower.endsWith(".pth") || lower.endsWith(".ckpt") -> 11
            lower.endsWith(".pb") || lower.endsWith(".keras") -> 12
            lower.endsWith(".zip") || lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> 20
            "model" in lower || "weights" in lower -> 25
            else -> Int.MAX_VALUE
        }
        return if (baseRank == Int.MAX_VALUE) baseRank else baseRank + shardPenalty
    }

    private fun isClearlyNonModelArtifact(lowerPath: String): Boolean {
        return lowerPath.endsWith(".md") ||
            lowerPath.endsWith(".txt") ||
            lowerPath.endsWith(".json") ||
            lowerPath.endsWith(".yaml") ||
            lowerPath.endsWith(".yml") ||
            lowerPath.endsWith(".png") ||
            lowerPath.endsWith(".jpg") ||
            lowerPath.endsWith(".jpeg") ||
            lowerPath.endsWith(".gif") ||
            lowerPath.endsWith(".webp") ||
            lowerPath.endsWith(".csv") ||
            lowerPath.endsWith(".tsv") ||
            lowerPath.endsWith(".py") ||
            lowerPath.endsWith(".ipynb") ||
            lowerPath.endsWith(".html") ||
            lowerPath.endsWith(".pdf") ||
            "readme" in lowerPath ||
            "license" in lowerPath ||
            "tokenizer" in lowerPath ||
            "merges" in lowerPath ||
            "vocab" in lowerPath ||
            "special_tokens_map" in lowerPath ||
            "generation_config" in lowerPath ||
            "preprocessor_config" in lowerPath ||
            "processor_config" in lowerPath ||
            "chat_template" in lowerPath ||
            "added_tokens" in lowerPath
    }

    private fun liteRtAlias(repoId: String): String? {
        return LITERT_ALIAS_REPOS[repoId.lowercase(Locale.US)]
    }

    private fun ggufRecommendedRepo(repoId: String): String? {
        return GGUF_RECOMMENDED_REPOS[repoId.lowercase(Locale.US)]
    }

    private fun loadRepoFiles(repoId: String, revision: String, hfToken: String): List<String> {
        val metadata = fetchRepoMetadata(repoId = repoId, revision = revision, hfToken = hfToken)
        val siblings = metadata.optJSONArray("siblings") ?: return emptyList()
        return buildList {
            for (index in 0 until siblings.length()) {
                val item = siblings.optJSONObject(index) ?: continue
                val fileName = item.optString("rfilename").ifBlank { item.optString("path") }
                if (fileName.isNotBlank()) {
                    add(fileName)
                }
            }
        }
    }

    private fun fetchRepoMetadata(repoId: String, revision: String, hfToken: String): JSONObject {
        val revisionEndpoint = if (revision.isBlank() || revision == "main") {
            null
        } else {
            "$HUGGING_FACE_API$repoId/revision/${Uri.encode(revision)}"
        }
        val endpoints = listOfNotNull(revisionEndpoint, "$HUGGING_FACE_API$repoId")
        var lastFailure: Exception? = null
        for (endpoint in endpoints) {
            try {
                return getJson(endpoint, hfToken)
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        throw IllegalArgumentException(lastFailure?.message ?: "Unable to inspect huggingface.co/$repoId")
    }

    private fun getJson(url: String, hfToken: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            if (looksLikeHuggingFaceResource(url) && hfToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $hfToken")
            }
        }
        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val detail = errorBody.take(160).ifBlank { "HTTP $responseCode" }
                throw IllegalArgumentException("Unable to inspect huggingface.co metadata: $detail")
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun isCompatibleRepoFile(path: String, runtimeFlavor: String): Boolean {
        val lower = path.substringBefore('?').lowercase(Locale.US)
        return when (runtimeFlavor.uppercase(Locale.US)) {
            "LITERT-LM" -> lower.endsWith(".litertlm") ||
                (lower.endsWith(".task") && !isLiteRtWebTaskArtifact(lower))
            else -> lower.endsWith(".gguf")
        }
    }

    private fun compatibleFileRank(path: String, runtimeFlavor: String): Int {
        return compatibleFileRank(path, runtimeFlavor, null)
    }

    private fun compatibleFileRank(path: String, runtimeFlavor: String, socFamily: String, gpuFamily: String): Int {
        return compatibleFileRank(
            path = path,
            runtimeFlavor = runtimeFlavor,
            liteRtArtifactPreference = LiteRtArtifactPreference(
                socFamily = socFamily.trim().lowercase(Locale.US),
                gpuFamily = gpuFamily.trim().lowercase(Locale.US),
            ),
        )
    }

    private fun compatibleFileRank(
        path: String,
        runtimeFlavor: String,
        liteRtArtifactPreference: LiteRtArtifactPreference?,
    ): Int {
        val lower = path.lowercase(Locale.US)
        val liteRtSpecificityRank = if (runtimeFlavor.equals("LiteRT-LM", ignoreCase = true)) {
            liteRtArtifactSpecificityRank(lower, liteRtArtifactPreference)
        } else {
            null
        }
        return when (runtimeFlavor.uppercase(Locale.US)) {
            "LITERT-LM" -> when {
                isLiteRtWebTaskArtifact(lower) -> Int.MAX_VALUE
                liteRtSpecificityRank != null -> liteRtSpecificityRank
                lower.endsWith(".litertlm") -> 0
                "q4" in lower || "int4" in lower -> 0
                "q8" in lower || "int8" in lower -> 1
                lower.endsWith(".task") -> 8
                "f32" in lower || "float32" in lower -> 20
                else -> 2
            }
            else -> when {
                "bonsai" in lower && ("q1_0" in lower || "q1-0" in lower) -> 0
                "q4_k_m" in lower -> 1
                "q4_k_s" in lower -> 2
                "iq4" in lower -> 3
                "q5_k_m" in lower -> 4
                "q5" in lower -> 5
                "q6" in lower -> 6
                "q8" in lower -> 7
                "f16" in lower || "bf16" in lower -> 18
                else -> 8
            }
        }
    }

    private fun liteRtArtifactPreferenceForCurrentDevice(): LiteRtArtifactPreference {
        val values = listOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER.orEmpty() else "",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty() else "",
            Build.MANUFACTURER.orEmpty(),
            Build.BRAND.orEmpty(),
            Build.DEVICE.orEmpty(),
            Build.HARDWARE.orEmpty(),
            Build.BOARD.orEmpty(),
            Build.PRODUCT.orEmpty(),
        )
        val profile = HermesAndroidHardwareProfile.classify(values)
        return LiteRtArtifactPreference(profile.socFamily, profile.gpuFamily)
    }

    private fun liteRtArtifactSpecificityRank(
        lowerPath: String,
        liteRtArtifactPreference: LiteRtArtifactPreference?,
    ): Int? {
        val tags = liteRtArtifactTags(lowerPath)
        if (tags.isEmpty()) return null
        val preference = liteRtArtifactPreference
        if (preference == null || (preference.socFamily == "unknown" && preference.gpuFamily == "unknown")) {
            return 30
        }
        val socTags = tags.intersect(LITERT_SOC_ARTIFACT_TAGS)
        val gpuTags = tags.intersect(LITERT_GPU_ARTIFACT_TAGS)
        return when {
            preference.socFamily != "unknown" && preference.socFamily in socTags -> 10
            socTags.isNotEmpty() -> 80
            preference.gpuFamily != "unknown" && preference.gpuFamily in gpuTags -> 12
            gpuTags.isNotEmpty() -> 70
            else -> 30
        }
    }

    private fun liteRtArtifactTags(lowerPath: String): Set<String> {
        val tags = linkedSetOf<String>()
        if (
            "mediatek" in lowerPath ||
            "dimensity" in lowerPath ||
            "helio" in lowerPath ||
            tokenRegex("mtk").containsMatchIn(lowerPath) ||
            Regex("""(^|[/_.-])mt[0-9]{3,}[a-z0-9_+-]*([/_.-]|$)""").containsMatchIn(lowerPath)
        ) {
            tags.add("mediatek")
        }
        if (
            "qualcomm" in lowerPath ||
            "snapdragon" in lowerPath ||
            "qcom" in lowerPath ||
            Regex("""(^|[/_.-])(sm|sdm|msm)[0-9]{3,}[a-z0-9_+-]*([/_.-]|$)""").containsMatchIn(lowerPath)
        ) {
            tags.add("qualcomm_snapdragon")
        }
        if ("tensor" in lowerPath || Regex("""(^|[/_.-])gs[0-9]{3,}[a-z0-9_+-]*([/_.-]|$)""").containsMatchIn(lowerPath)) {
            tags.add("google_tensor")
        }
        if ("exynos" in lowerPath || Regex("""(^|[/_.-])s5e[0-9]{3,}[a-z0-9_+-]*([/_.-]|$)""").containsMatchIn(lowerPath)) {
            tags.add("samsung_exynos")
        }
        if ("unisoc" in lowerPath || "spreadtrum" in lowerPath || Regex("""(^|[/_.-])ums[0-9]{3,}[a-z0-9_+-]*([/_.-]|$)""").containsMatchIn(lowerPath)) {
            tags.add("unisoc")
        }
        if ("immortalis" in lowerPath) tags.add("mali_immortalis")
        if ("mali" in lowerPath || "valhall" in lowerPath || "bifrost" in lowerPath) tags.add("mali")
        if ("adreno" in lowerPath) tags.add("adreno")
        if ("powervr" in lowerPath || "power-vr" in lowerPath || "imgtec" in lowerPath || "rogue" in lowerPath) tags.add("powervr_img")
        if ("xclipse" in lowerPath || "amd-rdna" in lowerPath || "amd_rdna" in lowerPath) tags.add("xclipse")
        return tags
    }

    private fun tokenRegex(token: String): Regex {
        return Regex("""(^|[/_.-])${Regex.escape(token)}([/_.-]|$)""")
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

    private fun headProbe(sourceUrl: String, hfToken: String): HeadProbeResult {
        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "HEAD"
            connectTimeout = 15_000
            readTimeout = 15_000
            if (looksLikeHuggingFaceResource(sourceUrl) && hfToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $hfToken")
            }
        }
        return try {
            HeadProbeResult(
                contentLength = connection.contentLengthLong,
                acceptRanges = connection.getHeaderField("Accept-Ranges").orEmpty().contains("bytes", ignoreCase = true),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun destinationFileName(repoOrUrl: String, filePath: String, sourceUrl: String): String {
        val raw = when {
            filePath.isNotBlank() -> filePath.substringAfterLast('/')
            repoOrUrl.startsWith("http://") || repoOrUrl.startsWith("https://") -> Uri.parse(repoOrUrl).lastPathSegment
            else -> Uri.parse(sourceUrl).lastPathSegment
        }.orEmpty().substringBefore('?')
        return sanitizeFileName(raw.ifBlank { "model.bin" })
    }

    private fun uniqueFileName(directory: File, desiredName: String): String {
        val existing = directory.resolve(desiredName)
        if (!existing.exists()) {
            return desiredName
        }
        val dotIndex = desiredName.lastIndexOf('.')
        val stem = if (dotIndex > 0) desiredName.substring(0, dotIndex) else desiredName
        val ext = if (dotIndex > 0) desiredName.substring(dotIndex) else ""
        var counter = 1
        while (true) {
            val candidate = "$stem-$counter$ext"
            if (!directory.resolve(candidate).exists()) {
                return candidate
            }
            counter += 1
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_").lowercase(Locale.US)
    }

    private fun Long.saturatingMultiply(factor: Long): Long {
        if (this <= 0L || factor <= 0L) {
            return 0L
        }
        return if (this > Long.MAX_VALUE / factor) Long.MAX_VALUE else this * factor
    }

    private fun looksLikeHuggingFaceResource(url: String): Boolean {
        return url.contains("huggingface.co", ignoreCase = true) || url.contains("hf.co", ignoreCase = true)
    }

    private fun activeNetworkPolicySnapshot(context: Context): NetworkPolicySnapshot {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val capabilities = connectivityManager?.getNetworkCapabilities(connectivityManager.activeNetwork)
        if (capabilities == null) {
            return NetworkPolicySnapshot(label = "offline", isMetered = false, isRoaming = false)
        }
        val label = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi‑Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "network"
        }
        val isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val isRoaming = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        return NetworkPolicySnapshot(label = label, isMetered = isMetered, isRoaming = isRoaming)
    }

    private fun queuedStatusMessage(
        context: Context,
        dataSaverMode: Boolean,
        allowMetered: Boolean,
        allowRoaming: Boolean,
    ): String {
        val network = activeNetworkPolicySnapshot(context)
        return when {
            !allowRoaming && network.isRoaming ->
                "Queued while roaming is blocked for this download. Use Wi‑Fi or restart on mobile data to allow roaming."
            dataSaverMode || !allowMetered ->
                "Queued with Data saver mode: large transfers wait for Wi‑Fi / unmetered connectivity"
            else -> "Queued in Android DownloadManager over ${network.label}"
        }
    }

    private fun statusLabel(
        context: Context,
        record: LocalModelDownloadRecord,
        status: Int,
        reason: Int,
    ): Pair<String, String> {
        val network = activeNetworkPolicySnapshot(context)
        return when (status) {
            DownloadManager.STATUS_PENDING -> "queued" to when {
                !record.allowRoaming && network.isRoaming ->
                    "Waiting because roaming downloads are blocked. Restart on mobile data or switch to Wi‑Fi."
                !record.allowMetered && network.isMetered ->
                    "Waiting for Wi‑Fi / unmetered connectivity. Restart on mobile data to continue over cellular."
                else -> "Waiting for Android to start the transfer"
            }
            DownloadManager.STATUS_RUNNING -> "downloading" to "Downloading in the background with system-managed resume support"
            DownloadManager.STATUS_PAUSED -> "paused" to when {
                !record.allowRoaming && network.isRoaming ->
                    "Paused because Android treats the current connection as roaming. Restart on mobile data or switch to Wi‑Fi."
                !record.allowMetered && (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI || network.isMetered) ->
                    "Paused until Wi‑Fi / unmetered connectivity is available. Restart on mobile data below to continue over cellular."
                reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Paused until network connectivity returns"
                reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Paused until Wi‑Fi / unmetered connectivity is available"
                reason == DownloadManager.PAUSED_WAITING_TO_RETRY -> "Paused while Android retries the connection"
                reason == DownloadManager.PAUSED_UNKNOWN -> "Paused by Android"
                else -> "Paused by Android"
            }
            DownloadManager.STATUS_SUCCESSFUL -> "completed" to "Download completed and saved locally"
            DownloadManager.STATUS_FAILED -> "failed" to when (reason) {
                DownloadManager.ERROR_HTTP_DATA_ERROR -> "Network transfer failed"
                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Download failed: insufficient storage"
                DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Download failed: too many redirects"
                DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "Download failed: file already exists"
                else -> "Download failed (reason $reason)"
            }
            else -> "unknown" to "Android reported an unknown download state"
        }
    }

    private inline fun <T> withModelExternalIoOperation(block: () -> T): T {
        return synchronized(MODEL_EXTERNAL_IO_LOCK, block)
    }

    private data class HeadProbeResult(
        val contentLength: Long,
        val acceptRanges: Boolean,
    )

    private data class HuggingFaceReference(
        val repoId: String,
        val revision: String? = null,
        val filePath: String? = null,
    )

    private val GEMMA4_SOURCE_REPOS = setOf(
        "google/gemma-4-e2b",
        "google/gemma-4-e2b-it",
        "google/gemma-4-e4b",
        "google/gemma-4-e4b-it",
        "google/gemma-3-1b-it",
        "google/gemma-3-4b-it",
        "google/gemma-3n-e2b-it",
        "google/gemma-3n-e4b-it",
    )
}
