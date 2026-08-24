package com.mobilefork.hermesagent.device

import android.content.Context
import org.json.JSONObject
import java.util.Locale

object HermesHyMemoryBridge {
    private const val PROVIDER_NAME = "hy_memory"
    private const val ANDROID_BACKEND = "android_local_hy_memory"
    private const val HY_MEMORY_PACKAGE_VERSION = "1.2.18"

    fun performActionJson(
        context: Context,
        rawAction: String,
        arguments: JSONObject = JSONObject(),
        publicationGate: AutomationPublicationGate? = null,
        reinforceRecall: Boolean = true,
    ): String {
        val action = rawAction.trim().lowercase(Locale.US).ifBlank { "status" }
        val delegatedAction = when (action) {
            "hy_memory_status", "memory_status" -> "status"
            "hy_memory_retain", "memory_add", "add" -> "retain"
            "hy_memory_recall", "memory_search", "search" -> "recall"
            "memory_list", "list" -> "list"
            "memory_delete", "delete", "forget" -> "delete"
            else -> action
        }
        return annotate(
            JSONObject(
                HermesHindsightMemoryBridge.performActionJson(
                    context = context.applicationContext,
                    rawAction = delegatedAction,
                    arguments = arguments,
                    publicationGate = publicationGate,
                    reinforceRecall = reinforceRecall,
                ),
            ),
        ).toString()
    }

    fun statusJson(context: Context): JSONObject {
        return annotate(HermesHindsightMemoryBridge.statusJson(context.applicationContext))
    }

    private fun annotate(payload: JSONObject): JSONObject {
        return payload
            .put("provider", PROVIDER_NAME)
            .put("backend", ANDROID_BACKEND)
            .put("tool_name", "hy_memory_tool")
            .put("compatibility_alias", "hindsight_memory_tool")
            .put("hy_memory_package", "hy-memory")
            .put("hy_memory_package_version", HY_MEMORY_PACKAGE_VERSION)
            // Honest product mode: on-device SharedPreferences companion, not full Chroma package.
            .put("provider_mode", "android_local_companion")
            .put(
                "implementation_note",
                "Android uses a local durable-memory companion (retain/recall/list/delete). " +
                    "Desktop hy-memory package path is separate; Settings → Local memory manages this store.",
            )
            .put("default_agent_enabled", true)
            .put("compatible_tool_names", "hy_memory_tool,memory_search,memory_add,memory_delete,memory_list")
            .put("python_provider", "plugins.memory.hy_memory")
            .put("user_visible_name", "Local memory (hy-memory companion)")
    }
}
