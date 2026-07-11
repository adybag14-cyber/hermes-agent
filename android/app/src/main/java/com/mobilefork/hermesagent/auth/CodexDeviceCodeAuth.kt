package com.mobilefork.hermesagent.auth

import com.mobilefork.hermesagent.data.AuthSession
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI Codex device-code login — parity with openai/codex
 * `codex-rs/login/src/device_code_auth.rs`.
 *
 * POST {issuer}/api/accounts/deviceauth/usercode  JSON {client_id}
 * UI:   {issuer}/codex/device  + user_code
 * POST {issuer}/api/accounts/deviceauth/token     JSON {device_auth_id, user_code}
 *      → authorization_code + code_verifier (+ code_challenge)
 * POST {issuer}/oauth/token  form grant_type=authorization_code …
 *      redirect_uri = {issuer}/deviceauth/callback
 */
object CodexDeviceCodeAuth {
    const val ISSUER = CodexOAuthClient.ISSUER
    const val CLIENT_ID = CodexOAuthClient.CLIENT_ID
    const val DEVICE_PAGE = "$ISSUER/codex/device"
    private const val USERCODE_URL = "$ISSUER/api/accounts/deviceauth/usercode"
    private const val POLL_URL = "$ISSUER/api/accounts/deviceauth/token"
    private const val DEVICE_REDIRECT_URI = "$ISSUER/deviceauth/callback"

    data class DeviceStart(
        val userCode: String,
        val deviceAuthId: String,
        val pollIntervalSeconds: Int,
        val verificationUrl: String = DEVICE_PAGE,
    )

    fun requestDeviceCode(timeoutMs: Int = 15_000): DeviceStart {
        val body = JSONObject().put("client_id", CLIENT_ID).toString()
        val connection = (URL(USERCODE_URL).openConnection() as HttpURLConnection).apply {
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
            if (responseCode == 404) {
                throw IllegalStateException(
                    "device code login is not enabled for this Codex server. Use browser login.",
                )
            }
            if (responseCode !in 200..299) {
                throw IllegalStateException("device code request failed with status $responseCode")
            }
            val json = JSONObject(text)
            // Official CLI accepts user_code or usercode; interval may be a string.
            val userCode = json.optString("user_code")
                .ifBlank { json.optString("usercode") }
                .trim()
            val deviceAuthId = json.optString("device_auth_id").trim()
            val interval = parseInterval(json)
            if (userCode.isBlank() || deviceAuthId.isBlank()) {
                throw IllegalStateException("device code response incomplete")
            }
            return DeviceStart(
                userCode = userCode,
                deviceAuthId = deviceAuthId,
                pollIntervalSeconds = interval,
                verificationUrl = DEVICE_PAGE,
            )
        }
    }

    /**
     * One poll. Returns null while pending (403/404), session when complete.
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
        val connection = (URL(POLL_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        connection.use {
            OutputStreamWriter(outputStream, Charsets.UTF_8).use { it.write(body) }
            // Official: 403 or 404 while waiting
            if (responseCode in setOf(403, 404)) {
                return null
            }
            val text = if (responseCode in 200..299) {
                inputStream.bufferedReader().use { it.readText() }
            } else {
                errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            if (responseCode !in 200..299) {
                throw IllegalStateException("device auth failed with status $responseCode")
            }
            val json = JSONObject(text)
            val authorizationCode = json.optString("authorization_code").trim()
            val codeVerifier = json.optString("code_verifier").trim()
            // code_challenge is returned but exchange only needs code_verifier (server.rs)
            if (authorizationCode.isBlank() || codeVerifier.isBlank()) {
                throw IllegalStateException("device auth response incomplete")
            }
            return CodexOAuthClient.exchangeAuthorizationCode(
                code = authorizationCode,
                codeVerifier = codeVerifier,
                redirectUri = DEVICE_REDIRECT_URI,
                methodId = methodId,
            ).let { session ->
                session.copy(
                    status = "Signed in with ChatGPT/Codex device code (openai/codex flow).",
                )
            }
        }
    }

    private fun parseInterval(json: JSONObject): Int {
        // Official deserializes interval as string → u64, default not specified; CLI uses raw value
        val asInt = json.optInt("interval", 0)
        if (asInt > 0) return asInt.coerceAtLeast(1)
        val asString = json.optString("interval").trim()
        return asString.toIntOrNull()?.coerceAtLeast(1) ?: 5
    }

    private inline fun <T> HttpURLConnection.use(block: HttpURLConnection.() -> T): T {
        return try {
            block()
        } finally {
            disconnect()
        }
    }
}
