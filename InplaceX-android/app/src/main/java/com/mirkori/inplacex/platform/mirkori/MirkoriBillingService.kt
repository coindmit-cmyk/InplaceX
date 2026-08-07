package com.mirkori.inplacex.platform.mirkori

import com.mirkori.inplacex.platform.config.BillingProviderConfig
import com.mirkori.inplacex.platform.logging.AppLog
import com.mirkori.inplacex.platform.services.BillingAvailability
import com.mirkori.inplacex.platform.services.BillingNotice
import com.mirkori.inplacex.platform.services.BillingProduct
import com.mirkori.inplacex.platform.services.BillingProductId
import com.mirkori.inplacex.platform.services.BillingPurchaseResult
import com.mirkori.inplacex.platform.services.BillingService
import com.mirkori.inplacex.platform.services.BillingState
import com.mirkori.inplacex.platform.services.MonetizationEntitlements
import com.mirkori.platform.sdk.GameIdentitySession
import com.mirkori.platform.sdk.PlatformApiException
import com.mirkori.platform.sdk.PlatformAuthMode
import com.mirkori.platform.sdk.PlatformEntitlement
import com.mirkori.platform.sdk.PlatformEntitlementType
import com.mirkori.platform.sdk.PlatformOrder
import com.mirkori.platform.sdk.PlatformOrderStatus
import com.mirkori.platform.sdk.PlatformProductKind
import com.mirkori.platform.sdk.PlatformProductOffer
import kotlinx.coroutines.CancellationException

/**
 * Production billing boundary for the browser checkout owned by Mirkori Games Platform.
 * A browser return is never treated as payment proof: only a paid order plus a matching
 * server entitlement can unlock a feature.
 */
class MirkoriBillingService(
    private val runtime: MirkoriPlatformRuntime,
    private val config: BillingProviderConfig,
    private val currency: String = DefaultCurrency,
) : BillingService {
    @Volatile
    private var lastState = runtime.cachedCommerceState(
        config = config,
        previousProducts = emptyMap(),
        notice = BillingNotice.NONE,
    )

    init {
        require(currency.matches(Regex("[A-Z]{3}")))
    }

    override fun cachedState(): BillingState = runtime.cachedCommerceState(
        config = config,
        previousProducts = lastState.products,
        notice = lastState.notice,
    ).also { lastState = it }

    override suspend fun refresh(): BillingState = runtime.refreshCommerce(
        config = config,
        currency = currency,
        previousProducts = lastState.products,
    ).also { lastState = it }

    override suspend fun purchase(productId: BillingProductId): BillingPurchaseResult =
        runtime.purchase(
            config = config,
            currency = currency,
            productId = productId,
            previousProducts = lastState.products,
        ).also { lastState = it.state }

    private companion object {
        const val DefaultCurrency = "RUB"
    }
}

private fun MirkoriPlatformRuntime.cachedCommerceState(
    config: BillingProviderConfig,
    previousProducts: Map<BillingProductId, BillingProduct>,
    notice: BillingNotice,
    availability: BillingAvailability = if (config.isConfigured) {
        BillingAvailability.INITIALIZING
    } else {
        BillingAvailability.UNAVAILABLE
    },
): BillingState {
    val nowMs = nowMs()
    val state = currentPersistedState()
    val session = state?.session
    val confirmed = state?.confirmedEntitlements?.takeIf { it.belongsTo(session) }
    val pending = state?.pendingPurchase?.takeIf { it.belongsTo(session) }
    return BillingState(
        availability = availability,
        products = previousProducts,
        entitlements = confirmed.toEntitlements(nowMs),
        pendingProduct = pending?.productId?.let(config::billingProductIdFor),
        pendingOrderId = pending?.orderId,
        notice = if (config.isConfigured) notice else BillingNotice.CONFIGURATION_REQUIRED,
        nextEntitlementExpiryAtMs = confirmed?.nextExpiryAfter(nowMs),
    )
}

private suspend fun MirkoriPlatformRuntime.refreshCommerce(
    config: BillingProviderConfig,
    currency: String,
    previousProducts: Map<BillingProductId, BillingProduct>,
): BillingState = withOperationLock {
    if (!config.isConfigured) {
        return@withOperationLock cachedCommerceState(
            config,
            previousProducts,
            BillingNotice.CONFIGURATION_REQUIRED,
            BillingAvailability.UNAVAILABLE,
        )
    }
    try {
        synchronizeCommerceLocked(config, currency, BillingNotice.NONE)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: PlatformApiException) {
        if (error.errorCode == "product_already_owned") {
            clearPendingPurchase()
            runCatching {
                synchronizeCommerceLocked(config, currency, BillingNotice.PRODUCT_ALREADY_ACTIVE)
            }.getOrElse { retryError ->
                commerceFailureState(config, previousProducts, retryError, "refresh")
            }
        } else {
            if (error.errorCode in TerminalPendingErrorCodes) clearPendingPurchase()
            commerceFailureState(config, previousProducts, error, "refresh")
        }
    } catch (error: Exception) {
        commerceFailureState(config, previousProducts, error, "refresh")
    }
}

