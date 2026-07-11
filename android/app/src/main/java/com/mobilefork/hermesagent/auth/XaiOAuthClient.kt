package com.mobilefork.hermesagent.auth

import android.net.Uri
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
 * xAI Grok SuperGrok OAuth (CLI parity): OIDC discovery + PKCE + loopback callback.
 * Redirect host must be 127.0.0.1 (xAI requirement).
 */
object XaiOAuthClient {
    const val AUTH_PROVIDER = "xai-oauth"
    const val METHOD_ID = "xai-oauth"
    const val DISCOVERY_URL = "https://auth.x.ai/.well-known/openid-configuration"
    const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    const val SCOPE = "openid profile email offline_access grok-cli:access api:access"
    const val DEFAULT_BASE_URL = "https://api.x.ai/v1"
    const val REDIRECT_HOST = "127.0.0.1"
    const val REDIRECT_PORT = 56121
    const val REDIRECT_PATH = "/callback"

    private val secureRandom = SecureRandom()

    data class Discovery(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
    )

    data class StartRequest(
        val pending: PendingAuthRequest,
        val authorizeUri: Uri,
        val redirectUri: String,
        val codeChallenge: String,
    )

    fun discover(timeoutMs: Int = 15_000): Discovery {
        val connection = (URL(DISCOVERY_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
        }
        connection.use {
            val body = if (responseCode in 200..299) {
                inputStream.bufferedReader().use { it.readText() }
            } else {
                errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (responseCode !in 200..299) {
                throw IllegalStateException("xAI discovery HTTP $responseCode")
            }
            val json = JSONObject(body)
            val auth = json.optString("authorization_endpoint").trim()
            val token = json.optString("token_endpoint").trim()
            if (auth.isBlank() || token.isBlank()) {
                throw IllegalStateException("xAI discovery missing endpoints")
            }
            return Discovery(authorizationEndpoint = auth, tokenEndpoint = token)
        }
    }

    fun createStartRequest(
        discovery: Discovery = discover(),
        state: String = UUID.randomUUID().toString().replace("-", ""),
        verifier: String = createCodeVerifier(),
        port: Int = REDIRECT_PORT,
    ): StartRequest {
        val challenge = codeChallenge(verifier)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val redirectUri = "http://$REDIRECT_HOST:$port$REDIRECT_PATH"
        val authorizeUri = Uri.parse(discovery.authorizationEndpoint).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("nonce", nonce)
            .appendQueryParameter("plan", "generic")
            .appendQueryParameter("referrer", "hermes-agent")
            .build()
        val pending = PendingAuthRequest(
            state = state,
            methodId = METHOD_ID,
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
        )
    }

    fun exchangeCodeForSession(
        code: String,
        pending: PendingAuthRequest,
        redirectUri: String,
        tokenEndpoint: String,
        codeChallenge: String,
        nowEpochMs: Long = System.currentTimeMillis(),
        timeoutMs: Int = 20_000,
    ): AuthSession {
        val option = AuthCatalog.find(METHOD_ID)
            ?: AuthCatalog.find("xai")
            ?: return failure("xAI catalog entry missing", nowEpochMs)
        if (pending.codeVerifier.isBlank()) {
            return failure("xAI sign-in rejected: missing PKCE verifier", nowEpochMs)
        }
        val data = buildString {
            append("grant_type=authorization_code")
            append("&code=").append(urlEncode(code))
            append("&redirect_uri=").append(urlEncode(redirectUri))
            append("&client_id=").append(urlEncode(CLIENT_ID))
            append("&code_verifier=").append(urlEncode(pending.codeVerifier))
            if (codeChallenge.isNotBlank()) {
                append("&code_challenge=").append(urlEncode(codeChallenge))
                append("&code_challenge_method=S256")
            }
        }
        return try {
            val connection = (URL(tokenEndpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }
            connection.use {
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(data) }
                val body = if (responseCode in 200..299) {
                    inputStream.bufferedReader().use { it.readText() }
                } else {
                    errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }
                if (responseCode !in 200..299) {
                    return failure("xAI token exchange failed: HTTP $responseCode", nowEpochMs)
                }
                val json = JSONObject(body)
                val access = json.optString("access_token").trim()
                val refresh = json.optString("refresh_token").trim()
                if (access.isBlank() || refresh.isBlank()) {
                    return failure("xAI token exchange returned incomplete tokens", nowEpochMs)
                }
                AuthSession(
                    methodId = METHOD_ID,
                    label = option.label,
                    scope = AuthScope.RuntimeProvider,
                    runtimeProvider = "xai-oauth",
                    signedIn = true,
                    status = "Signed in with xAI Grok OAuth (SuperGrok).",
                    accessToken = access,
                    refreshToken = refresh,
                    apiKey = access,
                    baseUrl = option.defaultBaseUrl.ifBlank { DEFAULT_BASE_URL },
                    model = option.defaultModel,
                    updatedAtEpochMs = nowEpochMs,
                )
            }
        } catch (error: Exception) {
            failure("xAI token exchange failed: ${error.javaClass.simpleName}", nowEpochMs)
        }
    }

    internal fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun createCodeVerifier(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun failure(status: String, nowEpochMs: Long): AuthSession {
        val option = AuthCatalog.find(METHOD_ID) ?: AuthCatalog.find("xai")
        return AuthSession(
            methodId = METHOD_ID,
            label = option?.label ?: "xAI / Grok",
            scope = AuthScope.RuntimeProvider,
            runtimeProvider = "xai-oauth",
            signedIn = false,
            status = status.take(240),
            baseUrl = option?.defaultBaseUrl ?: DEFAULT_BASE_URL,
            model = option?.defaultModel.orEmpty(),
            updatedAtEpochMs = nowEpochMs,
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
