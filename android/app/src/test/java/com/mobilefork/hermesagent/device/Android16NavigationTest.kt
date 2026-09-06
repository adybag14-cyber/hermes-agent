package com.mobilefork.hermesagent.device

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35, 36])
class Android16NavigationTest {
    @Test
    fun applicationManifestOptsIntoAndroid16Behavior() {
        assertTrue(RuntimeEnvironment.getApplication().applicationInfo.targetSdkVersion >= 36)
    }

    @Test
    fun backNavigatesWebHistoryThenReturnsToTheSystem() {
        val context = RuntimeEnvironment.getApplication()
        val first = "https://example.test/first"
        val second = "https://example.test/second"
        val intent = HermesProviderSetupWebActivity.createIntent(context, Uri.parse(first), "Setup")
        val controller = Robolectric.buildActivity(HermesProviderSetupWebActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val web = requireNotNull(findWebView(activity.window.decorView))
            val shadow = shadowOf(web)
            shadow.pushEntryToHistory(first)
            shadow.pushEntryToHistory(second)
            web.webViewClient.doUpdateVisitedHistory(web, second, false)
            assertTrue(activity.onBackPressedDispatcher.hasEnabledCallbacks())
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(first, web.url)
            assertFalse(activity.isFinishing)
            web.webViewClient.doUpdateVisitedHistory(web, first, false)
            assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(activity.isFinishing)
        } finally {
            controller.pause().stop().destroy()
        }
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
