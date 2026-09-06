pluginManagement {
    repositories {
        run {
            val configured = providers.gradleProperty("hermesChaquopyLab").orNull
                ?: providers.gradleProperty("hermesPythonBundle").orNull
            val lab = configured?.let { file(it).canonicalFile }
                ?: gradle.gradleUserHomeDir.resolve("hermes-python-runtime").canonicalFile
            require(lab.resolve("consumer.json").isFile && lab.resolve("maven").isDirectory) {
                "Prepare the source-built Hermes Python bundle before Gradle: see android/PYTHON_RUNTIME.md"
            }
            exclusiveContent {
                forRepository { maven { url = uri(lab.resolve("maven")) } }
                filter { includeModule("com.chaquo.python.runtime", "bootstrap") }
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HermesAgentAndroid"
include(":app")
include(":macrobenchmark")
