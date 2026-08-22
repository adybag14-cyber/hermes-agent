package com.mobilefork.hermesagent.ui.settings

import android.app.Application
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsPersistenceException
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.HermesNetworkPolicy
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.data.SecureSecretsStore
import com.mobilefork.hermesagent.models.DetectedHfModel
import com.mobilefork.hermesagent.models.HermesModelDownloadManager
import com.mobilefork.hermesagent.models.HuggingFaceModelIndexClient
import com.mobilefork.hermesagent.models.LocalModelRuntimeSelectionAuthority
import com.mobilefork.hermesagent.models.ModelDownloadDraft
import com.mobilefork.hermesagent.models.ModelDownloadInspection
import com.mobilefork.hermesagent.models.RuntimeSelectionSupersededException
import com.mobilefork.hermesagent.models.VerifiedLocalModelArtifacts
import com.mobilefork.hermesagent.models.clearPendingAutoStartForGeneration
import com.mobilefork.hermesagent.models.persistPreferredModelRuntimeSelection
import com.mobilefork.hermesagent.models.updateRuntimeSelectionSettings
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

sealed interface LocalModelRuntimeHandoffResult {
    data class Accepted(
        val requiredLlamaCppRuntimeLane: String?,
        val selectionGeneration: Long,
    ) : LocalModelRuntimeHandoffResult

    data object Rejected : LocalModelRuntimeHandoffResult
}

internal fun publishPendingAutoStartForGeneration(
    downloadStore: LocalModelDownloadStore,
    recordId: String,
    selectionGeneration: Long,
): Boolean {
    return LocalModelRuntimeSelectionAuthority.withCurrent(selectionGeneration) {
        val published = downloadStore.setPendingAutoStartRecordId(recordId)
        if (!LocalModelRuntimeSelectionAuthority.isCurrent(selectionGeneration)) {
            if (published) {
                runCatching { downloadStore.clearPendingAutoStartRecordId(recordId) }
            }
            throw RuntimeSelectionSupersededException()
        }
        published
    }
}

