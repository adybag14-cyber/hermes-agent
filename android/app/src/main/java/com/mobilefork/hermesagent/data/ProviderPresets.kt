package com.mobilefork.hermesagent.data

import com.mobilefork.hermesagent.api.HermesEndpointUrl

data class ProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val modelHint: String,
    val apiKeyUrl: String = "",
    val fallbackSetupUrls: List<String> = emptyList(),
)

data class ParsedProviderCredential(
    val apiKey: String,
    val sourceLabel: String = "",
) {
    val importedFromEnvLine: Boolean
        get() = sourceLabel.isNotBlank()
}

data class ProviderSetupTarget(
    val providerId: String,
    val url: String,
    val index: Int,
    val total: Int,
) {
    val displayIndex: Int
        get() = index + 1

    val nextIndex: Int
        get() = if (total <= 0) 0 else (index + 1) % total
}

data class ModelSelectionPreset(
    val id: String,
    val label: String,
    val description: String,
)

object ProviderPresets {
    val firstClassLocalModels = listOf(
        ModelSelectionPreset(
            id = "qwen3.5-0.8b-q4-k-m",
            label = "Qwen3.5 0.8B Q4_K_M (GGUF)",
            description = "Release-certified small GGUF for fast llama.cpp chat and native tool checks.",
        ),
        ModelSelectionPreset(
            id = "minicpm5-1b-fable5-q4-k-m",
            label = "MiniCPM5 1B Fable5 Q4_K_M (GGUF)",
            description = "Release-certified compact thinking model for the embedded llama.cpp runtime.",
        ),
        ModelSelectionPreset(
            id = "minicpm5-1b-web-litert-lm",
            label = "MiniCPM 5 1B mobile (LiteRT-LM)",
            description = "Release-certified mobile LiteRT-LM package with an Android-safe chat template.",
        ),
        ModelSelectionPreset(
            id = "vibethinker-3b-litert-lm",
            label = "VibeThinker 3B (LiteRT-LM)",
            description = "Release-certified LiteRT-LM reasoning model for high-memory devices.",
        ),
    )

