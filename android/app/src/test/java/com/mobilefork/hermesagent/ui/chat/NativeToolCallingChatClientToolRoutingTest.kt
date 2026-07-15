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
    fun generalLocalModelModeAlwaysPublishesCuratedToolArgumentShapes() {
        val specs = client.toolSpecsFor("Tell me a short joke.", "general")
        val names = toolNames(specs)

        assertEquals(
            listOf(
                "terminal_tool",
                "linux_sandbox_tool",
                "file_write_tool",
                "android_ui_tool",
                "android_system_tool",
                "android_automation_tool",
                "android_device_diagnostics_tool",
                "hy_memory_tool",
            ),
            names,
        )
        val terminal = specs.getJSONObject(0).getJSONObject("function")
        assertTrue(terminal.getJSONObject("parameters").getJSONObject("properties").has("command"))
    }

    @Test
    fun smallAndLargeLocalModelModesScalePublishedCatalog() {
        val small = toolNames(client.toolSpecsFor("Hello", "small"))
        val general = toolNames(client.toolSpecsFor("Hello", "general"))
        val large = toolNames(client.toolSpecsFor("Hello", "large"))

        assertEquals(4, small.size)
        assertTrue(general.size > small.size)
        assertTrue(large.size > general.size)
        assertTrue(small.contains("linux_sandbox_tool"))
    }

    @Test
    fun naturalEnglishPwdRequestExposesTerminalTool() {
        val specs = client.compactToolSpecsFor("Could you please run pwd and tell me the current directory?")

        assertTrue(toolNames(specs).contains("terminal_tool"))
        assertEquals(
            "pwd",
            NativeToolCallingChatClient.extractExactTerminalCommand(
                "Could you please run pwd and tell me the current directory?",
            ),
        )
    }

    @Test
    fun naturalTerminalFallbackOnlyMapsFixedReadOnlyIntents() {
        assertEquals("whoami", NativeToolCallingChatClient.inferSafeNaturalTerminalCommand("Tell me the current user"))
        assertEquals("ls -la", NativeToolCallingChatClient.inferSafeNaturalTerminalCommand("Please list files here"))
        assertEquals(null, NativeToolCallingChatClient.inferSafeNaturalTerminalCommand("Delete every file here"))
        assertEquals(null, NativeToolCallingChatClient.inferSafeNaturalTerminalCommand("I like the current directory layout"))
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

    @Test
    fun focusedPromptTellsSmallModelsToolsAreActuallyAvailable() {
        val prompt = NativeToolCallingChatClient.buildFocusedSystemPromptContent(setOf("terminal_tool"))

        assertTrue(prompt.contains("Tools are available"))
        assertTrue(prompt.contains("instead of saying you cannot execute commands"))
        assertTrue(prompt.contains("<tool_call>"))
    }

    @Test
    fun parsesMiniCpmTaggedJsonToolCallFallback() {
        val calls = NativeToolCallingChatClient.parseToolCallContentForTest(
            "<|tool_call_start|>[{\"name\":\"terminal_tool\",\"arguments\":{\"command\":\"pwd\"}}]<|tool_call_end|>",
        )

        assertEquals(1, calls.size)
        assertEquals("terminal_tool", calls.single().first)
        assertTrue(calls.single().second.contains("pwd"))
    }

    @Test
    fun parsesFencedFunctionJsonFallback() {
        val calls = NativeToolCallingChatClient.parseToolCallContentForTest(
            "```json\n{\"function\":{\"name\":\"mcp_run_in_proot\",\"arguments\":\"{\\\"command\\\":\\\"uname -a\\\"}\"}}\n```",
        )

        assertEquals("mcp_run_in_proot", calls.single().first)
        assertTrue(calls.single().second.contains("uname -a"))
    }

    @Test
    fun thinkBlockIsSeparatedFromVisibleAnswer() {
        val (reasoning, answer) = NativeToolCallingChatClient.parseReasoningContentForTest(
            "<think>I should inspect the directory.</think>There are three files.",
        )

        assertEquals("I should inspect the directory.", reasoning)
        assertEquals("There are three files.", answer)
    }

    private fun toolNames(specs: org.json.JSONArray): List<String> = buildList {
        for (index in 0 until specs.length()) {
            add(specs.getJSONObject(index).getJSONObject("function").getString("name"))
        }
    }
}
