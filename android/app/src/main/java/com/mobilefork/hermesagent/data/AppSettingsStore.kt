package com.mobilefork.hermesagent.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class AppSettings(
    val provider: String = "openrouter",
    val baseUrl: String = "",
    val model: String = "",
    val corr3xtBaseUrl: String = "",
    val dataSaverMode: Boolean = false,
    val offlineAirplaneMode: Boolean = false,
    val portalEnabled: Boolean = true,
    val onDeviceBackend: String = "none",
    val liteRtLmSpeculativeDecodingMode: String = "auto",
    val llamaCppRuntimeLane: String = DEFAULT_LLAMA_CPP_RUNTIME_LANE,
    val llamaCppCacheTypeK: String = DEFAULT_LLAMA_CPP_CACHE_TYPE,
    val llamaCppCacheTypeV: String = DEFAULT_LLAMA_CPP_CACHE_TYPE,
    val llamaCppFlashAttention: String = DEFAULT_LLAMA_CPP_FLASH_ATTENTION,
    val llamaCppAdditionalArguments: List<String> = emptyList(),
    val localModelMaxTokens: Int = DEFAULT_LOCAL_MODEL_MAX_TOKENS,
    val localModelTopK: Int = DEFAULT_LOCAL_MODEL_TOP_K,
    val localModelTopP: Float = DEFAULT_LOCAL_MODEL_TOP_P,
    val localModelTemperature: Float = DEFAULT_LOCAL_MODEL_TEMPERATURE,
    val localModelAccelerator: String = DEFAULT_LOCAL_MODEL_ACCELERATOR,
    val localModelToolMode: String = DEFAULT_LOCAL_MODEL_TOOL_MODE,
    val apiGenerationKnobsEnabled: Boolean = false,
    val languageTag: String = "en",
    val customSystemPrompt: String = "",
    val chatDisplayMode: String = "compact",
    val keywordHighlightingEnabled: Boolean = true,
    val themePrimaryHex: String = DEFAULT_THEME_PRIMARY_HEX,
    val themeSecondaryHex: String = DEFAULT_THEME_SECONDARY_HEX,
    val themeBackgroundHex: String = DEFAULT_THEME_BACKGROUND_HEX,
    val themeSurfaceHex: String = DEFAULT_THEME_SURFACE_HEX,
    val themeSurfaceVariantHex: String = DEFAULT_THEME_SURFACE_VARIANT_HEX,
    val themeCardShape: String = "rounded",
    val uiFontScale: Float = DEFAULT_UI_FONT_SCALE,
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("provider", provider)
            .put("base_url", baseUrl)
            .put("model", model)
            .put("corr3xt_base_url", corr3xtBaseUrl)
            .put("data_saver_mode", dataSaverMode)
            .put("offline_airplane_mode", offlineAirplaneMode)
            .put("portal_enabled", portalEnabled)
            .put("on_device_backend", onDeviceBackend)
            .put("litert_lm_speculative_decoding_mode", liteRtLmSpeculativeDecodingMode)
            .put("llama_cpp_runtime_lane", normalizeLlamaCppRuntimeLane(llamaCppRuntimeLane))
            .put("llama_cpp_cache_type_k", normalizeLlamaCppCacheType(llamaCppCacheTypeK))
            .put("llama_cpp_cache_type_v", normalizeLlamaCppCacheType(llamaCppCacheTypeV))
            .put("llama_cpp_flash_attention", normalizeLlamaCppFlashAttention(llamaCppFlashAttention))
            .put(
                "llama_cpp_additional_arguments",
                JSONArray(normalizeLlamaCppAdditionalArguments(llamaCppAdditionalArguments)),
            )
            .put("local_model_max_tokens", normalizeLocalModelMaxTokens(localModelMaxTokens))
            .put("local_model_top_k", normalizeLocalModelTopK(localModelTopK))
            .put("local_model_top_p", normalizeLocalModelTopP(localModelTopP).toDouble())
            .put("local_model_temperature", normalizeLocalModelTemperature(localModelTemperature).toDouble())
            .put("local_model_accelerator", normalizeLocalModelAccelerator(localModelAccelerator))
            .put("local_model_tool_mode", normalizeLocalModelToolMode(localModelToolMode))
            .put("api_generation_knobs_enabled", apiGenerationKnobsEnabled)
            .put("language_tag", languageTag)
            .put("custom_system_prompt", normalizeCustomSystemPrompt(customSystemPrompt))
            .put("chat_display_mode", chatDisplayMode)
            .put("keyword_highlighting_enabled", keywordHighlightingEnabled)
            .put("theme_primary_hex", themePrimaryHex)
            .put("theme_secondary_hex", themeSecondaryHex)
            .put("theme_background_hex", themeBackgroundHex)
            .put("theme_surface_hex", themeSurfaceHex)
            .put("theme_surface_variant_hex", themeSurfaceVariantHex)
            .put("theme_card_shape", themeCardShape)
            .put("ui_font_scale", normalizeUiFontScale(uiFontScale).toDouble())
    }

    companion object {
        const val EXPORT_KIND = "hermes_android_app_settings_bundle"
        const val EXPORT_SCHEMA_VERSION = 1
        const val MAX_CUSTOM_SYSTEM_PROMPT_CHARS = 2_000
        const val DEFAULT_LOCAL_MODEL_MAX_TOKENS = -1
        const val MAX_LOCAL_MODEL_MAX_TOKENS = 32_768
        const val DEFAULT_LOCAL_MODEL_TOP_K = 40
        const val MIN_LOCAL_MODEL_TOP_K = 1
        const val MAX_LOCAL_MODEL_TOP_K = 200
        const val DEFAULT_LOCAL_MODEL_TOP_P = 0.95f
        const val MIN_LOCAL_MODEL_TOP_P = 0.05f
        const val MAX_LOCAL_MODEL_TOP_P = 1.0f
        const val DEFAULT_LOCAL_MODEL_TEMPERATURE = 1.0f
        const val MIN_LOCAL_MODEL_TEMPERATURE = 0.0f
        const val MAX_LOCAL_MODEL_TEMPERATURE = 2.0f
        const val DEFAULT_LOCAL_MODEL_ACCELERATOR = "auto"
        const val DEFAULT_LOCAL_MODEL_TOOL_MODE = "general"
        const val DEFAULT_LLAMA_CPP_RUNTIME_LANE = "stable"
        const val DEFAULT_LLAMA_CPP_CACHE_TYPE = "default"
        const val DEFAULT_LLAMA_CPP_FLASH_ATTENTION = "default"
        const val MAX_LLAMA_CPP_ADDITIONAL_ARGUMENTS = 64
        const val MAX_LLAMA_CPP_ARGUMENT_CHARS = 256
        const val DEFAULT_THEME_PRIMARY_HEX = "#24D6A3"
        const val DEFAULT_THEME_SECONDARY_HEX = "#F1B84B"
        const val DEFAULT_THEME_BACKGROUND_HEX = "#03090C"
        const val DEFAULT_THEME_SURFACE_HEX = "#0A1418"
        const val DEFAULT_THEME_SURFACE_VARIANT_HEX = "#111E22"
        const val DEFAULT_UI_FONT_SCALE = 1.0f
        const val MIN_UI_FONT_SCALE = 0.8f
        const val MAX_UI_FONT_SCALE = 1.3f

        val REDACTED_SECRET_FIELDS: JSONArray
            get() = JSONArray()
                .put("api_key")
                .put("access_token")
                .put("refresh_token")
                .put("provider_credentials")
                .put("cookie")
                .put("authorization")

        val EXCLUDED_PORTABLE_FIELDS: JSONArray
            get() = JSONArray()
                .put("llama_cpp_additional_arguments")

        fun fromJson(json: JSONObject, fallback: AppSettings = AppSettings()): AppSettings {
            return fallback.copy(
                provider = json.optString("provider", fallback.provider).ifBlank { fallback.provider },
                baseUrl = json.optString("base_url", fallback.baseUrl),
                model = json.optString("model", fallback.model),
                corr3xtBaseUrl = json.optString("corr3xt_base_url", fallback.corr3xtBaseUrl),
                dataSaverMode = optBoolean(json, "data_saver_mode", fallback.dataSaverMode),
                offlineAirplaneMode = optBoolean(json, "offline_airplane_mode", fallback.offlineAirplaneMode),
                portalEnabled = optBoolean(json, "portal_enabled", fallback.portalEnabled),
                onDeviceBackend = json.optString("on_device_backend", fallback.onDeviceBackend).ifBlank { fallback.onDeviceBackend },
                liteRtLmSpeculativeDecodingMode = json.optString(
                    "litert_lm_speculative_decoding_mode",
                    fallback.liteRtLmSpeculativeDecodingMode,
                ).ifBlank { fallback.liteRtLmSpeculativeDecodingMode },
                llamaCppRuntimeLane = normalizeLlamaCppRuntimeLane(
                    json.optString("llama_cpp_runtime_lane", fallback.llamaCppRuntimeLane),
                ),
                llamaCppCacheTypeK = normalizeLlamaCppCacheType(
                    json.optString("llama_cpp_cache_type_k", fallback.llamaCppCacheTypeK),
                ),
                llamaCppCacheTypeV = normalizeLlamaCppCacheType(
                    json.optString("llama_cpp_cache_type_v", fallback.llamaCppCacheTypeV),
                ),
                llamaCppFlashAttention = normalizeLlamaCppFlashAttention(
                    json.optString("llama_cpp_flash_attention", fallback.llamaCppFlashAttention),
                ),
                llamaCppAdditionalArguments = normalizeLlamaCppAdditionalArguments(
                    optStringList(json, "llama_cpp_additional_arguments", fallback.llamaCppAdditionalArguments),
                ),
                localModelMaxTokens = normalizeLocalModelMaxTokens(
                    json.optInt("local_model_max_tokens", fallback.localModelMaxTokens),
                ),
                localModelTopK = normalizeLocalModelTopK(
                    json.optInt("local_model_top_k", fallback.localModelTopK),
                ),
                localModelTopP = normalizeLocalModelTopP(
                    json.optDouble("local_model_top_p", fallback.localModelTopP.toDouble()).toFloat(),
                ),
                localModelTemperature = normalizeLocalModelTemperature(
                    json.optDouble("local_model_temperature", fallback.localModelTemperature.toDouble()).toFloat(),
                ),
                localModelAccelerator = normalizeLocalModelAccelerator(
                    json.optString("local_model_accelerator", fallback.localModelAccelerator),
                ),
                localModelToolMode = normalizeLocalModelToolMode(
                    json.optString("local_model_tool_mode", fallback.localModelToolMode),
                ),
                apiGenerationKnobsEnabled = optBoolean(
                    json,
                    "api_generation_knobs_enabled",
                    fallback.apiGenerationKnobsEnabled,
                ),
                languageTag = json.optString("language_tag", fallback.languageTag).ifBlank { fallback.languageTag },
                customSystemPrompt = normalizeCustomSystemPrompt(
                    json.optString("custom_system_prompt", fallback.customSystemPrompt),
                ),
                chatDisplayMode = json.optString("chat_display_mode", fallback.chatDisplayMode).ifBlank { fallback.chatDisplayMode },
                keywordHighlightingEnabled = optBoolean(
                    json,
                    "keyword_highlighting_enabled",
                    fallback.keywordHighlightingEnabled,
                ),
                themePrimaryHex = json.optString("theme_primary_hex", fallback.themePrimaryHex).ifBlank { fallback.themePrimaryHex },
                themeSecondaryHex = json.optString("theme_secondary_hex", fallback.themeSecondaryHex).ifBlank { fallback.themeSecondaryHex },
                themeBackgroundHex = json.optString("theme_background_hex", fallback.themeBackgroundHex).ifBlank { fallback.themeBackgroundHex },
                themeSurfaceHex = json.optString("theme_surface_hex", fallback.themeSurfaceHex).ifBlank { fallback.themeSurfaceHex },
                themeSurfaceVariantHex = json.optString(
                    "theme_surface_variant_hex",
                    fallback.themeSurfaceVariantHex,
                ).ifBlank { fallback.themeSurfaceVariantHex },
                themeCardShape = json.optString("theme_card_shape", fallback.themeCardShape).ifBlank { fallback.themeCardShape },
                uiFontScale = normalizeUiFontScale(
                    json.optDouble("ui_font_scale", fallback.uiFontScale.toDouble()).toFloat(),
                ),
            )
        }

        fun exportBundle(settings: AppSettings, exportedAtEpochMs: Long = System.currentTimeMillis()): JSONObject {
            val settingsJson = settings.toJson().apply {
                // Arbitrary expert argv can contain local paths, tokens, or device-specific
                // switches. It remains durable on this device but is not portable export data.
                remove("llama_cpp_additional_arguments")
            }
            return JSONObject()
                .put("kind", EXPORT_KIND)
                .put("schema_version", EXPORT_SCHEMA_VERSION)
                .put("exported_at_epoch_ms", exportedAtEpochMs)
                .put("secrets_included", false)
                .put("expert_arguments_included", false)
                .put("portable_field_count", settingsJson.length())
                .put("redacted_secret_fields", REDACTED_SECRET_FIELDS)
                .put("excluded_portable_fields", EXCLUDED_PORTABLE_FIELDS)
                .put("settings", settingsJson)
        }

        private fun optBoolean(json: JSONObject, key: String, fallback: Boolean): Boolean {
            return if (json.has(key) && !json.isNull(key)) json.optBoolean(key, fallback) else fallback
        }

        private fun optStringList(json: JSONObject, key: String, fallback: List<String>): List<String> {
            val array = json.optJSONArray(key) ?: return fallback
            return buildList {
                repeat(array.length()) { index ->
                    if (!array.isNull(index)) add(array.optString(index))
                }
            }
        }

        fun normalizeCustomSystemPrompt(value: String): String {
            return value
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .filter { it == '\n' || it == '\t' || it >= ' ' }
                .trim()
                .take(MAX_CUSTOM_SYSTEM_PROMPT_CHARS)
        }

        fun normalizeUiFontScale(value: Float): Float = value.coerceIn(MIN_UI_FONT_SCALE, MAX_UI_FONT_SCALE)

        fun normalizeLocalModelMaxTokens(value: Int): Int {
            return when {
                value <= 0 -> DEFAULT_LOCAL_MODEL_MAX_TOKENS
                else -> value.coerceIn(1, MAX_LOCAL_MODEL_MAX_TOKENS)
            }
        }

        fun normalizeLocalModelTopK(value: Int): Int {
            return value.coerceIn(MIN_LOCAL_MODEL_TOP_K, MAX_LOCAL_MODEL_TOP_K)
        }

        fun normalizeLocalModelTopP(value: Float): Float {
            return if (value.isNaN() || value.isInfinite()) {
                DEFAULT_LOCAL_MODEL_TOP_P
            } else {
                value.coerceIn(MIN_LOCAL_MODEL_TOP_P, MAX_LOCAL_MODEL_TOP_P)
            }
        }

        fun normalizeLocalModelTemperature(value: Float): Float {
            return if (value.isNaN() || value.isInfinite()) {
                DEFAULT_LOCAL_MODEL_TEMPERATURE
            } else {
                value.coerceIn(MIN_LOCAL_MODEL_TEMPERATURE, MAX_LOCAL_MODEL_TEMPERATURE)
            }
        }

        fun normalizeLocalModelAccelerator(value: String): String {
            val normalized = value.trim().lowercase()
            return when (normalized) {
                "auto", "cpu", "gpu" -> normalized
                else -> DEFAULT_LOCAL_MODEL_ACCELERATOR
            }
        }

        fun normalizeLocalModelToolMode(value: String): String {
            return when (value.trim().lowercase()) {
                "small", "general", "large" -> value.trim().lowercase()
                else -> DEFAULT_LOCAL_MODEL_TOOL_MODE
            }
        }

        fun normalizeLlamaCppRuntimeLane(value: String): String {
            return when (value.trim().lowercase()) {
                "turboquant", "experimental" -> "turboquant"
                else -> DEFAULT_LLAMA_CPP_RUNTIME_LANE
            }
        }

        fun normalizeLlamaCppCacheType(value: String): String {
            val normalized = value.trim().lowercase()
            return when (normalized) {
                "default", "f32", "f16", "bf16", "q8_0", "q4_0", "q4_1", "iq4_nl",
                "q5_0", "q5_1", "turbo2", "turbo3", "turbo4" -> normalized
                else -> DEFAULT_LLAMA_CPP_CACHE_TYPE
            }
        }

        fun normalizeLlamaCppFlashAttention(value: String): String {
            return when (value.trim().lowercase()) {
                "auto", "on", "off" -> value.trim().lowercase()
                else -> DEFAULT_LLAMA_CPP_FLASH_ATTENTION
            }
        }

        fun normalizeLlamaCppAdditionalArguments(values: List<String>): List<String> {
            return values
                .flatMap { value -> value.replace("\r\n", "\n").replace('\r', '\n').split('\n') }
                .toList()
        }
    }
}

