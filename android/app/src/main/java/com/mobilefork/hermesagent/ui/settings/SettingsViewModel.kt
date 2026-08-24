package com.mobilefork.hermesagent.ui.settings

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.Python
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.backend.LocalBackendStatus
import com.mobilefork.hermesagent.backend.LlamaCppLaunchConfig
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import com.mobilefork.hermesagent.auth.ProviderSetupProbeResult
import com.mobilefork.hermesagent.auth.ProviderSetupUrlProbe
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsPersistenceException
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.HermesNetworkPolicy
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.data.LocalModelDownloadPersistenceException
import com.mobilefork.hermesagent.data.ProviderPresets
import com.mobilefork.hermesagent.data.ProviderSetupTarget
import com.mobilefork.hermesagent.data.SecureSecretsStore
import com.mobilefork.hermesagent.device.HermesProviderSetupWebActivity
import com.mobilefork.hermesagent.models.LocalModelRuntimeSelectionAuthority
import com.mobilefork.hermesagent.models.PythonRuntimeWriteAuthority
import com.mobilefork.hermesagent.models.RuntimeSelectionSupersededException
import com.mobilefork.hermesagent.models.clearContradictoryPendingAutoStart
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.HermesStrings
import com.mobilefork.hermesagent.ui.i18n.hermesStringsFor
import com.mobilefork.hermesagent.ui.i18n.llamaCppAdvancedText
import com.mobilefork.hermesagent.ui.theme.normalizeThemeHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SettingsUiState(
    val provider: String = "openrouter",
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val dataSaverMode: Boolean = false,
    val offlineAirplaneMode: Boolean = false,
    val onDeviceBackend: String = BackendKind.NONE.persistedValue,
    val liteRtLmSpeculativeDecodingMode: String = "auto",
    val llamaCppRuntimeLane: String = AppSettings.DEFAULT_LLAMA_CPP_RUNTIME_LANE,
    val llamaCppCacheTypeK: String = AppSettings.DEFAULT_LLAMA_CPP_CACHE_TYPE,
    val llamaCppCacheTypeV: String = AppSettings.DEFAULT_LLAMA_CPP_CACHE_TYPE,
    val llamaCppFlashAttention: String = AppSettings.DEFAULT_LLAMA_CPP_FLASH_ATTENTION,
    val llamaCppAdditionalArguments: List<String> = emptyList(),
    val localModelMaxTokens: Int = AppSettings.DEFAULT_LOCAL_MODEL_MAX_TOKENS,
    val localModelTopK: Int = AppSettings.DEFAULT_LOCAL_MODEL_TOP_K,
    val localModelTopP: Float = AppSettings.DEFAULT_LOCAL_MODEL_TOP_P,
    val localModelTemperature: Float = AppSettings.DEFAULT_LOCAL_MODEL_TEMPERATURE,
    val localModelAccelerator: String = AppSettings.DEFAULT_LOCAL_MODEL_ACCELERATOR,
    val localModelToolMode: String = AppSettings.DEFAULT_LOCAL_MODEL_TOOL_MODE,
    val apiGenerationKnobsEnabled: Boolean = false,
    val languageTag: String = AppLanguage.ENGLISH.tag,
    val customSystemPrompt: String = "",
    val chatDisplayMode: String = "compact",
    val keywordHighlightingEnabled: Boolean = true,
    val themePrimaryHex: String = AppSettings.DEFAULT_THEME_PRIMARY_HEX,
    val themeSecondaryHex: String = AppSettings.DEFAULT_THEME_SECONDARY_HEX,
    val themeBackgroundHex: String = AppSettings.DEFAULT_THEME_BACKGROUND_HEX,
    val themeSurfaceHex: String = AppSettings.DEFAULT_THEME_SURFACE_HEX,
    val themeSurfaceVariantHex: String = AppSettings.DEFAULT_THEME_SURFACE_VARIANT_HEX,
    val themeCardShape: String = "rounded",
    val uiFontScale: Float = AppSettings.DEFAULT_UI_FONT_SCALE,
    val onDeviceSummary: String = "",
    val agentEndpointStarted: Boolean = false,
    val agentLoopbackUrl: String = "",
    val agentLanUrl: String = "",
    val agentApiKey: String = "",
    val agentModelName: String = "",
    val status: String = "",
)

private data class SettingsSaveResult(
    val apiKey: String,
    val onDeviceSummary: String,
    val statusMessage: String,
    val authoritativeLlamaCppSettings: LlamaCppAdvancedSettingsTuple,
)

internal data class LlamaCppAdvancedSettingsTuple(
    val runtimeLane: String,
    val cacheTypeK: String,
    val cacheTypeV: String,
    val flashAttention: String,
    val additionalArguments: List<String>,
)

internal data class LlamaCppAdvancedDraftSnapshot(
    val settings: LlamaCppAdvancedSettingsTuple,
    val revision: Long,
    val hasUnsavedChanges: Boolean,
)

internal fun AppSettings.llamaCppAdvancedSettingsTuple() = LlamaCppAdvancedSettingsTuple(
    runtimeLane = AppSettings.normalizeLlamaCppRuntimeLane(llamaCppRuntimeLane),
    cacheTypeK = AppSettings.normalizeLlamaCppCacheType(llamaCppCacheTypeK),
    cacheTypeV = AppSettings.normalizeLlamaCppCacheType(llamaCppCacheTypeV),
    flashAttention = AppSettings.normalizeLlamaCppFlashAttention(llamaCppFlashAttention),
    additionalArguments = llamaCppAdditionalArguments.toList(),
)

internal fun SettingsUiState.llamaCppAdvancedSettingsTuple() = LlamaCppAdvancedSettingsTuple(
    runtimeLane = llamaCppRuntimeLane,
    cacheTypeK = llamaCppCacheTypeK,
    cacheTypeV = llamaCppCacheTypeV,
    flashAttention = llamaCppFlashAttention,
    additionalArguments = llamaCppAdditionalArguments.toList(),
)

private fun SettingsUiState.withLlamaCppAdvancedSettings(
    settings: LlamaCppAdvancedSettingsTuple,
) = copy(
    llamaCppRuntimeLane = settings.runtimeLane,
    llamaCppCacheTypeK = settings.cacheTypeK,
    llamaCppCacheTypeV = settings.cacheTypeV,
    llamaCppFlashAttention = settings.flashAttention,
    llamaCppAdditionalArguments = settings.additionalArguments.toList(),
)

internal typealias SettingsSaveSupersededException = RuntimeSelectionSupersededException

internal class SettingsSaveGeneration {
    fun beginSave(): Long = LocalModelRuntimeSelectionAuthority.beginAction()

    fun invalidate(): Long = LocalModelRuntimeSelectionAuthority.invalidate()

    /** Lock-free for AppSettingsStore.update transforms, which already hold cacheLock. */
    fun isCurrent(candidate: Long): Boolean = LocalModelRuntimeSelectionAuthority.isCurrent(candidate)

    /** Lock-free for AppSettingsStore.update transforms, which already hold cacheLock. */
    fun requireCurrent(candidate: Long) = LocalModelRuntimeSelectionAuthority.requireCurrent(candidate)

    /**
     * Admit one generation-owned side effect. Invalidation waits for an admitted effect to
     * finish; after invalidation completes, an older generation cannot begin another effect.
     * Runtime calls are gated separately so the main thread waits for at most the currently
     * admitted call rather than the save's entire runtime transition.
     */
    fun <T> withCurrent(candidate: Long, action: () -> T): T {
        return LocalModelRuntimeSelectionAuthority.withCurrent(candidate, action)
    }

    fun runIfCurrent(candidate: Long, action: () -> Unit): Boolean {
        return LocalModelRuntimeSelectionAuthority.runIfCurrent(candidate, action)
    }

    fun <T> performLongIfCurrent(
        candidate: Long,
        cleanupStaleResultWhileOwned: (T) -> Unit = {},
        action: () -> T,
    ): T {
        return LocalModelRuntimeSelectionAuthority.performLongIfCurrent(
            candidate = candidate,
            cleanupStaleResultWhileOwned = cleanupStaleResultWhileOwned,
            action = action,
        )
    }
}

internal fun updateSettingsForGeneration(
    store: AppSettingsStore,
    saveGeneration: SettingsSaveGeneration,
    generation: Long,
    transform: (AppSettings) -> AppSettings,
): AppSettings {
    return store.update { current ->
        // This check deliberately runs under AppSettingsStore.cacheLock. If a newer handoff
        // already committed, the old save cannot write; if the old save owns the lock first,
        // the newer handoff commits after it and remains authoritative.
        saveGeneration.requireCurrent(generation)
        transform(current)
    }
}

