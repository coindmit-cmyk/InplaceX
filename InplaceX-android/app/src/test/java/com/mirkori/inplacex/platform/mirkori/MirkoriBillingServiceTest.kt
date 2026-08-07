package com.mirkori.inplacex.platform.mirkori

import com.mirkori.inplacex.platform.config.BillingProviderConfig
import com.mirkori.inplacex.platform.services.BillingAvailability
import com.mirkori.inplacex.platform.services.BillingNotice
import com.mirkori.inplacex.platform.services.BillingProductId
import com.mirkori.inplacex.platform.services.BillingPurchaseResult
import com.mirkori.platform.sdk.GameIdentitySession
import com.mirkori.platform.sdk.InstallationIdentity
import com.mirkori.platform.sdk.MirkoriGameSdk
import com.mirkori.platform.sdk.MirkoriGameSdkConfig
import com.mirkori.platform.sdk.PlatformAuthMode
import com.mirkori.platform.sdk.PlatformCredentials
import com.mirkori.platform.sdk.PlatformHttpRequest
import com.mirkori.platform.sdk.PlatformHttpResponse
import com.mirkori.platform.sdk.PlatformIdempotencyKey
import com.mirkori.platform.sdk.PlatformTransport
import com.mirkori.platform.sdk.SecureEntropy
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MirkoriBillingServiceTest {
    @Test
    fun guestCannotCreateOrderOrCheckout() {
        val transport = ScriptedTransport(response(productsJson()))
        val store = linkedStore(authMode = PlatformAuthMode.GUEST)
        val service = service(transport, store)

        val result = runSuspend { service.purchase(BillingProductId.REMOVE_ADS) }

        assertTrue(result is BillingPurchaseResult.StateUpdated)
        assertEquals(BillingNotice.LINKED_ACCOUNT_REQUIRED, result.state.notice)
        assertEquals(1, transport.requests.size)
        assertNull(store.value?.pendingPurchase)
    }

    @Test
    fun ambiguousOrderRetryReusesPersistedKeysAndCheckoutDoesNotUnlockAnything() {
        val store = linkedStore()
        val firstTransport = ScriptedTransport(
            response(productsJson()),
            response(ordersJson()),
            MirkoriTransportException(MirkoriTransportFailure.NETWORK),
        )
        val firstResult = runSuspend {
            service(firstTransport, store).purchase(BillingProductId.REMOVE_ADS)
        }
        val persisted = requireNotNull(store.value?.pendingPurchase)

        assertTrue(firstResult is BillingPurchaseResult.StateUpdated)
        assertEquals(persisted.orderIdempotencyKey.value, firstTransport.requests[2].headers["Idempotency-Key"])
        assertNull(persisted.orderId)

        val orderId = "00000000-0000-4000-8000-000000000901"
        val checkoutId = "00000000-0000-4000-8000-000000000902"
        val retryTransport = ScriptedTransport(
            response(productsJson()),
            response(orderJson(orderId, "pending", "remove_ads"), status = 201),
            response(checkoutJson(checkoutId, orderId)),
        )
        val retried = runSuspend {
            service(retryTransport, store).purchase(BillingProductId.REMOVE_ADS)
        }

        assertTrue(retried is BillingPurchaseResult.OpenExternalCheckout)
        assertEquals(persisted.orderIdempotencyKey.value, retryTransport.requests[1].headers["Idempotency-Key"])
        assertEquals(persisted.checkoutIdempotencyKey.value, retryTransport.requests[2].headers["Idempotency-Key"])
        assertTrue(persisted.orderIdempotencyKey.value != persisted.checkoutIdempotencyKey.value)
        assertFalse(retried.state.entitlements.adsDisabled)
        assertNotNull(store.value?.pendingPurchase)
    }

    @Test
    fun unauthorizedOrderRefreshesSessionAndRetriesTheSameOrderOperation() {
        val orderId = "00000000-0000-4000-8000-000000000906"
        val checkoutId = "00000000-0000-4000-8000-000000000907"
        val store = linkedStore()
        val transport = ScriptedTransport(
            response(productsJson()),
            response(ordersJson()),
            response("""{"error":"profile_auth_required"}""", status = 401),
            response(billingCredentialsJson("refreshed")),
            response(orderJson(orderId, "pending", "remove_ads"), status = 201),
            response(checkoutJson(checkoutId, orderId)),
        )

        val result = runSuspend { service(transport, store).purchase(BillingProductId.REMOVE_ADS) }

        assertTrue(result is BillingPurchaseResult.OpenExternalCheckout)
        assertEquals(
            transport.requests[2].headers["Idempotency-Key"],
            transport.requests[4].headers["Idempotency-Key"],
        )
        assertTrue(store.value?.session?.credentials?.accessToken.orEmpty().startsWith("refreshed."))
        assertFalse(result.state.entitlements.adsDisabled)
    }

    @Test
    fun resumeUnlocksOnlyAfterPaidOrderAndMatchingServerEntitlement() {
        val orderId = "00000000-0000-4000-8000-000000000911"
        val store = linkedStore(
            pending = pendingPurchase(orderId = orderId),
        )
        val transport = ScriptedTransport(
            response(productsJson()),
            response(orderJson(orderId, "paid", "remove_ads")),
            response(entitlementsJson("""{"key":"ads.disabled","type":"durable","quantity":1}""")),
        )

        val state = runSuspend { service(transport, store).refresh() }

        assertEquals(BillingNotice.PAYMENT_CONFIRMED, state.notice)
        assertTrue(state.entitlements.adFreePurchased)
        assertTrue(state.entitlements.adsDisabled)
        assertNull(store.value?.pendingPurchase)
        assertTrue(store.value?.confirmedEntitlements?.removeAds?.active == true)
    }

    @Test
    fun paidOrderWithoutEntitlementStaysFailClosedAndRecoverable() {
        val orderId = "00000000-0000-4000-8000-000000000921"
        val store = linkedStore(pending = pendingPurchase(orderId = orderId))
        val transport = ScriptedTransport(
            response(productsJson()),
            response(orderJson(orderId, "paid", "remove_ads")),
            response(entitlementsJson()),
        )

        val state = runSuspend { service(transport, store).refresh() }

        assertEquals(BillingNotice.AWAITING_ENTITLEMENT, state.notice)
        assertFalse(state.entitlements.adsDisabled)
        assertNotNull(store.value?.pendingPurchase)
    }

    @Test
    fun provider503KeepsSameCheckoutAttemptForExplicitRetry() {
        val orderId = "00000000-0000-4000-8000-000000000931"
        val store = linkedStore()
        val transport = ScriptedTransport(
            response(productsJson()),
            response(ordersJson()),
            response(orderJson(orderId, "pending", "remove_ads"), status = 201),
            response("""{"error":"checkout_unavailable"}""", status = 503),
            response(productsJson()),
            response(orderJson(orderId, "pending", "remove_ads")),
            response("""{"error":"checkout_unavailable"}""", status = 503),
        )
        val service = service(transport, store)

        val first = runSuspend { service.purchase(BillingProductId.REMOVE_ADS) }
        val key = requireNotNull(store.value?.pendingPurchase).checkoutIdempotencyKey.value
        val second = runSuspend { service.purchase(BillingProductId.REMOVE_ADS) }

        assertEquals(BillingNotice.PROVIDER_UNAVAILABLE, first.state.notice)
        assertEquals(BillingNotice.PROVIDER_UNAVAILABLE, second.state.notice)
        assertEquals(key, transport.requests[3].headers["Idempotency-Key"])
        assertEquals(key, transport.requests[6].headers["Idempotency-Key"])
        assertNotNull(store.value?.pendingPurchase)
    }

    @Test
    fun checkoutReconciliationRequiredKeepsOriginalAttemptUntilServerConfirmsCancellation() {
        val orderId = "00000000-0000-4000-8000-000000000934"
        val store = linkedStore(pending = pendingPurchase(orderId = orderId))
        val originalKey = requireNotNull(store.value?.pendingPurchase).checkoutIdempotencyKey.value
        val transport = ScriptedTransport(
            response(productsJson()),
            response(orderJson(orderId, "pending", "remove_ads")),
            response("""{"error":"checkout_reconciliation_required"}""", status = 409),
        )

        val result = runSuspend { service(transport, store).purchase(BillingProductId.REMOVE_ADS) }
        val retainedKey = requireNotNull(store.value?.pendingPurchase).checkoutIdempotencyKey.value

        assertEquals(BillingNotice.CHECKOUT_EXPIRED, result.state.notice)
        assertEquals(originalKey, transport.requests[2].headers["Idempotency-Key"])
        assertEquals(originalKey, retainedKey)
        assertNotNull(store.value?.pendingPurchase)
    }

    @Test
    fun orderPriceMismatchFailsClosedBeforeCheckout() {
        val orderId = "00000000-0000-4000-8000-000000000936"
        val store = linkedStore()
        val transport = ScriptedTransport(
            response(productsJson()),
            response(ordersJson()),
            response(orderJson(orderId, "pending", "remove_ads", amountMinor = 19_900), status = 201),
        )

        val result = runSuspend { service(transport, store).purchase(BillingProductId.REMOVE_ADS) }

        assertTrue(result is BillingPurchaseResult.StateUpdated)
        assertEquals(BillingNotice.RETRY_REQUIRED, result.state.notice)
        assertFalse(result.state.entitlements.adsDisabled)
        assertEquals(3, transport.requests.size)
        assertNotNull(store.value?.pendingPurchase)
    }

    @Test
    fun serverCancellationClearsPendingPurchaseWithoutGrant() {
        val orderId = "00000000-0000-4000-8000-000000000941"
        val store = linkedStore(pending = pendingPurchase(orderId = orderId))
        val transport = ScriptedTransport(
            response(productsJson()),
            response(orderJson(orderId, "cancelled", "remove_ads")),
            response(entitlementsJson()),
        )

        val state = runSuspend { service(transport, store).refresh() }

        assertEquals(BillingNotice.PAYMENT_CANCELLED, state.notice)
        assertFalse(state.entitlements.adsDisabled)
        assertNull(store.value?.pendingPurchase)
    }

    @Test
    fun serverRefundClearsPendingAndRevokedGrant() {
        val orderId = "00000000-0000-4000-8000-000000000946"
        val store = linkedStore(
            pending = pendingPurchase(orderId = orderId),
            confirmed = ConfirmedMirkoriEntitlements(
                accountId = AccountId,
                gamePlayerId = PlayerId,
                confirmedAtEpochMs = NowMs - 1_000,
                removeAds = MirkoriFeatureGrant(true),
                pro = MirkoriFeatureGrant(false),
                proPlus = MirkoriFeatureGrant(false),
            ),
        )
        val transport = ScriptedTransport(
            response(productsJson()),
            response(orderJson(orderId, "refunded", "remove_ads")),
            response(entitlementsJson()),
        )

        val state = runSuspend { service(transport, store).refresh() }

        assertEquals(BillingNotice.PAYMENT_REFUNDED, state.notice)
        assertFalse(state.entitlements.adsDisabled)
        assertNull(store.value?.pendingPurchase)
        assertFalse(store.value?.confirmedEntitlements?.removeAds?.active == true)
    }

    @Test
    fun cachedTimedEntitlementExpiresWithoutAClientSideRevocationFlag() {
        val store = linkedStore(
            confirmed = ConfirmedMirkoriEntitlements(
                accountId = AccountId,
                gamePlayerId = PlayerId,
                confirmedAtEpochMs = NowMs - 1_000,
                removeAds = MirkoriFeatureGrant(false),
                pro = MirkoriFeatureGrant(true, validUntilEpochMs = NowMs + 1_000),
                proPlus = MirkoriFeatureGrant(false),
            ),
        )
        val before = service(ScriptedTransport(), store, nowMs = NowMs).cachedState()
        val after = service(ScriptedTransport(), store, nowMs = NowMs + 1_001).cachedState()

        assertTrue(before.entitlements.proSubscriptionActive)
        assertFalse(after.entitlements.proSubscriptionActive)
    }

    @Test
    fun offlineRefreshPreservesOnlyUnexpiredServerConfirmedAccess() {
        val store = linkedStore(
            confirmed = ConfirmedMirkoriEntitlements(
                accountId = AccountId,
                gamePlayerId = PlayerId,
                confirmedAtEpochMs = NowMs - 1_000,
                removeAds = MirkoriFeatureGrant(true),
                pro = MirkoriFeatureGrant(false),
                proPlus = MirkoriFeatureGrant(false),
            ),
        )
        val transport = ScriptedTransport(MirkoriTransportException(MirkoriTransportFailure.OFFLINE))

        val state = runSuspend { service(transport, store).refresh() }

        assertEquals(BillingAvailability.OFFLINE, state.availability)
        assertEquals(BillingNotice.OFFLINE, state.notice)
        assertTrue(state.entitlements.adsDisabled)
        assertTrue(store.value?.confirmedEntitlements?.removeAds?.active == true)
    }

    @Test
    fun delistedProductDoesNotRevokeStableTypedEntitlement() {
        val store = linkedStore()
        val transport = ScriptedTransport(
            response(productsJson(includeRemoveAds = false)),
            response(ordersJson()),
            response(entitlementsJson("""{"key":"ads.disabled","type":"durable","quantity":1}""")),
        )

        val state = runSuspend { service(transport, store).refresh() }

        assertTrue(state.entitlements.adFreePurchased)
        assertFalse(state.products.containsKey(BillingProductId.REMOVE_ADS))
        assertTrue(store.value?.confirmedEntitlements?.removeAds?.active == true)
    }

    @Test
    fun currentPriceChangeDoesNotMutatePersistedPendingOffer() {
        val orderId = "00000000-0000-4000-8000-000000000961"
        val store = linkedStore(pending = pendingPurchase(orderId))
        val transport = ScriptedTransport(
            response(productsJson(removeAdsAmountMinor = 19_900)),
            response(orderJson(orderId, "pending", "remove_ads", amountMinor = 9_900)),
            response(entitlementsJson()),
        )

        val state = runSuspend { service(transport, store).refresh() }

        assertEquals(BillingNotice.AWAITING_PAYMENT, state.notice)
        assertEquals(9_900L, store.value?.pendingPurchase?.offerSnapshot?.amountMinor)
        assertEquals(1L, store.value?.pendingPurchase?.offerSnapshot?.productVersion)
    }

    @Test
    fun legacyPendingStateAdoptsAuthoritativeOrderPriceWithoutUsingCurrentCatalogPrice() {
        val orderId = "00000000-0000-4000-8000-000000000967"
        val legacyPending = pendingPurchase(orderId).copy(offerSnapshot = null)
        val store = linkedStore(pending = legacyPending)
        val transport = ScriptedTransport(
            response(productsJson(removeAdsAmountMinor = 19_900)),
            response(orderJson(orderId, "pending", "remove_ads", amountMinor = 9_900)),
            response(entitlementsJson()),
        )

        val state = runSuspend { service(transport, store).refresh() }

        assertEquals(BillingNotice.AWAITING_PAYMENT, state.notice)
        assertEquals(9_900L, store.value?.pendingPurchase?.offerSnapshot?.amountMinor)
        assertNull(store.value?.pendingPurchase?.offerSnapshot?.productVersion)
    }

    @Test
    fun terminalCancellationClearsPendingEvenWhenOfferWasDelistedAndAmountChanged() {
        val orderId = "00000000-0000-4000-8000-000000000962"
        val store = linkedStore(pending = pendingPurchase(orderId))
        val transport = ScriptedTransport(
            response(productsJson(includeRemoveAds = false)),
            response(orderJson(orderId, "cancelled", "remove_ads", amountMinor = 19_900)),
            response(entitlementsJson()),
        )

        val state = runSuspend { service(transport, store).refresh() }

        assertEquals(BillingNotice.PAYMENT_CANCELLED, state.notice)
        assertNull(store.value?.pendingPurchase)
    }

    @Test
    fun reinstallRestoresExactlyOneServerPendingOrderBeforeCreatingAnything() {
        val orderId = "00000000-0000-4000-8000-000000000963"
        val checkoutId = "00000000-0000-4000-8000-000000000964"
        val order = orderJson(orderId, "pending", "remove_ads")
        val store = linkedStore()
        val transport = ScriptedTransport(
            response(productsJson()),
            response(ordersJson(order)),
            response(order),
            response(checkoutJson(checkoutId, orderId)),
        )

        val result = runSuspend { service(transport, store).purchase(BillingProductId.REMOVE_ADS) }

        assertTrue(result is BillingPurchaseResult.OpenExternalCheckout)
        assertEquals(orderId, store.value?.pendingPurchase?.orderId)
        assertEquals(9_900L, store.value?.pendingPurchase?.offerSnapshot?.amountMinor)
        assertTrue(transport.requests[1].url.endsWith("/api/v1/commerce/orders/pending"))
        assertFalse(
            transport.requests.any {
                it.method.name == "GET" && it.url.endsWith("/api/v1/commerce/orders")
            },
        )
        assertFalse(
            transport.requests.any { request ->
                request.url.endsWith("/api/v1/commerce/orders") && request.method.name == "POST"
            },
        )
    }

    @Test
    fun proPlusEntitlementCoversProAndCompletesLowerTierPendingOrder() {
        val orderId = "00000000-0000-4000-8000-000000000968"
        val expiresAt = Instant.ofEpochMilli(NowMs + 60_000L)
        val store = linkedStore(
            pending = pendingPurchase(
                orderId = orderId,
                productId = "pro_subscription",
                amountMinor = 19_900,
            ),
        )
        val transport = ScriptedTransport(
            response(productsJson()),
            response(orderJson(orderId, "paid", "pro_subscription", amountMinor = 19_900)),
            response(
                entitlementsJson(
                    """{"key":"pro-plus.active","type":"timed","quantity":1,"validUntil":"$expiresAt"}""",
                ),
            ),
        )

        val state = runSuspend { service(transport, store).refresh() }

        assertEquals(BillingNotice.PAYMENT_CONFIRMED, state.notice)
        assertTrue(state.entitlements.proSubscriptionActive)
        assertTrue(state.entitlements.proPlusSubscriptionActive)
        assertNull(store.value?.pendingPurchase)
    }

    @Test
    fun multipleServerPendingOrdersFailClosedWithoutCreatingAnotherOrder() {
        val first = orderJson("00000000-0000-4000-8000-000000000965", "pending", "remove_ads")
        val second = orderJson(
            "00000000-0000-4000-8000-000000000966",
            "pending",
            "pro_subscription",
            amountMinor = 19_900,
        )
        val store = linkedStore()
        val transport = ScriptedTransport(
            response(productsJson()),
            response(ordersJson(first, second)),
        )

        val result = runSuspend { service(transport, store).purchase(BillingProductId.REMOVE_ADS) }

        assertTrue(result is BillingPurchaseResult.StateUpdated)
        assertEquals(BillingNotice.BUSY, result.state.notice)
        assertEquals(2, transport.requests.size)
        assertNull(store.value?.pendingPurchase)
    }

    @Test
    fun cancellationDuringAlreadyOwnedRecoveryIsRethrown() {
        val transport = ScriptedTransport(
            response("""{"error":"product_already_owned"}""", status = 409),
            CancellationException("cancelled by caller"),
        )

        assertThrows(CancellationException::class.java) {
            runSuspend { service(transport, linkedStore()).refresh() }
        }
    }

    @Test
    fun purchaseCancellationDuringAlreadyOwnedRecoveryIsRethrown() {
        val transport = ScriptedTransport(
            response(productsJson()),
            response(ordersJson()),
            response("""{"error":"product_already_owned"}""", status = 409),
            CancellationException("cancelled by caller"),
        )

        assertThrows(CancellationException::class.java) {
            runSuspend { service(transport, linkedStore()).purchase(BillingProductId.REMOVE_ADS) }
        }
    }

    @Test
    fun orderPendingDropsLosingLocalAttemptAndRestoresAuthoritativeServerPending() {
        val serverOrderId = "00000000-0000-4000-8000-000000000969"
        val serverOrder = orderJson(serverOrderId, "pending", "remove_ads")
        val store = linkedStore()
        val transport = ScriptedTransport(
            response(productsJson()),
            response(ordersJson()),
            response("""{"error":"order_pending"}""", status = 409),
            response(productsJson()),
            response(ordersJson(serverOrder)),
            response(serverOrder),
            response(entitlementsJson()),
        )

        val result = runSuspend { service(transport, store).purchase(BillingProductId.REMOVE_ADS) }

        assertTrue(result is BillingPurchaseResult.StateUpdated)
        assertEquals(BillingNotice.AWAITING_PAYMENT, result.state.notice)
        assertEquals(serverOrderId, store.value?.pendingPurchase?.orderId)
        assertTrue(transport.requests[4].url.endsWith("/api/v1/commerce/orders/pending"))
        assertFalse(
            transport.requests.any {
                it.method.name == "GET" && it.url.endsWith("/api/v1/commerce/orders")
            },
        )
    }

    @Test
    fun cancellationDuringOrderPendingRecoveryIsRethrownAfterLosingAttemptIsCleared() {
        val store = linkedStore()
        val transport = ScriptedTransport(
            response(productsJson()),
            response(ordersJson()),
            response("""{"error":"order_pending"}""", status = 409),
            CancellationException("cancelled by caller"),
        )

        assertThrows(CancellationException::class.java) {
            runSuspend { service(transport, store).purchase(BillingProductId.REMOVE_ADS) }
        }
        assertNull(store.value?.pendingPurchase)
    }

    @Test
    fun timedAccessUsesServerAnchorAndFailsClosedAfterRollbackOrReboot() {
        val expiresAt = Instant.ofEpochMilli(NowMs + 60_000L)
        val store = linkedStore()
        val transport = ScriptedTransport(
            response(productsJson()),
            response(ordersJson()),
            response(
                entitlementsJson(
                    """{"key":"pro.active","type":"timed","quantity":1,"validUntil":"$expiresAt"}""",
                ),
            ),
        )

        val synchronized = runSuspend { service(transport, store).refresh() }
        val rolledBack = service(ScriptedTransport(), store, nowMs = NowMs - 1L).cachedState()
        val rebooted = service(
            ScriptedTransport(),
            store,
            nowMs = NowMs + 1_000L,
            bootMarker = TestBootMarker + 1L,
        ).cachedState()

        assertTrue(synchronized.entitlements.proSubscriptionActive)
        assertEquals(60_000L, synchronized.nextEntitlementExpiryDelayMs)
        assertFalse(rolledBack.entitlements.proSubscriptionActive)
        assertFalse(rebooted.entitlements.proSubscriptionActive)
    }

    private fun service(
        transport: PlatformTransport,
        store: BillingMemoryStore,
        nowMs: Long = NowMs,
        bootMarker: Long = TestBootMarker,
    ): MirkoriBillingService {
        val sdk = MirkoriGameSdk(
            MirkoriGameSdkConfig(
                platformBaseUrl = "https://games.dmit.life",
                gameId = "inplacex",
                redirectUri = MirkoriPlatformRuntime.RedirectUri,
            ),
            transport,
            BillingCountingEntropy(),
        )
        return MirkoriBillingService(
            runtime = MirkoriPlatformRuntime(
                sdk = sdk,
                store = store,
                clockMs = { nowMs },
                monotonicClockMs = { nowMs },
                bootMarker = { bootMarker },
            ),
            config = BillingProviderConfig("remove_ads", "pro_subscription", "pro_plus_subscription"),
        )
    }

    private fun linkedStore(
        authMode: PlatformAuthMode = PlatformAuthMode.LOCAL,
        pending: PendingMirkoriPurchase? = null,
        confirmed: ConfirmedMirkoriEntitlements? = null,
    ): BillingMemoryStore = BillingMemoryStore(
        MirkoriPersistedState(
            installation = InstallationIdentity(InstallationId, "I".repeat(43)),
            session = GameIdentitySession(
                accountId = AccountId,
                gamePlayerId = PlayerId,
                gameId = "inplacex",
                installationId = InstallationId,
                authMode = authMode,
                credentials = credentials(),
            ),
            pendingPurchase = pending,
            confirmedEntitlements = confirmed,
            trustedTimeAnchor = confirmed?.let {
                MirkoriTrustedTimeAnchor(
                    serverEpochMs = NowMs,
                    monotonicAtObservationMs = NowMs,
                    bootMarker = TestBootMarker,
                )
            },
        ),
    )

    private fun pendingPurchase(
        orderId: String?,
        productId: String = "remove_ads",
        amountMinor: Long = 9_900,
    ): PendingMirkoriPurchase = PendingMirkoriPurchase(
        accountId = AccountId,
        gamePlayerId = PlayerId,
        productId = productId,
        currency = "RUB",
        orderId = orderId,
        orderIdempotencyKey = PlatformIdempotencyKey("stable-order-key"),
        checkoutIdempotencyKey = PlatformIdempotencyKey("stable-checkout-key"),
        offerSnapshot = PendingMirkoriOfferSnapshot(
            amountMinor = amountMinor,
            currency = "RUB",
            entitlementSchemaVersion = 1,
            productVersion = 1,
        ),
    )

    private companion object {
        const val AccountId = "00000000-0000-4000-8000-000000000951"
        const val PlayerId = "00000000-0000-4000-8000-000000000952"
        const val InstallationId = "00000000-0000-4000-8000-000000000953"
        const val NowMs = 1_786_000_000_000L
        const val TestBootMarker = 7L
    }
}

