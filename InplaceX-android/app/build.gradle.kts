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

fun localProp(key: String, default: String): String =
    (localProps.getProperty(key) ?: default).replace("\"", "\\\"")

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

fun requiredReleaseAdPropertyKeys(): List<String> = listOf(
    "online.release.baseUrl",
    "provider.release.ads.yandex.owner.banner.game",
    "provider.release.ads.yandex.owner.rewarded.general",
)

fun validateDistinctProviderPlacements(
    providerName: String,
    keys: List<String>,
): List<String> {
    val configuredValues = keys
        .map { localProps.getProperty(it).orEmpty().trim() }
        .filter(String::isNotEmpty)
    return if (
        configuredValues.distinct().size != configuredValues.size
    ) {
        listOf("$providerName placement ids must be distinct: ${keys.joinToString()}")
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

abstract class ValidateReleaseAdsConfigTask : DefaultTask() {
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
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "ONLINE_BASE_URL", "\"${localProp("online.debug.baseUrl", "")}\"")
            buildConfigField("boolean", "ONLINE_ALLOW_CLEARTEXT_LOOPBACK", "true")
            buildConfigField("String", "MIRKORI_PLATFORM_BASE_URL", "\"${localProp("platform.debug.baseUrl", "https://games.dmit.life")}\"")
            buildConfigField("boolean", "MIRKORI_PLATFORM_ALLOW_CLEARTEXT_LOOPBACK", "true")
            buildConfigField("String", "PROVIDER_ENVIRONMENT", "\"sandbox\"")
            buildConfigField("String", "GOOGLE_PLAY_WEB_CLIENT_ID", "\"${localProp("provider.debug.googlePlay.webClientId", "")}\"")
            buildConfigField("String", "GOOGLE_PLAY_GAMES_PROJECT_ID", "\"${localProp("provider.debug.googlePlay.gamesProjectId", "")}\"")
            buildConfigField("String", "GOOGLE_PLAY_SERVER_CLIENT_ID", "\"${localProp("provider.debug.googlePlay.serverClientId", "")}\"")
            buildConfigField("String", "YANDEX_OWNER_GAME_BANNER_AD_UNIT_ID", "\"${localProp("provider.debug.ads.yandex.owner.banner.game", "")}\"")
            buildConfigField("String", "YANDEX_OWNER_REWARDED_AD_UNIT_ID", "\"${localProp("provider.debug.ads.yandex.owner.rewarded.general", "")}\"")
            buildConfigField("String", "YANDEX_OWNER_POST_MATCH_INTERSTITIAL_AD_UNIT_ID", "\"${localProp("provider.debug.ads.yandex.owner.interstitial.postMatch", "")}\"")
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
            buildConfigField("String", "BILLING_REMOVE_ADS_PRODUCT_ID", "\"${localProp("provider.release.billing.removeAdsProductId", "")}\"")
            buildConfigField("String", "BILLING_PRO_SUBSCRIPTION_ID", "\"${localProp("provider.release.billing.proSubscriptionId", "")}\"")
            buildConfigField("String", "BILLING_PRO_PLUS_SUBSCRIPTION_ID", "\"${localProp("provider.release.billing.proPlusSubscriptionId", "")}\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("internalDistribution") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    sourceSets.getByName("internalDistribution") {
        kotlin.srcDir("src/release/java")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val validateReleaseAdsConfig = tasks.register<ValidateReleaseAdsConfigTask>(
    "validateReleaseAdsConfig",
) {
    group = "verification"
    description = "Validates the release backend endpoint and Yandex placement ids without printing values."
    validationErrors.set(
        buildList {
            val missing = requiredReleaseAdPropertyKeys()
                .filter { localProps.getProperty(it).orEmpty().isBlank() }
            if (missing.isNotEmpty()) {
                add("Missing required release properties: ${missing.joinToString()}")
            }
            localProps.getProperty("online.release.baseUrl")
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { baseUrl ->
                    if (!isHttpsOrigin(baseUrl)) {
                        add(
                            "online.release.baseUrl must be an HTTPS origin without user info, path, query, or fragment",
                        )
                    }
                }
            addAll(
                validateDistinctProviderPlacements(
                    providerName = "owner Yandex",
                    keys = listOf(
                        "provider.release.ads.yandex.owner.banner.game",
                        "provider.release.ads.yandex.owner.rewarded.general",
                        "provider.release.ads.yandex.owner.interstitial.postMatch",
                    ),
                ),
            )
            addAll(
                validateProviderValueShape(
                    listOf(
                        "provider.release.ads.yandex.owner.banner.game",
                        "provider.release.ads.yandex.owner.rewarded.general",
                        "provider.release.ads.yandex.owner.interstitial.postMatch",
                    ),
                ),
            )
        },
    )
}

tasks.matching {
    it.name == "preReleaseBuild" || it.name == "preInternalDistributionBuild"
}.configureEach {
    dependsOn(validateReleaseAdsConfig)
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