internal fun updateSettingsAndPendingForGeneration(
    store: AppSettingsStore,
    downloadStore: LocalModelDownloadStore,
    saveGeneration: SettingsSaveGeneration,
    generation: Long,
    selectedBackend: BackendKind,
    selectedLlamaCppRuntimeLane: String,
    clearAnyPendingAutoStart: Boolean = false,
    transform: (AppSettings) -> AppSettings,
): AppSettings {
    return saveGeneration.withCurrent(generation) {
        val pendingId = downloadStore.pendingAutoStartRecordId()
        check(
            if (clearAnyPendingAutoStart && pendingId.isNotBlank()) {
                downloadStore.clearPendingAutoStartRecordId(pendingId)
            } else {
                clearContradictoryPendingAutoStart(
                    downloadStore = downloadStore,
                    selectedBackend = selectedBackend,
                    selectedLlamaCppRuntimeLane = selectedLlamaCppRuntimeLane,
                )
            },
        ) { "The pending local-model handoff changed while Settings was being saved" }
        updateSettingsForGeneration(store, saveGeneration, generation, transform)
    }
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = AppSettingsStore(application)
    private val downloadStore = LocalModelDownloadStore(application)
    private val secretsStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SecureSecretsStore(getApplication<Application>())
    }
    private val providerSetupOpenIndexes = mutableMapOf<String, Int>()
    private val settingsSaveGeneration = SettingsSaveGeneration()
    private var onDeviceSummaryJob: Job? = null
    private val llamaCppAdvancedDraftLock = Any()
    private var llamaCppAdvancedDraftRevision = 0L

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private var authoritativeLlamaCppSettings = _uiState.value.llamaCppAdvancedSettingsTuple()

    private fun loadInitialState(): SettingsUiState {
        val stored = settingsStore.load()
        val strings = hermesStringsFor(AppLanguage.fromTag(stored.languageTag))
        return SettingsUiState(
            provider = stored.provider,
            baseUrl = stored.baseUrl,
            model = stored.model,
            apiKey = "",
            dataSaverMode = stored.dataSaverMode,
            offlineAirplaneMode = stored.offlineAirplaneMode,
            onDeviceBackend = stored.onDeviceBackend,
            liteRtLmSpeculativeDecodingMode = normalizeSpeculativeDecodingMode(
                stored.liteRtLmSpeculativeDecodingMode,
            ),
            llamaCppRuntimeLane = AppSettings.normalizeLlamaCppRuntimeLane(stored.llamaCppRuntimeLane),
            llamaCppCacheTypeK = AppSettings.normalizeLlamaCppCacheType(stored.llamaCppCacheTypeK),
            llamaCppCacheTypeV = AppSettings.normalizeLlamaCppCacheType(stored.llamaCppCacheTypeV),
            llamaCppFlashAttention = AppSettings.normalizeLlamaCppFlashAttention(stored.llamaCppFlashAttention),
            llamaCppAdditionalArguments = stored.llamaCppAdditionalArguments.toList(),
            localModelMaxTokens = AppSettings.normalizeLocalModelMaxTokens(stored.localModelMaxTokens),
            localModelTopK = AppSettings.normalizeLocalModelTopK(stored.localModelTopK),
            localModelTopP = AppSettings.normalizeLocalModelTopP(stored.localModelTopP),
            localModelTemperature = AppSettings.normalizeLocalModelTemperature(stored.localModelTemperature),
            localModelAccelerator = AppSettings.normalizeLocalModelAccelerator(stored.localModelAccelerator),
            localModelToolMode = AppSettings.normalizeLocalModelToolMode(stored.localModelToolMode),
            apiGenerationKnobsEnabled = stored.apiGenerationKnobsEnabled,
            languageTag = AppLanguage.fromTag(stored.languageTag).tag,
            customSystemPrompt = stored.customSystemPrompt,
            chatDisplayMode = normalizeChatDisplayMode(stored.chatDisplayMode),
            keywordHighlightingEnabled = stored.keywordHighlightingEnabled,
            themePrimaryHex = normalizeThemeHex(stored.themePrimaryHex, AppSettings.DEFAULT_THEME_PRIMARY_HEX),
            themeSecondaryHex = normalizeThemeHex(stored.themeSecondaryHex, AppSettings.DEFAULT_THEME_SECONDARY_HEX),
            themeBackgroundHex = normalizeThemeHex(stored.themeBackgroundHex, AppSettings.DEFAULT_THEME_BACKGROUND_HEX),
            themeSurfaceHex = normalizeThemeHex(stored.themeSurfaceHex, AppSettings.DEFAULT_THEME_SURFACE_HEX),
            themeSurfaceVariantHex = normalizeThemeHex(stored.themeSurfaceVariantHex, AppSettings.DEFAULT_THEME_SURFACE_VARIANT_HEX),
            themeCardShape = normalizeThemeCardShape(stored.themeCardShape),
            uiFontScale = AppSettings.normalizeUiFontScale(stored.uiFontScale),
            onDeviceSummary = defaultOnDeviceSummary(stored.onDeviceBackend, strings),
        )
    }

    private fun currentStrings() = hermesStringsFor(AppLanguage.fromTag(_uiState.value.languageTag))

    private fun updateLlamaCppAdvancedDraft(
        transform: (SettingsUiState) -> SettingsUiState,
    ) = synchronized(llamaCppAdvancedDraftLock) {
        // Increment before publishing the draft so an in-flight runtime result cannot pass its
        // revision check and then overwrite this user action.
        llamaCppAdvancedDraftRevision += 1
        _uiState.update(transform)
    }

    internal fun captureLlamaCppAdvancedDraft(): LlamaCppAdvancedDraftSnapshot {
        return synchronized(llamaCppAdvancedDraftLock) {
            val settings = _uiState.value.llamaCppAdvancedSettingsTuple()
            LlamaCppAdvancedDraftSnapshot(
                settings = settings,
                revision = llamaCppAdvancedDraftRevision,
                hasUnsavedChanges = settings != authoritativeLlamaCppSettings,
            )
        }
    }

    /**
     * Publish the durable tuple produced by the same runtime-selection generation.
     *
     * Runtime reconciliation is allowed to replace the captured draft only when no later edit
     * occurred. Passive endpoint refreshes and ordinary Settings saves additionally preserve an
     * already-dirty advanced card; explicit Apply/start actions opt in because that draft was the
     * tuple they persisted. Endpoint/status fields still publish when an older draft is retained.
     */
    internal fun publishAuthoritativeLlamaCppSettingsForGeneration(
        generation: Long,
        expectedDraft: LlamaCppAdvancedDraftSnapshot,
        authoritativeSettings: LlamaCppAdvancedSettingsTuple,
        allowExistingDraftChanges: Boolean,
        transform: (SettingsUiState) -> SettingsUiState = { it },
    ): Boolean {
        return settingsSaveGeneration.runIfCurrent(generation) {
            synchronized(llamaCppAdvancedDraftLock) {
                _uiState.update { current ->
                    val draftUnchanged = llamaCppAdvancedDraftRevision == expectedDraft.revision &&
                        current.llamaCppAdvancedSettingsTuple() == expectedDraft.settings
                    val mayReplaceDraft = draftUnchanged &&
                        (allowExistingDraftChanges || !expectedDraft.hasUnsavedChanges)
                    // Even when a live user draft wins, remember the newest durable baseline so
                    // later refreshes can distinguish it from unsaved UI changes.
                    authoritativeLlamaCppSettings = authoritativeSettings
                    transform(
                        if (mayReplaceDraft) {
                            current.withLlamaCppAdvancedSettings(authoritativeSettings)
                        } else {
                            current
                        },
                    )
                }
            }
        }
    }

    private fun persistSettingsOrReport(
        transform: (AppSettings) -> AppSettings,
    ): AppSettings? {
        return try {
            settingsStore.update(transform)
        } catch (error: AppSettingsPersistenceException) {
            _uiState.update {
                it.copy(status = currentStrings().settingsSaveFailed(error::class.java.simpleName))
            }
            null
        }
    }

    fun reload() {
        val reloaded = loadInitialState()
        synchronized(llamaCppAdvancedDraftLock) {
            llamaCppAdvancedDraftRevision += 1
            authoritativeLlamaCppSettings = reloaded.llamaCppAdvancedSettingsTuple()
            _uiState.value = reloaded
        }
        loadApiKeyForProvider(reloaded.provider)
        refreshOnDeviceSummary(reloaded.onDeviceBackend)
        refreshAgentEndpoint()
    }

    /**
     * Refresh local agent endpoint fields shown on Settings.
     *
     * Passive Settings opens must not cold-start Python/API just to paint the card.
     * Use [forceStart]=true only for the explicit Refresh button.
     */
    fun refreshAgentEndpoint(forceStart: Boolean = false) {
        val generation = if (forceStart) {
            settingsSaveGeneration.beginSave()
        } else {
            LocalModelRuntimeSelectionAuthority.currentGeneration()
        }
        var expectedDraft = captureLlamaCppAdvancedDraft()
        if (!forceStart) {
            // SettingsScreen calls this on entry. Mirror a reconciliation completed while this
            // ViewModel was inactive synchronously, before the user can re-apply its stale card.
            publishAuthoritativeLlamaCppSettingsForGeneration(
                generation = generation,
                expectedDraft = expectedDraft,
                authoritativeSettings = settingsStore.load().llamaCppAdvancedSettingsTuple(),
                allowExistingDraftChanges = false,
            )
            expectedDraft = captureLlamaCppAdvancedDraft()
        }
        viewModelScope.launch(Dispatchers.IO) {
            val (runtime, authoritativeSettings) = try {
                val currentRuntime = if (forceStart) {
                    settingsSaveGeneration.performLongIfCurrent(generation) {
                        HermesRuntimeManager.ensureStarted(
                            getApplication(),
                            admissionCheck = { settingsSaveGeneration.requireCurrent(generation) },
                        )
                    }
                } else {
                    HermesRuntimeManager.currentState()
                }
                settingsSaveGeneration.withCurrent(generation) {
                    currentRuntime to settingsStore.load().llamaCppAdvancedSettingsTuple()
                }
            } catch (_: RuntimeSelectionSupersededException) {
                return@launch
            }
            publishAuthoritativeLlamaCppSettingsForGeneration(
                generation = generation,
                expectedDraft = expectedDraft,
                authoritativeSettings = authoritativeSettings,
                allowExistingDraftChanges = false,
            ) {
                it.copy(
                    agentEndpointStarted = runtime.started,
                    agentLoopbackUrl = runtime.baseUrl.orEmpty(),
                    agentLanUrl = runtime.lanBaseUrl.orEmpty(),
                    agentApiKey = runtime.apiKey.orEmpty(),
                    agentModelName = runtime.modelName.orEmpty(),
                )
            }
        }
    }

    fun updateProvider(provider: String) {
        val preset = ProviderPresets.find(provider)
        var shouldLoadApiKey = false
        _uiState.update {
            val providerChanged = provider != it.provider
            shouldLoadApiKey = providerChanged
            it.copy(
                provider = provider,
                baseUrl = if (providerChanged && provider != "custom") preset?.baseUrl.orEmpty() else it.baseUrl,
                model = if (providerChanged && provider != "custom") preset?.modelHint.orEmpty() else it.model,
                apiKey = if (providerChanged) "" else it.apiKey,
            )
        }
        if (shouldLoadApiKey) {
            loadApiKeyForProvider(provider)
        }
    }

    fun updateBaseUrl(value: String) = _uiState.update { it.copy(baseUrl = value) }
    fun updateModel(value: String) = _uiState.update { it.copy(model = value) }
    fun updateApiKey(value: String) = _uiState.update { it.copy(apiKey = value) }
    fun updateDataSaverMode(enabled: Boolean) = _uiState.update { it.copy(dataSaverMode = enabled) }
    fun updateOfflineAirplaneMode(enabled: Boolean) {
        val generation = settingsSaveGeneration.beginSave()
        val persisted = try {
            settingsSaveGeneration.withCurrent(generation) {
                settingsStore.update { current -> current.copy(offlineAirplaneMode = enabled) }
            }
        } catch (_: RuntimeSelectionSupersededException) {
            null
        } catch (error: AppSettingsPersistenceException) {
            _uiState.update {
                it.copy(status = currentStrings().settingsSaveFailed(error::class.java.simpleName))
            }
            null
        } ?: return
        val strings = currentStrings()
        if (!enabled) {
            settingsSaveGeneration.runIfCurrent(generation) {
                _uiState.update {
                    it.copy(
                        offlineAirplaneMode = persisted.offlineAirplaneMode,
                        status = strings.offlineAirplaneStatus(false),
                    )
                }
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val remoteStopFailure = try {
                settingsSaveGeneration.performLongIfCurrent(generation) {
                    HermesRuntimeManager.stopRemoteRuntime(
                        admissionCheck = { settingsSaveGeneration.requireCurrent(generation) },
                    ).error
                }
            } catch (_: RuntimeSelectionSupersededException) {
                return@launch
            }
            settingsSaveGeneration.runIfCurrent(generation) {
                _uiState.update {
                    it.copy(
                        offlineAirplaneMode = persisted.offlineAirplaneMode,
                        status = remoteStopFailure ?: strings.offlineAirplaneStatus(true),
                    )
                }
            }
        }
    }
    fun updateLiteRtLmSpeculativeDecodingMode(value: String) = _uiState.update {
        it.copy(liteRtLmSpeculativeDecodingMode = normalizeSpeculativeDecodingMode(value))
    }
    fun updateLlamaCppRuntimeLane(value: String) = updateLlamaCppAdvancedDraft {
        it.copy(llamaCppRuntimeLane = AppSettings.normalizeLlamaCppRuntimeLane(value))
    }
    fun updateLlamaCppCacheTypeK(value: String) = updateLlamaCppAdvancedDraft {
        it.copy(llamaCppCacheTypeK = AppSettings.normalizeLlamaCppCacheType(value))
    }
    fun updateLlamaCppCacheTypeV(value: String) = updateLlamaCppAdvancedDraft {
        it.copy(llamaCppCacheTypeV = AppSettings.normalizeLlamaCppCacheType(value))
    }
    fun updateLlamaCppFlashAttention(value: String) = updateLlamaCppAdvancedDraft {
        it.copy(llamaCppFlashAttention = AppSettings.normalizeLlamaCppFlashAttention(value))
    }
    fun updateLlamaCppAdditionalArguments(values: List<String>) = updateLlamaCppAdvancedDraft {
        // Keep the draft lossless so bounds, blank lines, and control characters remain
        // visible to validation instead of being silently trimmed, dropped, or truncated.
        it.copy(llamaCppAdditionalArguments = values.toList())
    }
    fun updateLocalModelMaxTokens(value: Int) = _uiState.update {
        it.copy(localModelMaxTokens = AppSettings.normalizeLocalModelMaxTokens(value))
    }
    fun updateLocalModelTopK(value: Int) = _uiState.update {
        it.copy(localModelTopK = AppSettings.normalizeLocalModelTopK(value))
    }
    fun updateLocalModelTopP(value: Float) = _uiState.update {
        it.copy(localModelTopP = AppSettings.normalizeLocalModelTopP(value))
    }
    fun updateLocalModelTemperature(value: Float) = _uiState.update {
        it.copy(localModelTemperature = AppSettings.normalizeLocalModelTemperature(value))
    }
    fun updateLocalModelAccelerator(value: String) = _uiState.update {
        it.copy(localModelAccelerator = AppSettings.normalizeLocalModelAccelerator(value))
    }
    fun updateLocalModelToolMode(value: String) = _uiState.update {
        it.copy(localModelToolMode = AppSettings.normalizeLocalModelToolMode(value))
    }
    fun updateApiGenerationKnobsEnabled(enabled: Boolean) = _uiState.update {
        it.copy(apiGenerationKnobsEnabled = enabled)
    }

    fun updateCustomSystemPrompt(value: String) {
        val normalized = AppSettings.normalizeCustomSystemPrompt(value)
        _uiState.update {
            it.copy(
                customSystemPrompt = normalized,
                status = if (value.length > normalized.length) {
                    currentStrings().agentPersonaLimited(AppSettings.MAX_CUSTOM_SYSTEM_PROMPT_CHARS)
                } else {
                    it.status
                },
            )
        }
    }

    fun saveAgentPersona() {
        val normalized = AppSettings.normalizeCustomSystemPrompt(_uiState.value.customSystemPrompt)
        val updated = persistSettingsOrReport { current ->
            current.copy(customSystemPrompt = normalized)
        } ?: return
        _uiState.update {
            val strings = currentStrings()
            it.copy(
                customSystemPrompt = updated.customSystemPrompt,
                status = if (updated.customSystemPrompt.isBlank()) {
                    strings.agentPersonaCleared()
                } else {
                    strings.agentPersonaSaved()
                },
            )
        }
    }

    fun clearAgentPersona() {
        persistSettingsOrReport { current -> current.copy(customSystemPrompt = "") } ?: return
        _uiState.update {
            it.copy(
                customSystemPrompt = "",
                status = currentStrings().agentPersonaCleared(),
            )
        }
    }

    fun saveModelGenerationConfig() {
        val snapshot = _uiState.value
        val normalizedPrompt = AppSettings.normalizeCustomSystemPrompt(snapshot.customSystemPrompt)
        val updated = persistSettingsOrReport { current ->
            current.copy(
                localModelMaxTokens = AppSettings.normalizeLocalModelMaxTokens(snapshot.localModelMaxTokens),
                localModelTopK = AppSettings.normalizeLocalModelTopK(snapshot.localModelTopK),
                localModelTopP = AppSettings.normalizeLocalModelTopP(snapshot.localModelTopP),
                localModelTemperature = AppSettings.normalizeLocalModelTemperature(snapshot.localModelTemperature),
                localModelAccelerator = AppSettings.normalizeLocalModelAccelerator(snapshot.localModelAccelerator),
                localModelToolMode = AppSettings.normalizeLocalModelToolMode(snapshot.localModelToolMode),
                apiGenerationKnobsEnabled = snapshot.apiGenerationKnobsEnabled,
                customSystemPrompt = normalizedPrompt,
            )
        } ?: return
        _uiState.update {
            it.copy(
                localModelMaxTokens = updated.localModelMaxTokens,
                localModelTopK = updated.localModelTopK,
                localModelTopP = updated.localModelTopP,
                localModelTemperature = updated.localModelTemperature,
                localModelAccelerator = updated.localModelAccelerator,
                localModelToolMode = updated.localModelToolMode,
                apiGenerationKnobsEnabled = updated.apiGenerationKnobsEnabled,
                customSystemPrompt = updated.customSystemPrompt,
                status = currentStrings().modelConfigurationSaved(),
            )
        }
    }

    fun applyLlamaCppAdvancedSettings() {
        val snapshot = _uiState.value
        val language = AppLanguage.fromTag(snapshot.languageTag)
        val validationKey = llamaCppAdvancedValidationKey(
            lane = snapshot.llamaCppRuntimeLane,
            cacheTypeK = snapshot.llamaCppCacheTypeK,
            cacheTypeV = snapshot.llamaCppCacheTypeV,
            flashAttention = snapshot.llamaCppFlashAttention,
            additionalArguments = snapshot.llamaCppAdditionalArguments,
        )
        if (validationKey != null) {
            _uiState.update { it.copy(status = llamaCppAdvancedText(language, validationKey)) }
            return
        }

        val normalizedArguments = AppSettings.normalizeLlamaCppAdditionalArguments(
            snapshot.llamaCppAdditionalArguments,
        )
        updateLlamaCppAdvancedDraft {
            it.copy(
                onDeviceBackend = BackendKind.LLAMA_CPP.persistedValue,
                llamaCppRuntimeLane = AppSettings.normalizeLlamaCppRuntimeLane(snapshot.llamaCppRuntimeLane),
                llamaCppCacheTypeK = AppSettings.normalizeLlamaCppCacheType(snapshot.llamaCppCacheTypeK),
                llamaCppCacheTypeV = AppSettings.normalizeLlamaCppCacheType(snapshot.llamaCppCacheTypeV),
                llamaCppFlashAttention = AppSettings.normalizeLlamaCppFlashAttention(snapshot.llamaCppFlashAttention),
                llamaCppAdditionalArguments = normalizedArguments,
                status = llamaCppAdvancedText(language, "saved"),
            )
        }
        saveWithLlamaCppAdvancedDraft()
    }

    /**
     * Starts exactly one llama.cpp attempt with the RAM-capacity gate bypassed.
     * The consent is deliberately absent from [AppSettings], so it cannot survive
     * a process restart or enter an exported settings bundle.
     */
    fun tryLlamaCppDespiteRamWarning() {
        val generation = settingsSaveGeneration.invalidate()
        val expectedDraft = captureLlamaCppAdvancedDraft()
        val snapshot = _uiState.value
        val language = AppLanguage.fromTag(snapshot.languageTag)
        val validationKey = llamaCppAdvancedValidationKey(
            lane = snapshot.llamaCppRuntimeLane,
            cacheTypeK = snapshot.llamaCppCacheTypeK,
            cacheTypeV = snapshot.llamaCppCacheTypeV,
            flashAttention = snapshot.llamaCppFlashAttention,
            additionalArguments = snapshot.llamaCppAdditionalArguments,
        )
        if (validationKey != null) {
            _uiState.update { it.copy(status = llamaCppAdvancedText(language, validationKey)) }
            return
        }

        val persisted = try {
            updateSettingsAndPendingForGeneration(
                store = settingsStore,
                downloadStore = downloadStore,
                saveGeneration = settingsSaveGeneration,
                generation = generation,
                selectedBackend = BackendKind.LLAMA_CPP,
                selectedLlamaCppRuntimeLane = snapshot.llamaCppRuntimeLane,
                clearAnyPendingAutoStart = true,
            ) { current ->
                current.copy(
                    onDeviceBackend = BackendKind.LLAMA_CPP.persistedValue,
                    llamaCppRuntimeLane = AppSettings.normalizeLlamaCppRuntimeLane(snapshot.llamaCppRuntimeLane),
                    llamaCppCacheTypeK = AppSettings.normalizeLlamaCppCacheType(snapshot.llamaCppCacheTypeK),
                    llamaCppCacheTypeV = AppSettings.normalizeLlamaCppCacheType(snapshot.llamaCppCacheTypeV),
                    llamaCppFlashAttention = AppSettings.normalizeLlamaCppFlashAttention(snapshot.llamaCppFlashAttention),
                    llamaCppAdditionalArguments = AppSettings.normalizeLlamaCppAdditionalArguments(
                        snapshot.llamaCppAdditionalArguments,
                    ),
                )
            }
        } catch (error: RuntimeSelectionSupersededException) {
            return
        } catch (error: AppSettingsPersistenceException) {
            _uiState.update {
                it.copy(
                    onDeviceSummary = error.message.orEmpty().ifBlank { error::class.java.simpleName },
                    status = currentStrings().settingsSaveFailed(error::class.java.simpleName),
                )
            }
            return
        } catch (error: LocalModelDownloadPersistenceException) {
            _uiState.update {
                it.copy(
                    onDeviceSummary = error.message.orEmpty().ifBlank { error::class.java.simpleName },
                    status = currentStrings().settingsSaveFailed(error::class.java.simpleName),
                )
            }
            return
        }
        val publishedStart = publishAuthoritativeLlamaCppSettingsForGeneration(
            generation = generation,
            expectedDraft = expectedDraft,
            authoritativeSettings = persisted.llamaCppAdvancedSettingsTuple(),
            allowExistingDraftChanges = true,
        ) {
            it.copy(
                    onDeviceBackend = BackendKind.LLAMA_CPP.persistedValue,
                status = llamaCppAdvancedText(language, "danger_starting"),
            )
        }
        if (!publishedStart) return
        val runtimeExpectedDraft = captureLlamaCppAdvancedDraft()
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    settingsSaveGeneration.performLongIfCurrent(generation) {
                        val runtimeState = HermesRuntimeManager.restartAfterRemoteStop(
                            context = getApplication<Application>(),
                            dangerouslySkipRamChecks = true,
                            admissionCheck = { settingsSaveGeneration.requireCurrent(generation) },
                        )
                        Triple(
                            runtimeState,
                            OnDeviceBackendManager.currentStatus(),
                            settingsStore.load().llamaCppAdvancedSettingsTuple(),
                        )
                    }
                }
            }.onSuccess { (runtimeState, backendStatus, authoritativeSettings) ->
                val localPublished = runtimeState.started &&
                    backendStatus.started &&
                    backendStatus.backendKind == BackendKind.LLAMA_CPP &&
                    !runtimeState.baseUrl.isNullOrBlank()
                val localizedStatus = if (localPublished) {
                    llamaCppAdvancedText(language, "danger_ready")
                } else {
                    llamaCppAdvancedText(language, "danger_failed")
                }
                val runtimeDetail = runtimeState.error
                    .orEmpty()
                    .ifBlank { backendStatus.statusMessage }
                publishAuthoritativeLlamaCppSettingsForGeneration(
                    generation = generation,
                    expectedDraft = runtimeExpectedDraft,
                    authoritativeSettings = authoritativeSettings,
                    allowExistingDraftChanges = false,
                ) {
                    it.copy(
                        onDeviceSummary = runtimeDetail.ifBlank { localizedStatus },
                        status = localizedStatus,
                    )
                }
            }.onFailure { error ->
                if (error is RuntimeSelectionSupersededException) return@onFailure
                settingsSaveGeneration.runIfCurrent(generation) {
                    _uiState.update {
                        it.copy(
                            onDeviceSummary = error.message.orEmpty().ifBlank { error::class.java.simpleName },
                            status = llamaCppAdvancedText(language, "danger_failed"),
                        )
                    }
                }
            }
        }
    }

    fun updateChatDisplayMode(value: String) {
        val normalized = normalizeChatDisplayMode(value)
        val updated = persistSettingsOrReport { current -> current.copy(chatDisplayMode = normalized) } ?: return
        _uiState.update {
            it.copy(
                chatDisplayMode = updated.chatDisplayMode,
                status = currentStrings().chatDisplayModeSet(updated.chatDisplayMode),
            )
        }
    }
    fun updateKeywordHighlighting(enabled: Boolean) {
        val updated = persistSettingsOrReport {
            current -> current.copy(keywordHighlightingEnabled = enabled)
        } ?: return
        _uiState.update {
            it.copy(
                keywordHighlightingEnabled = updated.keywordHighlightingEnabled,
                status = currentStrings().keywordHighlightingStatus(updated.keywordHighlightingEnabled),
            )
        }
    }
    fun updateThemePrimaryHex(value: String) = _uiState.update { it.copy(themePrimaryHex = value) }
    fun updateThemeSecondaryHex(value: String) = _uiState.update { it.copy(themeSecondaryHex = value) }
    fun updateThemeBackgroundHex(value: String) = _uiState.update { it.copy(themeBackgroundHex = value) }
    fun updateThemeSurfaceHex(value: String) = _uiState.update { it.copy(themeSurfaceHex = value) }
    fun updateThemeSurfaceVariantHex(value: String) = _uiState.update { it.copy(themeSurfaceVariantHex = value) }
    fun updateUiFontScale(value: Float) = _uiState.update { it.copy(uiFontScale = AppSettings.normalizeUiFontScale(value)) }
    fun updateThemeCardShape(value: String) {
        val normalized = normalizeThemeCardShape(value)
        val updated = persistSettingsOrReport { current -> current.copy(themeCardShape = normalized) } ?: return
        _uiState.update {
            it.copy(
                themeCardShape = updated.themeCardShape,
                status = currentStrings().cardShapeSet(updated.themeCardShape),
            )
        }
    }

    fun applyThemePreset(preset: AppearanceThemePreset) {
        _uiState.update {
            it.copy(
                themePrimaryHex = preset.primaryHex,
                themeSecondaryHex = preset.secondaryHex,
                themeBackgroundHex = preset.backgroundHex,
                themeSurfaceHex = preset.surfaceHex,
                themeSurfaceVariantHex = preset.surfaceVariantHex,
                status = currentStrings().themePresetLoaded(preset.id, preset.label),
            )
        }
    }

    fun saveAppearance() {
        val snapshot = _uiState.value
        val updated = persistSettingsOrReport { current ->
            current.copy(
                chatDisplayMode = normalizeChatDisplayMode(snapshot.chatDisplayMode),
                keywordHighlightingEnabled = snapshot.keywordHighlightingEnabled,
                themePrimaryHex = normalizeThemeHex(snapshot.themePrimaryHex, AppSettings.DEFAULT_THEME_PRIMARY_HEX),
                themeSecondaryHex = normalizeThemeHex(snapshot.themeSecondaryHex, AppSettings.DEFAULT_THEME_SECONDARY_HEX),
                themeBackgroundHex = normalizeThemeHex(snapshot.themeBackgroundHex, AppSettings.DEFAULT_THEME_BACKGROUND_HEX),
                themeSurfaceHex = normalizeThemeHex(snapshot.themeSurfaceHex, AppSettings.DEFAULT_THEME_SURFACE_HEX),
                themeSurfaceVariantHex = normalizeThemeHex(snapshot.themeSurfaceVariantHex, AppSettings.DEFAULT_THEME_SURFACE_VARIANT_HEX),
                themeCardShape = normalizeThemeCardShape(snapshot.themeCardShape),
                uiFontScale = AppSettings.normalizeUiFontScale(snapshot.uiFontScale),
            )
        } ?: return
        _uiState.update {
            it.copy(
                chatDisplayMode = updated.chatDisplayMode,
                keywordHighlightingEnabled = updated.keywordHighlightingEnabled,
                themePrimaryHex = updated.themePrimaryHex,
                themeSecondaryHex = updated.themeSecondaryHex,
                themeBackgroundHex = updated.themeBackgroundHex,
                themeSurfaceHex = updated.themeSurfaceHex,
                themeSurfaceVariantHex = updated.themeSurfaceVariantHex,
                themeCardShape = updated.themeCardShape,
                uiFontScale = updated.uiFontScale,
                status = currentStrings().appearanceSaved(),
            )
        }
    }

    private fun loadApiKeyForProvider(provider: String) {
        if (provider.isBlank()) {
            return
        }
        viewModelScope.launch {
            val storedKey = withContext(Dispatchers.IO) {
                try {
                    secretsStore.loadApiKey(provider)
                } catch (_: Exception) {
                    ""
                }
            }
            if (storedKey.isBlank()) {
                return@launch
            }
            _uiState.update {
                if (it.provider == provider && it.apiKey.isBlank()) {
                    it.copy(apiKey = storedKey)
                } else {
                    it
                }
            }
        }
    }

    fun updateOnDeviceBackend(value: String) {
        _uiState.update {
            it.copy(
                onDeviceBackend = value,
                onDeviceSummary = defaultOnDeviceSummary(value, currentStrings()),
            )
        }
        refreshOnDeviceSummary(value)
    }

    fun syncOnDeviceBackendWithRuntimeFlavor(runtimeFlavor: String) {
        val backendValue = when (runtimeFlavor) {
            "GGUF" -> BackendKind.LLAMA_CPP.persistedValue
            "LiteRT-LM" -> BackendKind.LITERT_LM.persistedValue
            else -> BackendKind.NONE.persistedValue
        }
        updateOnDeviceBackend(backendValue)
    }

    private fun defaultOnDeviceSummary(backendValue: String, strings: HermesStrings): String {
        return if (BackendKind.fromPersistedValue(backendValue) == BackendKind.NONE) {
            strings.remoteProviderMode()
        } else {
            strings.checkingPreferredLocalModel()
        }
    }

    private fun refreshOnDeviceSummary(backendValue: String) {
        onDeviceSummaryJob?.cancel()
        if (BackendKind.fromPersistedValue(backendValue) == BackendKind.NONE) {
            _uiState.update {
                if (it.onDeviceBackend == backendValue) {
                    it.copy(onDeviceSummary = currentStrings().remoteProviderMode())
                } else {
                    it
                }
            }
            return
        }
        onDeviceSummaryJob = viewModelScope.launch {
            val summary = withContext(Dispatchers.IO) {
                OnDeviceBackendManager.preferredDownloadSummary(getApplication(), backendValue)
            }
            _uiState.update {
                if (it.onDeviceBackend == backendValue) {
                    it.copy(onDeviceSummary = summary)
                } else {
                    it
                }
            }
        }
    }

    fun openProviderKeyPage(url: String) {
        openProviderKeyPage(providerId = "", url = url)
    }

    fun openProviderKeyPage(providerId: String, url: String) {
        val requestedUrl = url.trim()
        if (requestedUrl.isBlank()) {
            return
        }
        val resolvedProviderId = ProviderPresets.providerIdForSetupUrl(requestedUrl, providerId)
        val setupTarget = if (providerId.isNotBlank()) {
            resolvedProviderId?.let { nextProviderSetupTarget(it) }
        } else {
            null
        }
        val targetUrl = setupTarget?.url ?: requestedUrl
        if (HermesNetworkPolicy.isExternalNetworkBlocked(getApplication(), targetUrl)) {
            _uiState.update {
                it.copy(status = currentStrings().offlineProviderSetupBlocked(checking = false))
            }
            return
        }
        val uri = Uri.parse(targetUrl)
        if (uri.scheme !in setOf("http", "https")) {
            _uiState.update { it.copy(status = currentStrings().providerSetupUrlInvalid()) }
            return
        }
        val strings = currentStrings()
        val providerLabel = resolvedProviderId?.let { ProviderPresets.find(it)?.label }.orEmpty().ifBlank { strings.genericProviderLabel() }
        val launch = HermesProviderSetupWebActivity.open(
            context = getApplication(),
            uri = uri,
            title = strings.openProviderSetupTitle(providerLabel),
        )
        if (launch.success) {
            copyProviderKeyPage(resolvedProviderId.orEmpty(), targetUrl, updateSuccessStatus = false)
            _uiState.update {
                it.copy(status = providerSetupOpenedStatus(providerLabel, resolvedProviderId.orEmpty(), setupTarget))
            }
            probeProviderKeyPages(providerLabel, urlsForProviderKeyPage(resolvedProviderId, requestedUrl))
        } else {
            copyProviderKeyPage(resolvedProviderId.orEmpty(), targetUrl, updateSuccessStatus = false)
            _uiState.update {
                it.copy(status = strings.providerSetupOpenFailed(providerLabel, launch.errorName.ifBlank { "setup_page_error" }))
            }
        }
    }

    fun checkProviderKeyPage(url: String) {
        checkProviderKeyPage(providerId = "", url = url)
    }

    fun checkProviderKeyPage(providerId: String, url: String) {
        val requestedUrl = url.trim()
        if (requestedUrl.isBlank()) {
            return
        }
        val resolvedProviderId = ProviderPresets.providerIdForSetupUrl(requestedUrl, providerId)
        val strings = currentStrings()
        val providerLabel = resolvedProviderId?.let { ProviderPresets.find(it)?.label }.orEmpty().ifBlank { strings.genericProviderLabel() }
        val urls = resolvedProviderId?.let { ProviderPresets.setupUrls(it) }
            .orEmpty()
            .ifEmpty { listOf(requestedUrl) }
        if (urls.any { HermesNetworkPolicy.isExternalNetworkBlocked(getApplication(), it) }) {
            _uiState.update {
                it.copy(status = strings.offlineProviderSetupBlocked(checking = true))
            }
            return
        }
        copyProviderKeyPage(resolvedProviderId.orEmpty(), requestedUrl, updateSuccessStatus = false)
        _uiState.update { it.copy(status = strings.providerSetupChecking(providerLabel)) }
        probeProviderKeyPages(providerLabel, urls)
    }

    private fun urlsForProviderKeyPage(providerId: String?, requestedUrl: String): List<String> {
        return providerId?.let { ProviderPresets.setupUrls(it) }
            .orEmpty()
            .ifEmpty { listOf(requestedUrl) }
    }

    private fun probeProviderKeyPages(providerLabel: String, urls: List<String>) {
        if (urls.isEmpty()) {
            return
        }
        if (urls.any { HermesNetworkPolicy.isExternalNetworkBlocked(getApplication(), it) }) {
            _uiState.update {
                it.copy(status = HermesNetworkPolicy.offlineBlockedMessage("provider setup check"))
            }
            return
        }
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                urls.map(ProviderSetupUrlProbe::probe)
            }
            val status = providerSetupProbeStatus(providerLabel, results)
            _uiState.update { it.copy(status = status) }
        }
    }

    private fun providerSetupProbeStatus(
        providerLabel: String,
        results: List<ProviderSetupProbeResult>,
    ): String {
        val strings = currentStrings()
        val reachable = results.filter { it.reachable }
        val firstReachable = reachable.firstOrNull()
        return if (firstReachable != null) {
            strings.providerSetupReachable(
                label = providerLabel,
                url = firstReachable.url,
                statusLabel = firstReachable.statusLabel,
                reachableCount = reachable.size,
                totalCount = results.size,
                failedFallbackCount = results.size - reachable.size,
            )
        } else {
            val failureSummary = results.joinToString(separator = "; ") { "${it.url}: ${it.statusLabel}" }
            strings.providerSetupUnreachable(providerLabel, failureSummary)
                .take(ProviderSetupUrlProbe.MAX_STATUS_LENGTH)
        }
    }

    private fun nextProviderSetupTarget(providerId: String): ProviderSetupTarget? {
        val nextIndex = providerSetupOpenIndexes[providerId] ?: 0
        val target = ProviderPresets.setupTarget(providerId, nextIndex) ?: return null
        providerSetupOpenIndexes[providerId] = target.nextIndex
        return target
    }

    private fun providerSetupOpenedStatus(
        providerLabel: String,
        providerId: String,
        target: ProviderSetupTarget?,
    ): String {
        return currentStrings().providerSetupOpened(
            label = providerLabel,
            providerId = providerId,
            displayIndex = target?.displayIndex ?: 1,
            total = target?.total ?: 1,
        )
    }

    fun copyProviderKeyPage(url: String) {
        copyProviderKeyPage(providerId = "", url = url)
    }

    fun copyProviderKeyPage(providerId: String, url: String) {
        copyProviderKeyPage(providerId, url, updateSuccessStatus = true)
    }

    fun importSavedProviderCredential() {
        val snapshot = _uiState.value
        val preset = ProviderPresets.find(snapshot.provider)
        val providerLabel = preset?.label ?: snapshot.provider
        val strings = currentStrings()
        if (snapshot.provider.isBlank() || snapshot.provider == "custom") {
            _uiState.update { it.copy(status = strings.chooseSavedProviderCredential()) }
            return
        }
        val selectionGeneration = settingsSaveGeneration.beginSave()
        viewModelScope.launch {
            if (!settingsSaveGeneration.runIfCurrent(selectionGeneration) {
                    _uiState.update { it.copy(status = strings.checkingSavedProviderCredential(providerLabel)) }
                }
            ) return@launch
            val bundleResult = runCatching {
                withContext(Dispatchers.IO) {
                    settingsSaveGeneration.performLongIfCurrent(selectionGeneration) {
                        val app = getApplication<Application>()
                        HermesRuntimeManager.ensurePythonStarted(app)
                        Python.getInstance()
                            .getModule("hermes_android.auth_bridge")
                            .callAttr("read_provider_auth_bundle_json", snapshot.provider)
                            .toString()
                    }
                }
            }
            val payload = bundleResult.getOrElse { error ->
                if (error !is RuntimeSelectionSupersededException) {
                    settingsSaveGeneration.runIfCurrent(selectionGeneration) {
                        _uiState.update {
                            it.copy(status = strings.unableToReadSavedProviderCredential(error::class.java.simpleName))
                        }
                    }
                }
                return@launch
            }
            val json = runCatching { JSONObject(payload) }.getOrElse {
                settingsSaveGeneration.runIfCurrent(selectionGeneration) {
                    _uiState.update { it.copy(status = strings.savedProviderCredentialCouldNotBeDecoded(providerLabel)) }
                }
                return@launch
            }
            val apiKey = listOf(
                json.optString("api_key"),
                json.optString("access_token"),
                json.optString("session_token"),
            ).firstOrNull { it.isNotBlank() }.orEmpty()
            val configured = json.optBoolean("configured", false) || apiKey.isNotBlank()
            if (!configured || apiKey.isBlank()) {
                settingsSaveGeneration.runIfCurrent(selectionGeneration) {
                    _uiState.update { it.copy(status = strings.noSavedProviderCredential(providerLabel)) }
                }
                return@launch
            }

            val resolvedBaseUrl = json.optString("base_url")
                .ifBlank { snapshot.baseUrl }
                .ifBlank { preset?.baseUrl.orEmpty() }
            val resolvedModel = snapshot.model.ifBlank { preset?.modelHint.orEmpty() }
            val runtimeConfigBaseUrl = ProviderPresets.runtimeConfigBaseUrl(snapshot.provider, resolvedBaseUrl)
            runCatching {
                withContext(Dispatchers.IO) {
                    settingsSaveGeneration.withCurrent(selectionGeneration) {
                        // Durable fields become authoritative before any credential/config
                        // side effect or runtime restart is admitted.
                        settingsStore.update { current ->
                            current.copy(
                                provider = snapshot.provider,
                                baseUrl = resolvedBaseUrl,
                                model = resolvedModel,
                            )
                        }
                    }
                    settingsSaveGeneration.performLongIfCurrent(selectionGeneration) {
                        val app = getApplication<Application>()
                        HermesRuntimeManager.ensurePythonStarted(app)
                        val python = Python.getInstance()
                        PythonRuntimeWriteAuthority.writeIfCurrent(selectionGeneration) {
                            python.getModule("hermes_android.auth_bridge").callAttr(
                                "write_provider_auth_bundle",
                                snapshot.provider,
                                apiKey,
                                json.optString("access_token"),
                                json.optString("session_token"),
                                json.optString("refresh_token"),
                                resolvedBaseUrl,
                            )
                            python.getModule("hermes_android.config_bridge").callAttr(
                                "write_runtime_config",
                                snapshot.provider,
                                resolvedModel,
                                runtimeConfigBaseUrl,
                            )
                            secretsStore.saveApiKey(snapshot.provider, apiKey)
                        }
                    }
                    settingsSaveGeneration.performLongIfCurrent(selectionGeneration) {
                        val runtimeState = HermesRuntimeManager.restartAfterRemoteStop(
                            getApplication(),
                            admissionCheck = {
                                settingsSaveGeneration.requireCurrent(selectionGeneration)
                            },
                        )
                        runtimeState.error?.let { throw IllegalStateException(it) }
                    }
                }
            }.onSuccess {
                settingsSaveGeneration.runIfCurrent(selectionGeneration) {
                    _uiState.update {
                        it.copy(
                            baseUrl = resolvedBaseUrl,
                            model = resolvedModel,
                            apiKey = apiKey,
                            status = strings.importedSavedProviderCredential(providerLabel),
                        )
                    }
                }
            }.onFailure { error ->
                if (error !is RuntimeSelectionSupersededException) {
                    settingsSaveGeneration.runIfCurrent(selectionGeneration) {
                        _uiState.update {
                            it.copy(status = strings.savedProviderCredentialImportFailed(error::class.java.simpleName))
                        }
                    }
                }
            }
        }
    }

    private fun copyProviderKeyPage(providerId: String, url: String, updateSuccessStatus: Boolean) {
        val target = url.trim()
        if (target.isBlank()) {
            return
        }
        val resolvedProviderId = ProviderPresets.providerIdForSetupUrl(target, providerId)
        val setupText = resolvedProviderId?.let { ProviderPresets.setupClipboardText(it) }
            .orEmpty()
            .ifBlank { target }
        val fallbackCount = resolvedProviderId?.let { ProviderPresets.setupUrls(it).size - 1 } ?: 0
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val strings = currentStrings()
        val providerLabel = resolvedProviderId?.let { ProviderPresets.find(it)?.label }.orEmpty().ifBlank { strings.genericProviderLabel() }
        clipboard?.setPrimaryClip(ClipData.newPlainText(strings.providerSetupClipboardLabel(providerLabel), setupText))
        if (updateSuccessStatus) {
            _uiState.update { it.copy(status = strings.providerSetupCopied(providerLabel, fallbackCount)) }
        }
    }

    fun startLocalRuntimeForFlavor(runtimeFlavor: String): Boolean {
        val backendValue = when (runtimeFlavor) {
            "GGUF" -> BackendKind.LLAMA_CPP.persistedValue
            "LiteRT-LM" -> BackendKind.LITERT_LM.persistedValue
            else -> return false
        }
        val generation = settingsSaveGeneration.invalidate()
        return startLocalRuntimeForBackend(backendValue, generation, persistBackend = true)
    }

    internal fun startAcceptedLocalRuntimeHandoff(
        runtimeFlavor: String,
        selectionGeneration: Long,
    ): Boolean {
        val backendValue = when (runtimeFlavor) {
            "GGUF" -> BackendKind.LLAMA_CPP.persistedValue
            "LiteRT-LM" -> BackendKind.LITERT_LM.persistedValue
            else -> return false
        }
        return startLocalRuntimeForBackend(backendValue, selectionGeneration, persistBackend = false)
    }

    private fun startLocalRuntimeForBackend(
        backendValue: String,
        selectionGeneration: Long,
        persistBackend: Boolean,
    ): Boolean {
        val persisted = prepareLocalRuntimeHandoff(
            backendValue = backendValue,
            selectionGeneration = selectionGeneration,
            persistBackend = persistBackend,
        )
        if (persisted == null) {
            settingsSaveGeneration.runIfCurrent(selectionGeneration) {
                _uiState.update {
                    it.copy(status = currentStrings().settingsSaveFailed("storage"))
                }
            }
            return false
        }
        val startingSummary = defaultOnDeviceSummary(backendValue, currentStrings())
        val published = settingsSaveGeneration.runIfCurrent(selectionGeneration) {
            _uiState.update {
                it.copy(
                    onDeviceSummary = startingSummary,
                    status = currentStrings().startingLocalHermesRuntime(),
                )
            }
        }
        if (!published) return false
        saveInternal(
            persistLlamaCppAdvancedDraft = false,
            generation = selectionGeneration,
        )
        return true
    }

    /**
     * Adopt a model's required lane in both durable settings and the live Settings draft.
     * LocalModelDownloadsViewModel owns exact-artifact matching, while this ViewModel owns the
     * settings UI which would otherwise retain (and later save) a stale lane value.
     */
    internal fun adoptRequiredLlamaCppRuntimeLane(requiredLane: String?) {
        val requested = requiredLane?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return
        val normalized = AppSettings.normalizeLlamaCppRuntimeLane(requested)
        require(normalized == requested) {
            "Local model declared unsupported llama.cpp runtime lane: $requiredLane"
        }
        val generation = settingsSaveGeneration.invalidate()
        val persisted = try {
            settingsSaveGeneration.withCurrent(generation) {
                settingsStore.update { current ->
                    if (current.llamaCppRuntimeLane == normalized) current else current.copy(llamaCppRuntimeLane = normalized)
                }
            }
        } catch (error: RuntimeSelectionSupersededException) {
            return
        } catch (error: AppSettingsPersistenceException) {
            _uiState.update {
                it.copy(status = currentStrings().settingsSaveFailed(error::class.java.simpleName))
            }
            return
        }
        synchronized(llamaCppAdvancedDraftLock) {
            authoritativeLlamaCppSettings = persisted.llamaCppAdvancedSettingsTuple()
            _uiState.update { it.copy(llamaCppRuntimeLane = normalized) }
        }
    }

    /**
     * Mirror a lane which LocalModelDownloadsViewModel already committed under its click epoch.
     * This must remain UI-only: starting a second authority generation here would supersede the
     * still-running Download & Start action before it can publish its pending auto-start owner.
     */
    internal fun syncPersistedRequiredLlamaCppRuntimeLane(requiredLane: String?) {
        val requested = requiredLane?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return
        val normalized = AppSettings.normalizeLlamaCppRuntimeLane(requested)
        require(normalized == requested) {
            "Local model declared unsupported llama.cpp runtime lane: $requiredLane"
        }
        synchronized(llamaCppAdvancedDraftLock) {
            authoritativeLlamaCppSettings = settingsStore.load().llamaCppAdvancedSettingsTuple()
            _uiState.update { it.copy(llamaCppRuntimeLane = normalized) }
        }
    }

    /**
     * Persist the backend handoff first, then reload the authoritative advanced tuple. A local
     * model selection may have updated its required lane through a separate ViewModel/store
     * instance, so the Settings draft must not be allowed to overwrite it during [save].
     */
    internal fun prepareLocalRuntimeHandoff(backendValue: String): AppSettings? {
        val generation = settingsSaveGeneration.invalidate()
        return prepareLocalRuntimeHandoff(backendValue, generation, persistBackend = true)
    }

    private fun prepareLocalRuntimeHandoff(
        backendValue: String,
        selectionGeneration: Long,
        persistBackend: Boolean,
    ): AppSettings? {
        return try {
            settingsSaveGeneration.withCurrent(selectionGeneration) {
                val current = settingsStore.load()
                val pendingId = downloadStore.pendingAutoStartRecordId()
                val pendingAccepted = if (persistBackend && pendingId.isNotBlank()) {
                    downloadStore.clearPendingAutoStartRecordId(pendingId)
                } else {
                    clearContradictoryPendingAutoStart(
                        downloadStore = downloadStore,
                        selectedBackend = BackendKind.fromPersistedValue(backendValue),
                        selectedLlamaCppRuntimeLane = current.llamaCppRuntimeLane,
                    )
                }
                if (!pendingAccepted) return@withCurrent null
                val persisted = if (persistBackend) {
                    if (!settingsStore.persistOnDeviceBackend(backendValue)) return@withCurrent null
                    settingsStore.load()
                } else {
                    settingsStore.load().takeIf { it.onDeviceBackend == backendValue }
                        ?: return@withCurrent null
                }
                synchronized(llamaCppAdvancedDraftLock) {
                    val authoritativeSettings = persisted.llamaCppAdvancedSettingsTuple()
                    authoritativeLlamaCppSettings = authoritativeSettings
                    _uiState.update {
                        it.withLlamaCppAdvancedSettings(authoritativeSettings).copy(
                            onDeviceBackend = backendValue,
                        )
                    }
                }
                persisted
            }
        } catch (_: RuntimeSelectionSupersededException) {
            null
        } catch (error: AppSettingsPersistenceException) {
            _uiState.update {
                it.copy(status = currentStrings().settingsSaveFailed(error::class.java.simpleName))
            }
            null
        } catch (error: LocalModelDownloadPersistenceException) {
            _uiState.update {
                it.copy(status = currentStrings().settingsSaveFailed(error::class.java.simpleName))
            }
            null
        }
    }

    fun selectLanguage(language: AppLanguage) {
        val normalized = language.tag
        // Persist first so AppShell / other screens reading AppSettingsStore see the new tag.
        val updated = persistSettingsOrReport { current -> current.copy(languageTag = normalized) } ?: return
        val strings = hermesStringsFor(language)
        _uiState.update {
            it.copy(
                languageTag = updated.languageTag,
                status = strings.languageSwitchedTo(language.nativeLabel),
            )
        }
    }

    fun save() {
        saveInternal(persistLlamaCppAdvancedDraft = false)
    }

    private fun saveWithLlamaCppAdvancedDraft() {
        saveInternal(persistLlamaCppAdvancedDraft = true)
    }

    private fun saveInternal(
        persistLlamaCppAdvancedDraft: Boolean,
        generation: Long = settingsSaveGeneration.beginSave(),
    ) {
        val snapshot = _uiState.value
        val expectedDraft = captureLlamaCppAdvancedDraft()
        val strings = hermesStringsFor(AppLanguage.fromTag(snapshot.languageTag))
        viewModelScope.launch {
            val publishedSaveStarted = settingsSaveGeneration.runIfCurrent(generation) {
                _uiState.update { it.copy(status = strings.settingsSaveStarted()) }
            }
            if (!publishedSaveStarted) return@launch
            runCatching {
                withContext(Dispatchers.IO) {
                    val currentBeforeSave = settingsStore.load()
                    val persistedLlamaCppSettings = resolveLlamaCppAdvancedSettingsForSave(
                        existing = currentBeforeSave,
                        draft = snapshot,
                        persistDraft = persistLlamaCppAdvancedDraft,
                    )
                    updateSettingsAndPendingForGeneration(
                        store = settingsStore,
                        downloadStore = downloadStore,
                        saveGeneration = settingsSaveGeneration,
                        generation = generation,
                        selectedBackend = BackendKind.fromPersistedValue(snapshot.onDeviceBackend),
                        selectedLlamaCppRuntimeLane = persistedLlamaCppSettings.llamaCppRuntimeLane,
                    ) { current ->
                        current.copy(
                            provider = snapshot.provider,
                            baseUrl = snapshot.baseUrl,
                            model = snapshot.model,
                            dataSaverMode = snapshot.dataSaverMode,
                            offlineAirplaneMode = snapshot.offlineAirplaneMode,
                            onDeviceBackend = snapshot.onDeviceBackend,
                            liteRtLmSpeculativeDecodingMode = snapshot.liteRtLmSpeculativeDecodingMode,
                            llamaCppRuntimeLane = persistedLlamaCppSettings.llamaCppRuntimeLane,
                            llamaCppCacheTypeK = persistedLlamaCppSettings.llamaCppCacheTypeK,
                            llamaCppCacheTypeV = persistedLlamaCppSettings.llamaCppCacheTypeV,
                            llamaCppFlashAttention = persistedLlamaCppSettings.llamaCppFlashAttention,
                            llamaCppAdditionalArguments = persistedLlamaCppSettings.llamaCppAdditionalArguments,
                            localModelMaxTokens = snapshot.localModelMaxTokens,
                            localModelTopK = snapshot.localModelTopK,
                            localModelTopP = snapshot.localModelTopP,
                            localModelTemperature = snapshot.localModelTemperature,
                            localModelAccelerator = snapshot.localModelAccelerator,
                            localModelToolMode = snapshot.localModelToolMode,
                            apiGenerationKnobsEnabled = snapshot.apiGenerationKnobsEnabled,
                            languageTag = snapshot.languageTag,
                            customSystemPrompt = AppSettings.normalizeCustomSystemPrompt(snapshot.customSystemPrompt),
                            chatDisplayMode = normalizeChatDisplayMode(snapshot.chatDisplayMode),
                            keywordHighlightingEnabled = snapshot.keywordHighlightingEnabled,
                            themePrimaryHex = normalizeThemeHex(snapshot.themePrimaryHex, AppSettings.DEFAULT_THEME_PRIMARY_HEX),
                            themeSecondaryHex = normalizeThemeHex(snapshot.themeSecondaryHex, AppSettings.DEFAULT_THEME_SECONDARY_HEX),
                            themeBackgroundHex = normalizeThemeHex(snapshot.themeBackgroundHex, AppSettings.DEFAULT_THEME_BACKGROUND_HEX),
                            themeSurfaceHex = normalizeThemeHex(snapshot.themeSurfaceHex, AppSettings.DEFAULT_THEME_SURFACE_HEX),
                            themeSurfaceVariantHex = normalizeThemeHex(snapshot.themeSurfaceVariantHex, AppSettings.DEFAULT_THEME_SURFACE_VARIANT_HEX),
                            themeCardShape = normalizeThemeCardShape(snapshot.themeCardShape),
                            uiFontScale = AppSettings.normalizeUiFontScale(snapshot.uiFontScale),
                        )
                    }

                    val app = getApplication<Application>()
                    val backendKind = BackendKind.fromPersistedValue(snapshot.onDeviceBackend)
                    val remoteStopState = settingsSaveGeneration.performLongIfCurrent(generation) {
                        HermesRuntimeManager.stopRemoteRuntime(
                            admissionCheck = { settingsSaveGeneration.requireCurrent(generation) },
                        )
                    }
                    remoteStopState.error?.let { failureMessage ->
                        return@withContext settingsSaveGeneration.withCurrent(generation) {
                            SettingsSaveResult(
                                apiKey = "",
                                onDeviceSummary = failureMessage,
                                statusMessage = failureMessage,
                                authoritativeLlamaCppSettings =
                                    settingsStore.load().llamaCppAdvancedSettingsTuple(),
                            )
                        }
                    }

                    val localBackendStatus = settingsSaveGeneration.performLongIfCurrent(generation) {
                        OnDeviceBackendManager.ensureConfigured(
                            app,
                            snapshot.onDeviceBackend,
                            admissionCheck = { settingsSaveGeneration.requireCurrent(generation) },
                        )
                    }
                    settingsSaveUnsafeTransitionMessage(localBackendStatus)?.let { failureMessage ->
                        return@withContext settingsSaveGeneration.withCurrent(generation) {
                            SettingsSaveResult(
                                apiKey = "",
                                onDeviceSummary = failureMessage,
                                statusMessage = failureMessage,
                                authoritativeLlamaCppSettings =
                                    settingsStore.load().llamaCppAdvancedSettingsTuple(),
                            )
                        }
                    }

                    val configuredLocalBackend = localBackendStatus.started
                    val effectiveProvider = if (configuredLocalBackend) "custom" else snapshot.provider
                    val effectiveModel = if (configuredLocalBackend) localBackendStatus.modelName else snapshot.model
                    val effectiveBaseUrl = if (configuredLocalBackend) {
                        localBackendStatus.baseUrl
                    } else {
                        ProviderPresets.runtimeConfigBaseUrl(snapshot.provider, snapshot.baseUrl)
                    }
                    settingsSaveGeneration.performLongIfCurrent(generation) {
                        HermesRuntimeManager.ensurePythonStarted(app)
                        PythonRuntimeWriteAuthority.writeIfCurrent(generation) {
                            Python.getInstance().getModule("hermes_android.config_bridge").callAttr(
                                "write_runtime_config",
                                effectiveProvider,
                                effectiveModel,
                                effectiveBaseUrl,
                            )
                        }
                    }
                    val parsedCredential = ProviderPresets.parseCredentialInput(snapshot.provider, snapshot.apiKey)
                    val providerApiKey = parsedCredential.apiKey
                    val preservedBlankCredential = providerApiKey.isBlank() &&
                        secretsStore.loadApiKey(snapshot.provider).isNotBlank()
                    if (providerApiKey.isNotBlank()) {
                        settingsSaveGeneration.performLongIfCurrent(generation) {
                            PythonRuntimeWriteAuthority.writeIfCurrent(generation) {
                                secretsStore.saveApiKey(snapshot.provider, providerApiKey)
                                Python.getInstance().getModule("hermes_android.auth_bridge").callAttr(
                                    "write_provider_api_key",
                                    snapshot.provider,
                                    providerApiKey,
                                )
                            }
                        }
                    }
                    val (finalRuntimeState, finalLocalBackendStatus) = settingsSaveGeneration.performLongIfCurrent(generation) {
                        HermesRuntimeManager.ensureStarted(
                            app,
                            admissionCheck = { settingsSaveGeneration.requireCurrent(generation) },
                        ) to OnDeviceBackendManager.currentStatus()
                    }
                    settingsRuntimeTransitionFailureMessage(
                        backendKind = backendKind,
                        offlineAirplaneMode = snapshot.offlineAirplaneMode,
                        localBackendStatus = finalLocalBackendStatus,
                        runtimeState = finalRuntimeState,
                    )?.let { failureMessage ->
                        return@withContext settingsSaveGeneration.withCurrent(generation) {
                            SettingsSaveResult(
                                apiKey = providerApiKey,
                                onDeviceSummary = failureMessage,
                                statusMessage = failureMessage,
                                authoritativeLlamaCppSettings =
                                    settingsStore.load().llamaCppAdvancedSettingsTuple(),
                            )
                        }
                    }
                    val useLocalBackend = finalLocalBackendStatus.started
                    val backendSummary = if (useLocalBackend) {
                        strings.localBackendReady(
                            backend = finalLocalBackendStatus.backendKind.persistedValue,
                            model = finalLocalBackendStatus.modelName,
                        )
                    } else {
                        OnDeviceBackendManager.preferredDownloadSummary(app, snapshot.onDeviceBackend)
                    }
                    val statusMessage = when {
                        useLocalBackend -> strings.onDeviceBackendReady()
                        snapshot.offlineAirplaneMode ->
                            strings.offlineAirplaneKeptRemoteFallbackDisabled(finalLocalBackendStatus.statusMessage)
                        backendKind != BackendKind.NONE ->
                            strings.stayedOnSavedRemoteProvider(finalLocalBackendStatus.statusMessage)
                        parsedCredential.importedFromEnvLine ->
                            strings.settingsSavedImportedCredential(parsedCredential.sourceLabel)
                        snapshot.dataSaverMode -> strings.settingsSavedDataSaver()
                        preservedBlankCredential -> strings.settingsSavedPreservedCredential()
                        else -> strings.settingsSavedBackendRestarted()
                    }
                    settingsSaveGeneration.withCurrent(generation) {
                        SettingsSaveResult(
                            apiKey = providerApiKey,
                            onDeviceSummary = backendSummary,
                            statusMessage = statusMessage,
                            authoritativeLlamaCppSettings =
                                settingsStore.load().llamaCppAdvancedSettingsTuple(),
                        )
                    }
                }
            }.onSuccess { result ->
                publishAuthoritativeLlamaCppSettingsForGeneration(
                    generation = generation,
                    expectedDraft = expectedDraft,
                    authoritativeSettings = result.authoritativeLlamaCppSettings,
                    allowExistingDraftChanges = persistLlamaCppAdvancedDraft,
                ) {
                    it.copy(
                        onDeviceSummary = result.onDeviceSummary,
                        apiKey = result.apiKey.ifBlank { it.apiKey },
                        status = result.statusMessage,
                    )
                }
            }.onFailure { error ->
                if (error is SettingsSaveSupersededException) return@onFailure
                settingsSaveGeneration.runIfCurrent(generation) {
                    _uiState.update {
                        it.copy(status = strings.settingsSaveFailed(error::class.java.simpleName))
                    }
                }
            }
        }
    }

    private fun normalizeSpeculativeDecodingMode(value: String): String {
        return when (value.trim().lowercase()) {
            "enabled", "on", "force" -> "enabled"
            "disabled", "off" -> "disabled"
            else -> "auto"
        }
    }

    private fun normalizeChatDisplayMode(value: String): String {
        return when (value.trim().lowercase()) {
            "expanded", "classic", "full" -> "expanded"
            else -> "compact"
        }
    }

    private fun normalizeThemeCardShape(value: String): String {
        return when (value.trim().lowercase()) {
            "square", "squared" -> "square"
            "soft" -> "soft"
            else -> "rounded"
        }
    }
}

