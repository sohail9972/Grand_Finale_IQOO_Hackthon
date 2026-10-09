// Root Gradle settings for Animal Health Bridge.
// Every dependency version lives in gradle/libs.versions.toml.
pluginManagement {
    repositories {
        // Restrict google() to Android/AndroidX groups so a stray dependency
        // can never be silently pulled from the wrong repository.
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

dependencyResolutionManagement {
    // Fail the build if a module declares its own repositories. Keeps all
    // dependency resolution centralized and reproducible.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AnimalHealthBridge"
include(":app")