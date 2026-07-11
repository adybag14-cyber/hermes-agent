package com.mobilefork.hermesagent.data

import android.content.Context
import com.chaquo.python.Python
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class KanbanTask(
    val id: String,
    val title: String,
    val body: String,
    val assignee: String,
    val status: String,
    val priority: Int,
    val result: String,
    val createdAtEpochSec: Long,
)

data class KanbanBoardSnapshot(
    val ok: Boolean,
    val error: String = "",
    val board: String = "default",
    val note: String = "",
    val counts: Map<String, Int> = emptyMap(),
    val tasks: List<KanbanTask> = emptyList(),
)

object KanbanBridge {
    private fun hermesHome(context: Context): String {
        return File(context.applicationContext.filesDir, "hermes-home").absolutePath
    }

    private fun call(method: String, vararg args: Any?): JSONObject {
        if (!Python.isStarted()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "Python runtime is not started yet. Wait for Hermes boot to finish.")
        }
        return runCatching {
            val raw = Python.getInstance()
                .getModule("hermes_android.kanban_bridge")
                .callAttr(method, *args)
                .toString()
            JSONObject(raw)
        }.getOrElse { error ->
            JSONObject()
                .put("ok", false)
                .put("error", error.message.orEmpty().ifBlank { error::class.java.simpleName })
        }
    }

    fun ensureBoard(context: Context): KanbanBoardSnapshot {
        val json = call("ensure_board", hermesHome(context), null)
        return KanbanBoardSnapshot(
            ok = json.optBoolean("ok", false),
            error = json.optString("error"),
            board = json.optString("board", "default"),
            note = json.optString("note"),
        )
    }

    fun listBoard(context: Context, status: String? = null): KanbanBoardSnapshot {
        val json = call("list_board", hermesHome(context), status, null, 200)
        val countsJson = json.optJSONObject("counts") ?: JSONObject()
        val counts = mutableMapOf<String, Int>()
        countsJson.keys().forEach { key -> counts[key] = countsJson.optInt(key, 0) }
        val tasks = parseTasks(json.optJSONArray("tasks"))
        return KanbanBoardSnapshot(
            ok = json.optBoolean("ok", false),
            error = json.optString("error"),
            board = json.optString("board", "default"),
            counts = counts,
            tasks = tasks,
        )
    }

    fun createTask(context: Context, title: String, body: String = ""): KanbanBoardSnapshot {
        val create = call("create_task", title, body, hermesHome(context), null, null)
        if (!create.optBoolean("ok", false)) {
            return KanbanBoardSnapshot(ok = false, error = create.optString("error"))
        }
        return listBoard(context)
    }

    fun completeTask(context: Context, taskId: String, summary: String = ""): KanbanBoardSnapshot {
        val result = call("complete_task", taskId, summary, hermesHome(context), null)
        if (!result.optBoolean("ok", false)) {
            return KanbanBoardSnapshot(ok = false, error = result.optString("error"))
        }
        return listBoard(context)
    }

    fun unblockTask(context: Context, taskId: String): KanbanBoardSnapshot {
        val result = call("unblock_task", taskId, hermesHome(context), null)
        if (!result.optBoolean("ok", false)) {
            return KanbanBoardSnapshot(ok = false, error = result.optString("error"))
        }
        return listBoard(context)
    }

    fun commentTask(context: Context, taskId: String, text: String): KanbanBoardSnapshot {
        val result = call("comment_task", taskId, text, hermesHome(context), null, "android-ui")
        if (!result.optBoolean("ok", false)) {
            return KanbanBoardSnapshot(ok = false, error = result.optString("error"))
        }
        return listBoard(context)
    }

    private fun parseTasks(array: JSONArray?): List<KanbanTask> {
        if (array == null) return emptyList()
        val out = ArrayList<KanbanTask>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            out += KanbanTask(
                id = item.optString("id"),
                title = item.optString("title"),
                body = item.optString("body"),
                assignee = item.optString("assignee"),
                status = item.optString("status"),
                priority = item.optInt("priority"),
                result = item.optString("result"),
                createdAtEpochSec = item.optLong("created_at"),
            )
        }
        return out
    }
}
