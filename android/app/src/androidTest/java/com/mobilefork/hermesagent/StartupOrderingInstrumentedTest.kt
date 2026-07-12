package com.mobilefork.hermesagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.device.HermesLinuxSubsystemBridge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StartupOrderingInstrumentedTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun directPythonStartupLeavesLinuxReadyBeforeInterpreterStarts() {
        assumeTrue(
            "Pass -e run_startup_ordering true in a fresh instrumentation process",
            InstrumentationRegistry.getArguments().getString("run_startup_ordering") == "true",
        )
        assertFalse("Instrumentation process must begin with Python stopped", Python.isStarted())

        HermesRuntimeManager.ensurePythonStarted(context)

        assertTrue("Python did not start", Python.isStarted())
        val linuxState = HermesLinuxSubsystemBridge.readState(context)
        assertTrue("Linux state was not written before Python startup", linuxState != null)
        val shellPath = linuxState?.optString("shell_path").orEmpty()
        assertTrue(
            "Linux shell is not ready: ${linuxState?.toString(2)}",
            shellPath.startsWith("/system/") || File(shellPath).let { it.isFile && it.canExecute() },
        )
        assertTrue(
            "Linux home is not ready: ${linuxState?.toString(2)}",
            File(linuxState?.optString("home_path").orEmpty()).isDirectory,
        )
    }
}
