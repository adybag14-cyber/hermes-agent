package com.mobilefork.hermesagent.api

import org.json.JSONObject

enum class HermesToolPhase { RUNNING, COMPLETED, FAILED }

data class HermesToolActivity(
    val callId: String,
    val tool: String,
    val phase: HermesToolPhase,
    val detail: String,
)

/** Per-stream correlation excludes duplicate/reordered results and bounds history growth. */
internal class HermesToolActivityDecoder {
    private val started = linkedMapOf<String, String>()
    private val completed = mutableSetOf<String>()

    fun decode(eventName: String, payload: String): HermesToolActivity? {
        val root = JSONObject(payload)
        if (eventName == "hermes.tool.progress" || root.optString("type") == "hermes.tool.progress") {
            val id = root.optString("toolCallId")
            val name = root.optString("tool")
            return when (root.optString("status")) {
                "running" -> start(id, name, root.optString("arguments").ifBlank { root.optString("label") })
                "completed" -> finish(id, name, root.optString("result"), HermesToolPhase.COMPLETED)
                "failed" -> finish(id, name, root.optString("result"), HermesToolPhase.FAILED)
                else -> null
            }
        }
        val item = root.optJSONObject("item") ?: return null
        return when (root.optString("type")) {
            "response.output_item.added" -> if (item.optString("type") == "function_call")
                start(item.optString("call_id"), item.optString("name"), item.optString("arguments")) else null
            "response.output_item.done" -> if (item.optString("type") == "function_call_output") {
                val id = item.optString("call_id")
                val output = item.optJSONArray("output")
                val text = if (output == null) item.optString("output") else buildString {
                    for (index in 0 until minOf(output.length(), 16)) {
                        val part = output.optJSONObject(index) ?: continue
                        if (part.optString("type") in setOf("input_text", "text")) append(part.optString("text")).append('\n')
                    }
                }
                finish(id, started[id].orEmpty(), text, HermesToolPhase.COMPLETED)
            } else null
            else -> null
        }
    }

    private fun start(id: String, name: String, detail: String): HermesToolActivity? {
        if (!validId.matches(id) || !validName.matches(name) || id in started || started.size >= 128) return null
        started[id] = name
        return HermesToolActivity(id, name, HermesToolPhase.RUNNING, boundedDetail(detail))
    }

    private fun finish(id: String, name: String, detail: String, phase: HermesToolPhase): HermesToolActivity? {
        if (started[id] != name || !completed.add(id)) return null
        return HermesToolActivity(id, name, phase, boundedDetail(detail))
    }

    private fun boundedDetail(detail: String): String =
        detail.take(8192).filter { it == '\n' || it == '\t' || it >= ' ' }

    companion object {
        private val validId = Regex("[A-Za-z0-9_.:/-]{1,160}")
        private val validName = Regex("[A-Za-z0-9_.:/-]{1,128}")
    }
}
