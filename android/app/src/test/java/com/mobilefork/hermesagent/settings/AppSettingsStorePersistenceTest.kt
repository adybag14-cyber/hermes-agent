package com.mobilefork.hermesagent.settings

import com.mobilefork.hermesagent.data.AppSettings
import com.mobilefork.hermesagent.data.AppSettingsPersistenceException
import com.mobilefork.hermesagent.data.AppSettingsStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class AppSettingsStorePersistenceTest {
    @Test
    fun failedSaveAndUpdateLeaveProcessCacheAndDiskAtLastCommittedSettings() {
        val app: android.app.Application = RuntimeEnvironment.getApplication()
        val store = AppSettingsStore(app)
        val original = store.load()
        val baseline = AppSettings(
            provider = "openrouter",
            baseUrl = "https://committed.example/v1",
            model = "committed-model",
            onDeviceBackend = "llama_cpp",
            llamaCppRuntimeLane = "turboquant",
            llamaCppCacheTypeK = "turbo3",
            llamaCppCacheTypeV = "q5_1",
            llamaCppFlashAttention = "on",
        )

        try {
            store.save(baseline)
            val failingStore = AppSettingsStore.withCommitterForTest(app) { editor ->
                // Android updates SharedPreferences process memory before commit() reports its
                // disk result. Simulate that mutation and then report failure.
                editor.commit()
                false
            }

            assertThrows(AppSettingsPersistenceException::class.java) {
                failingStore.update { current ->
                    current.copy(
                        provider = "stale-provider",
                        llamaCppRuntimeLane = "stable",
                    )
                }
            }
            assertEquals(baseline, store.load())

            assertThrows(AppSettingsPersistenceException::class.java) {
                failingStore.save(
                    baseline.copy(
                        provider = "failed-whole-save",
                        onDeviceBackend = "none",
                        llamaCppRuntimeLane = "stable",
                    ),
                )
            }
            assertEquals(baseline, store.load())

            store.invalidateCache()
            assertEquals(baseline, store.load())
        } finally {
            store.save(original)
        }
    }

    @Test
    fun staleProviderWriterPreservesConcurrentNanbeigeRuntimeSelection() {
        val app: android.app.Application = RuntimeEnvironment.getApplication()
        val providerStore = AppSettingsStore(app)
        val modelStore = AppSettingsStore(app)
        val original = providerStore.load()
        val staleProviderRead = CountDownLatch(1)
        val allowProviderCommit = CountDownLatch(1)
        val providerCommitFinished = CountDownLatch(1)
        val providerFailure = AtomicReference<Throwable?>(null)
        val observedStaleLane = AtomicReference<String>()
        var providerWriter: Thread? = null

        try {
            providerStore.save(
                AppSettings(
                    provider = "openrouter",
                    baseUrl = "https://old.example/v1",
                    model = "old-model",
                    onDeviceBackend = "none",
                    llamaCppRuntimeLane = "stable",
                ),
            )

            providerWriter = Thread {
                try {
                    // Represents the pre-Python snapshot in a credential flow. Slow work happens
                    // after this read and before the field-scoped atomic update.
                    observedStaleLane.set(providerStore.load().llamaCppRuntimeLane)
                    staleProviderRead.countDown()
                    check(allowProviderCommit.await(5, TimeUnit.SECONDS))
                    providerStore.update { current ->
                        current.copy(
                            provider = "gemini",
                            baseUrl = "https://provider.example/v1",
                            model = "provider-model",
                        )
                    }
                } catch (error: Throwable) {
                    providerFailure.set(error)
                } finally {
                    providerCommitFinished.countDown()
                }
            }.apply {
                name = "stale-provider-settings-writer"
                isDaemon = true
                start()
            }

            try {
                assertTrue(
                    "provider writer did not capture its stale snapshot",
                    staleProviderRead.await(5, TimeUnit.SECONDS),
                )
                assertEquals("stable", observedStaleLane.get())

                modelStore.update { current ->
                    current.copy(
                        onDeviceBackend = "llama_cpp",
                        llamaCppRuntimeLane = "turboquant",
                        llamaCppCacheTypeK = "turbo3",
                        llamaCppCacheTypeV = "q5_1",
                        llamaCppFlashAttention = "on",
                        llamaCppAdditionalArguments = listOf("--threads-batch", "3"),
                    )
                }
            } finally {
                allowProviderCommit.countDown()
            }

            assertTrue("provider writer did not finish", providerCommitFinished.await(5, TimeUnit.SECONDS))
            providerFailure.get()?.let { error -> throw AssertionError("provider writer failed", error) }

            modelStore.invalidateCache()
            val persisted = modelStore.load()
            assertEquals("gemini", persisted.provider)
            assertEquals("https://provider.example/v1", persisted.baseUrl)
            assertEquals("provider-model", persisted.model)
            assertEquals("llama_cpp", persisted.onDeviceBackend)
            assertEquals("turboquant", persisted.llamaCppRuntimeLane)
            assertEquals("turbo3", persisted.llamaCppCacheTypeK)
            assertEquals("q5_1", persisted.llamaCppCacheTypeV)
            assertEquals("on", persisted.llamaCppFlashAttention)
            assertEquals(listOf("--threads-batch", "3"), persisted.llamaCppAdditionalArguments)
        } finally {
            allowProviderCommit.countDown()
            providerWriter?.join(1_000)
            providerStore.save(original)
        }
    }

    @Test
    fun exactLegacyBuiltInPaletteMigratesOnceButCustomPaletteDoesNot() {
        val app: android.app.Application = RuntimeEnvironment.getApplication()
        val preferences = app.getSharedPreferences("hermes_android_settings", android.content.Context.MODE_PRIVATE)
        preferences.edit().clear()
            .putString("theme_primary_hex", "#8C7BFF")
            .putString("theme_secondary_hex", "#C6A15B")
            .putString("theme_background_hex", "#090B10")
            .putString("theme_surface_hex", "#11141C")
            .putString("theme_surface_variant_hex", "#1B202B")
            .commit()
        val store = AppSettingsStore(app)
        store.invalidateCache()

        assertEquals(AppSettings.DEFAULT_THEME_PRIMARY_HEX, store.load().themePrimaryHex)
        assertEquals(AppSettings.DEFAULT_THEME_BACKGROUND_HEX, store.load().themeBackgroundHex)

        store.save(store.load().copy(themePrimaryHex = "#123456"))
        store.invalidateCache()
        assertEquals("#123456", store.load().themePrimaryHex)
    }

    @Test
    fun offlineAirplaneModeAndPortalEnabledPersist() {
        val store = AppSettingsStore(RuntimeEnvironment.getApplication())
        store.save(AppSettings())

        assertFalse(store.load().offlineAirplaneMode)
        assertTrue(store.load().portalEnabled)

        store.save(
            store.load().copy(
                offlineAirplaneMode = true,
                portalEnabled = false,
            )
        )

        val reloaded = store.load()
        assertTrue(reloaded.offlineAirplaneMode)
        assertFalse(reloaded.portalEnabled)
    }

    @Test
    fun appearanceSettingsPersist() {
        val store = AppSettingsStore(RuntimeEnvironment.getApplication())
        store.save(AppSettings())

        store.save(
            store.load().copy(
                chatDisplayMode = "expanded",
                keywordHighlightingEnabled = false,
                themePrimaryHex = "#D2B35E",
                themeSecondaryHex = "#72D6C9",
                themeBackgroundHex = "#000000",
                themeSurfaceHex = "#101014",
                themeSurfaceVariantHex = "#20242C",
                themeCardShape = "square",
                uiFontScale = 0.85f,
            )
        )

        val reloaded = store.load()
        assertEquals("expanded", reloaded.chatDisplayMode)
        assertFalse(reloaded.keywordHighlightingEnabled)
        assertEquals("#D2B35E", reloaded.themePrimaryHex)
        assertEquals("#72D6C9", reloaded.themeSecondaryHex)
        assertEquals("#000000", reloaded.themeBackgroundHex)
        assertEquals("#101014", reloaded.themeSurfaceHex)
        assertEquals("#20242C", reloaded.themeSurfaceVariantHex)
        assertEquals("square", reloaded.themeCardShape)
        assertEquals(0.85f, reloaded.uiFontScale, 0.0001f)
    }

    @Test
    fun appSettingsExportImportRoundTripsWithoutSecrets() {
        val store = AppSettingsStore(RuntimeEnvironment.getApplication())
        store.save(
            AppSettings(
                provider = "gemini",
                baseUrl = "https://example.test/v1",
                model = "gemini-test",
                dataSaverMode = true,
                offlineAirplaneMode = true,
                portalEnabled = false,
                onDeviceBackend = "litert_lm",
                liteRtLmSpeculativeDecodingMode = "disabled",
                llamaCppRuntimeLane = "turboquant",
                llamaCppCacheTypeK = "q5_0",
                llamaCppCacheTypeV = "q5_1",
                llamaCppFlashAttention = "on",
                llamaCppAdditionalArguments = listOf("--load-mode", "mmap", "--mlock"),
                localModelMaxTokens = 2048,
                localModelTopK = 64,
                localModelTopP = 0.9f,
                localModelTemperature = 0.7f,
                localModelAccelerator = "gpu",
                apiGenerationKnobsEnabled = true,
                languageTag = "es",
                customSystemPrompt = "Stay concise and ask before external sends.",
                chatDisplayMode = "expanded",
                keywordHighlightingEnabled = false,
                themePrimaryHex = "#112233",
                themeSecondaryHex = "#445566",
                themeBackgroundHex = "#000000",
                themeSurfaceHex = "#101010",
                themeSurfaceVariantHex = "#202020",
                themeCardShape = "square",
            ),
        )

        val exported = store.exportBundleJson()
        assertEquals(AppSettings.EXPORT_KIND, exported.getString("kind"))
        assertFalse(exported.getBoolean("secrets_included"))
        assertTrue(exported.getJSONArray("redacted_secret_fields").toString().contains("api_key"))
        assertFalse(exported.getBoolean("expert_arguments_included"))
        assertTrue(
            exported.getJSONArray("excluded_portable_fields")
                .toString()
                .contains("llama_cpp_additional_arguments"),
        )
        assertFalse(exported.toString().contains("sk-"))
        assertFalse(exported.toString().contains("--load-mode"))
        assertEquals("gemini", exported.getJSONObject("settings").getString("provider"))
        assertFalse(exported.getJSONObject("settings").has("llama_cpp_additional_arguments"))
        assertEquals(
            "Stay concise and ask before external sends.",
            exported.getJSONObject("settings").getString("custom_system_prompt"),
        )

        // A redacted portable bundle must clear local expert argv rather than
        // silently retaining device-specific arguments from the destination.
        store.save(AppSettings(llamaCppAdditionalArguments = listOf("--mlock")))
        val imported = store.importBundleJson(exported)

        assertEquals("gemini", imported.provider)
        assertEquals("https://example.test/v1", imported.baseUrl)
        assertEquals("gemini-test", imported.model)
        assertTrue(imported.dataSaverMode)
        assertTrue(imported.offlineAirplaneMode)
        assertFalse(imported.portalEnabled)
        assertEquals("litert_lm", imported.onDeviceBackend)
        assertEquals("disabled", imported.liteRtLmSpeculativeDecodingMode)
        assertEquals("turboquant", imported.llamaCppRuntimeLane)
        assertEquals("q5_0", imported.llamaCppCacheTypeK)
        assertEquals("q5_1", imported.llamaCppCacheTypeV)
        assertEquals("on", imported.llamaCppFlashAttention)
        assertTrue(imported.llamaCppAdditionalArguments.isEmpty())
        assertEquals(2048, imported.localModelMaxTokens)
        assertEquals(64, imported.localModelTopK)
        assertEquals(0.9f, imported.localModelTopP, 0.0001f)
        assertEquals(0.7f, imported.localModelTemperature, 0.0001f)
        assertEquals("gpu", imported.localModelAccelerator)
        assertTrue(imported.apiGenerationKnobsEnabled)
        assertEquals("es", imported.languageTag)
        assertEquals("Stay concise and ask before external sends.", imported.customSystemPrompt)
        assertEquals("expanded", imported.chatDisplayMode)
        assertFalse(imported.keywordHighlightingEnabled)
        assertEquals("#112233", store.load().themePrimaryHex)
        assertEquals("square", store.load().themeCardShape)
    }

    @Test
    fun customSystemPromptIsNormalizedAndBoundedForMobileContext() {
        val longPrompt = "x".repeat(AppSettings.MAX_CUSTOM_SYSTEM_PROMPT_CHARS + 50)
        val normalized = AppSettings.normalizeCustomSystemPrompt("\r\n$longPrompt\u0000")

        assertEquals(AppSettings.MAX_CUSTOM_SYSTEM_PROMPT_CHARS, normalized.length)
        assertFalse(normalized.contains("\u0000"))
    }

    @Test
    fun modelGenerationSettingsPersistWithBoundedDefaults() {
        val store = AppSettingsStore(RuntimeEnvironment.getApplication())
        store.save(
            AppSettings(
                localModelMaxTokens = 99_999,
                localModelTopK = 999,
                localModelTopP = 9.5f,
                localModelTemperature = -1.0f,
                localModelAccelerator = "tpu",
                localModelToolMode = "large",
                apiGenerationKnobsEnabled = true,
            )
        )

        val reloaded = store.load()
        assertEquals(AppSettings.MAX_LOCAL_MODEL_MAX_TOKENS, reloaded.localModelMaxTokens)
        assertEquals(AppSettings.MAX_LOCAL_MODEL_TOP_K, reloaded.localModelTopK)
        assertEquals(AppSettings.MAX_LOCAL_MODEL_TOP_P, reloaded.localModelTopP, 0.0001f)
        assertEquals(AppSettings.MIN_LOCAL_MODEL_TEMPERATURE, reloaded.localModelTemperature, 0.0001f)
        assertEquals(AppSettings.DEFAULT_LOCAL_MODEL_ACCELERATOR, reloaded.localModelAccelerator)
        assertEquals("large", reloaded.localModelToolMode)
        assertTrue(reloaded.apiGenerationKnobsEnabled)
    }

    @Test
    fun localModelToolModeRejectsUnknownValues() {
        val store = AppSettingsStore(RuntimeEnvironment.getApplication())
        store.save(AppSettings(localModelToolMode = "unbounded"))

        assertEquals(AppSettings.DEFAULT_LOCAL_MODEL_TOOL_MODE, store.load().localModelToolMode)
    }

    @Test
    fun llamaCppAdvancedSettingsPersistLosslesslyButPortableExportExcludesExpertArgv() {
        val store = AppSettingsStore(RuntimeEnvironment.getApplication())
        store.save(
            AppSettings(
                llamaCppRuntimeLane = "experimental",
                llamaCppCacheTypeK = "Q5_0",
                llamaCppCacheTypeV = "turbo3",
                llamaCppFlashAttention = "ON",
                llamaCppAdditionalArguments = listOf(
                    "  --no-mmap  ",
                    "--load-mode\nmmap",
                    "\u0000",
                    "x".repeat(AppSettings.MAX_LLAMA_CPP_ARGUMENT_CHARS + 20),
                ),
            ),
        )
        store.invalidateCache()

        val reloaded = store.load()
        assertEquals("turboquant", reloaded.llamaCppRuntimeLane)
        assertEquals("q5_0", reloaded.llamaCppCacheTypeK)
        assertEquals("turbo3", reloaded.llamaCppCacheTypeV)
        assertEquals("on", reloaded.llamaCppFlashAttention)
        assertEquals("  --no-mmap  ", reloaded.llamaCppAdditionalArguments[0])
        assertEquals("--load-mode", reloaded.llamaCppAdditionalArguments[1])
        assertEquals("mmap", reloaded.llamaCppAdditionalArguments[2])
        assertEquals("\u0000", reloaded.llamaCppAdditionalArguments[3])
        assertEquals(AppSettings.MAX_LLAMA_CPP_ARGUMENT_CHARS + 20, reloaded.llamaCppAdditionalArguments.last().length)

        val bundle = store.exportBundleJson()
        val exportedSettings = bundle.getJSONObject("settings")
        assertEquals("turboquant", exportedSettings.getString("llama_cpp_runtime_lane"))
        assertEquals("turbo3", exportedSettings.getString("llama_cpp_cache_type_v"))
        assertFalse(exportedSettings.has("llama_cpp_additional_arguments"))
        assertFalse(bundle.toString().contains("--load-mode"))
        assertFalse(bundle.getBoolean("expert_arguments_included"))
        assertTrue(bundle.getJSONArray("excluded_portable_fields").toString().contains("llama_cpp_additional_arguments"))
        assertFalse(bundle.has("dangerously_skip_ram_checks"))
        assertFalse(bundle.toString().contains("ram_override"))

        store.save(AppSettings(llamaCppAdditionalArguments = listOf("--no-mmap")))
        assertTrue(store.importBundleJson(bundle).llamaCppAdditionalArguments.isEmpty())
    }

    @Test
    fun llamaCppAdvancedUnknownValuesFallBackWithoutChangingLegacyDefaults() {
        val settings = AppSettings.fromJson(
            org.json.JSONObject()
                .put("llama_cpp_runtime_lane", "nightly")
                .put("llama_cpp_cache_type_k", "q2_secret")
                .put("llama_cpp_cache_type_v", "")
                .put("llama_cpp_flash_attention", "sometimes"),
        )

        assertEquals("stable", settings.llamaCppRuntimeLane)
        assertEquals("default", settings.llamaCppCacheTypeK)
        assertEquals("default", settings.llamaCppCacheTypeV)
        assertEquals("default", settings.llamaCppFlashAttention)
        assertTrue(settings.llamaCppAdditionalArguments.isEmpty())
        assertEquals("none", settings.onDeviceBackend)
    }
}