data class RecommendedLocalModelPreset(
    val id: String,
    val title: String,
    val description: String,
    val repoOrUrl: String,
    val filePath: String,
    val revision: String = "main",
    val runtimeFlavor: String,
    val expectedBytes: Long,
    val sha256: String,
    val testedLabel: String,
    val requiredLlamaCppRuntimeLane: String? = null,
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
    private val settingsStore: AppSettingsStore = AppSettingsStore(application),
    private val downloadStore: LocalModelDownloadStore = LocalModelDownloadStore(application),
    private val localModelFileImporter: (
        Application,
        LocalModelDownloadStore,
        Uri,
    ) -> LocalModelDownloadRecord = { context, store, sourceUri ->
        HermesModelDownloadManager.importLocalModelFile(
            context = context,
            store = store,
            sourceUri = sourceUri,
        )
    },
) : AndroidViewModel(application) {
    internal constructor(
        application: Application,
        huggingFaceTokenLoader: () -> String,
    ) : this(
        application = application,
        huggingFaceTokenLoader = huggingFaceTokenLoader,
        settingsStore = AppSettingsStore(application),
        downloadStore = LocalModelDownloadStore(application),
    )

    constructor(application: Application) : this(
        application = application,
        huggingFaceTokenLoader = { SecureSecretsStore(application).loadApiKey("huggingface") },
    )

    private val secretsStore = SecureSecretsStore(application)

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

    fun startRecommendedModelDownload(presetId: String, dataSaverMode: Boolean): String? {
        if (recommendedModelPresets.none { it.id == presetId }) return null
        val selectionGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        if (!cancelPriorPendingAutoStart(selectionGeneration)) return null
        val preset = selectRecommendedModel(presetId, selectionGeneration) ?: return null
        val context = getApplication<Application>()
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val refreshed = HermesModelDownloadManager.refreshDownloads(context, downloadStore)
                    val existing = refreshed.firstOrNull { record ->
                        record.status == "completed" && recordMatchesPreset(record, preset)
                    }
                    if (existing != null) {
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
                val pendingResult = runCatching {
                    publishPendingAutoStartForGeneration(downloadStore, record.id, selectionGeneration)
                }
                if (pendingResult.exceptionOrNull() is RuntimeSelectionSupersededException) return@onSuccess
                val pendingAccepted = pendingResult.getOrDefault(false)
                val pendingFailure = pendingResult.exceptionOrNull()
                val pendingUiId = if (pendingAccepted) record.id else downloadStore.pendingAutoStartRecordId()
                refreshDownloads(selectionGeneration)
                LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                    _uiState.update {
                        it.copy(
                            pendingAutoStartRecordId = pendingUiId,
                            inspectionStatus = when {
                                pendingFailure != null ->
                                    pendingFailure.message
                                        ?: "Hermes could not persist the pending model handoff"
                                !pendingAccepted ->
                                    "The model record changed before runtime handoff. Refresh Models and try again."
                                record.status == "completed" ->
                                    "${record.title} is already downloaded. Starting runtime…"
                                else ->
                                    "Queued ${record.title}; Hermes will start it when Android finishes the download."
                            },
                            candidateSummary = it.candidateSummary.ifBlank { record.statusMessage },
                            candidateRamWarning = record.ramWarning,
                        )
                    }
                }
            }.onFailure { error ->
                val pendingRecordId = downloadStore.pendingAutoStartRecordId()
                LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                    _uiState.update {
                        it.copy(
                            inspectionStatus = error.message ?: error.javaClass.simpleName,
                            pendingAutoStartRecordId = pendingRecordId,
                        )
                    }
                }
            }
        }
        return preset.requiredLlamaCppRuntimeLane
    }

    /**
     * Apply all runtime requirements synchronously before a recommended artifact can be queued.
     * Multi-gigabyte Android downloads can outlive this ViewModel, so the requirement is persisted
     * rather than kept only in transient UI state.
     */
    internal fun selectRecommendedModel(
        presetId: String,
        selectionGeneration: Long = LocalModelRuntimeSelectionAuthority.beginAction(),
    ): RecommendedLocalModelPreset? {
        val preset = recommendedModelPresets.firstOrNull { it.id == presetId } ?: return null
        if (!applyRequiredLlamaCppRuntimeLane(preset.requiredLlamaCppRuntimeLane, selectionGeneration)) return null
        if (!LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
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
            }
        ) return null
        return preset
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
        val ownedPendingAutoStartIntent = downloadStore.pendingAutoStartIntent()
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
                    localModelFileImporter(context, downloadStore, uri)
                }
            }.onSuccess { record ->
                val pendingClearFailure = if (ownedPendingAutoStartIntent == null) {
                    null
                } else {
                    runCatching {
                        downloadStore.clearPendingAutoStartIntent(ownedPendingAutoStartIntent)
                    }.exceptionOrNull()
                }
                refreshDownloads()
                _uiState.update {
                    it.copy(
                        pendingAutoStartRecordId = downloadStore.pendingAutoStartRecordId(),
                        runtimeFlavor = record.runtimeFlavor,
                        inspectionStatus = if (pendingClearFailure == null) {
                            "Imported ${record.title}. Use & Start to make it the preferred local model."
                        } else {
                            "Imported ${record.title}, but ${pendingClearFailure.message ?: "Hermes could not persist pending-handoff cleanup"}"
                        },
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

    fun selectDetectedModel(modelId: String): String? {
        val model = _uiState.value.detectedModels.firstOrNull { it.id == modelId } ?: return null
        val selectionGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        val requiredLane = recommendedPresetFor(
            repoOrUrl = model.repoOrUrl,
            filePath = model.filePath,
            revision = model.revision,
            runtimeFlavor = model.runtimeFlavor,
            expectedBytes = model.expectedBytes,
        )?.requiredLlamaCppRuntimeLane
        if (!applyRequiredLlamaCppRuntimeLane(requiredLane, selectionGeneration)) return null
        if (!LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
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
        ) return null
        return requiredLane
    }

    fun startDetectedModelDownload(dataSaverMode: Boolean): String? {
        val model = _uiState.value.detectedModels.firstOrNull {
            it.id == _uiState.value.selectedDetectedModelId
        } ?: return null
        if (!model.quickStartEligible) {
            _uiState.update {
                it.copy(
                    pendingAutoStartRecordId = downloadStore.pendingAutoStartRecordId(),
                    inspectionStatus = "Experimental catalog entries cannot auto-start. Use custom import after verifying the exact revision, size, and runtime compatibility.",
                )
            }
            return null
        }
        val selectionGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        if (!cancelPriorPendingAutoStart(selectionGeneration)) return null
        val requiredLane = recommendedPresetFor(
            repoOrUrl = model.repoOrUrl,
            filePath = model.filePath,
            revision = model.revision,
            runtimeFlavor = model.runtimeFlavor,
            expectedBytes = model.expectedBytes,
        )?.requiredLlamaCppRuntimeLane
        if (!applyRequiredLlamaCppRuntimeLane(requiredLane, selectionGeneration)) return null
        val context = getApplication<Application>()
        if (!LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
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
            }
        ) return null
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val refreshed = HermesModelDownloadManager.refreshDownloads(context, downloadStore)
                    val existing = refreshed.firstOrNull { record ->
                        record.status == "completed" && recordMatchesDetectedModel(record, model)
                    }
                    if (existing != null) {
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
                val pendingResult = runCatching {
                    publishPendingAutoStartForGeneration(downloadStore, record.id, selectionGeneration)
                }
                if (pendingResult.exceptionOrNull() is RuntimeSelectionSupersededException) return@onSuccess
                val pendingAccepted = pendingResult.getOrDefault(false)
                val pendingFailure = pendingResult.exceptionOrNull()
                val pendingUiId = if (pendingAccepted) record.id else downloadStore.pendingAutoStartRecordId()
                refreshDownloads(selectionGeneration)
                LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                    _uiState.update {
                        it.copy(
                            pendingAutoStartRecordId = pendingUiId,
                            inspectionStatus = when {
                                pendingFailure != null ->
                                    pendingFailure.message
                                        ?: "Hermes could not persist the pending model handoff"
                                !pendingAccepted ->
                                    "The model record changed before runtime handoff. Refresh Models and try again."
                                record.status == "completed" ->
                                    "${record.title} is already downloaded. Starting runtime…"
                                else ->
                                    "Queued ${record.title}; Hermes will start it when Android finishes the download."
                            },
                            candidateSummary = it.candidateSummary.ifBlank { record.statusMessage },
                            candidateRamWarning = record.ramWarning,
                        )
                    }
                }
            }.onFailure { error ->
                val pendingRecordId = downloadStore.pendingAutoStartRecordId()
                LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                    _uiState.update {
                        it.copy(
                            inspectionStatus = error.message ?: error.javaClass.simpleName,
                            pendingAutoStartRecordId = pendingRecordId,
                        )
                    }
                }
            }
        }
        return requiredLane
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

    fun refreshDownloads(selectionGeneration: Long? = null) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                HermesModelDownloadManager.refreshDownloads(context, downloadStore)
            }.onSuccess { refreshed ->
                val preferredId = downloadStore.preferredDownloadId()
                val pendingId = downloadStore.pendingAutoStartRecordId()
                val items = refreshed.toUiItems(context, preferredId)
                val publish = {
                    _uiState.update {
                        it.copy(
                            downloads = items,
                            pendingAutoStartRecordId = pendingId,
                        )
                    }
                }
                if (selectionGeneration == null) publish()
                else LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration, publish)
            }.onFailure { error ->
                val publish = {
                    _uiState.update {
                        it.copy(
                            inspectionStatus = error.message
                                ?: "Hermes could not persist the refreshed model download state",
                        )
                    }
                }
                if (selectionGeneration == null) publish()
                else LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration, publish)
            }
        }
    }

    fun removeDownload(recordId: String) {
        val selectionGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
            _uiState.update { it.copy(inspectionStatus = "Stopping any active local runtime before removing the model…") }
        }
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                LocalModelRuntimeSelectionAuthority.performLongIfCurrent(selectionGeneration) {
                    val result = HermesModelDownloadManager.removeDownload(
                        context,
                        downloadStore,
                        recordId,
                        selectionGeneration = selectionGeneration,
                    )
                    val refreshed = HermesModelDownloadManager.refreshDownloads(context, downloadStore)
                    result to refreshed
                }
            }.onSuccess { (result, refreshed) ->
                val preferredId = downloadStore.preferredDownloadId()
                val pendingId = downloadStore.pendingAutoStartRecordId()
                val items = refreshed.toUiItems(context, preferredId)
                LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                    _uiState.update {
                        it.copy(
                            downloads = items,
                            pendingAutoStartRecordId = pendingId,
                            inspectionStatus = result.statusMessage,
                        )
                    }
                }
            }.onFailure { error ->
                if (error is RuntimeSelectionSupersededException) return@onFailure
                LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                    _uiState.update {
                        it.copy(
                            inspectionStatus = error.message
                                ?: "Hermes could not persist the model removal state",
                        )
                    }
                }
            }
        }
    }

    fun restartDownloadOnMobileData(recordId: String) {
        val restartedResult = runCatching {
            HermesModelDownloadManager.restartDownloadOnMobileData(
                context = getApplication(),
                store = downloadStore,
                recordId = recordId,
                hfToken = _uiState.value.huggingFaceToken,
            )
        }
        val restartFailure = restartedResult.exceptionOrNull()
        if (restartFailure != null) {
            _uiState.update {
                it.copy(
                    inspectionStatus = restartFailure.message
                        ?: "Hermes could not persist the restarted model download",
                )
            }
            return
        }
        val restarted = restartedResult.getOrNull()
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

    fun setPreferredDownload(recordId: String): LocalModelRuntimeHandoffResult {
        val record = downloadStore.findDownload(recordId) ?: return LocalModelRuntimeHandoffResult.Rejected
        val requiredLane = requiredLlamaCppRuntimeLaneForDownload(record)
        val backendKind = backendKindForRuntimeFlavor(record.runtimeFlavor)
            ?: return LocalModelRuntimeHandoffResult.Rejected
        val selectionGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        val preferredResult = runCatching {
            persistPreferredModelRuntimeSelection(
                settingsStore = settingsStore,
                downloadStore = downloadStore,
                recordId = recordId,
                backendKind = backendKind,
                requiredLlamaCppRuntimeLane = requiredLane,
                selectionGeneration = selectionGeneration,
            )
        }
        val preferredFailure = preferredResult.exceptionOrNull()
        if (preferredFailure != null) {
            if (preferredFailure is RuntimeSelectionSupersededException) {
                return LocalModelRuntimeHandoffResult.Rejected
            }
            LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                _uiState.update {
                    it.copy(
                        inspectionStatus = preferredFailure.message
                            ?: "Hermes could not persist the preferred model",
                    )
                }
            }
            return LocalModelRuntimeHandoffResult.Rejected
        }
        if (preferredResult.getOrDefault(false).not()) {
            refreshDownloads(selectionGeneration)
            LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                _uiState.update { it.copy(inspectionStatus = "This model record no longer exists") }
            }
            return LocalModelRuntimeHandoffResult.Rejected
        }
        refreshDownloads(selectionGeneration)
        if (!LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                _uiState.update { it.copy(inspectionStatus = "Marked this model as the preferred local runtime candidate") }
            }
        ) return LocalModelRuntimeHandoffResult.Rejected
        return LocalModelRuntimeHandoffResult.Accepted(requiredLane, selectionGeneration)
    }

    fun promoteDownloadedModelForAutoStart(recordId: String): LocalModelRuntimeHandoffResult {
        // Reapply at the actual handoff boundary in case the process was recreated or settings
        // changed while Android DownloadManager was fetching the artifact.
        val record = downloadStore.findDownload(recordId) ?: return LocalModelRuntimeHandoffResult.Rejected
        val requiredLane = requiredLlamaCppRuntimeLaneForDownload(record)
        val backendKind = backendKindForRuntimeFlavor(record.runtimeFlavor)
            ?: return LocalModelRuntimeHandoffResult.Rejected
        val selectionGeneration = LocalModelRuntimeSelectionAuthority.beginActionIf {
            downloadStore.pendingAutoStartRecordId() == recordId
        } ?: return LocalModelRuntimeHandoffResult.Rejected
        val preferredResult = runCatching {
            persistPreferredModelRuntimeSelection(
                settingsStore = settingsStore,
                downloadStore = downloadStore,
                recordId = recordId,
                backendKind = backendKind,
                requiredLlamaCppRuntimeLane = requiredLane,
                selectionGeneration = selectionGeneration,
                clearSupersededPendingAutoStart = false,
                expectedPendingAutoStartRecordId = recordId,
            )
        }
        val preferredFailure = preferredResult.exceptionOrNull()
        if (preferredFailure != null) {
            if (preferredFailure is RuntimeSelectionSupersededException) {
                return LocalModelRuntimeHandoffResult.Rejected
            }
            LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                _uiState.update {
                    it.copy(
                        inspectionStatus = preferredFailure.message
                            ?: "Hermes could not persist the preferred model handoff",
                    )
                }
            }
            return LocalModelRuntimeHandoffResult.Rejected
        }
        if (preferredResult.getOrDefault(false).not()) {
            refreshDownloads(selectionGeneration)
            LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                _uiState.update { it.copy(inspectionStatus = "This model record no longer exists") }
            }
            return LocalModelRuntimeHandoffResult.Rejected
        }
        refreshDownloads(selectionGeneration)
        if (!LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                _uiState.update {
                    it.copy(
                        inspectionStatus = "Preferred model is ready. Handing off to Hermes runtime…",
                    )
                }
            }
        ) return LocalModelRuntimeHandoffResult.Rejected
        return LocalModelRuntimeHandoffResult.Accepted(requiredLane, selectionGeneration)
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
        val clearedResult = runCatching {
            downloadStore.clearPendingAutoStartRecordId(recordId)
        }
        val clearFailure = clearedResult.exceptionOrNull()
        if (clearFailure != null) {
            _uiState.update {
                it.copy(
                    inspectionStatus = clearFailure.message
                        ?: "Hermes could not persist completion of the model handoff",
                )
            }
            return false
        }
        if (clearedResult.getOrDefault(false).not()) {
            val currentPendingId = downloadStore.pendingAutoStartRecordId()
            _uiState.update {
                it.copy(
                    pendingAutoStartRecordId = currentPendingId,
                    inspectionStatus = "The pending model handoff changed. Refresh Models and try again.",
                )
            }
            return false
        }
        _uiState.update {
            it.copy(
                pendingAutoStartRecordId = "",
                inspectionStatus = "Preferred model is ready. Starting Hermes runtime…",
            )
        }
        return true
    }

    private fun requiredLlamaCppRuntimeLaneForDownload(record: LocalModelDownloadRecord): String? {
        val preset = recommendedModelPresets.firstOrNull { candidate ->
            recordMatchesPreset(record, candidate)
        } ?: return null
        return preset.requiredLlamaCppRuntimeLane
    }

    private fun cancelPriorPendingAutoStart(selectionGeneration: Long): Boolean {
        val clearResult = runCatching {
            clearPendingAutoStartForGeneration(downloadStore, selectionGeneration)
        }
        val failure = clearResult.exceptionOrNull()
        if (failure is RuntimeSelectionSupersededException) return false
        if (failure != null || clearResult.getOrDefault(false).not()) {
            LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                _uiState.update {
                    it.copy(
                        inspectionStatus = failure?.message
                            ?: "Hermes could not cancel the previous pending model handoff",
                    )
                }
            }
            return false
        }
        return LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
            _uiState.update { it.copy(pendingAutoStartRecordId = "") }
        }
    }

    private fun applyRequiredLlamaCppRuntimeLane(
        requiredLane: String?,
        selectionGeneration: Long = LocalModelRuntimeSelectionAuthority.beginAction(),
    ): Boolean {
        val requested = requiredLane?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return true
        val normalized = AppSettings.normalizeLlamaCppRuntimeLane(requested)
        require(normalized == requested) {
            "Recommended model declared unsupported llama.cpp runtime lane: $requiredLane"
        }
        return try {
            updateRuntimeSelectionSettings(settingsStore, selectionGeneration) { current ->
                if (current.llamaCppRuntimeLane == normalized) {
                    current
                } else {
                    current.copy(llamaCppRuntimeLane = normalized)
                }
            }
            true
        } catch (_: RuntimeSelectionSupersededException) {
            false
        } catch (error: AppSettingsPersistenceException) {
            LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                _uiState.update {
                    it.copy(
                        inspectionStatus = error.message
                            ?: "Hermes could not persist the required llama.cpp runtime lane",
                    )
                }
            }
            false
        }
    }

    private fun backendKindForRuntimeFlavor(runtimeFlavor: String): BackendKind? {
        return when (runtimeFlavor.trim().lowercase()) {
            "gguf", "llama.cpp", "llama-cpp", "llama_cpp" -> BackendKind.LLAMA_CPP
            "litert-lm", "litert_lm", "litertlm" -> BackendKind.LITERT_LM
            else -> null
        }
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
                fileName(preset.filePath).equals(artifact.fileName, ignoreCase = true) &&
                preset.expectedBytes == artifact.expectedBytes &&
                preset.sha256.equals(artifact.sha256, ignoreCase = true)
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

        private fun recommendedPresetFor(
            repoOrUrl: String,
            filePath: String,
            revision: String,
            runtimeFlavor: String,
            expectedBytes: Long?,
        ): RecommendedLocalModelPreset? {
            val artifact = VerifiedLocalModelArtifacts.find(repoOrUrl, filePath) ?: return null
            return recommendedModelPresets.firstOrNull { preset ->
                VerifiedLocalModelArtifacts.find(preset.repoOrUrl, preset.filePath)?.modelId == artifact.modelId &&
                    fileName(preset.filePath).equals(fileName(filePath), ignoreCase = true) &&
                    preset.revision.equals(revision, ignoreCase = true) &&
                    normalizedRuntime(preset.runtimeFlavor) == normalizedRuntime(runtimeFlavor) &&
                    expectedBytes == preset.expectedBytes
            }
        }

        private fun verifiedRecommendedModelPreset(
            id: String,
            title: String,
            description: String,
            repoOrUrl: String,
            filePath: String,
            testedLabel: String,
            requiredLlamaCppRuntimeLane: String? = null,
        ): RecommendedLocalModelPreset {
            val artifact = VerifiedLocalModelArtifacts.require(repoOrUrl, filePath)
            return RecommendedLocalModelPreset(
                id = id,
                title = title,
                description = description,
                repoOrUrl = artifact.repoId,
                filePath = artifact.fileName,
                revision = artifact.revision,
                runtimeFlavor = if (artifact.runtime == "litert-lm") "LiteRT-LM" else "GGUF",
                expectedBytes = artifact.expectedBytes,
                sha256 = artifact.sha256,
                testedLabel = testedLabel,
                requiredLlamaCppRuntimeLane = requiredLlamaCppRuntimeLane,
            )
        }

        val recommendedModelPresets = listOf(
            verifiedRecommendedModelPreset(
                id = "qwen35-08b-q4km-gguf",
                title = "Qwen3.5 0.8B Q4_K_M (GGUF)",
                description = "Small Unsloth GGUF model for fast visible chat replies, file creation, deletion, and native tool-calling validation on phones.",
                repoOrUrl = "unsloth/Qwen3.5-0.8B-GGUF",
                filePath = "Qwen3.5-0.8B-Q4_K_M.gguf",
                testedLabel = "Unsloth Q4_K_M phone tool-calling",
            ),
            verifiedRecommendedModelPreset(
                id = "nanbeige4.2-3b-q4-k-m",
                title = "Nanbeige4.2 3B Q4_K_M (GGUF · TurboQuant)",
                description = "Exact Tdamre Nanbeige4.2 3B Q4_K_M publisher artifact. It requires and selects the opt-in TurboQuant llama.cpp lane because Stable cannot load the legacy nanbeige architecture.",
                repoOrUrl = "Tdamre/Nanbeige4.2-3B-GGUF",
                filePath = "Nanbeige4.2-3B-Q4_K_M.gguf",
                testedLabel = "Tdamre exact Q4_K_M · TurboQuant required",
                requiredLlamaCppRuntimeLane = "turboquant",
            ),
            verifiedRecommendedModelPreset(
                id = "minicpm5-1b-fable5-q4km-gguf",
                title = "MiniCPM5 1B Claude Opus Fable5 Q4_K_M (GGUF)",
                description = "Compact MiniCPM5 thinking model for the embedded llama.cpp runtime, selected at Q4_K_M for practical phone memory use.",
                repoOrUrl = "GnLOLot/MiniCPM5-1B-Claude-Opus-Fable5-Thinking-GGUF",
                filePath = "MiniCPM5-1B-Claude-Opus-Fable5-Thinking-Q4_K_M.gguf",
                testedLabel = "MiniCPM5 llama.cpp compatibility target",
            ),
            verifiedRecommendedModelPreset(
                id = "minicpm5-1b-web-litert-lm",
                title = "MiniCPM5 1B mobile (LiteRT-LM)",
                description = "Mobile-oriented MiniCPM5 LiteRT-LM artifact with the shorter web cache and Android-safe chat template.",
                repoOrUrl = "Tdamre/MiniCPM5-1B-litert-lm",
                filePath = "MiniCPM5-1B-web.litertlm",
                testedLabel = "MiniCPM5 mobile LiteRT-LM compatibility target",
            ),
            verifiedRecommendedModelPreset(
                id = "vibethinker-3b-litert-lm",
                title = "VibeThinker 3B (LiteRT-LM)",
                description = "Three-billion-parameter reasoning model converted for the native LiteRT-LM runtime; intended for high-RAM phones and emulators.",
                repoOrUrl = "Tdamre/VibeThinker-3B-litert-lm",
                filePath = "VibeThinker-3B.litertlm",
                testedLabel = "VibeThinker LiteRT-LM compatibility target",
            ),
        )
    }
}
