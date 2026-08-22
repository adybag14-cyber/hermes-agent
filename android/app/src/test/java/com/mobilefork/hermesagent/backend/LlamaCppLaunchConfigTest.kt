package com.mobilefork.hermesagent.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaCppLaunchConfigTest {
    @Test
    fun stableDefaultsKeepTheExistingEffectiveArgumentVectorByteForByte() {
        val tokens = LlamaCppServerController.launchArgumentTokensForModel(
            modelPath = "/models/model-4b-q4_k_m.gguf",
            availableProcessors = 8,
            launchConfig = LlamaCppLaunchConfig(),
        )

        assertEquals(
            listOf(
                "--ctx-size",
                "2048",
                "--parallel",
                "1",
                "--threads",
                "4",
                "--batch-size",
                "64",
                "--ubatch-size",
                "64",
                "--no-warmup",
                "--jinja",
            ),
            tokens,
        )
        assertEquals(
            "--ctx-size 2048 --parallel 1 --threads 4 --batch-size 64 --ubatch-size 64 --no-warmup --jinja",
            LlamaCppServerController.launchOptionsForModel(
                modelPath = "/models/model-4b-q4_k_m.gguf",
                availableProcessors = 8,
            ),
        )
    }

    @Test
    fun stableLaneAcceptsEverySupportedNonTurboCacheType() {
        val cacheTypes = listOf(
            "default",
            "f32",
            "f16",
            "bf16",
            "q8_0",
            "q4_0",
            "q4_1",
            "iq4_nl",
            "q5_0",
            "q5_1",
        )

        cacheTypes.forEach { cacheType ->
            val validation = LlamaCppLaunchConfig(
                cacheTypeK = cacheType,
                cacheTypeV = cacheType,
                flashAttention = "on",
            ).validate()
            assertTrue("$cacheType: ${validation.error}", validation.valid)
        }
    }

    @Test
    fun stableLaneRejectsTurboCacheTypes() {
        listOf("turbo2", "turbo3", "turbo4").forEach { cacheType ->
            val validation = LlamaCppLaunchConfig(cacheTypeK = cacheType).validate()

            assertFalse(validation.valid)
            assertTrue(validation.error, validation.error.contains("not supported by the stable lane"))
        }
    }

    @Test
    fun turboLaneEmitsTurbo3KvAndExplicitFlashArguments() {
        val config = LlamaCppLaunchConfig(
            lane = LlamaCppRuntimeLane.TURBOQUANT,
            cacheTypeK = "turbo3",
            cacheTypeV = "turbo3",
            flashAttention = "on",
        )

        assertTrue(config.validate().error, config.validate().valid)
        assertEquals(
            listOf(
                "--cache-type-k",
                "turbo3",
                "--cache-type-v",
                "turbo3",
                "--flash-attn",
                "on",
            ),
            config.advancedArgumentTokens(),
        )
    }

    @Test
    fun turboCacheCannotPretendFlashAttentionIsOff() {
        val validation = LlamaCppLaunchConfig(
            lane = LlamaCppRuntimeLane.TURBOQUANT,
            cacheTypeK = "turbo3",
            flashAttention = "off",
        ).validate()

        assertFalse(validation.valid)
        assertTrue(validation.error, validation.error.contains("require Flash Attention"))
    }

    @Test
    fun quantizedVCacheRejectsExplicitFlashAttentionOff() {
        val validation = LlamaCppLaunchConfig(
            cacheTypeV = "q5_1",
            flashAttention = "off",
        ).validate()

        assertFalse(validation.valid)
        assertTrue(validation.error, validation.error.contains("Quantized V-cache"))
    }

    @Test
    fun allDocumentedFlashAttentionModesValidate() {
        listOf("default", "auto", "on", "off").forEach { value ->
            val validation = LlamaCppLaunchConfig(flashAttention = value).validate()
            assertTrue("$value: ${validation.error}", validation.valid)
        }
        assertFalse(LlamaCppLaunchConfig(flashAttention = "sometimes").validate().valid)
    }

    @Test
    fun additionalArgumentsRejectPositionalAndHermesOwnedFlags() {
        val rejected = listOf(
            "model.gguf",
            "--",
            "--model",
            "--host",
            "--port",
            "--api-key",
            "--ssl-key-file",
            "--hf-repo",
            "-hfr",
            "--public-path",
            "--cache-type-k",
            "-ctv",
            "--flash-attn",
            "--ctx-size",
            "-t",
            "--batch-size",
            "--warmup",
            "--no-jinja",
            "--embedding",
        )

        rejected.forEach { token ->
            val validation = LlamaCppLaunchConfig(additionalArguments = listOf(token)).validate()
            assertFalse("$token should be rejected", validation.valid)
        }
    }

    @Test
    fun exactPinnedE306PrivilegedAndArtifactAliasesRemainAppOwned() {
        val rejected = listOf(
            "--tools",
            "-ag",
            "--agent",
            "-no-ag",
            "--no-agent",
            "--ui-mcp-proxy",
            "--webui-mcp-proxy",
            "--no-ui-mcp-proxy",
            "--no-webui-mcp-proxy",
            "--mcp-servers-config",
            "--mcp-servers-json",
            "--reuse-port",
            "--cors-origins",
            "--cors-methods",
            "--cors-headers",
            "--cors-credentials",
            "--no-cors-credentials",
            "--api-prefix",
            "--ui",
            "--no-ui",
            "--ui-config",
            "--webui-config",
            "--ui-config-file",
            "--webui-config-file",
            "-dr",
            "--docker-repo",
            "-hf",
            "--models-dir",
            "--models-preset",
            "--models-max",
            "--models-autoload",
            "--no-models-autoload",
            "--spec-draft-model",
            "--spec-draft-hf",
            "-hfd",
            "--rerank",
        )

        rejected.forEach { token ->
            val validation = LlamaCppLaunchConfig(additionalArguments = listOf(token)).validate()
            assertFalse("Pinned e306 flag $token must remain app-owned", validation.valid)
            assertTrue(validation.error, validation.error.contains("Hermes-managed"))
        }
    }

    @Test
    fun pinnedE306MmprojDownloadAndEntireFimNamespaceRemainAppOwned() {
        val rejected = mapOf(
            "-mmu" to "model download and artifact selection",
            "--mmproj-url" to "model download and artifact selection",
            "--fim-model" to "FIM model download, endpoint, context, and RAM admission",
            "--fim-model-url" to "FIM model download, endpoint, context, and RAM admission",
            "--fim-port" to "FIM model download, endpoint, context, and RAM admission",
            "--fim-context" to "FIM model download, endpoint, context, and RAM admission",
            "--fim-ram-policy" to "FIM model download, endpoint, context, and RAM admission",
            "--FIM-FUTURE-ALIAS" to "FIM model download, endpoint, context, and RAM admission",
        )

        rejected.forEach { (token, expectedOwner) ->
            val validation = LlamaCppLaunchConfig(additionalArguments = listOf(token)).validate()
            assertFalse("Pinned e306 flag $token must remain app-owned", validation.valid)
            assertTrue(validation.error, validation.error.contains("Hermes-managed $expectedOwner"))
        }
    }

    @Test
    fun pinnedE306ServerPresetsCannotOverrideManagedLaunchPolicy() {
        val rejected = listOf(
            "--embd-gemma-default",
            "--gpt-oss-20b-default",
            "--gpt-oss-120b-default",
            "--vision-gemma-4b-default",
            "--vision-gemma-12b-default",
        )

        rejected.forEach { token ->
            val validation = LlamaCppLaunchConfig(additionalArguments = listOf(token)).validate()
            assertFalse("Pinned e306 server preset $token must remain app-owned", validation.valid)
            assertTrue(
                validation.error,
                validation.error.contains(
                    "Hermes-managed server presets, model download, and resource policy",
                ),
            )
        }
    }

    @Test
    fun pinnedShortAndNegativeAliasesCannotBypassHermesOwnedLaunchPolicy() {
        val rejected = mapOf(
            "-fit" to "device placement and memory policy",
            "-fitp" to "device placement and memory policy",
            "-fitt" to "device placement and memory policy",
            "-fitc" to "device placement and memory policy",
            "-cmoe" to "device placement and memory policy",
            "-ncmoe" to "device placement and memory policy",
            "--n-cpu-moe" to "device placement and memory policy",
            "--no-slots" to "endpoint, file, and privacy policy",
            "--no-skip-chat-parsing" to "chat and tool protocol",
            "--no-prefill-assistant" to "chat and tool protocol",
        )

        rejected.forEach { (flag, expectedOwner) ->
            val validation = LlamaCppLaunchConfig(additionalArguments = listOf(flag)).validate()

            assertFalse("Pinned llama-server alias $flag must remain app-owned", validation.valid)
            assertTrue(validation.error, validation.error.contains("Hermes-managed $expectedOwner"))
        }
    }

    @Test
    fun pinnedModelLoadingAndPagingFlagsCannotBypassOneShotRamPolicy() {
        val rejected = listOf(
            "--mlock",
            "--mmap",
            "--no-mmap",
            "-dio",
            "--direct-io",
            "-ndio",
            "--no-direct-io",
            "-lm",
            "--load-mode",
        )

        LlamaCppRuntimeLane.entries.forEach { lane ->
            rejected.forEach { flag ->
                val validation = LlamaCppLaunchConfig(
                    lane = lane,
                    additionalArguments = listOf(flag),
                ).validate()

                assertFalse("Pinned ${lane.persistedValue} paging flag $flag must remain app-owned", validation.valid)
                assertTrue(
                    validation.error,
                    validation.error.contains("Hermes-managed RAM admission and paging policy"),
                )
            }
        }
    }

    @Test
    fun ordinarySafeExpertPerformanceFlagsRemainAvailable() {
        val config = LlamaCppLaunchConfig(
            additionalArguments = listOf(
                "--perf",
                "--context-shift",
                "--threads-batch",
                "4",
            ),
        )

        assertTrue(config.validate().error, config.validate().valid)
        assertEquals(config.additionalArguments, config.advancedArgumentTokens())
    }

    @Test
    fun additionalArgumentVectorAcceptsSeparateEnumAndNumericValues() {
        val config = LlamaCppLaunchConfig(
            lane = LlamaCppRuntimeLane.TURBOQUANT,
            additionalArguments = listOf(
                "--cpu-mask",
                "ff",
                "--threads-batch",
                "4",
                "--prio-batch",
                "1",
            ),
        )

        assertTrue(config.validate().error, config.validate().valid)
        assertEquals(config.additionalArguments, config.advancedArgumentTokens())
    }

    @Test
    fun reviewedArgumentArityRejectsDanglingAndSurplusValuesBeforeRestart() {
        listOf(
            listOf("--threads-batch"),
            listOf("--threads-batch", "--perf"),
            listOf("--perf", "unexpected"),
            listOf("--cpu-range"),
        ).forEach { arguments ->
            val validation = LlamaCppLaunchConfig(
                lane = LlamaCppRuntimeLane.TURBOQUANT,
                additionalArguments = arguments,
            ).validate()
            assertFalse("$arguments should fail reviewed arity validation", validation.valid)
        }
    }

    @Test
    fun forwardCompatibleUnknownMultiValueAndBooleanOptionsRemainAvailable() {
        val config = LlamaCppLaunchConfig(
            lane = LlamaCppRuntimeLane.TURBOQUANT,
            additionalArguments = listOf(
                "--future-backend-option",
                "first",
                "second",
                "--future-boolean-option",
            ),
        )

        assertTrue(config.validate().error, config.validate().valid)
        assertEquals(config.additionalArguments, config.advancedArgumentTokens())
    }

    @Test
    fun modelPagingPlacementAndProtocolOverridesRemainAppOwned() {
        listOf(
            "--control-vector-layer-range",
            "--mmproj",
            "--gpu-layers",
            "--reasoning-format",
            "--server-base",
            "--log-file",
        ).forEach { flag ->
            val validation = LlamaCppLaunchConfig(additionalArguments = listOf(flag)).validate()
            assertFalse("$flag must remain app-owned", validation.valid)
            assertTrue(validation.error, validation.error.contains("Hermes-managed"))
        }
    }

    @Test
    fun caseSensitiveShortCpuMaskDoesNotCollideWithManagedContextAlias() {
        val cpuMask = LlamaCppLaunchConfig(additionalArguments = listOf("-C", "ff"))
        assertTrue(cpuMask.validate().error, cpuMask.validate().valid)

        val contextOverride = LlamaCppLaunchConfig(additionalArguments = listOf("-c", "4096"))
        assertFalse(contextOverride.validate().valid)
        assertTrue(contextOverride.validate().error.contains("memory-preflight context sizing"))
    }

    @Test
    fun additionalArgumentVectorRejectsOrphansAndUnsupportedEqualsSyntax() {
        listOf(
            listOf("mmap"),
            listOf("-1"),
            listOf("--threads-batch", "4", "second-orphan"),
            listOf("--threads-batch=4"),
            listOf("--threads-batch=4"),
        ).forEach { arguments ->
            val validation = LlamaCppLaunchConfig(additionalArguments = arguments).validate()
            assertFalse("$arguments should be rejected", validation.valid)
        }

        val equalsValidation = LlamaCppLaunchConfig(
            additionalArguments = listOf("--threads-batch=4"),
        ).validate()
        assertTrue(equalsValidation.error, equalsValidation.error.contains("separate lines"))
    }

    @Test
    fun protectedFlagWithEqualsSyntaxStillReportsTheHermesConflict() {
        val validation = LlamaCppLaunchConfig(
            additionalArguments = listOf("--host=0.0.0.0"),
        ).validate()

        assertFalse(validation.valid)
        assertTrue(validation.error, validation.error.contains("Hermes-managed loopback server binding"))
    }

    @Test
    fun additionalArgumentsEnforceCountLengthAndControlCharacterLimits() {
        assertFalse(
            LlamaCppLaunchConfig(
                additionalArguments = List(LlamaCppLaunchConfig.MAX_ADDITIONAL_ARGUMENTS + 1) { "--flag$it" },
            ).validate().valid,
        )
        assertFalse(
            LlamaCppLaunchConfig(
                additionalArguments = listOf("--flag=${"x".repeat(LlamaCppLaunchConfig.MAX_ADDITIONAL_ARGUMENT_CHARS)}"),
            ).validate().valid,
        )
        assertFalse(LlamaCppLaunchConfig(additionalArguments = listOf("--flag=bad\nvalue")).validate().valid)
        assertFalse(LlamaCppLaunchConfig(additionalArguments = listOf("")).validate().valid)
    }

    @Test
    fun everyShellArgumentIsQuotedAndAdditionalValueCannotBecomeShellSource() {
        val command = LlamaCppServerController.shellCommandForLaunch(
            llamaServerPath = "/data/app/lib server's.so",
            modelPath = "/models/model's.gguf",
            port = 15435,
            apiKey = "owned-process-nonce",
            availableProcessors = 2,
            launchConfig = LlamaCppLaunchConfig(
                additionalArguments = listOf("--tags", "$(touch /tmp/not-run)"),
            ),
        )

        assertTrue(command, command.startsWith("exec '/data/app/lib server'\\''s.so' '--model' '/models/model'\\''s.gguf'"))
        assertTrue(command, "'--host' '127.0.0.1' '--port' '15435'" in command)
        assertTrue(command, "'--api-key' 'owned-process-nonce'" in command)
        assertTrue(command, "'--ctx-size' '2048'" in command)
        assertTrue(command, command.endsWith("'--tags' '$(touch /tmp/not-run)'"))
    }

    @Test
    fun launchFingerprintChangesWithLaneOrAnyAdvancedArgument() {
        val stable = LlamaCppLaunchConfig().fingerprint()
        val turbo = LlamaCppLaunchConfig(lane = LlamaCppRuntimeLane.TURBOQUANT).fingerprint()
        val custom = LlamaCppLaunchConfig(additionalArguments = listOf("--perf")).fingerprint()

        assertEquals(64, stable.length)
        assertNotEquals(stable, turbo)
        assertNotEquals(stable, custom)
        assertEquals(stable, LlamaCppLaunchConfig().fingerprint())
    }

    @Test
    fun persistedLaneAliasesAreNormalizedWithoutEnablingUnknownExperimentalValues() {
        assertEquals(
            LlamaCppRuntimeLane.TURBOQUANT,
            LlamaCppRuntimeLane.fromPersistedValue("experimental"),
        )
        assertEquals(
            LlamaCppRuntimeLane.STABLE,
            LlamaCppRuntimeLane.fromPersistedValue("unrecognized-lane"),
        )
    }
}
