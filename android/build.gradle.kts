plugins {
    id("com.android.application") version "8.9.3" apply false
    id("com.android.test") version "8.9.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.chaquo.python") version "17.0.0" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

gradle.taskGraph.whenReady {
    val guardedAppBenchmarkTasks = setOf(
        "assembleBenchmark",
        "bundleBenchmark",
        "packageBenchmark",
        "connectedBenchmarkAndroidTest",
    )
    val guardedMacrobenchmarkTasks = setOf(
        "assembleBenchmark",
        "bundleBenchmark",
        "packageBenchmark",
        "connectedBenchmarkAndroidTest",
        "connectedCheck",
    )
    val requestedBenchmarkEvidence = allTasks.any { task ->
        (task.project.path == ":app" && task.name in guardedAppBenchmarkTasks) ||
            (task.project.path == ":macrobenchmark" &&
                task.name in guardedMacrobenchmarkTasks)
    }
    if (!requestedBenchmarkEvidence) return@whenReady

    val sourceDigest = System.getenv("HERMES_SOURCE_DIGEST").orEmpty().trim()
    val releaseTag = System.getenv("HERMES_RELEASE_TAG").orEmpty().trim()
    val projectProperties = gradle.startParameter.projectProperties
    val liteRtLmVersion = projectProperties["hermesLiteRtLmVersion"]
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "0.16.1"
    val liteRtLmLocalAar = projectProperties["hermesLiteRtLmLocalAar"]
        ?.trim()
        .orEmpty()

    require(Regex("[0-9a-f]{64}").matches(sourceDigest)) {
        "Benchmark artifact and connected tasks require one lowercase HERMES_SOURCE_DIGEST"
    }
    require(Regex("v\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?").matches(releaseTag)) {
        "Benchmark artifact and connected tasks require an exact semver HERMES_RELEASE_TAG"
    }
    require(liteRtLmVersion == "0.16.1" && liteRtLmLocalAar.isEmpty()) {
        "Release benchmark evidence requires LiteRT-LM 0.16.1 and forbids a local AAR"
    }

    val expectedSourceDigest = projectProperties["hermesBenchmarkExpectedSourceDigest"]
        ?.trim()
        .orEmpty()
    val expectedVersionName = projectProperties["hermesBenchmarkExpectedVersionName"]
        ?.trim()
        .orEmpty()
    val expectedLiteRtLmCoordinate =
        projectProperties["hermesBenchmarkExpectedLiteRtLmCoordinate"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "com.google.ai.edge.litertlm:litertlm-android:0.16.1"
    require(expectedSourceDigest.isEmpty() || expectedSourceDigest == sourceDigest) {
        "hermesBenchmarkExpectedSourceDigest must equal HERMES_SOURCE_DIGEST"
    }
    require(expectedVersionName.isEmpty() || expectedVersionName == releaseTag.removePrefix("v")) {
        "hermesBenchmarkExpectedVersionName must equal HERMES_RELEASE_TAG without v"
    }
    require(
        expectedLiteRtLmCoordinate ==
            "com.google.ai.edge.litertlm:litertlm-android:0.16.1"
    ) {
        "hermesBenchmarkExpectedLiteRtLmCoordinate must equal the release coordinate"
    }

    val requestedConnectedBenchmark = allTasks.any { task ->
        task.project.path == ":macrobenchmark" &&
            task.name in setOf("connectedBenchmarkAndroidTest", "connectedCheck")
    }
    if (requestedConnectedBenchmark) {
        val expectedVersionCode = projectProperties["hermesBenchmarkExpectedVersionCode"]
            ?.trim()
            .orEmpty()
        val expectedTargetApkSha256 = projectProperties["hermesBenchmarkTargetApkSha256"]
            ?.trim()
            .orEmpty()
        val expectedBenchmarkApkSha256 = projectProperties["hermesBenchmarkApkSha256"]
            ?.trim()
            .orEmpty()
        val evidenceRunId = projectProperties["hermesBenchmarkEvidenceRunId"]
            ?.trim()
            .orEmpty()
        val evidenceProfile = projectProperties["hermesBenchmarkEvidenceProfile"]
            ?.trim()
            .orEmpty()
        val expectedAvdName = projectProperties["hermesBenchmarkExpectedAvdName"]
            ?.trim()
            .orEmpty()
        val expectedBootId = projectProperties["hermesBenchmarkExpectedBootId"]
            ?.trim()
            .orEmpty()
        require(Regex("[1-9]\\d*").matches(expectedVersionCode)) {
            "Connected benchmark evidence requires hermesBenchmarkExpectedVersionCode"
        }
        require(Regex("[0-9a-f]{64}").matches(expectedTargetApkSha256)) {
            "Connected benchmark evidence requires a host-recorded " +
                "hermesBenchmarkTargetApkSha256"
        }
        require(Regex("[0-9a-f]{64}").matches(expectedBenchmarkApkSha256)) {
            "Connected benchmark evidence requires a host-recorded hermesBenchmarkApkSha256"
        }
        require(Regex("[a-z0-9][a-z0-9._-]{15,79}").matches(evidenceRunId)) {
            "Connected benchmark evidence requires hermesBenchmarkEvidenceRunId"
        }
        require(evidenceProfile in setOf("phone-compact", "tablet")) {
            "Connected benchmark evidence profile must be phone-compact or tablet"
        }
        require(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}").matches(expectedAvdName)) {
            "Connected benchmark evidence requires hermesBenchmarkExpectedAvdName"
        }
        require(
            Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(expectedBootId)
        ) {
            "Connected benchmark evidence requires one lowercase hermesBenchmarkExpectedBootId"
        }
    }
}
