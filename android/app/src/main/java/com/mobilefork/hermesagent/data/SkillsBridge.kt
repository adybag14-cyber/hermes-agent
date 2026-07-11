package com.mobilefork.hermesagent.data

import android.content.Context
import com.chaquo.python.Python
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HermesSkill(
    val name: String,
    val path: String,
    val description: String,
    val enabled: Boolean,
)

data class SkillsSnapshot(
    val ok: Boolean,
    val error: String = "",
    val note: String = "",
    val toggleSupported: Boolean = false,
    val skills: List<HermesSkill> = emptyList(),
)

object SkillsBridge {
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
                .getModule("hermes_android.skills_bridge")
                .callAttr(method, *args)
                .toString()
            JSONObject(raw)
        }.getOrElse { error ->
            JSONObject()
                .put("ok", false)
                .put("error", error.message.orEmpty().ifBlank { error::class.java.simpleName })
        }
    }

    fun listSkills(context: Context): SkillsSnapshot {
        val json = call("list_skills", hermesHome(context))
        return parseSnapshot(json)
    }

    fun setSkillEnabled(context: Context, name: String, enabled: Boolean): SkillsSnapshot {
        val json = call("set_skill_enabled", name, enabled, hermesHome(context))
        return parseSnapshot(json)
    }

    private fun parseSnapshot(json: JSONObject): SkillsSnapshot {
        return SkillsSnapshot(
            ok = json.optBoolean("ok", false),
            error = json.optString("error"),
            note = json.optString("note"),
            toggleSupported = json.optBoolean("toggle_supported", false),
            skills = parseSkills(json.optJSONArray("skills")),
        )
    }

    private fun parseSkills(array: JSONArray?): List<HermesSkill> {
        if (array == null) return emptyList()
        val out = ArrayList<HermesSkill>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            out += HermesSkill(
                name = item.optString("name"),
                path = item.optString("path"),
                description = item.optString("description"),
                enabled = item.optBoolean("enabled", true),
            )
        }
        return out
    }
}
