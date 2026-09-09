package com.mobilefork.hermesagent.ui.chat

import android.content.Context
import com.mobilefork.hermesagent.api.*
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.HermesNetworkPolicy
import com.mobilefork.hermesagent.play.PlayContentSafety
import com.mobilefork.hermesagent.privacy.RemoteProcessingConsentStore
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Reuses request cancellation/ownership, but never constructs an agent tool client. */
internal fun preparePlayChatOperation(
    context: Context, baseUrl: String, modelName: String, apiKey: String?, providerId: String,
    sessionId: String, userText: String, userContentParts: List<ChatContentPart>, priorMessages: List<ChatMessage>,
    baseHttpClient: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES).callTimeout(15, TimeUnit.MINUTES)
        .followRedirects(false).followSslRedirects(false).build(),
): NativeToolChatOperation<NativeToolChatSendResult> {
    val transport = RequestOwnedHttpTransport(baseHttpClient)
    return NativeToolChatOperation(onCancel = transport::cancel, executeBlock = {
        com.mobilefork.hermesagent.play.PlayForegroundLifetime.requireForeground()
        val settings = AppSettingsStore(context).load()
        val language = AppLanguage.fromTag(settings.languageTag)
        val loopback = com.mobilefork.hermesagent.play.PlayNetworkPolicy.requireApiUrl(baseUrl)
        val local = com.mobilefork.hermesagent.backend.BackendKind.fromPersistedValue(settings.onDeviceBackend) !=
            com.mobilefork.hermesagent.backend.BackendKind.NONE
        require(!local || loopback) { "Packaged on-device inference must use its loopback endpoint" }
        if (!local) {
            com.mobilefork.hermesagent.play.PlayNetworkPolicy.requireTargetMatches(settings, providerId, baseUrl)
            RemoteProcessingConsentStore.requireConfiguredRemoteConsent(context)
        }
        HermesNetworkPolicy.requireExternalNetworkAllowed(context, baseUrl, "Play chat request")
        if (PlayContentSafety.blocked(userText)) {
            NativeToolChatSendResult(content = PlayContentSafety.refusal(language))
        } else {
            val systemPrompt = PlaySessionPromptStore(context).systemPrompt(sessionId, settings.customSystemPrompt)
            val chatRequest = ChatCompletionRequest(
                model = modelName,
                messages = listOf(ChatMessage("system", systemPrompt)) +
                    priorMessages.filter { it.role in setOf("user", "assistant") } +
                    ChatMessage("user", userText, userContentParts),
                maxTokens = settings.localModelMaxTokens.takeIf { it > 0 } ?: 512,
                temperature = settings.localModelTemperature,
                topP = settings.localModelTopP,
                reasoningFormat = if (local && providerId == "llama.cpp") "none" else null,
                chatTemplateEnableThinking = if (local) false else null,
            )
            val responsesApi = !local && providerId in setOf("openai", "codex")
            val url = if (responsesApi) HermesEndpointUrl.responsesUrl(baseUrl)
                else HermesEndpointUrl.chatCompletionsUrl(baseUrl)
            val payload = if (responsesApi) chatRequest.toResponsesPayload() else chatRequest.toChatCompletionPayload()
            val request = Request.Builder().url(url)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }.build()
            val rawBody = transport.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("AI request failed (HTTP ${response.code}); no reply was published")
                val source = response.body?.source() ?: throw IOException("Missing AI response")
                if (source.request(MAX_REPLY_BYTES + 1)) throw IOException("AI response exceeds the supported size")
                source.readUtf8()
            }
            val json = JSONObject(rawBody)
            val chatMessage = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            check(chatMessage?.optJSONArray("tool_calls")?.length().let { it == null || it == 0 }) {
                "Play does not execute model-requested tools"
            }
            val output = json.optJSONArray("output")
            if (output != null) for (index in 0 until output.length()) {
                check(output.optJSONObject(index)?.optString("type") !in setOf("function_call", "computer_call", "local_shell_call")) {
                    "Play does not execute model-requested tools"
                }
            }
            if (!local) {
                com.mobilefork.hermesagent.play.PlayNetworkPolicy.requireTargetMatches(
                    AppSettingsStore(context).load(), providerId, baseUrl,
                )
                RemoteProcessingConsentStore.requireConfiguredRemoteConsent(context)
            }
            val content = assistantDisplayText(if (responsesApi) extractAssistantContentFromResponse(rawBody)
                else extractAssistantContentFromChatCompletion(rawBody))
            com.mobilefork.hermesagent.play.PlayForegroundLifetime.requireForeground()
            check(content.isNotBlank()) { "AI provider returned no displayable answer" }
            // The entire response is screened before publication; unreviewed deltas never reach the UI.
            NativeToolChatSendResult(
                content = if (PlayContentSafety.blocked(content)) PlayContentSafety.refusal(language) else content,
                modelRequestCount = 1,
            )
        }
    })
}

private const val MAX_REPLY_BYTES = 2L * 1024 * 1024

internal class PlaySessionPromptStore(context: Context) {
    private val preferences = context.getSharedPreferences("play-session-prompts-v1", Context.MODE_PRIVATE)

    fun systemPrompt(sessionId: String, customInstructions: String): String {
        val existing = preferences.getString(sessionId, null)
        if (existing != null) return existing
        val prompt = PlayContentSafety.SYSTEM_INSTRUCTIONS + customInstructions.takeIf { it.isNotBlank() }
            ?.let { "\n\nUser style preferences (subject to the safety rules above):\n$it" }.orEmpty()
        check(preferences.edit().putString(sessionId, prompt).commit()) { "Could not freeze the conversation's system instructions" }
        return prompt
    }
}
