package com.mobilefork.hermesagent.ui.settings

import android.app.Application
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.HermesNetworkPolicy
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.data.SecureSecretsStore
import com.mobilefork.hermesagent.models.DetectedHfModel
import com.mobilefork.hermesagent.models.HermesModelDownloadManager
import com.mobilefork.hermesagent.models.HuggingFaceModelIndexClient
import com.mobilefork.hermesagent.models.ModelDownloadDraft
import com.mobilefork.hermesagent.models.ModelDownloadInspection
import com.mobilefork.hermesagent.models.VerifiedLocalModelArtifacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LocalModelDownloadItemUi(
    val id: String,
    val title: String,
    val runtimeFlavor: String,
    val progressLabel: String,
    val progressFraction: Float,
    val statusLabel: String,
    val statusMessage: String,
    val ramWarning: String,
    val isPreferred: Boolean,
    val localPath: String,
    val canRestartOnMobileData: Boolean,
    val canOpenSystemDownloads: Boolean,
)

data class RecommendedLocalModelPreset(
    val id: String,
    val title: String,
    val description: String,
    val repoOrUrl: String,
    val filePath: String,
    val revision: String = "main",
    val runtimeFlavor: String,
    val testedLabel: String,
)

data class LocalModelDownloadsUiState(
    val repoOrUrl: String = "",
    val filePath: String = "",
    val revision: String = "main",
    val runtimeFlavor: String = "GGUF",
    val huggingFaceToken: String = "",
    val inspectionStatus: String = "",
    val candidateSummary: String = "",
    val candidateRamWarning: String = "",
    val pendingAutoStartRecordId: String = "",
    val workerCatalogStatus: String = "",
    val detectedModels: List<DetectedHfModel> = emptyList(),
    val selectedDetectedModelId: String = "",
    val downloads: List<LocalModelDownloadItemUi> = emptyList(),
)

