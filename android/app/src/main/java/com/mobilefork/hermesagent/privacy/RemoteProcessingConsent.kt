package com.mobilefork.hermesagent.privacy

import android.content.Context
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.ProviderPresets
import com.mobilefork.hermesagent.backend.BackendKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

data class RemoteProcessingTarget(val provider: String, val endpoint: String) {
    val consentKey: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest("$provider\n$endpoint".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        fun fromSettings(settings: AppSettings): RemoteProcessingTarget? {
            if (BackendKind.fromPersistedValue(settings.onDeviceBackend) != BackendKind.NONE) return null
            val provider = settings.provider.trim().lowercase()
            require(provider.isNotBlank()) { "Choose an AI provider before sending" }
            val baseUrl = settings.baseUrl.trim().ifBlank { ProviderPresets.find(provider)?.baseUrl.orEmpty() }
            if (baseUrl.isBlank()) return RemoteProcessingTarget(provider, "Provider-managed endpoint")
            require(baseUrl.none(Char::isWhitespace) && '?' !in baseUrl && '#' !in baseUrl) {
                "AI endpoint credentials and query parameters must not be embedded in its URL"
            }
            val normalized = com.mobilefork.hermesagent.api.HermesEndpointUrl.normalizeBaseUrl(baseUrl)
            val url = requireNotNull(normalized.toHttpUrlOrNull()) { "Configure a valid AI endpoint before sending" }
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "AI endpoint credentials and query parameters must not be embedded in its URL"
            }
            return RemoteProcessingTarget(provider, url.toString().trimEnd('/'))
        }
    }
}

class RemoteProcessingConsentStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("remote-processing-consent-v1", Context.MODE_PRIVATE)

    fun allowed(target: RemoteProcessingTarget): Boolean = preferences.getInt(target.consentKey, 0) == REVISION

    fun accept(target: RemoteProcessingTarget) {
        check(preferences.edit().putInt(target.consentKey, REVISION).commit()) { "Could not persist AI processing consent" }
    }

    fun revokeAll() {
        check(preferences.edit().clear().commit()) { "Could not revoke AI processing consent" }
        revocationCounter.value += 1
    }

    fun requireAllowed(target: RemoteProcessingTarget) {
        check(allowed(target)) { "Remote AI processing needs your consent in the chat screen" }
    }

    companion object {
        const val REVISION = 1
        private val revocationCounter = MutableStateFlow(0L)
        val revocations = revocationCounter.asStateFlow()

        @JvmStatic
        fun requireConfiguredRemoteConsent(context: Context) {
            val target = RemoteProcessingTarget.fromSettings(AppSettingsStore(context).load()) ?: return
            RemoteProcessingConsentStore(context).requireAllowed(target)
        }

        @JvmStatic
        fun requireConfiguredRemoteConsent() {
            requireConfiguredRemoteConsent(com.mobilefork.hermesagent.HermesApplication.instance)
        }
    }
}