private suspend fun MirkoriPlatformRuntime.purchase(
    config: BillingProviderConfig,
    currency: String,
    productId: BillingProductId,
    previousProducts: Map<BillingProductId, BillingProduct>,
): BillingPurchaseResult = withOperationLock {
    if (!config.isConfigured) {
        return@withOperationLock BillingPurchaseResult.StateUpdated(
            cachedCommerceState(
                config,
                previousProducts,
                BillingNotice.CONFIGURATION_REQUIRED,
                BillingAvailability.UNAVAILABLE,
            ),
        )
    }
    try {
        var session = ensureFreshSession()
        val offers = sdk.products(currency)
        val catalog = projectCatalog(offers, config)
        if (session.authMode == PlatformAuthMode.GUEST) {
            return@withOperationLock BillingPurchaseResult.StateUpdated(
                cachedCommerceState(
                    config = config,
                    previousProducts = catalog.products,
                    notice = BillingNotice.LINKED_ACCOUNT_REQUIRED,
                    availability = BillingAvailability.READY,
                ),
            )
        }

        val platformProductId = config.platformProductId(productId)
        val selectedOffer = catalog.offers[productId]
        if (selectedOffer == null) {
            return@withOperationLock BillingPurchaseResult.StateUpdated(
                cachedCommerceState(
                    config = config,
                    previousProducts = catalog.products,
                    notice = BillingNotice.RETRY_REQUIRED,
                    availability = BillingAvailability.UNAVAILABLE,
                ),
            )
        }

        var state = requireNotNull(currentPersistedState())
        var pending = state.pendingPurchase?.takeIf { it.belongsTo(session) }
        if (pending != null && (pending.productId != platformProductId || pending.currency != currency)) {
            return@withOperationLock BillingPurchaseResult.StateUpdated(
                cachedCommerceState(
                    config = config,
                    previousProducts = catalog.products,
                    notice = BillingNotice.BUSY,
                    availability = BillingAvailability.READY,
                ),
            )
        }
        if (pending == null) {
            pending = PendingMirkoriPurchase(
                accountId = session.accountId,
                gamePlayerId = session.gamePlayerId,
                productId = platformProductId,
                currency = currency,
                orderIdempotencyKey = sdk.newIdempotencyKey(),
                checkoutIdempotencyKey = sdk.newIdempotencyKey(),
            )
            persist(state.copy(pendingPurchase = pending))
            state = requireNotNull(currentPersistedState())
        }

        val restored = ensurePendingOrder(
            initialSession = session,
            initialPending = pending,
            expectedAmountMinor = selectedOffer.price.amountMinor,
        )
        session = restored.session
        pending = restored.pending
        val order = restored.order
        when (order.status) {
            PlatformOrderStatus.PAID -> BillingPurchaseResult.StateUpdated(
                synchronizeCommerceLocked(config, currency, BillingNotice.NONE),
            )

            PlatformOrderStatus.CANCELLED -> {
                persist(requireNotNull(currentPersistedState()).copy(pendingPurchase = null))
                BillingPurchaseResult.StateUpdated(
                    synchronizeCommerceLocked(config, currency, BillingNotice.PAYMENT_CANCELLED),
                )
            }

            PlatformOrderStatus.REFUNDED -> {
                persist(requireNotNull(currentPersistedState()).copy(pendingPurchase = null))
                BillingPurchaseResult.StateUpdated(
                    synchronizeCommerceLocked(config, currency, BillingNotice.PAYMENT_REFUNDED),
                )
            }

            PlatformOrderStatus.PENDING -> {
                val checkoutResult = authenticated(session) { token ->
                    sdk.createCheckout(
                        profileAccessToken = token,
                        orderId = order.id,
                        idempotencyKey = pending.checkoutIdempotencyKey,
                    )
                }
                require(checkoutResult.value.orderId == order.id)
                AppLog.info(
                    tag = LogTag,
                    message = "Mirkori checkout prepared",
                    attributes = mapOf("outcome" to "browser_ready"),
                )
                BillingPurchaseResult.OpenExternalCheckout(
                    checkoutUrl = checkoutResult.value.paymentUrl,
                    state = cachedCommerceState(
                        config = config,
                        previousProducts = catalog.products,
                        notice = BillingNotice.CHECKOUT_OPENED,
                        availability = BillingAvailability.READY,
                    ),
                )
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: PlatformApiException) {
        val state = when (error.errorCode) {
            "product_already_owned" -> {
                clearPendingPurchase()
                runCatching {
                    synchronizeCommerceLocked(config, currency, BillingNotice.PRODUCT_ALREADY_ACTIVE)
                }.getOrElse { retryError ->
                    commerceFailureState(config, previousProducts, retryError, "purchase")
                }
            }

            "checkout_expired" -> {
                rotateCheckoutAttempt()
                cachedCommerceState(
                    config = config,
                    previousProducts = previousProducts,
                    notice = BillingNotice.CHECKOUT_EXPIRED,
                    availability = BillingAvailability.READY,
                )
            }

            else -> {
                if (error.errorCode in TerminalPendingErrorCodes) clearPendingPurchase()
                commerceFailureState(config, previousProducts, error, "purchase")
            }
        }
        BillingPurchaseResult.StateUpdated(state)
    } catch (error: Exception) {
        BillingPurchaseResult.StateUpdated(
            commerceFailureState(config, previousProducts, error, "purchase"),
        )
    }
}

private suspend fun MirkoriPlatformRuntime.synchronizeCommerceLocked(
    config: BillingProviderConfig,
    currency: String,
    initialNotice: BillingNotice,
): BillingState {
    var session = ensureFreshSession()
    val offers = sdk.products(currency)
    val catalog = projectCatalog(offers, config)
    var pending = currentPersistedState()?.pendingPurchase?.takeIf { it.belongsTo(session) }
    var pendingOrder: PlatformOrder? = null
    var notice = initialNotice

    if (pending != null) {
        val pendingBillingId = config.billingProductIdFor(pending.productId)
            ?: throw CommerceContractException()
        val pendingOffer = catalog.offers[pendingBillingId]
            ?: throw CommerceContractException()
        val restored = ensurePendingOrder(
            initialSession = session,
            initialPending = pending,
            expectedAmountMinor = pendingOffer.price.amountMinor,
        )
        session = restored.session
        pending = restored.pending
        pendingOrder = restored.order
        notice = when (pendingOrder.status) {
            PlatformOrderStatus.PENDING -> BillingNotice.AWAITING_PAYMENT
            PlatformOrderStatus.PAID -> BillingNotice.AWAITING_ENTITLEMENT
            PlatformOrderStatus.CANCELLED -> BillingNotice.PAYMENT_CANCELLED
            PlatformOrderStatus.REFUNDED -> BillingNotice.PAYMENT_REFUNDED
        }
        if (pendingOrder.status == PlatformOrderStatus.CANCELLED || pendingOrder.status == PlatformOrderStatus.REFUNDED) {
            pending = null
        }
    }

    val entitlementResult = authenticated(session) { token -> sdk.entitlements(token) }
    session = entitlementResult.session
    val confirmed = deriveConfirmedEntitlements(
        session = session,
        catalog = catalog,
        entitlements = entitlementResult.value,
        nowMs = nowMs(),
    )
    if (pendingOrder?.status == PlatformOrderStatus.PAID && pending != null) {
        val purchasedProduct = config.billingProductIdFor(pending.productId)
        if (purchasedProduct != null && confirmed.isActive(purchasedProduct, nowMs())) {
            pending = null
            notice = BillingNotice.PAYMENT_CONFIRMED
        } else {
            notice = BillingNotice.AWAITING_ENTITLEMENT
        }
    }

    val state = requireNotNull(currentPersistedState())
    require(state.session?.accountId == session.accountId && state.session.gamePlayerId == session.gamePlayerId)
    persist(
        state.copy(
            pendingPurchase = pending,
            confirmedEntitlements = confirmed,
        ),
    )
    AppLog.info(
        tag = LogTag,
        message = "Mirkori commerce synchronized",
        attributes = mapOf(
            "outcome" to notice.name.lowercase(),
            "pending" to (pending != null).toString(),
        ),
    )
    return BillingState(
        availability = BillingAvailability.READY,
        products = catalog.products,
        entitlements = confirmed.toEntitlements(nowMs()),
        pendingProduct = pending?.productId?.let(config::billingProductIdFor),
        pendingOrderId = pending?.orderId,
        notice = notice,
        nextEntitlementExpiryAtMs = confirmed.nextExpiryAfter(nowMs()),
    )
}

private suspend fun MirkoriPlatformRuntime.ensurePendingOrder(
    initialSession: GameIdentitySession,
    initialPending: PendingMirkoriPurchase,
    expectedAmountMinor: Long,
): PendingOrderResult {
    var session = initialSession
    var pending = initialPending
    val orderResult = if (pending.orderId == null) {
        authenticated(session) { token ->
            sdk.createOrder(
                profileAccessToken = token,
                productId = pending.productId,
                currency = pending.currency,
                idempotencyKey = pending.orderIdempotencyKey,
            )
        }.also { result ->
            session = result.session
            require(result.value.gamePlayerId == session.gamePlayerId)
            pending = pending.copy(orderId = result.value.id)
            val state = requireNotNull(currentPersistedState())
            require(pending.belongsTo(state.session))
            persist(state.copy(pendingPurchase = pending))
        }
    } else {
        authenticated(session) { token -> sdk.order(token, requireNotNull(pending.orderId)) }.also { result ->
            session = result.session
        }
    }
    require(orderResult.value.gamePlayerId == session.gamePlayerId)
    require(orderResult.value.productId == pending.productId)
    require(orderResult.value.currency == pending.currency)
    require(orderResult.value.amountMinor == expectedAmountMinor)
    return PendingOrderResult(session, pending, orderResult.value)
}

private suspend fun <T> MirkoriPlatformRuntime.authenticated(
    initialSession: GameIdentitySession,
    request: suspend (String) -> T,
): AuthenticatedResult<T> {
    var session = initialSession
    return try {
        AuthenticatedResult(session, request(session.credentials.accessToken))
    } catch (error: PlatformApiException) {
        if (error.status != 401) throw error
        val originalAccountId = session.accountId
        val originalPlayerId = session.gamePlayerId
        session = ensureFreshSession(forceRefresh = true)
        if (session.accountId != originalAccountId || session.gamePlayerId != originalPlayerId) {
            throw CommerceProfileChangedException()
        }
        AuthenticatedResult(session, request(session.credentials.accessToken))
    }
}

private fun MirkoriPlatformRuntime.commerceFailureState(
    config: BillingProviderConfig,
    previousProducts: Map<BillingProductId, BillingProduct>,
    error: Throwable,
    operation: String,
): BillingState {
    val (availability, notice) = when {
        error is MirkoriTransportException && error.failure == MirkoriTransportFailure.OFFLINE ->
            BillingAvailability.OFFLINE to BillingNotice.OFFLINE
        error is PlatformApiException && error.errorCode == "linked_account_required" ->
            BillingAvailability.READY to BillingNotice.LINKED_ACCOUNT_REQUIRED
        error is PlatformApiException && error.status == 503 ->
            BillingAvailability.UNAVAILABLE to BillingNotice.PROVIDER_UNAVAILABLE
        else -> BillingAvailability.UNAVAILABLE to BillingNotice.RETRY_REQUIRED
    }
    AppLog.warn(
        tag = LogTag,
        message = "Mirkori commerce operation unavailable",
        attributes = buildMap {
            put("operation", operation)
            put("outcome", notice.name.lowercase())
            put("errorClass", error.javaClass.name)
            if (error is PlatformApiException) put("errorCode", error.errorCode)
        },
    )
    return cachedCommerceState(config, previousProducts, notice, availability)
}

private fun MirkoriPlatformRuntime.clearPendingPurchase() {
    currentPersistedState()?.let { state ->
        if (state.pendingPurchase != null) persist(state.copy(pendingPurchase = null))
    }
}

private fun MirkoriPlatformRuntime.rotateCheckoutAttempt() {
    currentPersistedState()?.let { state ->
        state.pendingPurchase?.let { pending ->
            persist(
                state.copy(
                    pendingPurchase = pending.copy(checkoutIdempotencyKey = sdk.newIdempotencyKey()),
                ),
            )
        }
    }
}

private fun projectCatalog(
    offers: List<PlatformProductOffer>,
    config: BillingProviderConfig,
): CatalogProjection {
    val duplicateIds = offers.groupingBy(PlatformProductOffer::id).eachCount().filterValues { it != 1 }.keys
    val projectedOffers = BillingProductId.entries.mapNotNull { billingId ->
        val expectedId = config.platformProductId(billingId)
        val offer = offers.singleOrNull { it.id == expectedId }
            ?.takeIf { it.id !in duplicateIds && it.isSafePremiumOffer() }
            ?: return@mapNotNull null
        billingId to offer
    }.toMap()
    return CatalogProjection(
        products = projectedOffers.mapValues { (_, offer) ->
            BillingProduct(
                platformProductId = offer.id,
                displayName = offer.displayName,
                description = offer.description,
                currency = offer.price.currency,
                amountMinor = offer.price.amountMinor,
            )
        },
        offers = projectedOffers,
    )
}

private fun PlatformProductOffer.isSafePremiumOffer(): Boolean =
    kind != PlatformProductKind.CURRENCY && grants.isNotEmpty() &&
        grants.all { it.type == PlatformEntitlementType.DURABLE || it.type == PlatformEntitlementType.TIMED }

private fun deriveConfirmedEntitlements(
    session: GameIdentitySession,
    catalog: CatalogProjection,
    entitlements: List<PlatformEntitlement>,
    nowMs: Long,
): ConfirmedMirkoriEntitlements = ConfirmedMirkoriEntitlements(
    accountId = session.accountId,
    gamePlayerId = session.gamePlayerId,
    confirmedAtEpochMs = nowMs,
    removeAds = featureGrant(catalog.offers[BillingProductId.REMOVE_ADS], entitlements, nowMs),
    pro = featureGrant(catalog.offers[BillingProductId.PRO_SUBSCRIPTION], entitlements, nowMs),
    proPlus = featureGrant(catalog.offers[BillingProductId.PRO_PLUS_SUBSCRIPTION], entitlements, nowMs),
)

private fun featureGrant(
    offer: PlatformProductOffer?,
    entitlements: List<PlatformEntitlement>,
    nowMs: Long,
): MirkoriFeatureGrant {
    if (offer == null) return MirkoriFeatureGrant(active = false)
    val matched = offer.grants.map { grant ->
        entitlements.firstOrNull { entitlement ->
            val validUntil = entitlement.validUntil
            entitlement.key == grant.entitlementKey &&
                entitlement.type == grant.type &&
                entitlement.quantity >= grant.quantity &&
                (validUntil == null || validUntil.toEpochMilli() > nowMs)
        } ?: return MirkoriFeatureGrant(active = false)
    }
    return MirkoriFeatureGrant(
        active = true,
        validUntilEpochMs = matched.mapNotNull { it.validUntil?.toEpochMilli() }.minOrNull(),
    )
}

private fun ConfirmedMirkoriEntitlements?.toEntitlements(nowMs: Long): MonetizationEntitlements =
    if (this == null) {
        MonetizationEntitlements.None
    } else {
        MonetizationEntitlements(
            adFreePurchased = removeAds.activeAt(nowMs),
            proSubscriptionActive = pro.activeAt(nowMs),
            proPlusSubscriptionActive = proPlus.activeAt(nowMs),
        )
    }

private fun ConfirmedMirkoriEntitlements.isActive(productId: BillingProductId, nowMs: Long): Boolean =
    when (productId) {
        BillingProductId.REMOVE_ADS -> removeAds.activeAt(nowMs)
        BillingProductId.PRO_SUBSCRIPTION -> pro.activeAt(nowMs)
        BillingProductId.PRO_PLUS_SUBSCRIPTION -> proPlus.activeAt(nowMs)
    }

private fun ConfirmedMirkoriEntitlements.belongsTo(session: GameIdentitySession?): Boolean =
    session != null && accountId == session.accountId && gamePlayerId == session.gamePlayerId

private fun PendingMirkoriPurchase.belongsTo(session: GameIdentitySession?): Boolean =
    session != null && accountId == session.accountId && gamePlayerId == session.gamePlayerId

private fun BillingProviderConfig.platformProductId(productId: BillingProductId): String = when (productId) {
    BillingProductId.REMOVE_ADS -> removeAdsProductId
    BillingProductId.PRO_SUBSCRIPTION -> proSubscriptionId
    BillingProductId.PRO_PLUS_SUBSCRIPTION -> proPlusSubscriptionId
}

private fun BillingProviderConfig.billingProductIdFor(platformProductId: String): BillingProductId? =
    BillingProductId.entries.firstOrNull { platformProductId(it) == platformProductId }

private data class CatalogProjection(
    val products: Map<BillingProductId, BillingProduct>,
    val offers: Map<BillingProductId, PlatformProductOffer>,
)

private data class AuthenticatedResult<T>(
    val session: GameIdentitySession,
    val value: T,
)

private data class PendingOrderResult(
    val session: GameIdentitySession,
    val pending: PendingMirkoriPurchase,
    val order: PlatformOrder,
)

private class CommerceProfileChangedException : IllegalStateException("Commerce profile changed")

private class CommerceContractException : IllegalStateException("Commerce contract rejected")

private const val LogTag = "MirkoriCommerce"

private val TerminalPendingErrorCodes = setOf(
    "idempotency_conflict",
    "order_not_found",
    "product_not_available",
    "profile_rejected",
)
