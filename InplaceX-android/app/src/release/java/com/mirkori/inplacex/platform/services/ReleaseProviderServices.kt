package com.mirkori.inplacex.platform.services

import android.content.Context
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
            adService = AdMobService(context, providers.ads),
            billingService = GooglePlayBillingService(context, providers.billing),
        )
    }
}
