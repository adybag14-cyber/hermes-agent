package com.mobilefork.hermesagent.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class NativeAndroidShellToolTest {
    @Test
    fun shellInvocationUsesLoginCommandForPackagedBash() {
        val invocation = NativeAndroidShellTool.shellInvocation(
            shellPath = "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/arm64-v8a/prefix/bin/bash",
            command = "echo hello",
        )

        assertEquals("-lc", invocation[1])
        assertEquals("echo hello", invocation[2])
    }

    @Test
    fun resolveShellPathFallsBackToAndroidSystemShellWhenPackagedShellIsMissing() {
        val state = JSONObject()
            .put("shell_path", File("missing-bash").absolutePath)

        assertEquals("/system/bin/sh", NativeAndroidShellTool.resolveShellPath(state))
    }
}
