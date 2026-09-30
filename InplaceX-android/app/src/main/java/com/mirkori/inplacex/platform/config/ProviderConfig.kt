package com.mirkori.inplacex.platform.config

enum class ProviderEnvironment {
    SANDBOX,
    LIVE,
}

data class GooglePlayProviderConfig(
    val webClientId: String = "",
    val serverClientId: String = "",
    val gamesProjectId: String = "",
) {
    val isConfigured: Boolean
        get() = webClientId.isNotBlank()
}

data class AdsProviderConfig(
    val admobAppId: String = "",
    val gameBannerAdUnitId: String = "",
    val rewardedAdUnitId: String = "",
    val postMatchInterstitialAdUnitId: String = "",
) {
    val isConfigured: Boolean
        get() = admobAppId.isNotBlank()
}

data class BillingProviderConfig(
    val removeAdsProductId: String = "",
    val proSubscriptionId: String = "",
    val proPlusSubscriptionId: String = "",
) {
    val isConfigured: Boolean
        get() = removeAdsProductId.isNotBlank() &&
            proSubscriptionId.isNotBlank() &&
            proPlusSubscriptionId.isNotBlank()
}

data class ProviderConfig(
    val environment: ProviderEnvironment = ProviderEnvironment.SANDBOX,
    val googlePlay: GooglePlayProviderConfig = GooglePlayProviderConfig(),
    val ads: AdsProviderConfig = AdsProviderConfig(),
    val billing: BillingProviderConfig = BillingProviderConfig(),
)
