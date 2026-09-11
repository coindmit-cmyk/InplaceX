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
import com.mirkori.platform.sdk.PlatformEntitlementKind
import com.mirkori.platform.sdk.PlatformEntitlementType
import com.mirkori.platform.sdk.PlatformGameEntitlementDelivery
import com.mirkori.platform.sdk.PlatformGameDeliveryAction
import com.mirkori.platform.sdk.PlatformHttpRequest
import com.mirkori.platform.sdk.PlatformHttpResponse
import com.mirkori.platform.sdk.PlatformIdempotencyKey
import com.mirkori.platform.sdk.PlatformProductGrant
import com.mirkori.platform.sdk.PlatformProductKind
import com.mirkori.platform.sdk.PlatformProductOffer
import com.mirkori.platform.sdk.PlatformProductPrice
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
    fun globalGooglePlayGuestPurchaseIsVerifiedBeforeEntitlementUnlock() {
        val orderId = "00000000-0000-4000-8000-000000000801"
        val paymentId = "00000000-0000-4000-8000-000000000802"
        val clientToken = paymentId
        val purchaseToken = "google-play-purchase-token-801"
        val pendingOrder = globalOrderJson(orderId, "pending")
        val paidOrder = globalOrderJson(orderId, "paid")
        val payment = """{"id":"$paymentId","orderId":"$orderId","status":"requires_action","paymentMethodId":"google_play","channel":"android","currency":"USD","amountMinor":9900,"expiresAt":"2026-09-12T10:00:00Z","createdAt":"2026-09-11T10:00:00Z","updatedAt":"2026-09-11T10:00:01Z","nextAction":{"type":"embedded_sdk","sdkAdapter":"google_play_billing","clientToken":"$clientToken"}}"""
        val succeededPayment = """{"id":"$paymentId","orderId":"$orderId","status":"succeeded","paymentMethodId":"google_play","channel":"android","currency":"USD","amountMinor":9900,"createdAt":"2026-09-11T10:00:00Z","updatedAt":"2026-09-11T10:00:05Z"}"""
        val entitlement = """{"key":"ads.disabled","type":"durable","quantity":1}"""
        val transport = ScriptedTransport(
            response(globalProductsJson()),
            response(ordersJson()),
            response(pendingOrder, status = 201),
            response(
                """{"schemaVersion":1,"orderId":"$orderId","currency":"USD","amountMinor":9900,"countryCode":"US","distributionId":"global-google","distributionPaymentChannel":"google_play","distributionPackageName":"com.mirkori.inplacex","methods":[{"id":"google_play","category":"store","displayName":"Google Play","nextActionTypes":["embedded_sdk"]}]}""",
            ),
            response("""{"schemaVersion":1,"payment":$payment}""", status = 201),
            response(
                """{"schemaVersion":1,"payment":$succeededPayment,"order":$paidOrder,"entitlements":[$entitlement],"providerFinalized":true}""",
            ),
            response(globalProductsJson()),
            response(paidOrder),
            response(entitlementsJson(entitlement)),
        )
        val gateway = RecordingGooglePlayGateway(
            GooglePlayPurchase(GooglePlayPurchaseState.NONE),
            GooglePlayPurchase(
                state = GooglePlayPurchaseState.PURCHASED,
                productId = "remove_ads",
                purchaseToken = purchaseToken,
                obfuscatedProfileId = clientToken,
            ),
        )
        val service = service(
            transport = transport,
            store = linkedStore(authMode = PlatformAuthMode.GUEST),
            distributionId = "global-google",
            currency = "USD",
            paymentFlow = GooglePlayMirkoriPaymentFlow(
                gateway = gateway,
                expectedDistributionId = "global-google",
                expectedPackageName = "com.mirkori.inplacex",
            ),
        )

        val result = runSuspend { service.purchase(BillingProductId.REMOVE_ADS) }

        assertTrue(result is BillingPurchaseResult.StateUpdated)
        assertEquals(BillingNotice.PAYMENT_CONFIRMED, result.state.notice)
        assertTrue(result.state.entitlements.adFreePurchased)
        assertEquals(listOf("query", "launch"), gateway.operations)
        assertTrue(transport.requests.any { it.url.endsWith("/payments/$paymentId/google-play-purchase") })
        assertFalse(transport.requests.toString().contains(purchaseToken))
    }

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

    @Test
    fun refreshAppliesPendingGameDeliveryBeforeAcknowledgingWithPersistedKey() {
        val deliveryId = "00000000-0000-4000-8000-000000000971"
        val delivery = """{"id":"$deliveryId","entitlementEventId":"00000000-0000-4000-8000-000000000972","entitlementId":"00000000-0000-4000-8000-000000000973","sequenceNumber":1,"action":"grant","gameId":"inplacex","productId":"inplacex.coins-100","orderId":"00000000-0000-4000-8000-000000000974","entitlementKey":"coins","entitlementKind":"consumable_balance","quantityDelta":100,"validFrom":"2026-08-07T10:01:00Z","correctionQuantity":0,"payloadSha256":"${"a".repeat(64)}","createdAt":"2026-08-07T10:01:00Z"}"""
        val transport = ScriptedTransport(
            response(productsJson()),
            response(ordersJson()),
            response(entitlementsJson()),
            response("""{"schemaVersion":1,"deliveries":[$delivery]}"""),
            response(
                """{"schemaVersion":1,"acknowledgement":{"deliveryId":"$deliveryId","acknowledgedAt":"2026-08-07T10:01:30Z"}}""",
            ),
        )
        val applier = RecordingDeliveryApplier()

        val refreshed = runSuspend {
            service(transport, linkedStore(), deliveryApplier = applier).refresh()
        }

        assertEquals(BillingAvailability.READY, refreshed.availability)
        assertEquals(listOf("prepare:$deliveryId", "acknowledged:$deliveryId"), applier.operations)
        assertEquals("coins", applier.delivery?.entitlementKey)
        assertTrue(transport.requests[3].url.endsWith("/api/v1/commerce/game-deliveries?limit=50"))
        assertTrue(transport.requests[4].url.endsWith("/api/v1/commerce/game-deliveries/$deliveryId/ack"))
        assertEquals("persisted-delivery-key", transport.requests[4].headers["Idempotency-Key"])
        assertEquals("{\"applied\":true}", transport.requests[4].body)
    }

    @Test
    fun deliveryApplicationRejectsUnknownProductEvenWhenEntitlementIsKnown() {
        val delivery = gameDelivery(productId = "inplacex.coins-unknown")
        val knownOffer = coinOffer(productId = "inplacex.coins-100")

        assertNull(delivery.validatedApplication(knownOffer))
        assertEquals(
            com.mirkori.inplacex.data.local.MirkoriDeliveryApplication.COINS,
            gameDelivery(productId = knownOffer.id).validatedApplication(knownOffer),
        )
    }

    private fun service(
        transport: PlatformTransport,
        store: BillingMemoryStore,
        nowMs: Long = NowMs,
        bootMarker: Long = TestBootMarker,
        distributionId: String? = null,
        currency: String = "RUB",
        paymentFlow: MirkoriPaymentFlow? = null,
        deliveryApplier: MirkoriGameDeliveryApplier? = null,
    ): MirkoriBillingService {
        val sdk = MirkoriGameSdk(
            MirkoriGameSdkConfig(
                platformBaseUrl = "https://games.dmit.life",
                gameId = "inplacex",
                redirectUri = MirkoriPlatformRuntime.RedirectUri,
                distributionId = distributionId,
            ),
            transport,
            BillingCountingEntropy(),
        )
        val runtime = MirkoriPlatformRuntime(
            sdk = sdk,
            store = store,
            clockMs = { nowMs },
            monotonicClockMs = { nowMs },
            bootMarker = { bootMarker },
        )
        val config = BillingProviderConfig("remove_ads", "pro_subscription", "pro_plus_subscription")
        return if (paymentFlow == null) {
            MirkoriBillingService(
                runtime = runtime,
                config = config,
                currency = currency,
                deliveryApplier = deliveryApplier,
            )
        } else {
            MirkoriBillingService(
                runtime = runtime,
                config = config,
                currency = currency,
                paymentFlow = paymentFlow,
                deliveryApplier = deliveryApplier,
            )
        }
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

private class RecordingDeliveryApplier : MirkoriGameDeliveryApplier {
    val operations = mutableListOf<String>()
    var delivery: PlatformGameEntitlementDelivery? = null

    override fun prepare(
        accountId: String,
        gamePlayerId: String,
        delivery: PlatformGameEntitlementDelivery,
        productOffer: PlatformProductOffer?,
        newIdempotencyKey: PlatformIdempotencyKey,
    ): PreparedMirkoriDeliveryAcknowledgement {
        assertEquals(delivery.productId, productOffer?.id)
        operations += "prepare:${delivery.id}"
        this.delivery = delivery
        return PreparedMirkoriDeliveryAcknowledgement(
            PlatformIdempotencyKey("persisted-delivery-key"),
        )
    }

    override fun markAcknowledged(
        deliveryId: String,
        idempotencyKey: PlatformIdempotencyKey,
        acknowledgedAtMs: Long,
    ) {
        assertEquals("persisted-delivery-key", idempotencyKey.value)
        operations += "acknowledged:$deliveryId"
    }
}

private class RecordingGooglePlayGateway(
    private val queried: GooglePlayPurchase,
    private val launched: GooglePlayPurchase,
) : GooglePlayBillingGateway {
    val operations = mutableListOf<String>()

    override suspend fun query(productId: String, obfuscatedProfileId: String): GooglePlayPurchase {
        operations += "query"
        return queried
    }

    override suspend fun launch(productId: String, obfuscatedProfileId: String): GooglePlayPurchase {
        operations += "launch"
        return launched
    }

    override fun close() = Unit
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
        add(
            """{"id":"inplacex.coins-100","gameId":"inplacex","slug":"coins-100","displayName":"100 монет","description":"Игровая валюта","productKind":"currency","version":1,"price":{"currency":"RUB","amountMinor":9900},"grants":[{"entitlementKey":"coins","type":"consumable","quantity":100}]}""",
        )
    }
    return """{"schemaVersion":1,"products":[${offers.joinToString(",")}]}"""
}

