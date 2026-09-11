package com.mirkori.inplacex.platform.mirkori

import android.app.Activity
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.mirkori.inplacex.BuildConfig
import com.mirkori.inplacex.platform.config.BillingProviderConfig
import com.mirkori.inplacex.platform.services.BillingService
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal fun createDistributionBillingService(
    activity: Activity,
    runtime: MirkoriPlatformRuntime,
    config: BillingProviderConfig,
): BillingService = MirkoriBillingService(
    runtime = runtime,
    config = config,
    currency = BuildConfig.MIRKORI_BILLING_CURRENCY,
    paymentFlow = GooglePlayMirkoriPaymentFlow(
        gateway = AndroidGooglePlayBillingGateway(activity),
        expectedDistributionId = BuildConfig.MIRKORI_DISTRIBUTION_ID,
        expectedPackageName = BuildConfig.APPLICATION_ID,
    ),
)

private class AndroidGooglePlayBillingGateway(
    private val activity: Activity,
) : GooglePlayBillingGateway, PurchasesUpdatedListener {
    private val closed = AtomicBoolean(false)
    private val billingClient = BillingClient.newBuilder(activity.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build(),
        )
        .enableAutoServiceReconnection()
        .build()
    private val callbackLock = Any()
    private var pendingCallback: PendingCallback? = null

    override suspend fun query(productId: String, obfuscatedProfileId: String): GooglePlayPurchase {
        if (!connect()) return unavailable()
        return suspendCancellableCoroutine { continuation ->
            billingClient.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build(),
            ) { result, purchases ->
                if (!continuation.isActive) return@queryPurchasesAsync
                continuation.resume(
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        selectPurchase(purchases, productId, obfuscatedProfileId)
                    } else {
                        unavailable()
                    },
                )
            }
        }
    }

    override suspend fun launch(productId: String, obfuscatedProfileId: String): GooglePlayPurchase {
        if (!connect()) return unavailable()
        val productDetails = queryProduct(productId) ?: return unavailable()
        val completion = CompletableDeferred<GooglePlayPurchase>()
        synchronized(callbackLock) {
            if (pendingCallback != null || closed.get()) return unavailable()
            pendingCallback = PendingCallback(productId, obfuscatedProfileId, completion)
        }
        completion.invokeOnCompletion {
            synchronized(callbackLock) {
                if (pendingCallback?.completion === completion) pendingCallback = null
            }
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .also { builder ->
                productDetails.oneTimePurchaseOfferDetailsList
                    ?.singleOrNull()
                    ?.offerToken
                    ?.takeIf(String::isNotBlank)
                    ?.let(builder::setOfferToken)
            }
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setObfuscatedProfileId(obfuscatedProfileId)
            .build()
        activity.runOnUiThread {
            val result = if (closed.get() || activity.isFinishing || activity.isDestroyed) {
                null
            } else {
                billingClient.launchBillingFlow(activity, flowParams)
            }
            when (result?.responseCode) {
                BillingClient.BillingResponseCode.OK -> Unit
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> completePendingFromQuery()
                BillingClient.BillingResponseCode.USER_CANCELED -> completePending(cancelled())
                else -> completePending(unavailable())
            }
        }
        return completion.await()
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val callback = synchronized(callbackLock) { pendingCallback } ?: return
        val purchase = when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                selectPurchase(purchases.orEmpty(), callback.productId, callback.obfuscatedProfileId)
            BillingClient.BillingResponseCode.USER_CANCELED -> cancelled()
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                completePendingFromQuery()
                return
            }
            else -> unavailable()
        }
        completePending(purchase)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        completePending(unavailable())
        billingClient.endConnection()
    }

    private suspend fun connect(): Boolean {
        if (closed.get()) return false
        if (billingClient.isReady) return true
        return suspendCancellableCoroutine { continuation ->
            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) {
                        continuation.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                    }
                }

                override fun onBillingServiceDisconnected() = Unit
            })
        }
    }

    private suspend fun queryProduct(productId: String): ProductDetails? =
        suspendCancellableCoroutine { continuation ->
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
            billingClient.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build(),
            ) { result, details ->
                if (!continuation.isActive) return@queryProductDetailsAsync
                val match = details.productDetailsList.singleOrNull { candidate ->
                    candidate.productId == productId && candidate.productType == BillingClient.ProductType.INAPP &&
                        candidate.oneTimePurchaseOfferDetailsList.orEmpty().size <= 1
                }
                continuation.resume(
                    match.takeIf { result.responseCode == BillingClient.BillingResponseCode.OK },
                )
            }
        }

    private fun completePendingFromQuery() {
        val callback = synchronized(callbackLock) { pendingCallback } ?: return
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        ) { result, purchases ->
            completePending(
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    selectPurchase(purchases, callback.productId, callback.obfuscatedProfileId)
                } else {
                    unavailable()
                },
            )
        }
    }

    private fun completePending(result: GooglePlayPurchase) {
        val completion = synchronized(callbackLock) { pendingCallback?.completion } ?: return
        completion.complete(result)
    }

    private fun selectPurchase(
        purchases: List<Purchase>,
        productId: String,
        obfuscatedProfileId: String,
    ): GooglePlayPurchase {
        val matched = purchases.singleOrNull { purchase ->
            purchase.products == listOf(productId) &&
                purchase.accountIdentifiers?.obfuscatedProfileId == obfuscatedProfileId
        } ?: return GooglePlayPurchase(GooglePlayPurchaseState.NONE)
        return when (matched.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> GooglePlayPurchase(
                state = GooglePlayPurchaseState.PURCHASED,
                productId = productId,
                purchaseToken = matched.purchaseToken,
                obfuscatedProfileId = obfuscatedProfileId,
            )
            Purchase.PurchaseState.PENDING -> GooglePlayPurchase(GooglePlayPurchaseState.PENDING)
            else -> GooglePlayPurchase(GooglePlayPurchaseState.NONE)
        }
    }

    private data class PendingCallback(
        val productId: String,
        val obfuscatedProfileId: String,
        val completion: CompletableDeferred<GooglePlayPurchase>,
    )

    private companion object {
        fun unavailable() = GooglePlayPurchase(GooglePlayPurchaseState.UNAVAILABLE)
        fun cancelled() = GooglePlayPurchase(GooglePlayPurchaseState.CANCELLED)
    }
}
