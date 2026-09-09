package com.mobilefork.hermesagent.api

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class HermesSseClientTest {
    @Test
    fun namedToolEventsAreCorrelatedAndKeptOutOfAssistantText() {
        val frames = """
            event: hermes.tool.progress
            data: {"toolCallId":"call-1","tool":"terminal","status":"running",
            data: "arguments":"pwd"}

            event: hermes.tool.progress
            data: {"toolCallId":"call-1","tool":"terminal","status":"running","arguments":"duplicate"}

            event: hermes.tool.progress
            data: {"toolCallId":"orphan","tool":"terminal","status":"completed","result":"orphan"}

            event: hermes.tool.progress
            data: {"toolCallId":"call-1","tool":"terminal","status":"completed","result":"/app/workspace"}

            data: {"choices":[{"delta":{"content":"Done."}}]}

            data: [DONE]

        """.trimIndent() + "\n"
        val client = HermesSseClient("http://127.0.0.1:15436", httpClient = singleResponseClient(frames), includeToolActivity = true)
        val activity = mutableListOf<HermesToolActivity>()
        val text = mutableListOf<String>()
        var completed = false
        client.streamChatCompletion(sampleRequest(), { text += it }, { completed = true }, { throw AssertionError(it) },
            onToolActivity = { activity += it })
        assertTrue(completed)
        assertTrue(client.receivedToolActivity)
        assertEquals(listOf(HermesToolPhase.RUNNING, HermesToolPhase.COMPLETED), activity.map { it.phase })
        assertEquals(listOf("call-1", "call-1"), activity.map { it.callId })
        assertEquals(listOf("pwd", "/app/workspace"), activity.map { it.detail })
        assertEquals(listOf("Done."), text)
    }

    @Test
    fun toolOnlyDisconnectRetainsExecutionEvidenceAndOversizedFramesFailBoundedly() {
        val body = "event: hermes.tool.progress\ndata: {\"toolCallId\":\"call-1\",\"tool\":\"terminal\",\"status\":\"running\"}\n\n"
        val client = HermesSseClient("http://127.0.0.1:15436", httpClient = singleResponseClient(body), includeToolActivity = true)
        var error: String? = null
        client.streamChatCompletion(sampleRequest(), {}, { throw AssertionError("Unexpected completion") }, { error = it })
        assertTrue(client.receivedToolActivity)
        assertNotNull(error)
        val oversized = HermesSseClient("http://127.0.0.1:15436", httpClient = singleResponseClient("data: " + "x".repeat(300000)))
        var oversizeError: String? = null
        oversized.streamChatCompletion(sampleRequest(), {}, { throw AssertionError("Unexpected completion") }, { oversizeError = it })
        assertTrue(oversizeError.orEmpty().contains("size limit"))
        assertFalse(oversized.receivedToolActivity)
    }

    @Test
    fun responsesToolItemsAndStopKeepActivitySeparateFromReasoningAndLateText() {
        val frames = """
            data: {"type":"response.reasoning.delta","delta":"not public activity"}

            data: {"type":"response.output_item.added","item":{"type":"function_call","call_id":"call-1","name":"read_file","arguments":"{}"}}

            data: {"type":"response.output_item.done","item":{"type":"function_call_output","call_id":"call-1","output":[{"type":"input_text","text":"file contents"}]}}

            data: {"type":"response.output_text.delta","delta":"Late answer"}

            data: [DONE]

        """.trimIndent() + "\n"
        val client = HermesSseClient("http://127.0.0.1:15436", httpClient = singleResponseClient(frames), includeToolActivity = true)
        val activity = mutableListOf<HermesToolActivity>()
        val text = mutableListOf<String>()
        client.streamResponse(sampleRequest(), { text += it }, { throw AssertionError("Stopped stream completed") }, {},
            onToolActivity = { activity += it; if (it.phase == HermesToolPhase.COMPLETED) client.cancel() })
        assertEquals(listOf(HermesToolPhase.RUNNING, HermesToolPhase.COMPLETED), activity.map { it.phase })
        assertEquals("file contents\n", activity.last().detail)
        assertTrue(text.isEmpty())
    }

    @Test
    fun directProviderCannotPublishLocalToolExecutionEvents() {
        val frames = """
            event: hermes.tool.progress
            data: {"toolCallId":"untrusted","tool":"terminal","status":"running","arguments":"not executed"}

            data: {"type":"response.output_item.added","item":{"type":"function_call","call_id":"also-untrusted","name":"terminal","arguments":"{}"}}

            data: {"choices":[{"delta":{"content":"Provider text only."}}]}

            data: [DONE]

        """.trimIndent() + "\n"
        val client = HermesSseClient("https://provider.example.test", httpClient = singleResponseClient(frames))
        val text = StringBuilder()
        var complete = false
        client.streamChatCompletion(sampleRequest(), { text.append(it) }, { complete = true }, { throw AssertionError(it) },
            onToolActivity = { throw AssertionError("A direct provider forged local execution evidence") })
        assertTrue(complete)
        assertEquals("Provider text only.", text.toString())
        assertFalse(client.receivedToolActivity)
    }

    @Test
    fun cancelBeforeCallRegistrationIsStickyAndCannotAffectAnotherClient() {
        val registrationReached = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val networkStartsA = AtomicInteger(0)
        val errorA = AtomicReference<String?>(null)
        val clientA = HermesSseClient(
            baseUrl = "http://127.0.0.1:15436",
            httpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    networkStartsA.incrementAndGet()
                    chain.proceed(chain.request())
                }
                .build(),
            beforeCallRegistration = {
                registrationReached.countDown()
                assertTrue(releaseRegistration.await(5, TimeUnit.SECONDS))
            },
        )
        val workerA = thread(name = "sse-pre-registration-a") {
            clientA.streamChatCompletion(
                request = sampleRequest(),
                onDelta = {},
                onComplete = {},
                onError = { errorA.set(it) },
            )
        }

        assertTrue(registrationReached.await(5, TimeUnit.SECONDS))
        clientA.cancel()
        releaseRegistration.countDown()
        workerA.join(5_000L)

        assertFalse("Cancelled SSE request A remained alive", workerA.isAlive)
        assertEquals(0, networkStartsA.get())
        assertTrue(errorA.get().orEmpty().contains("cancelled", ignoreCase = true))

        val clientB = HermesSseClient(
            baseUrl = "http://127.0.0.1:15436",
            httpClient = singleResponseClient(
                "data: {\"choices\":[{\"delta\":{\"content\":\"B_OK\"}}]}\n\ndata: [DONE]\n\n",
            ),
        )
        val deltasB = mutableListOf<String>()
        var completedB = false
        var errorB: String? = null
        clientB.streamChatCompletion(
            request = sampleRequest(),
            onDelta = { deltasB += it },
            onComplete = { completedB = true },
            onError = { errorB = it },
        )

        assertEquals(listOf("B_OK"), deltasB)
        assertTrue(completedB)
        assertNull(errorB)
    }

    @Test
    fun streamChatCompletion_reports_transport_failures_via_onError() {
        val client = HermesSseClient(
            baseUrl = "http://127.0.0.1:15436",
            httpClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { throw IOException("socket boom") })
                .build(),
        )

        var error: String? = null
        client.streamChatCompletion(
            request = sampleRequest(),
            onDelta = {},
            onComplete = {},
            onError = { error = it },
        )

        assertEquals("socket boom", error)
    }

    @Test
    fun streamChatCompletion_reports_malformed_sse_payload_instead_of_throwing() {
        val client = HermesSseClient(
            baseUrl = "http://127.0.0.1:15436",
            httpClient = singleResponseClient("data: not-json\n\ndata: [DONE]\n\n"),
        )

        val deltas = mutableListOf<String>()
        var completed = false
        var error: String? = null

        client.streamChatCompletion(
            request = sampleRequest(),
            onDelta = { deltas += it },
            onComplete = { completed = true },
            onError = { error = it },
        )

        assertTrue(deltas.isEmpty())
        assertFalse(completed)
        assertNotNull(error)
        assertTrue(error!!.isNotBlank())
    }

    @Test
    fun streamChatCompletion_emits_delta_and_completion_for_valid_sse_payload() {
        val body = """
            data: {"choices":[{"delta":{"content":"hello"}}]}

            data: [DONE]

        """.trimIndent() + "\n"
        val client = HermesSseClient(
            baseUrl = "http://127.0.0.1:15436",
            httpClient = singleResponseClient(body),
        )

        val deltas = mutableListOf<String>()
        var completed = false
        var error: String? = null

        client.streamChatCompletion(
            request = sampleRequest(),
            onDelta = { deltas += it },
            onComplete = { completed = true },
            onError = { error = it },
        )

        assertEquals(listOf("hello"), deltas)
        assertTrue(completed)
        assertNull(error)
    }

    @Test
    fun streamChatCompletion_reports_doneOnlyStreamSoCallerCanFallback() {
        val client = HermesSseClient(
            baseUrl = "http://127.0.0.1:15436",
            httpClient = singleResponseClient("data: [DONE]\n\n"),
        )

        var completed = false
        var error: String? = null
        client.streamChatCompletion(
            request = sampleRequest(),
            onDelta = {},
            onComplete = { completed = true },
            onError = { error = it },
        )

        assertFalse(completed)
        assertNotNull(error)
        assertTrue(error!!.contains("without assistant text"))
    }

    @Test
    fun streamChatCompletion_reports_finishReasonWithoutTextSoCallerCanFallback() {
        val body = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
        val client = HermesSseClient(
            baseUrl = "http://127.0.0.1:15436",
            httpClient = singleResponseClient(body),
        )

        var completed = false
        var error: String? = null
        client.streamChatCompletion(
            request = sampleRequest(),
            onDelta = {},
            onComplete = { completed = true },
            onError = { error = it },
        )

        assertFalse(completed)
        assertNotNull(error)
        assertTrue(error!!.contains("without assistant text"))
    }

    @Test
    fun streamChatCompletion_reports_endpoint_status_steps() {
        val body = """
            data: {"choices":[{"delta":{"content":"hello"}}]}

            data: [DONE]

        """.trimIndent() + "\n"
        val client = HermesSseClient(
            baseUrl = "http://127.0.0.1:15436",
            httpClient = singleResponseClient(body),
        )

        val statuses = mutableListOf<String>()
        client.streamChatCompletion(
            request = sampleRequest(),
            onDelta = {},
            onComplete = {},
            onError = {},
            onStatus = { statuses += it },
        )

        assertTrue(statuses.any { it.contains("Opening endpoint stream") })
        assertTrue(statuses.any { it.contains("Endpoint responded HTTP 200") })
        assertTrue(statuses.any { it.contains("Endpoint stream is live") })
    }

    @Test
    fun streamChatCompletion_reports_http_error_body_snippet() {
        val client = HermesSseClient(
            baseUrl = "http://127.0.0.1:15436",
            httpClient = singleResponseClient(
                body = """{"error":{"message":"model not found"}}""",
                code = 404,
                message = "Not Found",
            ),
        )

        var error: String? = null
        client.streamChatCompletion(
            request = sampleRequest(),
            onDelta = {},
            onComplete = {},
            onError = { error = it },
        )

        assertEquals("""SSE request failed: 404 Not Found {"error":{"message":"model not found"}}""", error)
    }

    @Test
    fun streamChatCompletion_reports_endpoint_hint_when_sse_stream_closes_before_done() {
        val body = """
            data: {"choices":[{"delta":{"content":"partial"}}]}

        """.trimIndent() + "\n"
        val client = HermesSseClient(
            baseUrl = "http://127.0.0.1:15436",
            httpClient = singleResponseClient(body),
        )

        val deltas = mutableListOf<String>()
        var completed = false
        var error: String? = null

        client.streamChatCompletion(
            request = sampleRequest(),
            onDelta = { deltas += it },
            onComplete = { completed = true },
            onError = { error = it },
        )

        assertEquals(listOf("partial"), deltas)
        assertFalse(completed)
        assertNotNull(error)
        assertTrue(error!!.contains("closed before"))
        assertTrue(error!!.contains("[DONE]"))
        assertTrue(error!!.contains("Base URL"))
    }

    @Test
    fun streamChatCompletion_accepts_finishReasonAsCompletionWhenDoneFrameIsMissing() {
        val body = """
            data: {"choices":[{"delta":{"content":"hello"}}]}

            data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

        """.trimIndent() + "\n"
        val client = HermesSseClient(
            baseUrl = "http://127.0.0.1:15436",
            httpClient = singleResponseClient(body),
        )

        val deltas = mutableListOf<String>()
        var completed = false
        var error: String? = null

        client.streamChatCompletion(
            request = sampleRequest(),
            onDelta = { deltas += it },
            onComplete = { completed = true },
            onError = { error = it },
        )

        assertEquals(listOf("hello"), deltas)
        assertTrue(completed)
        assertNull(error)
    }

    @Test
    fun streamChatCompletion_accepts_dataFramesWithoutSpaceAndKeepAliveLines() {
        val body = """
            : keep-alive
            event: message
            data:{"choices":[{"delta":{"content":"hello"}}]}

            : keep-alive
            data:[DONE]

        """.trimIndent() + "\n"
        val client = HermesSseClient(
            baseUrl = "http://127.0.0.1:15436",
            httpClient = singleResponseClient(body),
        )

        val deltas = mutableListOf<String>()
        var completed = false
        var error: String? = null

        client.streamChatCompletion(
            request = sampleRequest(),
            onDelta = { deltas += it },
            onComplete = { completed = true },
            onError = { error = it },
        )

        assertEquals(listOf("hello"), deltas)
        assertTrue(completed)
        assertNull(error)
    }

    @Test
    fun streamChatCompletion_normalizesPastedFullEndpointUrl() {
        val body = """
            data: {"choices":[{"delta":{"content":"hello"}}]}

            data: [DONE]

        """.trimIndent() + "\n"
        val client = HermesSseClient(
            baseUrl = "http://127.0.0.1:15436/proxy/v1/chat/completions",
            httpClient = singleResponseClient(body),
        )

        val deltas = mutableListOf<String>()
        var error: String? = null
        client.streamChatCompletion(
            request = sampleRequest(),
            onDelta = { deltas += it },
            onComplete = {},
            onError = { error = it },
        )

        assertEquals(listOf("hello"), deltas)
        assertNull(error)
    }

    @Test
    fun streamResponse_emitsResponsesOutputTextDeltaAndCompletion() {
        val body = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"hello"}

            event: response.completed
            data: {"type":"response.completed","response":{"id":"resp_123"}}

        """.trimIndent() + "\n"
        val client = HermesSseClient(
            baseUrl = "https://api.openai.com/v1/responses",
            httpClient = singleResponseClient(body),
        )

        val deltas = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        var completed = false
        var error: String? = null
        client.streamResponse(
            request = sampleRequest().copy(model = "gpt-5"),
            onDelta = { deltas += it },
            onComplete = { completed = true },
            onError = { error = it },
            onStatus = { statuses += it },
        )

        assertEquals(listOf("hello"), deltas)
        assertTrue(completed)
        assertNull(error)
        assertTrue(statuses.any { it.contains("Responses stream") })
    }

    @Test
    fun streamChatCompletion_preservesParagraphSeparatorDelta() {
        val body = """
            data: {"choices":[{"delta":{"content":"First paragraph."}}]}

            data: {"choices":[{"delta":{"content":"\n\n"}}]}

            data: {"choices":[{"delta":{"content":"Second paragraph."}}]}

            data: [DONE]

        """.trimIndent() + "\n"
        val client = HermesSseClient(
            baseUrl = "http://127.0.0.1:15436",
            httpClient = singleResponseClient(body),
        )

        val deltas = mutableListOf<String>()
        client.streamChatCompletion(
            request = sampleRequest(),
            onDelta = { deltas += it },
            onComplete = {},
            onError = { throw AssertionError(it) },
        )

        assertEquals(
            listOf("First paragraph.", "\n\n", "Second paragraph."),
            deltas,
        )
        assertEquals("First paragraph.\n\nSecond paragraph.", deltas.joinToString(""))
    }

    @Test
    fun streamResponse_preservesParagraphSeparatorDelta() {
        val body = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"First paragraph."}

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"\n\n"}

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"Second paragraph."}

            event: response.completed
            data: {"type":"response.completed","response":{"id":"resp_paragraphs"}}

        """.trimIndent() + "\n"
        val client = HermesSseClient(
            baseUrl = "https://api.openai.com/v1/responses",
            httpClient = singleResponseClient(body),
        )

        val deltas = mutableListOf<String>()
        client.streamResponse(
            request = sampleRequest().copy(model = "gpt-5"),
            onDelta = { deltas += it },
            onComplete = {},
            onError = { throw AssertionError(it) },
        )

        assertEquals(
            listOf("First paragraph.", "\n\n", "Second paragraph."),
            deltas,
        )
        assertEquals("First paragraph.\n\nSecond paragraph.", deltas.joinToString(""))
    }

    private fun sampleRequest(): ChatCompletionRequest {
        return ChatCompletionRequest(
            model = "gemma-4-local",
            messages = listOf(ChatMessage(role = "user", content = "hello")),
            stream = true,
            sessionId = "session-123",
        )
    }

    private fun singleResponseClient(body: String, code: Int = 200, message: String = "OK"): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(message)
                    .body(body.toResponseBody("text/event-stream".toMediaType()))
                    .build()
            }
            .build()
    }
}
