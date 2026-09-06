pluginManagement {
    repositories {
        providers.gradleProperty("hermesChaquopyLab").orNull?.let { configured ->
            val lab = file(configured).canonicalFile
            require(lab.resolve("consumer.json").isFile && lab.resolve("maven").isDirectory) {
                "hermesChaquopyLab must be a prepared, verified Hermes consumer bundle"
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
