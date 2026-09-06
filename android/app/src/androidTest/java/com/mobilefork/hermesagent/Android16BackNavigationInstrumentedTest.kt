package com.mobilefork.hermesagent

import android.content.Context
import android.accessibilityservice.AccessibilityService
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilefork.hermesagent.device.HermesProviderSetupWebActivity
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Android16BackNavigationInstrumentedTest {
    @Test
    fun platformBackTraversesWebHistoryBeforeClosingOnAndroid16() {
        assertTrue("This gate requires Android 16 or newer", Build.VERSION.SDK_INT >= 36)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("Installed APK must target Android 16", context.applicationInfo.targetSdkVersion >= 36)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = newFixedLengthResponse(
                Response.Status.OK, "text/html", "<!doctype html><title>Hermes back test</title><p>${session.uri}</p>",
            )
        }
        server.start(10_000, false)
        try {
            val first = "http://127.0.0.1:${server.listeningPort}/first"
            val second = "http://127.0.0.1:${server.listeningPort}/second"
            val intent = HermesProviderSetupWebActivity.createIntent(context, Uri.parse(first), "Back test")
            ActivityScenario.launch<HermesProviderSetupWebActivity>(intent).use { scenario ->
                awaitCondition {
                    var ready = false
                    scenario.onActivity { ready = findWebView(it.window.decorView)?.let { web -> web.url == first && web.progress == 100 } == true }
                    ready
                }
                scenario.onActivity { requireNotNull(findWebView(it.window.decorView)).loadUrl(second) }
                awaitCondition {
                    var ready = false
                    scenario.onActivity { ready = findWebView(it.window.decorView)?.let { web -> web.url == second && web.progress == 100 && web.canGoBack() } == true }
                    ready
                }
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                awaitCondition {
                    var ready = false
                    scenario.onActivity { ready = !it.isFinishing && findWebView(it.window.decorView)?.url == first }
                    ready
                }
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                awaitCondition { scenario.state == Lifecycle.State.DESTROYED }
            }
        } finally {
            server.stop()
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("Timed out waiting for observed WebView/back-navigation state", condition())
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findWebView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
