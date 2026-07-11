package com.mobilefork.hermesagent.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Asserts parity with openai/codex `codex-rs/login/src/server.rs` authorize URL shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CodexOAuthClientTest {
    @Test
    fun browserAuthorizeUrlMatchesOfficialCodexCli() {
        val start = CodexOAuthClient.createBrowserStartRequest(
            methodId = "codex",
            state = "stateabc",
            verifier = "verifier-abcdefghijklmnopqrstuvwx",
            port = 1455,
        )
        assertEquals("http://localhost:1455/auth/callback", start.redirectUri)
        assertEquals(CodexOAuthClient.CLIENT_ID, start.authorizeUri.getQueryParameter("client_id"))
        assertEquals("code", start.authorizeUri.getQueryParameter("response_type"))
        assertEquals(CodexOAuthClient.SCOPE, start.authorizeUri.getQueryParameter("scope"))
        assertEquals("S256", start.authorizeUri.getQueryParameter("code_challenge_method"))
        assertEquals("true", start.authorizeUri.getQueryParameter("id_token_add_organizations"))
        assertEquals("true", start.authorizeUri.getQueryParameter("codex_cli_simplified_flow"))
        assertEquals(CodexOAuthClient.ORIGINATOR, start.authorizeUri.getQueryParameter("originator"))
        assertEquals("stateabc", start.authorizeUri.getQueryParameter("state"))
        assertTrue(start.authorizeUri.toString().startsWith("https://auth.openai.com/oauth/authorize?"))
        assertEquals("codex-oauth", start.pending.authProvider)
    }

    @Test
    fun codeChallengeIsUrlSafeS256() {
        val challenge = CodexOAuthClient.codeChallenge("test-verifier-abcdefghijklmnopqrstuvwxyz")
        assertTrue(challenge.isNotBlank())
        assertTrue(!challenge.contains("="))
    }

    @Test
    fun clientIdMatchesOfficialCodexCli() {
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", CodexOAuthClient.CLIENT_ID)
        assertEquals(1455, CodexOAuthClient.DEFAULT_PORT)
        assertEquals(1457, CodexOAuthClient.FALLBACK_PORT)
    }
}
