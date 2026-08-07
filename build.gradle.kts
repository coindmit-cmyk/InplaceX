import java.util.Properties

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
    ":testReleaseDistribution",
)

val releaseDistributionPython = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    "python"
} else {
    "python3"
}
val releaseDistributionProcessEnvironment = System.getenv()
    .filterKeys { !it.startsWith("GIT_", ignoreCase = true) }

val releaseDistributionVersionProperties = Properties().apply {
    rootProject.file("InplaceX-android/version.properties").inputStream().use(::load)
}
val releaseDistributionVersionName = releaseDistributionVersionProperties.getProperty("versionName")
    ?.takeIf { it.matches(Regex("[0-9A-Za-z][0-9A-Za-z._-]{0,63}")) }
    ?: throw GradleException("Release distribution versionName is invalid")
val releaseDistributionVersionCode = releaseDistributionVersionProperties.getProperty("versionCode")
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: throw GradleException("Release distribution versionCode is invalid")
val releaseDistributionCandidateId =
    "inplacex-${releaseDistributionVersionName.lowercase()}-$releaseDistributionVersionCode"
val releaseDistributionCandidateDirectory =
    rootProject.layout.buildDirectory.dir("release-candidates/$releaseDistributionCandidateId")

fun requiredReleaseDistributionProperty(name: String): String = providers.gradleProperty(name)
    .orNull
    ?.takeIf(String::isNotBlank)
    ?: throw GradleException("Missing required Gradle property: $name")

tasks.register<Exec>("testReleaseDistribution") {
    group = "verification"
    description = "Validates immutable Mirkori Platform catalog generation from a signed APK candidate."
    workingDir(rootDir)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    commandLine(
        releaseDistributionPython,
        "-I",
        "-m",
        "unittest",
        "discover",
        "-s",
        "ops/release",
        "-p",
        "test_*.py",
    )
}

val testPlatformReleaseContract = tasks.register<Exec>("testPlatformReleaseContract") {
    group = "verification"
    description = "Opt-in verification of the exact clean Platform checkout and catalog validator schema."
    workingDir(rootDir)
    inputs.file(rootProject.file("ops/release/verify_platform_release_contract.py"))
    inputs.file(rootProject.file("ops/release/build_platform_catalog_release.py"))
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache("Verifies an external Git checkout and exact tool bytes at execution time")
    setEnvironment(releaseDistributionProcessEnvironment)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    doFirst {
        val platformRepositoryDirectory = rootProject.file(
            requiredReleaseDistributionProperty("inplacexPlatformRepositoryDir"),
        ).absolutePath
        val expectedPlatformCommit = requiredReleaseDistributionProperty("inplacexPlatformExpectedCommit")
        val expectedPlatformValidatorSha256 =
            requiredReleaseDistributionProperty("inplacexPlatformValidatorSha256").lowercase()
        if (!expectedPlatformCommit.matches(Regex("[0-9a-f]{40}"))) {
            throw GradleException("inplacexPlatformExpectedCommit must be an exact lowercase commit")
        }
        if (!expectedPlatformValidatorSha256.matches(Regex("[0-9a-f]{64}"))) {
            throw GradleException("inplacexPlatformValidatorSha256 must be an exact lowercase SHA-256")
        }
        commandLine(
            releaseDistributionPython,
            "-I",
            "ops/release/verify_platform_release_contract.py",
            "--platform-repo-dir",
            platformRepositoryDirectory,
            "--expected-platform-commit",
            expectedPlatformCommit,
            "--expected-platform-validator-sha256",
            expectedPlatformValidatorSha256,
        )
    }
}

tasks.register<Exec>("buildPlatformCatalogRelease") {
    group = "distribution"
    description = "Builds the exact signed candidate for HEAD and converts it using the current Platform catalog base."
    dependsOn(":app:releaseCandidate", testPlatformReleaseContract)
    workingDir(rootDir)
    inputs.file(rootProject.file("ops/release/build_platform_catalog_release.py"))
    inputs.file(rootProject.file("ops/release/verify_platform_release_contract.py"))
    inputs.dir(releaseDistributionCandidateDirectory)
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache("Consumes an external catalog and validator checkout at execution time")
    setEnvironment(releaseDistributionProcessEnvironment)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    doFirst {
        val baseReleaseDirectory = rootProject.file(
            requiredReleaseDistributionProperty("inplacexPlatformCatalogBaseReleaseDir"),
        ).absolutePath
        val outputDirectory = rootProject.file(
            requiredReleaseDistributionProperty("inplacexPlatformCatalogOutputDir"),
        ).absolutePath
        val platformRepositoryDirectory = rootProject.file(
            requiredReleaseDistributionProperty("inplacexPlatformRepositoryDir"),
        ).absolutePath
        val expectedPlatformCommit = requiredReleaseDistributionProperty("inplacexPlatformExpectedCommit")
        val expectedPlatformValidatorSha256 =
            requiredReleaseDistributionProperty("inplacexPlatformValidatorSha256").lowercase()
        val minimumSupportedVersionCode =
            requiredReleaseDistributionProperty("inplacexPlatformCatalogMinimumSupportedVersionCode")
                .toIntOrNull()
                ?.takeIf { it > 0 }
                ?: throw GradleException(
                    "inplacexPlatformCatalogMinimumSupportedVersionCode must be a positive integer",
                )
        val publishedAt = requiredReleaseDistributionProperty("inplacexPlatformCatalogPublishedAt")
        val changelog = requiredReleaseDistributionProperty("inplacexPlatformCatalogChangelog")
        val channel = providers.gradleProperty("inplacexPlatformCatalogChannel")
            .orNull
            ?.takeIf(String::isNotBlank)
            ?: "stable"
        if (channel !in setOf("stable", "beta")) {
            throw GradleException("inplacexPlatformCatalogChannel must be stable or beta")
        }
        val expectedCommit = providers.exec {
            setEnvironment(releaseDistributionProcessEnvironment)
            workingDir(rootDir)
            commandLine("git", "--no-replace-objects", "rev-parse", "HEAD")
        }.standardOutput.asText.get().trim()
        if (!expectedCommit.matches(Regex("[0-9a-f]{40}"))) {
            throw GradleException("Could not resolve the exact lowercase Git HEAD for release distribution")
        }
        commandLine(
            releaseDistributionPython,
            "-I",
            "ops/release/build_platform_catalog_release.py",
            "--candidate-dir",
            releaseDistributionCandidateDirectory.get().asFile.absolutePath,
            "--expected-commit",
            expectedCommit,
            "--base-release-dir",
            baseReleaseDirectory,
            "--platform-repo-dir",
            platformRepositoryDirectory,
            "--expected-platform-commit",
            expectedPlatformCommit,
            "--expected-platform-validator-sha256",
            expectedPlatformValidatorSha256,
            "--output-dir",
            outputDirectory,
            "--channel",
            channel,
            "--minimum-supported-version-code",
            minimumSupportedVersionCode.toString(),
            "--published-at",
            publishedAt,
            "--changelog",
            changelog,
        )
    }
}

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