private class BillingMemoryStore(initial: MirkoriPersistedState) : SecureMirkoriStateStore {
    var value: MirkoriPersistedState? = initial

    override fun read(): MirkoriPersistedState? = value

    override fun write(state: MirkoriPersistedState) {
        value = state
    }

    override fun clear() {
        value = null
    }
}

private class ScriptedTransport(vararg steps: Any) : PlatformTransport {
    private val queued = steps.toMutableList()
    val requests = mutableListOf<PlatformHttpRequest>()

    override suspend fun execute(request: PlatformHttpRequest): PlatformHttpResponse {
        requests += request
        return when (val next = queued.removeFirstOrNull() ?: error("No scripted response")) {
            is PlatformHttpResponse -> next
            is Throwable -> throw next
            else -> error("Unsupported scripted step")
        }
    }
}

private class BillingCountingEntropy : SecureEntropy {
    private var invocation = 0

    override fun bytes(count: Int): ByteArray {
        invocation += 1
        return ByteArray(count) { invocation.toByte() }
    }
}

private fun credentials() = PlatformCredentials(
    accessToken = "access.${"a".repeat(43)}",
    refreshToken = "refresh-${"r".repeat(43)}",
    accessExpiresAt = Instant.ofEpochMilli(1_786_032_600_000L),
    refreshExpiresAt = Instant.ofEpochMilli(1_788_624_600_000L),
)

