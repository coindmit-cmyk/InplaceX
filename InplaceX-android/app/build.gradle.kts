import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

        buildConfigField("String", "PROVIDER_ENVIRONMENT", "\"${localProp("provider.environment", "sandbox")}\"")
        buildConfigField("String", "GOOGLE_PLAY_WEB_CLIENT_ID", "\"${localProp("provider.googlePlay.webClientId", "")}\"")
        buildConfigField("String", "GOOGLE_PLAY_GAMES_PROJECT_ID", "\"${localProp("provider.googlePlay.gamesProjectId", "")}\"")
        buildConfigField("String", "GOOGLE_PLAY_SERVER_CLIENT_ID", "\"${localProp("provider.googlePlay.serverClientId", "")}\"")
        buildConfigField("String", "ADMOB_APP_ID", "\"${localProp("provider.ads.admobAppId", "ca-app-pub-3940256099942544~3347511713")}\"")
        buildConfigField("String", "ADMOB_GAME_BANNER_AD_UNIT_ID", "\"${localProp("provider.ads.banner.game", "ca-app-pub-3940256099942544/6300978111")}\"")
        buildConfigField("String", "ADMOB_REWARDED_AD_UNIT_ID", "\"${localProp("provider.ads.rewarded.general", "ca-app-pub-3940256099942544/5224354917")}\"")
        buildConfigField("String", "ADMOB_POST_MATCH_INTERSTITIAL_AD_UNIT_ID", "\"${localProp("provider.ads.interstitial.postMatch", "ca-app-pub-3940256099942544/1033173712")}\"")
        buildConfigField("String", "BILLING_REMOVE_ADS_PRODUCT_ID", "\"${localProp("provider.billing.removeAdsProductId", "remove_ads")}\"")
        buildConfigField("String", "BILLING_PRO_SUBSCRIPTION_ID", "\"${localProp("provider.billing.proSubscriptionId", "pro_subscription")}\"")
        buildConfigField("String", "BILLING_PRO_PLUS_SUBSCRIPTION_ID", "\"${localProp("provider.billing.proPlusSubscriptionId", "pro_plus_subscription")}\"")

        manifestPlaceholders["admobAppId"] = localProp(
            "provider.ads.admobAppId",
            "ca-app-pub-3940256099942544~3347511713"
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.leanback)
    implementation(libs.glide)
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))

    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

}
