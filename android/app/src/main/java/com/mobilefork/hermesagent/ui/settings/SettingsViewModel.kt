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
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import com.mobilefork.hermesagent.auth.ProviderSetupProbeResult
import com.mobilefork.hermesagent.auth.ProviderSetupUrlProbe
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.HermesNetworkPolicy
import com.mobilefork.hermesagent.data.ProviderPresets
import com.mobilefork.hermesagent.data.ProviderSetupTarget
import com.mobilefork.hermesagent.data.SecureSecretsStore
import com.mobilefork.hermesagent.device.HermesProviderSetupWebActivity
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.HermesStrings
import com.mobilefork.hermesagent.ui.i18n.hermesStringsFor
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
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = AppSettingsStore(application)
    private val secretsStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SecureSecretsStore(getApplication<Application>())
    }
    private val providerSetupOpenIndexes = mutableMapOf<String, Int>()
    private var onDeviceSummaryJob: Job? = null

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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

    fun reload() {
        val reloaded = loadInitialState()
        _uiState.value = reloaded
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
        viewModelScope.launch(Dispatchers.IO) {
            val runtime = if (forceStart) {
                HermesRuntimeManager.ensureStarted(getApplication())
            } else {
                HermesRuntimeManager.currentState()
            }
            _uiState.update {
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
        val existing = settingsStore.load()
        settingsStore.save(existing.copy(offlineAirplaneMode = enabled))
        val strings = currentStrings()
        val remoteStopFailure = if (enabled) {
            HermesRuntimeManager.stopRemoteRuntime().error
        } else {
            null
        }
        _uiState.update {
            it.copy(
                offlineAirplaneMode = enabled,
                status = remoteStopFailure ?: strings.offlineAirplaneStatus(enabled),
            )
        }
    }
    fun updateLiteRtLmSpeculativeDecodingMode(value: String) = _uiState.update {
        it.copy(liteRtLmSpeculativeDecodingMode = normalizeSpeculativeDecodingMode(value))
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
        settingsStore.save(settingsStore.load().copy(customSystemPrompt = normalized))
        _uiState.update {
            val strings = currentStrings()
            it.copy(
                customSystemPrompt = normalized,
                status = if (normalized.isBlank()) {
                    strings.agentPersonaCleared()
                } else {
                    strings.agentPersonaSaved()
                },
            )
        }
    }

    fun clearAgentPersona() {
        settingsStore.save(settingsStore.load().copy(customSystemPrompt = ""))
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
        val updated = settingsStore.load().copy(
            localModelMaxTokens = AppSettings.normalizeLocalModelMaxTokens(snapshot.localModelMaxTokens),
            localModelTopK = AppSettings.normalizeLocalModelTopK(snapshot.localModelTopK),
            localModelTopP = AppSettings.normalizeLocalModelTopP(snapshot.localModelTopP),
            localModelTemperature = AppSettings.normalizeLocalModelTemperature(snapshot.localModelTemperature),
            localModelAccelerator = AppSettings.normalizeLocalModelAccelerator(snapshot.localModelAccelerator),
            localModelToolMode = AppSettings.normalizeLocalModelToolMode(snapshot.localModelToolMode),
            apiGenerationKnobsEnabled = snapshot.apiGenerationKnobsEnabled,
            customSystemPrompt = normalizedPrompt,
        )
        settingsStore.save(updated)
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

    fun updateChatDisplayMode(value: String) {
        val normalized = normalizeChatDisplayMode(value)
        settingsStore.save(settingsStore.load().copy(chatDisplayMode = normalized))
        _uiState.update {
            it.copy(
                chatDisplayMode = normalized,
                status = currentStrings().chatDisplayModeSet(normalized),
            )
        }
    }
    fun updateKeywordHighlighting(enabled: Boolean) {
        settingsStore.save(settingsStore.load().copy(keywordHighlightingEnabled = enabled))
        _uiState.update {
            it.copy(
                keywordHighlightingEnabled = enabled,
                status = currentStrings().keywordHighlightingStatus(enabled),
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
        settingsStore.save(settingsStore.load().copy(themeCardShape = normalized))
        _uiState.update {
            it.copy(
                themeCardShape = normalized,
                status = currentStrings().cardShapeSet(normalized),
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
        val existing = settingsStore.load()
        val updated = existing.copy(
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
        settingsStore.save(updated)
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
        viewModelScope.launch {
            _uiState.update { it.copy(status = strings.checkingSavedProviderCredential(providerLabel)) }
            val bundleResult = runCatching {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    HermesRuntimeManager.ensurePythonStarted(app)
                    Python.getInstance()
                        .getModule("hermes_android.auth_bridge")
                        .callAttr("read_provider_auth_bundle_json", snapshot.provider)
                        .toString()
                }
            }
            val payload = bundleResult.getOrElse { error ->
                _uiState.update {
                    it.copy(status = strings.unableToReadSavedProviderCredential(error::class.java.simpleName))
                }
                return@launch
            }
            val json = runCatching { JSONObject(payload) }.getOrElse {
                _uiState.update { it.copy(status = strings.savedProviderCredentialCouldNotBeDecoded(providerLabel)) }
                return@launch
            }
            val apiKey = listOf(
                json.optString("api_key"),
                json.optString("access_token"),
                json.optString("session_token"),
            ).firstOrNull { it.isNotBlank() }.orEmpty()
            val configured = json.optBoolean("configured", false) || apiKey.isNotBlank()
            if (!configured || apiKey.isBlank()) {
                _uiState.update { it.copy(status = strings.noSavedProviderCredential(providerLabel)) }
                return@launch
            }

            val resolvedBaseUrl = json.optString("base_url")
                .ifBlank { snapshot.baseUrl }
                .ifBlank { preset?.baseUrl.orEmpty() }
            val resolvedModel = snapshot.model.ifBlank { preset?.modelHint.orEmpty() }
            val runtimeConfigBaseUrl = ProviderPresets.runtimeConfigBaseUrl(snapshot.provider, resolvedBaseUrl)
            val existingSettings = settingsStore.load()
            val updatedSettings = existingSettings.copy(
                provider = snapshot.provider,
                baseUrl = resolvedBaseUrl,
                model = resolvedModel,
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    HermesRuntimeManager.ensurePythonStarted(app)
                    val python = Python.getInstance()
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
                }
                settingsStore.save(updatedSettings)
                secretsStore.saveApiKey(snapshot.provider, apiKey)
                val runtimeState = HermesRuntimeManager.restartAfterRemoteStop(getApplication())
                runtimeState.error?.let { throw IllegalStateException(it) }
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        baseUrl = resolvedBaseUrl,
                        model = resolvedModel,
                        apiKey = apiKey,
                        status = strings.importedSavedProviderCredential(providerLabel),
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(status = strings.savedProviderCredentialImportFailed(error::class.java.simpleName))
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
        if (!settingsStore.persistOnDeviceBackend(backendValue)) {
            _uiState.update {
                it.copy(status = currentStrings().settingsSaveFailed("storage"))
            }
            return false
        }
        _uiState.update {
            it.copy(
                onDeviceBackend = backendValue,
                onDeviceSummary = OnDeviceBackendManager.preferredDownloadSummary(getApplication(), backendValue),
                status = currentStrings().startingLocalHermesRuntime(),
            )
        }
        save()
        return true
    }

    fun selectLanguage(language: AppLanguage) {
        val normalized = language.tag
        // Persist first so AppShell / other screens reading AppSettingsStore see the new tag.
        settingsStore.save(settingsStore.load().copy(languageTag = normalized))
        val strings = hermesStringsFor(language)
        _uiState.update {
            it.copy(
                languageTag = normalized,
                status = strings.languageSwitchedTo(language.nativeLabel),
            )
        }
    }

    fun save() {
        val snapshot = _uiState.value
        val strings = hermesStringsFor(AppLanguage.fromTag(snapshot.languageTag))
        viewModelScope.launch {
            _uiState.update { it.copy(status = strings.settingsSaveStarted()) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val existingSettings = settingsStore.load()
                    val updatedSettings = existingSettings.copy(
                        provider = snapshot.provider,
                        baseUrl = snapshot.baseUrl,
                        model = snapshot.model,
                        dataSaverMode = snapshot.dataSaverMode,
                        offlineAirplaneMode = snapshot.offlineAirplaneMode,
                        onDeviceBackend = snapshot.onDeviceBackend,
                        liteRtLmSpeculativeDecodingMode = snapshot.liteRtLmSpeculativeDecodingMode,
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
                    settingsStore.save(updatedSettings)

                    val app = getApplication<Application>()
                    val backendKind = BackendKind.fromPersistedValue(snapshot.onDeviceBackend)
                    val remoteStopState = HermesRuntimeManager.stopRemoteRuntime()
                    remoteStopState.error?.let { failureMessage ->
                        return@withContext SettingsSaveResult(
                            apiKey = "",
                            onDeviceSummary = failureMessage,
                            statusMessage = failureMessage,
                        )
                    }

                    val localBackendStatus = OnDeviceBackendManager.ensureConfigured(app, snapshot.onDeviceBackend)
                    settingsSaveUnsafeTransitionMessage(localBackendStatus)?.let { failureMessage ->
                        return@withContext SettingsSaveResult(
                            apiKey = "",
                            onDeviceSummary = failureMessage,
                            statusMessage = failureMessage,
                        )
                    }

                    HermesRuntimeManager.ensurePythonStarted(app)
                    val configuredLocalBackend = localBackendStatus.started
                    val effectiveProvider = if (configuredLocalBackend) "custom" else snapshot.provider
                    val effectiveModel = if (configuredLocalBackend) localBackendStatus.modelName else snapshot.model
                    val effectiveBaseUrl = if (configuredLocalBackend) {
                        localBackendStatus.baseUrl
                    } else {
                        ProviderPresets.runtimeConfigBaseUrl(snapshot.provider, snapshot.baseUrl)
                    }
                    Python.getInstance().getModule("hermes_android.config_bridge").callAttr(
                        "write_runtime_config",
                        effectiveProvider,
                        effectiveModel,
                        effectiveBaseUrl,
                    )
                    val parsedCredential = ProviderPresets.parseCredentialInput(snapshot.provider, snapshot.apiKey)
                    val providerApiKey = parsedCredential.apiKey
                    val preservedBlankCredential = providerApiKey.isBlank() &&
                        secretsStore.loadApiKey(snapshot.provider).isNotBlank()
                    if (providerApiKey.isNotBlank()) {
                        secretsStore.saveApiKey(snapshot.provider, providerApiKey)
                        Python.getInstance().getModule("hermes_android.auth_bridge").callAttr(
                            "write_provider_api_key",
                            snapshot.provider,
                            providerApiKey,
                        )
                    }
                    val finalRuntimeState = HermesRuntimeManager.ensureStarted(app)
                    val finalLocalBackendStatus = OnDeviceBackendManager.currentStatus()
                    settingsRuntimeTransitionFailureMessage(
                        backendKind = backendKind,
                        offlineAirplaneMode = snapshot.offlineAirplaneMode,
                        localBackendStatus = finalLocalBackendStatus,
                        runtimeState = finalRuntimeState,
                    )?.let { failureMessage ->
                        return@withContext SettingsSaveResult(
                            apiKey = providerApiKey,
                            onDeviceSummary = failureMessage,
                            statusMessage = failureMessage,
                        )
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
                    SettingsSaveResult(
                        apiKey = providerApiKey,
                        onDeviceSummary = backendSummary,
                        statusMessage = statusMessage,
                    )
                }
            }.onSuccess { result ->
                _uiState.update {
                    it.copy(
                        onDeviceSummary = result.onDeviceSummary,
                        apiKey = result.apiKey.ifBlank { it.apiKey },
                        status = result.statusMessage,
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(status = strings.settingsSaveFailed(error::class.java.simpleName))
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
