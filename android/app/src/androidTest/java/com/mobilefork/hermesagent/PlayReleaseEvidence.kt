package com.mobilefork.hermesagent

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File

/** Optional for ordinary debug tests; the release verifier requires every source-bound record. */
internal object PlayReleaseEvidence {
    fun capture(context: Context, name: String): JSONObject? {
        if (InstrumentationRegistry.getArguments().getString("record_play_release_evidence") != "true") return null
        val identity = ReleaseDeviceEvidenceIdentity.requireBound(context)
        require(name.matches(Regex("[a-z0-9-]+")))
        val directory = File(context.filesDir, "hermes-play-evidence/${identity.evidenceRunId}")
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, "$name.png")
        check(!file.exists())
        // Compose idleness does not wait for the platform Dialog window's entrance animation.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        android.os.SystemClock.sleep(400)
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            return JSONObject().put("file", file.name).put("sha256", ReleaseDeviceEvidenceIdentity.sha256(file))
                .put("bytes", file.length()).put("width", bitmap.width).put("height", bitmap.height)
        } finally { bitmap.recycle() }
    }

    fun emit(context: Context, case: String, details: JSONObject) {
        if (InstrumentationRegistry.getArguments().getString("record_play_release_evidence") != "true") return
        check(BuildConfig.HERMES_PLAY_EDITION)
        val identity = ReleaseDeviceEvidenceIdentity.requireBound(context)
        check(identity.buildVariant == "playDebug")
        require(case.matches(Regex("[a-z0-9][a-z0-9-]{0,79}")))
        val directory = File(context.filesDir, "hermes-play-evidence/${identity.evidenceRunId}")
        check(directory.isDirectory || directory.mkdirs())
        val output = File(directory, "$case.json")
        check(!output.exists()) { "Use a fresh run id; refusing to replace prior Play evidence" }
        val record = JSONObject()
            .put("schema", "hermes-play-release-v1").put("case", case).put("result", "passed")
            .put("edition", "play").put("source_digest", identity.releaseSourceDigest)
            .put("candidate_apk_sha256", identity.candidateApkSha256)
            .put("instrumentation_apk_sha256", identity.instrumentationApkSha256)
            .put("evidence_run_id", identity.evidenceRunId).put("package", identity.packageId)
            .put("version_name", identity.versionName).put("version_code", identity.versionCode)
            .put("build_variant", identity.buildVariant).put("avd_name", identity.avdName)
            .put("device_serial", identity.deviceSerial).put("device_boot_id", identity.deviceBootId)
            .put("android_sdk", Build.VERSION.SDK_INT).put("build_fingerprint", Build.FINGERPRINT)
            .put("page_size", Os.sysconf(OsConstants._SC_PAGESIZE))
            .put("recorded_at_epoch_ms", System.currentTimeMillis()).put("details", details)
            .put("evidence_file", output.absolutePath)
        output.writeText(record.toString(2), Charsets.UTF_8)
        InstrumentationRegistry.getInstrumentation().addResults(Bundle().apply {
            putString("HERMES_PLAY_EVIDENCE_$case", record.toString())
        })
    }
}
