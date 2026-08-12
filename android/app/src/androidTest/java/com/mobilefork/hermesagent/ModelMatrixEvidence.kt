package com.mobilefork.hermesagent

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File

/**
 * Writes one durable, machine-readable record for every completed real-model matrix run.
 *
 * The record lives in debuggable app-private storage so the release harness can retrieve it
 * with `run-as`, and the compact one-line copy is also visible in instrumentation output.
 * Failed or skipped tests never emit a passing record.
 */
internal object ModelMatrixEvidence {
    data class Record(
        val backend: String,
        val instrumentationMethod: String,
        val modelId: String,
        val publisherRepository: String,
        val publisherRevision: String,
        val fileName: String,
        val devicePath: String,
        val publisherExpectedBytes: Long,
        val deviceVisibleBytes: Long,
        val expectedSha256: String,
        val deviceSha256: String,
        val runtimeStarted: Boolean,
        val healthOk: Boolean,
        val completionNonEmpty: Boolean,
        val elapsedMs: Long,
        val accelerator: String,
        val statusMessage: String,
        val details: JSONObject = JSONObject(),
    )

    fun emit(context: Context, record: Record): File {
        val releaseIdentity = ReleaseDeviceEvidenceIdentity.requireBound(context)
        val recordedAtMs = System.currentTimeMillis()
        val outputDirectory = File(context.filesDir, EVIDENCE_DIRECTORY).apply { mkdirs() }
        val safeBackend = safeFilePart(record.backend)
        val safeArtifact = safeFilePart(record.fileName)
        val outputFile = File(outputDirectory, "$safeBackend-$safeArtifact-$recordedAtMs.json")
        val contentAddressed = record.publisherRepository.isNotBlank() &&
            record.publisherRevision.matches(Regex("[0-9a-fA-F]{40}")) &&
            record.publisherExpectedBytes > 0L &&
            record.deviceVisibleBytes == record.publisherExpectedBytes &&
            record.expectedSha256.matches(Regex("[0-9a-fA-F]{64}")) &&
            record.deviceSha256.equals(record.expectedSha256, ignoreCase = true)
        val evidenceComplete = contentAddressed &&
            record.runtimeStarted &&
            record.healthOk &&
            record.completionNonEmpty &&
            record.elapsedMs > 0L
        val payload = JSONObject()
            .put("schema", SCHEMA)
            .put("release_source_digest", releaseIdentity.releaseSourceDigest)
            .put("candidate_apk_sha256", releaseIdentity.candidateApkSha256)
            .put("instrumentation_apk_sha256", releaseIdentity.instrumentationApkSha256)
            .put("evidence_run_id", releaseIdentity.evidenceRunId)
            .put("package_id", releaseIdentity.packageId)
            .put("version_name", releaseIdentity.versionName)
            .put("version_code", releaseIdentity.versionCode)
            .put("build_variant", releaseIdentity.buildVariant)
            .put("litertlm_coordinate", releaseIdentity.liteRtLmCoordinate)
            .put("result", if (evidenceComplete) "passed" else "incomplete")
            .put("evidence_complete", evidenceComplete)
            .put("content_addressed", contentAddressed)
            .put("backend", record.backend)
            .put("instrumentation_method", record.instrumentationMethod)
            .put("model_id", record.modelId)
            .put("publisher_repository", record.publisherRepository)
            .put("publisher_revision", record.publisherRevision)
            .put("file_name", record.fileName)
            .put("device_path", record.devicePath)
            .put("publisher_expected_bytes", record.publisherExpectedBytes)
            .put("device_visible_bytes", record.deviceVisibleBytes)
            .put("expected_sha256", record.expectedSha256.lowercase())
            .put("device_sha256", record.deviceSha256.lowercase())
            .put("runtime_started", record.runtimeStarted)
            .put("health_ok", record.healthOk)
            .put("completion_nonempty", record.completionNonEmpty)
            .put("elapsed_ms", record.elapsedMs)
            .put("accelerator", record.accelerator)
            .put("status_message", record.statusMessage)
            .put("device_model", Build.MODEL)
            .put("device_serial", releaseIdentity.deviceSerial)
            .put("avd_name", releaseIdentity.avdName)
            .put("device_boot_id", releaseIdentity.deviceBootId)
            .put("build_fingerprint", Build.FINGERPRINT)
            .put("android_sdk", Build.VERSION.SDK_INT)
            .put("supported_abis", Build.SUPPORTED_ABIS.joinToString(","))
            .put("recorded_at_epoch_ms", recordedAtMs)
            .put("details", JSONObject(record.details.toString()))
            .put("evidence_file", outputFile.absolutePath)

        val temporaryFile = File(outputDirectory, "${outputFile.name}.tmp")
        temporaryFile.writeText(payload.toString(2), Charsets.UTF_8)
        if (!temporaryFile.renameTo(outputFile)) {
            outputFile.writeText(payload.toString(2), Charsets.UTF_8)
            temporaryFile.delete()
        }
        check(outputFile.isFile && outputFile.length() > 0L) {
            "Unable to persist model matrix evidence at ${outputFile.absolutePath}"
        }
        val compactPayload = payload.toString()
        InstrumentationRegistry.getInstrumentation().addResults(
            Bundle().apply { putString(RESULT_KEY, compactPayload) },
        )
        Log.i(LOG_TAG, "$LOG_PREFIX$compactPayload")
        println("$LOG_PREFIX$compactPayload")
        check(evidenceComplete) {
            "Model matrix evidence is incomplete for ${record.backend}/${record.fileName}; see ${outputFile.absolutePath}"
        }
        return outputFile
    }

    private fun safeFilePart(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_')
        .ifBlank { "unknown" }
        .take(120)

    private const val SCHEMA = "hermes-model-evidence-v1"
    private const val EVIDENCE_DIRECTORY = "hermes-model-evidence"
    private const val RESULT_KEY = "HERMES_MODEL_EVIDENCE"
    private const val LOG_TAG = "HermesModelEvidence"
    const val LOG_PREFIX = "HERMES_MODEL_EVIDENCE "
}
