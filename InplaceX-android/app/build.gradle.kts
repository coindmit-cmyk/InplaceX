import java.io.File
import java.net.URI
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val file = providers.gradleProperty("inplacexProviderConfigFile")
        .orNull
        ?.let(rootProject::file)
        ?: rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

val versionProps = Properties().apply {
    val file = rootProject.file("InplaceX-android/version.properties")
    if (!file.isFile) {
        throw GradleException("Missing canonical Android version file: InplaceX-android/version.properties")
    }
    file.inputStream().use(::load)
}

val appVersionCode = versionProps.getProperty("versionCode")
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: throw GradleException("versionCode must be a positive integer")
val appVersionName = versionProps.getProperty("versionName")
    ?.trim()
    ?.takeIf { it.matches(Regex("[0-9A-Za-z][0-9A-Za-z._-]{0,63}")) }
    ?: throw GradleException("versionName has an invalid format")

data class ReleaseSigningValues(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
    val expectedCertificateSha256: String,
)

val releaseSigningFilePath = providers.gradleProperty("inplacexReleaseSigningFile")
    .orElse(providers.environmentVariable("INPLACEX_RELEASE_SIGNING_FILE"))
    .orNull
    ?.takeIf(String::isNotBlank)
val releaseSigningProps = Properties().apply {
    releaseSigningFilePath?.let { configuredPath ->
        val file = rootProject.file(configuredPath)
        if (!file.isFile) {
            throw GradleException("Configured release signing properties file does not exist")
        }
        file.inputStream().use(::load)
    }
}

fun releaseSigningValue(environmentKey: String, propertyKey: String): String? =
    providers.environmentVariable(environmentKey)
        .orNull
        ?.takeIf(String::isNotEmpty)
        ?: releaseSigningProps.getProperty(propertyKey)
            ?.takeIf(String::isNotEmpty)

val releaseSigningFields = linkedMapOf(
    "storeFile" to releaseSigningValue("INPLACEX_RELEASE_STORE_FILE", "storeFile"),
    "storePassword" to releaseSigningValue("INPLACEX_RELEASE_STORE_PASSWORD", "storePassword"),
    "keyAlias" to releaseSigningValue("INPLACEX_RELEASE_KEY_ALIAS", "keyAlias"),
    "keyPassword" to releaseSigningValue("INPLACEX_RELEASE_KEY_PASSWORD", "keyPassword"),
    "expectedCertificateSha256" to releaseSigningValue(
        "INPLACEX_RELEASE_EXPECTED_CERT_SHA256",
        "expectedCertificateSha256",
    ),
)
val configuredReleaseSigningFields = releaseSigningFields.values.count { it != null }
if (configuredReleaseSigningFields in 1 until releaseSigningFields.size) {
    val missing = releaseSigningFields.filterValues { it == null }.keys
    throw GradleException("Partial release signing configuration; missing: ${missing.joinToString()}")
}
if (releaseSigningFilePath != null && configuredReleaseSigningFields == 0) {
    throw GradleException("Release signing properties file does not contain the required keys")
}
val releaseSigningValues = if (configuredReleaseSigningFields == releaseSigningFields.size) {
    ReleaseSigningValues(
        storeFile = requireNotNull(releaseSigningFields["storeFile"]),
        storePassword = requireNotNull(releaseSigningFields["storePassword"]),
        keyAlias = requireNotNull(releaseSigningFields["keyAlias"]),
        keyPassword = requireNotNull(releaseSigningFields["keyPassword"]),
        expectedCertificateSha256 = requireNotNull(releaseSigningFields["expectedCertificateSha256"]),
    ).also { values ->
        if (!rootProject.file(values.storeFile).isFile) {
            throw GradleException("Configured release keystore does not exist")
        }
    }
} else {
    null
}

fun normalizeCertificateSha256(value: String): String? {
    val compact = when {
        value.matches(Regex("[0-9A-Fa-f]{64}")) -> value
        value.matches(Regex("(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}")) -> value.replace(":", "")
        else -> return null
    }
    return compact.uppercase().chunked(2).joinToString(":")
}

val expectedReleaseCertificateSha256 = releaseSigningValues
    ?.expectedCertificateSha256
    ?.let(::normalizeCertificateSha256)

