package com.mobilefork.hermesagent

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.play.PlayActivity
import com.mobilefork.hermesagent.privacy.AiReportReceiptStore
import com.mobilefork.hermesagent.privacy.AiContentReportClient
import com.mobilefork.hermesagent.privacy.RemoteProcessingConsentStore
import com.mobilefork.hermesagent.privacy.RemoteProcessingTarget
import com.mobilefork.hermesagent.ui.chat.ChatViewModel
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.json.JSONArray
import com.mobilefork.hermesagent.ui.i18n.AppLanguage
import com.mobilefork.hermesagent.ui.i18n.hermesStringsFor
import com.mobilefork.hermesagent.ui.i18n.playEditionSummary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs against the real Play launcher and request owner, never pre-seeds consent. */
@RunWith(AndroidJUnit4::class)
class PlayPrivacyInstrumentedTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun actualSettingsSwitchAllSixLanguagesAndShowPrivacyControls() {
        assertTrue(BuildConfig.HERMES_PLAY_EDITION)
        AppSettingsStore(app).save(AppSettings(languageTag = "en"))
        val visited = JSONArray()
        val screenshots = JSONArray()
        ActivityScenario.launch(PlayActivity::class.java).use {
            ui.onNodeWithTag("PlaySettingsTab").performClick()
            for (language in AppLanguage.entries) {
                ui.onNodeWithTag("HermesSettingsContentList").performScrollToIndex(0)
                ui.onNodeWithTag("HermesSettingsPage_Overview").performClick()
                ui.onNodeWithTag("HermesSettingsContentList").performScrollToNode(hasTestTag("SettingsLanguagePicker"))
                ui.onNodeWithTag("SettingsLanguage-${language.tag}").performScrollTo().performClick()
                ui.onNodeWithTag("PlayEditionIdentity").assertTextEquals(hermesStringsFor(language).playEditionSummary())
                assertEquals(language.tag, AppSettingsStore(app).load().languageTag)
                ui.onNodeWithTag("HermesSettingsContentList").performScrollToIndex(0)
                ui.onNodeWithTag("HermesSettingsPage_Privacy").performClick()
                ui.onNodeWithTag("OpenPrivacyPolicy").performScrollTo().assertIsDisplayed()
                ui.onNodeWithTag("RevokeRemoteProcessingConsent").performScrollTo().assertIsDisplayed()
                ui.onNodeWithTag("DeleteLocalAppData").performScrollTo().performClick()
                ui.onNodeWithTag("DeleteLocalDataConfirmation").assertIsDisplayed()
                PlayReleaseEvidence.capture(app, "privacy-${language.tag.lowercase()}")?.let(screenshots::put)
                ui.onNodeWithTag("CancelDeleteLocalAppData").performClick()
                visited.put(language.tag)
            }
            PlayReleaseEvidence.emit(app, "six-language-privacy", JSONObject()
                .put("languages", visited).put("screenshots", screenshots).put("persisted_language_matches_ui", true)
                .put("local_deletion_cancelled", true).put("privacy_controls_visible", true))
        }
    }

    @Test fun disclosureDeclineAcceptRevokeAndBackgroundStopFollowRealChatTransport() {
        assertTrue("Must install the separate Play APK", BuildConfig.HERMES_PLAY_EDITION)
        val requests = CopyOnWriteArrayList<JSONObject>()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val body = mutableMapOf<String, String>()
                session.parseBody(body)
                val request = JSONObject(body.getValue("postData"))
                requests.add(request)
                val messages = request.getJSONArray("messages")
                if (messages.getJSONObject(messages.length() - 1).optString("content").contains("hold-response")) {
                    held.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "Held test response was not released" }
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json",
                    """{"choices":[{"message":{"role":"assistant","content":"Synthetic Play transport reply"}}]}""")
            }
        }
        server.start(10_000, false)
        val settings = AppSettings(provider = "custom", baseUrl = "http://127.0.0.1:${server.listeningPort}/v1",
            model = "test-model", onDeviceBackend = "none", languageTag = "en")
        AppSettingsStore(app).save(settings)
        val consents = RemoteProcessingConsentStore(app)
        consents.revokeAll()
        val target = requireNotNull(RemoteProcessingTarget.fromSettings(settings))
        try {
            ActivityScenario.launch(PlayActivity::class.java).use { scenario ->
                lateinit var chat: ChatViewModel
                scenario.onActivity { chat = ViewModelProvider(it)[ChatViewModel::class.java]; chat.startNewConversation() }
                ui.onNodeWithTag("PlayEditionIdentity").assertIsDisplayed()
                val microphoneBefore = androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO)
                ui.onNodeWithTag("HermesChatMicButton").performClick()
                ui.onNodeWithTag("VoiceInputDisclosure").assertIsDisplayed()
                ui.onNodeWithTag("VoiceInputConsentDecline").performClick()
                assertEquals(microphoneBefore, androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO))
                ui.onNodeWithTag("HermesChatInput").performTextReplacement("Hello synthetic endpoint")
                ui.onNodeWithTag("HermesChatSendButton").performClick()
                ui.onNodeWithTag("RemoteProcessingEndpoint").assertTextEquals(target.endpoint)
                ui.onNodeWithTag("RemoteProcessingDecline").performClick()
                ui.runOnIdle {
                    assertFalse(consents.allowed(target))
                    assertEquals(0, requests.size)
                    assertTrue(chat.uiState.value.messages.isEmpty())
                    assertEquals("Hello synthetic endpoint", chat.uiState.value.input)
                }
                ui.onNodeWithTag("HermesChatSendButton").performClick()
                ui.onNodeWithTag("RemoteProcessingAccept").performClick()
                ui.waitUntil(20_000) { chat.latestAssistantReply().isNotBlank() && !chat.uiState.value.isSending }
                assertTrue(consents.allowed(target))
                assertEquals(1, requests.size)
                assertFalse(requests.single().has("tools"))
                assertEquals("Synthetic Play transport reply", chat.latestAssistantReply())
                ui.onNodeWithTag("PlaySettingsTab").performClick()
                ui.onNodeWithTag("HermesSettingsPage_Privacy").performClick()
                ui.onNodeWithTag("RevokeRemoteProcessingConsent").performScrollTo().performClick()
                ui.waitUntil(10_000) { !consents.allowed(target) }
                ui.onNodeWithTag("PlayChatTab").performClick()
                ui.onNodeWithTag("HermesChatInput").performTextReplacement("hold-response")
                ui.onNodeWithTag("HermesChatSendButton").performClick()
                ui.onNodeWithTag("RemoteProcessingAccept").performClick()
                assertTrue(held.await(20, TimeUnit.SECONDS))
                scenario.moveToState(Lifecycle.State.CREATED)
                assertFalse("Background must cancel the request owner", chat.uiState.value.isSending)
                release.countDown()
                scenario.moveToState(Lifecycle.State.RESUMED)
                ui.waitForIdle()
                assertFalse(chat.uiState.value.isSending)
                assertEquals(1, chat.uiState.value.messages.count { it.role == "assistant" && it.content == "Synthetic Play transport reply" })
                PlayReleaseEvidence.emit(app, "privacy-consent-lifecycle", JSONObject()
                    .put("decline_no_http", true).put("accept_real_http", true).put("request_has_no_tools", true)
                    .put("revoke_reprompts", true).put("background_cancelled", true).put("voice_decline_no_permission_change", true))
            }
        } finally { release.countDown(); server.stop() }
    }

    @Test fun reportingPreviewCancelThenVoluntaryLiveSubmissionAndDeletion() {
        assertTrue(BuildConfig.HERMES_PLAY_EDITION)
        AppSettingsStore(app).save(AppSettings(languageTag = "en"))
        val receipts = AiReportReceiptStore(app)
        val before = receipts.load().map { it.id }.toSet()
        try {
            ActivityScenario.launch(PlayActivity::class.java).use { scenario ->
                // Use the production dialog in the production activity; no alternate client or endpoint.
                scenario.onActivity { activity ->
                    activity.setContentForReportTest()
                }
                ui.onNodeWithTag("AiReportPreview").assertTextContains("Synthetic Hermes Play UI verification", substring = true)
                assertEquals(before, receipts.load().map { it.id }.toSet())
                ui.onNodeWithTag("AiReportCancel").performClick()
                ui.onNodeWithTag("AiContentReportDialog").assertDoesNotExist()
                assertEquals(before, receipts.load().map { it.id }.toSet())
                scenario.onActivity { it.setContentForReportTest() }
                ui.onNodeWithTag("AiReportSubmit").performClick()
                ui.waitUntil(40_000) { receipts.load().any { it.id !in before && it.submitted } }
                val created = receipts.load().single { it.id !in before }
                ui.onNodeWithTag("AiReportCancel").performClick()
                scenario.recreate()
                ui.onNodeWithTag("PlaySettingsTab").performClick()
                ui.onNodeWithTag("HermesSettingsPage_Privacy").performClick()
                ui.onNodeWithTag("DeleteReport-${created.id}").performScrollTo().performClick()
                ui.onNodeWithTag("ConfirmDeleteReport").performClick()
                ui.waitUntil(40_000) { receipts.load().none { it.id == created.id } }
                assertEquals(before, receipts.load().map { it.id }.toSet())
                PlayReleaseEvidence.emit(app, "report-submit-delete", JSONObject()
                    .put("preview_before_send", true).put("cancel_no_receipt", true).put("live_submission_confirmed", true)
                    .put("in_app_deletion_confirmed", true).put("endpoint", AiContentReportClient.ENDPOINT)
                    .put("synthetic_only", true))
            }
        } finally {
            receipts.load().filter { it.id !in before }.forEach { AiContentReportClient(app).delete(it) }
        }
    }
}

private fun PlayActivity.setContentForReportTest() {
    var open by androidx.compose.runtime.mutableStateOf(true)
    setContent {
        com.mobilefork.hermesagent.ui.theme.HermesTheme {
            if (open) com.mobilefork.hermesagent.ui.privacy.AiContentReportDialog(
                "Synthetic Hermes Play UI verification. No user content. Delete after test.",
                onDismiss = { open = false },
            )
        }
    }
}
