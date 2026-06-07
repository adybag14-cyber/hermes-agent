package com.mobilefork.hermesagent.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun linuxSandboxCatalogIncludesRecommendedMobileDistros() {
        val catalog = HermesLinuxSandboxCatalog.distroCatalog()
        val ids = buildSet {
            for (index in 0 until catalog.length()) {
                add(catalog.getJSONObject(index).getString("id"))
            }
        }

        assertTrue(ids.contains("debian-bookworm"))
        assertTrue(ids.contains("ubuntu-24-04"))
        assertTrue(ids.contains("alpine-3-21"))
        assertTrue(ids.contains("archlinux"))
        assertTrue(HermesLinuxSandboxCatalog.agentSummary().getJSONArray("desktops").length() >= 3)
    }

    @Test
    fun linuxSandboxCatalogFindsDistroAliases() {
        val alpine = HermesLinuxSandboxCatalog.findDistro("hermes-alpine")

        assertEquals("alpine-3-21", alpine?.getString("id"))
        assertEquals("proot-distro install --name hermes-alpine alpine:3.21", alpine?.getString("install_command"))
    }

    @Test
    fun linuxSandboxBridgeBuildsQuotedInstallAndRunCommands() {
        assertEquals(
            "proot-distro install --name 'hermes-alpine' 'alpine:3.21'",
            HermesLinuxSandboxBridge.installCommandFor("hermes-alpine", "alpine:3.21"),
        )
        val runCommand = HermesLinuxSandboxBridge.runCommandFor(
            prefixPath = "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/x86_64/prefix",
            sandboxName = "hermes-alpine",
            command = "printf 'hello world'",
            qemuPath = "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/x86_64/native-exec/bin/qemu-x86_64",
        )

        assertTrue(runCommand.startsWith("ROOTFS="))
        assertTrue(runCommand.contains("qemu-x86_64"))
        assertTrue(runCommand.contains("qemu_user_direct").not())
        assertTrue(runCommand.contains("QEMU_LD_PREFIX"))
        assertTrue(runCommand.contains("GUEST_SCRIPT="))
        assertTrue(runCommand.contains("busybox"))
        assertTrue(runCommand.contains("alias uname"))
        assertTrue(runCommand.contains("hermes-alpine/rootfs"))
        assertTrue(runCommand.contains("printf"))
        assertTrue(runCommand.contains("hello world"))

        val fallbackCommand = HermesLinuxSandboxBridge.nativePrefixWorkspaceCommandFor(
            prefixPath = "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/x86_64/prefix",
            sandboxName = "hermes-alpine",
            command = "uname -m",
        )

        assertTrue(fallbackCommand.startsWith("SANDBOX_ROOTFS="))
        assertTrue(fallbackCommand.contains("HERMES_SANDBOX_ROOTFS"))
        assertTrue(fallbackCommand.contains("cd \"\$SANDBOX_ROOTFS\""))
        assertTrue(fallbackCommand.endsWith("uname -m"))
    }

    @Test
    fun linuxSandboxBridgeTrimsPromptPunctuationFromSelectors() {
        assertEquals(
            "alpine-3-21",
            HermesLinuxSandboxBridge.normalizeArgumentValue(" alpine-3-21. "),
        )
        assertEquals(
            "hermes-alpine",
            HermesLinuxSandboxBridge.normalizeArgumentValue("hermes-alpine;"),
        )
        assertEquals(
            "alpine:3.21",
            HermesLinuxSandboxBridge.normalizeArgumentValue("alpine:3.21,"),
        )
    }

    @Test
    fun embeddedAliasPreludeRoutesProotDistroThroughPackagedPython() {
        val state = JSONObject()
            .put("uses_termux", true)
            .put("prefix_path", "/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix")
            .put("home_path", "/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/home")
            .put("tmp_path", "/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/tmp")
            .put("app_package_name", "com.nousresearch.hermesagent")
            .put("native_library_dir", "/data/app/example/lib/x86_64")
            .put("lib_path", "/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/lib")
            .put("python_path", "/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/native-exec/bin/python3.13")

        val command = HermesLinuxSubsystemBridge.commandWithEmbeddedToolAliases(state, "proot-distro list")

        assertTrue(command.contains("TERMUX_APP__PACKAGE_NAME='com.nousresearch.hermesagent'"))
        assertTrue(command.contains("TERMUX__PREFIX='/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix'"))
        assertTrue(command.contains("PROOT_TMP_DIR='/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/tmp'"))
        assertTrue(command.contains("PROOT_LOADER='/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/libexec/proot/loader'"))
        assertTrue(command.contains("PROOT_LOADER_32='/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/libexec/proot/loader32'"))
        assertTrue(command.contains("PROOT_NO_SECCOMP='1'"))
        assertTrue(command.contains("LD_LIBRARY_PATH='/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/lib:/data/app/example/lib/x86_64'"))
        assertTrue(command.contains("proot-distro() { case \"\${1:-}\" in login|sh|run)"))
        assertTrue(command.contains("\"${'$'}_pd_cmd\" -e \"LD_LIBRARY_PATH=${'$'}LD_LIBRARY_PATH\" -e \"PROOT_TMP_DIR=${'$'}PROOT_TMP_DIR\" -e \"PROOT_LOADER=${'$'}PROOT_LOADER\" -e \"PROOT_LOADER_32=${'$'}PROOT_LOADER_32\" -e \"PROOT_NO_SECCOMP=${'$'}PROOT_NO_SECCOMP\""))
        assertTrue(command.endsWith("; proot-distro list"))
    }
}