    val defaults = listOf(
        ProviderPreset(
            id = "openrouter",
            label = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            modelHint = "anthropic/claude-sonnet-4",
            apiKeyUrl = "https://openrouter.ai/settings/keys",
            fallbackSetupUrls = listOf(
                "https://openrouter.ai/keys",
                "https://openrouter.ai/docs/api-keys",
                "https://openrouter.ai/docs/quickstart",
            ),
        ),
        ProviderPreset(
            id = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            modelHint = "gpt-4.1",
            apiKeyUrl = "https://platform.openai.com/settings/organization/api-keys",
            fallbackSetupUrls = listOf("https://platform.openai.com/docs/quickstart"),
        ),
        ProviderPreset(
            id = "codex",
            label = "Codex / OpenAI Responses",
            baseUrl = "https://api.openai.com/v1",
            modelHint = "gpt-5",
            apiKeyUrl = "https://platform.openai.com/settings/organization/api-keys",
            fallbackSetupUrls = listOf(
                "https://developers.openai.com/api/docs/guides/migrate-to-responses",
                "https://developers.openai.com/api/docs/guides/realtime",
                "https://developers.openai.com/codex/auth",
                "https://developers.openai.com/codex/config-reference",
            ),
        ),
        ProviderPreset(
            id = "chatgpt-web",
            label = "ChatGPT Web",
            baseUrl = "https://chatgpt.com/backend-api/f",
            modelHint = "gpt-5-thinking",
            apiKeyUrl = "https://chatgpt.com/",
            // Prefer login/console pages for in-app WebView subscription flows.
            fallbackSetupUrls = listOf("https://chatgpt.com/#settings"),
        ),
        ProviderPreset(
            id = "anthropic",
            label = "Claude / Anthropic",
            baseUrl = "https://api.anthropic.com",
            modelHint = "claude-sonnet-4",
            apiKeyUrl = "https://console.anthropic.com/settings/keys",
            fallbackSetupUrls = listOf("https://docs.anthropic.com/claude/docs/quickstart-guide"),
        ),
        ProviderPreset(
            id = "gemini",
            label = "Gemini / Google AI Studio",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            modelHint = "gemini-2.5-pro",
            apiKeyUrl = "https://aistudio.google.com/apikey",
            fallbackSetupUrls = listOf("https://ai.google.dev/gemini-api/docs/api-key"),
        ),
        ProviderPreset(
            id = "groq",
            label = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            modelHint = "openai/gpt-oss-20b",
            apiKeyUrl = "https://console.groq.com/docs/quickstart",
            fallbackSetupUrls = listOf("https://console.groq.com/docs/"),
        ),
        ProviderPreset(
            id = "mistral",
            label = "Mistral AI",
            baseUrl = "https://api.mistral.ai/v1",
            modelHint = "mistral-large-latest",
            apiKeyUrl = "https://docs.mistral.ai/getting-started/quickstart/",
            fallbackSetupUrls = listOf("https://console.mistral.ai/"),
        ),
        ProviderPreset(
            id = "perplexity",
            label = "Perplexity Agent API",
            baseUrl = "https://api.perplexity.ai/v1",
            modelHint = "openai/gpt-5-mini",
            apiKeyUrl = "https://docs.perplexity.ai/docs/grounded-llm/openai-compatibility",
            fallbackSetupUrls = listOf("https://docs.perplexity.ai/getting-started/quickstart"),
        ),
        ProviderPreset(
            id = "cerebras",
            label = "Cerebras Inference",
            baseUrl = "https://api.cerebras.ai/v1",
            modelHint = "",
            apiKeyUrl = "https://inference-docs.cerebras.ai/resources/openai",
            fallbackSetupUrls = listOf("https://cloud.cerebras.ai/"),
        ),
        ProviderPreset(
            id = "together",
            label = "Together AI",
            baseUrl = "https://api.together.xyz/v1",
            modelHint = "",
            apiKeyUrl = "https://docs.together.ai/docs/openai-api-compatibility",
            fallbackSetupUrls = listOf("https://api.together.ai/settings/api-keys"),
        ),
        ProviderPreset(
            id = "fireworks",
            label = "Fireworks AI",
            baseUrl = "https://api.fireworks.ai/inference/v1",
            modelHint = "accounts/fireworks/models/llama-v3p1-8b-instruct",
            apiKeyUrl = "https://docs.fireworks.ai/tools-sdks/openai-compatibility",
            fallbackSetupUrls = listOf("https://fireworks.ai/account/api-keys"),
        ),
        ProviderPreset(
            id = "deepinfra",
            label = "DeepInfra",
            baseUrl = "https://api.deepinfra.com/v1/openai",
            modelHint = "",
            apiKeyUrl = "https://docs.deepinfra.com/",
            fallbackSetupUrls = listOf("https://deepinfra.com/dash/api_keys"),
        ),
        ProviderPreset(
            id = "alibaba",
            label = "Qwen Cloud / DashScope API key",
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            modelHint = "qwen3.6-plus",
            apiKeyUrl = "https://docs.qwencloud.com/developer-guides/administration/api-keys",
            fallbackSetupUrls = listOf(
                "https://modelstudio.console.alibabacloud.com/?tab=playground",
                "https://www.alibabacloud.com/help/en/model-studio/get-api-key",
                "https://docs.qwencloud.com/api-reference/preparation/api-key",
                "https://home.qwencloud.com/api-keys",
                "https://account.alibabacloud.com/login/login.htm",
            ),
        ),
        ProviderPreset(
            id = "alibaba-coding-plan",
            label = "Qwen Coding Plan",
            baseUrl = "https://coding-intl.dashscope.aliyuncs.com/v1",
            modelHint = "qwen3.6-plus",
            apiKeyUrl = "https://docs.qwencloud.com/coding-plan/overview",
            fallbackSetupUrls = listOf(
                "https://modelstudio.console.alibabacloud.com/?tab=playground",
                "https://www.alibabacloud.com/help/en/model-studio/coding-plan",
                "https://docs.qwencloud.com/coding-plan/tools/cline",
                "https://home.qwencloud.com/api-keys",
                "https://qwenlm.github.io/qwen-code-docs/en/users/configuration/model-providers/",
                "https://qwenlm.github.io/qwen-code-docs/en/users/configuration/auth/",
            ),
        ),
        ProviderPreset(
            id = "qwen-oauth",
            label = "Qwen OAuth / Qwen Chat token (legacy)",
            baseUrl = "https://portal.qwen.ai/v1",
            modelHint = "qwen3-coder-plus",
            apiKeyUrl = "https://qwenlm.github.io/qwen-code-docs/en/users/configuration/auth/",
            fallbackSetupUrls = listOf(
                "https://docs.qwencloud.com/api-reference/preparation/api-key",
                "https://docs.qwencloud.com/developer-guides/getting-started/first-api-call",
                "https://home.qwencloud.com/api-keys",
                "https://qwen.ai/apiplatform",
                "https://chat.qwen.ai/",
            ),
        ),
        ProviderPreset(
            id = "zai",
            label = "Z.AI / GLM",
            baseUrl = "https://api.z.ai/api/paas/v4",
            modelHint = "glm-5.1",
            apiKeyUrl = "https://z.ai/manage-apikey/apikey-list",
            fallbackSetupUrls = listOf(
                "https://z.ai/manage-apikey/apikey-list",
                "https://docs.z.ai/guides/",
                "https://open.bigmodel.cn/usercenter/apikeys",
            ),
        ),
        ProviderPreset(
            id = "zai-coding-plan",
            label = "Z.AI Coding Plan",
            baseUrl = "https://api.z.ai/api/coding/paas/v4",
            modelHint = "glm-5.1",
            apiKeyUrl = "https://z.ai/manage-apikey/apikey-list",
            fallbackSetupUrls = listOf(
                "https://docs.z.ai/devpack/quick-start",
                "https://docs.z.ai/guides/",
            ),
        ),
        ProviderPreset(
            id = "bigmodel",
            label = "BigModel CN / 智谱",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            modelHint = "glm-4-plus",
            apiKeyUrl = "https://open.bigmodel.cn/usercenter/apikeys",
            fallbackSetupUrls = listOf(
                "https://open.bigmodel.cn/usercenter/apikeys",
                "https://open.bigmodel.cn/login",
                "https://docs.bigmodel.cn/",
                "https://z.ai/manage-apikey/apikey-list",
            ),
        ),
        ProviderPreset(
            id = "xai",
            label = "xAI / Grok",
            baseUrl = "https://api.x.ai/v1",
            modelHint = "grok-3",
            apiKeyUrl = "https://console.x.ai/",
            fallbackSetupUrls = listOf(
                "https://console.x.ai/",
                "https://docs.x.ai/docs/overview",
                "https://accounts.x.ai/",
            ),
        ),
        ProviderPreset(
            id = "xai-oauth",
            label = "xAI Grok OAuth",
            baseUrl = "https://api.x.ai/v1",
            modelHint = "grok-3",
            apiKeyUrl = "https://console.x.ai/",
            fallbackSetupUrls = listOf(
                "https://auth.x.ai/",
                "https://console.x.ai/",
                "https://docs.x.ai/docs/overview",
            ),
        ),
        ProviderPreset(
            id = "openai-codex",
            label = "OpenAI Codex (device OAuth)",
            baseUrl = "https://chatgpt.com/backend-api/codex",
            modelHint = "gpt-5",
            apiKeyUrl = "https://auth.openai.com/codex/device",
            fallbackSetupUrls = listOf(
                "https://auth.openai.com/codex/device",
                "https://chatgpt.com/",
                "https://platform.openai.com/api-keys",
            ),
        ),
        ProviderPreset(
            id = "nous",
            label = "Nous",
            baseUrl = "https://inference-api.nousresearch.com/v1",
            modelHint = "",
            apiKeyUrl = "https://portal.nousresearch.com/",
            fallbackSetupUrls = listOf(
                "https://portal.nousresearch.com/",
                "https://portal.nousresearch.com/login",
            ),
        ),
        ProviderPreset(
            id = "custom",
            label = "Custom OpenAI-compatible",
            baseUrl = "",
            modelHint = "",
        ),
    )