fun localProp(key: String, default: String): String =
    (localProps.getProperty(key) ?: default).replace("\"", "\\\"")

fun localPropWithFallback(
    key: String,
    fallbackKey: String,
    default: String = "",
): String =
    (
        localProps.getProperty(key)?.takeIf(String::isNotBlank)
            ?: localProps.getProperty(fallbackKey)?.takeIf(String::isNotBlank)
            ?: default
        ).replace("\"", "\\\"")

fun localIntProp(
    key: String,
    default: Int,
    range: IntRange,
): Int =
    localProps.getProperty(key)
        ?.toIntOrNull()
        ?.takeIf(range::contains)
        ?: default

fun localLongProp(
    key: String,
    default: Long,
    range: LongRange,
): Long =
    localProps.getProperty(key)
        ?.toLongOrNull()
        ?.takeIf(range::contains)
        ?: default

fun requiredProductionReleasePropertyKeys(): List<String> = listOf(
    "online.release.baseUrl",
    "platform.release.baseUrl",
    "provider.release.ads.yandex.owner.banner.game",
    "provider.release.ads.yandex.owner.rewarded.general",
)

val canonicalReleaseBillingProductIds = linkedMapOf(
    "provider.release.billing.removeAdsProductId" to "inplacex.remove_ads",
    "provider.release.billing.proSubscriptionId" to "inplacex.pro",
    "provider.release.billing.proPlusSubscriptionId" to "inplacex.pro_plus",
)

fun validateDistinctProviderIds(
    providerName: String,
    keys: List<String>,
): List<String> {
    val configuredValues = keys
        .map { localProps.getProperty(it).orEmpty().trim() }
        .filter(String::isNotEmpty)
    return if (
        configuredValues.distinct().size != configuredValues.size
    ) {
        listOf("$providerName configured ids must be distinct: ${keys.joinToString()}")
    } else {
        emptyList()
    }
}

fun validateProviderValueShape(keys: List<String>): List<String> = keys.mapNotNull { key ->
    val value = localProps.getProperty(key).orEmpty()
    when {
        value.any(Char::isISOControl) -> "$key must not contain control characters"
        value.length > 256 -> "$key must not exceed 256 characters"
        else -> null
    }
}

fun validateNoSurroundingWhitespace(keys: List<String>): List<String> = keys.mapNotNull { key ->
    val value = localProps.getProperty(key) ?: return@mapNotNull null
    if (value != value.trim()) "$key must not contain surrounding whitespace" else null
}

fun isHttpsOrigin(value: String): Boolean =
    runCatching { URI(value) }
        .getOrNull()
        ?.let { uri ->
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null &&
                uri.rawPath.isNullOrEmpty() &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                (uri.port == -1 || uri.port in 1..65_535)
        }
        ?: false

abstract class ValidateReleaseConfigTask : DefaultTask() {
    @get:Input
    abstract val validationErrors: ListProperty<String>

    @TaskAction
    fun validate() {
        val errors = validationErrors.get()
        if (errors.isNotEmpty()) {
            throw GradleException(errors.joinToString(separator = "\n"))
        }
    }
}