private fun gameDelivery(productId: String): PlatformGameEntitlementDelivery = PlatformGameEntitlementDelivery(
    id = "00000000-0000-4000-8000-000000000971",
    entitlementEventId = "00000000-0000-4000-8000-000000000972",
    entitlementId = "00000000-0000-4000-8000-000000000973",
    sequenceNumber = 1,
    action = PlatformGameDeliveryAction.GRANT,
    gameId = "inplacex",
    productId = productId,
    orderId = "00000000-0000-4000-8000-000000000974",
    entitlementKey = "coins",
    entitlementKind = PlatformEntitlementKind.CONSUMABLE_BALANCE,
    quantityDelta = 100,
    validFrom = Instant.parse("2026-08-07T10:01:00Z"),
    expiresAt = null,
    correctionQuantity = 0,
    payloadSha256 = "a".repeat(64),
    createdAt = Instant.parse("2026-08-07T10:01:00Z"),
)

private fun coinOffer(productId: String): PlatformProductOffer = PlatformProductOffer(
    id = productId,
    gameId = "inplacex",
    slug = "coins-100",
    displayName = "100 монет",
    description = "Игровая валюта",
    kind = PlatformProductKind.CURRENCY,
    version = 1,
    price = PlatformProductPrice("RUB", 9_900),
    grants = listOf(
        PlatformProductGrant(
            entitlementKey = "coins",
            type = PlatformEntitlementType.CONSUMABLE,
            quantity = 100,
            durationSeconds = null,
        ),
    ),
)

private fun globalProductsJson(): String =
    productsJson().replace("\"currency\":\"RUB\"", "\"currency\":\"USD\"")

private fun globalOrderJson(orderId: String, status: String): String =
    """{"id":"$orderId","gameId":"inplacex","gamePlayerId":"00000000-0000-4000-8000-000000000952","productId":"remove_ads","currency":"USD","amountMinor":9900,"tenderType":"money","distributionId":"global-google","distributionPaymentChannel":"google_play","distributionPackageName":"com.mirkori.inplacex","status":"$status","createdAt":"2026-09-11T10:00:00Z","updatedAt":"2026-09-11T10:00:05Z"}"""

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