internal fun resolveLlamaCppAdvancedSettingsForGeneralSave(
    existing: AppSettings,
    draft: SettingsUiState,
): AppSettings {
    val valid = llamaCppAdvancedValidationKey(
        lane = draft.llamaCppRuntimeLane,
        cacheTypeK = draft.llamaCppCacheTypeK,
        cacheTypeV = draft.llamaCppCacheTypeV,
        flashAttention = draft.llamaCppFlashAttention,
        additionalArguments = draft.llamaCppAdditionalArguments,
    ) == null
    if (!valid) {
        // Preserve the entire tuple. Mixing a rejected raw argv draft with a new
        // lane/cache/Flash selection could make formerly valid durable settings invalid.
        return existing
    }
    return existing.copy(
        llamaCppRuntimeLane = AppSettings.normalizeLlamaCppRuntimeLane(draft.llamaCppRuntimeLane),
        llamaCppCacheTypeK = AppSettings.normalizeLlamaCppCacheType(draft.llamaCppCacheTypeK),
        llamaCppCacheTypeV = AppSettings.normalizeLlamaCppCacheType(draft.llamaCppCacheTypeV),
        llamaCppFlashAttention = AppSettings.normalizeLlamaCppFlashAttention(draft.llamaCppFlashAttention),
        llamaCppAdditionalArguments = AppSettings.normalizeLlamaCppAdditionalArguments(
            draft.llamaCppAdditionalArguments,
        ),
    )
}

