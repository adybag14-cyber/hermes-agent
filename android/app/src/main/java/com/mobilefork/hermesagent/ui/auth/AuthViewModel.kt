package com.mobilefork.hermesagent.ui.auth

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobilefork.hermesagent.auth.AuthRuntimeApplier
import com.mobilefork.hermesagent.auth.CodexDeviceCodeAuth
import com.mobilefork.hermesagent.auth.CodexLoopbackOAuthServer
import com.mobilefork.hermesagent.auth.CodexOAuthClient
import com.mobilefork.hermesagent.auth.Corr3xtAuthClient
import com.mobilefork.hermesagent.auth.NousDeviceCodeAuth
import com.mobilefork.hermesagent.auth.OpenRouterLoopbackOAuthServer
import com.mobilefork.hermesagent.auth.OpenRouterOAuthClient
import com.mobilefork.hermesagent.auth.ProviderSetupProbeResult
import com.mobilefork.hermesagent.auth.ProviderSetupUrlProbe
import com.mobilefork.hermesagent.auth.XaiLoopbackOAuthServer
import com.mobilefork.hermesagent.auth.XaiOAuthClient
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.AuthCatalog
import com.mobilefork.hermesagent.data.AuthOption
import com.mobilefork.hermesagent.data.AuthScope
import com.mobilefork.hermesagent.data.AuthSession
import com.mobilefork.hermesagent.data.AuthSessionStore
import com.mobilefork.hermesagent.data.PendingAuthRequest
import com.mobilefork.hermesagent.data.ProviderPresets
import com.mobilefork.hermesagent.data.ProviderSetupTarget
import com.mobilefork.hermesagent.device.BrowserLaunchResult
import com.mobilefork.hermesagent.device.HermesExternalBrowserLauncher
import com.mobilefork.hermesagent.device.HermesProviderSetupWebActivity
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.HermesStrings
import com.mobilefork.hermesagent.ui.i18n.hermesStringsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class AuthOptionUiState(
    val id: String,
    val label: String,
    val description: String,
    val scope: AuthScope,
    val runtimeProvider: String = "",
    val credentialInput: String = "",
    val credentialInputHelp: String = "",
    val signedIn: Boolean = false,
    val status: String = "Not signed in",
    val accountHint: String = "",
    val supportsApiKeySetup: Boolean = false,
    val supportsBrowserSignIn: Boolean = true,
    val browserSignInEnabled: Boolean = true,
    val providerSetupUrl: String = "",
)