private fun billingCredentialsJson(prefix: String): String =
    """{"accessToken":"$prefix.${"a".repeat(43)}","refreshToken":"$prefix-${"r".repeat(43)}","accessExpiresAtEpochMs":1786032600000,"refreshExpiresAtEpochMs":1788624600000}"""

private fun productsJson(
    includeRemoveAds: Boolean = true,
    removeAdsAmountMinor: Long = 9_900,
): String {
    val offers = buildList {
        if (includeRemoveAds) {
            add(
                """{"id":"remove_ads","gameId":"inplacex","slug":"remove-ads","displayName":"Без рекламы","description":"Без рекламы","productKind":"addon","version":2,"price":{"currency":"RUB","amountMinor":$removeAdsAmountMinor},"grants":[{"entitlementKey":"ads.disabled","type":"durable","quantity":1}]}""",
            )
        }
        add(
            """{"id":"pro_subscription","gameId":"inplacex","slug":"pro","displayName":"Pro","description":"Pro","productKind":"addon","version":1,"price":{"currency":"RUB","amountMinor":19900},"grants":[{"entitlementKey":"pro.active","type":"timed","quantity":1,"durationSeconds":2592000}]}""",
        )
        add(
            """{"id":"pro_plus_subscription","gameId":"inplacex","slug":"pro-plus","displayName":"Pro+","description":"Pro+","productKind":"addon","version":1,"price":{"currency":"RUB","amountMinor":29900},"grants":[{"entitlementKey":"pro-plus.active","type":"timed","quantity":1,"durationSeconds":2592000}]}""",
        )
    }
    return """{"schemaVersion":1,"products":[${offers.joinToString(",")}]}"""
}

