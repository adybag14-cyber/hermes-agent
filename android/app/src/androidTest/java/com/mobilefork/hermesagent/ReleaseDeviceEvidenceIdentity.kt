package com.mobilefork.hermesagent

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest

/**
 * Binds headed-device evidence to both the committed source identity embedded in the app and the
 * exact app/test APK bytes installed for the run. Release evidence must never be emitted from an
 * unbound ordinary debug build or from an instrumentation APK other than the one named by the
 * release harness.
 */
internal object ReleaseDeviceEvidenceIdentity {
    data class Identity(
        val releaseSourceDigest: String,
        val candidateApkSha256: String,
        val instrumentationApkSha256: String,
        val evidenceRunId: String,
        val packageId: String,
        val versionName: String,
        val versionCode: Int,
        val buildVariant: String,
        val liteRtLmCoordinate: String,
        val deviceSerial: String,
        val avdName: String,
        val deviceBootId: String,
    )

    @Volatile
    private var cached: Identity? = null

    fun requireBound(appContext: Context): Identity {
        cached?.let { return it }
        return synchronized(this) {
            cached?.let { return@synchronized it }
            val arguments = InstrumentationRegistry.getArguments()
            val sourceDigest = arguments.getString(ARG_SOURCE_DIGEST).orEmpty().trim().lowercase()
            val candidateDigest = arguments.getString(ARG_CANDIDATE_APK_SHA256).orEmpty().trim().lowercase()
            val instrumentationDigest = arguments.getString(ARG_INSTRUMENTATION_APK_SHA256)
                .orEmpty()
                .trim()
                .lowercase()
            val evidenceRunId = arguments.getString(ARG_EVIDENCE_RUN_ID).orEmpty().trim().lowercase()
            val deviceSerial = arguments.getString(ARG_DEVICE_SERIAL).orEmpty().trim()
            val avdName = arguments.getString(ARG_AVD_NAME).orEmpty().trim()
            require(SHA256.matches(sourceDigest)) {
                "Release evidence requires -e $ARG_SOURCE_DIGEST with the committed source SHA-256"
            }
            require(SHA256.matches(candidateDigest)) {
                "Release evidence requires -e $ARG_CANDIDATE_APK_SHA256 with the candidate APK SHA-256"
            }
            require(SHA256.matches(instrumentationDigest)) {
                "Release evidence requires -e $ARG_INSTRUMENTATION_APK_SHA256 with the test APK SHA-256"
            }
            require(RUN_ID.matches(evidenceRunId)) {
                "Release evidence requires -e $ARG_EVIDENCE_RUN_ID with a stable run identifier"
            }
            require(deviceSerial.startsWith("emulator-") && deviceSerial.drop(9).all(Char::isDigit)) {
                "Release evidence requires -e $ARG_DEVICE_SERIAL with the active emulator serial"
            }
            require(AVD_NAME.matches(avdName)) {
                "Release evidence requires -e $ARG_AVD_NAME with the active AVD name"
            }
            val observedAvdName = readSystemProperty("ro.boot.qemu.avd_name")
            check(observedAvdName == avdName) {
                "Executing AVD $observedAvdName does not match requested evidence AVD $avdName"
            }
            val deviceBootId = File("/proc/sys/kernel/random/boot_id")
                .readText(Charsets.UTF_8)
                .trim()
                .lowercase()
            check(BOOT_ID.matches(deviceBootId)) {
                "Executing device did not expose a valid kernel boot identity"
            }
            check(BuildConfig.HERMES_SOURCE_DIGEST == sourceDigest) {
                "Installed candidate source digest ${BuildConfig.HERMES_SOURCE_DIGEST} does not match $sourceDigest"
            }
            check(!BuildConfig.HERMES_LITERTLM_LOCAL_AAR && BuildConfig.HERMES_LITERTLM_COORDINATE == LITERTLM_COORDINATE) {
                "Release evidence requires exact dependency $LITERTLM_COORDINATE; installed candidate reports " +
                    "${BuildConfig.HERMES_LITERTLM_COORDINATE} (localAar=${BuildConfig.HERMES_LITERTLM_LOCAL_AAR})"
            }

            val actualCandidateDigest = sha256(File(appContext.applicationInfo.sourceDir))
            check(actualCandidateDigest == candidateDigest) {
                "Installed candidate APK SHA-256 $actualCandidateDigest does not match $candidateDigest"
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val actualInstrumentationDigest = sha256(File(instrumentation.context.applicationInfo.sourceDir))
            check(actualInstrumentationDigest == instrumentationDigest) {
                "Installed instrumentation APK SHA-256 $actualInstrumentationDigest does not match $instrumentationDigest"
            }
            Identity(
                releaseSourceDigest = sourceDigest,
                candidateApkSha256 = candidateDigest,
                instrumentationApkSha256 = instrumentationDigest,
                evidenceRunId = evidenceRunId,
                packageId = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                buildVariant = BuildConfig.BUILD_TYPE,
                liteRtLmCoordinate = BuildConfig.HERMES_LITERTLM_COORDINATE,
                deviceSerial = deviceSerial,
                avdName = observedAvdName,
                deviceBootId = deviceBootId,
            ).also { cached = it }
        }
    }

    private fun readSystemProperty(name: String): String {
        val process = ProcessBuilder("/system/bin/getprop", name)
            .redirectErrorStream(true)
            .start()
        val value = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        check(process.waitFor() == 0 && value.isNotBlank()) {
            "Unable to read required Android system property $name"
        }
        return value
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
    private val RUN_ID = Regex("[a-z0-9][a-z0-9._-]{15,79}")
    private val AVD_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
    private val BOOT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private const val ARG_SOURCE_DIGEST = "release_source_digest"
    private const val ARG_CANDIDATE_APK_SHA256 = "candidate_apk_sha256"
    private const val ARG_INSTRUMENTATION_APK_SHA256 = "instrumentation_apk_sha256"
    private const val ARG_EVIDENCE_RUN_ID = "evidence_run_id"
    private const val ARG_DEVICE_SERIAL = "device_serial"
    private const val ARG_AVD_NAME = "avd_name"
    private const val LITERTLM_COORDINATE = "com.google.ai.edge.litertlm:litertlm-android:0.16.0"
}
