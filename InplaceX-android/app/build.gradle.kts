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
            buildConfigField("String", "ADMOB_APP_ID", "\"${localProp("provider.debug.ads.admobAppId", "ca-app-pub-3940256099942544~3347511713")}\"")
            buildConfigField("String", "ADMOB_GAME_BANNER_AD_UNIT_ID", "\"${localProp("provider.debug.ads.banner.game", "ca-app-pub-3940256099942544/6300978111")}\"")
            buildConfigField("String", "ADMOB_REWARDED_AD_UNIT_ID", "\"${localProp("provider.debug.ads.rewarded.general", "ca-app-pub-3940256099942544/5224354917")}\"")
            buildConfigField("String", "ADMOB_POST_MATCH_INTERSTITIAL_AD_UNIT_ID", "\"${localProp("provider.debug.ads.interstitial.postMatch", "ca-app-pub-3940256099942544/1033173712")}\"")
            buildConfigField("String", "BILLING_REMOVE_ADS_PRODUCT_ID", "\"${localProp("provider.debug.billing.removeAdsProductId", "remove_ads")}\"")
            buildConfigField("String", "BILLING_PRO_SUBSCRIPTION_ID", "\"${localProp("provider.debug.billing.proSubscriptionId", "pro_subscription")}\"")
            buildConfigField("String", "BILLING_PRO_PLUS_SUBSCRIPTION_ID", "\"${localProp("provider.debug.billing.proPlusSubscriptionId", "pro_plus_subscription")}\"")
            manifestPlaceholders["admobAppId"] = localProp(
                "provider.debug.ads.admobAppId",
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
            buildConfigField("String", "ADMOB_APP_ID", "\"${localProp("provider.release.ads.admobAppId", "")}\"")
            buildConfigField("String", "ADMOB_GAME_BANNER_AD_UNIT_ID", "\"${localProp("provider.release.ads.banner.game", "")}\"")
            buildConfigField("String", "ADMOB_REWARDED_AD_UNIT_ID", "\"${localProp("provider.release.ads.rewarded.general", "")}\"")
            buildConfigField("String", "ADMOB_POST_MATCH_INTERSTITIAL_AD_UNIT_ID", "\"${localProp("provider.release.ads.interstitial.postMatch", "")}\"")
            buildConfigField("String", "BILLING_REMOVE_ADS_PRODUCT_ID", "\"${localProp("provider.release.billing.removeAdsProductId", "")}\"")
            buildConfigField("String", "BILLING_PRO_SUBSCRIPTION_ID", "\"${localProp("provider.release.billing.proSubscriptionId", "")}\"")
            buildConfigField("String", "BILLING_PRO_PLUS_SUBSCRIPTION_ID", "\"${localProp("provider.release.billing.proPlusSubscriptionId", "")}\"")
            manifestPlaceholders["admobAppId"] = localProp("provider.release.ads.admobAppId", "")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        (variantBuilder as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
    }
}

dependencies {
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

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(project(":InplaceX-test-support"))
    testImplementation(libs.ktor.client.mock)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
