package com.nousresearch.hermesagent

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intending
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nousresearch.hermesagent.data.ProviderPresets
import com.nousresearch.hermesagent.device.HermesExternalBrowserLauncher
import com.nousresearch.hermesagent.device.HermesProviderSetupWebActivity
import fi.iki.elonen.NanoHTTPD
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ProviderSetupWebActivityInstrumentedTest {
    @After
    fun tearDown() {
        shellOutput("input keyevent KEYCODE_HOME")
    }

    @Test
    fun providerSetupOpenUsesExternalBrowserForQwenCloudWhenAvailable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("https://docs.qwencloud.com/api-reference/preparation/api-key")
        val browserIntent = HermesExternalBrowserLauncher.createBrowserIntent(context, uri)
        val resolved = browserIntent.resolveActivity(context.packageManager)
        assumeTrue("No browser is installed on this test device", resolved != null)
        assumeTrue(
            "Provider setup should not resolve back to Hermes",
            resolved?.packageName != context.packageName,
        )

        val qwenDocsOpened = AtomicBoolean(false)
        val qwenDocsIntent = object : TypeSafeMatcher<Intent>() {
            override fun describeTo(description: Description) {
                description.appendText("Qwen Cloud setup browser intent")
            }

            override fun matchesSafely(intent: Intent): Boolean {
                val targetIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                val targetUri = intent.data ?: targetIntent?.data ?: return false
                val matches = intent.action in setOf(Intent.ACTION_VIEW, Intent.ACTION_CHOOSER) &&
                    targetUri == uri
                if (matches) {
                    qwenDocsOpened.set(true)
                }
                return matches
            }
        }

        Intents.init()
        try {
            intending(qwenDocsIntent).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))

            val result = HermesProviderSetupWebActivity.open(context, uri, "Open Qwen setup")

            assertTrue(result.toString(), result.success)
            assertTrue("Expected provider setup to launch the Qwen docs browser intent", qwenDocsOpened.get())
        } finally {
            Intents.release()
        }
    }

    @Test
    fun providerSetupViewerStartsForApiKeyAndTokenProviders() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val localServer = if (isX86Emulator()) {
            LightweightProviderSetupServer(freePort()).also { it.start(30_000, false) }
        } else {
            null
        }
        try {
            val providerIds = providerSetupViewerProviderIds()
            providerIds.forEach { providerId ->
                requireNotNull(ProviderPresets.setupTarget(providerId, 0)) {
                    "Expected setup target for $providerId"
                }
            }
            val providerId = providerIds.first()
            val target = requireNotNull(ProviderPresets.setupTarget(providerId, 0)) {
                "Expected setup target for $providerId"
            }
            val setupUrl = localServer?.urlFor(providerId) ?: target.url
            val intent = HermesProviderSetupWebActivity.createIntent(
                context = context,
                uri = Uri.parse(setupUrl),
                title = "Open $providerId setup",
            )

            try {
                val hierarchy = launchProviderSetupAndReadHierarchy(
                    context = context,
                    intent = intent,
                    expectedTitle = "Open $providerId setup",
                )
                val viewerToolbarVisible = hierarchy.hasUiTexts("Back", "Browser")
                val fallbackVisible = hierarchy.hasUiTexts("Open in browser", "Copy URL", "Close")
                assertTrue(
                    "Expected $providerId setup UI to show WebView toolbar or fallback controls. Hierarchy: $hierarchy",
                    viewerToolbarVisible || fallbackVisible,
                )
            } finally {
                closeProviderSetupActivity()
            }
        } finally {
            localServer?.stop()
        }
    }

    @Test
    fun providerSetupViewerShowsCopyableFallbackForInvalidSetupUrl() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = HermesProviderSetupWebActivity.createIntent(
            context = context,
            uri = Uri.parse("https:///missing-host"),
            title = "Open broken provider setup",
        )

        try {
            val hierarchy = launchProviderSetupAndReadHierarchy(
                context = context,
                intent = intent,
                expectedTitle = "Open broken provider setup",
            )
            assertTrue("Missing browser fallback button: $hierarchy", hierarchy.hasUiText("Open in browser"))
            assertTrue("Missing copy fallback button: $hierarchy", hierarchy.hasUiText("Copy URL"))
            assertTrue("Missing close fallback button: $hierarchy", hierarchy.hasUiText("Close"))
        } finally {
            closeProviderSetupActivity()
        }
    }

    /*
     * UI automation keeps this validation close to the user-visible provider setup flow and
     * lets Android 17 compatibility dialogs be dismissed before asserting the app controls.
     */
    private fun shellOutput(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return descriptor.use { fd ->
            FileInputStream(fd.fileDescriptor).bufferedReader().use { it.readText() }
        }
    }

    private fun launchProviderSetupAndReadHierarchy(
        context: Context,
        intent: Intent,
        expectedTitle: String,
    ): String {
        context.startActivity(intent)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val ready: (String) -> Boolean = {
            it.hasUiText(expectedTitle) ||
                it.hasUiTexts("Back", "Browser", "Copy", "Close") ||
                it.hasUiTexts("Open in browser", "Copy URL", "Close")
        }
        val hierarchy = waitForWindowHierarchy(ready = ready)
        if (ready(hierarchy)) {
            return hierarchy
        }

        closeProviderSetupActivity()
        return readHierarchyWithActivityScenario(intent)
    }

    private fun waitForWindowHierarchy(
        timeoutMs: Long = 15_000L,
        ready: (String) -> Boolean,
    ): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var latest = ""
        while (SystemClock.uptimeMillis() < deadline) {
            latest = dumpWindowHierarchy()
            if (latest.hasUiText("Android App Compatibility")) {
                clickUiText("OK")
                Thread.sleep(500L)
                continue
            }
            if (ready(latest)) {
                return latest
            }
            Thread.sleep(250L)
        }
        return latest
    }

    private fun dumpWindowHierarchy(): String {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: return ""
        val values = mutableListOf<String>()
        root.collectVisibleText(values)
        return values.joinToString(separator = "\n")
    }

    private fun readHierarchyWithActivityScenario(intent: Intent): String {
        var hierarchy = ""
        ActivityScenario.launch<HermesProviderSetupWebActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val values = mutableListOf<String>()
                root.collectVisibleText(values)
                hierarchy = values.joinToString(separator = "\n")
                root.findFirstWebView()?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                }
                activity.finish()
            }
        }
        return hierarchy
    }

    private fun closeProviderSetupActivity() {
        shellOutput("input keyevent KEYCODE_BACK")
        waitForWindowHierarchy(timeoutMs = 3_000L) {
            !it.hasUiText("Back") &&
                !it.hasUiText("Browser") &&
                !it.hasUiText("Open in browser")
        }
    }

    private fun String.hasUiTexts(vararg values: String): Boolean = values.all { hasUiText(it) }

    private fun String.hasUiText(value: String): Boolean {
        return lineSequence().any { line ->
            line.equals("text=$value", ignoreCase = true) ||
                line.equals("contentDescription=$value", ignoreCase = true)
        }
    }

    private fun clickUiText(value: String): Boolean {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            ?: return false
        return root.findNodeWithText(value)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun AccessibilityNodeInfo.collectVisibleText(values: MutableList<String>) {
        text?.toString()?.takeIf { it.isNotBlank() }?.let { values += "text=$it" }
        contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            values += "contentDescription=$it"
        }
        for (index in 0 until childCount) {
            getChild(index)?.collectVisibleText(values)
        }
    }

    private fun AccessibilityNodeInfo.findNodeWithText(value: String): AccessibilityNodeInfo? {
        val nodeText = text?.toString().orEmpty()
        val nodeDescription = contentDescription?.toString().orEmpty()
        if (nodeText.equals(value, ignoreCase = true) || nodeDescription.equals(value, ignoreCase = true)) {
            return this
        }
        for (index in 0 until childCount) {
            val match = getChild(index)?.findNodeWithText(value)
            if (match != null) {
                return match
            }
        }
        return null
    }

    private fun View.collectVisibleText(values: MutableList<String>) {
        if (this is TextView) {
            text?.toString()?.takeIf { it.isNotBlank() }?.let { values += "text=$it" }
        }
        contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            values += "contentDescription=$it"
        }
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).collectVisibleText(values)
            }
        }
    }

    private fun View.findFirstWebView(): WebView? {
        if (this is WebView) {
            return this
        }
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                val match = getChildAt(index).findFirstWebView()
                if (match != null) {
                    return match
                }
            }
        }
        return null
    }

    private fun providerSetupViewerProviderIds(): List<String> {
        return listOf(
            "openrouter",
            "openai",
            "chatgpt-web",
            "anthropic",
            "gemini",
            "alibaba",
            "alibaba-coding-plan",
            "qwen-oauth",
            "zai",
            "zai-coding-plan",
        )
    }

    private fun isX86Emulator(): Boolean {
        return Build.SUPPORTED_ABIS.any { it.contains("x86", ignoreCase = true) }
    }

    private fun freePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private class LightweightProviderSetupServer(port: Int) : NanoHTTPD("127.0.0.1", port) {
        override fun serve(session: IHTTPSession): Response {
            val providerId = session.uri.substringAfterLast('/').ifBlank { "provider" }
            val html = "<!doctype html><html><body><h1>Hermes setup $providerId</h1></body></html>"
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }

        fun urlFor(providerId: String): String = "http://127.0.0.1:$listeningPort/setup/$providerId"
    }
}
