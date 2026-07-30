import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
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
    "provider.release.ads.yandex.owner.interstitial.postMatch",
)

fun validateOptionalProviderGroup(
    providerName: String,
    keys: List<String>,
): List<String> {
    val configured = keys.filter { localProps.getProperty(it).orEmpty().isNotBlank() }
    return if (configured.isEmpty() || configured.size == keys.size) {
        emptyList()
    } else {
        listOf("$providerName must be configured completely or left disabled: ${keys.joinToString()}")
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
            buildConfigField("String", "PROVIDER_ENVIRONMENT", "\"sandbox\"")
            buildConfigField("String", "GOOGLE_PLAY_WEB_CLIENT_ID", "\"${localProp("provider.debug.googlePlay.webClientId", "")}\"")
            buildConfigField("String", "GOOGLE_PLAY_GAMES_PROJECT_ID", "\"${localProp("provider.debug.googlePlay.gamesProjectId", "")}\"")
            buildConfigField("String", "GOOGLE_PLAY_SERVER_CLIENT_ID", "\"${localProp("provider.debug.googlePlay.serverClientId", "")}\"")
            buildConfigField("String", "ADMOB_APP_ID", "\"${localProp("provider.debug.ads.admob.company.appId", "ca-app-pub-3940256099942544~3347511713")}\"")
            buildConfigField("String", "ADMOB_GAME_BANNER_AD_UNIT_ID", "\"${localProp("provider.debug.ads.admob.company.banner.game", "ca-app-pub-3940256099942544/6300978111")}\"")
            buildConfigField("String", "ADMOB_REWARDED_AD_UNIT_ID", "\"${localProp("provider.debug.ads.admob.company.rewarded.general", "ca-app-pub-3940256099942544/5224354917")}\"")
            buildConfigField("String", "ADMOB_POST_MATCH_INTERSTITIAL_AD_UNIT_ID", "\"${localProp("provider.debug.ads.admob.company.interstitial.postMatch", "ca-app-pub-3940256099942544/1033173712")}\"")
            buildConfigField("String", "YANDEX_OWNER_GAME_BANNER_AD_UNIT_ID", "\"${localProp("provider.debug.ads.yandex.owner.banner.game", "")}\"")
            buildConfigField("String", "YANDEX_OWNER_REWARDED_AD_UNIT_ID", "\"${localProp("provider.debug.ads.yandex.owner.rewarded.general", "")}\"")
            buildConfigField("String", "YANDEX_OWNER_POST_MATCH_INTERSTITIAL_AD_UNIT_ID", "\"${localProp("provider.debug.ads.yandex.owner.interstitial.postMatch", "")}\"")
            buildConfigField("String", "HUAWEI_COMPANY_APP_ID", "\"${localProp("provider.debug.ads.huawei.company.appId", "")}\"")
            buildConfigField("String", "HUAWEI_COMPANY_GAME_BANNER_AD_UNIT_ID", "\"${localProp("provider.debug.ads.huawei.company.banner.game", "")}\"")
            buildConfigField("String", "HUAWEI_COMPANY_REWARDED_AD_UNIT_ID", "\"${localProp("provider.debug.ads.huawei.company.rewarded.general", "")}\"")
            buildConfigField("String", "HUAWEI_COMPANY_POST_MATCH_INTERSTITIAL_AD_UNIT_ID", "\"${localProp("provider.debug.ads.huawei.company.interstitial.postMatch", "")}\"")
            buildConfigField("int", "ADS_INTERSTITIAL_MINIMUM_COMPLETED_MATCHES", localIntProp("provider.debug.ads.interstitial.minimumCompletedMatches", 20, 0..10_000).toString())
            buildConfigField("long", "ADS_INTERSTITIAL_MINIMUM_FOREGROUND_SECONDS", "${localLongProp("provider.debug.ads.interstitial.minimumForegroundSeconds", 0, 0L..2_592_000L)}L")
            buildConfigField("int", "ADS_INTERSTITIAL_GAMES_BETWEEN_IMPRESSIONS", localIntProp("provider.debug.ads.interstitial.gamesBetweenImpressions", 4, 1..10_000).toString())
            buildConfigField("String", "BILLING_REMOVE_ADS_PRODUCT_ID", "\"${localProp("provider.debug.billing.removeAdsProductId", "remove_ads")}\"")
            buildConfigField("String", "BILLING_PRO_SUBSCRIPTION_ID", "\"${localProp("provider.debug.billing.proSubscriptionId", "pro_subscription")}\"")
            buildConfigField("String", "BILLING_PRO_PLUS_SUBSCRIPTION_ID", "\"${localProp("provider.debug.billing.proPlusSubscriptionId", "pro_plus_subscription")}\"")
            manifestPlaceholders["admobAppId"] = localProp(
                "provider.debug.ads.admob.company.appId",
                "ca-app-pub-3940256099942544~3347511713",
            )
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "ONLINE_BASE_URL", "\"${localProp("online.release.baseUrl", "")}\"")
            buildConfigField("boolean", "ONLINE_ALLOW_CLEARTEXT_LOOPBACK", "false")
            buildConfigField("String", "PROVIDER_ENVIRONMENT", "\"live\"")
            buildConfigField("String", "GOOGLE_PLAY_WEB_CLIENT_ID", "\"${localProp("provider.release.googlePlay.webClientId", "")}\"")
            buildConfigField("String", "GOOGLE_PLAY_GAMES_PROJECT_ID", "\"${localProp("provider.release.googlePlay.gamesProjectId", "")}\"")
            buildConfigField("String", "GOOGLE_PLAY_SERVER_CLIENT_ID", "\"${localProp("provider.release.googlePlay.serverClientId", "")}\"")
            buildConfigField("String", "ADMOB_APP_ID", "\"${localProp("provider.release.ads.admob.company.appId", "")}\"")
            buildConfigField("String", "ADMOB_GAME_BANNER_AD_UNIT_ID", "\"${localProp("provider.release.ads.admob.company.banner.game", "")}\"")
            buildConfigField("String", "ADMOB_REWARDED_AD_UNIT_ID", "\"${localProp("provider.release.ads.admob.company.rewarded.general", "")}\"")
            buildConfigField("String", "ADMOB_POST_MATCH_INTERSTITIAL_AD_UNIT_ID", "\"${localProp("provider.release.ads.admob.company.interstitial.postMatch", "")}\"")
            buildConfigField("String", "YANDEX_OWNER_GAME_BANNER_AD_UNIT_ID", "\"${localProp("provider.release.ads.yandex.owner.banner.game", "")}\"")
            buildConfigField("String", "YANDEX_OWNER_REWARDED_AD_UNIT_ID", "\"${localProp("provider.release.ads.yandex.owner.rewarded.general", "")}\"")
            buildConfigField("String", "YANDEX_OWNER_POST_MATCH_INTERSTITIAL_AD_UNIT_ID", "\"${localProp("provider.release.ads.yandex.owner.interstitial.postMatch", "")}\"")
            buildConfigField("String", "HUAWEI_COMPANY_APP_ID", "\"${localProp("provider.release.ads.huawei.company.appId", "")}\"")
            buildConfigField("String", "HUAWEI_COMPANY_GAME_BANNER_AD_UNIT_ID", "\"${localProp("provider.release.ads.huawei.company.banner.game", "")}\"")
            buildConfigField("String", "HUAWEI_COMPANY_REWARDED_AD_UNIT_ID", "\"${localProp("provider.release.ads.huawei.company.rewarded.general", "")}\"")
            buildConfigField("String", "HUAWEI_COMPANY_POST_MATCH_INTERSTITIAL_AD_UNIT_ID", "\"${localProp("provider.release.ads.huawei.company.interstitial.postMatch", "")}\"")
            buildConfigField("int", "ADS_INTERSTITIAL_MINIMUM_COMPLETED_MATCHES", localIntProp("provider.release.ads.interstitial.minimumCompletedMatches", 20, 0..10_000).toString())
            buildConfigField("long", "ADS_INTERSTITIAL_MINIMUM_FOREGROUND_SECONDS", "${localLongProp("provider.release.ads.interstitial.minimumForegroundSeconds", 1_800, 0L..2_592_000L)}L")
            buildConfigField("int", "ADS_INTERSTITIAL_GAMES_BETWEEN_IMPRESSIONS", localIntProp("provider.release.ads.interstitial.gamesBetweenImpressions", 4, 1..10_000).toString())
            buildConfigField("String", "BILLING_REMOVE_ADS_PRODUCT_ID", "\"${localProp("provider.release.billing.removeAdsProductId", "")}\"")
            buildConfigField("String", "BILLING_PRO_SUBSCRIPTION_ID", "\"${localProp("provider.release.billing.proSubscriptionId", "")}\"")
            buildConfigField("String", "BILLING_PRO_PLUS_SUBSCRIPTION_ID", "\"${localProp("provider.release.billing.proPlusSubscriptionId", "")}\"")
            manifestPlaceholders["admobAppId"] = localProp(
                "provider.release.ads.admob.company.appId",
                "",
            )
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

tasks.register("validateReleaseAdsConfig") {
    group = "verification"
    description = "Validates the release backend endpoint and complete advertising SDK id groups without printing values."
    doLast {
        val errors = mutableListOf<String>()
        val missing = requiredReleaseAdPropertyKeys()
            .filter { localProps.getProperty(it).orEmpty().isBlank() }
        if (missing.isNotEmpty()) {
            errors += "Missing required release properties: ${missing.joinToString()}"
        }
        localProps.getProperty("online.release.baseUrl")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { baseUrl ->
                if (!baseUrl.matches(Regex("https://[^/]+(:[0-9]+)?"))) {
                    errors += "online.release.baseUrl must be an HTTPS origin without a path"
                }
            }
        errors += validateOptionalProviderGroup(
            providerName = "company AdMob",
            keys = listOf(
                "provider.release.ads.admob.company.appId",
                "provider.release.ads.admob.company.banner.game",
                "provider.release.ads.admob.company.rewarded.general",
                "provider.release.ads.admob.company.interstitial.postMatch",
            ),
        )
        errors += validateOptionalProviderGroup(
            providerName = "company Huawei",
            keys = listOf(
                "provider.release.ads.huawei.company.appId",
                "provider.release.ads.huawei.company.banner.game",
                "provider.release.ads.huawei.company.rewarded.general",
                "provider.release.ads.huawei.company.interstitial.postMatch",
            ),
        )
        if (errors.isNotEmpty()) {
            throw GradleException(errors.joinToString(separator = "\n"))
        }
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        (variantBuilder as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
    }
}

dependencies {
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
