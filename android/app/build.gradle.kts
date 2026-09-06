import java.util.Properties
import java.io.ByteArrayOutputStream
import org.gradle.process.ExecOperations
import javax.inject.Inject
import com.chaquo.python.PythonPlugin
import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.gradle.api.artifacts.Configuration
import java.nio.file.Files

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

val repoRoot = rootDir.parentFile
interface HermesProcessServices {
    @get:Inject val execOperations: ExecOperations
}
val hermesProcessServices = objects.newInstance<HermesProcessServices>()
val hermesChaquopyLabRoot = providers.gradleProperty("hermesChaquopyLab").orNull
    ?.let { rootProject.file(it).canonicalFile }
require(hermesChaquopyLabRoot == null || !providers.gradleProperty("hermesPythonBundle").isPresent) {
    "Select either a Chaquopy lab or the source-built production Python bundle, not both"
}
val hermesPythonRoot = hermesChaquopyLabRoot
    ?: providers.gradleProperty("hermesPythonBundle").orNull?.let { rootProject.file(it).canonicalFile }
    ?: gradle.gradleUserHomeDir.resolve("hermes-python-runtime").canonicalFile
fun fileSha256(file: File): String = file.inputStream().buffered().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1024 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
val hermesPythonReceipt = hermesPythonRoot.let { lab ->
    val receipt = lab.resolve("consumer.json")
    require(receipt.isFile && receipt.length() <= 4 * 1024 * 1024) {
        "Missing/oversized Python source-bundle receipt; run scripts/prepare_android_python_runtime.py prepare first"
    }
    (JsonSlurper().parse(receipt) as Map<*, *>).also {
        val expectedSchema = if (hermesChaquopyLabRoot != null) "hermes-chaquopy-consumer-v1"
            else "hermes-chaquopy-source-consumer-v1"
        require(it["schema"] == expectedSchema && it["python"] == "3.13") {
            "Unsupported Python consumer; Hermes uses the qualified Python 3.13 runtime"
        }
        require(it["hermes_requirements_sha256"] == fileSha256(repoRoot.resolve("requirements-android-chaquopy.txt"))) {
            "Hermes requirements changed after preparing the Chaquopy consumer"
        }
        if (hermesChaquopyLabRoot == null) {
            require(it["source_lock_sha256"] == fileSha256(repoRoot.resolve("hermes_android/python_runtime.lock.json")) &&
                it["runtime_tested"] == false) { "Production Python bundle differs from the committed source lock" }
        }
    }
}
fun verifyHermesPythonBundle() {
    val lab = hermesPythonRoot
    val receipt = hermesPythonReceipt
    val files = receipt["files"] as List<*>
    val expected = mutableSetOf<String>()
    files.forEach { entry ->
        val item = entry as Map<*, *>
        val path = item["path"] as String
        require(path.matches(Regex("[A-Za-z0-9_./+-]+")) && !path.startsWith('/') &&
            path.split('/').none { it == ".." || it == "." } && expected.add(path)) {
            "Invalid or duplicate Chaquopy consumer path"
        }
        val file = lab.resolve(path)
        require(file.canonicalFile.toPath().startsWith(lab.toPath()) &&
            !Files.isSymbolicLink(file.toPath()) && file.isFile &&
            file.length() == (item["bytes"] as Number).toLong() && fileSha256(file) == item["sha256"]) {
            "Chaquopy consumer input changed: $path"
        }
    }
    val actual = lab.walkTopDown().filter { it.isFile && it != lab.resolve("consumer.json") }
        .map { it.relativeTo(lab).invariantSeparatorsPath }.toSet()
    require(actual == expected) { "Chaquopy consumer contains unrecorded or missing inputs" }
}
verifyHermesPythonBundle()
val hermesVersionFile = repoRoot.resolve("hermes_cli/__init__.py")
val releaseTag = System.getenv("HERMES_RELEASE_TAG").orEmpty().trim()
require(hermesChaquopyLabRoot == null || releaseTag.isBlank()) {
    "The opt-in Chaquopy consumer is a development experiment, not a tagged release input"
}
if (hermesChaquopyLabRoot != null) {
    gradle.taskGraph.whenReady {
        require(allTasks.none { it.project == project && it.name.contains("Release", ignoreCase = true) }) {
            "Chaquopy laboratory inputs may not produce a release variant"
        }
    }
}
val fdroidSourceBindingFileName = "hermes-android-fdroid-source-binding.properties"
val hermesFdroidSourceBindingSetting = providers.gradleProperty("hermesFdroidSourceBinding")
    .orNull
    ?.trim()
    ?.also { configured ->
        require(configured == "true" || configured == "false") {
            "hermesFdroidSourceBinding must be exactly true or false, got '$configured'"
        }
    }
