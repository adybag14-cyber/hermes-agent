package com.mobilefork.hermesagent.auth

import android.content.Context
import com.chaquo.python.Python
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.AuthScope
import com.mobilefork.hermesagent.data.AuthSession
import com.mobilefork.hermesagent.data.AuthSessionStore
import com.mobilefork.hermesagent.data.ProviderPresets
import com.mobilefork.hermesagent.data.SecureSecretsStore
import com.mobilefork.hermesagent.models.LocalModelRuntimeSelectionAuthority
import com.mobilefork.hermesagent.models.PythonRuntimeWriteAuthority
import com.mobilefork.hermesagent.models.RuntimeSelectionSupersededException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AuthRuntimeApplier {
    private val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun apply(context: Context, session: AuthSession) {
        if (!session.signedIn || session.scope != AuthScope.RuntimeProvider || session.runtimeProvider.isBlank()) {
            return
        }

        val appContext = context.applicationContext
        val settingsStore = AppSettingsStore(appContext)
        val preset = ProviderPresets.find(session.runtimeProvider)
        val resolvedBaseUrl = session.baseUrl.ifBlank { preset?.baseUrl.orEmpty() }
        val runtimeConfigBaseUrl = ProviderPresets.runtimeConfigBaseUrl(session.runtimeProvider, resolvedBaseUrl)
        val resolvedModel = session.model.ifBlank { preset?.modelHint.orEmpty() }
        val sessionStore = AuthSessionStore(appContext)
        val selectionGeneration = LocalModelRuntimeSelectionAuthority.beginActionIf {
            // A delayed OAuth/API-key completion must not resurrect credentials after Sign out.
            // Sign out clears this record under the same process-wide authority monitor.
            sessionStore.loadSession(session.methodId) == session
        } ?: return

        try {
            LocalModelRuntimeSelectionAuthority.withCurrent(selectionGeneration) {
                // Durable settings are authoritative. Do not rewrite Python configuration or
                // restart a runtime if the settings commit cannot be persisted.
                settingsStore.update { current ->
                    current.copy(
                        provider = session.runtimeProvider,
                        baseUrl = resolvedBaseUrl,
                        model = resolvedModel,
                    )
                }
                val providerCredential = session.apiKey
                    .ifBlank { session.accessToken }
                    .ifBlank { session.sessionToken }
                if (providerCredential.isNotBlank()) {
                    SecureSecretsStore(appContext).saveApiKey(session.runtimeProvider, providerCredential)
                }
            }
            LocalModelRuntimeSelectionAuthority.performLongIfCurrent(selectionGeneration) {
                HermesRuntimeManager.ensurePythonStarted(appContext)
                val python = Python.getInstance()
                PythonRuntimeWriteAuthority.writeIfCurrent(selectionGeneration) {
                    python.getModule("hermes_android.auth_bridge").callAttr(
                        "write_provider_auth_bundle",
                        session.runtimeProvider,
                        session.apiKey,
                        session.accessToken,
                        session.sessionToken,
                        session.refreshToken,
                        resolvedBaseUrl,
                    )
                    python.getModule("hermes_android.config_bridge").callAttr(
                        "write_runtime_config",
                        session.runtimeProvider,
                        resolvedModel,
                        runtimeConfigBaseUrl,
                    )
                }
            }
        } catch (_: RuntimeSelectionSupersededException) {
            return
        }
        restartRuntimeAsync(appContext, selectionGeneration)
    }

    private fun restartRuntimeAsync(context: Context, selectionGeneration: Long) {
        val appContext = context.applicationContext
        restartScope.launch {
            runCatching {
                LocalModelRuntimeSelectionAuthority.performLongIfCurrent(selectionGeneration) {
                    HermesRuntimeManager.restartAfterRemoteStop(
                        appContext,
                        admissionCheck = {
                            LocalModelRuntimeSelectionAuthority.requireCurrent(selectionGeneration)
                        },
                    )
                }
            }.onFailure { error ->
                if (error !is RuntimeSelectionSupersededException) throw error
            }
        }
    }
}
