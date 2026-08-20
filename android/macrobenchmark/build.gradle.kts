import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

val expectedSourceDigest = providers.gradleProperty("hermesBenchmarkExpectedSourceDigest")
    .orElse(providers.environmentVariable("HERMES_SOURCE_DIGEST"))
    .orElse("")
val expectedVersionName = providers.gradleProperty("hermesBenchmarkExpectedVersionName")
    .orElse(providers.environmentVariable("HERMES_RELEASE_TAG").map { it.removePrefix("v") })
    .orElse("")
val expectedVersionCode = providers.gradleProperty("hermesBenchmarkExpectedVersionCode")
    .orElse("")
val expectedLiteRtLmCoordinate = providers.gradleProperty("hermesBenchmarkExpectedLiteRtLmCoordinate")
    .orElse("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
val expectedTargetApkSha256 = providers.gradleProperty("hermesBenchmarkTargetApkSha256")
    .orElse("")
val expectedBenchmarkApkSha256 = providers.gradleProperty("hermesBenchmarkApkSha256")
    .orElse("")
val evidenceRunId = providers.gradleProperty("hermesBenchmarkEvidenceRunId")
    .orElse("")
val evidenceProfile = providers.gradleProperty("hermesBenchmarkEvidenceProfile")
    .orElse("")
val expectedAvdName = providers.gradleProperty("hermesBenchmarkExpectedAvdName")
    .orElse("")
val expectedBootId = providers.gradleProperty("hermesBenchmarkExpectedBootId")
    .orElse("")

android {
    namespace = "com.mobilefork.hermesagent.macrobenchmark"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
        testInstrumentationRunnerArguments["hermes.expectedSourceDigest"] = expectedSourceDigest.get()
        testInstrumentationRunnerArguments["hermes.expectedVersionName"] = expectedVersionName.get()
        testInstrumentationRunnerArguments["hermes.expectedVersionCode"] = expectedVersionCode.get()
        testInstrumentationRunnerArguments["hermes.expectedLiteRtLmCoordinate"] =
            expectedLiteRtLmCoordinate.get()
        testInstrumentationRunnerArguments["hermes.expectedTargetApkSha256"] =
            expectedTargetApkSha256.get()
        testInstrumentationRunnerArguments["hermes.expectedBenchmarkApkSha256"] =
            expectedBenchmarkApkSha256.get()
        testInstrumentationRunnerArguments["hermes.evidenceRunId"] = evidenceRunId.get()
        testInstrumentationRunnerArguments["hermes.evidenceProfile"] = evidenceProfile.get()
        testInstrumentationRunnerArguments["hermes.expectedAvdName"] = expectedAvdName.get()
        testInstrumentationRunnerArguments["hermes.expectedBootId"] = expectedBootId.get()
        testInstrumentationRunnerArguments[
            "androidx.benchmark.output.payload.sourceDigest"
        ] = expectedSourceDigest.get()
        testInstrumentationRunnerArguments[
            "androidx.benchmark.output.payload.targetApkSha256"
        ] = expectedTargetApkSha256.get()
        testInstrumentationRunnerArguments[
            "androidx.benchmark.output.payload.benchmarkApkSha256"
        ] = expectedBenchmarkApkSha256.get()
        testInstrumentationRunnerArguments[
            "androidx.benchmark.output.payload.evidenceRunId"
        ] = evidenceRunId.get()
        testInstrumentationRunnerArguments[
            "androidx.benchmark.output.payload.evidenceProfile"
        ] = evidenceProfile.get()
        testInstrumentationRunnerArguments[
            "androidx.benchmark.output.payload.avdName"
        ] = expectedAvdName.get()
        testInstrumentationRunnerArguments[
            "androidx.benchmark.output.payload.bootId"
        ] = expectedBootId.get()
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark"
    }
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.0")
}
