plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
}

description = "Root Gradle build for the InplaceX repository."

tasks.register("assembleDebug") {
    group = "build"
    description = "Assembles the Android debug build from the repository root."
    dependsOn(":app:assembleDebug")
}

tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs backend, shared bot-core, and Android debug unit tests from the repository root."
    dependsOn(":InplaceX-backend:test")
    dependsOn(":InplaceX-bot-core:test")
    dependsOn(":app:testDebugUnitTest")
}

tasks.register("clean") {
    group = "build"
    description = "Cleans backend, shared modules, Android app, and root build outputs."
    dependsOn(":InplaceX-backend:clean")
    dependsOn(":InplaceX-bot-core:clean")
    dependsOn(":app:clean")
}
