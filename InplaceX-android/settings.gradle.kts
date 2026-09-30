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
include(":InplaceX-auth-core")
include(":InplaceX-ads-core")
include(":InplaceX-logging")
include(":InplaceX-test-support")
include(":InplaceX-backend")
include(":app")
project(":InplaceX-bot-core").projectDir = file("../InplaceX-bot-core")
project(":InplaceX-auth-core").projectDir = file("../InplaceX-auth-core")
project(":InplaceX-ads-core").projectDir = file("../InplaceX-ads-core")
project(":InplaceX-logging").projectDir = file("../InplaceX-logging")
project(":InplaceX-test-support").projectDir = file("../InplaceX-test-support")
project(":InplaceX-backend").projectDir = file("../InplaceX-backend")
