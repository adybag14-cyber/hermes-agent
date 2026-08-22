package com.mobilefork.hermesagent.models

/**
 * ModelManagerViewModel following Google AI Edge Gallery pattern.
 *
 * Manages the model catalog, download lifecycle, and initialization state
 * for on-device inference models. Provides a unified StateFlow that the UI
 * observes for model status changes.
 *
 * Key design decisions:
 * - Catalog is static + extensible via user downloads (LocalModelDownloadsViewModel)
 * - ModelState tracks: NOT_AVAILABLE -> DOWNLOADING -> DOWNLOADED -> READY / INIT_FAILED
 * - Initialization is lazy — models are only initialized when first needed
 * - Supports both llama.cpp (GGUF) and LiteRT-LM backends
 *
 * Based on: https://github.com/google-ai-edge/gallery
 * ModelManagerViewModel.kt pattern from Google AI Edge Gallery.
 */
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.data.SecureSecretsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Represents a model available for on-device inference.
 * Combines catalog metadata with runtime state.
 */
data class ManagedModel(
    val catalogEntry: ModelCatalogEntry,
    val state: ModelState = ModelState.NOT_AVAILABLE,
    val downloadProgress: ModelDownloadProgress? = null,
    val localFilePath: String? = null,
    val errorMessage: String? = null,
) {
    val isReady: Boolean = state == ModelState.READY
    val isDownloaded: Boolean = state in setOf(ModelState.DOWNLOADED, ModelState.READY)
    val isDownloading: Boolean = state == ModelState.DOWNLOADING
    val isFailed: Boolean = state == ModelState.INIT_FAILED

    /** Percentage of download complete (0-100) */
    val downloadPercentage: Int
        get() = downloadProgress?.percentage ?: (if (isDownloaded) 100 else 0)

    /** Human-readable status label */
    val statusLabel: String
        get() = when (state) {
            ModelState.NOT_AVAILABLE -> "Not downloaded"
            ModelState.DOWNLOADING -> "Downloading ${downloadPercentage}%"
            ModelState.DOWNLOADED -> "Downloaded"
            ModelState.READY -> "Ready"
            ModelState.INIT_FAILED -> "Init failed"
            ModelState.REMOVED -> "Removed"
        }
}

/**
 * Aggregate UI state for the ModelManager.
 */
data class ModelManagerUiState(
    /** Catalog of all available models */
    val models: List<ManagedModel> = emptyList(),

    /** Currently selected backend (llama.cpp or LiteRT-LM) */
    val selectedBackend: BackendKind = BackendKind.NONE,

    /** Model currently active for inference (if any) */
    val activeModelId: String? = null,

    /** Overall system status message */
    val systemMessage: String = "",

    /** Error message if initialization failed */
    val systemError: String? = null,

    /** Whether any model downloads are in progress */
    val hasActiveDownloads: Boolean = false,

    /** Filter applied to catalog (null = all) */
    val filter: ModelCatalogFilter? = null,

    /** Search query text */
    val searchText: String = "",
)

internal fun isModelRuntimeReady(
    status: com.mobilefork.hermesagent.backend.LocalBackendStatus,
    expectedBackend: BackendKind? = null,
    expectedModelPath: String? = null,
): Boolean {
    val backendMatches = expectedBackend == null || status.backendKind == expectedBackend
    val pathMatches = expectedModelPath == null || runCatching {
        java.io.File(status.sourceModelPath).canonicalPath == java.io.File(expectedModelPath).canonicalPath
    }.getOrDefault(false)
    return status.started && status.completionVerified && backendMatches && pathMatches
}

/** Filters for browsing the model catalog */
enum class ModelCatalogFilter {
    ALL,
    MOBILE_RECOMMENDED,
    DOWNLOADED,
    READY,
    LITERT_LM_ONLY,
    LLAMA_CPP_ONLY,
}

class ModelManagerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val settingsStore = AppSettingsStore(app)
    private val downloadStore = LocalModelDownloadStore(app)
    private val secretsStore = SecureSecretsStore(app)

    /** The model catalog — pre-defined entries for known models */
    private val catalog: List<ModelCatalogEntry> = buildDefaultCatalog()

    /** Mutable UI state */
    private val _uiState = MutableStateFlow(ModelManagerUiState())
    val uiState: StateFlow<ModelManagerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            refreshState()
        }
    }

    // =========================================================================
    // State Management
    // =========================================================================

    /** Refresh all model states from disk and backend */
    private suspend fun refreshState(selectionGeneration: Long? = null) {
        val (backend, managedModels) = withContext(Dispatchers.IO) {
            val settings = settingsStore.load()
            val backend = BackendKind.fromPersistedValue(settings.onDeviceBackend)

            val managedModels = catalog.map { entry ->
                val record = findDownloadRecordForModel(entry.id)
                ManagedModel(
                    catalogEntry = entry,
                    state = inferModelState(entry, record),
                    downloadProgress = inferDownloadProgress(record),
                    localFilePath = record?.destinationPath,
                )
            }
            backend to managedModels
        }
        val publish = {
            _uiState.update {
                it.copy(
                    models = managedModels,
                    selectedBackend = backend,
                    hasActiveDownloads = managedModels.any { it.isDownloading },
                    systemMessage = buildSystemMessage(backend, managedModels),
                )
            }
        }
        if (selectionGeneration == null) {
            publish()
        } else {
            LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration, publish)
        }
    }

    // =========================================================================
    // Catalog Operations
    // =========================================================================

    /** Apply a filter to the catalog */
    fun setFilter(filter: ModelCatalogFilter?) {
        _uiState.update {
            it.copy(filter = filter)
        }
    }

    /** Update search text */
    fun setSearchText(text: String) {
        _uiState.update {
            it.copy(searchText = text)
        }
    }

    /** Get filtered catalog */
    fun getFilteredModels(): List<ManagedModel> {
        val state = _uiState.value
        return state.models.filter { model ->
            val passesFilter = when (state.filter) {
                ModelCatalogFilter.MOBILE_RECOMMENDED -> model.catalogEntry.isMobileRecommended
                ModelCatalogFilter.DOWNLOADED -> model.isDownloaded
                ModelCatalogFilter.READY -> model.isReady
                ModelCatalogFilter.LITERT_LM_ONLY -> model.catalogEntry.supportedBackends.contains(ModelRuntimeBackend.LITERT_LM)
                ModelCatalogFilter.LLAMA_CPP_ONLY -> model.catalogEntry.supportedBackends.contains(ModelRuntimeBackend.LLAMA_CPP)
                ModelCatalogFilter.ALL, null -> true
            }
            val passesSearch = state.searchText.isBlank() ||
                model.catalogEntry.displayName.contains(state.searchText, ignoreCase = true) ||
                model.catalogEntry.description.contains(state.searchText, ignoreCase = true) ||
                model.catalogEntry.tags.any { it.contains(state.searchText, ignoreCase = true) }
            passesFilter && passesSearch
        }
    }

    // =========================================================================
    // Model Lifecycle
    // =========================================================================

    /** Start downloading a model from the catalog */
    fun downloadModel(modelId: String) {
        val entry = catalog.find { it.id == modelId } ?: return
        val selectionGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        val requirementFailure = runCatching {
            entry.requiredLlamaCppRuntimeLane?.let { requiredLane ->
                updateRuntimeSelectionSettings(settingsStore, selectionGeneration) { current ->
                    if (current.llamaCppRuntimeLane == requiredLane) {
                        current
                    } else {
                        current.copy(llamaCppRuntimeLane = requiredLane)
                    }
                }
            }
        }.exceptionOrNull()
        if (requirementFailure is RuntimeSelectionSupersededException) return
        if (requirementFailure != null) {
            val message = requirementFailure.message ?: "Unable to persist the model runtime requirement"
            _uiState.update {
                it.copy(
                    models = it.models.map { managed ->
                        if (managed.catalogEntry.id == modelId) {
                            managed.copy(state = ModelState.INIT_FAILED, errorMessage = message)
                        } else managed
                    },
                    systemMessage = "Download not started: $message",
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                models = it.models.map { m ->
                    if (m.catalogEntry.id == modelId) {
                        m.copy(state = ModelState.DOWNLOADING)
                    } else m
                },
                hasActiveDownloads = true,
                systemMessage = "Starting download for ${entry.displayName}…",
            )
        }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    HermesModelDownloadManager.enqueueDownload(
                        context = app,
                        store = downloadStore,
                        draft = ModelDownloadDraft(
                            repoOrUrl = entry.repoId,
                            filePath = entry.filePath,
                            revision = entry.revision,
                            runtimeFlavor = if (entry.supportedBackends.contains(ModelRuntimeBackend.LITERT_LM)) "LiteRT-LM" else "GGUF",
                        ),
                        hfToken = secretsStore.loadApiKey("huggingface"),
                        dataSaverMode = false,
                    )
                }
            }.onSuccess { record ->
                _uiState.update {
                    it.copy(
                        systemMessage = "Download queued: ${record.title}",
                    )
                }
                // Monitor download progress
                monitorDownload(record.id, modelId)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        models = it.models.map { m ->
                            if (m.catalogEntry.id == modelId) {
                                m.copy(
                                    state = ModelState.INIT_FAILED,
                                    errorMessage = error.message ?: "Download failed",
                                )
                            } else m
                        },
                        systemMessage = "Download failed: ${error.message}",
                    )
                }
            }
        }
    }

    /** Monitor download progress for a specific model */
    private fun monitorDownload(recordId: String, modelId: String) {
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(2000)
                val record = downloadStore.findDownload(recordId) ?: break
                val isComplete = record.status == "completed"
                val isFailed = record.status == "failed" || record.status == "canceled"

                val progress = ModelDownloadProgress(
                    modelId = modelId,
                    downloadedBytes = record.downloadedBytes,
                    totalBytes = record.totalBytes,
                    progressFraction = if (record.totalBytes > 0) {
                        record.downloadedBytes.toFloat() / record.totalBytes.toFloat()
                    } else 0f,
                    status = record.status,
                )

                val newState = when {
                    isComplete -> ModelState.DOWNLOADED
                    isFailed -> ModelState.INIT_FAILED
                    record.status in setOf("queued", "downloading", "paused") -> ModelState.DOWNLOADING
                    else -> ModelState.NOT_AVAILABLE
                }

                _uiState.update {
                    it.copy(
                        models = it.models.map { m ->
                            if (m.catalogEntry.id == modelId) {
                                m.copy(
                                    state = newState,
                                    downloadProgress = progress,
                                    localFilePath = record.destinationPath.takeIf { it.isNotBlank() },
                                )
                            } else m
                        },
                        hasActiveDownloads = !isComplete && !isFailed,
                        systemMessage = if (isComplete) {
                            "Download complete for ${record.title}"
                        } else if (isFailed) {
                            "Download failed for ${record.title}"
                        } else {
                            "Downloading ${record.title}… ${progress.percentage}%"
                        },
                    )
                }

                if (isComplete || isFailed) break
            }
        }
    }

    /** Initialize a model for inference (loads into runtime) */
    fun initializeModel(modelId: String) {
        val model = _uiState.value.models.find { it.catalogEntry.id == modelId } ?: return

        if (!model.isDownloaded) {
            _uiState.update {
                it.copy(systemMessage = "Model ${model.catalogEntry.displayName} is not downloaded yet")
            }
            return
        }

        if (model.localFilePath == null) return
        val entry = model.catalogEntry
        val selectionGeneration = LocalModelRuntimeSelectionAuthority.beginAction()

        if (!LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                _uiState.update {
                    it.copy(
                        models = it.models.map { m ->
                            if (m.catalogEntry.id == modelId) {
                                m.copy(state = ModelState.DOWNLOADING)
                            } else m
                        },
                        systemMessage = "Initializing ${entry.displayName}…",
                    )
                }
            }
        ) return

        viewModelScope.launch {
            val backendKind = if (entry.supportedBackends.contains(ModelRuntimeBackend.LITERT_LM)) {
                BackendKind.LITERT_LM
            } else {
                BackendKind.LLAMA_CPP
            }
            val statusResult = runCatching {
                withContext(Dispatchers.IO) {
                    val record = findDownloadRecordForModel(modelId)
                        ?: return@withContext (
                            com.mobilefork.hermesagent.backend.LocalBackendStatus(
                                backendKind = backendKind,
                                started = false,
                                statusMessage = "The completed download record for ${entry.displayName} is missing",
                            ) to null
                            )
                    check(
                        persistPreferredModelRuntimeSelection(
                            settingsStore = settingsStore,
                            downloadStore = downloadStore,
                            recordId = record.id,
                            backendKind = backendKind,
                            requiredLlamaCppRuntimeLane = entry.requiredLlamaCppRuntimeLane,
                            selectionGeneration = selectionGeneration,
                        ),
                    ) {
                        "The completed download record for ${entry.displayName} no longer exists"
                    }
                    val (runtimeState, localStatus) = LocalModelRuntimeSelectionAuthority.performLongIfCurrent(
                        selectionGeneration,
                    ) {
                        HermesRuntimeManager.restartAfterRemoteStop(
                            app,
                            admissionCheck = {
                                LocalModelRuntimeSelectionAuthority.requireCurrent(selectionGeneration)
                            },
                        ) to OnDeviceBackendManager.currentStatus()
                    }
                    if (runtimeState.started) {
                        localStatus to record.destinationPath
                    } else {
                        localStatus.copy(
                            backendKind = backendKind,
                            started = false,
                            statusMessage = runtimeState.error
                                ?: localStatus.statusMessage.ifBlank { "The local runtime did not start" },
                            completionVerified = false,
                        ) to record.destinationPath
                    }
                }
            }
            val statusFailure = statusResult.exceptionOrNull()
            if (statusFailure != null) {
                if (statusFailure is RuntimeSelectionSupersededException) return@launch
                val message = statusFailure.message ?: "Unable to persist the local model selection"
                LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                    _uiState.update {
                        it.copy(
                            models = it.models.map { managed ->
                                if (managed.catalogEntry.id == modelId) {
                                    managed.copy(state = ModelState.INIT_FAILED, errorMessage = message)
                                } else managed
                            },
                            activeModelId = null,
                            selectedBackend = backendKind,
                            systemMessage = "Failed to initialize ${entry.displayName}: $message",
                        )
                    }
                }
                return@launch
            }
            val (status, expectedModelPath) = statusResult.getOrThrow()
            val success = isModelRuntimeReady(status, backendKind, expectedModelPath)

            if (success) {
                LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                    _uiState.update {
                        it.copy(
                            models = it.models.map { m ->
                                if (m.catalogEntry.id == modelId) {
                                    m.copy(state = ModelState.READY)
                                } else m
                            },
                            activeModelId = modelId,
                            selectedBackend = backendKind,
                            systemMessage = "${entry.displayName} initialized and produced a verified completion",
                        )
                    }
                }
            } else {
                LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                    _uiState.update {
                        it.copy(
                            models = it.models.map { m ->
                                if (m.catalogEntry.id == modelId) {
                                    m.copy(
                                        state = ModelState.INIT_FAILED,
                                        errorMessage = status.statusMessage.ifBlank { "Model initialization failed" },
                                    )
                                } else m
                            },
                            activeModelId = null,
                            selectedBackend = backendKind,
                            systemMessage = "Failed to initialize ${entry.displayName}: " +
                                status.statusMessage.ifBlank { "no completion-verified backend became ready" },
                        )
                    }
                }
            }
        }
    }

    /** Remove a model from the device */
    fun removeModel(modelId: String) {
        val model = _uiState.value.models.find { it.catalogEntry.id == modelId } ?: return
        val record = findDownloadRecordForModel(modelId)
        if (record == null) {
            _uiState.update {
                it.copy(systemMessage = "The download record for ${model.catalogEntry.displayName} no longer exists")
            }
            return
        }
        val selectionGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
            _uiState.update { it.copy(systemMessage = "Stopping any active local runtime before removing ${model.catalogEntry.displayName}…") }
        }
        viewModelScope.launch {
            val resultOutcome = runCatching {
                withContext(Dispatchers.IO) {
                    LocalModelRuntimeSelectionAuthority.performLongIfCurrent(selectionGeneration) {
                        HermesModelDownloadManager.removeDownload(
                            app,
                            downloadStore,
                            record.id,
                            selectionGeneration = selectionGeneration,
                        )
                    }
                }
            }
            val removalFailure = resultOutcome.exceptionOrNull()
            if (removalFailure != null) {
                if (removalFailure is RuntimeSelectionSupersededException) return@launch
                LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                    _uiState.update {
                        it.copy(
                            systemMessage = removalFailure.message
                                ?: "Hermes could not persist removal of ${model.catalogEntry.displayName}",
                        )
                    }
                }
                return@launch
            }
            val result = resultOutcome.getOrThrow()
            if (!result.removed) {
                LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                    _uiState.update { it.copy(systemMessage = result.statusMessage) }
                }
                return@launch
            }
            LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                _uiState.update {
                    it.copy(
                        models = it.models.map { managed ->
                            if (managed.catalogEntry.id == modelId) {
                                managed.copy(
                                    state = ModelState.NOT_AVAILABLE,
                                    localFilePath = null,
                                    downloadProgress = null,
                                    errorMessage = null,
                                )
                            } else managed
                        },
                        activeModelId = if (it.activeModelId == modelId) null else it.activeModelId,
                        systemMessage = result.statusMessage,
                    )
                }
            }
        }
    }

    /** Set this model as preferred for the backend */
    fun setPreferred(modelId: String) {
        val entry = catalog.find { it.id == modelId } ?: return
        val record = findDownloadRecordForModel(modelId) ?: return

        // Sync backend kind based on model
        val backendKind = if (entry.supportedBackends.contains(ModelRuntimeBackend.LITERT_LM)) {
            BackendKind.LITERT_LM
        } else {
            BackendKind.LLAMA_CPP
        }
        val selectionGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        val preferenceFailure = runCatching {
            check(
                persistPreferredModelRuntimeSelection(
                    settingsStore = settingsStore,
                    downloadStore = downloadStore,
                    recordId = record.id,
                    backendKind = backendKind,
                    requiredLlamaCppRuntimeLane = entry.requiredLlamaCppRuntimeLane,
                    selectionGeneration = selectionGeneration,
                ),
            ) {
                "The download record for ${entry.displayName} no longer exists"
            }
        }.exceptionOrNull()
        if (preferenceFailure != null) {
            if (preferenceFailure is RuntimeSelectionSupersededException) return
            LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                _uiState.update {
                    it.copy(
                        systemMessage = preferenceFailure.message
                            ?: "Hermes could not persist the preferred model",
                    )
                }
            }
            return
        }

        LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
            _uiState.update {
                it.copy(
                    selectedBackend = backendKind,
                    systemMessage = "${entry.displayName} set as preferred model",
                )
            }
        }
    }

    // =========================================================================
    // Backend Operations
    // =========================================================================

    /** Start the backend server with the active model */
    fun startBackend() {
        val activeModel = _uiState.value.models.find { it.catalogEntry.id == _uiState.value.activeModelId }
            ?: run {
                _uiState.update { it.copy(systemMessage = "No active model selected") }
                return
            }

        if (!activeModel.isReady) {
            _uiState.update { it.copy(systemMessage = "Model is not ready for inference") }
            return
        }
        val selectionGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        if (!cancelPendingAutoStartForBackendAction(selectionGeneration)) return

        LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
            _uiState.update {
                it.copy(systemMessage = "Starting backend server…")
            }
        }

        viewModelScope.launch {
            val runtimeResult = runCatching {
                withContext(Dispatchers.IO) {
                    LocalModelRuntimeSelectionAuthority.performLongIfCurrent(selectionGeneration) {
                        HermesRuntimeManager.restartAfterRemoteStop(
                            app,
                            admissionCheck = {
                                LocalModelRuntimeSelectionAuthority.requireCurrent(selectionGeneration)
                            },
                        )
                    }
                }
            }
            val runtimeFailure = runtimeResult.exceptionOrNull()
            if (runtimeFailure is RuntimeSelectionSupersededException) return@launch
            if (runtimeFailure != null) {
                LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                    _uiState.update { it.copy(systemMessage = runtimeFailure.message ?: "Backend server did not start") }
                }
                return@launch
            }
            val runtimeState = runtimeResult.getOrThrow()
            if (!runtimeState.started) {
                LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                    _uiState.update {
                        it.copy(systemMessage = runtimeState.error ?: "Backend server did not start")
                    }
                }
                return@launch
            }
            refreshState(selectionGeneration)
        }
    }

    /** Stop the backend server */
    fun stopBackend() {
        val selectionGeneration = LocalModelRuntimeSelectionAuthority.beginAction()
        if (!cancelPendingAutoStartForBackendAction(selectionGeneration)) return
        LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
            _uiState.update { it.copy(systemMessage = "Stopping backend server…") }
        }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    LocalModelRuntimeSelectionAuthority.performLongIfCurrent(selectionGeneration) {
                        HermesRuntimeManager.stopLocalRuntime(
                            admissionCheck = {
                                LocalModelRuntimeSelectionAuthority.requireCurrent(selectionGeneration)
                            },
                        )
                    }
                }
            }
            val failure = result.exceptionOrNull()
            if (failure is RuntimeSelectionSupersededException) return@launch
            LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                _uiState.update {
                    val status = result.getOrNull()
                    when {
                        failure != null -> it.copy(
                            systemMessage = failure.message ?: "Backend server did not stop",
                        )
                        status != null && !status.requiresAppRestart -> it.copy(
                            systemMessage = "Backend server stopped",
                            activeModelId = null,
                        )
                        else -> it.copy(systemMessage = status?.statusMessage ?: "Backend server did not stop")
                    }
                }
            }
        }
    }

    // =========================================================================
    // Internal Helpers
    // =========================================================================

    private fun cancelPendingAutoStartForBackendAction(selectionGeneration: Long): Boolean {
        val clearResult = runCatching {
            clearPendingAutoStartForGeneration(downloadStore, selectionGeneration)
        }
        val failure = clearResult.exceptionOrNull()
        if (failure is RuntimeSelectionSupersededException) return false
        if (failure != null || clearResult.getOrDefault(false).not()) {
            LocalModelRuntimeSelectionAuthority.runIfCurrent(selectionGeneration) {
                _uiState.update {
                    it.copy(
                        systemMessage = failure?.message
                            ?: "Hermes could not cancel the pending model handoff",
                    )
                }
            }
            return false
        }
        return true
    }

    private fun findDownloadRecordForModel(modelId: String): LocalModelDownloadRecord? {
        val entry = catalog.find { it.id == modelId } ?: return null
        val verifiedArtifact = entry.filePath.takeIf { it.isNotBlank() }?.let { filePath ->
            VerifiedLocalModelArtifacts.find(entry.repoId, filePath)
        }
        if (verifiedArtifact != null) {
            return downloadStore.loadDownloads().find { record ->
                VerifiedLocalModelArtifacts.find(record.repoOrUrl, record.filePath)?.modelId ==
                    verifiedArtifact.modelId &&
                    record.revision.equals(verifiedArtifact.revision, ignoreCase = true) &&
                    record.destinationFileName.equals(verifiedArtifact.fileName, ignoreCase = true) &&
                    record.totalBytes == verifiedArtifact.expectedBytes
            }
        }
        // Preserve legacy fuzzy matching for unpinned catalog entries.
        return downloadStore.loadDownloads().find { record ->
            record.title.contains(entry.displayName, ignoreCase = true) ||
                record.destinationPath.contains(entry.displayName.lowercase(Locale.US), true)
        }
    }

    private fun inferModelState(entry: ModelCatalogEntry, record: LocalModelDownloadRecord?): ModelState {
        if (record == null) return ModelState.NOT_AVAILABLE
        return when (record.status) {
            "completed" -> ModelState.DOWNLOADED
            "queued", "downloading", "paused" -> ModelState.DOWNLOADING
            "failed", "canceled" -> ModelState.INIT_FAILED
            else -> ModelState.NOT_AVAILABLE
        }
    }

    private fun inferDownloadProgress(record: LocalModelDownloadRecord?): ModelDownloadProgress? {
        if (record == null) return null
        val entry = catalog.find {
            record.title.contains(it.displayName, ignoreCase = true)
        } ?: return null
        return ModelDownloadProgress(
            modelId = entry.id,
            downloadedBytes = record.downloadedBytes,
            totalBytes = record.totalBytes,
            progressFraction = if (record.totalBytes > 0) {
                record.downloadedBytes.toFloat() / record.totalBytes.toFloat()
            } else 0f,
            status = record.status,
        )
    }

    private fun buildSystemMessage(backend: BackendKind, models: List<ManagedModel>): String {
        val readyModels = models.filter { it.isReady }
        val downloadingModels = models.count { it.isDownloading }

        return when {
            readyModels.isNotEmpty() && downloadingModels > 0 ->
                "${readyModels.size} model(s) ready, $downloadingModels downloading"
            readyModels.isNotEmpty() ->
                "${readyModels.size} model(s) ready for inference"
            downloadingModels > 0 ->
                "$downloadingModels model(s) downloading"
            backend == BackendKind.NONE ->
                "Remote provider mode — no local models configured"
            else ->
                "No models ready. Download and initialize a model to get started."
        }
    }

    /**
     * Build the default model catalog with known models for on-device inference.
     * Includes Gemma 4 variants, Qwen models, and other LiteRT-LM compatible models.
     */
    companion object {
        internal fun buildDefaultCatalog(): List<ModelCatalogEntry> = listOf(
            // Gemma 4 remains discoverable, but is experimental until an exact artifact and
            // hardware path pass the content-addressed release matrix.
            ModelCatalogEntry(
                id = "gemma-4-e2b-litert-lm",
                displayName = "Gemma 4 E2B (LiteRT-LM)",
                description = "Experimental text-only Google Gemma 4 E2B LiteRT-LM artifact. Hermes has not certified its image, audio, MTP, tool-use, or device-accelerator paths; choose a release-certified model for one-tap setup.",
                repoId = "litert-community/gemma-4-E2B-it-litert-lm",
                revision = "7fa1d78473894f7e736a21d920c3aa80f950c0db",
                supportedBackends = listOf(ModelRuntimeBackend.LITERT_LM),
                approximateSizeBytes = 2_583_085_056,
                recommendedRamBytes = 8_000_000_000,
                supportsImageInput = false,
                supportsAudioInput = false,
                tags = listOf("gemma", "google", "litert-lm", "experimental", "text-only", "2b"),
                author = "Google",
                license = "Apache-2.0",
                isMobileRecommended = false,
            ),
            ModelCatalogEntry(
                id = "gemma-4-e4b-litert-lm",
                displayName = "Gemma 4 E4B (LiteRT-LM)",
                description = "Experimental text-only Google Gemma 4 E4B LiteRT-LM artifact for high-memory devices. Hermes has not certified its image, audio, MTP, tool-use, Snapdragon/Adreno, or NPU paths, and it is not selected automatically.",
                repoId = "litert-community/gemma-4-E4B-it-litert-lm",
                revision = "9695417f248178c63a9f318c6e0c56cb917cb837",
                supportedBackends = listOf(ModelRuntimeBackend.LITERT_LM),
                approximateSizeBytes = 3_654_467_584,
                recommendedRamBytes = 12_000_000_000,
                supportsImageInput = false,
                supportsAudioInput = false,
                tags = listOf("gemma", "google", "litert-lm", "experimental", "text-only", "4b"),
                author = "Google",
                license = "Apache-2.0",
                isMobileRecommended = false,
            ),
            ModelCatalogEntry(
                id = "gemma-3-1b-it-litert-lm",
                displayName = "Gemma 3 1B IT INT4 (LiteRT-LM)",
                description = "Google Gemma 3 1B instruction model packaged for LiteRT-LM. This is the smallest first-class Gemma 3 path for compatibility and startup checks.",
                repoId = "litert-community/Gemma3-1B-IT",
                revision = "main",
                supportedBackends = listOf(ModelRuntimeBackend.LITERT_LM),
                approximateSizeBytes = 0,
                recommendedRamBytes = 2_000_000_000,
                tags = listOf("gemma", "google", "gemma-3", "litert-lm", "small", "1b"),
                author = "Google",
                license = "Gemma",
                isMobileRecommended = true,
            ),
            ModelCatalogEntry(
                id = "gemma-3-4b-it-vision-task",
                displayName = "Gemma 3 4B IT Vision (.task)",
                description = "Google Gemma 3 4B image-text instruction model packaged as a LiteRT task artifact for multimodal image description tests.",
                repoId = "litert-community/Gemma3-4B-IT",
                revision = "main",
                supportedBackends = listOf(ModelRuntimeBackend.LITERT_LM),
                approximateSizeBytes = 0,
                recommendedRamBytes = 6_000_000_000,
                supportsImageInput = true,
                tags = listOf("gemma", "google", "gemma-3", "litert-lm", "vision", "image-text", "4b"),
                author = "Google",
                license = "Gemma",
                isMobileRecommended = true,
            ),
            ModelCatalogEntry(
                id = "gemma-3n-e2b-it-litert-lm",
                displayName = "Gemma 3n E2B IT Vision (LiteRT-LM)",
                description = "Google Gemma 3n E2B instruction model packaged for LiteRT-LM with image input support for mobile multimodal tests.",
                repoId = "google/gemma-3n-E2B-it-litert-lm",
                revision = "main",
                supportedBackends = listOf(ModelRuntimeBackend.LITERT_LM),
                approximateSizeBytes = 0,
                recommendedRamBytes = 4_000_000_000,
                supportsImageInput = true,
                supportsAudioInput = true,
                tags = listOf("gemma", "google", "gemma-3n", "litert-lm", "vision", "audio", "2b"),
                author = "Google",
                license = "Gemma",
                isMobileRecommended = true,
            ),
            ModelCatalogEntry(
                id = "gemma-3n-e4b-it-litert-lm",
                displayName = "Gemma 3n E4B IT Vision (LiteRT-LM)",
                description = "Google Gemma 3n E4B instruction model packaged for LiteRT-LM with image input support under the 5 GB model testing target when an int4 artifact is selected.",
                repoId = "google/gemma-3n-E4B-it-litert-lm",
                revision = "main",
                supportedBackends = listOf(ModelRuntimeBackend.LITERT_LM),
                approximateSizeBytes = 0,
                recommendedRamBytes = 6_000_000_000,
                supportsImageInput = true,
                supportsAudioInput = true,
                tags = listOf("gemma", "google", "gemma-3n", "litert-lm", "vision", "audio", "4b"),
                author = "Google",
                license = "Gemma",
                isMobileRecommended = true,
            ),
            ModelCatalogEntry(
                id = "qwen3-0-6b-litert-lm",
                displayName = "Qwen3 0.6B (LiteRT-LM)",
                description = "Qwen3 0.6B LiteRT-LM model for very small on-device inference checks and fast native agent smoke tests.",
                repoId = "litert-community/Qwen3-0.6B",
                revision = "main",
                supportedBackends = listOf(ModelRuntimeBackend.LITERT_LM),
                approximateSizeBytes = 614_236_160,
                recommendedRamBytes = 2_000_000_000,
                tags = listOf("qwen", "alibaba", "litert-lm", "small", "0.6b"),
                author = "Qwen/Alibaba",
                license = "Apache-2.0",
                isMobileRecommended = true,
            ),
            ModelCatalogEntry(
                id = "qwen2-5-1-5b-instruct-litert-lm",
                displayName = "Qwen2.5 1.5B Instruct (LiteRT-LM)",
                description = "Qwen2.5 1.5B Instruct quantized LiteRT-LM model for lightweight mobile chat and tool-routing tests.",
                repoId = "litert-community/Qwen2.5-1.5B-Instruct",
                revision = "main",
                supportedBackends = listOf(ModelRuntimeBackend.LITERT_LM),
                approximateSizeBytes = 1_597_931_520,
                recommendedRamBytes = 3_000_000_000,
                tags = listOf("qwen", "alibaba", "litert-lm", "instruct", "1.5b"),
                author = "Qwen/Alibaba",
                license = "Apache-2.0",
                isMobileRecommended = true,
            ),
            ModelCatalogEntry(
                id = "minicpm5-1b-litert-lm",
                displayName = "MiniCPM 5 1B (LiteRT-LM)",
                description = "MiniCPM 5 1B LiteRT-LM package for compact on-device chat and agent smoke tests (~1.1 GB).",
                repoId = "Tdamre/MiniCPM5-1B-litert-lm",
                revision = VerifiedLocalModelArtifacts.require(
                    "Tdamre/MiniCPM5-1B-litert-lm",
                    "MiniCPM5-1B-web.litertlm",
                ).revision,
                filePath = "MiniCPM5-1B-web.litertlm",
                sha256 = "a6d6d61fdfa0e04458fea344791d15ca304b54a40573e1b44ebab30c54d7bf1d",
                supportedBackends = listOf(ModelRuntimeBackend.LITERT_LM),
                approximateSizeBytes = 1_103_486_896,
                recommendedRamBytes = 3_000_000_000,
                tags = listOf("minicpm", "openbmb", "litert-lm", "small", "1b"),
                author = "OpenBMB/community",
                license = "Apache-2.0",
                isMobileRecommended = true,
            ),
            ModelCatalogEntry(
                id = "vibethinker-3b-litert-lm",
                displayName = "VibeThinker 3B (LiteRT-LM)",
                description = "VibeThinker 3B packaged for the native LiteRT-LM runtime on high-memory phones and emulators.",
                repoId = "Tdamre/VibeThinker-3B-litert-lm",
                revision = VerifiedLocalModelArtifacts.require(
                    "Tdamre/VibeThinker-3B-litert-lm",
                    "VibeThinker-3B.litertlm",
                ).revision,
                filePath = "VibeThinker-3B.litertlm",
                sha256 = "4cd4a856ab9fb890223d927efd4ed37268ecd1fa78559a9d27bf21daa6b8c22f",
                supportedBackends = listOf(ModelRuntimeBackend.LITERT_LM),
                approximateSizeBytes = 3_446_780_848,
                recommendedRamBytes = 8_000_000_000,
                tags = listOf("vibethinker", "litert-lm", "reasoning", "3b"),
                author = "Community",
                license = "Other",
                isMobileRecommended = true,
            ),
            // Small GGUF models (llama.cpp)
            ModelCatalogEntry(
                id = "qwen35-0-8b-gguf",
                displayName = "Qwen3.5 0.8B Q4_K_M (GGUF)",
                description = "Very small Qwen3.5 0.8B GGUF (~0.5 GB) for fast llama.cpp on-device smoke tests and low-RAM devices.",
                repoId = "unsloth/Qwen3.5-0.8B-GGUF",
                revision = VerifiedLocalModelArtifacts.require(
                    "unsloth/Qwen3.5-0.8B-GGUF",
                    "Qwen3.5-0.8B-Q4_K_M.gguf",
                ).revision,
                filePath = "Qwen3.5-0.8B-Q4_K_M.gguf",
                sha256 = "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517",
                supportedBackends = listOf(ModelRuntimeBackend.LLAMA_CPP),
                approximateSizeBytes = 532_517_120,
                recommendedRamBytes = 2_000_000_000,
                tags = listOf("qwen", "alibaba", "gguf", "small", "0.8b"),
                author = "Qwen/Unsloth",
                license = "Apache-2.0",
                isMobileRecommended = true,
            ),
            ModelCatalogEntry(
                id = "nanbeige4-2-3b-q4-k-m-gguf",
                displayName = "Nanbeige4.2 3B Q4_K_M (GGUF · TurboQuant)",
                description = "Exact Tdamre Nanbeige4.2 Q4_K_M artifact for the opt-in TurboQuant llama.cpp lane; Stable does not support its legacy nanbeige architecture metadata.",
                repoId = "Tdamre/Nanbeige4.2-3B-GGUF",
                revision = VerifiedLocalModelArtifacts.require(
                    "Tdamre/Nanbeige4.2-3B-GGUF",
                    "Nanbeige4.2-3B-Q4_K_M.gguf",
                ).revision,
                filePath = "Nanbeige4.2-3B-Q4_K_M.gguf",
                sha256 = "99c7bfb88907f7eee0a04c4314f1c46bca391819478d8cb90b3e164f09576489",
                supportedBackends = listOf(ModelRuntimeBackend.LLAMA_CPP),
                approximateSizeBytes = 2_574_807_840,
                recommendedRamBytes = 6_000_000_000,
                tags = listOf("nanbeige", "tdamre", "gguf", "q4-k-m", "3b", "turboquant"),
                author = "Nanbeige/Tdamre",
                license = "Apache-2.0",
                isMobileRecommended = true,
                requiredLlamaCppRuntimeLane = "turboquant",
            ),
            ModelCatalogEntry(
                id = "minicpm5-1b-fable5-q4-k-m-gguf",
                displayName = "MiniCPM5 1B Fable5 Q4_K_M (GGUF)",
                description = "Compact MiniCPM5 thinking model pinned to the Q4_K_M GGUF artifact for the embedded llama.cpp runtime.",
                repoId = "GnLOLot/MiniCPM5-1B-Claude-Opus-Fable5-Thinking-GGUF",
                revision = VerifiedLocalModelArtifacts.require(
                    "GnLOLot/MiniCPM5-1B-Claude-Opus-Fable5-Thinking-GGUF",
                    "MiniCPM5-1B-Claude-Opus-Fable5-Thinking-Q4_K_M.gguf",
                ).revision,
                filePath = "MiniCPM5-1B-Claude-Opus-Fable5-Thinking-Q4_K_M.gguf",
                sha256 = "b1c3bf2995e96cb792a0031e4e1497a500e9244c68ba17c24a7e6edf1fc59019",
                supportedBackends = listOf(ModelRuntimeBackend.LLAMA_CPP),
                approximateSizeBytes = 688_066_496,
                recommendedRamBytes = 2_000_000_000,
                tags = listOf("minicpm", "gguf", "thinking", "small", "1b"),
                author = "OpenBMB/community",
                license = "Other",
                isMobileRecommended = true,
            ),
            ModelCatalogEntry(
                id = "qwen3-4b-gguf",
                displayName = "Qwen3 4B (GGUF)",
                description = "Qwen3 4B quantized model in GGUF format. Lightweight and fast, ideal for devices with 6GB+ RAM. Supports tool use and function calling.",
                repoId = "Qwen/Qwen3-4B-GGUF",
                revision = "main",
                supportedBackends = listOf(ModelRuntimeBackend.LLAMA_CPP),
                approximateSizeBytes = 3_000_000_000,
                recommendedRamBytes = 6_000_000_000,
                tags = listOf("qwen", "alibaba", "tool-use", "gguf"),
                author = "Qwen/Alibaba",
                license = "Apache-2.0",
                isMobileRecommended = true,
            ),
            ModelCatalogEntry(
                id = "qwen3-8b-gguf",
                displayName = "Qwen3 8B (GGUF)",
                description = "Qwen3 8B in GGUF format. Strong multilingual capabilities and reasoning. Requires 10GB+ RAM for comfortable inference.",
                repoId = "Qwen/Qwen3-8B-GGUF",
                revision = "main",
                supportedBackends = listOf(ModelRuntimeBackend.LLAMA_CPP),
                approximateSizeBytes = 5_500_000_000,
                recommendedRamBytes = 10_000_000_000,
                tags = listOf("qwen", "alibaba", "multilingual", "gguf"),
                author = "Qwen/Alibaba",
                license = "Apache-2.0",
                isMobileRecommended = true,
            ),
            // Phi models
            ModelCatalogEntry(
                id = "phi-4-gguf",
                displayName = "Phi 4 (GGUF)",
                description = "Microsoft's Phi 4 — a capable small language model optimized for efficiency. Excellent performance-to-size ratio.",
                repoId = "microsoft/Phi-4-GGUF",
                revision = "main",
                supportedBackends = listOf(ModelRuntimeBackend.LLAMA_CPP),
                approximateSizeBytes = 2_500_000_000,
                recommendedRamBytes = 5_000_000_000,
                tags = listOf("phi", "microsoft", "efficient", "gguf"),
                author = "Microsoft",
                license = "MIT",
                isMobileRecommended = true,
            ),
            // Llama 3.2
            ModelCatalogEntry(
                id = "llama-3-2-3b-gguf",
                displayName = "Llama 3.2 3B (GGUF)",
                description = "Meta's Llama 3.2 3B — extremely lightweight model designed for edge deployment. Fast inference on modest hardware.",
                repoId = "NousResearch/Llama-3.2-3B-Instruct-GGUF",
                revision = "main",
                supportedBackends = listOf(ModelRuntimeBackend.LLAMA_CPP),
                approximateSizeBytes = 2_000_000_000,
                recommendedRamBytes = 4_000_000_000,
                tags = listOf("llama", "meta", "edge", "gguf"),
                author = "Meta",
                license = "llama3.2",
                isMobileRecommended = true,
            ),
        ).map { entry ->
            entry.copy(isMobileRecommended = isReleaseCertifiedMobileEntry(entry))
        }

        private fun isReleaseCertifiedMobileEntry(entry: ModelCatalogEntry): Boolean {
            return VerifiedLocalModelArtifacts.releaseMatrix.any { artifact ->
                val backendMatches = when (artifact.runtime) {
                    "litert-lm" -> ModelRuntimeBackend.LITERT_LM in entry.supportedBackends
                    "llama.cpp" -> ModelRuntimeBackend.LLAMA_CPP in entry.supportedBackends
                    else -> false
                }
                artifact.repoId.equals(entry.repoId, ignoreCase = true) &&
                    artifact.revision == entry.revision &&
                    artifact.fileName.equals(entry.filePath, ignoreCase = true) &&
                    artifact.expectedBytes == entry.approximateSizeBytes &&
                    artifact.sha256.equals(entry.sha256, ignoreCase = true) &&
                    backendMatches
            }
        }
    }
}