private fun orderJson(
    orderId: String,
    status: String,
    productId: String,
    amountMinor: Long = 9_900,
): String =
    """{"id":"$orderId","gameId":"inplacex","gamePlayerId":"00000000-0000-4000-8000-000000000952","productId":"$productId","currency":"RUB","amountMinor":$amountMinor,"status":"$status","createdAt":"2026-08-07T10:00:00Z","updatedAt":"2026-08-07T10:00:01Z"}"""

private fun checkoutJson(checkoutId: String, orderId: String): String =
    """{"schemaVersion":1,"checkout":{"id":"$checkoutId","orderId":"$orderId","provider":"rf_checkout","status":"ready","expiresAt":"2026-08-07T10:15:00Z","createdAt":"2026-08-07T10:00:00Z","updatedAt":"2026-08-07T10:00:01Z"},"paymentUrl":"https://payments.example/checkout/$checkoutId"}"""

private fun entitlementsJson(vararg entitlements: String): String =
    """{"schemaVersion":1,"entitlements":[${entitlements.joinToString(",") }]}"""

private fun ordersJson(vararg orders: String): String =
    """{"schemaVersion":1,"orders":[${orders.joinToString(",") }]}"""

private fun response(body: String, status: Int = 200) =
    PlatformHttpResponse(status, body, Instant.ofEpochMilli(1_786_000_000_000L))

private fun <T> runSuspend(block: suspend () -> T): T {
    val latch = CountDownLatch(1)
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            outcome = result
            latch.countDown()
        }
    })
    check(latch.await(10, TimeUnit.SECONDS))
    return requireNotNull(outcome).getOrThrow()
}
