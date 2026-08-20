package com.mobilefork.hermesagent.auth

import android.net.Uri
import android.util.Base64
import com.mobilefork.hermesagent.data.AuthCatalog
import com.mobilefork.hermesagent.data.AuthScope
import com.mobilefork.hermesagent.data.AuthSession
import com.mobilefork.hermesagent.data.PendingAuthRequest
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/**
 * OpenAI Codex CLI OAuth paths (parity with openai/codex `codex-rs/login`).
 *
 * Primary (browser / WebView):
 *   authorize: https://auth.openai.com/oauth/authorize
 *   redirect:  http://localhost:{1455|1457}/auth/callback
 *   token:     https://auth.openai.com/oauth/token
 *
 * Device-code (headless fallback) lives in [CodexDeviceCodeAuth].
 */
object CodexOAuthClient {
    const val ISSUER = "https://auth.openai.com"
    /** Official Codex CLI client id (`codex-rs/login/src/auth/manager.rs`). */
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val TOKEN_URL = "$ISSUER/oauth/token"
    const val AUTHORIZE_PATH = "/oauth/authorize"
    /** Official scope from `build_authorize_url` in codex-rs/login/src/server.rs */
    const val SCOPE =
        "openid profile email offline_access api.connectors.read api.connectors.invoke"
    /** Default originator from codex CLI (`codex_cli_rs`). */
    const val ORIGINATOR = "codex_cli_rs"
    const val DEFAULT_PORT = 1455
    const val FALLBACK_PORT = 1457
    const val CALLBACK_PATH = "/auth/callback"
    const val AUTH_PROVIDER = "codex-oauth"
    const val DEFAULT_CODEX_BASE = "https://chatgpt.com/backend-api/codex"
    const val DEFAULT_CHATGPT_BASE = "https://chatgpt.com/backend-api/f"

    private val secureRandom = SecureRandom()

    data class StartRequest(
        val pending: PendingAuthRequest,
        val authorizeUri: Uri,
        val redirectUri: String,
        val codeChallenge: String,
        val preferredPort: Int,
    )

    fun createBrowserStartRequest(
        methodId: String,
        state: String = UUID.randomUUID().toString().replace("-", ""),
        verifier: String = createCodeVerifier(),
        port: Int = DEFAULT_PORT,
    ): StartRequest {
        val challenge = codeChallenge(verifier)
        val redirectUri = "http://localhost:$port$CALLBACK_PATH"
        val authorizeUri = Uri.parse("$ISSUER$AUTHORIZE_PATH").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("id_token_add_organizations", "true")
            .appendQueryParameter("codex_cli_simplified_flow", "true")
            .appendQueryParameter("state", state)
            .appendQueryParameter("originator", ORIGINATOR)
            .build()
        val pending = PendingAuthRequest(
            state = state,
            methodId = methodId,
            startUrl = authorizeUri.toString(),
            authProvider = AUTH_PROVIDER,
            codeVerifier = verifier,
            codeChallengeMethod = "S256",
        )
        return StartRequest(
            pending = pending,
            authorizeUri = authorizeUri,
            redirectUri = redirectUri,
            codeChallenge = challenge,
            preferredPort = port,
        )
    }

    fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
        redirectUri: String,
        methodId: String,
        timeoutMs: Int = 20_000,
    ): AuthSession {
        // Exact form fields from codex-rs/login/src/server.rs exchange_code_for_tokens
        val form = buildString {
            append("grant_type=authorization_code")
            append("&code=").append(urlEncode(code))
            append("&redirect_uri=").append(urlEncode(redirectUri))
            append("&client_id=").append(urlEncode(CLIENT_ID))
            append("&code_verifier=").append(urlEncode(codeVerifier))
        }
        val connection = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        connection.use {
            OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(form) }
            val text = if (responseCode in 200..299) {
                inputStream.bufferedReader().use { it.readText() }
            } else {
                errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (responseCode !in 200..299) {
                throw IllegalStateException("token endpoint returned status $responseCode: ${text.take(240)}")
            }
            val tokens = JSONObject(text)
            val access = tokens.optString("access_token").trim()
            val refresh = tokens.optString("refresh_token").trim()
            val idToken = tokens.optString("id_token").trim()
            if (access.isBlank() || refresh.isBlank()) {
                throw IllegalStateException("token endpoint missing access/refresh token")
            }
            return sessionFromTokens(
                methodId = methodId,
                accessToken = access,
                refreshToken = refresh,
                idToken = idToken,
                status = "Signed in with ChatGPT/Codex OAuth (openai/codex browser flow).",
            )
        }
    }

    fun sessionFromTokens(
        methodId: String,
        accessToken: String,
        refreshToken: String,
        idToken: String = "",
        status: String,
    ): AuthSession {
        val option = AuthCatalog.find(methodId)
        val runtimeProvider = when (methodId) {
            "chatgpt" -> "chatgpt-web"
            "codex", "openai-codex" -> "openai-codex"
            else -> option?.runtimeProvider ?: "openai-codex"
        }
        val baseUrl = when (runtimeProvider) {
            "chatgpt-web" -> option?.defaultBaseUrl?.ifBlank { DEFAULT_CHATGPT_BASE } ?: DEFAULT_CHATGPT_BASE
            else -> option?.defaultBaseUrl?.ifBlank { DEFAULT_CODEX_BASE } ?: DEFAULT_CODEX_BASE
        }
        // Prefer access token as the runtime bearer (Codex stores id+access+refresh).
        return AuthSession(
            methodId = methodId,
            label = option?.label ?: "ChatGPT / Codex",
            scope = AuthScope.RuntimeProvider,
            runtimeProvider = runtimeProvider,
            signedIn = true,
            status = status,
            accessToken = accessToken,
            refreshToken = refreshToken,
            sessionToken = idToken.ifBlank { accessToken },
            apiKey = accessToken,
            baseUrl = baseUrl,
            model = option?.defaultModel.orEmpty(),
        )
    }

    internal fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private fun createCodeVerifier(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private inline fun <T> HttpURLConnection.use(block: HttpURLConnection.() -> T): T {
        return try {
            block()
        } finally {
            disconnect()
        }
    }
}
