package com.mobilefork.hermesagent.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class XaiOAuthClientTest {
    @Test
    fun codeChallengeIsS256UrlSafe() {
        val challenge = XaiOAuthClient.codeChallenge("test-verifier-abcdefghijklmnopqrstuvwxyz")
        assertTrue(challenge.isNotBlank())
        assertFalseContainsPadding(challenge)
    }

    @Test
    fun createStartRequestUsesLoopbackRedirect() {
        val discovery = XaiOAuthClient.Discovery(
            authorizationEndpoint = "https://auth.x.ai/authorize",
            tokenEndpoint = "https://auth.x.ai/oauth/token",
        )
        val start = XaiOAuthClient.createStartRequest(
            discovery = discovery,
            state = "state123",
            verifier = "verifier-abcdefghijklmnopqrstuvwx",
        )
        assertEquals("xai-oauth", start.pending.methodId)
        assertEquals("xai-oauth", start.pending.authProvider)
        assertTrue(start.redirectUri.startsWith("http://127.0.0.1:"))
        assertTrue(start.redirectUri.endsWith("/callback"))
        assertTrue(start.authorizeUri.getQueryParameter("client_id") == XaiOAuthClient.CLIENT_ID)
        assertEquals("S256", start.authorizeUri.getQueryParameter("code_challenge_method"))
        assertEquals("generic", start.authorizeUri.getQueryParameter("plan"))
        assertEquals("hermes-agent", start.authorizeUri.getQueryParameter("referrer"))
    }

    private fun assertFalseContainsPadding(value: String) {
        assertTrue("challenge should be URL-safe base64 without padding", !value.contains("="))
    }
}
