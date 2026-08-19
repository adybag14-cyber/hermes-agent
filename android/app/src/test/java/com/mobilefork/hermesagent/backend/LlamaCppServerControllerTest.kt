package com.mobilefork.hermesagent.backend

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaCppServerControllerTest {
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
    }

    @Test
    fun launchOptionsUseCompactContextForBonsai27BQ10() {
        val options = LlamaCppServerController.launchOptionsForModel(
            modelPath = "/models/Bonsai-27B-Q1_0.gguf",
            availableProcessors = 4,
        )

        assertTrue(options, "--ctx-size 1024" in options)
        assertTrue(options, "--threads 4" in options)
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