    val androidSettingsDefaults = defaults

    fun find(id: String): ProviderPreset? = defaults.firstOrNull { it.id == id }

    fun setupUrls(providerId: String): List<String> {
        val preset = find(providerId) ?: return emptyList()
        return (listOf(preset.apiKeyUrl) + preset.fallbackSetupUrls)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun setupTarget(providerId: String, requestedIndex: Int): ProviderSetupTarget? {
        val urls = setupUrls(providerId)
        if (urls.isEmpty()) {
            return null
        }
        val index = requestedIndex.floorMod(urls.size)
        return ProviderSetupTarget(
            providerId = providerId,
            url = urls[index],
            index = index,
            total = urls.size,
        )
    }

    fun setupClipboardText(providerId: String): String {
        return setupUrls(providerId).joinToString(separator = "\n")
    }

    fun providerIdForSetupUrl(url: String, preferredProviderId: String = ""): String? {
        val normalized = url.trim()
        val preferred = preferredProviderId.trim()
        if (preferred.isNotBlank() && setupUrls(preferred).any { it == normalized }) {
            return preferred
        }
        return defaults.firstOrNull { preset ->
            setupUrls(preset.id).any { it == normalized }
        }?.id
    }

    fun runtimeConfigBaseUrl(providerId: String, baseUrl: String): String {
        val normalized = normalizeRuntimeBaseUrl(providerId, baseUrl)
        val presetDefault = find(providerId)?.baseUrl.orEmpty().trim().trimEnd('/')
        return when {
            providerId in setOf("zai", "zai-coding-plan") && normalized == presetDefault -> ""
            else -> normalized
        }
    }

    private fun normalizeRuntimeBaseUrl(providerId: String, baseUrl: String): String {
        val trimmed = baseUrl.trim()
        if (trimmed.isBlank()) {
            return ""
        }
        return if (providerId.trim().lowercase() == "custom") {
            runCatching { HermesEndpointUrl.openAiRuntimeBaseUrl(trimmed) }
                .getOrElse { trimmed.trimEnd('/') }
        } else {
            trimmed.trimEnd('/')
        }
    }

    fun apiKeyEnvVars(providerId: String): List<String> {
        return when (providerId.trim().lowercase()) {
            "openrouter" -> listOf("OPENROUTER_API_KEY")
            "openai", "custom" -> listOf("OPENAI_API_KEY")
            "codex" -> listOf("OPENAI_API_KEY", "CODEX_API_KEY")
            "anthropic" -> listOf("ANTHROPIC_API_KEY", "ANTHROPIC_TOKEN")
            "gemini" -> listOf("GOOGLE_API_KEY", "GEMINI_API_KEY")
            "groq" -> listOf("GROQ_API_KEY")
            "mistral" -> listOf("MISTRAL_API_KEY")
            "perplexity" -> listOf("PERPLEXITY_API_KEY")
            "cerebras" -> listOf("CEREBRAS_API_KEY")
            "together" -> listOf("TOGETHER_API_KEY")
            "fireworks" -> listOf("FIREWORKS_API_KEY")
            "deepinfra" -> listOf("DEEPINFRA_API_KEY")
            "chatgpt-web" -> listOf("CHATGPT_WEB_ACCESS_TOKEN")
            "alibaba", "dashscope" -> listOf("DASHSCOPE_API_KEY", "QWEN_API_KEY")
            "alibaba-coding-plan" -> listOf(
                "BAILIAN_CODING_PLAN_API_KEY",
                "ALIBABA_CODING_PLAN_API_KEY",
                "DASHSCOPE_API_KEY",
            )
            "qwen-oauth" -> listOf("QWEN_ACCESS_TOKEN", "QWEN_API_KEY", "DASHSCOPE_API_KEY")
            "zai", "bigmodel", "zhipu" -> listOf("GLM_API_KEY", "ZAI_API_KEY", "Z_AI_API_KEY", "BIGMODEL_API_KEY")
            "zai-coding-plan" -> listOf(
                "GLM_CODING_PLAN_API_KEY",
                "ZAI_CODING_PLAN_API_KEY",
                "GLM_API_KEY",
                "ZAI_API_KEY",
                "Z_AI_API_KEY",
            )
            "xai", "grok" -> listOf("XAI_API_KEY", "GROK_API_KEY")
            "xai-oauth" -> listOf("XAI_API_KEY", "XAI_ACCESS_TOKEN", "GROK_API_KEY")
            "openai-codex" -> listOf("OPENAI_API_KEY", "CODEX_API_KEY", "CHATGPT_WEB_ACCESS_TOKEN")
            "nous" -> listOf("NOUS_API_KEY", "NOUS_ACCESS_TOKEN")
            else -> listOf(providerId.trim().uppercase().replace('-', '_') + "_API_KEY")
        }.distinct()
    }

    fun credentialInputHelp(providerId: String): String {
        val envVars = apiKeyEnvVars(providerId)
        val primary = envVars.firstOrNull().orEmpty()
        val aliases = envVars.drop(1).joinToString(separator = ", ")
        return if (aliases.isBlank()) {
            "Paste a raw key or a CLI env line such as $primary=..."
        } else {
            "Paste a raw key or a CLI env line such as $primary=...; also accepts $aliases."
        }
    }

    fun parseCredentialInput(providerId: String, input: String): ParsedProviderCredential {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            return ParsedProviderCredential("")
        }
        val envVars = apiKeyEnvVars(providerId)
        envVars.forEach { envVar ->
            extractEnvValue(trimmed, envVar)?.let { value ->
                return ParsedProviderCredential(value, envVar)
            }
        }
        extractBearerCredential(trimmed)?.let { value ->
            return ParsedProviderCredential(value, "Bearer")
        }
        extractGenericCredential(trimmed)?.let { value ->
            return ParsedProviderCredential(value, "credential block")
        }
        extractAnyLikelyCredential(trimmed)?.let { value ->
            return ParsedProviderCredential(value, "env")
        }
        return ParsedProviderCredential(unquote(trimmed))
    }

