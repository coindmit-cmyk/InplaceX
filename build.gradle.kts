plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

description = "Root Gradle build for the InplaceX repository."

subprojects {
    tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
        from(layout.buildDirectory.dir("classes/kotlin/main"))
    }
}

val verificationTasks = listOf(
    ":InplaceX-ads-core:test",
    ":InplaceX-auth-core:test",
    ":InplaceX-backend:test",
    ":InplaceX-bot-core:test",
    ":InplaceX-identity:test",
    ":InplaceX-logging:test",
    ":Mirkori-platform-game-sdk:test",
    ":InplaceX-test-support:test",
    ":app:testDebugUnitTest",
)

tasks.register("assembleDebug") {
    group = "build"
    description = "Assembles the Android debug build from the repository root."
    dependsOn(":app:assembleDebug")
}

tasks.register("verifyProject") {
    group = "verification"
    description = "Runs backend, shared modules, and Android debug unit tests from the repository root."
    dependsOn(verificationTasks)
}

tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs backend, shared modules, and Android debug unit tests from the repository root."
    dependsOn(verificationTasks)
}

tasks.register<Delete>("clean") {
    group = "build"
    description = "Cleans backend, shared modules, Android app, and root build outputs."
    delete(layout.buildDirectory)
    dependsOn(":InplaceX-backend:clean")
    dependsOn(":InplaceX-ads-core:clean")
    dependsOn(":InplaceX-auth-core:clean")
    dependsOn(":InplaceX-bot-core:clean")
    dependsOn(":InplaceX-logging:clean")
    dependsOn(":Mirkori-platform-game-sdk:clean")
    dependsOn(":InplaceX-test-support:clean")
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
