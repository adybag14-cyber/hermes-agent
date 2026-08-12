package com.mobilefork.hermesagent.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun resolveShellPathHonorsPersistedAndroidSystemFallback() {
        val state = JSONObject()
            .put("execution_mode", "android_system_shell")
            .put("shell_path", "/data/app/example/lib/x86_64/libhermes_android_bash.so")

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
        assertTrue(ids.contains("opensuse-tumbleweed"))
        assertTrue(HermesLinuxSandboxCatalog.agentSummary().getJSONArray("desktops").length() >= 3)
    }

    @Test
    fun linuxSandboxCatalogFindsDistroAliases() {
        val alpine = HermesLinuxSandboxCatalog.findDistro("hermes-alpine")

        assertEquals("alpine-3-21", alpine?.getString("id"))
        assertEquals("proot-distro install --name hermes-alpine alpine:3.21", alpine?.getString("install_command"))
    }

    @Test
    fun linuxSandboxCatalogIncludesMirrorProfiles() {
        val mirrors = HermesLinuxSandboxCatalog.mirrorProfiles()
        val ids = buildSet {
            for (index in 0 until mirrors.length()) {
                add(mirrors.getJSONObject(index).getString("id"))
            }
        }
        assertTrue(ids.contains("default"))
        assertTrue(ids.contains("china"))
        assertTrue(ids.contains("aliyun"))
        assertTrue(HermesLinuxSandboxCatalog.mirrorCommandFor("apt", "china").contains("mirrors.aliyun.com"))
        assertTrue(HermesLinuxSandboxCatalog.mirrorCommandFor("apk", "tsinghua").contains("mirrors.tuna.tsinghua.edu.cn"))
    }

    @Test
    fun linuxSandboxBridgeBuildsPackageUpdateCommands() {
        assertEquals(
            "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y upgrade",
            HermesLinuxSandboxBridge.updateCommandFor("apt"),
        )
        assertEquals(
            "zypper --non-interactive refresh && zypper --non-interactive update",
            HermesLinuxSandboxBridge.updateCommandFor("zypper"),
        )
        assertTrue(HermesLinuxSandboxBridge.updateCommandFor("").contains("command -v apt-get"))
    }

    @Test
    fun linuxSandboxInstallRetriesOnlyTransientTlsRecordFailuresWithAndroidHttp() {
        assertTrue(
            HermesLinuxSandboxBridge.shouldRetryInstallWithAndroidHttp(
                JSONObject().put("exit_code", 1).put("error", "SSL: RECORD_LAYER_FAILURE"),
            ),
        )
        assertTrue(
            HermesLinuxSandboxBridge.shouldRetryInstallWithAndroidHttp(
                JSONObject().put("exit_code", 1).put("error", "UNEXPECTED_EOF_WHILE_READING"),
            ),
        )
        assertTrue(
            HermesLinuxSandboxBridge.shouldRetryInstallWithAndroidHttp(
                JSONObject().put("exit_code", 1).put("error", "certificate verify failed"),
            ).not(),
        )
        assertEquals(
            "aca76fef1f67058b",
            HermesLinuxSandboxBridge.dockerManifestCacheKey("alpine:3.21", "x86_64"),
        )
        assertEquals(
            "b632145ecd134a4c",
            HermesLinuxSandboxBridge.dockerManifestCacheKey("alpine:3.21", "aarch64"),
        )
    }

    @Test
    fun linuxSandboxBridgeBuildsQuotedInstallAndRunCommands() {
        assertEquals(
            "proot-distro install --name 'hermes-alpine' --architecture 'aarch64' 'alpine:3.21'",
            HermesLinuxSandboxBridge.installCommandFor("hermes-alpine", "alpine:3.21", "aarch64"),
        )
        assertEquals("aarch64", HermesLinuxSandboxBridge.preferredGuestArchitecture("x86_64"))
        assertEquals("x86_64", HermesLinuxSandboxBridge.preferredGuestArchitecture("arm64-v8a"))
        val runCommand = HermesLinuxSandboxBridge.runCommandFor(
            prefixPath = "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/x86_64/prefix",
            sandboxName = "hermes-alpine",
            command = "printf 'hello world'",
            qemuPath = "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/x86_64/native-exec/bin/qemu-x86_64",
        )

        assertTrue(runCommand.startsWith("HERMES_SANDBOX_ROOTFS="))
        assertTrue(runCommand.contains("qemu-x86_64"))
        assertTrue(runCommand.contains("proot-distro run 'hermes-alpine'"))
        assertTrue(runCommand.contains("--emulator"))
        assertTrue(runCommand.contains("/bin/sh -lc"))
        assertTrue(runCommand.contains("hermes-alpine/rootfs"))
        assertTrue(runCommand.contains("printf"))
        assertTrue(runCommand.contains("hello world"))

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
            .put("python_path", "/data/app/example/lib/x86_64/libhermes_exec_bin_python3_14.so")
            .put("python_lib_path", "/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/lib/python3.14")
            .put("native_proot_path", "/data/app/example/lib/x86_64/libhermes_exec_bin_proot.so")
            .put("native_execution_route", "apk_native_library_direct")

        val command = HermesLinuxSubsystemBridge.commandWithEmbeddedToolAliases(state, "proot-distro list")

        assertTrue(command.contains("TERMUX_APP__PACKAGE_NAME='com.nousresearch.hermesagent'"))
        assertTrue(command.contains("TERMUX__PREFIX='/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix'"))
        assertTrue(command.contains("PROOT_TMP_DIR='/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/tmp'"))
        assertTrue(command.contains("PROOT_LOADER='/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/libexec/proot/loader'"))
        assertTrue(command.contains("PROOT_LOADER_32='/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/libexec/proot/loader32'"))
        assertTrue(command.contains("PROOT_NO_SECCOMP='1'"))
        assertTrue(command.contains("LD_LIBRARY_PATH='/data/user/0/com.nousresearch.hermesagent/files/hermes-home/linux/x86_64/prefix/lib:/data/app/example/lib/x86_64'"))
        assertTrue(command.contains("/data/app/example/lib/x86_64/libhermes_exec_bin_python3_14.so'"))
        assertTrue(command.contains("HERMES_ANDROID_PROOT_EXECUTABLE='/data/app/example/lib/x86_64/libhermes_exec_bin_proot.so'"))
        assertTrue(command.contains("python3.13").not())
        assertTrue(command.contains("proot-distro() { case \"\${1:-}\" in login|sh|run)"))
        assertTrue(command.contains("\"${'$'}_pd_cmd\" -e \"LD_LIBRARY_PATH=${'$'}LD_LIBRARY_PATH\" -e \"PROOT_TMP_DIR=${'$'}PROOT_TMP_DIR\" -e \"PROOT_LOADER=${'$'}PROOT_LOADER\" -e \"PROOT_LOADER_32=${'$'}PROOT_LOADER_32\" -e \"PROOT_NO_SECCOMP=${'$'}PROOT_NO_SECCOMP\""))
        assertTrue(command.endsWith("; proot-distro list"))
    }

    @Test
    fun embeddedEnvironmentPublishesOnlyDirectPackagedProotPath() {
        val state = JSONObject()
            .put("prefix_path", "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/arm64-v8a/prefix")
            .put("native_proot_path", "/data/app/example/lib/arm64/libhermes_exec_bin_proot.so")
            .put("native_command_env_path", "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/arm64-v8a/native-command-functions.sh")

        val environment = HermesLinuxSubsystemBridge.buildRunEnvironment(state)

        assertEquals(
            "/data/app/example/lib/arm64/libhermes_exec_bin_proot.so",
            environment["HERMES_ANDROID_PROOT_EXECUTABLE"],
        )
        assertEquals(
            "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/arm64-v8a/native-command-functions.sh",
            environment["HERMES_ANDROID_NATIVE_COMMAND_ENV"],
        )
    }

    @Test
    fun sandboxQemuPrefersDirectApkNativeLibrary() {
        val qemu = File.createTempFile("hermes-qemu-direct-", ".so")
        try {
            qemu.writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
            qemu.setExecutable(true, false)
            val state = JSONObject()
                .put("native_qemu_x86_64_path", qemu.absolutePath)
                .put("prefix_path", File(qemu.parentFile, "prefix").absolutePath)

            assertEquals(
                qemu.absolutePath,
                HermesLinuxSandboxBridge.qemuPathForGuestArchitecture(state, "x86_64"),
            )
        } finally {
            qemu.delete()
        }
    }

    @Test
    fun sandboxQemuRejectsLegacyShimResolvedIntoWritablePrefix() {
        val root = createTempDir(prefix = "hermes-qemu-prefix-")
        try {
            val prefix = File(root, "prefix").apply { mkdirs() }
            val writableBin = File(prefix, "bin").apply { mkdirs() }
            File(writableBin, "qemu-x86_64").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
                setExecutable(true, false)
            }
            val state = JSONObject()
                .put("prefix_path", prefix.absolutePath)
                .put("native_bin_path", writableBin.absolutePath)

            val resolved = HermesLinuxSandboxBridge.qemuPathForGuestArchitecture(state, "x86_64")

            assertTrue(resolved.isBlank())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sandboxWritablePrefixContainmentDoesNotMatchSiblingWithSameNamePrefix() {
        val root = kotlin.io.path.createTempDirectory("hermes-prefix-boundary-").toFile()
        try {
            val prefix = File(root, "prefix").apply { mkdirs() }
            val inside = File(prefix, "bin/qemu-x86_64")
            val sibling = File(root, "prefix-sibling/bin/qemu-x86_64")

            assertTrue(HermesLinuxSandboxBridge.isInsideDirectory(inside, prefix))
            assertFalse(HermesLinuxSandboxBridge.isInsideDirectory(sibling, prefix))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exit126HintRejectsChmodAndBroadStorageWorkarounds() {
        val prefix = "/data/user/0/com.mobilefork.hermesagent/files/hermes-home/linux/arm64-v8a/prefix"
        val hint = NativeAndroidShellTool.executionDeniedHint(
            JSONObject()
                .put("prefix_path", prefix)
                .put("native_execution_route", "apk_native_library_direct"),
            "$prefix/bin/curl --version",
        )

        assertTrue(hint.contains("writable prefix path"))
        assertTrue(hint.contains("cannot be made executable with chmod"))
        assertTrue(hint.contains("do not grant broad storage permission"))
    }
}
