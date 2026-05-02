package com.nousresearch.hermesagent.ui.chat

import android.content.Context
import com.nousresearch.hermesagent.api.HermesApiClient
import com.nousresearch.hermesagent.device.NativeAndroidShellTool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class NativeToolCallingChatClient(
    context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES)
        .build(),
) {
    private val appContext = context.applicationContext

    data class Result(
        val content: String,
        val executedToolCalls: Int,
    )

    fun send(
        baseUrl: String,
        modelName: String,
        sessionId: String,
        userText: String,
    ): Result {
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val messages = JSONArray()
            .put(systemMessage())
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("content", userText)
            )

        var executedToolCalls = 0
        var latestToolResult = ""
        var assistant = postChatCompletion(
            normalizedBaseUrl = normalizedBaseUrl,
            modelName = modelName,
            sessionId = sessionId,
            messages = messages,
            includeTools = true,
        )

        repeat(MAX_TOOL_ROUNDS) {
            if (assistant.toolCalls.isEmpty()) {
                val content = assistant.content.ifBlank {
                    latestToolResult.ifBlank { "Done." }
                }
                return Result(content = content, executedToolCalls = executedToolCalls)
            }

            messages.put(assistant.toJsonMessage())
            assistant.toolCalls.forEach { toolCall ->
                val toolResult = executeToolCall(toolCall)
                executedToolCalls += 1
                latestToolResult = toolResult
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", toolCall.id)
                        .put("name", toolCall.name)
                        .put("content", toolResult)
                )
            }
            assistant = postChatCompletion(
                normalizedBaseUrl = normalizedBaseUrl,
                modelName = modelName,
                sessionId = sessionId,
                messages = messages,
                includeTools = true,
            )
        }

        val content = assistant.content.ifBlank {
            latestToolResult.ifBlank { "Tool call completed." }
        }
        return Result(content = content, executedToolCalls = executedToolCalls)
    }

    private fun postChatCompletion(
        normalizedBaseUrl: String,
        modelName: String,
        sessionId: String,
        messages: JSONArray,
        includeTools: Boolean,
    ): AssistantMessage {
        val payload = JSONObject()
            .put("model", modelName)
            .put("stream", false)
            .put("messages", messages)
        if (includeTools) {
            payload.put("tools", toolSpecs())
        }

        val request = Request.Builder()
            .url("$normalizedBaseUrl/v1/chat/completions")
            .header(HermesApiClient.SESSION_HEADER, sessionId)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            require(response.isSuccessful) { "Native chat request failed: ${response.code} $body" }
            val root = JSONObject(body)
            val message = root
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
            return AssistantMessage.fromJson(message)
        }
    }

    private fun executeToolCall(toolCall: ToolCall): String {
        return when (toolCall.name) {
            "terminal_tool", "terminal", "shell" -> executeTerminalTool(toolCall)
            else -> JSONObject()
                .put("exit_code", 127)
                .put("error", "Unsupported native Hermes tool: ${toolCall.name}")
                .toString()
        }
    }

    private fun executeTerminalTool(toolCall: ToolCall): String {
        val command = listOf("command", "cmd", "input")
            .firstNotNullOfOrNull { key -> toolCall.arguments.optString(key).takeIf { it.isNotBlank() } }
            ?: return JSONObject()
                .put("exit_code", 2)
                .put("error", "terminal_tool requires a command argument")
                .toString()

        val result = NativeAndroidShellTool.run(
            context = appContext,
            command = command,
            timeoutSeconds = TOOL_TIMEOUT_SECONDS.toLong(),
        )
        return JSONObject()
            .put("exit_code", result.optInt("exit_code", -1))
            .put("output", truncate(result.optString("output")))
            .put("error", truncate(result.optString("error")))
            .put("cwd", result.optString("cwd"))
            .toString()
    }

    private fun systemMessage(): JSONObject {
        return JSONObject()
            .put("role", "system")
            .put(
                "content",
                "You are Hermes running inside the native Android app. " +
                    "You have a function named terminal_tool. " +
                    "When the user asks to run a command, inspect the filesystem, write a file, read a file, or use a device command, call terminal_tool instead of simulating the result. " +
                    "terminal_tool runs through /system/bin/sh in the Hermes app workspace.",
            )
    }

    private fun toolSpecs(): JSONArray {
        return JSONArray().put(
            JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", "terminal_tool")
                        .put(
                            "description",
                            "Run a short Android native shell command through /system/bin/sh in the Hermes app workspace and return stdout, stderr, exit code, and cwd.",
                        )
                        .put(
                            "parameters",
                            JSONObject()
                                .put("type", "object")
                                .put(
                                    "properties",
                                    JSONObject()
                                        .put(
                                            "command",
                                            JSONObject()
                                                .put("type", "string")
                                                .put("description", "The shell command to run."),
                                        ),
                                )
                                .put("required", JSONArray().put("command")),
                        ),
                ),
        )
    }

    private fun truncate(value: String): String {
        return if (value.length <= MAX_TOOL_RESULT_CHARS) {
            value
        } else {
            value.take(MAX_TOOL_RESULT_CHARS) + "\n[truncated]"
        }
    }

    private data class AssistantMessage(
        val content: String,
        val toolCalls: List<ToolCall>,
    ) {
        fun toJsonMessage(): JSONObject {
            val json = JSONObject()
                .put("role", "assistant")
                .put("content", content.ifBlank { JSONObject.NULL })
            if (toolCalls.isNotEmpty()) {
                json.put(
                    "tool_calls",
                    JSONArray().apply {
                        toolCalls.forEach { put(it.toJson()) }
                    },
                )
            }
            return json
        }

        companion object {
            fun fromJson(json: JSONObject): AssistantMessage {
                val toolCalls = mutableListOf<ToolCall>()
                val rawToolCalls = json.optJSONArray("tool_calls") ?: JSONArray()
                for (index in 0 until rawToolCalls.length()) {
                    val rawToolCall = rawToolCalls.optJSONObject(index) ?: continue
                    val function = rawToolCall.optJSONObject("function") ?: JSONObject()
                    val name = function.optString("name").ifBlank { "terminal_tool" }
                    val arguments = runCatching {
                        JSONObject(function.optString("arguments", "{}"))
                    }.getOrDefault(JSONObject())
                    toolCalls += ToolCall(
                        id = rawToolCall.optString("id").ifBlank { "call_${UUID.randomUUID()}_$index" },
                        name = name,
                        arguments = arguments,
                    )
                }
                return AssistantMessage(
                    content = json.optString("content").takeUnless { json.isNull("content") }.orEmpty(),
                    toolCalls = toolCalls,
                )
            }
        }
    }

    private data class ToolCall(
        val id: String,
        val name: String,
        val arguments: JSONObject,
    ) {
        fun toJson(): JSONObject {
            return JSONObject()
                .put("id", id)
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", name)
                        .put("arguments", arguments.toString()),
                )
        }
    }

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val MAX_TOOL_ROUNDS = 2
        private const val TOOL_TIMEOUT_SECONDS = 60
        private const val MAX_TOOL_RESULT_CHARS = 12_000
    }
}
