plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

description = "Root Gradle build for the InplaceX repository."

val verificationTasks = listOf(
    ":InplaceX-backend:test",
    ":InplaceX-bot-core:test",
    ":app:testDebugUnitTest",
)

tasks.register("assembleDebug") {
    group = "build"
    description = "Assembles the Android debug build from the repository root."
    dependsOn(":app:assembleDebug")
}

tasks.register("verifyProject") {
    group = "verification"
    description = "Runs backend, shared bot-core, and Android debug unit tests from the repository root."
    dependsOn(verificationTasks)
}

tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs backend, shared bot-core, and Android debug unit tests from the repository root."
    dependsOn(verificationTasks)
}

tasks.register<Delete>("clean") {
    group = "build"
    description = "Cleans backend, shared modules, Android app, and root build outputs."
    delete(layout.buildDirectory)
    dependsOn(":InplaceX-backend:clean")
    dependsOn(":InplaceX-bot-core:clean")
    dependsOn(":app:clean")
}

tasks.register<Delete>("cleanLocalDiagnostics") {
    group = "build"
    description = "Removes local diagnostic captures that should not be committed."
    delete(
        fileTree(rootDir) {
            include("*.hprof")
            include("fresh-logcat.txt")
            include("temp-logcat.txt")
            include("post-start-log.txt")
            include("window_dump*.xml")
            include("blackscreen-check.png")
            include("emulator-screen.png")
            include("inplacex-*.png")
            include("MainActivity.debug-backup.kt")
        },
    )
}
