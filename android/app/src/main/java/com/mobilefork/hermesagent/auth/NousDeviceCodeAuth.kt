package com.mobilefork.hermesagent.auth

import com.mobilefork.hermesagent.data.AuthCatalog
import com.mobilefork.hermesagent.data.AuthScope
import com.mobilefork.hermesagent.data.AuthSession
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Nous Portal device-code OAuth (CLI parity).
 */
object NousDeviceCodeAuth {
    const val PORTAL_BASE = "https://portal.nousresearch.com"
    const val INFERENCE_BASE = "https://inference-api.nousresearch.com/v1"
    const val CLIENT_ID = "hermes-cli"
    const val SCOPE = "inference:invoke inference:mint_agent_key"
    const val LEGACY_SCOPE = "inference:mint_agent_key"
    private const val GRANT = "urn:ietf:params:oauth:grant-type:device_code"

    data class DeviceStart(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresIn: Int,
        val intervalSeconds: Int,
        val scope: String,
        val portalBaseUrl: String,
        val clientId: String,
    )

    fun requestDeviceCode(
        portalBaseUrl: String = PORTAL_BASE,
        clientId: String = CLIENT_ID,
        scope: String = SCOPE,
        timeoutMs: Int = 15_000,
    ): DeviceStart {
        return try {
            postDeviceCode(portalBaseUrl, clientId, scope, timeoutMs)
        } catch (first: Exception) {
            // CLI falls back to legacy scope when invoke is refused.
            if (scope != LEGACY_SCOPE) {
                postDeviceCode(portalBaseUrl, clientId, LEGACY_SCOPE, timeoutMs)
            } else {
                throw first
            }
        }
    }

    private fun postDeviceCode(
        portalBaseUrl: String,
        clientId: String,
        scope: String,
        timeoutMs: Int,
    ): DeviceStart {
        val base = portalBaseUrl.trimEnd('/')
        val form = buildString {
            append("client_id=").append(URLEncoder.encode(clientId, "UTF-8"))
            if (scope.isNotBlank()) {
                append("&scope=").append(URLEncoder.encode(scope, "UTF-8"))
            }
        }
        val connection = (URL("$base/api/oauth/device/code").openConnection() as HttpURLConnection).apply {
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
                throw IllegalStateException("Nous device code HTTP $responseCode: ${text.take(200)}")
            }
            val json = JSONObject(text)
            return DeviceStart(
                deviceCode = json.optString("device_code").trim(),
                userCode = json.optString("user_code").trim(),
                verificationUri = json.optString("verification_uri").trim(),
                verificationUriComplete = json.optString("verification_uri_complete").trim(),
                expiresIn = json.optInt("expires_in", 900),
                intervalSeconds = json.optInt("interval", 1).coerceAtLeast(1),
                scope = scope,
                portalBaseUrl = base,
                clientId = clientId,
            ).also {
                require(it.deviceCode.isNotBlank() && it.userCode.isNotBlank() && it.verificationUriComplete.isNotBlank()) {
                    "Nous device code response incomplete"
                }
            }
        }
    }

    /**
     * One poll attempt. Returns null if authorization_pending / slow_down.
     */
    fun pollOnce(device: DeviceStart, timeoutMs: Int = 15_000): AuthSession? {
        val form = buildString {
            append("grant_type=").append(URLEncoder.encode(GRANT, "UTF-8"))
            append("&client_id=").append(URLEncoder.encode(device.clientId, "UTF-8"))
            append("&device_code=").append(URLEncoder.encode(device.deviceCode, "UTF-8"))
        }
        val connection = (URL("${device.portalBaseUrl}/api/oauth/token").openConnection() as HttpURLConnection).apply {
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
            if (responseCode in 200..299) {
                val json = JSONObject(text)
                val access = json.optString("access_token").trim()
                val refresh = json.optString("refresh_token").trim()
                if (access.isBlank()) {
                    throw IllegalStateException("Nous token response missing access_token")
                }
                val option = AuthCatalog.find("nous")
                return AuthSession(
                    methodId = "nous",
                    label = option?.label ?: "Nous Portal",
                    scope = AuthScope.RuntimeProvider,
                    runtimeProvider = "nous",
                    signedIn = true,
                    status = "Signed in with Nous Portal device code.",
                    accessToken = access,
                    refreshToken = refresh,
                    apiKey = access,
                    baseUrl = option?.defaultBaseUrl?.ifBlank { INFERENCE_BASE } ?: INFERENCE_BASE,
                    model = option?.defaultModel.orEmpty(),
                )
            }
            val errorJson = runCatching { JSONObject(text) }.getOrNull()
            val errorCode = errorJson?.optString("error").orEmpty()
            if (errorCode == "authorization_pending" || errorCode == "slow_down") {
                return null
            }
            val description = errorJson?.optString("error_description")
                .orEmpty()
                .ifBlank { text.take(160) }
            throw IllegalStateException("$errorCode: $description")
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