class LocalModelDownloadsViewModel internal constructor(
    application: Application,
    private val huggingFaceTokenLoader: () -> String,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        huggingFaceTokenLoader = { SecureSecretsStore(application).loadApiKey("huggingface") },
    )

    private val settingsStore = AppSettingsStore(application)
    private val secretsStore = SecureSecretsStore(application)
    private val downloadStore = LocalModelDownloadStore(application)

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<LocalModelDownloadsUiState> = _uiState.asStateFlow()

    init {
        refreshDownloads()
        viewModelScope.launch {
            while (true) {
                delay(1800)
                if (_uiState.value.downloads.any { item -> item.statusLabel in setOf("queued", "downloading", "paused") }) {
                    refreshDownloads()
                }
            }
        }
    }

    private fun loadInitialState(): LocalModelDownloadsUiState {
        val settings = settingsStore.load()
        val initialRuntimeFlavor = when (settings.onDeviceBackend) {
            "litert-lm" -> "LiteRT-LM"
            else -> "GGUF"
        }
        return LocalModelDownloadsUiState(
            huggingFaceToken = huggingFaceTokenLoader(),
            runtimeFlavor = initialRuntimeFlavor,
            pendingAutoStartRecordId = downloadStore.pendingAutoStartRecordId(),
            workerCatalogStatus = "Tap Refresh catalog to load signed model choices when needed.",
        )
    }

    fun updateRepoOrUrl(value: String) = _uiState.update {
        it.copy(
            repoOrUrl = value,
            inspectionStatus = "",
            candidateSummary = "",
            candidateRamWarning = "",
        )
    }

    fun updateFilePath(value: String) = _uiState.update {
        it.copy(
            filePath = value,
            inspectionStatus = "",
            candidateSummary = "",
            candidateRamWarning = "",
        )
    }

    fun updateRevision(value: String) = _uiState.update {
        it.copy(
            revision = value,
            inspectionStatus = "",
            candidateSummary = "",
            candidateRamWarning = "",
        )
    }

    fun updateRuntimeFlavor(value: String) = _uiState.update {
        it.copy(
            runtimeFlavor = value,
            inspectionStatus = "",
            candidateSummary = "",
            candidateRamWarning = "",
        )
    }

    fun updateHuggingFaceToken(value: String) = _uiState.update {
        it.copy(
            huggingFaceToken = value,
            inspectionStatus = "",
            candidateSummary = "",
            candidateRamWarning = "",
        )
    }

    fun syncSelectedBackend(selectedBackend: String) {
        val runtimeFlavor = when (selectedBackend) {
            "llama.cpp" -> "GGUF"
            "litert-lm" -> "LiteRT-LM"
            else -> _uiState.value.runtimeFlavor
        }
        if (runtimeFlavor != _uiState.value.runtimeFlavor) {
            updateRuntimeFlavor(runtimeFlavor)
        } else {
            _uiState.update {
                it.copy(
                    inspectionStatus = "",
                    candidateSummary = "",
                    candidateRamWarning = "",
                )
            }
        }
    }

    fun saveHuggingFaceToken() {
        val token = _uiState.value.huggingFaceToken.trim()
        secretsStore.saveApiKey("huggingface", token)
        _uiState.update {
            it.copy(
                inspectionStatus = if (token.isBlank()) {
                    "Cleared Hugging Face token"
                } else {
                    "Saved Hugging Face token for private or gated model downloads"
                },
            )
        }
    }

    fun startRecommendedModelDownload(presetId: String, dataSaverMode: Boolean) {
        val preset = recommendedModelPresets.firstOrNull { it.id == presetId } ?: return
        val context = getApplication<Application>()
        _uiState.update {
            it.copy(
                repoOrUrl = preset.repoOrUrl,
                filePath = preset.filePath,
                revision = preset.revision,
                runtimeFlavor = preset.runtimeFlavor,
                inspectionStatus = "Preparing ${preset.title}…",
                candidateSummary = preset.description,
                candidateRamWarning = "",
            )
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val refreshed = HermesModelDownloadManager.refreshDownloads(context, downloadStore)
                    val existing = refreshed.firstOrNull { record ->
                        record.status == "completed" && recordMatchesPreset(record, preset)
                    }
                    if (existing != null) {
                        HermesModelDownloadManager.setPreferredDownload(downloadStore, existing.id)
                        existing
                    } else {
                        HermesModelDownloadManager.enqueueDownload(
                            context = context,
                            store = downloadStore,
                            draft = preset.toDraft(),
                            hfToken = _uiState.value.huggingFaceToken,
                            dataSaverMode = dataSaverMode,
                        )
                    }
                }
            }.onSuccess { record ->
                downloadStore.setPendingAutoStartRecordId(record.id)
                refreshDownloads()
                _uiState.update {
                    it.copy(
                        pendingAutoStartRecordId = record.id,
                        inspectionStatus = if (record.status == "completed") {
                            "${record.title} is already downloaded. Starting runtime…"
                        } else {
                            "Queued ${record.title}; Hermes will start it when Android finishes the download."
                        },
                        candidateSummary = it.candidateSummary.ifBlank { record.statusMessage },
                        candidateRamWarning = record.ramWarning,
                    )
                }
            }.onFailure { error ->
                downloadStore.setPendingAutoStartRecordId("")
                _uiState.update {
                    it.copy(
                        inspectionStatus = error.message ?: error.javaClass.simpleName,
                        pendingAutoStartRecordId = "",
                    )
                }
            }
        }
    }

    fun refreshDetectedModels() {
        _uiState.update {
            it.copy(workerCatalogStatus = "Refreshing signed Hugging Face model catalog…")
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    HermesNetworkPolicy.requireExternalNetworkAllowed(
                        getApplication(),
                        HuggingFaceModelIndexClient.DEFAULT_INDEX_URL,
                        actionLabel = "model catalog refresh",
                    )
                    HuggingFaceModelIndexClient.fetchDetectedModels()
                }
            }.onSuccess { models ->
                _uiState.update { state ->
                    val selectedId = HuggingFaceModelIndexClient.preferredDetectedModelId(
                        models = models,
                        currentSelectionId = state.selectedDetectedModelId,
                    )
                    state.copy(
                        detectedModels = models,
                        selectedDetectedModelId = selectedId,
                        workerCatalogStatus = if (models.isEmpty()) {
                            "Signed catalog loaded, but no downloadable model files were detected yet"
                        } else {
                            "Signed catalog loaded with ${models.size} downloadable model choices"
                        },
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        workerCatalogStatus = "Unable to load signed model catalog: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun importLocalModelFile(uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            _uiState.update { it.copy(inspectionStatus = "Importing local model from phone files…") }
            runCatching {
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    HermesModelDownloadManager.importLocalModelFile(
                        context = context,
                        store = downloadStore,
                        sourceUri = uri,
                    )
                }
            }.onSuccess { record ->
                downloadStore.setPendingAutoStartRecordId("")
                refreshDownloads()
                _uiState.update {
                    it.copy(
                        pendingAutoStartRecordId = "",
                        runtimeFlavor = record.runtimeFlavor,
                        inspectionStatus = "Imported ${record.title} and marked it as the preferred local model.",
                        candidateSummary = "Local file · ${record.runtimeFlavor} · ${Formatter.formatShortFileSize(context, record.totalBytes)}",
                        candidateRamWarning = "",
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(inspectionStatus = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    fun selectDetectedModel(modelId: String) {
        val model = _uiState.value.detectedModels.firstOrNull { it.id == modelId } ?: return
        _uiState.update {
            it.copy(
                selectedDetectedModelId = model.id,
                repoOrUrl = model.repoOrUrl,
                filePath = model.filePath,
                revision = model.revision,
                runtimeFlavor = model.runtimeFlavor,
                inspectionStatus = "",
                candidateSummary = model.summary,
                candidateRamWarning = "",
            )
        }
    }

    fun startDetectedModelDownload(dataSaverMode: Boolean) {
        val model = _uiState.value.detectedModels.firstOrNull { it.id == _uiState.value.selectedDetectedModelId } ?: return
        if (!model.quickStartEligible) {
            downloadStore.setPendingAutoStartRecordId("")
            _uiState.update {
                it.copy(
                    pendingAutoStartRecordId = "",
                    inspectionStatus = "Experimental catalog entries cannot auto-start. Use custom import after verifying the exact revision, size, and runtime compatibility.",
                )
            }
            return
        }
        val context = getApplication<Application>()
        _uiState.update {
            it.copy(
                repoOrUrl = model.repoOrUrl,
                filePath = model.filePath,
                revision = model.revision,
                runtimeFlavor = model.runtimeFlavor,
                inspectionStatus = "Preparing ${model.title} from signed catalog…",
                candidateSummary = model.summary,
                candidateRamWarning = "",
            )
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val refreshed = HermesModelDownloadManager.refreshDownloads(context, downloadStore)
                    val existing = refreshed.firstOrNull { record ->
                        record.status == "completed" && recordMatchesDetectedModel(record, model)
                    }
                    if (existing != null) {
                        HermesModelDownloadManager.setPreferredDownload(downloadStore, existing.id)
                        existing
                    } else {
                        HermesModelDownloadManager.enqueueDownload(
                            context = context,
                            store = downloadStore,
                            draft = model.toDraft(),
                            hfToken = _uiState.value.huggingFaceToken,
                            dataSaverMode = dataSaverMode,
                        )
                    }
                }
            }.onSuccess { record ->
                downloadStore.setPendingAutoStartRecordId(record.id)
                refreshDownloads()
                _uiState.update {
                    it.copy(
                        pendingAutoStartRecordId = record.id,
                        inspectionStatus = if (record.status == "completed") {
                            "${record.title} is already downloaded. Starting runtime…"
                        } else {
                            "Queued ${record.title}; Hermes will start it when Android finishes the download."
                        },
                        candidateSummary = it.candidateSummary.ifBlank { record.statusMessage },
                        candidateRamWarning = record.ramWarning,
                    )
                }
            }.onFailure { error ->
                downloadStore.setPendingAutoStartRecordId("")
                _uiState.update {
                    it.copy(
                        inspectionStatus = error.message ?: error.javaClass.simpleName,
                        pendingAutoStartRecordId = "",
                    )
                }
            }
        }
    }

    fun inspectCandidate(runtimeFlavorOverride: String? = null) {
        val context = getApplication<Application>()
        val state = _uiState.value
        val resolvedRuntimeFlavor = runtimeFlavorOverride?.ifBlank { null } ?: state.runtimeFlavor
        _uiState.update {
            it.copy(
                runtimeFlavor = resolvedRuntimeFlavor,
                inspectionStatus = "Inspecting model candidate…",
                candidateSummary = "",
                candidateRamWarning = "",
            )
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    HermesModelDownloadManager.inspectCandidate(
                        context,
                        draft = ModelDownloadDraft(
                            repoOrUrl = state.repoOrUrl,
                            filePath = state.filePath,
                            revision = state.revision,
                            runtimeFlavor = resolvedRuntimeFlavor,
                        ),
                        hfToken = state.huggingFaceToken,
                    )
                }
            }.onSuccess { inspection ->
                _uiState.update {
                    it.copy(
                        inspectionStatus = "Model candidate inspected",
                        candidateSummary = candidateSummary(context, inspection),
                        candidateRamWarning = inspection.ramWarning,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        inspectionStatus = error.message ?: error.javaClass.simpleName,
                        candidateSummary = "",
                        candidateRamWarning = "",
                    )
                }
            }
        }
    }

    fun startDownload(dataSaverMode: Boolean, runtimeFlavorOverride: String? = null) {
        val context = getApplication<Application>()
        val state = _uiState.value
        val resolvedRuntimeFlavor = runtimeFlavorOverride?.ifBlank { null } ?: state.runtimeFlavor
        _uiState.update {
            it.copy(
                runtimeFlavor = resolvedRuntimeFlavor,
                inspectionStatus = "Preparing download…",
            )
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    HermesModelDownloadManager.enqueueDownload(
                        context = context,
                        store = downloadStore,
                        draft = ModelDownloadDraft(
                            repoOrUrl = state.repoOrUrl,
                            filePath = state.filePath,
                            revision = state.revision,
                            runtimeFlavor = resolvedRuntimeFlavor,
                        ),
                        hfToken = state.huggingFaceToken,
                        dataSaverMode = dataSaverMode,
                    )
                }
            }.onSuccess { record ->
                refreshDownloads()
                _uiState.update {
                    it.copy(
                        inspectionStatus = "Queued ${record.title} in Android DownloadManager",
                        candidateSummary = it.candidateSummary.ifBlank { record.statusMessage },
                        candidateRamWarning = record.ramWarning,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(inspectionStatus = error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    fun refreshDownloads() {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val refreshed = HermesModelDownloadManager.refreshDownloads(context, downloadStore)
            _uiState.update {
                it.copy(
                    downloads = refreshed.toUiItems(context, downloadStore.preferredDownloadId()),
                    pendingAutoStartRecordId = downloadStore.pendingAutoStartRecordId(),
                )
            }
        }
    }

    fun removeDownload(recordId: String) {
        HermesModelDownloadManager.removeDownload(getApplication(), downloadStore, recordId)
        refreshDownloads()
    }

    fun restartDownloadOnMobileData(recordId: String) {
        val restarted = HermesModelDownloadManager.restartDownloadOnMobileData(
            context = getApplication(),
            store = downloadStore,
            recordId = recordId,
            hfToken = _uiState.value.huggingFaceToken,
        )
        refreshDownloads()
        _uiState.update {
            it.copy(
                inspectionStatus = if (restarted != null) {
                    "Restarted ${restarted.title} with mobile data and roaming allowed"
                } else {
                    "Unable to restart this download on mobile data"
                }
            )
        }
    }

    fun openSystemDownloads() {
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            getApplication<Application>().startActivity(intent)
            _uiState.update { it.copy(inspectionStatus = "Opened Android Downloads") }
        } catch (_: ActivityNotFoundException) {
            _uiState.update { it.copy(inspectionStatus = "Android Downloads is not available on this device") }
        }
    }

    fun setPreferredDownload(recordId: String) {
        HermesModelDownloadManager.setPreferredDownload(downloadStore, recordId)
        refreshDownloads()
        _uiState.update { it.copy(inspectionStatus = "Marked this model as the preferred local runtime candidate") }
    }

    fun promoteDownloadedModelForAutoStart(recordId: String) {
        HermesModelDownloadManager.setPreferredDownload(downloadStore, recordId)
        refreshDownloads()
        _uiState.update {
            it.copy(
                inspectionStatus = "Preferred model is ready. Handing off to Hermes runtime…",
            )
        }
    }

    fun completePendingAutoStartHandoff(recordId: String, accepted: Boolean): Boolean {
        if (recordId.isBlank() || _uiState.value.pendingAutoStartRecordId != recordId) {
            return false
        }
        if (!accepted) {
            _uiState.update {
                it.copy(
                    inspectionStatus = "The runtime start handoff was not accepted. Reopen Models to retry safely.",
                )
            }
            return false
        }
        downloadStore.setPendingAutoStartRecordId("")
        _uiState.update {
            it.copy(
                pendingAutoStartRecordId = "",
                inspectionStatus = "Preferred model is ready. Starting Hermes runtime…",
            )
        }
        return true
    }

    private fun candidateSummary(context: Application, inspection: ModelDownloadInspection): String {
        val resumeText = if (inspection.supportsResume) {
            "HTTP range resume is available"
        } else {
            "resume depends on server support"
        }
        return buildString {
            append("File: ")
            append(inspection.destinationFileName)
            append(" · Size: ")
            append(inspection.totalBytesLabel)
            append(" · Phone RAM: ")
            append(inspection.deviceRamLabel)
            append(" · ABIs: ")
            append(inspection.abiSummary)
            append(" · ")
            append(resumeText)
            if (inspection.compatibilityHint.isNotBlank()) {
                append(" · ")
                append(inspection.compatibilityHint)
            }
        }
    }

    private fun RecommendedLocalModelPreset.toDraft(): ModelDownloadDraft {
        return ModelDownloadDraft(
            repoOrUrl = repoOrUrl,
            filePath = filePath,
            revision = revision,
            runtimeFlavor = runtimeFlavor,
        )
    }

    private fun DetectedHfModel.toDraft(): ModelDownloadDraft {
        return ModelDownloadDraft(
            repoOrUrl = repoOrUrl,
            filePath = filePath,
            revision = revision,
            runtimeFlavor = runtimeFlavor,
        )
    }

    private fun List<LocalModelDownloadRecord>.toUiItems(
        context: Application,
        preferredId: String,
    ): List<LocalModelDownloadItemUi> {
        return sortedByDescending { it.updatedAtEpochMs }.map { record ->
            val totalBytes = record.totalBytes.coerceAtLeast(0L)
            val downloadedBytes = record.downloadedBytes.coerceAtLeast(0L)
            val progressFraction = if (totalBytes > 0L) {
                (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
            val progressLabel = if (totalBytes > 0L) {
                val percent = (progressFraction * 100).toInt().coerceIn(0, 100)
                "$percent% · ${Formatter.formatShortFileSize(context, downloadedBytes)} / ${Formatter.formatShortFileSize(context, totalBytes)}"
            } else {
                Formatter.formatShortFileSize(context, downloadedBytes)
            }
            val transientStatus = record.status in setOf("queued", "paused", "downloading")
            LocalModelDownloadItemUi(
                id = record.id,
                title = record.title,
                runtimeFlavor = record.runtimeFlavor,
                progressLabel = progressLabel,
                progressFraction = progressFraction,
                statusLabel = record.status,
                statusMessage = record.statusMessage,
                ramWarning = record.ramWarning,
                isPreferred = preferredId == record.id,
                localPath = record.destinationPath,
                canRestartOnMobileData = transientStatus && (!record.allowMetered || !record.allowRoaming),
                canOpenSystemDownloads = transientStatus,
            )
        }
    }

    companion object {
        internal fun recordMatchesPreset(
            record: LocalModelDownloadRecord,
            preset: RecommendedLocalModelPreset,
        ): Boolean {
            val artifact = VerifiedLocalModelArtifacts.find(preset.repoOrUrl, preset.filePath) ?: return false
            val presetIsExact = preset.revision.equals(artifact.revision, ignoreCase = true) &&
                normalizedRuntime(preset.runtimeFlavor) == artifact.runtime &&
                fileName(preset.filePath).equals(artifact.fileName, ignoreCase = true)
            return presetIsExact && recordMatchesArtifact(record, artifact)
        }

        internal fun recordMatchesDetectedModel(
            record: LocalModelDownloadRecord,
            model: DetectedHfModel,
        ): Boolean {
            val artifact = VerifiedLocalModelArtifacts.find(model.repoOrUrl, model.filePath) ?: return false
            val modelIsExact = model.quickStartEligible &&
                model.releaseCertified &&
                model.immutableRevision &&
                model.revision.equals(artifact.revision, ignoreCase = true) &&
                model.expectedBytes == artifact.expectedBytes &&
                normalizedRuntime(model.runtimeFlavor) == artifact.runtime &&
                fileName(model.filePath).equals(artifact.fileName, ignoreCase = true)
            return modelIsExact && recordMatchesArtifact(record, artifact)
        }

        private fun recordMatchesArtifact(
            record: LocalModelDownloadRecord,
            artifact: VerifiedLocalModelArtifacts.Artifact,
        ): Boolean {
            val repoMatches = VerifiedLocalModelArtifacts.find(record.repoOrUrl, artifact.fileName)
                ?.modelId == artifact.modelId
            val selectedFileMatches = fileName(record.filePath).equals(artifact.fileName, ignoreCase = true)
            val destinationNameMatches = fileName(record.destinationFileName)
                .equals(artifact.fileName, ignoreCase = true)
            val destinationPathMatches = record.destinationPath.isBlank() ||
                fileName(record.destinationPath).equals(artifact.fileName, ignoreCase = true)
            return repoMatches &&
                selectedFileMatches &&
                destinationNameMatches &&
                destinationPathMatches &&
                record.revision.equals(artifact.revision, ignoreCase = true) &&
                normalizedRuntime(record.runtimeFlavor) == artifact.runtime &&
                record.totalBytes == artifact.expectedBytes
        }

        private fun normalizedRuntime(value: String): String {
            return when (value.trim().lowercase()) {
                "gguf", "llama.cpp", "llama-cpp" -> "llama.cpp"
                "litert-lm", "litertlm", "litert lm" -> "litert-lm"
                else -> value.trim().lowercase()
            }
        }

        private fun fileName(path: String): String {
            return path.substringBefore('?').replace('\\', '/').substringAfterLast('/')
        }

        val recommendedModelPresets = listOf(
            RecommendedLocalModelPreset(
                id = "qwen35-08b-q4km-gguf",
                title = "Qwen3.5 0.8B Q4_K_M (GGUF)",
                description = "Small Unsloth GGUF model for fast visible chat replies, file creation, deletion, and native tool-calling validation on phones.",
                repoOrUrl = "unsloth/Qwen3.5-0.8B-GGUF",
                filePath = "Qwen3.5-0.8B-Q4_K_M.gguf",
                revision = VerifiedLocalModelArtifacts.require(
                    "unsloth/Qwen3.5-0.8B-GGUF",
                    "Qwen3.5-0.8B-Q4_K_M.gguf",
                ).revision,
                runtimeFlavor = "GGUF",
                testedLabel = "Unsloth Q4_K_M phone tool-calling",
            ),
            RecommendedLocalModelPreset(
                id = "minicpm5-1b-fable5-q4km-gguf",
                title = "MiniCPM5 1B Claude Opus Fable5 Q4_K_M (GGUF)",
                description = "Compact MiniCPM5 thinking model for the embedded llama.cpp runtime, selected at Q4_K_M for practical phone memory use.",
                repoOrUrl = "GnLOLot/MiniCPM5-1B-Claude-Opus-Fable5-Thinking-GGUF",
                filePath = "MiniCPM5-1B-Claude-Opus-Fable5-Thinking-Q4_K_M.gguf",
                revision = VerifiedLocalModelArtifacts.require(
                    "GnLOLot/MiniCPM5-1B-Claude-Opus-Fable5-Thinking-GGUF",
                    "MiniCPM5-1B-Claude-Opus-Fable5-Thinking-Q4_K_M.gguf",
                ).revision,
                runtimeFlavor = "GGUF",
                testedLabel = "MiniCPM5 llama.cpp compatibility target",
            ),
            RecommendedLocalModelPreset(
                id = "minicpm5-1b-web-litert-lm",
                title = "MiniCPM5 1B mobile (LiteRT-LM)",
                description = "Mobile-oriented MiniCPM5 LiteRT-LM artifact with the shorter web cache and Android-safe chat template.",
                repoOrUrl = "Tdamre/MiniCPM5-1B-litert-lm",
                filePath = "MiniCPM5-1B-web.litertlm",
                revision = VerifiedLocalModelArtifacts.require(
                    "Tdamre/MiniCPM5-1B-litert-lm",
                    "MiniCPM5-1B-web.litertlm",
                ).revision,
                runtimeFlavor = "LiteRT-LM",
                testedLabel = "MiniCPM5 mobile LiteRT-LM compatibility target",
            ),
            RecommendedLocalModelPreset(
                id = "vibethinker-3b-litert-lm",
                title = "VibeThinker 3B (LiteRT-LM)",
                description = "Three-billion-parameter reasoning model converted for the native LiteRT-LM runtime; intended for high-RAM phones and emulators.",
                repoOrUrl = "Tdamre/VibeThinker-3B-litert-lm",
                filePath = "VibeThinker-3B.litertlm",
                revision = VerifiedLocalModelArtifacts.require(
                    "Tdamre/VibeThinker-3B-litert-lm",
                    "VibeThinker-3B.litertlm",
                ).revision,
                runtimeFlavor = "LiteRT-LM",
                testedLabel = "VibeThinker LiteRT-LM compatibility target",
            ),
        )
    }
}
