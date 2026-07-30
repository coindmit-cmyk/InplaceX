package com.mirkori.inplacex.platform.services

import android.content.Context
import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.platform.ads.createBackendAdMarketResolverOrUnknown
import com.mirkori.inplacex.platform.ads.createFailClosedAdRuntime
import com.mirkori.inplacex.platform.config.PlatformConfig

object ProviderServicesFactory {
    fun create(
        context: Context,
        platformConfig: PlatformConfig,
    ): ProviderServices {
        val providers = platformConfig.providers
        val auth = GooglePlayAuthService(context, providers.googlePlay)
        return ProviderServices(
            authService = auth,
            profileService = auth,
            adService = CompanyAdMobService(context, providers.ads.companyAdMob),
            adRuntime = createFailClosedAdRuntime(
                config = providers.ads,
                marketResolver = createBackendAdMarketResolverOrUnknown(
                    baseUrl = BuildConfig.ONLINE_BASE_URL,
                    allowCleartextLoopback = BuildConfig.ONLINE_ALLOW_CLEARTEXT_LOOPBACK,
                ),
            ),
            billingService = GooglePlayBillingService(context, providers.billing),
        )
    }
}
