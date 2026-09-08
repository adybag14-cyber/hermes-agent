package com.mobilefork.hermesagent.privacy

import android.content.Context
import com.mobilefork.hermesagent.BuildConfig
import com.mobilefork.hermesagent.data.HermesNetworkPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class AiReportReason(val wireValue: String) {
    CHILD_SAFETY("child_safety"), VIOLENCE("violence"), SEXUAL_CONTENT("sexual_content"),
    HATE("hate"), DECEPTIVE_CONTENT("deceptive_content"), OTHER("other"),
}

data class AiReportReceipt(val id: String, val deletionSecret: String, val submitted: Boolean)

/** Only receipts are retained here, never a background transcript or a report draft. */
class AiReportReceiptStore(context: Context) {
    private val preferences = context.getSharedPreferences("ai-report-receipts-v1", Context.MODE_PRIVATE)

    fun load(): List<AiReportReceipt> = preferences.all.values.mapNotNull { value ->
        runCatching {
            val record = JSONObject(value as String)
            AiReportReceipt(record.getString("id"), record.getString("secret"), record.getBoolean("submitted"))
        }.getOrNull()
    }

    fun save(receipt: AiReportReceipt) {
        val json = JSONObject().put("id", receipt.id).put("secret", receipt.deletionSecret)
            .put("submitted", receipt.submitted).toString()
        check(preferences.edit().putString(receipt.id, json).commit()) { "Could not save report deletion receipt" }
    }

    fun forget(id: String) {
        check(preferences.edit().remove(id).commit()) { "Could not remove report deletion receipt" }
    }
}

class AiContentReportClient(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) {
    private val receipts = AiReportReceiptStore(context)

    fun prepare(): AiReportReceipt = AiReportReceipt(
        id = UUID.randomUUID().toString(),
        deletionSecret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 255) },
        submitted = false,
    ).also(receipts::save)

    /** Caller must preview this exact text and get a separate affirmative submission action. */
    fun submit(receipt: AiReportReceipt, reason: AiReportReason, message: String, notes: String): AiReportReceipt {
        require(message.isNotBlank() && message.length <= 4000 && notes.length <= 1000)
        requireReceipt(receipt)
        HermesNetworkPolicy.requireExternalNetworkAllowed(context, ENDPOINT, "AI content report")
        val body = JSONObject().put("schema", 1).put("request_id", receipt.id)
            .put("deletion_secret", receipt.deletionSecret).put("reason", reason.wireValue)
            .put("message", message).put("notes", notes).put("app_version", BuildConfig.VERSION_NAME)
            .put("edition", if (BuildConfig.HERMES_PLAY_EDITION) "play" else "full")
        val request = Request.Builder().url("$ENDPOINT/v1/reports")
            .post(body.toString().toRequestBody(JSON)).build()
        http.newCall(request).execute().use { response ->
            if (response.code !in setOf(200, 201)) throw IOException("Report not confirmed (${response.code})")
            val result = JSONObject(response.body?.string().orEmpty())
            if (!result.optBoolean("received") || result.optString("report_id") != receipt.id) {
                throw IOException("Invalid report receipt")
            }
        }
        return receipt.copy(submitted = true).also(receipts::save)
    }

    fun delete(receipt: AiReportReceipt) {
        requireReceipt(receipt)
        HermesNetworkPolicy.requireExternalNetworkAllowed(context, ENDPOINT, "report deletion")
        val body = JSONObject().put("deletion_secret", receipt.deletionSecret)
        val request = Request.Builder().url("$ENDPOINT/v1/reports/${receipt.id}")
            .delete(body.toString().toRequestBody(JSON)).build()
        http.newCall(request).execute().use { response ->
            // The fixed private endpoint deliberately returns 404 for expired or absent reports.
            if (response.code !in setOf(204, 404)) throw IOException("Deletion not confirmed (${response.code})")
        }
        receipts.forget(receipt.id)
    }

    private fun requireReceipt(receipt: AiReportReceipt) {
        require(UUID.fromString(receipt.id).toString() == receipt.id)
        require(receipt.deletionSecret.matches(Regex("[0-9a-f]{64}")))
        require(receipts.load().any { it.id == receipt.id && it.deletionSecret == receipt.deletionSecret })
    }

    companion object {
        const val ENDPOINT = "https://hermes-content-reports.adybag14.workers.dev"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