internal fun resolveLlamaCppAdvancedSettingsForSave(
    existing: AppSettings,
    draft: SettingsUiState,
    persistDraft: Boolean,
): AppSettings {
    // The advanced card has its own explicit Apply action. Ordinary Settings saves preserve the
    // durable tuple so a catalog-required lane written by LocalModelDownloadsViewModel cannot be
    // clobbered by this ViewModel's older in-memory draft.
    return if (persistDraft) {
        resolveLlamaCppAdvancedSettingsForGeneralSave(existing, draft)
    } else {
        existing
    }
}

internal fun llamaCppAdvancedValidationKey(
    lane: String,
    cacheTypeK: String,
    cacheTypeV: String,
    flashAttention: String,
    additionalArguments: List<String> = emptyList(),
): String? {
    val normalizedLane = AppSettings.normalizeLlamaCppRuntimeLane(lane)
    val normalizedK = AppSettings.normalizeLlamaCppCacheType(cacheTypeK)
    val normalizedV = AppSettings.normalizeLlamaCppCacheType(cacheTypeV)
    val normalizedFlash = AppSettings.normalizeLlamaCppFlashAttention(flashAttention)
    val turboSelected = normalizedK.startsWith("turbo") || normalizedV.startsWith("turbo")
    val quantizedV = normalizedV in setOf(
        "q8_0",
        "q4_0",
        "q4_1",
        "iq4_nl",
        "q5_0",
        "q5_1",
        "turbo2",
        "turbo3",
        "turbo4",
    )
    val structuredValidationKey = when {
        turboSelected && normalizedLane != "turboquant" -> "invalid_stable_turbo"
        quantizedV && normalizedFlash == "off" -> "invalid_quantized_v_flash_off"
        turboSelected && normalizedFlash == "off" -> "invalid_turbo_flash_off"
        else -> null
    }
    if (structuredValidationKey != null) return structuredValidationKey

    val fullValidation = LlamaCppLaunchConfig.fromPersistedValues(
        lane = normalizedLane,
        cacheTypeK = normalizedK,
        cacheTypeV = normalizedV,
        flashAttention = normalizedFlash,
        // Validate the lossless draft. Normalizing first would hide the 65th token,
        // truncate token 257, and remove blank/control tokens before they can be rejected.
        additionalArguments = additionalArguments,
    ).validate()
    return if (fullValidation.valid) null else "invalid_arguments"
}

