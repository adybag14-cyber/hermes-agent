package com.mobilefork.hermesagent.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HermesSseClient(
    baseUrl: String,
    private val apiKey: String? = null,
    private val httpClient: OkHttpClient = DEFAULT_HTTP_CLIENT,
    private val networkGuard: (String) -> Unit = {},
    internal val beforeCallRegistration: () -> Unit = {},
) {
    private val normalizedBaseUrl = HermesEndpointUrl.normalizeBaseUrl(baseUrl)
    private val callLock = Any()
    @Volatile
    private var cancelled = false
    @Volatile
    private var activeCall: Call? = null

    fun cancel() {
        val call = synchronized(callLock) {
            cancelled = true
            activeCall
        }
        call?.cancel()
    }

    fun streamChatCompletion(
        request: ChatCompletionRequest,
        onDelta: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
        onStatus: (String) -> Unit = {},
    ) {
        try {
            // Keep streaming and non-streaming retries on one request contract. In
            // particular, local Nanbeige needs its non-thinking controls on both paths.
            val payload = request.copy(stream = true).toChatCompletionPayload()
            val chatUrl = HermesEndpointUrl.chatCompletionsUrl(normalizedBaseUrl)
            onStatus("Opening endpoint stream at ${endpointLabel(chatUrl)}")
            networkGuard(chatUrl)
            val builder = Request.Builder()
                .url(chatUrl)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            if (!apiKey.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $apiKey")
            }
            if (!request.sessionId.isNullOrBlank()) {
                builder.header(HermesApiClient.SESSION_HEADER, request.sessionId)
            }

            val call = httpClient.newCall(builder.build())
            beforeCallRegistration()
            registerCallOrThrow(call)
            try {
                call.execute().use { response ->
                    onStatus("Endpoint responded HTTP ${response.code}; reading SSE frames")
                    val body = response.body
                    if (!response.isSuccessful) {
                        onError("SSE request failed: ${response.code} ${response.message} ${body?.string().orEmpty().takeBodySnippet()}")
                        return
                    }
                    val source = body?.source()
                    if (source == null) {
                        onError("SSE response body was empty")
                        return
                    }
                    parseStream(source, onDelta, onComplete, onError, onStatus)
                }
            } finally {
                clearCall(call)
            }
        } catch (error: Exception) {
            onError(endpointTransportErrorMessage(error))
        }
    }

    fun streamResponse(
        request: ChatCompletionRequest,
        onDelta: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
        onStatus: (String) -> Unit = {},
    ) {
        try {
            val payload = request.toResponsesPayload()
            val responsesUrl = HermesEndpointUrl.responsesUrl(normalizedBaseUrl)
            onStatus("Opening Responses stream at ${endpointLabel(responsesUrl)}")
            networkGuard(responsesUrl)
            val builder = Request.Builder()
                .url(responsesUrl)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            if (!apiKey.isNullOrBlank()) {
                builder.header("Authorization", "Bearer $apiKey")
            }
            if (!request.sessionId.isNullOrBlank()) {
                builder.header(HermesApiClient.SESSION_HEADER, request.sessionId)
            }

            val call = httpClient.newCall(builder.build())
            beforeCallRegistration()
            registerCallOrThrow(call)
            try {
                call.execute().use { response ->
                    onStatus("Responses endpoint returned HTTP ${response.code}; reading SSE frames")
                    val body = response.body
                    if (!response.isSuccessful) {
                        onError("Responses SSE request failed: ${response.code} ${response.message} ${body?.string().orEmpty().takeBodySnippet()}")
                        return
                    }
                    val source = body?.source()
                    if (source == null) {
                        onError("Responses SSE response body was empty")
                        return
                    }
                    parseStream(source, onDelta, onComplete, onError, onStatus)
                }
            } finally {
                clearCall(call)
            }
        } catch (error: Exception) {
            onError(endpointTransportErrorMessage(error))
        }
    }

    private fun registerCallOrThrow(call: Call) {
        val registered = synchronized(callLock) {
            if (cancelled) {
                false
            } else {
                activeCall = call
                true
            }
        }
        if (!registered) {
            call.cancel()
            throw IOException("SSE request cancelled before network start")
        }
    }

    private fun clearCall(call: Call) {
        synchronized(callLock) {
            if (activeCall === call) {
                activeCall = null
            }
        }
    }

    internal fun parseStream(
        source: BufferedSource,
        onDelta: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
        onStatus: (String) -> Unit = {},
    ) {
        var sawDataFrame = false
        var sawFinishReason = false
        var sawAssistantText = false
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            val payload = sseDataPayload(line) ?: continue
            if (payload.isBlank()) {
                continue
            }
            if (!sawDataFrame) {
                sawDataFrame = true
                onStatus("Endpoint stream is live; waiting for assistant text")
            }
            if (payload == "[DONE]") {
                if (sawAssistantText) {
                    onComplete()
                } else {
                    onError(NO_ASSISTANT_TEXT_ERROR)
                }
                return
            }
            val event = runCatching { extractStreamEvent(payload) }.getOrElse { error ->
                onError(error.message ?: error.javaClass.simpleName)
                return
            }
            if (!event.finishReason.isNullOrBlank() && event.finishReason != "null") {
                sawFinishReason = true
            }
            if (!event.delta.isNullOrEmpty()) {
                sawAssistantText = true
                onDelta(event.delta)
            }
        }
        if (sawFinishReason) {
            if (sawAssistantText) {
                onComplete()
            } else {
                onError(NO_ASSISTANT_TEXT_ERROR)
            }
        } else {
            onError(
                if (sawDataFrame) {
                    EARLY_CLOSE_ERROR
                } else {
                    "Custom endpoint stream closed before any SSE data arrived. $CUSTOM_ENDPOINT_HINT"
                },
            )
        }
    }

    private fun sseDataPayload(line: String): String? {
        if (!line.startsWith("data:")) {
            return null
        }
        return line.removePrefix("data:").trim()
    }

    private data class StreamEvent(
        val delta: String?,
        val finishReason: String?,
    )

    private fun extractStreamEvent(payload: String): StreamEvent {
        val root = JSONObject(payload)
        val type = root.optString("type")
        when (type) {
            "response.output_text.delta", "response.refusal.delta" -> {
                return StreamEvent(
                    delta = root.optString("delta").takeIf { it.isNotEmpty() },
                    finishReason = null,
                )
            }
            "response.completed" -> {
                return StreamEvent(delta = null, finishReason = "stop")
            }
            "response.failed", "response.incomplete", "error" -> {
                val error = root.optJSONObject("error")
                val message = error?.optString("message")
                    ?.ifBlank { root.optString("message") }
                    ?.ifBlank { type }
                    ?: type
                throw IllegalArgumentException(message)
            }
        }
        root.optString("output_text").takeIf { it.isNotBlank() }?.let { output ->
            return StreamEvent(delta = output, finishReason = root.optString("finish_reason").ifBlank { null })
        }
        val choices = root.optJSONArray("choices") ?: return StreamEvent(delta = null, finishReason = null)
        if (choices.length() == 0) {
            return StreamEvent(delta = null, finishReason = null)
        }
        val choice = choices.optJSONObject(0) ?: return StreamEvent(delta = null, finishReason = null)
        val delta = choice.optJSONObject("delta")
        return StreamEvent(
            // Whitespace-only chunks are meaningful in streamed prose: providers
            // commonly emit paragraph separators as their own "\n\n" delta.
            delta = delta?.optString("content")?.takeIf { it.isNotEmpty() },
            finishReason = choice.optString("finish_reason").ifBlank { null },
        )
    }

    private fun endpointTransportErrorMessage(error: Exception): String {
        val raw = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return when {
            raw.contains("timeout", ignoreCase = true) ->
                "Custom endpoint stream timed out while waiting for data. $CUSTOM_ENDPOINT_HINT"
            raw.contains("closed", ignoreCase = true) ||
                raw.contains("reset", ignoreCase = true) ||
                raw.contains("disconnect", ignoreCase = true) ||
                raw.contains("unexpected end", ignoreCase = true) ->
                "Custom endpoint stream disconnected: $raw. $CUSTOM_ENDPOINT_HINT"
            else -> raw
        }
    }

    private fun endpointLabel(url: String): String {
        return url
            .removePrefix("https://")
            .removePrefix("http://")
            .take(96)
    }

    private fun String.takeBodySnippet(limit: Int = 240): String {
        val compact = replace(Regex("\\s+"), " ").trim()
        return when {
            compact.isBlank() -> ""
            compact.length <= limit -> compact
            else -> compact.take(limit).trimEnd() + "..."
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val CUSTOM_ENDPOINT_HINT =
            "Check the Base URL, exact model name, mobile network, server timeout, and that the OpenAI-compatible endpoint keeps SSE open until [DONE]."
        private val EARLY_CLOSE_ERROR =
            "Custom endpoint stream closed before the endpoint sent [DONE]. $CUSTOM_ENDPOINT_HINT"
        private val NO_ASSISTANT_TEXT_ERROR =
            "Custom endpoint stream completed without assistant text. $CUSTOM_ENDPOINT_HINT"
        private val DEFAULT_HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