android {
    namespace = "com.mirkori.inplacex"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    defaultConfig {
        applicationId = "com.mirkori.inplacex"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    val releaseCandidateSigning = releaseSigningValues?.let { values ->
        signingConfigs.create("releaseCandidate") {
            storeFile = rootProject.file(values.storeFile)
            storePassword = values.storePassword
            keyAlias = values.keyAlias
            keyPassword = values.keyPassword
        }
    }

    buildTypes {
        getByName("debug") {
            versionNameSuffix = "-debug.4"
            buildConfigField("String", "ONLINE_BASE_URL", "\"${localProp("online.debug.baseUrl", "")}\"")
            buildConfigField("boolean", "ONLINE_ALLOW_CLEARTEXT_LOOPBACK", "true")
            buildConfigField("String", "MIRKORI_PLATFORM_BASE_URL", "\"${localProp("platform.debug.baseUrl", "https://games.dmit.life")}\"")
            buildConfigField("boolean", "MIRKORI_PLATFORM_ALLOW_CLEARTEXT_LOOPBACK", "true")
            buildConfigField("String", "PROVIDER_ENVIRONMENT", "\"sandbox\"")
            buildConfigField("String", "GOOGLE_PLAY_WEB_CLIENT_ID", "\"${localPropWithFallback("provider.debug.googlePlay.webClientId", "provider.release.googlePlay.webClientId")}\"")
            buildConfigField("String", "GOOGLE_PLAY_GAMES_PROJECT_ID", "\"${localPropWithFallback("provider.debug.googlePlay.gamesProjectId", "provider.release.googlePlay.gamesProjectId")}\"")
            buildConfigField("String", "GOOGLE_PLAY_SERVER_CLIENT_ID", "\"${localPropWithFallback("provider.debug.googlePlay.serverClientId", "provider.release.googlePlay.serverClientId")}\"")
            buildConfigField("String", "YANDEX_OWNER_GAME_BANNER_AD_UNIT_ID", "\"${localPropWithFallback("provider.debug.ads.yandex.owner.banner.game", "provider.release.ads.yandex.owner.banner.game")}\"")
            buildConfigField("String", "YANDEX_OWNER_REWARDED_AD_UNIT_ID", "\"${localPropWithFallback("provider.debug.ads.yandex.owner.rewarded.general", "provider.release.ads.yandex.owner.rewarded.general")}\"")
            buildConfigField("String", "YANDEX_OWNER_POST_MATCH_INTERSTITIAL_AD_UNIT_ID", "\"${localPropWithFallback("provider.debug.ads.yandex.owner.interstitial.postMatch", "provider.release.ads.yandex.owner.interstitial.postMatch")}\"")
            buildConfigField("int", "ADS_INTERSTITIAL_MINIMUM_COMPLETED_MATCHES", localIntProp("provider.debug.ads.interstitial.minimumCompletedMatches", 20, 0..10_000).toString())
            buildConfigField("long", "ADS_INTERSTITIAL_MINIMUM_FOREGROUND_SECONDS", "${localLongProp("provider.debug.ads.interstitial.minimumForegroundSeconds", 0, 0L..2_592_000L)}L")
            buildConfigField("int", "ADS_INTERSTITIAL_GAMES_BETWEEN_IMPRESSIONS", localIntProp("provider.debug.ads.interstitial.gamesBetweenImpressions", 4, 1..10_000).toString())
            buildConfigField("String", "BILLING_REMOVE_ADS_PRODUCT_ID", "\"${localProp("provider.debug.billing.removeAdsProductId", "remove_ads")}\"")
            buildConfigField("String", "BILLING_PRO_SUBSCRIPTION_ID", "\"${localProp("provider.debug.billing.proSubscriptionId", "pro_subscription")}\"")
            buildConfigField("String", "BILLING_PRO_PLUS_SUBSCRIPTION_ID", "\"${localProp("provider.debug.billing.proPlusSubscriptionId", "pro_plus_subscription")}\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "ONLINE_BASE_URL", "\"${localProp("online.release.baseUrl", "")}\"")
            buildConfigField("boolean", "ONLINE_ALLOW_CLEARTEXT_LOOPBACK", "false")
            buildConfigField("String", "MIRKORI_PLATFORM_BASE_URL", "\"${localProp("platform.release.baseUrl", "https://games.dmit.life")}\"")
            buildConfigField("boolean", "MIRKORI_PLATFORM_ALLOW_CLEARTEXT_LOOPBACK", "false")
            buildConfigField("String", "PROVIDER_ENVIRONMENT", "\"live\"")
            buildConfigField("String", "GOOGLE_PLAY_WEB_CLIENT_ID", "\"${localProp("provider.release.googlePlay.webClientId", "")}\"")
            buildConfigField("String", "GOOGLE_PLAY_GAMES_PROJECT_ID", "\"${localProp("provider.release.googlePlay.gamesProjectId", "")}\"")
            buildConfigField("String", "GOOGLE_PLAY_SERVER_CLIENT_ID", "\"${localProp("provider.release.googlePlay.serverClientId", "")}\"")
            buildConfigField("String", "YANDEX_OWNER_GAME_BANNER_AD_UNIT_ID", "\"${localProp("provider.release.ads.yandex.owner.banner.game", "")}\"")
            buildConfigField("String", "YANDEX_OWNER_REWARDED_AD_UNIT_ID", "\"${localProp("provider.release.ads.yandex.owner.rewarded.general", "")}\"")
            buildConfigField("String", "YANDEX_OWNER_POST_MATCH_INTERSTITIAL_AD_UNIT_ID", "\"${localProp("provider.release.ads.yandex.owner.interstitial.postMatch", "")}\"")
            buildConfigField("int", "ADS_INTERSTITIAL_MINIMUM_COMPLETED_MATCHES", localIntProp("provider.release.ads.interstitial.minimumCompletedMatches", 20, 0..10_000).toString())
            buildConfigField("long", "ADS_INTERSTITIAL_MINIMUM_FOREGROUND_SECONDS", "${localLongProp("provider.release.ads.interstitial.minimumForegroundSeconds", 1_800, 0L..2_592_000L)}L")
            buildConfigField("int", "ADS_INTERSTITIAL_GAMES_BETWEEN_IMPRESSIONS", localIntProp("provider.release.ads.interstitial.gamesBetweenImpressions", 4, 1..10_000).toString())
            buildConfigField("String", "BILLING_REMOVE_ADS_PRODUCT_ID", "\"${canonicalReleaseBillingProductIds.getValue("provider.release.billing.removeAdsProductId")}\"")
            buildConfigField("String", "BILLING_PRO_SUBSCRIPTION_ID", "\"${canonicalReleaseBillingProductIds.getValue("provider.release.billing.proSubscriptionId")}\"")
            buildConfigField("String", "BILLING_PRO_PLUS_SUBSCRIPTION_ID", "\"${canonicalReleaseBillingProductIds.getValue("provider.release.billing.proPlusSubscriptionId")}\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("internalDistribution") {
            initWith(getByName("release"))
            versionNameSuffix = "-dev.1"
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
        create("signedReleaseCandidate") {
            initWith(getByName("release"))
            versionNameSuffix = "-rc.1"
            isDebuggable = false
            releaseCandidateSigning?.let { signingConfig = it }
            matchingFallbacks += listOf("release")
        }
    }
    sourceSets.getByName("internalDistribution") {
        kotlin.srcDir("src/release/java")
    }
    sourceSets.getByName("signedReleaseCandidate") {
        kotlin.srcDir("src/release/java")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val validateProductionReleaseConfig = tasks.register<ValidateReleaseConfigTask>(
    "validateProductionReleaseConfig",
) {
    group = "verification"
    description = "Validates production HTTPS origins, Yandex placements, and Mirkori product ids without printing values."
    validationErrors.set(
        buildList {
            val missing = requiredProductionReleasePropertyKeys()
                .filter { localProps.getProperty(it).orEmpty().isBlank() }
            if (missing.isNotEmpty()) {
                add("Missing required release properties: ${missing.joinToString()}")
            }
            listOf("online.release.baseUrl", "platform.release.baseUrl").forEach { key ->
                localProps.getProperty(key)
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { baseUrl ->
                        if (!isHttpsOrigin(baseUrl)) {
                            add("$key must be an HTTPS origin without user info, path, query, or fragment")
                        }
                    }
            }
            addAll(
                validateDistinctProviderIds(
                    providerName = "owner Yandex",
                    keys = listOf(
                        "provider.release.ads.yandex.owner.banner.game",
                        "provider.release.ads.yandex.owner.rewarded.general",
                        "provider.release.ads.yandex.owner.interstitial.postMatch",
                    ),
                ),
            )
            addAll(
                validateDistinctProviderIds(
                    providerName = "Mirkori commerce",
                    keys = listOf(
                        "provider.release.billing.removeAdsProductId",
                        "provider.release.billing.proSubscriptionId",
                        "provider.release.billing.proPlusSubscriptionId",
                    ),
                ),
            )
            canonicalReleaseBillingProductIds.forEach { (key, canonicalId) ->
                val configured = localProps.getProperty(key) ?: return@forEach
                if (configured != canonicalId) {
                    add("$key must equal the canonical InplaceX Platform product id")
                }
            }
            addAll(
                validateProviderValueShape(
                    listOf(
                        "provider.release.ads.yandex.owner.banner.game",
                        "provider.release.ads.yandex.owner.rewarded.general",
                        "provider.release.ads.yandex.owner.interstitial.postMatch",
                        "provider.release.billing.removeAdsProductId",
                        "provider.release.billing.proSubscriptionId",
                        "provider.release.billing.proPlusSubscriptionId",
                    ),
                ),
            )
            addAll(
                validateNoSurroundingWhitespace(
                    listOf(
                        "online.release.baseUrl",
                        "platform.release.baseUrl",
                        "provider.release.ads.yandex.owner.banner.game",
                        "provider.release.ads.yandex.owner.rewarded.general",
                        "provider.release.ads.yandex.owner.interstitial.postMatch",
                        "provider.release.billing.removeAdsProductId",
                        "provider.release.billing.proSubscriptionId",
                        "provider.release.billing.proPlusSubscriptionId",
                    ),
                ),
            )
        },
    )
}

val validateReleaseSigningConfig = tasks.register<ValidateReleaseConfigTask>(
    "validateReleaseSigningConfig",
) {
    group = "verification"
    description = "Requires one complete external release signing configuration without printing secret values."
    validationErrors.set(
        buildList {
            if (releaseSigningValues == null) {
                add(
                    "Release signing is not configured; provide all INPLACEX_RELEASE_* values or an external signing properties file",
                )
            } else if (expectedReleaseCertificateSha256 == null) {
                add("expectedCertificateSha256 must be a SHA-256 fingerprint with 64 hex digits")
            }
        },
    )
}

tasks.matching { it.name == "preSignedReleaseCandidateBuild" }.configureEach {
    dependsOn(validateProductionReleaseConfig, validateReleaseSigningConfig)
}

val releaseCandidateBash = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    File(System.getenv("ProgramFiles") ?: "C:\\Program Files", "Git/bin/bash.exe")
        .takeIf(File::isFile)
        ?.absolutePath
        ?: "bash"
} else {
    "bash"
}
val releaseCandidateProcessEnvironment = System.getenv()
    .filterKeys {
        !it.startsWith("GIT_", ignoreCase = true) &&
            !it.startsWith("BASH_FUNC_", ignoreCase = true) &&
            !it.equals("BASH_ENV", ignoreCase = true) &&
            !it.equals("ENV", ignoreCase = true)
    }

tasks.register<Exec>("releaseCandidate") {
    group = "distribution"
    description = "Builds and verifies a signed production APK and writes its immutable release identity bundle."
    dependsOn("assembleSignedReleaseCandidate")
    workingDir(rootProject.rootDir)
    inputs.file(layout.buildDirectory.file("outputs/apk/signedReleaseCandidate/app-signedReleaseCandidate.apk"))
    inputs.file(rootProject.file("scripts/ci/artifact_identity.sh"))
    inputs.property("expectedCertificateSha256", expectedReleaseCertificateSha256.orEmpty())
    outputs.dir(rootProject.layout.buildDirectory.dir("release-candidates"))
    outputs.upToDateWhen { false }
    setEnvironment(releaseCandidateProcessEnvironment)
    commandLine(
        releaseCandidateBash,
        "-p",
        "scripts/ci/artifact_identity.sh",
        "--apk",
        "InplaceX-android/app/build/outputs/apk/signedReleaseCandidate/app-signedReleaseCandidate.apk",
        "--output-dir",
        "build/release-candidates",
        "--artifact-type",
        "release",
        "--expected-signing",
        "verified",
        "--expected-certificate-sha256",
        expectedReleaseCertificateSha256.orEmpty(),
    )
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        (variantBuilder as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
    }
}

dependencies {
    implementation(project(":Mirkori-platform-game-sdk"))
    implementation(project(":InplaceX-ads-core"))
    implementation(project(":InplaceX-bot-core"))
    implementation(project(":InplaceX-logging"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.google.identity.googleid)
    implementation(libs.yandex.mobileads)
    implementation(libs.yandex.mobileads.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(project(":InplaceX-test-support"))
    testImplementation(project(":InplaceX-backend"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation("com.h2database:h2:2.3.232")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