internal fun settingsSaveUnsafeTransitionMessage(status: LocalBackendStatus): String? {
    if (!status.requiresAppRestart) return null
    return status.statusMessage.ifBlank {
        "The previous local runtime did not stop safely. Force stop and reopen Hermes before switching providers."
    }
}

internal fun settingsRuntimeTransitionFailureMessage(
    backendKind: BackendKind,
    offlineAirplaneMode: Boolean,
    localBackendStatus: LocalBackendStatus,
    runtimeState: HermesRuntimeManager.RuntimeState,
): String? {
    settingsSaveUnsafeTransitionMessage(localBackendStatus)?.let { return it }
    if (backendKind != BackendKind.NONE && !localBackendStatus.started) {
        return localBackendStatus.statusMessage.ifBlank {
            runtimeState.error ?: "The selected local backend did not start."
        }
    }
    if (backendKind != BackendKind.NONE && !runtimeState.started) {
        return runtimeState.error ?: "The selected local backend was not published as ready."
    }
    if (backendKind == BackendKind.NONE && !offlineAirplaneMode && !runtimeState.started) {
        return runtimeState.error ?: "The remote Hermes runtime did not start."
    }
    return null
}

data class AppearanceThemePreset(
    val id: String,
    /** Canonical English fallback; every visible label is resolved by HermesStrings from [id]. */
    val label: String,
    val primaryHex: String,
    val secondaryHex: String,
    val backgroundHex: String,
    val surfaceHex: String,
    val surfaceVariantHex: String,
)

