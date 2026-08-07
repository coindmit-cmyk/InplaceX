package com.mirkori.inplacex.platform.config

import com.mirkori.inplacex.ads.AdProviderId
import com.mirkori.inplacex.ads.PostMatchInterstitialPolicy

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

data class AdSdkConfig(
    val gameBannerAdUnitId: String = "",
    val rewardedAdUnitId: String = "",
    val postMatchInterstitialAdUnitId: String = "",
) {
    val hasAnyPlacement: Boolean
        get() = gameBannerAdUnitId.isNotBlank() ||
            rewardedAdUnitId.isNotBlank() ||
            postMatchInterstitialAdUnitId.isNotBlank()
}

data class AdsProviderConfig(
    val ownerYandex: AdSdkConfig = AdSdkConfig(),
    val postMatchInterstitialPolicy: PostMatchInterstitialPolicy =
        PostMatchInterstitialPolicy(),
) {
    val isConfigured: Boolean
        get() = configuredProviderIds().isNotEmpty()

    fun configuredProviderIds(): List<AdProviderId> = buildList {
        if (ownerYandex.hasAnyPlacement) {
            add(AdProviderId.OWNER_YANDEX)
        }
    }
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