val hermesFdroidSourceBinding = hermesFdroidSourceBindingSetting?.toBoolean() ?: false
val environmentHermesSourceDigest = System.getenv("HERMES_SOURCE_DIGEST")
    .orEmpty()
    .trim()
    .lowercase()
    .also { digest ->
        require(digest.isBlank() || Regex("[0-9a-f]{64}").matches(digest)) {
            "HERMES_SOURCE_DIGEST must be one lowercase SHA-256 digest, got '$digest'"
        }
    }
val fdroidGeneratedSdkLocatorFiles = listOf(
    repoRoot.resolve("local.properties"),
    rootDir.resolve("local.properties"),
    rootDir.resolve("app/local.properties"),
)
val fdroidScannerManagedGradleFiles = listOf(
    rootDir.resolve("gradlew"),
    rootDir.resolve("gradlew.bat"),
    rootDir.resolve("gradle/wrapper/gradle-wrapper.jar"),
)
val fdroidSdkLocatorPresence = fdroidGeneratedSdkLocatorFiles.map { it.isFile }
val fdroidWrapperPresence = fdroidScannerManagedGradleFiles.map { it.isFile }
val ordinaryCheckoutMarkers =
    !fdroidSdkLocatorPresence[0] && !fdroidSdkLocatorPresence[2] && fdroidWrapperPresence.all { it }
val exactFdroidCheckoutMarkers =
    fdroidSdkLocatorPresence.all { it } && fdroidWrapperPresence.none { it }
val fdroidMutationDetected =
    fdroidSdkLocatorPresence[0] ||
        fdroidSdkLocatorPresence[2] ||
        fdroidWrapperPresence.any { !it }
val semanticReleaseTag =
    Regex("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?").matches(releaseTag)
require(releaseTag.isBlank() || semanticReleaseTag) {
    "HERMES_RELEASE_TAG must be an exact semantic release tag, got '$releaseTag'"
}
if (fdroidMutationDetected) {
    require(!ordinaryCheckoutMarkers && exactFdroidCheckoutMarkers) {
        "F-Droid SDK-locator and scanner-wrapper state is partial or contradictory"
    }
    require(environmentHermesSourceDigest.isBlank()) {
        "A transformed F-Droid checkout cannot use HERMES_SOURCE_DIGEST"
    }
    require(semanticReleaseTag) {
        "A transformed F-Droid checkout requires an exact semantic release tag, got '$releaseTag'"
    }
    require(hermesFdroidSourceBindingSetting != "false") {
        "A transformed F-Droid checkout cannot disable source binding"
    }
}
if (semanticReleaseTag && environmentHermesSourceDigest.isBlank()) {
    require(hermesFdroidSourceBindingSetting != "false") {
        "A release-tagged build cannot disable source binding"
    }
}
val automaticFdroidSourceBinding = semanticReleaseTag &&
    environmentHermesSourceDigest.isBlank() &&
    hermesFdroidSourceBindingSetting == null

fun resolvedBuildPython(): String {
    val configured = System.getenv("PYTHON_FOR_BUILD").orEmpty().trim()
    if (configured.isNotBlank()) {
        return configured
    }
    val osName = System.getProperty("os.name").lowercase()
    return if (osName.contains("windows")) "python" else "python3"
}

