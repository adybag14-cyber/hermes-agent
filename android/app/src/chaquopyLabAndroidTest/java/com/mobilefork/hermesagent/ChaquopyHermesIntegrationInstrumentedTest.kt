package com.mobilefork.hermesagent

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chaquo.python.Python
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in, non-release evidence: genuine dependencies plus the real Hermes agent loop. */
@RunWith(AndroidJUnit4::class)
class ChaquopyHermesIntegrationInstrumentedTest {
    @Test
    fun sourceBuiltForkRunsGenuineSdksAndHermesAgent() {
        assertTrue("Build with a verified -PhermesChaquopyLab bundle", BuildConfig.HERMES_CHAQUOPY_LAB)
        val context = ApplicationProvider.getApplicationContext<Context>()
        HermesRuntimeManager.stop()
        HermesRuntimeManager.ensurePythonStarted(context)
        val python = Python.getInstance()
        val evidence = File(context.filesDir, "chaquopy-hermes-integration").apply { mkdirs() }
        val sdkReportFile = File(evidence, "sdk-runtime-report.json")
        val sdkResult = python.getModule("runtime_smoke").callAttr(
            "main", arrayOf("--package", "full", "--require-android", "--json-output", sdkReportFile.absolutePath),
        ).toInt()
        assertEquals("Genuine SDK runtime tests failed; inspect ${sdkReportFile.absolutePath}", 0, sdkResult)
        val sdkReport = JSONObject(sdkReportFile.readText())
        assertEquals("passed", sdkReport.getString("status"))
        assertTrue(sdkReport.getBoolean("android_execution_verified"))
        assertTrue(sdkReport.getJSONObject("tests").getInt("run") > 0)
        assertEquals(0, sdkReport.getJSONObject("tests").getJSONArray("skipped").length())
        val hermesReport = JSONObject(
            python.getModule("hermes_runtime_smoke").callAttr("run", evidence.absolutePath, true).toString(),
        )
        assertEquals("passed", hermesReport.getString("status"))
        assertTrue(hermesReport.getBoolean("android_execution_verified"))
        assertFalse(hermesReport.getBoolean("model_inference_verified"))
        hermesReport.put("android_api", Build.VERSION.SDK_INT)
        hermesReport.put("android_abis", Build.SUPPORTED_ABIS.joinToString())
        hermesReport.put("litertlm_coordinate", BuildConfig.HERMES_LITERTLM_COORDINATE)
        hermesReport.put("app_version", BuildConfig.VERSION_NAME)
        File(evidence, "hermes-runtime-report.json").writeText(hermesReport.toString(2))
    }
}
