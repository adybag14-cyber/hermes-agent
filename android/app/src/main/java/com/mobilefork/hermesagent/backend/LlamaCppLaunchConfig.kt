package com.mobilefork.hermesagent.backend

import java.security.MessageDigest
import java.util.Locale

internal enum class LlamaCppRuntimeLane(val persistedValue: String) {
    STABLE("stable"),
    TURBOQUANT("turboquant");

    companion object {
        fun fromPersistedValue(value: String?): LlamaCppRuntimeLane {
            return when (value.orEmpty().trim().lowercase(Locale.US)) {
                TURBOQUANT.persistedValue, "experimental" -> TURBOQUANT
                else -> STABLE
            }
        }
    }
}

internal data class LlamaCppLaunchValidation(
    val valid: Boolean,
    val error: String = "",
)

/**
 * A validated, argument-vector-level description of a llama-server launch.
 *
 * Additional arguments are already tokenized by the settings layer: one list item becomes
 * exactly one argv entry. They are never concatenated as raw shell source. The controller
 * quotes every token again at the shell boundary because the stable Termux lane still needs
 * to start through its packaged shell.
 */
internal data class LlamaCppLaunchConfig(
    val lane: LlamaCppRuntimeLane = LlamaCppRuntimeLane.STABLE,
    val cacheTypeK: String = DEFAULT_VALUE,
    val cacheTypeV: String = DEFAULT_VALUE,
    val flashAttention: String = DEFAULT_VALUE,
    val additionalArguments: List<String> = emptyList(),
) {
    private val normalizedCacheTypeK: String
        get() = cacheTypeK.trim().lowercase(Locale.US)

    private val normalizedCacheTypeV: String
        get() = cacheTypeV.trim().lowercase(Locale.US)

    private val normalizedFlashAttention: String
        get() = flashAttention.trim().lowercase(Locale.US)

    fun validate(): LlamaCppLaunchValidation {
        val allowedCacheTypes = when (lane) {
            LlamaCppRuntimeLane.STABLE -> STABLE_CACHE_TYPES
            LlamaCppRuntimeLane.TURBOQUANT -> TURBOQUANT_CACHE_TYPES
        }
        if (normalizedCacheTypeK !in allowedCacheTypes) {
            return invalid(
                "K-cache type '$cacheTypeK' is not supported by the ${lane.persistedValue} lane",
            )
        }
        if (normalizedCacheTypeV !in allowedCacheTypes) {
            return invalid(
                "V-cache type '$cacheTypeV' is not supported by the ${lane.persistedValue} lane",
            )
        }
        if (normalizedFlashAttention !in FLASH_ATTENTION_VALUES) {
            return invalid(
                "Flash Attention mode '$flashAttention' is invalid; choose default, auto, on, or off",
            )
        }

        val usesQuantizedVCache = normalizedCacheTypeV in QUANTIZED_CACHE_TYPES
        val usesTurboCache = normalizedCacheTypeK in TURBO_CACHE_TYPES ||
            normalizedCacheTypeV in TURBO_CACHE_TYPES
        if ((usesQuantizedVCache || usesTurboCache) && normalizedFlashAttention == "off") {
            val feature = if (usesTurboCache) "TurboQuant cache types" else "Quantized V-cache"
            return invalid("$feature require Flash Attention; choose default, auto, or on instead of off")
        }

        if (additionalArguments.size > MAX_ADDITIONAL_ARGUMENTS) {
            return invalid(
                "At most $MAX_ADDITIONAL_ARGUMENTS additional llama.cpp arguments are allowed",
            )
        }
        var totalChars = 0
        var activeFlag: String? = null
        var activeFlagReviewed = false
        var remainingReviewedValues = 0
        additionalArguments.forEachIndexed { index, token ->
            if (token.isBlank()) {
                return invalid("Additional argument ${index + 1} is blank")
            }
            if (token.length > MAX_ADDITIONAL_ARGUMENT_CHARS) {
                return invalid(
                    "Additional argument ${index + 1} exceeds $MAX_ADDITIONAL_ARGUMENT_CHARS characters",
                )
            }
            totalChars += token.length
            if (totalChars > MAX_ADDITIONAL_ARGUMENT_TOTAL_CHARS) {
                return invalid(
                    "Additional llama.cpp arguments exceed $MAX_ADDITIONAL_ARGUMENT_TOTAL_CHARS characters in total",
                )
            }
            if (token.any { character -> Character.isISOControl(character) }) {
                return invalid("Additional argument ${index + 1} contains a control character")
            }
            if (token == "--") {
                return invalid("Additional argument ${index + 1} cannot be the positional-argument separator --")
            }
            if (token.startsWith("-")) {
                val candidateFlagName = canonicalFlagName(token.substringBefore('='))
                appOwnedFlagOwner(candidateFlagName)?.let { owner ->
                    return invalid("Additional flag '$candidateFlagName' conflicts with Hermes-managed $owner")
                }
            }
            if ('=' in token && token.startsWith("-")) {
                return invalid(
                    "Additional argument ${index + 1} uses --flag=value syntax, which this llama.cpp parser rejects; put the flag and value on separate lines",
                )
            }
            val signedNumericValue = SIGNED_NUMERIC_VALUE.matches(token)
            val isFlag = !signedNumericValue && FLAG_TOKEN.matches(token)
            if (isFlag) {
                if (activeFlagReviewed && remainingReviewedValues > 0) {
                    return invalid(reviewedArityError(activeFlag.orEmpty(), remainingReviewedValues))
                }
                val candidateFlagName = canonicalFlagName(token)
                val reviewedArity = reviewedFlagArity(candidateFlagName)
                activeFlag = candidateFlagName
                activeFlagReviewed = reviewedArity != null
                remainingReviewedValues = reviewedArity ?: 0
                return@forEachIndexed
            }
            if (token.startsWith("-") && !signedNumericValue) {
                return invalid(
                    "Additional argument ${index + 1} is not a valid llama.cpp flag or signed numeric value",
                )
            }
            if (activeFlag == null) {
                return invalid(
                    "Additional argument ${index + 1} is an orphan positional value; each value must immediately follow its flag on the next line",
                )
            }
            if (activeFlagReviewed) {
                if (remainingReviewedValues == 0) {
                    return invalid("Additional flag '$activeFlag' does not accept a value")
                }
                remainingReviewedValues -= 1
                if (remainingReviewedValues == 0) {
                    activeFlag = null
                    activeFlagReviewed = false
                }
            }
            // Unreviewed flags intentionally remain open until the next flag. This preserves
            // forward-compatible expert argv, including options which accept multiple values.
            // The selected pinned native parser remains the final semantic authority.
        }
        if (activeFlagReviewed && remainingReviewedValues > 0) {
            return invalid(reviewedArityError(activeFlag.orEmpty(), remainingReviewedValues))
        }
        return LlamaCppLaunchValidation(valid = true)
    }

    /** Arguments controlled by the advanced settings, excluding the stable defaults. */
    fun advancedArgumentTokens(): List<String> {
        val validation = validate()
        check(validation.valid) { validation.error }
        return buildList {
            if (normalizedCacheTypeK != DEFAULT_VALUE) {
                add("--cache-type-k")
                add(normalizedCacheTypeK)
            }
            if (normalizedCacheTypeV != DEFAULT_VALUE) {
                add("--cache-type-v")
                add(normalizedCacheTypeV)
            }
            if (normalizedFlashAttention != DEFAULT_VALUE) {
                add("--flash-attn")
                add(normalizedFlashAttention)
            }
            addAll(additionalArguments)
        }
    }

    /**
     * A one-way identity for process reuse. Raw arguments do not appear in logs or status text.
     */
    fun fingerprint(): String {
        val canonical = buildList {
            add(lane.persistedValue)
            add(normalizedCacheTypeK)
            add(normalizedCacheTypeV)
            add(normalizedFlashAttention)
            addAll(additionalArguments)
        }.joinToString(separator = "\u0000") { value -> "${value.length}:$value" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
    }

    companion object {
        const val DEFAULT_VALUE = "default"
        const val MAX_ADDITIONAL_ARGUMENTS = 64
        const val MAX_ADDITIONAL_ARGUMENT_CHARS = 256
        const val MAX_ADDITIONAL_ARGUMENT_TOTAL_CHARS = 4_096

        private val STABLE_CACHE_TYPES = setOf(
            DEFAULT_VALUE,
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
        private val TURBO_CACHE_TYPES = setOf("turbo2", "turbo3", "turbo4")
        private val TURBOQUANT_CACHE_TYPES = STABLE_CACHE_TYPES + TURBO_CACHE_TYPES
        private val QUANTIZED_CACHE_TYPES = setOf(
            "q8_0",
            "q4_0",
            "q4_1",
            "iq4_nl",
            "q5_0",
            "q5_1",
        ) + TURBO_CACHE_TYPES
        private val FLASH_ATTENTION_VALUES = setOf(DEFAULT_VALUE, "auto", "on", "off")

        private val FLAG_TOKEN = Regex("^-{1,2}[A-Za-z0-9][A-Za-z0-9_-]*$")
        private val SIGNED_NUMERIC_VALUE = Regex(
            "^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$",
        )

        /**
         * Reviewed parser arities for useful expert options shared by the pinned lanes.
         *
         * This is deliberately not an allow-list. Unknown, non-owned options remain available
         * so advanced users can exercise backend-specific and future flags. A reviewed entry
         * lets Hermes catch a missing or surplus argv value before it restarts the server.
         */
        private val COMMON_REVIEWED_FLAG_ARITIES = buildMap {
            listOf(
                "--perf",
                "--no-perf",
                "--context-shift",
                "--no-context-shift",
                "-cb",
                "--cont-batching",
                "-nocb",
                "--no-cont-batching",
                "--cache-prompt",
                "--no-cache-prompt",
                "--check-tensors",
            )
                .forEach { put(it, 0) }
            listOf(
                "-tb",
                "--threads-batch",
                "-C",
                "--cpu-mask",
                "-Cr",
                "--cpu-range",
                "--cpu-strict",
                "--prio",
                "--poll",
                "-Cb",
                "--cpu-mask-batch",
                "-Crb",
                "--cpu-range-batch",
                "--cpu-strict-batch",
                "--prio-batch",
                "--poll-batch",
                "--keep",
                "--numa",
                "-to",
                "--timeout",
                "--sse-ping-interval",
                "--threads-http",
                "--cache-reuse",
                "-sps",
                "--slot-prompt-similarity",
                "--tags",
            ).forEach { put(it, 1) }
        }

        private val TURBOQUANT_REVIEWED_FLAG_ARITIES = buildMap {
            listOf("--slot-cache-key-similarity", "--slot-cache-key-min-prefix")
                .forEach { put(it, 1) }
        }

        private val APP_OWNED_FLAGS = buildMap {
            listOf("-m", "--model").forEach { put(it, "model selection") }
            listOf("--host", "--port").forEach { put(it, "loopback server binding") }
            listOf("--api-key", "--api-key-file").forEach { put(it, "API security") }
            listOf("--ssl-key-file", "--ssl-cert-file").forEach { put(it, "TLS configuration") }
            listOf(
                "-mu",
                "--model-url",
                "-mmu",
                "--mmproj-url",
                "-hfr",
                "--hf-repo",
                "-hff",
                "--hf-file",
                "-hft",
                "--hf-token",
                "-md",
                "--model-draft",
                "--model-url-draft",
                "-hfrd",
                "--hf-repo-draft",
                "-hffd",
                "--hf-file-draft",
                "-mv",
                "--model-vocoder",
                "--model-url-vocoder",
                "-hfrv",
                "--hf-repo-vocoder",
                "-hffv",
                "--hf-file-vocoder",
            ).forEach { put(it, "model download and artifact selection") }
            listOf("--path", "--public-path", "--url-prefix", "--webui", "--no-webui")
                .forEach { put(it, "public-path and application UI routing") }
            listOf("--rpc", "--rpc-server-host", "--rpc-server-port")
                .forEach { put(it, "runtime endpoint routing") }
            listOf(
                "--reuse-port",
                "--cors-origins",
                "--cors-methods",
                "--cors-headers",
                "--cors-credentials",
                "--no-cors-credentials",
                "--api-prefix",
            ).forEach { put(it, "loopback network and CORS policy") }
            listOf(
                "--ui",
                "--no-ui",
                "--ui-config",
                "--webui-config",
                "--ui-config-file",
                "--webui-config-file",
                "--ui-mcp-proxy",
                "--webui-mcp-proxy",
                "--no-ui-mcp-proxy",
                "--no-webui-mcp-proxy",
            ).forEach { put(it, "application UI and MCP proxy routing") }
            listOf(
                "--tools",
                "-ag",
                "--agent",
                "-no-ag",
                "--no-agent",
                "--mcp-servers-config",
                "--mcp-servers-json",
            ).forEach { put(it, "privileged server tools and MCP execution") }
            listOf(
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
            ).forEach { put(it, "model download, multiplexing, and RAM admission") }
            listOf(
                "--embd-gemma-default",
                "--gpt-oss-20b-default",
                "--gpt-oss-120b-default",
                "--vision-gemma-4b-default",
                "--vision-gemma-12b-default",
            ).forEach { put(it, "server presets, model download, and resource policy") }

            listOf(
                "--server-base",
                "-h",
                "--help",
                "--usage",
                "--version",
                "-cl",
                "--cache-list",
                "--completion-bash",
                "--list-devices",
            ).forEach { put(it, "owned-process startup and readiness") }
            listOf(
                "--swa-full",
                "-ctxcp",
                "--ctx-checkpoints",
                "--swa-checkpoints",
                "-cram",
                "--cache-ram",
                "--cache-idle",
                "--cache-idle-seconds",
                "--mlock",
                "--mmap",
                "--no-mmap",
                "-dio",
                "--direct-io",
                "-ndio",
                "--no-direct-io",
                "-lm",
                "--load-mode",
            ).forEach { put(it, "RAM admission and paging policy") }
            listOf(
                "-dev",
                "--device",
                "-ngl",
                "--gpu-layers",
                "--n-gpu-layers",
                "-sm",
                "--split-mode",
                "-ts",
                "--tensor-split",
                "-mg",
                "--main-gpu",
                "-ot",
                "--override-tensor",
                "--no-host",
                "-kvo",
                "--kv-offload",
                "-nkvo",
                "--no-kv-offload",
                "--repack",
                "-nr",
                "--no-repack",
                "--op-offload",
                "--no-op-offload",
                "-fit",
                "-fitp",
                "-fitt",
                "-fitc",
                "-cmoe",
                "-ncmoe",
                "--n-cpu-moe",
            ).forEach { put(it, "device placement and memory policy") }
            listOf(
                "-mm",
                "--mmproj",
                "--mmproj-auto",
                "--no-mmproj",
                "--no-mmproj-auto",
                "--mmproj-offload",
                "--no-mmproj-offload",
                "--lora",
                "--lora-scaled",
                "--lora-init-without-apply",
                "--control-vector",
                "--control-vector-scaled",
                "--control-vector-layer-range",
                "--override-kv",
            ).forEach { put(it, "additional model artifacts and model metadata") }
            listOf(
                "--chat-template",
                "--chat-template-file",
                "--chat-template-kwargs",
                "--reasoning-format",
                "--reasoning-budget",
                "--skip-chat-parsing",
                "--no-skip-chat-parsing",
                "--prefill-assistant",
                "--no-prefill-assistant",
            ).forEach { put(it, "chat and tool protocol") }
            listOf(
                "--props",
                "--metrics",
                "--slots",
                "--no-slots",
                "--slot-save-path",
                "--media-path",
                "-p",
                "--prompt",
                "-f",
                "--file",
                "--system-prompt-file",
                "--prompt-cache",
                "--prompt-cache-all",
                "--prompt-cache-ro",
                "--log-file",
                "--log-prompts",
                "--log-prompts-dir",
                "--sleep-idle-seconds",
            ).forEach { put(it, "endpoint, file, and privacy policy") }

            listOf("-ctk", "--cache-type-k", "-ctv", "--cache-type-v")
                .forEach { put(it, "structured KV-cache settings") }
            listOf("-fa", "--flash-attn").forEach { put(it, "structured Flash Attention settings") }
            listOf("-c", "--ctx-size").forEach { put(it, "memory-preflight context sizing") }
            listOf("-np", "--parallel").forEach { put(it, "single-slot server operation") }
            listOf("-t", "--threads").forEach { put(it, "mobile CPU thread sizing") }
            listOf("-b", "--batch-size", "-ub", "--ubatch-size")
                .forEach { put(it, "mobile batch sizing") }
            listOf("--warmup", "--no-warmup").forEach { put(it, "startup warmup policy") }
            listOf("--jinja", "--no-jinja").forEach { put(it, "tool-call chat-template support") }
            listOf("--embedding", "--embeddings", "--rerank", "--reranking")
                .forEach { put(it, "chat-completions endpoint mode") }
        }

        private val APP_OWNED_FLAG_PREFIXES = listOf(
            "--mcp-" to "privileged MCP execution",
            "--spec-" to "speculative-model loading and RAM admission",
            "--models-" to "model multiplexing and RAM admission",
            "--model-url" to "model download and artifact selection",
            "--fim-" to "FIM model download, endpoint, context, and RAM admission",
            "--hf-" to "model download and artifact selection",
            "--cors-" to "loopback network and CORS policy",
            "--rpc-" to "runtime endpoint routing",
            "--lookup-cache-" to "unpreflighted speculative cache loading",
            "--fit" to "device placement and memory policy",
            "--gpu-" to "device placement and memory policy",
            "--n-gpu-" to "device placement and memory policy",
            "--rope-" to "model metadata and context policy",
            "--yarn-" to "model metadata and context policy",
            "--control-vector" to "additional model artifacts and model metadata",
            "--lora" to "additional model artifacts and model metadata",
            "--mmproj" to "additional model artifacts and model metadata",
            "--override-" to "additional model artifacts and model metadata",
            "--chat-template" to "chat and tool protocol",
            "--reasoning" to "chat and tool protocol",
            "--prompt-cache" to "endpoint, file, and privacy policy",
            "--log-prompts" to "endpoint, file, and privacy policy",
            "--cache-ram" to "RAM admission and paging policy",
            "--ctx-checkpoint" to "RAM admission and paging policy",
            "--swa-checkpoint" to "RAM admission and paging policy",
            "--cpu-moe" to "device placement and memory policy",
            "--moe" to "device placement and memory policy",
        )

        private fun appOwnedFlagOwner(flag: String): String? {
            APP_OWNED_FLAGS[flag]?.let { return it }
            return APP_OWNED_FLAG_PREFIXES.firstOrNull { (prefix, _) -> flag.startsWith(prefix) }?.second
        }

        private fun canonicalFlagName(flag: String): String {
            // llama.cpp short aliases are case-sensitive: -c is context size while -C is a CPU
            // mask. Long names are normalized only for conservative app-ownership matching.
            return if (flag.startsWith("--")) flag.lowercase(Locale.US) else flag
        }

        private fun LlamaCppLaunchConfig.reviewedFlagArity(flag: String): Int? {
            COMMON_REVIEWED_FLAG_ARITIES[flag]?.let { return it }
            return if (lane == LlamaCppRuntimeLane.TURBOQUANT) {
                TURBOQUANT_REVIEWED_FLAG_ARITIES[flag]
            } else {
                null
            }
        }

        private fun reviewedArityError(flag: String, remainingValues: Int): String {
            val noun = if (remainingValues == 1) "value" else "values"
            return "Additional flag '$flag' requires $remainingValues more argv $noun"
        }

        fun fromPersistedValues(
            lane: String,
            cacheTypeK: String,
            cacheTypeV: String,
            flashAttention: String,
            additionalArguments: List<String>,
        ): LlamaCppLaunchConfig {
            return LlamaCppLaunchConfig(
                lane = LlamaCppRuntimeLane.fromPersistedValue(lane),
                cacheTypeK = cacheTypeK,
                cacheTypeV = cacheTypeV,
                flashAttention = flashAttention,
                additionalArguments = additionalArguments.toList(),
            )
        }

        private fun invalid(message: String): LlamaCppLaunchValidation {
            return LlamaCppLaunchValidation(valid = false, error = message)
        }
    }
}
