package com.mobilefork.hermesagent.auth

import com.mobilefork.hermesagent.data.AuthCatalog
import com.mobilefork.hermesagent.data.AuthScope
import com.mobilefork.hermesagent.data.AuthSession
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI Codex / ChatGPT subscription device-code login (CLI parity).
 */
object CodexDeviceCodeAuth {
    const val ISSUER = "https://auth.openai.com"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val TOKEN_URL = "https://auth.openai.com/oauth/token"
    const val DEVICE_URL = "https://auth.openai.com/codex/device"
    const val DEFAULT_CODEX_BASE = "https://chatgpt.com/backend-api/codex"
    const val DEFAULT_CHATGPT_BASE = "https://chatgpt.com/backend-api/f"

    data class DeviceStart(
        val userCode: String,
        val deviceAuthId: String,
        val pollIntervalSeconds: Int,
        val verificationUrl: String = DEVICE_URL,
    )

    fun requestDeviceCode(timeoutMs: Int = 15_000): DeviceStart {
        val body = JSONObject().put("client_id", CLIENT_ID).toString()
        val connection = (URL("$ISSUER/api/accounts/deviceauth/usercode").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        connection.use {
            OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body) }
            val text = if (responseCode in 200..299) {
                inputStream.bufferedReader().use { it.readText() }
            } else {
                errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (responseCode !in 200..299) {
                throw IllegalStateException("Device code request HTTP $responseCode")
            }
            val json = JSONObject(text)
            val userCode = json.optString("user_code").trim()
            val deviceAuthId = json.optString("device_auth_id").trim()
            val interval = json.optInt("interval", 5).coerceAtLeast(3)
            if (userCode.isBlank() || deviceAuthId.isBlank()) {
                throw IllegalStateException("Device code response incomplete")
            }
            return DeviceStart(
                userCode = userCode,
                deviceAuthId = deviceAuthId,
                pollIntervalSeconds = interval,
            )
        }
    }

    /**
     * One poll attempt. Returns null if still pending, session when complete.
     */
    fun pollOnce(
        device: DeviceStart,
        methodId: String,
        timeoutMs: Int = 15_000,
    ): AuthSession? {
        val body = JSONObject()
            .put("device_auth_id", device.deviceAuthId)
            .put("user_code", device.userCode)
            .toString()
        val connection = (URL("$ISSUER/api/accounts/deviceauth/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        connection.use {
            OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body) }
            if (responseCode in setOf(403, 404)) {
                return null // still waiting
            }
            val text = if (responseCode in 200..299) {
                inputStream.bufferedReader().use { it.readText() }
            } else {
                errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (responseCode !in 200..299) {
                throw IllegalStateException("Device poll HTTP $responseCode")
            }
            val json = JSONObject(text)
            val authorizationCode = json.optString("authorization_code").trim()
            val codeVerifier = json.optString("code_verifier").trim()
            if (authorizationCode.isBlank() || codeVerifier.isBlank()) {
                throw IllegalStateException("Device auth response incomplete")
            }
            return exchangeAuthorizationCode(
                authorizationCode = authorizationCode,
                codeVerifier = codeVerifier,
                methodId = methodId,
            )
        }
    }

    private fun exchangeAuthorizationCode(
        authorizationCode: String,
        codeVerifier: String,
        methodId: String,
        timeoutMs: Int = 15_000,
    ): AuthSession {
        val redirectUri = "$ISSUER/deviceauth/callback"
        val form = buildString {
            append("grant_type=authorization_code")
            append("&code=").append(java.net.URLEncoder.encode(authorizationCode, "UTF-8"))
            append("&redirect_uri=").append(java.net.URLEncoder.encode(redirectUri, "UTF-8"))
            append("&client_id=").append(java.net.URLEncoder.encode(CLIENT_ID, "UTF-8"))
            append("&code_verifier=").append(java.net.URLEncoder.encode(codeVerifier, "UTF-8"))
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
                throw IllegalStateException("Token exchange HTTP $responseCode")
            }
            val tokens = JSONObject(text)
            val access = tokens.optString("access_token").trim()
            val refresh = tokens.optString("refresh_token").trim()
            if (access.isBlank()) {
                throw IllegalStateException("Token exchange missing access_token")
            }
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
            return AuthSession(
                methodId = methodId,
                label = option?.label ?: "ChatGPT / Codex",
                scope = AuthScope.RuntimeProvider,
                runtimeProvider = runtimeProvider,
                signedIn = true,
                status = "Signed in with OpenAI device code (ChatGPT/Codex subscription).",
                accessToken = access,
                refreshToken = refresh,
                apiKey = access,
                sessionToken = access,
                baseUrl = baseUrl,
                model = option?.defaultModel.orEmpty(),
            )
        }
    }

    private inline fun <T> HttpURLConnection.use(block: HttpURLConnection.() -> T): T {
        return try {
            block()
        } finally {
            disconnect()
        }
    }
}