    fun modelSelections(providerId: String): List<ModelSelectionPreset> {
        val providerHint = find(providerId)?.modelHint.orEmpty().takeIf { it.isNotBlank() }?.let {
            ModelSelectionPreset(
                id = it,
                label = it,
                description = "Provider suggested model",
            )
        }
        return listOfNotNull(providerHint) + firstClassLocalModels
    }

    private fun Int.floorMod(divisor: Int): Int {
        return ((this % divisor) + divisor) % divisor
    }

    private fun extractEnvValue(input: String, envVar: String): String? {
        val escapedEnvVar = Regex.escape(envVar)
        val patterns = listOf(
            Regex("""(?im)^\s*(?:export\s+|set\s+|setx\s+)?$escapedEnvVar\s*=\s*(.+?)\s*$"""),
            Regex("""(?im)^\s*setx\s+$escapedEnvVar\s+(.+?)\s*$"""),
            Regex("""(?im)^\s*$escapedEnvVar\s*:\s*(.+?)\s*$"""),
            Regex("""(?im)^\s*\${'$'}env:$escapedEnvVar\s*=\s*(.+?)\s*$"""),
            Regex("""(?im)["']$escapedEnvVar["']\s*:\s*["']([^"']+)["']"""),
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(input)?.groupValues?.getOrNull(1)?.let(::cleanCredentialValue)
        }
    }

