package com.mirkori.inplacex.platform.mirkori

import android.app.Activity
import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.data.local.GameProgressRepository
import com.mirkori.inplacex.platform.config.BillingProviderConfig
import com.mirkori.inplacex.platform.services.BillingService

internal fun createDistributionBillingService(
    activity: Activity,
    runtime: MirkoriPlatformRuntime,
    config: BillingProviderConfig,
    progressRepository: GameProgressRepository,
): BillingService {
    check(activity.packageName == BuildConfig.APPLICATION_ID)
    return MirkoriBillingService(
        runtime = runtime,
        config = config,
        currency = BuildConfig.MIRKORI_BILLING_CURRENCY,
        deliveryApplier = InplaceXMirkoriGameDeliveryApplier(progressRepository),
    )
}
