package com.mobilefork.hermesagent.ui.chat

/** Presentation shared by native tool-result handling, independent of the model loop. */
internal fun nativeToolActivityType(toolName: String): AgentEventType = when (toolName) {
    "file_write_tool", "write_file", "file_tool" -> AgentEventType.FileAccess
    "terminal_tool", "terminal", "shell", "mcp_send_terminal_input",
    "mcp_run_in_proot", "linux_sandbox_tool", "linux_sandbox",
    "proot_distro_tool", "proot-distro", "proot_distro" -> AgentEventType.ProcessLog
    else -> AgentEventType.ToolResult
}