    private fun extractAnyLikelyCredential(input: String): String? {
        val assignment = Regex("""(?im)^\s*(?:export\s+|set\s+|setx\s+|\${'$'}env:)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+?)\s*$""")
            .findAll(input)
            .firstOrNull { match ->
                val key = match.groupValues[1].uppercase()
                key.endsWith("_API_KEY") || key.endsWith("_ACCESS_TOKEN") || key.endsWith("_TOKEN")
            }
        return assignment?.groupValues?.getOrNull(2)?.let(::cleanCredentialValue)
    }

    private fun extractBearerCredential(input: String): String? {
        val inline = Regex("""(?im)^\s*(?:authorization\s*:\s*)?bearer\s+(.+?)\s*$""")
            .find(input)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanCredentialValue)
        if (!inline.isNullOrBlank()) {
            return inline
        }
        return Regex("""(?i)["']?authorization["']?\s*[:=]\s*["']bearer\s+([^"'\s]+)""")
            .find(input)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanCredentialValue)
    }

    private fun extractGenericCredential(input: String): String? {
        val names = """(?:api[_-]?key|access[_-]?token|auth[_-]?token|session[_-]?token|token|secret)"""
        val quoted = Regex("""(?i)["']$names["']\s*:\s*["']([^"']+)["']""")
            .find(input)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanCredentialValue)
        if (!quoted.isNullOrBlank()) {
            return quoted
        }
        return Regex("""(?im)^\s*$names\s*[:=]\s*(.+?)\s*$""")
            .find(input)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::cleanCredentialValue)
    }

    private fun cleanCredentialValue(value: String): String {
        return unquote(
            value.trim()
                .substringBefore(" #")
                .substringBefore(" //")
                .trim()
                .trimEnd(';'),
        )
    }

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length < 2) {
            return trimmed
        }
        val first = trimmed.first()
        val last = trimmed.last()
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            trimmed.substring(1, trimmed.length - 1).trim()
        } else {
            trimmed
        }
    }
}
