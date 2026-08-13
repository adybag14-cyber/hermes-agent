package com.mobilefork.hermesagent.macrobenchmark

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal const val TARGET_PACKAGE = "com.mobilefork.hermesagent"
internal const val SETTINGS_CONTENT_TAG = "HermesSettingsContentList"
internal const val CHAT_DRAWER_TAG = "HermesChatDrawerButton"
internal const val PHONE_SETTINGS_TAG = "HermesNavSettings"
internal const val TABLET_SETTINGS_TAG = "HermesRailSettings"
internal const val SETTINGS_MODELS_PAGE_TAG = "HermesSettingsPage_Models"

private const val SOURCE_DIGEST_METADATA =
    "com.mobilefork.hermesagent.benchmark.SOURCE_DIGEST"
private const val VERSION_NAME_METADATA =
    "com.mobilefork.hermesagent.benchmark.VERSION_NAME"
private const val VERSION_CODE_METADATA =
    "com.mobilefork.hermesagent.benchmark.VERSION_CODE"
private const val LITERTLM_COORDINATE_METADATA =
    "com.mobilefork.hermesagent.benchmark.LITERTLM_COORDINATE"
private const val EVIDENCE_TOKEN_DOMAIN = "hermes-macrobenchmark-evidence-v2"
private const val EVIDENCE_TOKEN_HEX_DIGITS = 13
private const val MAX_EXACT_DOUBLE_INTEGER = (1L shl 53) - 1
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val RUN_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{15,79}")
private val AVD_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
private val BOOT_ID_PATTERN = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")

internal data class VerifiedTargetIdentity(
    val sourceDigest: String,
    val versionName: String,
    val versionCode: Long,
    val liteRtLmCoordinate: String,
    val targetApkSha256: String,
    val benchmarkApkSha256: String,
    val evidenceRunId: String,
    val evidenceProfile: String,
    val avdName: String,
    val bootId: String,
    val evidenceToken: Long,
)

