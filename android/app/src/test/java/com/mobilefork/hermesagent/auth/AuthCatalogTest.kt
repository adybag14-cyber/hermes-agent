package com.mobilefork.hermesagent.auth

import com.mobilefork.hermesagent.data.AuthCatalog
import com.mobilefork.hermesagent.data.AuthScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthCatalogTest {
    @Test
    fun apiKeyOnlyProvidersDoNotExposeBrowserOAuthSignIn() {
        listOf(
            "openai",
            "claude",
            "gemini",
            "qwen",
            "qwen-coding-plan",
            "qwen-oauth",
            "zai",
            "zai-coding-plan",
            "xai",
            "bigmodel",
        ).forEach { optionId ->
            val option = requireNotNull(AuthCatalog.find(optionId)) {
                "Missing auth option $optionId"
            }

            assertEquals("Expected $optionId to configure a runtime provider", AuthScope.RuntimeProvider, option.scope)
            assertFalse("$optionId must use secure API-key/token setup, not browser OAuth", option.browserSignInSupported)
            assertTrue("$optionId must map to a provider preset", option.runtimeProvider.isNotBlank())
        }
    }

    @Test
    fun oauthAndDeviceCodeProvidersExposeBrowserSignIn() {
        listOf(
            "openrouter",
            "xai-oauth",
            "chatgpt",
            "codex",
            "nous",
        ).forEach { optionId ->
            val option = requireNotNull(AuthCatalog.find(optionId)) {
                "Missing auth option $optionId"
            }
            assertTrue("$optionId should support browser/device sign-in", option.browserSignInSupported)
            assertEquals(AuthScope.RuntimeProvider, option.scope)
        }
    }

    @Test
    fun openRouterRemainsTheBrowserOAuthRuntimeProvider() {
        val option = requireNotNull(AuthCatalog.find("openrouter"))

        assertEquals(AuthScope.RuntimeProvider, option.scope)
        assertEquals("openrouter", option.runtimeProvider)
        assertTrue(option.browserSignInSupported)
    }

    @Test
    fun xaiOauthUsesXaiOauthRuntimeProvider() {
        val option = requireNotNull(AuthCatalog.find("xai-oauth"))
        assertEquals("xai-oauth", option.runtimeProvider)
        assertTrue(option.defaultBaseUrl.contains("api.x.ai"))
    }
}
