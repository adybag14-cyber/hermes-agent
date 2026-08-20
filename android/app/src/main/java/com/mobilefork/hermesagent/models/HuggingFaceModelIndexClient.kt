package com.mobilefork.hermesagent.models

import android.content.Context
import android.util.Base64
import com.mobilefork.hermesagent.data.HermesNetworkPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Locale

private const val MAX_QUICK_START_MODEL_BYTES = 5L * 1024L * 1024L * 1024L

data class DetectedHfModel(
    val id: String,
    val title: String,
    val summary: String,
    val repoOrUrl: String,
    val filePath: String,
    val revision: String,
    val runtimeFlavor: String,
    val sourceLabel: String,
    val expectedBytes: Long? = null,
    val releaseCertified: Boolean = false,
    val immutableRevision: Boolean = false,
) {
    val quickStartEligible: Boolean
        get() = releaseCertified &&
            immutableRevision &&
            expectedBytes != null &&
            expectedBytes in 1..MAX_QUICK_START_MODEL_BYTES
}

object HuggingFaceModelIndexClient {
    const val DEFAULT_INDEX_URL = "https://hf-model-index-worker.adybag14.workers.dev/models.json"

    private const val PUBLIC_JWK_JSON = """
        {
          "key_ops": [
            "verify"
          ],
          "ext": true,
          "kty": "EC",
          "x": "XWxajJMPUVKLpX_RSMALxq0DEAGTwRW0wusRsdSPReQ",
          "y": "QSFU7WbyPwqDuDdd-9lCrF3ZxJOlcGzox34IVjBhIog",
          "crv": "P-256"
        }
    """

    fun fetchDetectedModels(indexUrl: String = DEFAULT_INDEX_URL, context: Context? = null): List<DetectedHfModel> {
        if (context != null) {
            HermesNetworkPolicy.requireExternalNetworkAllowed(
                context,
                indexUrl,
                actionLabel = "model catalog refresh",
            )
        }
        val root = getJson(indexUrl)
        val canonicalPayload = root.optString("payload_canonical").ifBlank {
            stableStringify(root.getJSONObject("payload"))
        }
        verifySignature(canonicalPayload, root.getJSONObject("signature"))
        val payload = JSONObject(canonicalPayload)
        val models = payload.optJSONArray("models") ?: JSONArray()
        return buildList {
            for (index in 0 until models.length()) {
                val model = models.optJSONObject(index) ?: continue
                val download = model.optJSONObject("download") ?: continue
                val repo = download.optString("repo_or_url").trim()
                if (repo.isBlank()) continue
                val filePath = download.optString("file_path").trim()
                val advertisedRuntimeFlavor = download.optString("runtime_flavor").ifBlank {
                    model.optString("runtime_flavor").ifBlank { runtimeFlavorFor(filePath) }
                }
                val binding = revisionBinding(
                    repoOrUrl = repo,
                    filePath = filePath,
                    currentSha = model.optString("current_sha"),
                    advertisedRevision = download.optString("revision").ifBlank { "main" },
                ) ?: continue
                val verifiedArtifact = VerifiedLocalModelArtifacts.find(repo, filePath)
                val runtimeFlavor = verifiedArtifact?.let(::runtimeFlavorForVerifiedArtifact)
                    ?: advertisedRuntimeFlavor
                add(
                    DetectedHfModel(
                        id = model.optString("id").ifBlank { "${repo}:${filePath.ifBlank { runtimeFlavor }}" },
                        title = model.optString("title").ifBlank { repo.substringAfterLast('/') },
                        summary = model.optString("summary"),
                        repoOrUrl = repo,
                        filePath = filePath,
                        revision = binding.revision,
                        runtimeFlavor = runtimeFlavor,
                        sourceLabel = model.optString("source").ifBlank { "Cloudflare catalog" },
                        expectedBytes = verifiedArtifact?.expectedBytes,
                        releaseCertified = binding.releaseCertified,
                        immutableRevision = binding.immutableRevision,
                    )
                )
            }
        }.let(::mobileQuickCatalogModels).let(::prioritizeDetectedModels)
    }

    internal data class RevisionBinding(
        val revision: String,
        val releaseCertified: Boolean,
        val immutableRevision: Boolean,
    )

    internal fun revisionBinding(
        repoOrUrl: String,
        filePath: String,
        currentSha: String,
        advertisedRevision: String,
    ): RevisionBinding? {
        val verified = VerifiedLocalModelArtifacts.find(repoOrUrl, filePath)
        if (verified != null) {
            return RevisionBinding(
                revision = verified.revision,
                releaseCertified = true,
                immutableRevision = true,
            )
        }
        val immutableCurrentSha = currentSha.trim().takeIf(::isImmutableGitRevision)
        val immutableAdvertisedRevision = advertisedRevision.trim().takeIf(::isImmutableGitRevision)
        val revision = immutableCurrentSha ?: immutableAdvertisedRevision ?: return null
        return RevisionBinding(
            revision = revision,
            releaseCertified = false,
            immutableRevision = true,
        )
    }

    internal fun prioritizeDetectedModels(models: List<DetectedHfModel>): List<DetectedHfModel> {
        return models.sortedWith(
            compareBy<DetectedHfModel> { catalogRank(it) }
                .thenBy { it.title.lowercase(Locale.US) }
        )
    }

    internal fun mobileQuickCatalogModels(models: List<DetectedHfModel>): List<DetectedHfModel> {
        // The signed worker's max_model_bytes field is a crawler limit, not the
        // artifact's byte identity. Quick start therefore accepts only artifacts
        // whose immutable revision and exact byte count are bound by the release
        // matrix. Experimental or unknown-size files remain available through the
        // explicit custom repo/file import path, where they cannot masquerade as a
        // release-certified mobile recommendation.
        return models.filter { it.quickStartEligible }
    }

