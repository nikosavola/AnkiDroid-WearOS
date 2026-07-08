pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Auto-provision a JDK for the Java/Kotlin toolchains: this machine only has JREs (no javac), and
// AGP 8.13+/Gradle 8.14+ eagerly require a compiler even for source-less Java tasks.
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0" }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AnkiDroid-WearOS"
include(":phone")
include(":wear")