val appearanceThemePresets = listOf(
    AppearanceThemePreset(
        id = "hermes",
        label = "Hermes emerald",
        primaryHex = AppSettings.DEFAULT_THEME_PRIMARY_HEX,
        secondaryHex = AppSettings.DEFAULT_THEME_SECONDARY_HEX,
        backgroundHex = AppSettings.DEFAULT_THEME_BACKGROUND_HEX,
        surfaceHex = AppSettings.DEFAULT_THEME_SURFACE_HEX,
        surfaceVariantHex = AppSettings.DEFAULT_THEME_SURFACE_VARIANT_HEX,
    ),
    AppearanceThemePreset(
        id = "legacy",
        label = "Legacy purple",
        primaryHex = "#8C7BFF",
        secondaryHex = "#C6A15B",
        backgroundHex = "#090B10",
        surfaceHex = "#11141C",
        surfaceVariantHex = "#1B202B",
    ),
    AppearanceThemePreset(
        id = "gold",
        label = "Gold noir",
        primaryHex = "#D2B35E",
        secondaryHex = "#8C7BFF",
        backgroundHex = "#080808",
        surfaceHex = "#14130F",
        surfaceVariantHex = "#211D14",
    ),
    AppearanceThemePreset(
        id = "graphite",
        label = "Graphite",
        primaryHex = "#9AA4B2",
        secondaryHex = "#72D6C9",
        backgroundHex = "#090A0C",
        surfaceHex = "#13161B",
        surfaceVariantHex = "#20252D",
    ),
    AppearanceThemePreset(
        id = "contrast",
        label = "High contrast",
        primaryHex = "#B6A7FF",
        secondaryHex = "#FFD166",
        backgroundHex = "#000000",
        surfaceHex = "#0E0E12",
        surfaceVariantHex = "#24242C",
    ),
)
