package com.mobilefork.hermesagent

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.device.LocalModelRuntimeDiagnostics
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real launcher -> Settings -> Android DocumentsUI -> user-chosen folder -> verified bytes. */
@RunWith(AndroidJUnit4::class)
class AgentDiagnosticsExportInstrumentedTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private lateinit var original: AppSettings
    private val backups = linkedMapOf<File, ByteArray?>()
    private val runId = UUID.randomUUID().toString().take(12)
    private val evidence by lazy { File(app.getExternalFilesDir(null), "diagnostic-export-validation/$runId").apply { mkdirs() } }

    @Before fun prepareOwnedOfflineFixture() {
        val store = AppSettingsStore(app)
        original = store.load()
        store.save(original.copy(languageTag = "en", offlineAirplaneMode = true, portalEnabled = false, onDeviceBackend = "none"))
        for (name in listOf("last-crash.json", "diagnostics-log.jsonl", "local-model-runtime.json")) {
            val file = File(app.filesDir, "hermes-diagnostics/$name")
            backups[file] = if (file.isFile) file.readBytes() else null
            file.delete()
        }
    }

    @After fun restoreOwnedFixture() {
        AppSettingsStore(app).save(original)
        backups.forEach { (file, bytes) ->
            if (bytes == null) file.delete() else { file.parentFile!!.mkdirs(); file.writeBytes(bytes) }
        }
        File(app.cacheDir, "export-fixture-$runId.gguf").delete()
        automation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE)
    }

    private fun openGeneral(scenario: ActivityScenario<out Activity>) {
        val navigation = listOf("PlaySettingsTab", "HermesChatDrawerButton", "HermesShellDrawerButton", "HermesRailSettings")
        ui.waitUntil(60_000) { navigation.any { ui.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() } }
        val visible = navigation.first { ui.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag(visible).performClick()
        if (visible !in listOf("HermesRailSettings", "PlaySettingsTab")) ui.onNodeWithTag("HermesNavSettings").performScrollTo().performClick()
        ui.onNodeWithTag("HermesSettingsPage_Overview").performClick()
        capture("general-before-export-assertion")
        ui.onNodeWithTag("ExportDiagnosticLog").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun settingsHasAlwaysAvailableLogExportBeforeAnyModelStarts() {
        ActivityScenario.launch<Activity>(requireNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))).use { scenario ->
            openGeneral(scenario)
            assertNull(LocalModelRuntimeDiagnostics.readSnapshot(app))
            capture("general-visible-export")
            ui.onNodeWithTag("ExportDiagnosticLog").performClick()
            awaitPicker()
            capture("native-save-dialog")
            cancelPicker()
            awaitStatus("Export cancelled. No log was saved.")
            ui.onNodeWithTag("ExportDiagnosticLog").assertIsEnabled()
            assertNull(LocalModelRuntimeDiagnostics.readSnapshot(app))
            scenario.recreate()
            ui.onNodeWithTag("ExportDiagnosticLog").assertIsDisplayed().assertIsEnabled()
            ui.onNodeWithTag("HermesSettingsPage_Models").performClick()
            ui.onNodeWithTag("HermesSettingsContentList").performScrollToNode(hasTestTag("ExportDiagnosticLog"))
            ui.onNodeWithTag("ExportDiagnosticLog").assertIsDisplayed().assertIsEnabled()
            capture("models-visible-export")
            File(evidence, "presence-cancel.json").writeText(JSONObject()
                .put("passed", true).put("model_started", false).put("native_picker_opened", true)
                .put("cancelled", true).put("activity_recreated", true).toString(2))
        }
    }

    @Test fun realPickerSavesInTwoChosenFoldersAndFailureCanBeRetried() {
        val model = File(app.cacheDir, "export-fixture-$runId.gguf").apply { writeBytes(ByteArray(128)) }
        val memory = LocalModelRuntimeDiagnostics.MemorySnapshot(
            8_000_000_000L, 2_672_004_000L, 500_000_000L, false, 201_326_592L, 536_870_912L, 10_000_000L)
        val preflight = LocalModelRuntimeDiagnostics.PreflightDecision(false, 512, 3_000_000_000L, "blocked", "Synthetic export regression fixture; not a Pixel 8 reproduction")
        val attemptId = LocalModelRuntimeDiagnostics.beginAttempt(app, "llama.cpp", model, "cpu", 2048, 512, memory, preflight,
            ramBypassRequested = true)
        LocalModelRuntimeDiagnostics.finishAttempt(app, attemptId, "failed", "memory_preflight",
            "export-marker-$runId user@example.test Authorization: Bearer hidden-export-token")
        ActivityScenario.launch<Activity>(requireNotNull(app.packageManager.getLaunchIntentForPackage(app.packageName))).use { scenario ->
            openGeneral(scenario)
            val saved = mutableListOf<JSONObject>()
            for (index in 1..2) {
                val folder = "Agent-log-export-$runId-$index"
                val fileName = "pixel8-log-café-$index.txt"
                ui.onNodeWithTag("ExportDiagnosticLog").performClick()
                awaitPicker()
                chooseDownloads()
                createFolder(folder)
                capture("chosen-folder-$index")
                if (index == 1) {
                    // Rotate while the external activity owns the foreground; the result must
                    // still reach the recreated app, without launching another save request.
                    assertTrue(automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_90))
                    SystemClock.sleep(700)
                    awaitPicker()
                    capture("picker-rotated")
                }
                // DocumentsUI can rebuild its filename field during rotation. Choose and
                // positively verify the final filename on the currently visible dialog.
                setFileName(fileName)
                capture("chosen-filename-$index")
                clickNode { it.text?.toString()?.equals("Save", ignoreCase = true) == true && it.isEnabled }
                awaitStatus("Diagnostic log saved to the selected location.")
                ui.onNodeWithTag("ExportDiagnosticLog").assertIsEnabled()
                val path = "/sdcard/Download/$folder/$fileName"
                val report = shell("cat $path")
                File(evidence, "actual-read-$index.txt").writeText(report)
                assertTrue("Actual saved file must contain complete report: $path", report.endsWith("End of Agent diagnostics\n"))
                assertTrue(report.contains("agent-diagnostic-report-v1"))
                assertTrue(report.contains("export-marker-$runId"))
                assertTrue(report.contains("memory_preflight"))
                assertTrue(report.contains("2672004000"))
                assertFalse(report.contains("user@example.test"))
                assertFalse(report.contains("hidden-export-token"))
                assertTrue(report.contains("[REDACTED"))
                assertEquals(attemptId, LocalModelRuntimeDiagnostics.readSnapshot(app)!!.getString("attempt_id"))
                File(evidence, "saved-$index.txt").writeText(report)
                saved.add(JSONObject().put("path", path).put("bytes", report.toByteArray().size)
                    .put("sha256", MessageDigest.getInstance("SHA-256").digest(report.toByteArray()).joinToString("") { "%02x".format(it) }))
                assertTrue(automation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0))
                SystemClock.sleep(500)
                capture("saved-$index")
            }
            // Actual Android picker success above is not mocked. Only the denied-provider
            // result is injected here to exercise visible failure/retry deterministically.
            Intents.init()
            try {
                Intents.intending(hasAction(Intent.ACTION_CREATE_DOCUMENT)).respondWith(
                    ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.parse("content://agent.invalid-provider/no-permission"))))
                ui.onNodeWithTag("ExportDiagnosticLog").performClick()
                awaitStatus("Could not save the log. A partial file may remain. Check free space or choose another location, then retry.")
                ui.onNodeWithTag("ExportDiagnosticLog").assertIsEnabled()
                capture("provider-error-retry-enabled")
            } finally { Intents.release() }
            ui.onNodeWithTag("ExportDiagnosticLog").performClick()
            awaitPicker()
            cancelPicker()
            awaitStatus("Export cancelled. No log was saved.")
            File(evidence, "saved-files.json").writeText(JSONObject()
                .put("passed", true).put("files", org.json.JSONArray(saved)).put("real_documents_ui", true)
                .put("rotation_while_picker_open", true).put("provider_failure_and_retry", true)
                .put("model_inference_performed", false).put("source", BuildConfig.HERMES_SOURCE_DIGEST).toString(2))
        }
    }

    private fun cancelPicker() {
        // Back first closes the keyboard or navigates to a parent folder. It is not
        // a cancellation result until DocumentsUI actually returns to this app.
        repeat(8) {
            if (automation.rootInActiveWindow?.packageName?.toString() == app.packageName) return
            assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            SystemClock.sleep(350)
        }
        waitFor("Return from Android save dialog") {
            automation.rootInActiveWindow?.packageName?.toString() == app.packageName
        }
    }

    private fun awaitStatus(expected: String) {
        ui.waitUntil(30_000) {
            // The external picker temporarily owns the foreground; no Compose root is
            // expected until ActivityResult has returned to the app.
            runCatching { ui.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
        ui.onNodeWithTag("DiagnosticLogExportStatus").assertTextEquals(expected)
    }

    private fun awaitPicker() {
        waitFor("Android DocumentsUI") { automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui", true) == true }
    }

    private fun atDownloadsRoot(): Boolean = findNode { it.text?.toString() == "Downloads" &&
        hasAncestor(it) { parent -> parent.viewIdResourceName?.endsWith("/toolbar") == true } } != null

    private fun isDownloadsBreadcrumb(node: AccessibilityNodeInfo): Boolean =
        node.text?.toString() == "Downloads" && node.isEnabled &&
            node.viewIdResourceName?.endsWith("/breadcrumb_text") == true &&
            hasAncestor(node) { parent -> parent.viewIdResourceName?.endsWith("/horizontal_breadcrumb") == true }

    private fun rootsAreVisible(): Boolean = findNode {
        it.viewIdResourceName?.endsWith("/roots_list") == true
    } != null

    private fun closeRootsDrawer() {
        var node = findNode { it.viewIdResourceName?.endsWith("/roots_list") == true }
        while (node != null) {
            // DrawerLayout exposes DISMISS on the actual open, unlocked drawer.
            // A visible roots list alone can also describe a persistent side pane.
            // Never use global Back here: it can cancel ACTION_CREATE_DOCUMENT.
            if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_DISMISS }) {
                // The selected root can finish closing the drawer after this node
                // snapshot was read. A stale DISMISS can then return false even
                // though the requested transition already completed. Verify the
                // real window state, including that the save request is still open.
                node.performAction(AccessibilityNodeInfo.ACTION_DISMISS)
                waitFor("Drawer dismissed while save request remains active") {
                    !rootsAreVisible() && automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui", true) == true
                }
                return
            }
            node = node.parent
        }
        // No dismissable drawer is exposed. Leave a permanent pane alone;
        // directory selection below still requires the actual Downloads header.
    }

    private fun chooseDownloads() {
        waitFor("Document location ready") {
            atDownloadsRoot() || findNode(::isDownloadsBreadcrumb) != null || rootsAreVisible() ||
                findNode { it.contentDescription?.toString()?.contains("Show roots", true) == true } != null
        }
        closeRootsDrawer()
        if (atDownloadsRoot()) return
        if (findNode(::isDownloadsBreadcrumb) == null) {
            clickNode { it.contentDescription?.toString()?.let { text -> text.contains("Show roots", true) || text.contains("Navigate up", true) } == true }
            val downloads = awaitNode { it.text?.toString() == "Downloads" && it.isEnabled &&
                hasAncestor(it) { parent -> parent.viewIdResourceName?.endsWith("/roots_list") == true } }
            // A selected Downloads row can be inert. Wait for its animation,
            // tap the actual row, then explicitly dismiss a still-open drawer.
            SystemClock.sleep(350)
            tapSystemControl(downloads)
            SystemClock.sleep(350)
            closeRootsDrawer()
        }
        waitFor("Downloads root or restored Downloads directory") {
            atDownloadsRoot() || findNode(::isDownloadsBreadcrumb) != null
        }
        if (!atDownloadsRoot()) clickNode(::isDownloadsBreadcrumb)
        waitFor("Downloads root selected") { atDownloadsRoot() }
        assertTrue("Choosing a directory must not cancel the save dialog",
            automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui", true) == true)
    }

    private fun hasAncestor(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        var parent = node.parent
        while (parent != null) {
            if (predicate(parent)) return true
            parent = parent.parent
        }
        return false
    }

    private fun tapSystemControl(node: AccessibilityNodeInfo) {
        assertTrue("Only the visible document picker may receive a native tap",
            node.packageName?.toString()?.contains("documentsui", true) == true && node.isVisibleToUser && node.isEnabled)
        val bounds = Rect().also(node::getBoundsInScreen)
        assertTrue("Picker control must have actual on-screen bounds", bounds.width() > 0 && bounds.height() > 0)
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(80)
        }
    }

    private fun createFolder(name: String) {
        // Android 17 exposes New folder directly on the toolbar, not in overflow.
        clickNode { it.contentDescription?.toString()?.equals("New folder", true) == true ||
            it.text?.toString()?.equals("New folder", true) == true }
        val edit = awaitNode { it.isEditable }
        assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
        }))
        clickNode { it.text?.toString()?.let { text -> text.equals("OK", true) || text.equals("Create", true) } == true }
        waitFor("Created folder $name") { findNode { it.text?.toString() == name } != null }
    }

    private fun setFileName(name: String) {
        val edit = awaitNode { it.isEditable && (it.viewIdResourceName?.endsWith("/title") == true || it.text?.toString()?.contains(".txt") == true) }
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
        }))
        waitFor("Chosen filename $name") { findNode { it.isEditable && it.text?.toString() == name } != null }
    }

    private fun clickNode(predicate: (AccessibilityNodeInfo) -> Boolean) {
        var node: AccessibilityNodeInfo? = awaitNode(predicate)
        while (node != null && !node.isClickable) node = node.parent
        assertNotNull("Clickable ancestor is required", node)
        assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(350)
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        waitFor("Picker control") { node = findNode(predicate); node != null }
        return node!!
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node) && node.isVisibleToUser) return node
            for (index in 0 until node.childCount) search(node.getChild(index))?.let { return it }
            return null
        }
        return search(automation.rootInActiveWindow)
    }

    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        capture("failure-${SystemClock.elapsedRealtime()}")
        throw AssertionError("Timed out waiting for $label")
    }

    private fun shell(command: String): String = automation.executeShellCommand(command).use { descriptor ->
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    private fun capture(name: String) {
        SystemClock.sleep(200)
        val bitmap = requireNotNull(automation.takeScreenshot())
        try {
            File(evidence, "$name.png").outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.fd.sync()
            }
        } finally { bitmap.recycle() }
        if (name.startsWith("failure-")) {
            val nodes = org.json.JSONArray()
            fun visit(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 30 || nodes.length() >= 250) return
                nodes.put(JSONObject().put("depth", depth).put("id", node.viewIdResourceName)
                    .put("text", node.text?.toString()).put("description", node.contentDescription?.toString())
                    .put("visible", node.isVisibleToUser).put("clickable", node.isClickable)
                    .put("package", node.packageName?.toString())
                    .put("actions", org.json.JSONArray(node.actionList.map { it.id })))
                for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
            }
            visit(automation.rootInActiveWindow, 0)
            File(evidence, "$name.json").outputStream().use { output ->
                output.write(nodes.toString(2).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
        }
    }

}
