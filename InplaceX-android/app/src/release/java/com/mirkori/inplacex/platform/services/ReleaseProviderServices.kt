package com.mirkori.inplacex.platform.services

import android.content.Context
import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.platform.ads.AdConsentController
import com.mirkori.inplacex.platform.ads.AdActivityHost
import com.mirkori.inplacex.platform.ads.YandexAdProvider
import com.mirkori.inplacex.platform.ads.createAdRuntime
import com.mirkori.inplacex.platform.ads.SharedPreferencesAdConsentController
import com.mirkori.inplacex.platform.ads.createBackendAdMarketResolverOrUnknown
import com.mirkori.inplacex.platform.config.PlatformConfig

object ProviderServicesFactory {
    fun create(
        context: Context,
        platformConfig: PlatformConfig,
        adConsentController: AdConsentController? = null,
    ): ProviderServices {
        val providers = platformConfig.providers
        val auth = GooglePlayAuthService(context, providers.googlePlay)
        val adConsent = adConsentController ?: SharedPreferencesAdConsentController(context)
        val adActivityHost = AdActivityHost()
        val marketResolver = createBackendAdMarketResolverOrUnknown(
            baseUrl = BuildConfig.ONLINE_BASE_URL,
            allowCleartextLoopback = BuildConfig.ONLINE_ALLOW_CLEARTEXT_LOOPBACK,
        )
        val yandex = YandexAdProvider(
            appContext = context,
            config = providers.ads.ownerYandex,
            consentProvider = adConsent,
            activityHost = adActivityHost,
        )
        return ProviderServices(
            authService = auth,
            profileService = auth,
            adService = UnavailableAdService(),
            adRuntime = createAdRuntime(
                config = providers.ads,
                providers = listOf(yandex),
                marketResolver = marketResolver,
                consentProvider = adConsent,
            ),
            adConsent = adConsent,
            adActivityHost = adActivityHost,
            billingService = GooglePlayBillingService(context, providers.billing),
        )
    }
}
