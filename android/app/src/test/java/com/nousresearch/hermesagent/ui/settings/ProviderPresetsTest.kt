package com.nousresearch.hermesagent.ui.settings

import com.nousresearch.hermesagent.data.ProviderPresets
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderPresetsTest {
    @Test
    fun zaiDefaultBaseUrlDoesNotOverrideCliEndpointDetection() {
        val preset = requireNotNull(ProviderPresets.find("zai"))

        val configBaseUrl = ProviderPresets.runtimeConfigBaseUrl("zai", preset.baseUrl)

        assertEquals("", configBaseUrl)
    }

    @Test
    fun customZaiBaseUrlIsPreserved() {
        val configBaseUrl = ProviderPresets.runtimeConfigBaseUrl(
            providerId = "zai",
            baseUrl = "https://open.bigmodel.cn/api/coding/paas/v4/",
        )

        assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", configBaseUrl)
    }

    @Test
    fun nonZaiProviderDefaultBaseUrlIsPreserved() {
        val preset = requireNotNull(ProviderPresets.find("openrouter"))

        val configBaseUrl = ProviderPresets.runtimeConfigBaseUrl("openrouter", preset.baseUrl)

        assertEquals("https://openrouter.ai/api/v1", configBaseUrl)
    }

    @Test
    fun setupTargetsCycleThroughOfficialFallbacks() {
        val first = requireNotNull(ProviderPresets.setupTarget("qwen-oauth", 0))
        val second = requireNotNull(ProviderPresets.setupTarget("qwen-oauth", 1))
        val wrapped = requireNotNull(ProviderPresets.setupTarget("qwen-oauth", 6))

        assertEquals("https://qwenlm.github.io/qwen-code-docs/en/users/configuration/auth/", first.url)
        assertEquals("https://home.qwencloud.com/api-keys", second.url)
        assertEquals(first.url, wrapped.url)
        assertEquals(6, first.total)
        assertEquals(1, first.nextIndex)
    }

    @Test
    fun parsesProviderEnvStyleCredentialInput() {
        assertEquals(
            "sk-or-v1-test",
            ProviderPresets.parseCredentialInput("openrouter", "OPENROUTER_API_KEY=sk-or-v1-test").apiKey,
        )
        assertEquals(
            "sk-qwen-test",
            ProviderPresets.parseCredentialInput("alibaba", "export DASHSCOPE_API_KEY='sk-qwen-test'").apiKey,
        )
        assertEquals(
            "glm-test",
            ProviderPresets.parseCredentialInput("zai", "\$env:ZAI_API_KEY=\"glm-test\"").apiKey,
        )
        assertEquals(
            "google-test",
            ProviderPresets.parseCredentialInput("gemini", "{\"GOOGLE_API_KEY\":\"google-test\"}").apiKey,
        )
    }

    @Test
    fun rawProviderCredentialInputIsPreserved() {
        assertEquals(
            "sk-raw-test",
            ProviderPresets.parseCredentialInput("openrouter", "sk-raw-test").apiKey,
        )
    }
}