class AppSettingsPersistenceException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class AppSettingsStore private constructor(
    context: Context,
    private val commitEditor: (SharedPreferences.Editor) -> Boolean,
) {
    constructor(context: Context) : this(context, { editor -> editor.commit() })

    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        // Process-wide cache so Settings, AppShell, Chat, and Auth share one source of truth.
        // (Per-instance cache previously broke language switching: Settings saved, shell kept stale cache.)
        processCache?.let { return it }
        synchronized(cacheLock) {
            processCache?.let { return it }
            val loaded = readFromPreferences()
            processCache = loaded
            return loaded
        }
    }

    fun save(settings: AppSettings) {
        synchronized(cacheLock) {
            val current = processCache ?: readFromPreferences()
            val normalized = normalizeForPersistence(settings)
            commitNormalizedLocked(normalized, rollback = current)
            processCache = normalized
        }
    }

    /**
     * Atomically mutate the latest process-wide settings snapshot.
     *
     * Callers must finish slow work before entering this callback. The transform and durable
     * commit share [cacheLock], so a field-scoped writer cannot replace unrelated values from a
     * snapshot captured before another AppSettingsStore instance committed its change.
     */
    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        synchronized(cacheLock) {
            val current = processCache ?: readFromPreferences()
            val normalized = normalizeForPersistence(transform(current))
            commitNormalizedLocked(normalized, rollback = current)
            processCache = normalized
            return normalized
        }
    }

    private fun normalizeForPersistence(settings: AppSettings): AppSettings {
        return settings.copy(
            localModelMaxTokens = AppSettings.normalizeLocalModelMaxTokens(settings.localModelMaxTokens),
            localModelTopK = AppSettings.normalizeLocalModelTopK(settings.localModelTopK),
            localModelTopP = AppSettings.normalizeLocalModelTopP(settings.localModelTopP),
            localModelTemperature = AppSettings.normalizeLocalModelTemperature(settings.localModelTemperature),
            localModelAccelerator = AppSettings.normalizeLocalModelAccelerator(settings.localModelAccelerator),
            localModelToolMode = AppSettings.normalizeLocalModelToolMode(settings.localModelToolMode),
            llamaCppRuntimeLane = AppSettings.normalizeLlamaCppRuntimeLane(settings.llamaCppRuntimeLane),
            llamaCppCacheTypeK = AppSettings.normalizeLlamaCppCacheType(settings.llamaCppCacheTypeK),
            llamaCppCacheTypeV = AppSettings.normalizeLlamaCppCacheType(settings.llamaCppCacheTypeV),
            llamaCppFlashAttention = AppSettings.normalizeLlamaCppFlashAttention(settings.llamaCppFlashAttention),
            llamaCppAdditionalArguments = AppSettings.normalizeLlamaCppAdditionalArguments(
                settings.llamaCppAdditionalArguments,
            ),
            customSystemPrompt = AppSettings.normalizeCustomSystemPrompt(settings.customSystemPrompt),
            uiFontScale = AppSettings.normalizeUiFontScale(settings.uiFontScale),
        )
    }

    private fun editorFor(settings: AppSettings): SharedPreferences.Editor {
        return preferences.edit()
            .putString(KEY_PROVIDER, settings.provider)
            .putString(KEY_BASE_URL, settings.baseUrl)
            .putString(KEY_MODEL, settings.model)
            .putString(KEY_CORR3XT_BASE_URL, settings.corr3xtBaseUrl)
            .putBoolean(KEY_DATA_SAVER_MODE, settings.dataSaverMode)
            .putBoolean(KEY_OFFLINE_AIRPLANE_MODE, settings.offlineAirplaneMode)
            .putBoolean(KEY_PORTAL_ENABLED, settings.portalEnabled)
            .putString(KEY_ON_DEVICE_BACKEND, settings.onDeviceBackend)
            .putString(KEY_LITERT_LM_SPECULATIVE_DECODING_MODE, settings.liteRtLmSpeculativeDecodingMode)
            .putString(KEY_LLAMA_CPP_RUNTIME_LANE, settings.llamaCppRuntimeLane)
            .putString(KEY_LLAMA_CPP_CACHE_TYPE_K, settings.llamaCppCacheTypeK)
            .putString(KEY_LLAMA_CPP_CACHE_TYPE_V, settings.llamaCppCacheTypeV)
            .putString(KEY_LLAMA_CPP_FLASH_ATTENTION, settings.llamaCppFlashAttention)
            .putString(KEY_LLAMA_CPP_ADDITIONAL_ARGUMENTS, JSONArray(settings.llamaCppAdditionalArguments).toString())
            .putInt(KEY_LOCAL_MODEL_MAX_TOKENS, settings.localModelMaxTokens)
            .putInt(KEY_LOCAL_MODEL_TOP_K, settings.localModelTopK)
            .putFloat(KEY_LOCAL_MODEL_TOP_P, settings.localModelTopP)
            .putFloat(KEY_LOCAL_MODEL_TEMPERATURE, settings.localModelTemperature)
            .putString(KEY_LOCAL_MODEL_ACCELERATOR, settings.localModelAccelerator)
            .putString(KEY_LOCAL_MODEL_TOOL_MODE, settings.localModelToolMode)
            .putBoolean(KEY_API_GENERATION_KNOBS_ENABLED, settings.apiGenerationKnobsEnabled)
            .putString(KEY_LANGUAGE_TAG, settings.languageTag)
            .putString(KEY_CUSTOM_SYSTEM_PROMPT, settings.customSystemPrompt)
            .putString(KEY_CHAT_DISPLAY_MODE, settings.chatDisplayMode)
            .putBoolean(KEY_KEYWORD_HIGHLIGHTING_ENABLED, settings.keywordHighlightingEnabled)
            .putString(KEY_THEME_PRIMARY_HEX, settings.themePrimaryHex)
            .putString(KEY_THEME_SECONDARY_HEX, settings.themeSecondaryHex)
            .putString(KEY_THEME_BACKGROUND_HEX, settings.themeBackgroundHex)
            .putString(KEY_THEME_SURFACE_HEX, settings.themeSurfaceHex)
            .putString(KEY_THEME_SURFACE_VARIANT_HEX, settings.themeSurfaceVariantHex)
            .putString(KEY_THEME_CARD_SHAPE, settings.themeCardShape)
            .putFloat(KEY_UI_FONT_SCALE, settings.uiFontScale)
    }

    private fun commitNormalizedLocked(settings: AppSettings, rollback: AppSettings) {
        val failure = runCatching {
            if (commitEditor(editorFor(settings))) null else {
                AppSettingsPersistenceException("Failed to commit Hermes app settings")
            }
        }.fold(
            onSuccess = { it },
            onFailure = { error ->
                if (error is AppSettingsPersistenceException) error else {
                    AppSettingsPersistenceException("Failed to commit Hermes app settings", error)
                }
            },
        )
        if (failure != null) {
            // SharedPreferences.Editor.commit() updates the process-memory map before it
            // reports the disk result. Restore the authoritative pre-commit snapshot so a
            // false/throw cannot leak the rejected values through a fresh preferences read.
            runCatching { editorFor(rollback).apply() }
            throw failure
        }
    }

    /**
     * Durably accept a foreground local-runtime handoff before its asynchronous
     * startup work begins. The caller may clear a persisted pending-download
     * intent only when this commit succeeds.
     */
    fun persistOnDeviceBackend(onDeviceBackend: String): Boolean {
        synchronized(cacheLock) {
            val current = processCache ?: readFromPreferences()
            val committed = runCatching {
                commitEditor(preferences.edit().putString(KEY_ON_DEVICE_BACKEND, onDeviceBackend))
            }.getOrDefault(false)
            if (committed) {
                processCache = current.copy(onDeviceBackend = onDeviceBackend)
            } else {
                // See commitNormalizedLocked: a failed commit can still have changed the
                // in-memory SharedPreferences value visible to other store instances.
                runCatching {
                    preferences.edit()
                        .putString(KEY_ON_DEVICE_BACKEND, current.onDeviceBackend)
                        .apply()
                }
            }
            return committed
        }
    }

    /** Force next load() to re-read disk (tests / rare recovery). */
    fun invalidateCache() {
        synchronized(cacheLock) {
            processCache = null
        }
    }

    private fun readFromPreferences(): AppSettings {
        val storedPrimary = preferences.getString(KEY_THEME_PRIMARY_HEX, AppSettings.DEFAULT_THEME_PRIMARY_HEX).orEmpty()
        val storedSecondary = preferences.getString(KEY_THEME_SECONDARY_HEX, AppSettings.DEFAULT_THEME_SECONDARY_HEX).orEmpty()
        val storedBackground = preferences.getString(KEY_THEME_BACKGROUND_HEX, AppSettings.DEFAULT_THEME_BACKGROUND_HEX).orEmpty()
        val storedSurface = preferences.getString(KEY_THEME_SURFACE_HEX, AppSettings.DEFAULT_THEME_SURFACE_HEX).orEmpty()
        val storedSurfaceVariant = preferences.getString(KEY_THEME_SURFACE_VARIANT_HEX, AppSettings.DEFAULT_THEME_SURFACE_VARIANT_HEX).orEmpty()
        val migrateLegacyDefaults = !preferences.getBoolean(KEY_EMERALD_THEME_MIGRATION_COMPLETE, false) &&
            storedPrimary.equals("#8C7BFF", ignoreCase = true) &&
            storedSecondary.equals("#C6A15B", ignoreCase = true) &&
            storedBackground.equals("#090B10", ignoreCase = true) &&
            storedSurface.equals("#11141C", ignoreCase = true) &&
            storedSurfaceVariant.equals("#1B202B", ignoreCase = true)
        if (!preferences.getBoolean(KEY_EMERALD_THEME_MIGRATION_COMPLETE, false)) {
            preferences.edit().putBoolean(KEY_EMERALD_THEME_MIGRATION_COMPLETE, true).commit()
        }
        return AppSettings(
            provider = preferences.getString(KEY_PROVIDER, "openrouter").orEmpty(),
            baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty(),
            model = preferences.getString(KEY_MODEL, "").orEmpty(),
            corr3xtBaseUrl = preferences.getString(KEY_CORR3XT_BASE_URL, "").orEmpty(),
            dataSaverMode = preferences.getBoolean(KEY_DATA_SAVER_MODE, false),
            offlineAirplaneMode = preferences.getBoolean(KEY_OFFLINE_AIRPLANE_MODE, false),
            portalEnabled = preferences.getBoolean(KEY_PORTAL_ENABLED, true),
            onDeviceBackend = preferences.getString(KEY_ON_DEVICE_BACKEND, "none").orEmpty(),
            liteRtLmSpeculativeDecodingMode = preferences.getString(
                KEY_LITERT_LM_SPECULATIVE_DECODING_MODE,
                "auto",
            ).orEmpty(),
            llamaCppRuntimeLane = AppSettings.normalizeLlamaCppRuntimeLane(
                preferences.getString(KEY_LLAMA_CPP_RUNTIME_LANE, AppSettings.DEFAULT_LLAMA_CPP_RUNTIME_LANE).orEmpty(),
            ),
            llamaCppCacheTypeK = AppSettings.normalizeLlamaCppCacheType(
                preferences.getString(KEY_LLAMA_CPP_CACHE_TYPE_K, AppSettings.DEFAULT_LLAMA_CPP_CACHE_TYPE).orEmpty(),
            ),
            llamaCppCacheTypeV = AppSettings.normalizeLlamaCppCacheType(
                preferences.getString(KEY_LLAMA_CPP_CACHE_TYPE_V, AppSettings.DEFAULT_LLAMA_CPP_CACHE_TYPE).orEmpty(),
            ),
            llamaCppFlashAttention = AppSettings.normalizeLlamaCppFlashAttention(
                preferences.getString(
                    KEY_LLAMA_CPP_FLASH_ATTENTION,
                    AppSettings.DEFAULT_LLAMA_CPP_FLASH_ATTENTION,
                ).orEmpty(),
            ),
            llamaCppAdditionalArguments = AppSettings.normalizeLlamaCppAdditionalArguments(
                runCatching {
                    val array = JSONArray(preferences.getString(KEY_LLAMA_CPP_ADDITIONAL_ARGUMENTS, "[]").orEmpty())
                    buildList {
                        repeat(array.length()) { index ->
                            if (!array.isNull(index)) add(array.optString(index))
                        }
                    }
                }.getOrDefault(emptyList()),
            ),
            localModelMaxTokens = AppSettings.normalizeLocalModelMaxTokens(
                preferences.getInt(KEY_LOCAL_MODEL_MAX_TOKENS, AppSettings.DEFAULT_LOCAL_MODEL_MAX_TOKENS),
            ),
            localModelTopK = AppSettings.normalizeLocalModelTopK(
                preferences.getInt(KEY_LOCAL_MODEL_TOP_K, AppSettings.DEFAULT_LOCAL_MODEL_TOP_K),
            ),
            localModelTopP = AppSettings.normalizeLocalModelTopP(
                preferences.getFloat(KEY_LOCAL_MODEL_TOP_P, AppSettings.DEFAULT_LOCAL_MODEL_TOP_P),
            ),
            localModelTemperature = AppSettings.normalizeLocalModelTemperature(
                preferences.getFloat(KEY_LOCAL_MODEL_TEMPERATURE, AppSettings.DEFAULT_LOCAL_MODEL_TEMPERATURE),
            ),
            localModelAccelerator = AppSettings.normalizeLocalModelAccelerator(
                preferences.getString(KEY_LOCAL_MODEL_ACCELERATOR, AppSettings.DEFAULT_LOCAL_MODEL_ACCELERATOR).orEmpty(),
            ),
            localModelToolMode = AppSettings.normalizeLocalModelToolMode(
                preferences.getString(KEY_LOCAL_MODEL_TOOL_MODE, AppSettings.DEFAULT_LOCAL_MODEL_TOOL_MODE).orEmpty(),
            ),
            apiGenerationKnobsEnabled = preferences.getBoolean(KEY_API_GENERATION_KNOBS_ENABLED, false),
            languageTag = preferences.getString(KEY_LANGUAGE_TAG, "en").orEmpty(),
            customSystemPrompt = AppSettings.normalizeCustomSystemPrompt(
                preferences.getString(KEY_CUSTOM_SYSTEM_PROMPT, "").orEmpty(),
            ),
            chatDisplayMode = preferences.getString(KEY_CHAT_DISPLAY_MODE, "compact").orEmpty(),
            keywordHighlightingEnabled = preferences.getBoolean(KEY_KEYWORD_HIGHLIGHTING_ENABLED, true),
            themePrimaryHex = if (migrateLegacyDefaults) AppSettings.DEFAULT_THEME_PRIMARY_HEX else storedPrimary,
            themeSecondaryHex = if (migrateLegacyDefaults) AppSettings.DEFAULT_THEME_SECONDARY_HEX else storedSecondary,
            themeBackgroundHex = if (migrateLegacyDefaults) AppSettings.DEFAULT_THEME_BACKGROUND_HEX else storedBackground,
            themeSurfaceHex = if (migrateLegacyDefaults) AppSettings.DEFAULT_THEME_SURFACE_HEX else storedSurface,
            themeSurfaceVariantHex = if (migrateLegacyDefaults) AppSettings.DEFAULT_THEME_SURFACE_VARIANT_HEX else storedSurfaceVariant,
            themeCardShape = preferences.getString(KEY_THEME_CARD_SHAPE, "rounded").orEmpty(),
            uiFontScale = AppSettings.normalizeUiFontScale(
                preferences.getFloat(KEY_UI_FONT_SCALE, AppSettings.DEFAULT_UI_FONT_SCALE),
            ),
        )
    }

    fun exportBundleJson(): JSONObject = AppSettings.exportBundle(load())

    fun importBundleJson(bundle: JSONObject): AppSettings {
        val settingsJson = bundle.optJSONObject("settings") ?: bundle
        val expertArgumentsExplicitlyExcluded = bundle.has("settings") && (
            bundle.optBoolean("expert_arguments_included", true).not() ||
                bundle.optJSONArray("excluded_portable_fields")?.let { excluded ->
                    (0 until excluded.length()).any { index ->
                        excluded.optString(index) == "llama_cpp_additional_arguments"
                    }
                } == true
            )
        return update { current ->
            AppSettings.fromJson(settingsJson, current).let { parsed ->
                if (expertArgumentsExplicitlyExcluded) {
                    parsed.copy(llamaCppAdditionalArguments = emptyList())
                } else {
                    parsed
                }
            }
        }
    }

    companion object {
        private val cacheLock = Any()
        @Volatile
        private var processCache: AppSettings? = null

        internal fun withCommitterForTest(
            context: Context,
            commitEditor: (SharedPreferences.Editor) -> Boolean,
        ): AppSettingsStore = AppSettingsStore(context, commitEditor)

        private const val PREFS_NAME = "hermes_android_settings"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_CORR3XT_BASE_URL = "corr3xt_base_url"
        private const val KEY_DATA_SAVER_MODE = "data_saver_mode"
        private const val KEY_OFFLINE_AIRPLANE_MODE = "offline_airplane_mode"
        private const val KEY_PORTAL_ENABLED = "portal_enabled"
        private const val KEY_ON_DEVICE_BACKEND = "on_device_backend"
        private const val KEY_LITERT_LM_SPECULATIVE_DECODING_MODE = "litert_lm_speculative_decoding_mode"
        private const val KEY_LLAMA_CPP_RUNTIME_LANE = "llama_cpp_runtime_lane"
        private const val KEY_LLAMA_CPP_CACHE_TYPE_K = "llama_cpp_cache_type_k"
        private const val KEY_LLAMA_CPP_CACHE_TYPE_V = "llama_cpp_cache_type_v"
        private const val KEY_LLAMA_CPP_FLASH_ATTENTION = "llama_cpp_flash_attention"
        private const val KEY_LLAMA_CPP_ADDITIONAL_ARGUMENTS = "llama_cpp_additional_arguments"
        private const val KEY_LOCAL_MODEL_MAX_TOKENS = "local_model_max_tokens"
        private const val KEY_LOCAL_MODEL_TOP_K = "local_model_top_k"
        private const val KEY_LOCAL_MODEL_TOP_P = "local_model_top_p"
        private const val KEY_LOCAL_MODEL_TEMPERATURE = "local_model_temperature"
        private const val KEY_LOCAL_MODEL_ACCELERATOR = "local_model_accelerator"
        private const val KEY_LOCAL_MODEL_TOOL_MODE = "local_model_tool_mode"
        private const val KEY_API_GENERATION_KNOBS_ENABLED = "api_generation_knobs_enabled"
        private const val KEY_LANGUAGE_TAG = "language_tag"
        private const val KEY_CUSTOM_SYSTEM_PROMPT = "custom_system_prompt"
        private const val KEY_CHAT_DISPLAY_MODE = "chat_display_mode"
        private const val KEY_KEYWORD_HIGHLIGHTING_ENABLED = "keyword_highlighting_enabled"
        private const val KEY_THEME_PRIMARY_HEX = "theme_primary_hex"
        private const val KEY_THEME_SECONDARY_HEX = "theme_secondary_hex"
        private const val KEY_THEME_BACKGROUND_HEX = "theme_background_hex"
        private const val KEY_THEME_SURFACE_HEX = "theme_surface_hex"
        private const val KEY_THEME_SURFACE_VARIANT_HEX = "theme_surface_variant_hex"
        private const val KEY_THEME_CARD_SHAPE = "theme_card_shape"
        private const val KEY_UI_FONT_SCALE = "ui_font_scale"
        private const val KEY_EMERALD_THEME_MIGRATION_COMPLETE = "emerald_theme_migration_complete"
    }
}
