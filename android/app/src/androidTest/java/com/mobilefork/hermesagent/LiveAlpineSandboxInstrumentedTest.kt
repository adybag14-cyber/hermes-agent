package com.mobilefork.hermesagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilefork.hermesagent.device.HermesLinuxSandboxBridge
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveAlpineSandboxInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun productionBridgeInstallsListsStartsAndRunsAlpine() {
        assumeTrue(
            "Pass -e run_live_sandbox true to allow the real Alpine network install",
            InstrumentationRegistry.getArguments().getString("run_live_sandbox") == "true",
        )

        var status = HermesLinuxSandboxBridge.performAction(context, action = "status")
        assertTrue(status.toString(), status.optBoolean("proot_available"))
        assertTrue(status.toString(), status.optBoolean("proot_distro_available"))

        if (
            InstrumentationRegistry.getArguments().getString("reset_live_sandbox") == "true" &&
            status.optJSONArray("installed_sandboxes").containsSandbox("hermes-alpine")
        ) {
            val remove = HermesLinuxSandboxBridge.performAction(
                context = context,
                action = "remove",
                name = "hermes-alpine",
                timeoutSeconds = 120,
            )
            assertEquals(remove.toString(2), 0, remove.optInt("exit_code", -1))
            status = HermesLinuxSandboxBridge.performAction(context, action = "status")
        }

        if (!status.optJSONArray("installed_sandboxes").containsSandbox("hermes-alpine")) {
            val install = HermesLinuxSandboxBridge.performAction(
                context = context,
                action = "download",
                distroId = "alpine-3-21",
                timeoutSeconds = 600,
            )
            assertEquals(install.toString(2), 0, install.optInt("exit_code", -1))
        }

        status = HermesLinuxSandboxBridge.performAction(context, action = "status")
        assertTrue(status.toString(2), status.optJSONArray("installed_sandboxes").containsSandbox("hermes-alpine"))
        val installed = status.optJSONArray("installed_sandboxes").findSandbox("hermes-alpine")
        assertEquals(status.toString(2), status.optString("preferred_guest_architecture"), installed.optString("architecture"))
        assertTrue(status.toString(2), installed.optBoolean("android_execution_supported"))

        val start = HermesLinuxSandboxBridge.performAction(
            context = context,
            action = "start",
            distroId = "alpine-3-21",
        )
        assertEquals(start.toString(2), 0, start.optInt("exit_code", -1))
        assertTrue(start.toString(2), start.optBoolean("agent_shell_enabled"))
        assertEquals("hermes-alpine", start.optString("active_sandbox_name"))

        val run = HermesLinuxSandboxBridge.performAction(
            context = context,
            action = "run",
            distroId = "alpine-3-21",
            command = "printf 'HERMES_ALPINE_OK '; cat /etc/alpine-release; printf ' '; uname -m; printf ' '; pwd",
            timeoutSeconds = 120,
        )
        assertEquals(run.toString(2), 0, run.optInt("exit_code", -1))
        assertEquals(run.toString(2), "proot_distro_qemu", run.optString("sandbox_execution_mode"))
        assertTrue(run.toString(2), run.optString("output").contains("HERMES_ALPINE_OK"))
        assertTrue(run.toString(2), run.optString("output").contains("3.21"))

        val finalStatus = HermesLinuxSandboxBridge.performAction(context, action = "status")
        assertTrue(finalStatus.toString(2), finalStatus.optBoolean("agent_shell_enabled"))
        assertTrue(finalStatus.toString(2), finalStatus.optJSONArray("installed_sandboxes").containsSandbox("hermes-alpine"))
    }

    private fun JSONArray?.containsSandbox(name: String): Boolean {
        return findSandbox(name).length() > 0
    }

    private fun JSONArray?.findSandbox(name: String): JSONObject {
        val values = this ?: return JSONObject()
        for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: JSONObject()
            if (item.optString("name") == name) return item
        }
        return JSONObject()
    }
}
