package com.mobilefork.hermesagent.privacy

import com.mobilefork.hermesagent.data.AppSettingsStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AiContentReportClientTest {
    @Test
    fun explicitSubmissionUsesOnlyPreviewAndKeepsDeletionReceiptThroughUncertainDelivery() {
        MockWebServer().use { server ->
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val client = AiContentReportClient(context, OkHttpClient.Builder().addInterceptor { chain ->
                val original = chain.request()
                assertTrue(original.url.toString().startsWith(AiContentReportClient.ENDPOINT + "/v1/reports"))
                assertNull(original.header("Authorization"))
                chain.proceed(original.newBuilder().url(server.url(original.url.encodedPath)).build())
            }.build())
            val pending = client.prepare()
            assertEquals(0, server.requestCount)
            assertEquals(64, pending.deletionSecret.length)
            server.enqueue(MockResponse().setResponseCode(503))
            assertThrows(java.io.IOException::class.java) {
                client.submit(pending, AiReportReason.HATE, "Edited preview only", "User notes")
            }
            assertEquals(pending, AiReportReceiptStore(context).load().single())
            val submittedBody = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("Edited preview only", submittedBody.getString("message"))
            assertEquals("User notes", submittedBody.getString("notes"))
            assertEquals(setOf("schema", "request_id", "deletion_secret", "reason", "message", "notes", "app_version", "edition"),
                submittedBody.keys().asSequence().toSet())
            server.enqueue(MockResponse().setResponseCode(201).setBody(JSONObject()
                .put("received", true).put("report_id", pending.id).toString()))
            val confirmed = client.submit(pending, AiReportReason.HATE, "Edited preview only", "User notes")
            assertTrue(confirmed.submitted)
            assertEquals(submittedBody.toString(), JSONObject(server.takeRequest().body.readUtf8()).toString())
            val saved = context.getSharedPreferences("ai-report-receipts-v1", 0).all.toString()
            assertFalse(saved.contains("Edited preview"))
            assertFalse(saved.contains("User notes"))
            server.enqueue(MockResponse().setResponseCode(204))
            client.delete(confirmed)
            val deletion = server.takeRequest()
            assertEquals("DELETE", deletion.method)
            assertEquals(pending.deletionSecret, JSONObject(deletion.body.readUtf8()).getString("deletion_secret"))
            assertTrue(AiReportReceiptStore(context).load().isEmpty())
        }
    }

    @Test
    fun offlineAndMalformedReportsCannotLeaveDeviceAndDeletionFailureRetainsReceipt() {
        val context = RuntimeEnvironment.getApplication()
        val settings = AppSettingsStore(context)
        settings.save(settings.load().copy(offlineAirplaneMode = true))
        var requests = 0
        val client = AiContentReportClient(context, OkHttpClient.Builder().addInterceptor {
            requests += 1
            throw AssertionError("Offline submission reached transport")
        }.build())
        val receipt = client.prepare()
        assertThrows(java.io.IOException::class.java) { client.submit(receipt, AiReportReason.OTHER, "preview", "") }
        assertThrows(IllegalArgumentException::class.java) { client.submit(receipt, AiReportReason.OTHER, "x".repeat(4001), "") }
        assertThrows(java.io.IOException::class.java) { client.delete(receipt) }
        assertEquals(0, requests)
        assertEquals(receipt, AiReportReceiptStore(context).load().single())
    }
}
