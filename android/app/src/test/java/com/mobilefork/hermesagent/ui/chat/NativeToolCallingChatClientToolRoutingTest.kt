package com.mobilefork.hermesagent.ui.chat

import org.junit.Assert.assertEquals
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
    fun compactToolSpecsDoNotSpendContextOnUnrequestedTools() {
        val specs = client.compactToolSpecsFor("hello, what can you do?")
        assertEquals(0, specs.length())
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

    @Test
    fun activeAlpineCommandUsesOnlySmallRunAliasSchema() {
        val specs = client.compactToolSpecsFor(
            "Inside the active Alpine 3.21 guest, perform this as one guest action: " +
                "printf 'HERMES_GEMMA_ALPINE_TOOL_OK\\n' > /tmp/hermes-gemma-alpine-proof; " +
                "cat /etc/alpine-release >> /tmp/hermes-gemma-alpine-proof.",
        )
        assertEquals(listOf("mcp_run_in_proot"), toolNames(specs))

        val prompt = NativeToolCallingChatClient.buildFocusedSystemPromptContent(
            toolNames = setOf("mcp_run_in_proot"),
        )
        assertTrue(prompt.length < 600)
        assertTrue(prompt.contains("installed Linux guest"))
    }

    private fun toolNames(specs: org.json.JSONArray): List<String> = buildList {
        for (index in 0 until specs.length()) {
            add(specs.getJSONObject(index).getJSONObject("function").getString("name"))
        }
    }
}
