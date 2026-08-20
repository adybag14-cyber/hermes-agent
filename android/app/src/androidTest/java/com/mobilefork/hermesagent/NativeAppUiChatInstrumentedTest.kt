package com.mobilefork.hermesagent

import android.app.Application
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilefork.hermesagent.backend.BackendKind
import com.mobilefork.hermesagent.backend.HermesRuntimeManager
import com.mobilefork.hermesagent.backend.OnDeviceBackendManager
import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsStore
import com.mobilefork.hermesagent.data.ConversationStore
import com.mobilefork.hermesagent.data.LocalModelDownloadRecord
import com.mobilefork.hermesagent.data.LocalModelDownloadStore
import com.mobilefork.hermesagent.device.HermesLinuxSubsystemBridge
import com.mobilefork.hermesagent.device.LocalModelRuntimeDiagnostics
import com.mobilefork.hermesagent.models.DetectedHfModel
import com.mobilefork.hermesagent.models.HermesModelDownloadManager
import com.mobilefork.hermesagent.models.HuggingFaceModelIndexClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NativeAppUiChatInstrumentedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        HermesRuntimeManager.stop()
        OnDeviceBackendManager.stopAll()
    }

    @Test
    fun mainActivityRunsIssueEightReadOnlyToolsBeforeAnyRemoteProviderRequest() {
        val settingsStore = AppSettingsStore(app)
        val originalSettings = settingsStore.load()
        val instrumentationArguments = InstrumentationRegistry.getArguments()
        val profile = instrumentationArguments.getString("profile").orEmpty().trim()
        require(profile == "phone-compact") {
            "Issue 8 release evidence requires -e profile phone-compact"
        }
        val identity = ReleaseDeviceEvidenceIdentity.requireBound(app)
        val releaseIdentity = JSONObject()
            .put("release_source_digest", identity.releaseSourceDigest)
            .put("candidate_apk_sha256", identity.candidateApkSha256)
            .put("instrumentation_apk_sha256", identity.instrumentationApkSha256)
            .put("evidence_run_id", identity.evidenceRunId)
            .put("package_id", identity.packageId)
            .put("version_name", identity.versionName)
            .put("version_code", identity.versionCode)
            .put("release_tag", "v${identity.versionName}")
            .put("build_variant", identity.buildVariant)
            .put("lite_rt_lm_coordinate", identity.liteRtLmCoordinate)
            .put("device_serial", identity.deviceSerial)
            .put("avd_name", identity.avdName)
            .put("device_boot_id", identity.deviceBootId)
            .put("device_model", Build.MODEL)
            .put("build_fingerprint", Build.FINGERPRINT)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("profile", profile)
        val remoteConnections = AtomicInteger(0)
        val acceptConnections = AtomicBoolean(true)
        val probeServer = ServerSocket(0).apply { soTimeout = 100 }
        val probeThread = Thread {
            while (acceptConnections.get()) {
                try {
                    probeServer.accept().use { remoteConnections.incrementAndGet() }
                } catch (_: SocketTimeoutException) {
                    // Keep polling until the headed direct-route proof completes.
                } catch (_: IOException) {
                    if (acceptConnections.get()) throw AssertionError("Remote probe server failed unexpectedly")
                }
            }
        }.apply { start() }

        val remoteStop = HermesRuntimeManager.stopRemoteRuntime()
        val localStop = OnDeviceBackendManager.stopAll()
        check(remoteStop.error.isNullOrBlank()) { remoteStop.error.orEmpty() }
        check(!localStop.started && !localStop.requiresAppRestart) { localStop.statusMessage }
        val directToolRoutes = JSONArray()
        try {
            settingsStore.save(
                AppSettings(
                    provider = "openai",
                    baseUrl = "http://127.0.0.1:${probeServer.localPort}/v1",
                    model = "network-must-not-be-contacted",
                    onDeviceBackend = BackendKind.NONE.persistedValue,
                )
            )
            ConversationStore(app).clearAll()

            ActivityScenario.launch(MainActivity::class.java).use {
                composeRule.waitUntil(timeoutMillis = BOOT_TIMEOUT_MS) {
                    composeRule.onAllNodesWithTag("HermesChatInput").fetchSemanticsNodes().isNotEmpty()
                }
                // The status reply is intentionally source-exact JSON and can be taller than a
                // compact turn's viewport. Select the app's visible expanded mode so each event is
                // its own lazy-list item and the call/result nodes can be independently displayed.
                composeRule.onNodeWithTag("HermesChatDisplayToggle").assertIsDisplayed().performClick()
                composeRule.waitUntil(timeoutMillis = 5_000L) {
                    settingsStore.load().chatDisplayMode == "expanded"
                }
                composeRule.waitForIdle()
                composeRule.onNodeWithTag("HermesChatInput").performTextInput(ISSUE8_TIME_PROMPT)
                composeRule.onNodeWithTag("HermesChatSendButton").performClick()

                composeRule.waitUntil(timeoutMillis = 30_000L) {
                    val messages = ConversationStore(app).currentConversationMessages()
                    messages.lastOrNull { it.role == "assistant" }
                        ?.content
                        ?.let { Regex("""\b\d{4}\b""").containsMatchIn(it) } == true &&
                        messages.any { it.role == "tool_call" && it.content.contains("terminal_tool") } &&
                        messages.any { it.role == "tool_result" && it.content.contains("model_requests=0") }
                }
                composeRule.waitUntil(timeoutMillis = 30_000L) {
                    composeRule.onAllNodesWithTag("HermesStopAgentButton").fetchSemanticsNodes().isEmpty()
                }
                val dateToolVisible = assertPersistedAgentEventDisplayed(
                    prompt = ISSUE8_TIME_PROMPT,
                    role = "tool_call",
                    requiredContent = listOf("terminal_tool", "date"),
                )
                val dateResultVisible = assertPersistedAgentEventDisplayed(
                    prompt = ISSUE8_TIME_PROMPT,
                    role = "tool_result",
                    requiredContent = listOf("model_requests=0"),
                )
                Thread.sleep(250L)
                assertEquals(
                    "The exact issue phrase must make zero remote TCP connections",
                    0,
                    remoteConnections.get(),
                )
                directToolRoutes.put(
                    directRouteEvidence(
                        prompt = ISSUE8_TIME_PROMPT,
                        toolName = "terminal_tool",
                        toolAction = "date",
                        visibleToolEvent = dateToolVisible,
                        visibleResultEvent = dateResultVisible,
                        visibleResultText = ConversationStore(app).currentConversationMessages()
                            .last { it.role == "assistant" }.content,
                        providerNetworkRequestCount = remoteConnections.get(),
                    ),
                )

                composeRule.onNodeWithTag("HermesChatInput").performTextInput(ISSUE8_STATUS_PROMPT)
                composeRule.onNodeWithTag("HermesChatSendButton").performClick()
                composeRule.waitUntil(timeoutMillis = 30_000L) {
                    val messages = ConversationStore(app).currentConversationMessages()
                    messages.lastOrNull { it.role == "assistant" }
                        ?.content
                        ?.contains("\"status\"") == true &&
                        messages.any {
                            it.role == "tool_call" &&
                                it.content.contains("android_device_diagnostics_tool") &&
                                it.content.contains("status")
                        } &&
                        messages.lastOrNull { it.role == "tool_result" }
                            ?.content
                            ?.contains("model_requests=0") == true
                }
                composeRule.waitUntil(timeoutMillis = 30_000L) {
                    composeRule.onAllNodesWithTag("HermesStopAgentButton").fetchSemanticsNodes().isEmpty()
                }
                val statusToolVisible = assertPersistedAgentEventDisplayed(
                    prompt = ISSUE8_STATUS_PROMPT,
                    role = "tool_call",
                    requiredContent = listOf("android_device_diagnostics_tool", "status"),
                )
                val statusResultVisible = assertPersistedAgentEventDisplayed(
                    prompt = ISSUE8_STATUS_PROMPT,
                    role = "tool_result",
                    requiredContent = listOf("model_requests=0"),
                )
                Thread.sleep(250L)
                assertEquals(
                    "Both exact issue routes must make zero remote TCP connections",
                    0,
                    remoteConnections.get(),
                )
                directToolRoutes.put(
                    directRouteEvidence(
                        prompt = ISSUE8_STATUS_PROMPT,
                        toolName = "android_device_diagnostics_tool",
                        toolAction = "status",
                        visibleToolEvent = statusToolVisible,
                        visibleResultEvent = statusResultVisible,
                        visibleResultText = ConversationStore(app).currentConversationMessages()
                            .last { it.role == "assistant" }.content,
                        providerNetworkRequestCount = remoteConnections.get(),
                    ),
                )
            }

            val artifactPresent = HermesModelDownloadManager.modelDiscoveryDirectories(app)
                .any { directory -> File(directory, ISSUE8_TWELVE_B_FILE_NAME).isFile }
            val twelveBModel = DetectedHfModel(
                id = ISSUE8_TWELVE_B_MODEL_ID,
                title = "Gemma 4 12B LiteRT-LM",
                summary = "Official immutable 12B policy row",
                repoOrUrl = ISSUE8_TWELVE_B_REPOSITORY,
                filePath = ISSUE8_TWELVE_B_FILE_NAME,
                revision = ISSUE8_TWELVE_B_REVISION,
                runtimeFlavor = "LiteRT-LM",
                sourceLabel = "Hugging Face",
                expectedBytes = ISSUE8_TWELVE_B_BYTES,
                releaseCertified = false,
                immutableRevision = true,
            )
            val mobileCatalog = HuggingFaceModelIndexClient.mobileQuickCatalogModels(listOf(twelveBModel))
            val automaticSelection = HuggingFaceModelIndexClient.preferredDetectedModelId(
                models = listOf(twelveBModel),
                currentSelectionId = "",
            )
            val catalogPolicy = JSONObject()
                .put("evaluation_source", "production-mobile-catalog-policy")
                .put("model_id", ISSUE8_TWELVE_B_MODEL_ID)
                .put("repository", ISSUE8_TWELVE_B_REPOSITORY)
                .put("revision", ISSUE8_TWELVE_B_REVISION)
                .put("file_name", ISSUE8_TWELVE_B_FILE_NAME)
                .put("catalog_declared_bytes", ISSUE8_TWELVE_B_BYTES)
                .put("expected_sha256", ISSUE8_TWELVE_B_SHA256)
                .put("release_certified", twelveBModel.releaseCertified)
                .put("quick_start_eligible", twelveBModel.quickStartEligible)
                .put("present_in_mobile_quick_catalog", mobileCatalog.any { it.id == ISSUE8_TWELVE_B_MODEL_ID })
                .put("automatically_selected", automaticSelection == ISSUE8_TWELVE_B_MODEL_ID)
                .put("artifact_file_present", artifactPresent)

            val controlledMemory = LocalModelRuntimeDiagnostics.MemorySnapshot(
                totalBytes = ISSUE8_NOMINAL_SIXTEEN_GIB_BYTES,
                availableBytes = ISSUE8_AVAILABLE_BYTES,
                thresholdBytes = ISSUE8_THRESHOLD_BYTES,
                lowMemory = false,
                memoryClassBytes = 0L,
                largeMemoryClassBytes = 0L,
                nativeHeapAllocatedBytes = 0L,
            )
            val nativeSnapshotBefore = LocalModelRuntimeDiagnostics.readSnapshot(app)?.toString()
            val preflightDecision = LocalModelRuntimeDiagnostics.evaluatePreflight(
                backend = "litert-lm",
                modelBytes = ISSUE8_TWELVE_B_BYTES,
                requestedContextTokens = ISSUE8_REQUESTED_CONTEXT_TOKENS,
                memory = controlledMemory,
            )
            val nativeSnapshotAfter = LocalModelRuntimeDiagnostics.readSnapshot(app)?.toString()
            val localStatusAfterPreflight = OnDeviceBackendManager.currentStatus()
            val nativeEngineStartAttempted = nativeSnapshotBefore != nativeSnapshotAfter
            val twelveBPreflight = JSONObject()
                .put("model_id", ISSUE8_TWELVE_B_MODEL_ID)
                .put("repository", ISSUE8_TWELVE_B_REPOSITORY)
                .put("revision", ISSUE8_TWELVE_B_REVISION)
                .put("file_name", ISSUE8_TWELVE_B_FILE_NAME)
                .put("catalog_declared_bytes", ISSUE8_TWELVE_B_BYTES)
                .put("model_bytes_evaluated", ISSUE8_TWELVE_B_BYTES)
                .put("expected_sha256", ISSUE8_TWELVE_B_SHA256)
                .put("backend", "litert-lm")
                .put("artifact_path", "")
                .put("artifact_file_present", artifactPresent)
                .put("evaluation_source", "production-local-model-runtime-preflight")
                .put(
                    "memory_profile",
                    JSONObject()
                        .put("source", "controlled-instrumentation-memory-snapshot")
                        .put("classification", "nominal-16-gib")
                        .put("total_bytes", controlledMemory.totalBytes)
                        .put("available_bytes", controlledMemory.availableBytes)
                        .put("threshold_bytes", controlledMemory.thresholdBytes)
                        .put("usable_available_bytes", controlledMemory.usableAvailableBytes)
                        .put("low_memory", controlledMemory.lowMemory),
                )
                .put("requested_context_tokens", ISSUE8_REQUESTED_CONTEXT_TOKENS)
                .put("effective_context_tokens", preflightDecision.effectiveContextTokens)
                .put("estimated_additional_bytes", preflightDecision.estimatedAdditionalBytes)
                .put("preflight_allowed", preflightDecision.allowed)
                .put("preflight_level", preflightDecision.level)
                .put("blocked_before_native_engine", !preflightDecision.allowed && !nativeEngineStartAttempted)
                .put("native_engine_start_attempted", nativeEngineStartAttempted)
                .put("native_engine_started", localStatusAfterPreflight.started)
                .put("requires_app_restart", localStatusAfterPreflight.requiresAppRestart)
                .put("reason", preflightDecision.detail)

            val validationErrors = JSONArray()
            fun requireEvidence(condition: Boolean, message: String) {
                if (!condition) validationErrors.put(message)
            }
            requireEvidence(directToolRoutes.length() == 2, "both direct routes were not recorded")
            requireEvidence(remoteConnections.get() == 0, "a provider network request was observed")
            requireEvidence(!artifactPresent, "the unsupported 12B artifact is unexpectedly present")
            requireEvidence(!twelveBModel.quickStartEligible, "12B was marked quick-start eligible")
            requireEvidence(mobileCatalog.isEmpty(), "12B remains in the mobile quick catalog")
            requireEvidence(automaticSelection.isBlank(), "12B was automatically selected")
            requireEvidence(!preflightDecision.allowed, "12B memory preflight was not blocked")
            requireEvidence(preflightDecision.level == "blocked", "12B memory preflight level is not blocked")
            requireEvidence(!nativeEngineStartAttempted, "12B preflight changed the native-attempt snapshot")
            requireEvidence(!localStatusAfterPreflight.started, "12B preflight started a native engine")
            requireEvidence(!localStatusAfterPreflight.requiresAppRestart, "12B preflight poisoned native state")
            requireEvidence(
                preflightDecision.detail.contains("usable RAM") &&
                    preflightDecision.detail.contains("choose a smaller model"),
                "12B preflight reason is not actionable",
            )

            val passed = validationErrors.length() == 0
            val evidence = JSONObject()
                .put("schema", "hermes-android-issue-8-tool-and-preflight-v1")
                .put("issue_number", 8)
                .put("result", if (passed) "pass" else "fail")
                .put("overall_exit_code", if (passed) 0 else 1)
                .put("evidence_source", "instrumentation")
                .put(
                    "instrumentation_method",
                    "NativeAppUiChatInstrumentedTest#" +
                        "mainActivityRunsIssueEightReadOnlyToolsBeforeAnyRemoteProviderRequest",
                )
                .put("release_identity", releaseIdentity)
                .put("direct_tool_routes", directToolRoutes)
                .put("catalog_policy", catalogPolicy)
                .put("twelve_b_preflight", twelveBPreflight)
                .put("validation_errors", validationErrors)
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0,
                Bundle().apply { putString("issue8_tool_and_preflight", evidence.toString()) },
            )
            assertTrue(evidence.toString(2), passed)
        } finally {
            acceptConnections.set(false)
            probeServer.close()
            probeThread.join(1_000L)
            HermesRuntimeManager.stop()
            OnDeviceBackendManager.stopAll()
            settingsStore.save(originalSettings)
        }
    }

    private fun assertPersistedAgentEventDisplayed(
        prompt: String,
        role: String,
        requiredContent: List<String>,
    ): Boolean {
        val messages = ConversationStore(app).currentConversationMessages()
        val latestUserIndex = messages.indexOfLast { it.role == "user" && it.content == prompt }
        assertTrue("Expected persisted user prompt: $prompt", latestUserIndex >= 0)
        val expectedMessage = messages
            .drop(latestUserIndex + 1)
            .firstOrNull { message ->
                message.role == role && requiredContent.all { marker -> message.content.contains(marker) }
            }
        assertTrue(
            "Expected persisted $role after '$prompt' containing ${requiredContent.joinToString()}",
            expectedMessage != null,
        )
        val message = requireNotNull(expectedMessage)
        val messageIndex = messages.indexOfFirst { it.id == message.id }
        assertTrue("Expected persisted message index for ${message.id}", messageIndex >= 0)
        composeRule.onNodeWithTag("HermesChatMessageList").performScrollToIndex(messageIndex)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(
            "HermesAgentEventMessage_${message.id}",
            useUnmergedTree = true,
        ).assertIsDisplayed()
        return true
    }

    private fun directRouteEvidence(
        prompt: String,
        toolName: String,
        toolAction: String,
        visibleToolEvent: Boolean,
        visibleResultEvent: Boolean,
        visibleResultText: String,
        providerNetworkRequestCount: Int,
    ): JSONObject {
        val messages = ConversationStore(app).currentConversationMessages()
        val latestUserIndex = messages.indexOfLast { it.role == "user" && it.content == prompt }
        val routeMessages = if (latestUserIndex >= 0) messages.drop(latestUserIndex + 1) else emptyList()
        val toolCalls = routeMessages.filter {
            it.role == "tool_call" && it.content.contains(toolName) && it.content.contains(toolAction)
        }
        val toolResult = routeMessages.lastOrNull { it.role == "tool_result" }
        val modelRequestCount = toolResult?.content
            ?.let { Regex("""model_requests=(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: -1
        return JSONObject()
            .put("prompt", prompt)
            .put("tool_name", toolName)
            .put("tool_action", toolAction)
            .put("visible_tool_event", visibleToolEvent)
            .put("visible_result_event", visibleResultEvent)
            .put("visible_result_text", visibleResultText)
            .put("executed_tool_calls", toolCalls.size)
            .put("model_request_count", modelRequestCount)
            .put("provider_network_request_count", providerNetworkRequestCount)
    }

    @Test
    fun mainActivityChatUiUsesGemma4ToRunNativeTerminalTool() {
        val modelFile = File(app.filesDir, MODEL_RELATIVE_PATH)
        assumeTrue("Gemma 4 LiteRT-LM model is not provisioned at ${modelFile.absolutePath}", modelFile.isFile)
        assertEquals("Gemma 4 LiteRT-LM model size", MODEL_BYTES, modelFile.length())

        seedPreferredGemma4Model(modelFile)
        ConversationStore(app).clearAll()
        val workspace = File(HermesLinuxSubsystemBridge.ensureInstalled(app).getString("home_path"))
        val toolFile = File(workspace, "hermes-ui-tool-smoke.txt")
        toolFile.delete()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(timeoutMillis = BOOT_TIMEOUT_MS) {
                composeRule.onAllNodesWithText("Hermes Fork Chat").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Hermes Fork Chat").assertIsDisplayed()
            composeRule.onNodeWithTag("HermesChatInput").assertIsDisplayed()

            composeRule.onNodeWithTag("HermesChatInput").performTextInput(
                "Use terminal_tool to run exactly this command: " +
                    "printf ui-tool-ok > \$HOME/hermes-ui-tool-smoke.txt && " +
                    "cat \$HOME/hermes-ui-tool-smoke.txt. " +
                    "After terminal_tool returns, reply with the command output.",
            )
            composeRule.onNodeWithTag("HermesChatSendButton").performClick()

            composeRule.waitUntil(timeoutMillis = CHAT_TIMEOUT_MS) {
                toolFile.isFile && toolFile.readText() == "ui-tool-ok"
            }
            composeRule.waitUntil(timeoutMillis = CHAT_TIMEOUT_MS) {
                ConversationStore(app)
                    .currentConversationMessages()
                    .lastOrNull { it.role == "assistant" }
                    ?.content
                    ?.contains("ui-tool-ok") == true
            }
            composeRule.waitUntil(timeoutMillis = CHAT_TIMEOUT_MS) {
                composeRule.onAllNodesWithText("ui-tool-ok", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }
    }

    @Test
    fun mainActivityMiniCpmNaturalTerminalRequestShowsCollapsibleTimeline() {
        val modelFile = File(app.filesDir, MINICPM_MODEL_RELATIVE_PATH)
        assumeTrue("MiniCPM LiteRT-LM model is not provisioned at ${modelFile.absolutePath}", modelFile.isFile)
        assertEquals("MiniCPM LiteRT-LM model size", MINICPM_MODEL_BYTES, modelFile.length())

        seedPreferredMiniCpmModel(modelFile)
        ConversationStore(app).clearAll()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(timeoutMillis = BOOT_TIMEOUT_MS) {
                composeRule.onAllNodesWithText("Hermes Fork Chat").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("HermesChatInput").performTextInput(
                "Could you please run pwd and tell me the current working directory?",
            )
            composeRule.onNodeWithTag("HermesChatSendButton").performClick()

            composeRule.waitUntil(timeoutMillis = CHAT_TIMEOUT_MS) {
                val roles = ConversationStore(app).currentConversationMessages().map { message -> message.role }
                "tool_call" in roles && "process_log" in roles && "assistant" in roles
            }
            val processLog = ConversationStore(app).currentConversationMessages()
                .last { message -> message.role == "process_log" }
                .content
            assertTrue(
                "Expected the working directory from a successful terminal result, got $processLog",
                processLog.contains("/files/hermes-home/native-shell/home"),
            )
            assertFalse("System-shell fallback must not hit a missing Termux dependency: $processLog", processLog.contains("CANNOT LINK EXECUTABLE"))
            composeRule.onNodeWithTag("HermesAgentEvent_tool_call").assertIsDisplayed()
            composeRule.onNodeWithTag("HermesAgentEvent_process_log").assertIsDisplayed()

            composeRule.onNodeWithTag("HermesToggleIntermediateSteps").performClick()
            composeRule.waitUntil {
                composeRule.onAllNodesWithTag("HermesAgentEvent_tool_call").fetchSemanticsNodes().isEmpty() &&
                    composeRule.onAllNodesWithTag("HermesAgentEvent_process_log").fetchSemanticsNodes().isEmpty()
            }
            composeRule.onNodeWithTag("HermesToggleIntermediateSteps").performClick()
            composeRule.onNodeWithTag("HermesAgentEvent_tool_call").assertIsDisplayed()
        }
    }

    private fun seedPreferredGemma4Model(modelFile: File) {
        val record = LocalModelDownloadRecord(
            id = "gemma-4-e2b-litertlm-native-ui-smoke",
            title = MODEL_ID,
            sourceUrl = MODEL_SOURCE_URL,
            repoOrUrl = MODEL_REPO,
            filePath = MODEL_FILE_NAME,
            revision = MODEL_REVISION,
            runtimeFlavor = "LiteRT-LM",
            destinationFileName = MODEL_FILE_NAME,
            destinationPath = modelFile.absolutePath,
            downloadManagerId = -1L,
            totalBytes = MODEL_BYTES,
            downloadedBytes = MODEL_BYTES,
            status = "completed",
            statusMessage = "Provisioned for native UI instrumentation",
            supportsResume = false,
        )
        LocalModelDownloadStore(app).apply {
            upsertDownload(record)
            setPreferredDownloadId(record.id)
        }
        AppSettingsStore(app).save(
            AppSettings(
                provider = "custom",
                baseUrl = "",
                model = MODEL_ID,
                onDeviceBackend = BackendKind.LITERT_LM.persistedValue,
            )
        )
    }

    private fun seedPreferredMiniCpmModel(modelFile: File) {
        val record = LocalModelDownloadRecord(
            id = "minicpm5-1b-litertlm-native-ui-regression",
            title = MINICPM_MODEL_ID,
            sourceUrl = "https://huggingface.co/$MINICPM_MODEL_REPO/resolve/main/$MINICPM_MODEL_FILE_NAME",
            repoOrUrl = MINICPM_MODEL_REPO,
            filePath = MINICPM_MODEL_FILE_NAME,
            revision = "main",
            runtimeFlavor = "LiteRT-LM",
            destinationFileName = MINICPM_MODEL_FILE_NAME,
            destinationPath = modelFile.absolutePath,
            downloadManagerId = -1L,
            totalBytes = MINICPM_MODEL_BYTES,
            downloadedBytes = MINICPM_MODEL_BYTES,
            status = "completed",
            statusMessage = "Provisioned for MiniCPM native UI regression",
            supportsResume = false,
        )
        LocalModelDownloadStore(app).apply {
            upsertDownload(record)
            setPreferredDownloadId(record.id)
        }
        AppSettingsStore(app).save(
            AppSettings(
                provider = "custom",
                baseUrl = "",
                model = MINICPM_MODEL_ID,
                onDeviceBackend = BackendKind.LITERT_LM.persistedValue,
            ),
        )
    }

    private companion object {
        private const val ISSUE8_TIME_PROMPT = "Run a command to tell me what time it is."
        private const val ISSUE8_STATUS_PROMPT = "Check my device status"
        private const val ISSUE8_TWELVE_B_MODEL_ID = "gemma-4-12b-litert-lm"
        private const val ISSUE8_TWELVE_B_REPOSITORY = "litert-community/gemma-4-12B-it-litert-lm"
        private const val ISSUE8_TWELVE_B_REVISION = "d7de8ec6dcf035c90999ff38560bf4c6eb45a947"
        private const val ISSUE8_TWELVE_B_FILE_NAME = "gemma-4-12B-it.litertlm"
        private const val ISSUE8_TWELVE_B_BYTES = 6_547_589_312L
        private const val ISSUE8_TWELVE_B_SHA256 =
            "74fc29a10c20eb5b3ced6c389471a7994a0ffd657255b2a1c764262fb9054aef"
        private const val ISSUE8_NOMINAL_SIXTEEN_GIB_BYTES = 17_179_869_184L
        private const val ISSUE8_AVAILABLE_BYTES = 10_000_000_000L
        private const val ISSUE8_THRESHOLD_BYTES = 500_000_000L
        private const val ISSUE8_REQUESTED_CONTEXT_TOKENS = 32_000
        private const val MODEL_ID = "gemma-4-E2B-it"
        private const val MODEL_REPO = "litert-community/gemma-4-E2B-it-litert-lm"
        private const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val MODEL_RELATIVE_PATH = "hermes-home/downloads/models/$MODEL_FILE_NAME"
        private const val MODEL_SOURCE_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/gemma-4-E2B-it.litertlm"
        private const val MODEL_REVISION = "7fa1d78473894f7e736a21d920c3aa80f950c0db"
        private const val MODEL_BYTES = 2_583_085_056L
        private const val MINICPM_MODEL_ID = "MiniCPM5-1B"
        private const val MINICPM_MODEL_REPO = "Tdamre/MiniCPM5-1B-litert-lm"
        private const val MINICPM_MODEL_FILE_NAME = "MiniCPM5-1B-web.litertlm"
        private const val MINICPM_MODEL_RELATIVE_PATH = "hermes-home/downloads/models/$MINICPM_MODEL_FILE_NAME"
        private const val MINICPM_MODEL_BYTES = 1_103_486_896L
        private const val BOOT_TIMEOUT_MS = 180_000L
        private const val CHAT_TIMEOUT_MS = 900_000L
    }
}
