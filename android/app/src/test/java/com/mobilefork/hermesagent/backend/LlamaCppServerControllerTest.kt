package com.mobilefork.hermesagent.backend

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class LlamaCppServerControllerTest {
    @Test
    fun cachedReadyStatusRetainsCompletionCharactersAndLatency() {
        assertEquals(
            "llama.cpp Experimental TurboQuant lane is serving locally; " +
                "GGUF metadata and a real chat completion canary are verified; " +
                "completion canary passed with nonblank message.content (17 characters) in 321 ms",
            LlamaCppServerController.cachedCompletionStatusMessage(
                laneDisplayLabel = "Experimental TurboQuant",
                completionDetail = "nonblank message.content (17 characters)",
                completionLatencyMs = 321L,
            ),
        )
    }

    @Test
    fun onlyAuthenticationRejectionsProveTheProtectedEndpointRequiresTheProcessKey() {
        assertTrue(LlamaCppServerController.isApiKeyRejectionStatus(401))
        assertTrue(LlamaCppServerController.isApiKeyRejectionStatus(403))
        assertFalse(LlamaCppServerController.isApiKeyRejectionStatus(200))
        assertFalse(LlamaCppServerController.isApiKeyRejectionStatus(400))
        assertFalse(LlamaCppServerController.isApiKeyRejectionStatus(500))
    }

    @Test
    fun androidSystemFallbackDoesNotLaunchLlamaThroughTermuxBash() {
        val state = JSONObject()
            .put("execution_mode", "android_system_shell")
            .put("shell_path", "/data/app/example/libhermes_android_bash.so")

        assertEquals("/system/bin/sh", LlamaCppServerController.shellPathForState(state))
    }

    @Test
    fun launchOptionsUseCompactContextForTinyQwenGguf() {
        val options = LlamaCppServerController.launchOptionsForModel(
            modelPath = "/models/Qwen3.5-0.8B-Q4_K_M.gguf",
            availableProcessors = 8,
        )

        assertTrue(options, "--ctx-size 1024" in options)
        assertTrue(options, "--threads 4" in options)
        assertTrue(options, "--batch-size 64" in options)
        assertTrue(options, "--ubatch-size 64" in options)
        assertTrue(options, "--no-warmup" in options)
        assertTrue(options, "--jinja" in options)
    }

    @Test
    fun launchOptionsUseToolCallContextForBonsai27BQ10() {
        val options = LlamaCppServerController.launchOptionsForModel(
            modelPath = "/models/Bonsai-27B-Q1_0.gguf",
            availableProcessors = 4,
        )

        assertTrue(options, "--ctx-size 2048" in options)
        assertTrue(options, "--threads 4" in options)
        assertTrue(options, "--jinja" in options)
    }

    @Test
    fun launchOptionsUseMobileDefaultForLargerGguf() {
        val options = LlamaCppServerController.launchOptionsForModel(
            modelPath = "/models/model-4b-q4_k_m.gguf",
            availableProcessors = 2,
        )

        assertTrue(options, "--ctx-size 2048" in options)
        assertTrue(options, "--threads 2" in options)
        assertTrue(options, "--parallel 1" in options)
    }

    @Test
    fun defaultLaunchBreadcrumbPreservesStableLaneDefaultsWithoutRawArgv() {
        val config = LlamaCppLaunchConfig()

        val breadcrumb = LlamaCppServerController.diagnosticsBreadcrumbFor(config)

        assertEquals("stable", breadcrumb.lane)
        assertEquals("default", breadcrumb.cacheTypeK)
        assertEquals("default", breadcrumb.cacheTypeV)
        assertEquals("default", breadcrumb.flashAttention)
        assertEquals(config.fingerprint(), breadcrumb.launchFingerprintSha256)
        assertEquals(0, breadcrumb.additionalArgvCount)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            breadcrumb.additionalArgvSha256,
        )
    }

    @Test
    fun nativeFailureRedactsExpertArgvFromReturnedStatusAndDiagnostics() {
        val secretToken = "publisher-secret-token-9347"
        val config = LlamaCppLaunchConfig(
            additionalArguments = listOf("--logit-bias", secretToken),
        )
        val nativeFailure =
            "llama.cpp parser rejected --logit-bias value $secretToken while loading the model"

        val status = LlamaCppServerController.failureStatusAfterStop(
            modelPath = "/models/model.gguf",
            artifactSummary = "GGUF fixture",
            detail = nativeFailure,
            launchConfig = config,
        )
        val diagnosticsDetail = LlamaCppServerController.diagnosticsSafeDetail(
            nativeFailure,
            config,
        )

        assertFalse(status.statusMessage, status.statusMessage.contains(secretToken))
        assertFalse(status.statusMessage, status.statusMessage.contains("--logit-bias"))
        assertTrue(status.statusMessage, status.statusMessage.contains("<redacted-additional-argv>"))
        assertTrue(status.statusMessage, status.statusMessage.contains("while loading the model"))
        assertFalse(diagnosticsDetail, diagnosticsDetail.contains(secretToken))
        assertFalse(diagnosticsDetail, diagnosticsDetail.contains("--logit-bias"))
        assertTrue(diagnosticsDetail, diagnosticsDetail.contains("<redacted-additional-argv>"))
        assertTrue(diagnosticsDetail, diagnosticsDetail.contains("while loading the model"))
    }

    @Test
    fun startupAndReleaseMatrixCompletionPayloadsDisableThinking() {
        val payloads = listOf(
            Triple(
                LlamaCppServerController.startupCompletionCanaryPayload("startup-model"),
                "startup-model",
                "Reply with exactly this word and nothing else: OK",
            ),
            Triple(
                LlamaCppServerController.releaseMatrixCompletionPayload("matrix-model"),
                "matrix-model",
                "Reply with one short word: hello",
            ),
        )

        payloads.forEach { (payload, expectedModel, expectedPrompt) ->
            assertEquals(expectedModel, payload.getString("model"))
            assertFalse(
                payload.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"),
            )
            assertFalse(payload.getBoolean("stream"))
            assertEquals(0, payload.getInt("temperature"))
            assertEquals(64, payload.getInt("max_tokens"))

            val messages = payload.getJSONArray("messages")
            assertEquals(1, messages.length())
            val message = messages.getJSONObject(0)
            assertEquals("user", message.getString("role"))
            assertEquals(expectedPrompt, message.getString("content"))
        }
    }

    @Test
    fun turboQuantCompletionPayloadSuppressesReasoningContentButStablePayloadIsUnchanged() {
        val stable = LlamaCppServerController.startupCompletionCanaryPayload("stable-model")
        val turboQuant = LlamaCppServerController.startupCompletionCanaryPayload(
            "nanbeige-model",
            LlamaCppRuntimeLane.TURBOQUANT,
        )

        assertFalse(stable.has("reasoning_format"))
        assertEquals("none", turboQuant.getString("reasoning_format"))
        assertFalse(
            turboQuant.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"),
        )
    }

    @Test
    fun nonTerminatingOwnedProcessFailsClosedInsteadOfDroppingItsHandle() {
        val handle = object : LlamaProcessStopHandle {
            var destroyCalls = 0
            var forceCalls = 0

            override val supportsForceDestroy: Boolean = true

            override fun exitValue(): Int = throw IllegalThreadStateException("still alive")

            override fun destroy() {
                destroyCalls += 1
            }

            override fun forceDestroy() {
                forceCalls += 1
            }
        }

        val failure = LlamaCppServerController.stopOwnedProcess(
            current = handle,
            gracefulTimeoutMs = 0L,
            forcedTimeoutMs = 0L,
        )

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("remained alive"))
        assertEquals(1, handle.destroyCalls)
        assertEquals(1, handle.forceCalls)
        assertTrue(runCatching { handle.exitValue() }.exceptionOrNull() is IllegalThreadStateException)
    }

    @Test
    fun publicationLivenessDistinguishesRunningAndExitedOwnedProcesses() {
        val running = object : LlamaProcessStopHandle {
            override val supportsForceDestroy: Boolean = true
            override fun exitValue(): Int = throw IllegalThreadStateException("still alive")
            override fun destroy() = Unit
            override fun forceDestroy() = Unit
        }
        val exited = object : LlamaProcessStopHandle {
            override val supportsForceDestroy: Boolean = true
            override fun exitValue(): Int = 1
            override fun destroy() = Unit
            override fun forceDestroy() = Unit
        }

        assertTrue(LlamaCppServerController.isOwnedProcessAlive(running))
        assertFalse(LlamaCppServerController.isOwnedProcessAlive(exited))
    }

    @Test
    fun launchRejectsAPortAlreadyOwnedByAnotherListener() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val occupied = ServerSocket(0, 1, loopback)
        val port = occupied.localPort
        try {
            assertFalse(LlamaCppServerController.isLoopbackPortAvailable(port))
        } finally {
            occupied.close()
        }
        assertTrue(LlamaCppServerController.isLoopbackPortAvailable(port))
    }

    @Test
    fun api24NonTerminatingLlamaProcessUsesGracefulStopOnlyAndFailsClosed() {
        val handle = object : LlamaProcessStopHandle {
            var destroyCalls = 0
            var forceCalls = 0

            override val supportsForceDestroy: Boolean = false

            override fun exitValue(): Int = throw IllegalThreadStateException("still alive")

            override fun destroy() {
                destroyCalls += 1
            }

            override fun forceDestroy() {
                forceCalls += 1
            }
        }

        val failure = LlamaCppServerController.stopOwnedProcess(
            current = handle,
            gracefulTimeoutMs = 0L,
            forcedTimeoutMs = 0L,
        )

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("requires Android 8.0 (API 26)"))
        assertEquals(1, handle.destroyCalls)
        assertEquals(0, handle.forceCalls)
    }

    @Test
    fun api24GracefulLlamaStopUsesExitValuePollingAndReturnsCleanly() {
        val handle = object : LlamaProcessStopHandle {
            var alive = true
            var destroyCalls = 0
            var forceCalls = 0

            override val supportsForceDestroy: Boolean = false

            override fun exitValue(): Int {
                if (alive) throw IllegalThreadStateException("still alive")
                return 0
            }

            override fun destroy() {
                destroyCalls += 1
                alive = false
            }

            override fun forceDestroy() {
                forceCalls += 1
            }
        }

        val failure = LlamaCppServerController.stopOwnedProcess(
            current = handle,
            gracefulTimeoutMs = 0L,
            forcedTimeoutMs = 0L,
        )

        assertEquals(null, failure)
        assertEquals(1, handle.destroyCalls)
        assertEquals(0, handle.forceCalls)
        assertEquals(0, handle.exitValue())
    }
}