fun runSourceDigestCommand(script: File, arguments: List<String>): String {
    val identityOutput = ByteArrayOutputStream()
    hermesProcessServices.execOperations.exec {
        commandLine(listOf(resolvedBuildPython(), script.absolutePath) + arguments)
        standardOutput = identityOutput
    }.assertNormalExitValue()
    return identityOutput
        .toString(Charsets.UTF_8)
        .lineSequence()
        .singleOrNull { it.startsWith("sourceDigest=") }
        ?.substringAfter('=')
        ?.also { digest ->
            require(Regex("[0-9a-f]{64}").matches(digest)) {
                "Source identity command returned an invalid digest: '$digest'"
            }
        }
        ?: error("Source identity command did not return exactly one sourceDigest")
}

val hermesSourceDigest = when {
    hermesFdroidSourceBinding -> {
        require(environmentHermesSourceDigest.isBlank()) {
            "F-Droid source binding and HERMES_SOURCE_DIGEST are mutually exclusive authorities"
        }
        require(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?").matches(releaseTag)) {
            "F-Droid source binding requires an exact semantic HERMES_RELEASE_TAG, got '$releaseTag'"
        }
        runSourceDigestCommand(
            repoRoot.resolve("scripts/android_fdroid_source_binding.py"),
            listOf(
                "verify",
                "--repo-root",
                repoRoot.absolutePath,
                "--binding-file",
                gradle.gradleUserHomeDir.resolve(fdroidSourceBindingFileName).absolutePath,
                "--version",
                releaseTag.removePrefix("v"),
            ),
        )
    }
    automaticFdroidSourceBinding -> {
        runSourceDigestCommand(
            repoRoot.resolve("scripts/android_fdroid_source_binding.py"),
            listOf(
                "verify-transformed",
                "--repo-root",
                repoRoot.absolutePath,
                "--version",
                releaseTag.removePrefix("v"),
            ),
        )
    }
    environmentHermesSourceDigest.isNotBlank() -> {
        val actualSourceDigest = runSourceDigestCommand(
            repoRoot.resolve("scripts/android_release_evidence.py"),
            listOf(
                "source-identity",
                "--repo-root",
                repoRoot.absolutePath,
                "--require-clean",
            ),
        )
        require(actualSourceDigest == environmentHermesSourceDigest) {
            "HERMES_SOURCE_DIGEST does not match the clean committed source: " +
                "expected $actualSourceDigest, got $environmentHermesSourceDigest"
        }
        environmentHermesSourceDigest
    }
    else -> ""
}
val hermesWheelDir = layout.buildDirectory.dir("hermes-wheel")
val generatedHermesLinuxAssetsDir = objects.directoryProperty().convention(layout.buildDirectory.dir("generated/hermes-linux-assets"))
val generatedHermesNativeLibsDir = objects.directoryProperty().convention(layout.buildDirectory.dir("generated/hermes-native-libs"))
val generatedHermesExperimentalLlamaLibsDir = objects.directoryProperty().convention(layout.buildDirectory.dir("generated/hermes-experimental-llama-libs"))
val generatedHermesExperimentalLlamaAssetsDir = objects.directoryProperty().convention(layout.buildDirectory.dir("generated/hermes-experimental-llama-assets"))
val hermesLinuxAssetLockFile = repoRoot.resolve("hermes_android/termux_linux_assets.lock.json")
val hermesExperimentalLlamaLockFile = repoRoot.resolve("hermes_android/experimental_llama_server.lock.json")
val hermesExperimentalLlamaNdkVersion = "29.0.14206865"
val hermesExperimentalLlamaPatchFile =
    repoRoot.resolve("hermes_android/patches/llama_cpp_e306_legacy_nanbeige_loop_count.patch")
