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

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "InplaceX"

include(":InplaceX-bot-core")
include(":InplaceX-backend")
include(":app")
project(":InplaceX-bot-core").projectDir = file("InplaceX-bot-core")
project(":InplaceX-backend").projectDir = file("InplaceX-backend")
project(":app").projectDir = file("InplaceX-android/app")
