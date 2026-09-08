package com.mobilefork.hermesagent.play

import com.mobilefork.hermesagent.api.HermesEndpointUrl
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.ProviderPresets
import com.mobilefork.hermesagent.privacy.RemoteProcessingTarget
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object PlayNetworkPolicy {
    fun requireApiUrl(url: String): Boolean {
        val parsed = requireNotNull(url.toHttpUrlOrNull()) { "Configure an HTTP(S) AI endpoint" }
        require(parsed.username.isBlank() && parsed.password.isBlank() && parsed.query == null && parsed.fragment == null) {
            "Keep credentials out of the endpoint URL"
        }
        val local = parsed.host in setOf("localhost", "127.0.0.1", "::1")
        require(local || parsed.isHttps) { "Remote Play AI endpoints require HTTPS" }
        return local
    }

    fun supportsProvider(provider: String): Boolean = provider == "custom" ||
        (com.mobilefork.hermesagent.ui.chat.usesDirectOpenAiCompatibleTransport(provider) &&
            provider !in setOf("codex", "qwen-oauth", "xai-oauth"))

    fun validateRemoteSettings(provider: String, baseUrl: String) {
        require(supportsProvider(provider)) { "Choose a supported API-key provider for the Play edition" }
        requireApiUrl(baseUrl.ifBlank { ProviderPresets.find(provider)?.baseUrl.orEmpty() })
    }

    fun requireTargetMatches(settings: AppSettings, provider: String, baseUrl: String) {
        val target = requireNotNull(RemoteProcessingTarget.fromSettings(settings)) {
            "Remote processing cannot borrow a local-model selection"
        }
        require(target.provider == provider &&
            HermesEndpointUrl.normalizeBaseUrl(target.endpoint) == HermesEndpointUrl.normalizeBaseUrl(baseUrl)) {
            "The provider or endpoint changed; review the new target before sending"
        }
    }
}