data class AuthUiState(
    val corr3xtBaseUrl: String = "",
    val corr3xtConfigured: Boolean = false,
    val globalStatus: String = "Configure a reachable Corr3xt URL for app sign-in; providers use secure API keys or tokens in Settings.",
    val pendingMethodLabel: String = "",
    val hasPendingRequest: Boolean = false,
    val apiKeyFallbackMethodId: String = "",
    val apiKeyFallbackLabel: String = "",
    val pendingStartUrl: String = "",
    val options: List<AuthOptionUiState> = emptyList(),
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val appSettingsStore = AppSettingsStore(application)
    private val authSessionStore = AuthSessionStore(application)
    private val providerSetupOpenIndexes = mutableMapOf<String, Int>()
    private val providerCredentialInputs = mutableMapOf<String, String>()
    private var deviceCodePollJob: Job? = null
    private val signedOutStatuses by lazy {
        buildSet {
            add("Not signed in")
            AppLanguage.entries.forEach { language ->
                add(hermesStringsFor(language).authNotSignedIn())
            }
        }
    }

    private fun currentStrings(): HermesStrings {
        val settings = appSettingsStore.load()
        return hermesStringsFor(AppLanguage.fromTag(settings.languageTag))
    }

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun refresh() {
        _uiState.value = buildState()
    }

    fun updateProviderCredentialInput(methodId: String, value: String) {
        providerCredentialInputs[methodId] = value
        val current = _uiState.value
        _uiState.value = buildState().copy(
            globalStatus = current.globalStatus,
            apiKeyFallbackMethodId = current.apiKeyFallbackMethodId,
            apiKeyFallbackLabel = current.apiKeyFallbackLabel,
        )
    }

    fun updateCorr3xtBaseUrl(value: String) {
        _uiState.update { it.copy(corr3xtBaseUrl = value) }
    }

    fun saveCorr3xtBaseUrl() {
        val candidate = _uiState.value.corr3xtBaseUrl.trim()
        if (candidate.isBlank()) {
            _uiState.update {
                it.copy(globalStatus = currentStrings().authConfigureCorr3xtFirst())
            }
            return
        }
        val normalized = Corr3xtAuthClient.normalizeConfiguredBaseUrl(candidate)
        if (normalized == null) {
            _uiState.update {
                it.copy(globalStatus = currentStrings().authBaseUrlMustBeValid())
            }
            return
        }

        val existing = appSettingsStore.load()
        appSettingsStore.save(
            existing.copy(
                corr3xtBaseUrl = normalized,
            )
        )
        _uiState.update {
            it.copy(
                corr3xtBaseUrl = normalized,
                corr3xtConfigured = true,
                globalStatus = currentStrings().authSavedBaseUrl(),
            )
        }
    }

    fun startAuth(methodId: String): Boolean {
        val option = AuthCatalog.find(methodId) ?: return false
        if (option.id == "openrouter") {
            return startOpenRouterOAuth(option)
        }
        if (option.id == "xai-oauth") {
            return startXaiOAuth(option)
        }
        if (option.id == "chatgpt" || option.id == "codex") {
            // Primary path = openai/codex browser OAuth (localhost:1455); device code is fallback.
            return startCodexBrowserOAuth(option)
        }
        if (option.id == "nous") {
            return startNousDeviceCode(option)
        }
        if (!option.browserSignInSupported && option.scope == AuthScope.RuntimeProvider) {
            prepareApiKeySetup(methodId)
            openProviderSetupPage(methodId)
            return true
        }
        val candidateBaseUrl = _uiState.value.corr3xtBaseUrl.trim()
        if (candidateBaseUrl.isBlank()) {
            _uiState.update {
                it.copy(globalStatus = currentStrings().authConfigureCorr3xtFirst())
            }
            return false
        }
        val normalizedBaseUrl = Corr3xtAuthClient.normalizeConfiguredBaseUrl(candidateBaseUrl)
        if (normalizedBaseUrl == null) {
            _uiState.update {
                it.copy(globalStatus = currentStrings().authBaseUrlMustBeValid())
            }
            return false
        }

        val settings = appSettingsStore.load()
        val state = UUID.randomUUID().toString()
        val pendingRequest = PendingAuthRequest(
            state = state,
            methodId = option.id,
            startUrl = Corr3xtAuthClient.buildStartUri(
                baseUrl = normalizedBaseUrl,
                option = option,
                state = state,
                languageTag = settings.languageTag,
            ).toString(),
        )
        val startUri = Uri.parse(pendingRequest.startUrl)

        viewModelScope.launch {
            _uiState.update { it.copy(globalStatus = currentStrings().authCheckingCorr3xt(option.label)) }
            val probe = withContext(Dispatchers.IO) {
                Corr3xtAuthClient.probeStartUri(android.net.Uri.parse(pendingRequest.startUrl))
            }
            if (!probe.reachable) {
                authSessionStore.clearPendingRequest()
                val apiKeyFallbackAvailable = option.scope == AuthScope.RuntimeProvider &&
                    option.runtimeProvider.isNotBlank()
                val failureStatus = when (probe.status) {
                    "unknown_host" -> if (option.scope == AuthScope.AppAccount) {
                        currentStrings().authAppSignInHostCouldNotBeResolved(probe.host)
                    } else {
                        currentStrings().authHostCouldNotBeResolved(probe.host)
                    }
                    "network_error" -> if (option.scope == AuthScope.AppAccount) {
                        currentStrings().authAppSignInPageCouldNotBeReached(probe.errorName)
                    } else {
                        currentStrings().authPageCouldNotBeReached(probe.errorName)
                    }
                    else -> probe.status.ifBlank { currentStrings().authTryAgain() }
                }.let { status ->
                    if (apiKeyFallbackAvailable) {
                        "$status ${currentStrings().authApiKeyFallbackAvailable(option.label)}"
                    } else {
                        status
                    }
                }
                _uiState.update {
                    it.copy(
                        corr3xtBaseUrl = normalizedBaseUrl,
                        globalStatus = failureStatus,
                        pendingMethodLabel = "",
                        hasPendingRequest = false,
                        pendingStartUrl = "",
                        apiKeyFallbackMethodId = if (apiKeyFallbackAvailable) option.id else "",
                        apiKeyFallbackLabel = if (apiKeyFallbackAvailable) option.label else "",
                    )
                }
                return@launch
            }

            authSessionStore.savePendingRequest(pendingRequest)
            val launch = openAuthStartPage(startUri, "Open ${option.label} sign-in")
            if (launch.success) {
                _uiState.update { current ->
                    current.copy(
                        corr3xtBaseUrl = normalizedBaseUrl,
                        globalStatus = currentStrings().authOpenedCorr3xt(option.label),
                        pendingMethodLabel = option.label,
                        hasPendingRequest = true,
                        pendingStartUrl = pendingRequest.startUrl,
                        apiKeyFallbackMethodId = "",
                        apiKeyFallbackLabel = "",
                    )
                }
            } else {
                authSessionStore.clearPendingRequest()
                copyAuthStartUrl(pendingRequest.startUrl, updateStatus = false)
                val statusPrefix = if (launch.errorName == "ActivityNotFoundException") {
                    currentStrings().authNoBrowser()
                } else {
                    "${currentStrings().authTryAgain()} (${launch.errorName.ifBlank { "browser_error" }})"
                }
                _uiState.update {
                    it.copy(
                        globalStatus = "$statusPrefix ${currentStrings().authCopiedSignInUrl()}",
                        pendingStartUrl = pendingRequest.startUrl,
                        apiKeyFallbackMethodId = if (option.scope == AuthScope.RuntimeProvider) option.id else "",
                        apiKeyFallbackLabel = if (option.scope == AuthScope.RuntimeProvider) option.label else "",
                    )
                }
            }
        }
        return true
    }

    private fun startOpenRouterOAuth(option: AuthOption): Boolean {
        val state = UUID.randomUUID().toString()
        // Prefer custom-scheme callback so in-app WebView can return to Hermes without localhost.
        val customSchemeStart = OpenRouterOAuthClient.createStartRequest(state = state)
        authSessionStore.savePendingRequest(customSchemeStart.pendingRequest)
        val inApp = openAuthStartPage(customSchemeStart.startUri, "OpenRouter sign-in")
        if (inApp.success) {
            _uiState.update {
                it.copy(
                    globalStatus = "Opened OpenRouter sign-in in the in-app browser. Approve Hermes; the app will receive hermesagent://auth/callback and save the key securely.",
                    pendingMethodLabel = option.label,
                    hasPendingRequest = true,
                    pendingStartUrl = customSchemeStart.pendingRequest.startUrl,
                    apiKeyFallbackMethodId = "",
                    apiKeyFallbackLabel = "",
                )
            }
            return true
        }

        // Fallback: local loopback + external browser (older devices / WebView missing).
        val callbackUrl = OpenRouterLoopbackOAuthServer.callbackUrlForState(state)
        val loopbackStart = OpenRouterOAuthClient.createStartRequest(
            state = state,
            callbackUrl = callbackUrl,
        )
        val loopback = OpenRouterLoopbackOAuthServer.start(
            context = getApplication(),
            pending = loopbackStart.pendingRequest,
        )
        if (!loopback.started) {
            authSessionStore.clearPendingRequest()
            copyAuthStartUrl(customSchemeStart.pendingRequest.startUrl, updateStatus = false)
            _uiState.update {
                it.copy(
                    globalStatus = "Unable to open OpenRouter sign-in (${inApp.errorName.ifBlank { "webview_error" }}); copied the URL. You can paste an OpenRouter API key below.",
                    pendingStartUrl = customSchemeStart.pendingRequest.startUrl,
                    apiKeyFallbackMethodId = option.id,
                    apiKeyFallbackLabel = option.label,
                )
            }
            return true
        }
        authSessionStore.savePendingRequest(loopbackStart.pendingRequest)
        val external = HermesExternalBrowserLauncher.open(
            context = getApplication(),
            uri = loopbackStart.startUri,
            title = "Open OpenRouter sign-in",
            forceChooser = true,
        )
        if (external.success) {
            _uiState.update {
                it.copy(
                    globalStatus = "Opened OpenRouter sign-in in an external browser (WebView unavailable). Approve Hermes; the local callback will save the API key.",
                    pendingMethodLabel = option.label,
                    hasPendingRequest = true,
                    pendingStartUrl = loopbackStart.pendingRequest.startUrl,
                    apiKeyFallbackMethodId = "",
                    apiKeyFallbackLabel = "",
                )
            }
        } else {
            loopback.handle?.stop()
            authSessionStore.clearPendingRequest()
            copyAuthStartUrl(loopbackStart.pendingRequest.startUrl, updateStatus = false)
            _uiState.update {
                it.copy(
                    globalStatus = "Unable to open OpenRouter sign-in; copied the URL. Paste an OpenRouter API key below if needed.",
                    pendingStartUrl = loopbackStart.pendingRequest.startUrl,
                    apiKeyFallbackMethodId = option.id,
                    apiKeyFallbackLabel = option.label,
                )
            }
        }
        return true
    }

    private fun openAuthStartPage(uri: Uri, title: String): BrowserLaunchResult {
        // Always prefer in-app WebView so OAuth can intercept hermesagent://auth/callback.
        val inApp = HermesProviderSetupWebActivity.openInApp(
            context = getApplication(),
            uri = uri,
            title = title,
        )
        if (inApp.success) {
            return inApp
        }
        return HermesExternalBrowserLauncher.open(
            context = getApplication(),
            uri = uri,
            title = title,
            forceChooser = true,
        )
    }

    private fun startXaiOAuth(option: AuthOption): Boolean {
        viewModelScope.launch {
            _uiState.update { it.copy(globalStatus = "Starting xAI Grok OAuth…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val discovery = XaiOAuthClient.discover()
                    val start = XaiOAuthClient.createStartRequest(discovery = discovery)
                    val loopback = XaiLoopbackOAuthServer.start(
                        context = getApplication(),
                        pending = start.pending,
                        tokenEndpoint = discovery.tokenEndpoint,
                        codeChallenge = start.codeChallenge,
                    )
                    if (!loopback.started) {
                        return@runCatching "Unable to bind xAI callback on 127.0.0.1:56121 (${loopback.errorName}). " +
                            "Close other apps using that port, or paste an xAI API key under xAI / Grok API key."
                    }
                    authSessionStore.savePendingRequest(start.pending)
                    val launch = openAuthStartPage(start.authorizeUri, "xAI Grok OAuth")
                    if (!launch.success) {
                        loopback.handle?.stop()
                        authSessionStore.clearPendingRequest()
                        return@runCatching "Unable to open xAI authorize page (${launch.errorName}). URL: ${start.authorizeUri}"
                    }
                    "Opened xAI Grok OAuth in the in-app browser. Approve SuperGrok; " +
                        "callback returns to 127.0.0.1:56121 and Hermes saves tokens securely."
                }.getOrElse { error ->
                    "xAI OAuth failed: ${error.message ?: error.javaClass.simpleName}"
                }
            }
            _uiState.update {
                it.copy(
                    globalStatus = result,
                    pendingMethodLabel = option.label,
                    hasPendingRequest = result.contains("Opened xAI"),
                    apiKeyFallbackMethodId = if (result.contains("Opened xAI")) "" else option.id,
                    apiKeyFallbackLabel = if (result.contains("Opened xAI")) "" else option.label,
                )
            }
            refresh()
        }
        return true
    }

    /**
     * Primary Codex path from openai/codex: PKCE browser OAuth with
     * http://localhost:1455/auth/callback (fallback port 1457).
     * Falls back to device-code if loopback cannot bind.
     */
    private fun startCodexBrowserOAuth(option: AuthOption): Boolean {
        viewModelScope.launch {
            _uiState.update { it.copy(globalStatus = "Starting ChatGPT/Codex OAuth (openai/codex path)…") }
            val browserResult = withContext(Dispatchers.IO) {
                runCatching {
                    val start = CodexOAuthClient.createBrowserStartRequest(methodId = option.id)
                    val loopback = CodexLoopbackOAuthServer.start(
                        context = getApplication(),
                        pending = start.pending,
                        preferredPort = start.preferredPort,
                    )
                    if (!loopback.started) {
                        return@runCatching null to
                            "loopback_unavailable:${loopback.errorName.ifBlank { "bind" }}"
                    }
                    // Rebuild authorize URL if fallback port was used (1457).
                    val authorizeUri = if (loopback.actualPort != start.preferredPort) {
                        CodexOAuthClient.createBrowserStartRequest(
                            methodId = option.id,
                            state = start.pending.state,
                            verifier = start.pending.codeVerifier,
                            port = loopback.actualPort,
                        ).authorizeUri
                    } else {
                        start.authorizeUri
                    }
                    // Re-save pending with matching verifier/state for the actual redirect port
                    val pendingForPort = if (loopback.actualPort != start.preferredPort) {
                        CodexOAuthClient.createBrowserStartRequest(
                            methodId = option.id,
                            state = start.pending.state,
                            verifier = start.pending.codeVerifier,
                            port = loopback.actualPort,
                        ).pending
                    } else {
                        start.pending
                    }
                    authSessionStore.savePendingRequest(pendingForPort)
                    val launch = openAuthStartPage(authorizeUri, "ChatGPT / Codex sign-in")
                    if (!launch.success) {
                        loopback.handle?.stop()
                        authSessionStore.clearPendingRequest()
                        return@runCatching null to "webview:${launch.errorName}"
                    }
                    true to
                        "Opened ChatGPT/Codex OAuth in the in-app browser (openai/codex authorize). " +
                        "Approve access; callback returns to localhost:${loopback.actualPort}/auth/callback."
                }.getOrElse { error ->
                    null to (error.message ?: error.javaClass.simpleName)
                }
            }
            if (browserResult.first == true) {
                _uiState.update {
                    it.copy(
                        globalStatus = browserResult.second,
                        pendingMethodLabel = option.label,
                        hasPendingRequest = true,
                        apiKeyFallbackMethodId = "",
                        apiKeyFallbackLabel = "",
                    )
                }
                return@launch
            }
            // Fallback: official device-code path
            _uiState.update {
                it.copy(
                    globalStatus = "Browser OAuth unavailable (${browserResult.second}); trying device code…",
                )
            }
            startCodexDeviceCodeInternal(option)
        }
        return true
    }

    private fun startCodexDeviceCodeInternal(option: AuthOption) {
        deviceCodePollJob?.cancel()
        viewModelScope.launch {
            _uiState.update { it.copy(globalStatus = "Requesting OpenAI device code…") }
            val start = withContext(Dispatchers.IO) {
                runCatching { CodexDeviceCodeAuth.requestDeviceCode() }
            }.getOrElse { error ->
                _uiState.update {
                    it.copy(
                        globalStatus = "OpenAI device code failed: ${error.message ?: error.javaClass.simpleName}. " +
                            "You can still paste a ChatGPT/Codex token below.",
                        apiKeyFallbackMethodId = option.id,
                        apiKeyFallbackLabel = option.label,
                    )
                }
                return@launch
            }
            openAuthStartPage(
                Uri.parse(start.verificationUrl),
                "OpenAI device login",
            )
            _uiState.update {
                it.copy(
                    globalStatus = "Enter code ${start.userCode} at ${start.verificationUrl} " +
                        "(opened in-app). Waiting for approval…",
                    pendingMethodLabel = option.label,
                    hasPendingRequest = true,
                    apiKeyFallbackMethodId = "",
                    apiKeyFallbackLabel = "",
                )
            }
            deviceCodePollJob = viewModelScope.launch(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + 15 * 60_000L
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(start.pollIntervalSeconds * 1000L)
                    val session = runCatching {
                        CodexDeviceCodeAuth.pollOnce(start, methodId = option.id)
                    }.getOrElse { error ->
                        withContext(Dispatchers.Main) {
                            _uiState.update {
                                it.copy(globalStatus = "OpenAI device poll error: ${error.message}")
                            }
                        }
                        return@launch
                    }
                    if (session != null) {
                        authSessionStore.saveSession(session)
                        if (session.signedIn) {
                            AuthRuntimeApplier.apply(getApplication(), session)
                        }
                        withContext(Dispatchers.Main) {
                            _uiState.update {
                                it.copy(
                                    globalStatus = session.status,
                                    hasPendingRequest = false,
                                    pendingMethodLabel = "",
                                )
                            }
                            refresh()
                        }
                        return@launch
                    }
                }
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            globalStatus = "OpenAI device sign-in timed out. Tap Sign in to try again.",
                            hasPendingRequest = false,
                        )
                    }
                }
            }
        }
    }

    private fun startNousDeviceCode(option: AuthOption): Boolean {
        deviceCodePollJob?.cancel()
        viewModelScope.launch {
            _uiState.update { it.copy(globalStatus = "Starting Nous Portal device code…") }
            val start = withContext(Dispatchers.IO) {
                runCatching { NousDeviceCodeAuth.requestDeviceCode() }
            }.getOrElse { error ->
                _uiState.update {
                    it.copy(
                        globalStatus = "Nous device code failed: ${error.message ?: error.javaClass.simpleName}",
                        apiKeyFallbackMethodId = option.id,
                        apiKeyFallbackLabel = option.label,
                    )
                }
                return@launch
            }
            openAuthStartPage(
                Uri.parse(start.verificationUriComplete),
                "Nous Portal sign-in",
            )
            _uiState.update {
                it.copy(
                    globalStatus = "Nous code ${start.userCode} — approve in the in-app browser. Waiting…",
                    pendingMethodLabel = option.label,
                    hasPendingRequest = true,
                )
            }
            deviceCodePollJob = viewModelScope.launch(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + start.expiresIn * 1000L
                val intervalMs = (start.intervalSeconds.coerceAtLeast(1) * 1000L)
                while (isActive && System.currentTimeMillis() < deadline) {
                    delay(intervalMs)
                    val session = runCatching {
                        NousDeviceCodeAuth.pollOnce(start)
                    }.getOrElse { error ->
                        withContext(Dispatchers.Main) {
                            _uiState.update {
                                it.copy(globalStatus = "Nous poll error: ${error.message}")
                            }
                        }
                        return@launch
                    }
                    if (session != null) {
                        authSessionStore.saveSession(session)
                        if (session.signedIn) {
                            AuthRuntimeApplier.apply(getApplication(), session)
                        }
                        withContext(Dispatchers.Main) {
                            _uiState.update {
                                it.copy(
                                    globalStatus = session.status,
                                    hasPendingRequest = false,
                                    pendingMethodLabel = "",
                                )
                            }
                            refresh()
                        }
                        return@launch
                    }
                }
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            globalStatus = "Nous sign-in timed out. Tap Sign in to try again.",
                            hasPendingRequest = false,
                        )
                    }
                }
            }
        }
        return true
    }

    fun copyPendingSignInUrl() {
        val startUrl = _uiState.value.pendingStartUrl.ifBlank {
            authSessionStore.loadPendingRequest()?.startUrl.orEmpty()
        }
        copyAuthStartUrl(startUrl, updateStatus = true)
    }

    private fun copyAuthStartUrl(startUrl: String, updateStatus: Boolean) {
        val target = startUrl.trim()
        if (target.isBlank()) {
            return
        }
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Hermes Corr3xt sign-in URL", target))
        if (updateStatus) {
            _uiState.update { it.copy(globalStatus = currentStrings().authCopiedSignInUrl()) }
        }
    }

    fun prepareApiKeySetup(methodId: String) {
        val option = AuthCatalog.find(methodId) ?: return
        if (option.runtimeProvider.isBlank()) {
            return
        }
        val existing = appSettingsStore.load()
        appSettingsStore.save(
            existing.copy(
                provider = option.runtimeProvider,
                baseUrl = option.defaultBaseUrl,
                model = option.defaultModel,
            )
        )
        _uiState.update {
            it.copy(
                globalStatus = currentStrings().authApiKeySetupReady(option.label),
                apiKeyFallbackMethodId = "",
                apiKeyFallbackLabel = "",
                pendingStartUrl = "",
            )
        }
    }

    fun saveProviderCredential(methodId: String) {
        val option = AuthCatalog.find(methodId) ?: return
        if (option.scope != AuthScope.RuntimeProvider || option.runtimeProvider.isBlank()) {
            return
        }
        val input = providerCredentialInputs[methodId].orEmpty()
        val parsedCredential = ProviderPresets.parseCredentialInput(option.runtimeProvider, input)
        if (parsedCredential.apiKey.isBlank()) {
            _uiState.update {
                it.copy(globalStatus = "Paste an API key, token, or CLI env line for ${option.label} first.")
            }
            return
        }
        val preset = ProviderPresets.find(option.runtimeProvider)
        val resolvedBaseUrl = option.defaultBaseUrl.ifBlank { preset?.baseUrl.orEmpty() }
        val resolvedModel = option.defaultModel.ifBlank { preset?.modelHint.orEmpty() }
        val sourceSuffix = parsedCredential.sourceLabel
            .takeIf { it.isNotBlank() }
            ?.let { " from $it" }
            .orEmpty()
        val session = AuthSession(
            methodId = option.id,
            label = option.label,
            scope = option.scope,
            runtimeProvider = option.runtimeProvider,
            signedIn = true,
            status = "Saved ${option.label} credential$sourceSuffix and queued Hermes runtime restart.",
            apiKey = parsedCredential.apiKey,
            baseUrl = resolvedBaseUrl,
            model = resolvedModel,
        )

        viewModelScope.launch {
            _uiState.update {
                it.copy(globalStatus = "Saving ${option.label} credential and restarting Hermes...")
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    authSessionStore.saveSession(session)
                    AuthRuntimeApplier.apply(getApplication(), session)
                }
            }.onSuccess {
                providerCredentialInputs.remove(methodId)
                _uiState.value = buildState().copy(
                    globalStatus = session.status,
                    apiKeyFallbackMethodId = "",
                    apiKeyFallbackLabel = "",
                )
            }.onFailure { error ->
                _uiState.update {
                    it.copy(globalStatus = "Unable to save ${option.label} credential (${error::class.java.simpleName}).")
                }
            }
        }
    }

    fun openProviderSetupPage(methodId: String) {
        val option = AuthCatalog.find(methodId) ?: return
        val target = nextProviderSetupTarget(option.runtimeProvider) ?: return
        val uri = Uri.parse(target.url)
        if (uri.scheme !in setOf("http", "https")) {
            _uiState.update { it.copy(globalStatus = "Provider setup URL must start with https:// or http://") }
            return
        }
        // Prefer in-app WebView so subscription/login pages can return via hermesagent://auth/callback.
        val launch = HermesProviderSetupWebActivity.openInApp(
            context = getApplication(),
            uri = uri,
            title = "${option.label} setup",
        ).let { inApp ->
            if (inApp.success) inApp
            else HermesProviderSetupWebActivity.open(
                context = getApplication(),
                uri = uri,
                title = "Open ${option.label} setup page",
            )
        }
        if (launch.success) {
            copyProviderSetupUrl(methodId, updateStatus = false)
            _uiState.update {
                it.copy(globalStatus = providerSetupOpenedStatus(option.label, option.runtimeProvider, target))
            }
            probeProviderSetupPages(option.label, option.runtimeProvider)
        } else {
            copyProviderSetupUrl(methodId, updateStatus = false)
            _uiState.update {
                it.copy(globalStatus = "Unable to open setup page (${launch.errorName.ifBlank { "setup_page_error" }}); copied the ${option.label} setup URLs.")
            }
        }
    }

    fun checkProviderSetupPages(methodId: String) {
        val option = AuthCatalog.find(methodId) ?: return
        val urls = ProviderPresets.setupUrls(option.runtimeProvider)
        if (urls.isEmpty()) {
            _uiState.update { it.copy(globalStatus = "No setup URLs are configured for ${option.label}.") }
            return
        }
        copyProviderSetupUrl(methodId, updateStatus = false)
        _uiState.update { it.copy(globalStatus = "Checking ${option.label} setup pages from this device...") }
        probeProviderSetupPages(option.label, option.runtimeProvider)
    }

    private fun probeProviderSetupPages(optionLabel: String, providerId: String) {
        val urls = ProviderPresets.setupUrls(providerId)
        if (urls.isEmpty()) {
            return
        }
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                urls.map(ProviderSetupUrlProbe::probe)
            }
            val status = providerSetupProbeStatus(optionLabel, results)
            _uiState.update { it.copy(globalStatus = status) }
        }
    }

    private fun providerSetupProbeStatus(
        optionLabel: String,
        results: List<ProviderSetupProbeResult>,
    ): String {
        val reachable = results.filter { it.reachable }
        val firstReachable = reachable.firstOrNull()
        return if (firstReachable != null) {
            val fallbackHint = if (reachable.size < results.size) {
                " ${results.size - reachable.size} fallback page(s) did not respond cleanly; tap Open again to cycle official alternatives."
            } else {
                ""
            }
            "$optionLabel setup is reachable from Hermes: ${firstReachable.url} (${firstReachable.statusLabel}). ${reachable.size}/${results.size} official setup page(s) responded; copied all setup URLs.$fallbackHint"
        } else {
            val failureSummary = results.joinToString(separator = "; ") { "${it.url}: ${it.statusLabel}" }
            "No $optionLabel setup page responded from Hermes. Copied all setup URLs. $failureSummary"
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
        optionLabel: String,
        providerId: String,
        target: ProviderSetupTarget,
    ): String {
        val cycleHint = if (target.total > 1) {
            " in your browser ${target.displayIndex}/${target.total}; copied all official setup URLs. Tap Open again for the next fallback if this page stalls."
        } else {
            " in your browser or Hermes fallback. If this page stalls, use Copy setup URL."
        }
        val qwenLegacyHint = if (providerId == "qwen-oauth") {
            " Qwen OAuth is legacy; choose Qwen Cloud for new API-key setup."
        } else {
            ""
        }
        return "Opened $optionLabel setup page$cycleHint$qwenLegacyHint"
    }

    fun copyProviderSetupUrl(methodId: String) {
        copyProviderSetupUrl(methodId, updateStatus = true)
    }

    private fun copyProviderSetupUrl(methodId: String, updateStatus: Boolean) {
        val option = AuthCatalog.find(methodId) ?: return
        val setupText = ProviderPresets.setupClipboardText(option.runtimeProvider)
        if (setupText.isBlank()) {
            return
        }
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Hermes ${option.label} setup URLs", setupText))
        if (updateStatus) {
            val fallbackCount = ProviderPresets.setupUrls(option.runtimeProvider).size - 1
            val suffix = when (fallbackCount) {
                0 -> ""
                1 -> " and 1 alternate official page"
                else -> " and $fallbackCount alternate official pages"
            }
            _uiState.update { it.copy(globalStatus = "Copied ${option.label} setup URL$suffix.") }
        }
    }

    fun cancelPendingRequest() {
        authSessionStore.clearPendingRequest()
        _uiState.update {
            it.copy(
                pendingMethodLabel = "",
                hasPendingRequest = false,
                apiKeyFallbackMethodId = "",
                apiKeyFallbackLabel = "",
                pendingStartUrl = "",
                globalStatus = currentStrings().authCanceled(),
            )
        }
    }

    fun signOut(methodId: String) {
        val session = authSessionStore.loadSession(methodId)
        authSessionStore.clearSession(methodId)
        if (session != null && session.runtimeProvider.isNotBlank()) {
            runCatching {
                val python = com.chaquo.python.Python.getInstance()
                python.getModule("hermes_android.auth_bridge")
                    .callAttr("clear_provider_auth_bundle", session.runtimeProvider)
            }
        }
        refresh()
    }

    private fun buildState(): AuthUiState {
        val settings = appSettingsStore.load()
        val strings = hermesStringsFor(AppLanguage.fromTag(settings.languageTag))
        val persistedPending = authSessionStore.loadPendingRequest()
        val pending = persistedPending?.takeUnless { AuthSessionStore.isPendingRequestExpired(it) }
        if (persistedPending != null && pending == null) {
            authSessionStore.clearPendingRequest()
        }

        val corr3xtBaseUrl = Corr3xtAuthClient.normalizedBaseUrl(settings.corr3xtBaseUrl)
        val corr3xtConfigured = corr3xtBaseUrl.isNotBlank()
        val sessions = authSessionStore.loadSessions()
        val sessionsById = sessions.associateBy { it.methodId }
        val options = AuthCatalog.options.map { option ->
            val session = sessionsById[option.id] ?: defaultSession(option)
            val localizedStatus = when {
                session.signedIn -> strings.authSignedInWith(option.label)
                isSignedOutStatus(session.status) -> strings.authNotSignedIn()
                else -> session.status
            }
            AuthOptionUiState(
                id = option.id,
                label = option.label,
                description = strings.authDescription(option.id, option.description),
                scope = option.scope,
                runtimeProvider = session.runtimeProvider,
                credentialInput = providerCredentialInputs[option.id].orEmpty(),
                credentialInputHelp = if (option.scope == AuthScope.RuntimeProvider && option.runtimeProvider.isNotBlank()) {
                    ProviderPresets.credentialInputHelp(option.runtimeProvider)
                } else {
                    ""
                },
                signedIn = session.signedIn,
                status = localizedStatus,
                supportsApiKeySetup = option.scope == AuthScope.RuntimeProvider && option.runtimeProvider.isNotBlank(),
                supportsBrowserSignIn = option.browserSignInSupported,
                accountHint = listOf(session.displayName, session.email, session.phone)
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty(),
                browserSignInEnabled = option.scope != AuthScope.AppAccount || corr3xtConfigured,
                providerSetupUrl = ProviderPresets.find(option.runtimeProvider)?.apiKeyUrl.orEmpty(),
            )
        }
        val signedInAccounts = options.count { it.signedIn }
        val latestSessionStatus = sessions
            .filter { session ->
                session.updatedAtEpochMs > 0 &&
                    session.status.isNotBlank() &&
                    !isSignedOutStatus(session.status)
            }
            .maxByOrNull { it.updatedAtEpochMs }
            ?.status
        val pendingMethodLabel = pending?.methodId
            ?.let { AuthCatalog.find(it)?.label ?: it }
            .orEmpty()
        val globalStatus = when {
            pending != null -> strings.authWaitingCallback(pendingMethodLabel)
            !latestSessionStatus.isNullOrBlank() -> latestSessionStatus
            signedInAccounts > 0 -> strings.authConnectedMethods(signedInAccounts)
            !corr3xtConfigured -> strings.authConfigureCorr3xtFirst()
            else -> strings.authGlobalStatusDefault()
        }

        return AuthUiState(
            corr3xtBaseUrl = corr3xtBaseUrl,
            corr3xtConfigured = corr3xtConfigured,
            globalStatus = globalStatus,
            pendingMethodLabel = pendingMethodLabel,
            hasPendingRequest = pending != null,
            pendingStartUrl = pending?.startUrl.orEmpty(),
            apiKeyFallbackMethodId = "",
            apiKeyFallbackLabel = "",
            options = options,
        )
    }

    fun applyConsumedCallbackIfPresent() {
        val pending = authSessionStore.loadPendingRequest() ?: return
        val storedSession = authSessionStore.loadSession(pending.methodId) ?: return
        if (!storedSession.signedIn) {
            refresh()
            return
        }
        AuthRuntimeApplier.apply(getApplication(), storedSession)
        authSessionStore.clearPendingRequest()
        refresh()
    }

    private fun defaultSession(option: AuthOption): AuthSession {
        return AuthSession(
            methodId = option.id,
            label = option.label,
            scope = option.scope,
            runtimeProvider = option.runtimeProvider,
            status = currentStrings().authNotSignedIn(),
            updatedAtEpochMs = 0,
        )
    }

    private fun isSignedOutStatus(status: String): Boolean {
        return status.trim() in signedOutStatuses
    }
}