val skipHermesAndroidLinuxAssets = providers.gradleProperty("skipHermesAndroidLinuxAssets")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)
val keystorePropertiesFile = rootDir.resolve("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}
val hasReleaseKeystore = keystoreProperties.isNotEmpty()
val liteRtLmStableVersion = "0.17.0"
val liteRtLmVersion = providers.gradleProperty("hermesLiteRtLmVersion")
    .getOrElse(liteRtLmStableVersion)
    .trim()
    .also { version ->
        require(Regex("""\d+\.\d+\.\d+(?:[-.][0-9A-Za-z.]+)?""").matches(version)) {
            "hermesLiteRtLmVersion must be one exact LiteRT-LM version, got '$version'"
        }
    }
val liteRtLmLocalAar = providers.gradleProperty("hermesLiteRtLmLocalAar")
    .orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { path ->
        val candidate = file(path)
        require(candidate.isFile && candidate.extension.equals("aar", ignoreCase = true)) {
            "hermesLiteRtLmLocalAar must point to an existing .aar file, got '$path'"
        }
        candidate
    }

fun hermesVersionName(): String {
    val text = hermesVersionFile.readText()
    val match = Regex("""__version__\s*=\s*\"([^\"]+)\"""").find(text)
    return match?.groupValues?.get(1) ?: "0.1.0"
}

fun androidVersionName(): String {
    if (releaseTag.isBlank()) {
        return hermesVersionName()
    }
    val semverMatch = Regex("""v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?""").matchEntire(releaseTag)
    if (semverMatch != null) {
        return releaseTag.removePrefix("v")
    }
    return hermesVersionName()
}

fun semverVersionCode(versionText: String): Int? {
    val semverMatch = Regex("""v?(\d+)\.(\d+)\.(\d+)(?:-([A-Za-z]+)(?:[.-]?(\d+))?)?""").matchEntire(versionText)
    if (semverMatch != null) {
        val major = semverMatch.groupValues[1].toInt()
        val minor = semverMatch.groupValues[2].toInt()
        val patch = semverMatch.groupValues[3].toInt()
        val prerelease = semverMatch.groupValues[4].lowercase()
        val prereleaseSeq = semverMatch.groupValues[5].ifBlank { "0" }.toInt().coerceIn(0, 9)
        val prereleaseRank = when (prerelease) {
            "alpha" -> 1
            "beta" -> 2
            "rc" -> 3
            "" -> 9
            else -> 4
        }
        return (major * 1_000_000) + (minor * 10_000) + (patch * 100) + (prereleaseRank * 10) + prereleaseSeq
    }
    return null
}

fun hermesVersionCode(): Int {
    if (releaseTag.isBlank()) {
        return semverVersionCode(hermesVersionName()) ?: 1
    }

    semverVersionCode(releaseTag)?.let { return it }

    val releaseMatch = Regex("""v(\d{4})\.(\d{1,2})\.(\d{1,2})(?:\.(\d{1,2}))?""").matchEntire(releaseTag)
        ?: return 1
    val year = releaseMatch.groupValues[1]
    val month = releaseMatch.groupValues[2].padStart(2, '0')
    val day = releaseMatch.groupValues[3].padStart(2, '0')
    val seq = releaseMatch.groupValues[4].ifBlank { "0" }.padStart(2, '0')
    return "$year$month$day$seq".toInt()
}

fun hermesWheelName(): String = "hermes_agent-${hermesVersionName()}-py3-none-any.whl"

if (hermesSourceDigest.isNotBlank()) {
    require(liteRtLmLocalAar == null) {
        "A source-bound release-evidence build cannot use hermesLiteRtLmLocalAar"
    }
    require(liteRtLmVersion == liteRtLmStableVersion) {
        "A source-bound release-evidence build must use the release LiteRT-LM version " +
            "$liteRtLmStableVersion, got $liteRtLmVersion"
    }
}

android {
    namespace = "com.mobilefork.hermesagent"
    compileSdk = 35
    ndkVersion = hermesExperimentalLlamaNdkVersion

    defaultConfig {
        applicationId = if (hermesChaquopyLabRoot == null) "com.mobilefork.hermesagent" else "com.mobilefork.hermesagent.lab"
        minSdk = 24
        targetSdk = 35
        versionCode = hermesVersionCode()
        versionName = androidVersionName()
        buildConfigField(
            "String",
            "HERMES_SOURCE_DIGEST",
            "\"hermes-source-unbound\"",
        )
        buildConfigField(
            "String",
            "HERMES_LITERTLM_COORDINATE",
            "\"com.google.ai.edge.litertlm:litertlm-android:$liteRtLmVersion\"",
        )
        buildConfigField(
            "boolean",
            "HERMES_LITERTLM_LOCAL_AAR",
            (liteRtLmLocalAar != null).toString(),
        )
        buildConfigField("boolean", "HERMES_CHAQUOPY_LAB", (hermesChaquopyLabRoot != null).toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    splits {
        abi {
            isEnable = false
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootDir.resolve(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hermesChaquopyLabRoot != null) versionNameSuffix = "-lab"
            buildConfigField(
                "String",
                "HERMES_SOURCE_DIGEST",
                "\"${hermesSourceDigest.ifBlank { "hermes-source-unbound" }}\"",
            )
        }
        release {
            buildConfigField(
                "String",
                "HERMES_SOURCE_DIGEST",
                "\"${hermesSourceDigest.ifBlank { "hermes-source-unbound" }}\"",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            buildConfigField(
                "String",
                "HERMES_SOURCE_DIGEST",
                "\"${hermesSourceDigest.ifBlank { "hermes-source-unbound" }}\"",
            )
            manifestPlaceholders["hermesBenchmarkSourceDigest"] =
                hermesSourceDigest.ifBlank { "hermes-source-unbound" }
            manifestPlaceholders["hermesBenchmarkVersionName"] = androidVersionName()
            manifestPlaceholders["hermesBenchmarkVersionCode"] = hermesVersionCode().toString()
            manifestPlaceholders["hermesBenchmarkLiteRtLmCoordinate"] =
                "com.google.ai.edge.litertlm:litertlm-android:$liteRtLmVersion"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                test.maxParallelForks = 1
                test.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                )
                test.testLogging {
                    events("failed", "skipped")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    showCauses = true
                    showExceptions = true
                    showStackTraces = true
                }
            }
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
        checkReleaseBuilds = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    sourceSets.getByName("androidTest").kotlin.directories += "src/chaquopyLabAndroidTest/java"
    if (hermesChaquopyLabRoot != null) {
        sourceSets.getByName("debug").manifest.srcFile("src/chaquopyLab/AndroidManifest.xml")
    }

    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"

        buildPython(resolvedBuildPython())

        pip {
            // Install Hermes itself from an isolated wheel, then layer an explicit
            // Android-safe runtime set. Chaquopy applies pip options globally per
            // block, so the runtime requirements file must include all transitive
            // dependencies explicitly.
            options("--no-deps")
            options("--no-index", "--find-links", hermesPythonRoot.resolve("wheels").absolutePath,
                "--only-binary", ":all:")
            install("build/hermes-wheel/${hermesWheelName()}")
            install("-r", hermesPythonRoot.resolve("requirements.txt").absolutePath)
        }
    }
    sourceSets.getByName("debug") { srcDir(hermesPythonRoot.resolve("python")) }
}

hermesPythonRoot.let {
    afterEvaluate {
        val receipt = hermesPythonReceipt
        val bootstrapVersion = receipt["bootstrap_version"] as String
        val bootstrapHash = receipt["bootstrap_sha256"] as String
        val configs = plugins.getPlugin(PythonPlugin::class.java).configs
            .filterKeys { name -> name.endsWith("RuntimeBootstrap") }.values
        check(configs.isNotEmpty()) { "Chaquopy bootstrap configurations were not found" }
        configs.forEach { config ->
            check(config.state == Configuration.State.UNRESOLVED) { "Bootstrap resolved before source-bundle selection" }
            config.resolutionStrategy.eachDependency {
                if (requested.group == "com.chaquo.python.runtime" && requested.name == "bootstrap") {
                    useVersion(bootstrapVersion)
                    because("Use the source-verified split-archive importer from the Chaquopy fork")
                }
            }
        }
        val verifyInputs = tasks.register("verifyHermesPythonBundle") {
            doLast {
                verifyHermesPythonBundle()
                configs.forEach { config ->
                    val artifact = config.resolvedConfiguration.resolvedArtifacts.single()
                    val id = artifact.moduleVersion.id
                    check(id.group == "com.chaquo.python.runtime" && id.name == "bootstrap" &&
                        id.version == bootstrapVersion && artifact.classifier == "3.13" &&
                        artifact.extension == "imy" && fileSha256(artifact.file) == bootstrapHash) {
                        "Resolved Python bootstrap differs from the verified fork input"
                    }
                }
            }
        }
        tasks.named("preBuild") { dependsOn(verifyInputs) }
    }
}

val prepareHermesAndroidWheel = tasks.register<Exec>("prepareHermesAndroidWheel") {
    group = "python"
    description = "Build a no-deps Hermes wheel for the Android embedded runtime."
    val wheelDir = hermesWheelDir.get().asFile
    inputs.file(repoRoot.resolve("pyproject.toml"))
    inputs.file(repoRoot.resolve("README.md"))
    inputs.file(repoRoot.resolve("LICENSE"))
    inputs.file(repoRoot.resolve("setup.py"))
    inputs.file(repoRoot.resolve("MANIFEST.in"))
    inputs.file(repoRoot.resolve("scripts/build_android_runtime_wheel.py"))
    inputs.file(repoRoot.resolve("scripts/verify_android_runtime_wheel.py"))
    inputs.files(fileTree(repoRoot) { include("*.py") })
    listOf(
        "agent",
        "tools",
        "hermes_cli",
        "gateway",
        "tui_gateway",
        "cron",
        "acp_adapter",
        "plugins",
        "providers",
        "hermes_android",
        "skills",
        "optional-skills",
        "locales",
        "optional-mcps",
    ).forEach { packageDir ->
        inputs.files(fileTree(repoRoot.resolve(packageDir)) {
            // Include every package-data/resource type, not just a small
            // extension list: images, templates, shell scripts, etc. matter.
            exclude("**/.git/**", "**/.hg/**", "**/.svn/**")
            exclude("**/__pycache__/**", "**/.pytest_cache/**", "**/.mypy_cache/**", "**/.ruff_cache/**")
            exclude("**/.tox/**", "**/.nox/**", "**/.venv/**", "**/venv/**", "**/node_modules/**")
            exclude("**/build/**", "**/dist/**", "**/target/**", "**/.artifacts/**", "**/release-evidence/**")
            exclude("**/*.egg-info/**", "**/*.dist-info/**", "**/.coverage")
            exclude("**/*.pyc", "**/*.pyo", "**/*.pyd", "**/*.whl", "**/*.egg", "**/*.apk", "**/*.aab")
            exclude("**/*.pftrace", "**/*.profraw", "**/*.profdata")
        })
    }
    outputs.file(wheelDir.resolve(hermesWheelName()))
    environment("HERMES_ANDROID_BUILD", "1")
    commandLine(
        resolvedBuildPython(),
        repoRoot.resolve("scripts/build_android_runtime_wheel.py").absolutePath,
        "--project-root",
        repoRoot.absolutePath,
        "--wheel-dir",
        wheelDir.absolutePath,
        "--wheel-name",
        hermesWheelName(),
    )
}

val prepareHermesAndroidLinuxAssets = tasks.register<Exec>("prepareHermesAndroidLinuxAssets") {
    group = "android"
    description = "Download and normalize the Android Linux command-suite assets."
    val outputDir = generatedHermesLinuxAssetsDir
    inputs.file(repoRoot.resolve("scripts/prepare_android_linux_assets.py"))
    inputs.file(hermesLinuxAssetLockFile)
    inputs.file(repoRoot.resolve("scripts/prepare_android_linux_assets.py"))
    inputs.file(repoRoot.resolve("hermes_android/linux_assets.py"))
    outputs.dir(outputDir)
    doFirst {
        outputDir.get().asFile.mkdirs()
        commandLine(
            resolvedBuildPython(),
            repoRoot.resolve("scripts/prepare_android_linux_assets.py").absolutePath,
            "--output-dir",
            outputDir.get().asFile.absolutePath,
            "--lock-file",
            hermesLinuxAssetLockFile.absolutePath,
        )
    }
}

val prepareHermesAndroidNativeLibs = tasks.register<Exec>("prepareHermesAndroidNativeLibs") {
    group = "android"
    description = "Expose embedded Linux launchers through Android's executable native-library directory."
    dependsOn(prepareHermesAndroidLinuxAssets)
    val outputDir = generatedHermesNativeLibsDir
    inputs.file(repoRoot.resolve("scripts/prepare_android_native_libs.py"))
    inputs.dir(generatedHermesLinuxAssetsDir)
    outputs.dir(outputDir)
    doFirst {
        outputDir.get().asFile.mkdirs()
        commandLine(
            resolvedBuildPython(),
            repoRoot.resolve("scripts/prepare_android_native_libs.py").absolutePath,
            "--linux-assets-dir",
            generatedHermesLinuxAssetsDir.get().asFile.absolutePath,
            "--output-dir",
            outputDir.get().asFile.absolutePath,
        )
    }
}

val prepareHermesAndroidExperimentalLlamaServer = tasks.register<Exec>("prepareHermesAndroidExperimentalLlamaServer") {
    group = "android"
    description = "Build and verify the pinned experimental TurboQuant llama-server for Android."
    val outputDir = generatedHermesExperimentalLlamaLibsDir
    val assetsOutputDir = generatedHermesExperimentalLlamaAssetsDir
    inputs.file(hermesExperimentalLlamaLockFile)
    inputs.file(hermesExperimentalLlamaPatchFile)
    inputs.file(repoRoot.resolve("scripts/prepare_android_experimental_llama_server.py"))
    outputs.dir(outputDir)
    outputs.dir(assetsOutputDir)
    onlyIf { !skipHermesAndroidLinuxAssets }
    doFirst {
        outputDir.get().asFile.parentFile.mkdirs()
        commandLine(
            resolvedBuildPython(),
            repoRoot.resolve("scripts/prepare_android_experimental_llama_server.py").absolutePath,
            "--output-dir",
            outputDir.get().asFile.absolutePath,
            "--assets-output-dir",
            assetsOutputDir.get().asFile.absolutePath,
            "--lock-file",
            hermesExperimentalLlamaLockFile.absolutePath,
            "--cache-dir",
            gradle.gradleUserHomeDir.resolve("caches/hermes-experimental-llama/source").absolutePath,
            "--jobs",
            "12",
        )
    }
}

if (!skipHermesAndroidLinuxAssets) {
    androidComponents.onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareHermesAndroidLinuxAssets) {
            generatedHermesLinuxAssetsDir
        }
        variant.sources.assets?.addGeneratedSourceDirectory(prepareHermesAndroidExperimentalLlamaServer) {
            generatedHermesExperimentalLlamaAssetsDir
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareHermesAndroidNativeLibs) {
            generatedHermesNativeLibsDir
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareHermesAndroidExperimentalLlamaServer) {
            generatedHermesExperimentalLlamaLibsDir
        }
    }
    tasks.named("preBuild") {
        dependsOn(prepareHermesAndroidLinuxAssets)
        dependsOn(prepareHermesAndroidNativeLibs)
        dependsOn(prepareHermesAndroidExperimentalLlamaServer)
    }
}

tasks.matching { it.name.endsWith("PythonRequirements") }.configureEach {
    dependsOn(prepareHermesAndroidWheel)
    if (!skipHermesAndroidLinuxAssets) {
        dependsOn(prepareHermesAndroidLinuxAssets)
    }
    val taskName = name
    val variant = taskName.removePrefix("install").removeSuffix("PythonRequirements")
    if (variant.isNotEmpty()) {
        dependsOn("merge${variant}PythonSources")
        dependsOn("merge${variant}NativeDebugMetadata")
        dependsOn("check${variant}AarMetadata")
    }
}

// Chaquopy's Windows installer marks packaged Python directories execute-only.
// Gradle 8.11 cannot fingerprint those generated proxy inputs even though the
// Chaquopy task itself can consume them. Linux/F-Droid builds are unaffected.
tasks.matching {
    it.name.endsWith("PythonProxies") || it.name.endsWith("PythonRequirementsAssets")
}.configureEach {
    doNotTrackState("Chaquopy proxy inputs use execute-only package directories on Windows")
}

fun normalizeChaquopyBuildJson(variant: String) {
    if (variant.isBlank()) {
        return
    }
    val buildJson = layout.buildDirectory.file(
        "python/assets/build/${variant.lowercase()}/chaquopy/build.json"
    ).get().asFile
    if (!buildJson.isFile) {
        return
    }
    hermesProcessServices.execOperations.exec {
        commandLine(
            resolvedBuildPython(),
            repoRoot.resolve("scripts/normalize_chaquopy_assets.py").absolutePath,
            "build-json",
            buildJson.absolutePath,
        )
    }
}

fun normalizeChaquopyRequirementsImy(variant: String) {
    if (variant.isBlank()) {
        return
    }
    val requirementsImy = layout.buildDirectory.file(
        "python/assets/requirements/${variant.lowercase()}/chaquopy/requirements-common.imy"
    ).get().asFile
    if (!requirementsImy.isFile) {
        return
    }
    hermesProcessServices.execOperations.exec {
        commandLine(
            resolvedBuildPython(),
            repoRoot.resolve("scripts/normalize_chaquopy_assets.py").absolutePath,
            "requirements-imy",
            requirementsImy.absolutePath,
        )
    }
}

afterEvaluate {
    tasks.matching { it.name.endsWith("PythonRequirementsAssets") }.configureEach {
        inputs.file(repoRoot.resolve("scripts/normalize_chaquopy_assets.py"))
        val taskName = name
        doLast {
            normalizeChaquopyRequirementsImy(
                taskName.removePrefix("generate").removeSuffix("PythonRequirementsAssets")
            )
        }
    }
    tasks.matching { it.name.endsWith("PythonBuildAssets") }.configureEach {
        inputs.file(repoRoot.resolve("scripts/normalize_chaquopy_assets.py"))
        val taskName = name
        doFirst {
            normalizeChaquopyRequirementsImy(
                taskName.removePrefix("generate").removeSuffix("PythonBuildAssets")
            )
        }
        doLast {
            normalizeChaquopyBuildJson(
                taskName.removePrefix("generate").removeSuffix("PythonBuildAssets")
            )
        }
    }
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
        inputs.file(repoRoot.resolve("scripts/normalize_chaquopy_assets.py"))
        val taskName = name
        doFirst {
            normalizeChaquopyBuildJson(
                taskName.removePrefix("merge").removeSuffix("Assets")
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("org.json:json:20240303")
    // Release/F-Droid builds use the exact stable default (0.17.0). Developers can compile
    // an upstream preview version or a locally built LiteRT-LM main-branch AAR
    // without weakening the reproducible release pin.
    if (liteRtLmLocalAar != null) {
        implementation(files(liteRtLmLocalAar))
    } else {
        implementation("com.google.ai.edge.litertlm:litertlm-android:$liteRtLmVersion")
    }
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
