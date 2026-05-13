package com.nousresearch.hermesagent.auth

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

data class ProviderSetupProbeResult(
    val url: String,
    val reachable: Boolean,
    val statusLabel: String,
)

object ProviderSetupUrlProbe {
    const val DEFAULT_TIMEOUT_MS = 6_000
    const val MAX_STATUS_LENGTH = 900

    fun probe(url: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ProviderSetupProbeResult {
        val target = url.trim()
        val parsed = runCatching { Uri.parse(target) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase().orEmpty()
        if (target.isBlank() || scheme !in setOf("http", "https") || parsed?.host.isNullOrBlank()) {
            return ProviderSetupProbeResult(target, reachable = false, statusLabel = "invalid URL")
        }
        return try {
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "HermesAgentAndroidProviderSetup/1.0")
                setRequestProperty("Accept", "text/html,application/json,*/*")
            }
            connection.use {
                val code = responseCode
                ProviderSetupProbeResult(
                    url = target,
                    reachable = code in 200..499,
                    statusLabel = "HTTP $code",
                )
            }
        } catch (_: UnknownHostException) {
            ProviderSetupProbeResult(target, reachable = false, statusLabel = "unknown host")
        } catch (error: Exception) {
            ProviderSetupProbeResult(target, reachable = false, statusLabel = error.javaClass.simpleName)
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