    internal fun preferredDetectedModelId(
        models: List<DetectedHfModel>,
        currentSelectionId: String,
    ): String {
        if (models.any { it.id == currentSelectionId && it.quickStartEligible }) return currentSelectionId
        return models.firstOrNull { it.quickStartEligible }?.id.orEmpty()
    }

    private fun getJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "HermesAndroidModelIndex/1.0")
        }
        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalArgumentException("Model catalog returned HTTP $responseCode: ${errorBody.take(160)}")
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    internal fun runtimeFlavorForVerifiedArtifact(artifact: VerifiedLocalModelArtifacts.Artifact): String {
        return when (artifact.runtime.lowercase(Locale.US)) {
            "litert-lm" -> "LiteRT-LM"
            "llama.cpp" -> "GGUF"
            else -> artifact.runtime
        }
    }

    private fun verifySignature(canonicalPayload: String, signatureJson: JSONObject) {
        val alg = signatureJson.optString("alg")
        require(alg == "ES256") { "Unsupported model catalog signature algorithm: $alg" }
        val signatureBytes = base64UrlDecode(signatureJson.getString("value"))
        val derSignature = if (signatureBytes.firstOrNull() == 0x30.toByte()) {
            signatureBytes
        } else {
            rawEcdsaToDer(signatureBytes)
        }
        val publicKey = publicKeyFromJwk(JSONObject(PUBLIC_JWK_JSON))
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(publicKey)
            update(canonicalPayload.toByteArray(Charsets.UTF_8))
        }
        require(verifier.verify(derSignature)) { "Model catalog signature verification failed" }
    }

    private fun publicKeyFromJwk(jwk: JSONObject): java.security.PublicKey {
        val x = BigInteger(1, base64UrlDecode(jwk.getString("x")))
        val y = BigInteger(1, base64UrlDecode(jwk.getString("y")))
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }
        val spec = parameters.getParameterSpec(ECParameterSpec::class.java)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), spec))
    }

    private fun stableStringify(value: Any?): String {
        if (value == null || value == JSONObject.NULL) return "null"
        return when (value) {
            is JSONObject -> {
                val keys = mutableListOf<String>()
                val iterator = value.keys()
                while (iterator.hasNext()) keys += iterator.next()
                keys.sorted().joinToString(prefix = "{", postfix = "}") { key ->
                    "${JSONObject.quote(key)}:${stableStringify(value.get(key))}"
                }
            }
            is JSONArray -> {
                buildString {
                    append("[")
                    for (index in 0 until value.length()) {
                        if (index > 0) append(",")
                        append(stableStringify(value.get(index)))
                    }
                    append("]")
                }
            }
            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            else -> JSONObject.quote(value.toString())
        }
    }

    private fun rawEcdsaToDer(raw: ByteArray): ByteArray {
        require(raw.size % 2 == 0 && raw.isNotEmpty()) { "Invalid ECDSA signature size" }
        val half = raw.size / 2
        val r = positiveIntegerBytes(raw.copyOfRange(0, half))
        val s = positiveIntegerBytes(raw.copyOfRange(half, raw.size))
        val sequenceLength = 2 + r.size + 2 + s.size
        return byteArrayOf(0x30, sequenceLength.toByte(), 0x02, r.size.toByte()) +
            r +
            byteArrayOf(0x02, s.size.toByte()) +
            s
    }

    private fun positiveIntegerBytes(value: ByteArray): ByteArray {
        var firstNonZero = 0
        while (firstNonZero < value.lastIndex && value[firstNonZero] == 0.toByte()) {
            firstNonZero++
        }
        val trimmed = value.copyOfRange(firstNonZero, value.size)
        return if ((trimmed.first().toInt() and 0x80) != 0) {
            byteArrayOf(0) + trimmed
        } else {
            trimmed
        }
    }

    private fun base64UrlDecode(value: String): ByteArray {
        val padded = value.padEnd(value.length + ((4 - value.length % 4) % 4), '=')
        return Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun runtimeFlavorFor(filePath: String): String {
        val lower = filePath.lowercase(Locale.US)
        return if (lower.endsWith(".litertlm") || lower.endsWith(".task")) "LiteRT-LM" else "GGUF"
    }

    private fun isImmutableGitRevision(value: String): Boolean {
        return Regex("^[0-9a-fA-F]{40}$").matches(value.trim())
    }

    private fun catalogRank(model: DetectedHfModel): Int {
        val combined = "${model.repoOrUrl} ${model.filePath}".lowercase(Locale.US)
        return when {
            model.releaseCertified -> 0
            clearlyOversizedForMobile(combined) -> 100
            model.runtimeFlavor.equals("LiteRT-LM", ignoreCase = true) && "gemma-4-e2b" in combined -> 5
            model.runtimeFlavor.equals("LiteRT-LM", ignoreCase = true) && "gemma-4-e4b" in combined -> 6
            model.runtimeFlavor.equals("LiteRT-LM", ignoreCase = true) -> 10
            "unsloth/qwen3.5-0.8b-gguf" in combined -> 20
            model.repoOrUrl.startsWith("unsloth/", ignoreCase = true) && "q4_k_m" in combined -> 30
            model.repoOrUrl.startsWith("unsloth/", ignoreCase = true) -> 40
            else -> 50
        }
    }

    private fun clearlyOversizedForMobile(combined: String): Boolean {
        return Regex("(?:^|[^0-9])(?:12b|26b|27b|30b|31b|35b)(?:[^0-9]|$)").containsMatchIn(combined)
    }
}
