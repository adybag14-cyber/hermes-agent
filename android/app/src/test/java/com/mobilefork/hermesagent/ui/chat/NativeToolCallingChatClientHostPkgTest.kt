package com.mobilefork.hermesagent.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NativeToolCallingChatClientHostPkgTest {
    private val client = NativeToolCallingChatClient(org.robolectric.RuntimeEnvironment.getApplication())

    @Test
    fun compactToolSpecsIncludeHostPkgForProotUpgradePrompt() {
        val specs = client.compactToolSpecsFor("Please upgrade proot and refresh the host linux suite packages")
        val names = toolNames(specs)
        assertTrue(names.contains("linux_host_pkg_tool"))
    }

    @Test
    fun compactToolSpecsIncludeHostPkgForPkgInstallPrompt() {
        val specs = client.compactToolSpecsFor("Run pkg install tree on the host prefix")
        val names = toolNames(specs)
        assertTrue(names.contains("linux_host_pkg_tool") || names.contains("terminal_tool"))
    }

    private fun toolNames(specs: org.json.JSONArray): List<String> = buildList {
        for (index in 0 until specs.length()) {
            add(specs.getJSONObject(index).getJSONObject("function").getString("name"))
        }
    }
}