internal fun verifyInstalledTargetIdentity(): VerifiedTargetIdentity {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val arguments = InstrumentationRegistry.getArguments()
    val packageManager = instrumentation.context.packageManager
    val benchmarkApplicationInfo = instrumentation.context.applicationInfo
    val device = UiDevice.getInstance(instrumentation)
    @Suppress("DEPRECATION")
    val packageInfo = packageManager.getPackageInfo(TARGET_PACKAGE, PackageManager.GET_META_DATA)
    @Suppress("DEPRECATION")
    val applicationInfo = packageManager.getApplicationInfo(
        TARGET_PACKAGE,
        PackageManager.GET_META_DATA,
    )
    val metadata = requireNotNull(applicationInfo.metaData) {
        "The installed target has no benchmark identity metadata"
    }

    check(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
        "Refusing to benchmark a debuggable target APK"
    }
    check(applicationInfo.isProfileableByShell) {
        "The installed target is not profileable by shell"
    }
    check(applicationInfo.splitSourceDirs.isNullOrEmpty()) {
        "The benchmark identity currently requires one universal target APK"
    }
    check(instrumentation.context.packageName != TARGET_PACKAGE) {
        "The benchmark must execute from a separate package"
    }
    check(benchmarkApplicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        "The separate benchmark process is expected to be locally debuggable"
    }
    check(benchmarkApplicationInfo.splitSourceDirs.isNullOrEmpty()) {
        "The benchmark identity currently requires one benchmark APK"
    }

    val sourceDigest = metadata.requiredString(SOURCE_DIGEST_METADATA)
    val versionName = metadata.requiredString(VERSION_NAME_METADATA)
    val versionCode = packageInfo.longVersionCodeCompat()
    val metadataVersionCode = metadata.requiredString(VERSION_CODE_METADATA).toLongOrNull()
    val liteRtLmCoordinate = metadata.requiredString(LITERTLM_COORDINATE_METADATA)
    val targetApkSha256 = sha256(File(applicationInfo.sourceDir))
    val benchmarkApkSha256 = sha256(File(benchmarkApplicationInfo.sourceDir))

    val expectedSourceDigest = arguments.requiredString("hermes.expectedSourceDigest")
    val expectedVersionName = arguments.requiredString("hermes.expectedVersionName")
    val expectedVersionCode = arguments.requiredString("hermes.expectedVersionCode").toLongOrNull()
    val expectedLiteRtLmCoordinate =
        arguments.requiredString("hermes.expectedLiteRtLmCoordinate")
    val expectedTargetApkSha256 =
        arguments.requiredString("hermes.expectedTargetApkSha256")
    val expectedBenchmarkApkSha256 =
        arguments.requiredString("hermes.expectedBenchmarkApkSha256")
    val evidenceRunId = arguments.requiredString("hermes.evidenceRunId")
    val evidenceProfile = arguments.requiredString("hermes.evidenceProfile")
    val expectedAvdName = arguments.requiredString("hermes.expectedAvdName")
    val expectedBootId = arguments.requiredString("hermes.expectedBootId").lowercase()
    val avdName = device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim()
    val bootId = device.executeShellCommand("cat /proc/sys/kernel/random/boot_id").trim().lowercase()

    check(SHA256_PATTERN.matches(expectedSourceDigest)) {
        "hermes.expectedSourceDigest must be one lowercase SHA-256 digest"
    }
    check(SHA256_PATTERN.matches(expectedTargetApkSha256)) {
        "hermes.expectedTargetApkSha256 must be one lowercase SHA-256 digest"
    }
    check(SHA256_PATTERN.matches(expectedBenchmarkApkSha256)) {
        "hermes.expectedBenchmarkApkSha256 must be one lowercase SHA-256 digest"
    }
    check(RUN_ID_PATTERN.matches(evidenceRunId)) {
        "hermes.evidenceRunId has an invalid release-evidence identity"
    }
    check(evidenceProfile in setOf("phone-compact", "tablet")) {
        "hermes.evidenceProfile must be phone-compact or tablet"
    }
    check(AVD_NAME_PATTERN.matches(expectedAvdName) && AVD_NAME_PATTERN.matches(avdName)) {
        "The expected or observed AVD name is invalid"
    }
    check(BOOT_ID_PATTERN.matches(expectedBootId) && BOOT_ID_PATTERN.matches(bootId)) {
        "The expected or observed kernel boot ID is invalid"
    }
    check(avdName == expectedAvdName) {
        "Observed AVD $avdName != expected $expectedAvdName"
    }
    check(bootId == expectedBootId) {
        "Observed boot ID $bootId != expected $expectedBootId"
    }
    check(SHA256_PATTERN.matches(sourceDigest)) {
        "The installed target source digest is not release-bound: $sourceDigest"
    }
    check(expectedVersionCode != null && expectedVersionCode > 0) {
        "hermes.expectedVersionCode must be a positive integer"
    }
    check(metadataVersionCode == versionCode) {
        "Installed manifest version code metadata $metadataVersionCode != package $versionCode"
    }
    check(sourceDigest == expectedSourceDigest) {
        "Installed source digest $sourceDigest != expected $expectedSourceDigest"
    }
    check(versionName == expectedVersionName && packageInfo.versionName == expectedVersionName) {
        "Installed version ${packageInfo.versionName}/$versionName != expected $expectedVersionName"
    }
    check(versionCode == expectedVersionCode) {
        "Installed version code $versionCode != expected $expectedVersionCode"
    }
    check(liteRtLmCoordinate == expectedLiteRtLmCoordinate) {
        "Installed LiteRT-LM coordinate $liteRtLmCoordinate != expected $expectedLiteRtLmCoordinate"
    }
    check(targetApkSha256 == expectedTargetApkSha256) {
        "Installed target APK SHA-256 $targetApkSha256 != expected $expectedTargetApkSha256"
    }
    check(benchmarkApkSha256 == expectedBenchmarkApkSha256) {
        "Installed benchmark APK SHA-256 $benchmarkApkSha256 != " +
            "expected $expectedBenchmarkApkSha256"
    }
    val evidenceToken = hermesEvidenceToken(
        sourceDigest = sourceDigest,
        targetApkSha256 = targetApkSha256,
        benchmarkApkSha256 = benchmarkApkSha256,
        evidenceRunId = evidenceRunId,
        evidenceProfile = evidenceProfile,
        avdName = avdName,
        bootId = bootId,
    )

    return VerifiedTargetIdentity(
        sourceDigest = sourceDigest,
        versionName = versionName,
        versionCode = versionCode,
        liteRtLmCoordinate = liteRtLmCoordinate,
        targetApkSha256 = targetApkSha256,
        benchmarkApkSha256 = benchmarkApkSha256,
        evidenceRunId = evidenceRunId,
        evidenceProfile = evidenceProfile,
        avdName = avdName,
        bootId = bootId,
        evidenceToken = evidenceToken,
    ).also { identity ->
        Log.i(
            "HermesMacrobenchmarkIdentity",
            "sourceDigest=${identity.sourceDigest} " +
                "versionName=${identity.versionName} " +
                "versionCode=${identity.versionCode} " +
                "liteRtLmCoordinate=${identity.liteRtLmCoordinate} " +
                "targetApkSha256=${identity.targetApkSha256} " +
                "benchmarkApkSha256=${identity.benchmarkApkSha256} " +
                "evidenceRunId=${identity.evidenceRunId} " +
                "evidenceProfile=${identity.evidenceProfile} " +
                "avdName=${identity.avdName} " +
                "bootId=${identity.bootId} " +
                "evidenceToken=${identity.evidenceToken}",
        )
    }
}

internal fun hermesEvidenceToken(
    sourceDigest: String,
    targetApkSha256: String,
    benchmarkApkSha256: String,
    evidenceRunId: String,
    evidenceProfile: String,
    avdName: String,
    bootId: String,
): Long {
    val canonicalIdentity = buildString {
        append(EVIDENCE_TOKEN_DOMAIN).append('\n')
        append(sourceDigest).append('\n')
        append(targetApkSha256).append('\n')
        append(benchmarkApkSha256).append('\n')
        append(evidenceRunId).append('\n')
        append(evidenceProfile).append('\n')
        append(avdName).append('\n')
        append(bootId).append('\n')
    }
    val digestHex = MessageDigest.getInstance("SHA-256")
        .digest(canonicalIdentity.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
    return digestHex.take(EVIDENCE_TOKEN_HEX_DIGITS).toLong(radix = 16).also { token ->
        check(token in 0..MAX_EXACT_DOUBLE_INTEGER) {
            "Hermes evidence token is not exactly representable as a Double: $token"
        }
    }
}

private fun Bundle.requiredString(key: String): String {
    @Suppress("DEPRECATION")
    val value = get(key)?.toString()?.trim().orEmpty()
    check(value.isNotEmpty()) { "Missing required benchmark value: $key" }
    return value
}

private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= 28) longVersionCode else {
        @Suppress("DEPRECATION")
        versionCode.toLong()
    }

private fun sha256(file: File): String {
    check(file.isFile) { "Installed target APK is not readable: $file" }
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
