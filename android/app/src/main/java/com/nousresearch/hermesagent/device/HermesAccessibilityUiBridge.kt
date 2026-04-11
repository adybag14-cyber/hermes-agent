package com.nousresearch.hermesagent.device

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object HermesAccessibilityUiBridge {
    private const val DEFAULT_LIMIT = 80
    private const val MAX_LIMIT = 200

    @JvmStatic
    fun snapshotJson(limit: Int): String {
        return runCatching {
            val service = HermesAccessibilityController.currentService()
                ?: return errorJson("Hermes accessibility service is not connected")
            val root = service.rootInActiveWindow
                ?: return errorJson("No active accessibility window is available")
            val cappedLimit = limit.coerceIn(1, MAX_LIMIT)
            val nodes = flattenNodes(root, cappedLimit)
            JSONObject().apply {
                put("accessibility_connected", true)
                put("active_package", root.packageName?.toString().orEmpty())
                put("node_count", nodes.size)
                put("nodes", JSONArray(nodes.mapIndexed { index, node -> nodeJson(node, index) }))
            }.toString()
        }.getOrElse { error ->
            errorJson(error.message ?: error.javaClass.simpleName)
        }
    }

    @JvmStatic
    fun performActionJson(
        action: String,
        textContains: String,
        contentDescriptionContains: String,
        viewId: String,
        packageName: String,
        value: String,
        index: Int,
    ): String {
        return runCatching {
            val service = HermesAccessibilityController.currentService()
                ?: return errorJson("Hermes accessibility service is not connected")
            val root = service.rootInActiveWindow
                ?: return errorJson("No active accessibility window is available")
            val nodes = flattenNodes(root, MAX_LIMIT)
            val matches = nodes.filter { node ->
                matchesSelector(node, textContains, contentDescriptionContains, viewId, packageName)
            }
            if (matches.isEmpty()) {
                return errorJson("No accessibility node matched the requested selector")
            }

            val resolvedIndex = index.coerceAtLeast(0)
            if (resolvedIndex >= matches.size) {
                return errorJson("Requested match index $resolvedIndex but only ${matches.size} node(s) matched")
            }
            val selected = matches[resolvedIndex]
            val performed = performResolvedAction(action, selected, value)
            JSONObject().apply {
                put("success", performed)
                put("action", action)
                put("matched_count", matches.size)
                put("matched_node", nodeJson(selected, resolvedIndex))
            }.toString()
        }.getOrElse { error ->
            errorJson(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun flattenNodes(root: AccessibilityNodeInfo, limit: Int): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || nodes.size >= limit) {
                return
            }
            nodes.add(node)
            for (childIndex in 0 until node.childCount) {
                visit(node.getChild(childIndex))
                if (nodes.size >= limit) {
                    return
                }
            }
        }

        visit(root)
        return nodes
    }

    private fun matchesSelector(
        node: AccessibilityNodeInfo,
        textContains: String,
        contentDescriptionContains: String,
        viewId: String,
        packageName: String,
    ): Boolean {
        if (textContains.isNotBlank() && !node.text?.toString().orEmpty().contains(textContains, ignoreCase = true)) {
            return false
        }
        if (contentDescriptionContains.isNotBlank() && !node.contentDescription?.toString().orEmpty().contains(contentDescriptionContains, ignoreCase = true)) {
            return false
        }
        if (viewId.isNotBlank() && !node.viewIdResourceName.orEmpty().contains(viewId, ignoreCase = true)) {
            return false
        }
        if (packageName.isNotBlank() && !node.packageName?.toString().orEmpty().contains(packageName, ignoreCase = true)) {
            return false
        }
        return textContains.isNotBlank() ||
            contentDescriptionContains.isNotBlank() ||
            viewId.isNotBlank() ||
            packageName.isNotBlank()
    }

    private fun performResolvedAction(action: String, node: AccessibilityNodeInfo, value: String): Boolean {
        return when (action.lowercase()) {
            "click" -> findSelfOrAncestor(node) { it.isClickable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            "long_click" -> findSelfOrAncestor(node) { it.isLongClickable }?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
            "focus" -> findSelfOrAncestor(node) { it.isFocusable }?.performAction(AccessibilityNodeInfo.ACTION_FOCUS) == true
            "scroll_forward" -> findSelfOrAncestor(node) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
            "scroll_backward" -> findSelfOrAncestor(node) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
            "set_text" -> {
                val editableTarget = findSelfOrAncestor(node) { it.isEditable || it.actionList.any { actionItem -> actionItem.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
                    ?: throw IOException("No editable accessibility node matched the selector")
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                }
                editableTarget.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }
            else -> throw IOException("Unsupported accessibility action: $action")
        }
    }

    private fun findSelfOrAncestor(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (predicate(current)) {
                return current
            }
            current = current.parent
        }
        return null
    }

    private fun nodeJson(node: AccessibilityNodeInfo, index: Int): JSONObject {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return JSONObject().apply {
            put("index", index)
            put("text", node.text?.toString().orEmpty())
            put("content_description", node.contentDescription?.toString().orEmpty())
            put("view_id", node.viewIdResourceName.orEmpty())
            put("package_name", node.packageName?.toString().orEmpty())
            put("class_name", node.className?.toString().orEmpty())
            put("clickable", node.isClickable)
            put("editable", node.isEditable)
            put("scrollable", node.isScrollable)
            put("enabled", node.isEnabled)
            put("focused", node.isFocused)
            put(
                "bounds",
                JSONObject().apply {
                    put("left", bounds.left)
                    put("top", bounds.top)
                    put("right", bounds.right)
                    put("bottom", bounds.bottom)
                },
            )
        }
    }

    private fun errorJson(message: String): String {
        return JSONObject().apply {
            put("error", message)
        }.toString()
    }
}
