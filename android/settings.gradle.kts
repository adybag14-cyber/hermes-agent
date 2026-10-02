pluginManagement {
    // LiteRT-LM 0.17.1 carries Kotlin 2.4 metadata. Keep the dex compiler
    // compatible while retaining the existing AGP/Chaquopy integration.
    buildscript {
        repositories {
            google()
            mavenCentral()
            maven {
                url = uri("https://storage.googleapis.com/r8-releases/raw")
                content {
                    includeModule("com.android.tools", "r8")
                }
            }
        }
        dependencies {
            classpath("com.android.tools:r8:9.1.29")
        }
    }
    repositories {
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
