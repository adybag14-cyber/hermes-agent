package com.mobilefork.hermesagent.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeToolCallingChatClientToolRoutingTest {
    private val client = NativeToolCallingChatClient(org.robolectric.RuntimeEnvironment.getApplication())

    @Test
    fun compactToolSpecsIncludeLinuxSandboxToolsForAlpineDeployPrompt() {
        val specs = client.compactToolSpecsFor(
            "Deploy an Alpine 3.21 proot sandbox, start it, and run uname -a inside.",
        )
        val names = toolNames(specs)

        assertTrue(names.contains("linux_sandbox_tool"))
        assertTrue(names.contains("mcp_run_in_proot"))
    }

    @Test
    fun compactToolSpecsAlwaysIncludeHyMemoryToolEvenWithoutMemoryKeywords() {
        val specs = client.compactToolSpecsFor("hello, what can you do?")
        val names = toolNames(specs)

        assertTrue(
            "hy_memory_tool must always be registered for native agent memory companion",
            names.contains("hy_memory_tool"),
        )
    }

    @Test
    fun compactToolSpecsIncludeMemoryAliasesForRecallPrompt() {
        val specs = client.compactToolSpecsFor(
            "Use memory_search to recall what we stored about the alpine sandbox.",
        )
        val names = toolNames(specs)

        assertTrue(names.contains("hy_memory_tool"))
        assertTrue(names.contains("memory_search"))
    }

    @Test
    fun compactToolSpecsHonorExplicitLinuxSandboxToolRequest() {
        val specs = client.compactToolSpecsFor(
            "Call linux_sandbox_tool with action=deploy and distro_id=alpine-3-21.",
        )
        val names = toolNames(specs)

        assertTrue(names.contains("linux_sandbox_tool"))
        assertTrue(names.contains("mcp_run_in_proot"))
    }

    private fun toolNames(specs: org.json.JSONArray): List<String> = buildList {
        for (index in 0 until specs.length()) {
            add(specs.getJSONObject(index).getJSONObject("function").getString("name"))
        }
    }
}