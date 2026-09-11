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
import com.mirkori.platform.sdk.PlatformDistributionPaymentChannel
import com.mirkori.platform.sdk.PlatformOrder
import com.mirkori.platform.sdk.PlatformOrderStatus
import com.mirkori.platform.sdk.PlatformPaymentChannel
import com.mirkori.platform.sdk.PlatformPaymentMethodCategory
import com.mirkori.platform.sdk.PlatformPaymentNextActionType
import com.mirkori.platform.sdk.PlatformPaymentStatus
import com.mirkori.platform.sdk.PlatformProductKind
import com.mirkori.platform.sdk.PlatformProductOffer
import kotlinx.coroutines.CancellationException

/**
 * Production billing boundary for the browser checkout owned by Mirkori Games Platform.
 * A browser return is never treated as payment proof: only a paid order plus a matching
 * server entitlement can unlock a feature.
 */
class MirkoriBillingService internal constructor(
    private val runtime: MirkoriPlatformRuntime,
    private val config: BillingProviderConfig,
    private val currency: String = DefaultCurrency,
    private val paymentFlow: MirkoriPaymentFlow = BrowserMirkoriPaymentFlow,
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
        paymentFlow = paymentFlow,
    ).also { lastState = it }

    override suspend fun purchase(productId: BillingProductId): BillingPurchaseResult =
        runtime.purchase(
            config = config,
            currency = currency,
            productId = productId,
            previousProducts = lastState.products,
            paymentFlow = paymentFlow,
        ).also { lastState = it.state }

    override fun close() = paymentFlow.close()

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
    val trustedNowMs = trustedNowMs()
    val state = currentPersistedState()
    val session = state?.session
    val confirmed = state?.confirmedEntitlements?.takeIf { it.belongsTo(session) }
    val pending = state?.pendingPurchase?.takeIf { it.belongsTo(session) }
    return BillingState(
        availability = availability,
        products = previousProducts,
        entitlements = confirmed.toEntitlements(trustedNowMs),
        pendingProduct = pending?.productId?.let(config::billingProductIdFor),
        pendingOrderId = pending?.orderId,
        notice = if (config.isConfigured) notice else BillingNotice.CONFIGURATION_REQUIRED,
        nextEntitlementExpiryDelayMs = confirmed?.nextExpiryDelayMs(trustedNowMs),
    )
}

private suspend fun MirkoriPlatformRuntime.refreshCommerce(
    config: BillingProviderConfig,
    currency: String,
    previousProducts: Map<BillingProductId, BillingProduct>,
    paymentFlow: MirkoriPaymentFlow,
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
        synchronizeCommerceLocked(config, currency, BillingNotice.NONE, paymentFlow)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: PlatformApiException) {
        when (error.errorCode) {
            "product_already_owned" -> {
                clearPendingPurchase()
                recoverCommerceAfterServerSignal(
                    config,
                    currency,
                    previousProducts,
                    BillingNotice.PRODUCT_ALREADY_ACTIVE,
                    "refresh",
                    paymentFlow,
                )
            }

            "order_pending" -> {
                clearPendingPurchase()
                recoverCommerceAfterServerSignal(
                    config,
                    currency,
                    previousProducts,
                    BillingNotice.AWAITING_PAYMENT,
                    "refresh",
                    paymentFlow,
                )
            }

            else -> {
                if (error.errorCode in TerminalPendingErrorCodes) clearPendingPurchase()
                commerceFailureState(config, previousProducts, error, "refresh")
            }
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
    paymentFlow: MirkoriPaymentFlow,
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
        if (session.authMode == PlatformAuthMode.GUEST && !paymentFlow.allowsGuest) {
            return@withOperationLock BillingPurchaseResult.StateUpdated(
                cachedCommerceState(
                    config = config,
                    previousProducts = catalog.products,
                    notice = BillingNotice.LINKED_ACCOUNT_REQUIRED,
                    availability = BillingAvailability.READY,
                ),
            )
        }

        val reconciliation = reconcilePendingOrderIfNeeded(session, catalog, config, currency)
        session = reconciliation.session
        var pending = reconciliation.pending
        val platformProductId = config.platformProductId(productId)
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
            pending = PendingMirkoriPurchase(
                accountId = session.accountId,
                gamePlayerId = session.gamePlayerId,
                productId = platformProductId,
                currency = currency,
                orderIdempotencyKey = sdk.newIdempotencyKey(),
                checkoutIdempotencyKey = sdk.newIdempotencyKey(),
                offerSnapshot = selectedOffer.toPendingSnapshot(),
            )
            val state = requireNotNull(currentPersistedState())
            persist(state.copy(pendingPurchase = pending))
        }

        val restored = ensurePendingOrder(
            initialSession = session,
            initialPending = pending,
            currentOffer = catalog.offers[productId],
        )
        session = restored.session
        pending = restored.pending
        val order = restored.order
        when (order.status) {
            PlatformOrderStatus.PAID -> BillingPurchaseResult.StateUpdated(
                synchronizeCommerceLocked(config, currency, BillingNotice.NONE, paymentFlow),
            )

            PlatformOrderStatus.CANCELLED -> {
                persist(requireNotNull(currentPersistedState()).copy(pendingPurchase = null))
                BillingPurchaseResult.StateUpdated(
                    synchronizeCommerceLocked(config, currency, BillingNotice.PAYMENT_CANCELLED, paymentFlow),
                )
            }

            PlatformOrderStatus.REFUNDED -> {
                persist(requireNotNull(currentPersistedState()).copy(pendingPurchase = null))
                BillingPurchaseResult.StateUpdated(
                    synchronizeCommerceLocked(config, currency, BillingNotice.PAYMENT_REFUNDED, paymentFlow),
                )
            }

            PlatformOrderStatus.PENDING -> {
                when (val result = paymentFlow.start(this, session, pending, order)) {
                    is MirkoriPaymentFlowResult.ExternalCheckout -> BillingPurchaseResult.OpenExternalCheckout(
                        checkoutUrl = result.url,
                        state = cachedCommerceState(
                            config = config,
                            previousProducts = catalog.products,
                            notice = BillingNotice.CHECKOUT_OPENED,
                            availability = BillingAvailability.READY,
                        ),
                    )
                    MirkoriPaymentFlowResult.Settled -> BillingPurchaseResult.StateUpdated(
                        synchronizeCommerceLocked(config, currency, BillingNotice.NONE, paymentFlow),
                    )
                    is MirkoriPaymentFlowResult.Notice -> BillingPurchaseResult.StateUpdated(
                        cachedCommerceState(
                            config = config,
                            previousProducts = catalog.products,
                            notice = result.notice,
                            availability = result.availability,
                        ),
                    )
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: PlatformApiException) {
        val state = when (error.errorCode) {
            "product_already_owned" -> {
                clearPendingPurchase()
                recoverCommerceAfterServerSignal(
                    config,
                    currency,
                    previousProducts,
                    BillingNotice.PRODUCT_ALREADY_ACTIVE,
                    "purchase",
                    paymentFlow,
                )
            }

            "order_pending" -> {
                clearPendingPurchase()
                recoverCommerceAfterServerSignal(
                    config,
                    currency,
                    previousProducts,
                    BillingNotice.AWAITING_PAYMENT,
                    "purchase",
                    paymentFlow,
                )
            }

            "checkout_expired", "checkout_reconciliation_required" -> {
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

private suspend fun MirkoriPlatformRuntime.recoverCommerceAfterServerSignal(
    config: BillingProviderConfig,
    currency: String,
    previousProducts: Map<BillingProductId, BillingProduct>,
    notice: BillingNotice,
    operation: String,
    paymentFlow: MirkoriPaymentFlow,
): BillingState = try {
    synchronizeCommerceLocked(config, currency, notice, paymentFlow)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (retryError: Exception) {
    commerceFailureState(config, previousProducts, retryError, operation)
}

private suspend fun MirkoriPlatformRuntime.synchronizeCommerceLocked(
    config: BillingProviderConfig,
    currency: String,
    initialNotice: BillingNotice,
    paymentFlow: MirkoriPaymentFlow,
): BillingState {
    val timeRevisionBeforeSync = serverTimeRevision()
    var session = ensureFreshSession()
    val offers = sdk.products(currency)
    val catalog = projectCatalog(offers, config)
    val reconciliation = reconcilePendingOrderIfNeeded(session, catalog, config, currency)
    session = reconciliation.session
    var pending = reconciliation.pending
    var pendingOrder: PlatformOrder? = null
    var notice = initialNotice
    var availability = BillingAvailability.READY

    if (pending != null) {
        val pendingBillingId = config.billingProductIdFor(pending.productId)
            ?: throw CommerceContractException()
        val restored = ensurePendingOrder(
            initialSession = session,
            initialPending = pending,
            currentOffer = catalog.offers[pendingBillingId],
        )
        session = restored.session
        pending = restored.pending
        pendingOrder = restored.order
        if (pendingOrder.status == PlatformOrderStatus.PENDING) {
            when (val recovery = paymentFlow.recover(this, session, pending, pendingOrder)) {
                MirkoriPaymentFlowResult.Settled -> {
                    val refreshed = ensurePendingOrder(session, pending, catalog.offers[pendingBillingId])
                    session = refreshed.session
                    pending = refreshed.pending
                    pendingOrder = refreshed.order
                }
                is MirkoriPaymentFlowResult.Notice -> {
                    notice = recovery.notice
                    availability = recovery.availability
                }
                is MirkoriPaymentFlowResult.ExternalCheckout -> throw CommerceContractException()
            }
        }
        notice = when (pendingOrder.status) {
            PlatformOrderStatus.PENDING -> notice.takeUnless { it == BillingNotice.NONE }
                ?: BillingNotice.AWAITING_PAYMENT
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
    val stateBeforeEntitlements = requireNotNull(currentPersistedState())
    val existingTimeAnchor = stateBeforeEntitlements.trustedTimeAnchor
    val freshTimeAnchor = captureTrustedTimeAfter(timeRevisionBeforeSync)
    val trustedTimeAnchor = when {
        freshTimeAnchor == null -> existingTimeAnchor
        existingTimeAnchor == null -> freshTimeAnchor
        freshTimeAnchor.serverEpochMs >= (
            trustedNowMs(existingTimeAnchor) ?: existingTimeAnchor.serverEpochMs
        ) -> freshTimeAnchor
        else -> existingTimeAnchor
    }
    val trustedNowMs = trustedNowMs(trustedTimeAnchor)
    val confirmed = deriveConfirmedEntitlements(
        session = session,
        entitlements = entitlementResult.value,
        trustedNowMs = trustedNowMs,
        previousConfirmedAtEpochMs = stateBeforeEntitlements.confirmedEntitlements?.confirmedAtEpochMs,
    )
    if (pendingOrder?.status == PlatformOrderStatus.PAID && pending != null) {
        val purchasedProduct = config.billingProductIdFor(pending.productId)
        if (purchasedProduct != null && confirmed.isActive(purchasedProduct, trustedNowMs)) {
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
            trustedTimeAnchor = trustedTimeAnchor,
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
        availability = availability,
        products = catalog.products,
        entitlements = confirmed.toEntitlements(trustedNowMs),
        pendingProduct = pending?.productId?.let(config::billingProductIdFor),
        pendingOrderId = pending?.orderId,
        notice = notice,
        nextEntitlementExpiryDelayMs = confirmed.nextExpiryDelayMs(trustedNowMs),
    )
}

private suspend fun MirkoriPlatformRuntime.reconcilePendingOrderIfNeeded(
    initialSession: GameIdentitySession,
    catalog: CatalogProjection,
    config: BillingProviderConfig,
    currency: String,
): PendingReconciliationResult {
    val localPending = currentPersistedState()?.pendingPurchase?.takeIf { it.belongsTo(initialSession) }
    if (localPending != null) return PendingReconciliationResult(initialSession, localPending)

    val ordersResult = authenticated(initialSession) { token -> sdk.pendingOrders(token) }
    val session = ordersResult.session
    if (ordersResult.value.any { it.gamePlayerId != session.gamePlayerId }) {
        throw CommerceContractException()
    }
    val pendingOrders = ordersResult.value
    if (pendingOrders.isEmpty()) return PendingReconciliationResult(session, null)
    if (pendingOrders.size != 1) throw CommercePendingAmbiguousException()

    val order = pendingOrders.single()
    val billingProductId = config.billingProductIdFor(order.productId)
        ?: throw CommercePendingAmbiguousException()
    if (order.currency != currency) throw CommercePendingAmbiguousException()
    val currentOffer = catalog.offers[billingProductId]
    val pending = PendingMirkoriPurchase(
        accountId = session.accountId,
        gamePlayerId = session.gamePlayerId,
        productId = order.productId,
        currency = order.currency,
        orderId = order.id,
        orderIdempotencyKey = sdk.newIdempotencyKey(),
        checkoutIdempotencyKey = sdk.newIdempotencyKey(),
        offerSnapshot = order.toPendingSnapshot(currentOffer),
    )
    val state = requireNotNull(currentPersistedState())
    require(pending.belongsTo(state.session))
    persist(state.copy(pendingPurchase = pending))
    return PendingReconciliationResult(session, pending)
}

private suspend fun MirkoriPlatformRuntime.ensurePendingOrder(
    initialSession: GameIdentitySession,
    initialPending: PendingMirkoriPurchase,
    currentOffer: PlatformProductOffer?,
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
        }.also { result -> session = result.session }
    } else {
        authenticated(session) { token -> sdk.order(token, requireNotNull(pending.orderId)) }.also { result ->
            session = result.session
        }
    }
    require(orderResult.value.gamePlayerId == session.gamePlayerId)
    require(orderResult.value.productId == pending.productId)
    require(orderResult.value.currency == pending.currency)
    val terminal = orderResult.value.status == PlatformOrderStatus.CANCELLED ||
        orderResult.value.status == PlatformOrderStatus.REFUNDED
    if (!terminal) {
        val snapshot = pending.offerSnapshot ?: orderResult.value.toPendingSnapshot(currentOffer)
        require(snapshot.currency == orderResult.value.currency)
        require(snapshot.amountMinor == orderResult.value.amountMinor)
        require(snapshot.entitlementSchemaVersion == EntitlementContractSchemaVersion)
        pending = pending.copy(offerSnapshot = snapshot)
    }
    if (pending.orderId != orderResult.value.id) pending = pending.copy(orderId = orderResult.value.id)
    if (pending != initialPending) {
        val state = requireNotNull(currentPersistedState())
        require(pending.belongsTo(state.session))
        persist(state.copy(pendingPurchase = pending))
    }
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
        error is CommercePaymentAttemptTerminalException ->
            BillingAvailability.READY to BillingNotice.RETRY_REQUIRED
        error is CommercePendingAmbiguousException ->
            BillingAvailability.READY to BillingNotice.BUSY
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

private fun projectCatalog(
    offers: List<PlatformProductOffer>,
    config: BillingProviderConfig,
): CatalogProjection {
    val duplicateIds = offers.groupingBy(PlatformProductOffer::id).eachCount().filterValues { it != 1 }.keys
    val projectedOffers = BillingProductId.entries.mapNotNull { billingId ->
        val expectedId = config.platformProductId(billingId)
        val offer = offers.singleOrNull { it.id == expectedId }
            ?.takeIf { it.id !in duplicateIds && it.isSafePremiumOffer(billingId.entitlementContract()) }
            ?: return@mapNotNull null
        billingId to offer
    }.toMap()
    return CatalogProjection(
        products = projectedOffers.mapValues { (billingId, offer) ->
            BillingProduct(
                platformProductId = offer.id,
                displayName = offer.displayName,
                description = offer.description,
                currency = offer.price.currency,
                amountMinor = offer.price.amountMinor,
                accessDurationSeconds = offer.durationSecondsFor(billingId.entitlementContract()),
            )
        },
        offers = projectedOffers,
    )
}

private fun PlatformProductOffer.isSafePremiumOffer(contract: StableEntitlementContract): Boolean =
    kind != PlatformProductKind.CURRENCY && grants.isNotEmpty() &&
        grants.all { it.type == PlatformEntitlementType.DURABLE || it.type == PlatformEntitlementType.TIMED } &&
        grants.singleOrNull {
            it.entitlementKey == contract.entitlementKey &&
                it.type == contract.type &&
                it.quantity >= contract.quantity
        } != null

private fun PlatformProductOffer.durationSecondsFor(contract: StableEntitlementContract): Long? =
    grants.single {
        it.entitlementKey == contract.entitlementKey && it.type == contract.type && it.quantity >= contract.quantity
    }.durationSeconds

private fun deriveConfirmedEntitlements(
    session: GameIdentitySession,
    entitlements: List<PlatformEntitlement>,
    trustedNowMs: Long?,
    previousConfirmedAtEpochMs: Long?,
): ConfirmedMirkoriEntitlements = ConfirmedMirkoriEntitlements(
    accountId = session.accountId,
    gamePlayerId = session.gamePlayerId,
    confirmedAtEpochMs = trustedNowMs ?: previousConfirmedAtEpochMs?.takeIf { it > 0 } ?: 1L,
    removeAds = featureGrant(BillingProductId.REMOVE_ADS.entitlementContract(), entitlements, trustedNowMs),
    pro = featureGrant(BillingProductId.PRO_SUBSCRIPTION.entitlementContract(), entitlements, trustedNowMs),
    proPlus = featureGrant(BillingProductId.PRO_PLUS_SUBSCRIPTION.entitlementContract(), entitlements, trustedNowMs),
)

private fun featureGrant(
    contract: StableEntitlementContract,
    entitlements: List<PlatformEntitlement>,
    trustedNowMs: Long?,
): MirkoriFeatureGrant {
    val matched = entitlements.filter { entitlement ->
        entitlement.key == contract.entitlementKey &&
            entitlement.type == contract.type &&
            entitlement.quantity >= contract.quantity
    }
    return when (contract.type) {
        PlatformEntitlementType.DURABLE -> MirkoriFeatureGrant(active = matched.isNotEmpty())
        PlatformEntitlementType.TIMED -> {
            val expiresAt = trustedNowMs?.let { nowMs ->
                matched.mapNotNull { it.validUntil?.toEpochMilli() }
                    .filter { it > nowMs }
                    .maxOrNull()
            }
            MirkoriFeatureGrant(active = expiresAt != null, validUntilEpochMs = expiresAt)
        }
        PlatformEntitlementType.CONSUMABLE -> MirkoriFeatureGrant(active = false)
    }
}

private fun ConfirmedMirkoriEntitlements?.toEntitlements(trustedNowMs: Long?): MonetizationEntitlements =
    if (this == null) {
        MonetizationEntitlements.None
    } else {
        val proPlusActive = proPlus.activeAt(trustedNowMs)
        MonetizationEntitlements(
            adFreePurchased = removeAds.activeAt(trustedNowMs),
            proSubscriptionActive = pro.activeAt(trustedNowMs) || proPlusActive,
            proPlusSubscriptionActive = proPlusActive,
        )
    }

private fun ConfirmedMirkoriEntitlements.isActive(productId: BillingProductId, trustedNowMs: Long?): Boolean =
    when (productId) {
        BillingProductId.REMOVE_ADS -> removeAds.activeAt(trustedNowMs)
        BillingProductId.PRO_SUBSCRIPTION -> pro.activeAt(trustedNowMs) || proPlus.activeAt(trustedNowMs)
        BillingProductId.PRO_PLUS_SUBSCRIPTION -> proPlus.activeAt(trustedNowMs)
    }

private fun BillingProductId.entitlementContract(): StableEntitlementContract = when (this) {
    BillingProductId.REMOVE_ADS -> StableEntitlementContract("ads.disabled", PlatformEntitlementType.DURABLE, 1)
    BillingProductId.PRO_SUBSCRIPTION -> StableEntitlementContract("pro.active", PlatformEntitlementType.TIMED, 1)
    BillingProductId.PRO_PLUS_SUBSCRIPTION -> StableEntitlementContract(
        "pro-plus.active",
        PlatformEntitlementType.TIMED,
        1,
    )
}

private fun PlatformProductOffer.toPendingSnapshot(): PendingMirkoriOfferSnapshot = PendingMirkoriOfferSnapshot(
    amountMinor = price.amountMinor,
    currency = price.currency,
    entitlementSchemaVersion = EntitlementContractSchemaVersion,
    productVersion = version,
)

private fun PlatformOrder.toPendingSnapshot(currentOffer: PlatformProductOffer?): PendingMirkoriOfferSnapshot =
    PendingMirkoriOfferSnapshot(
        amountMinor = amountMinor,
        currency = currency,
        entitlementSchemaVersion = EntitlementContractSchemaVersion,
        productVersion = currentOffer?.takeIf {
            it.id == productId && it.price.currency == currency && it.price.amountMinor == amountMinor
        }?.version,
    )

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

private data class PendingReconciliationResult(
    val session: GameIdentitySession,
    val pending: PendingMirkoriPurchase?,
)

private data class StableEntitlementContract(
    val entitlementKey: String,
    val type: PlatformEntitlementType,
    val quantity: Long,
)

private class CommerceProfileChangedException : IllegalStateException("Commerce profile changed")

private class CommerceContractException : IllegalStateException("Commerce contract rejected")

private class CommercePendingAmbiguousException : IllegalStateException("Pending commerce state is ambiguous")

private class CommercePaymentAttemptTerminalException : IllegalStateException("Payment attempt is terminal")

internal interface MirkoriPaymentFlow : AutoCloseable {
    val allowsGuest: Boolean

    suspend fun start(
        runtime: MirkoriPlatformRuntime,
        session: GameIdentitySession,
        pending: PendingMirkoriPurchase,
        order: PlatformOrder,
    ): MirkoriPaymentFlowResult

    suspend fun recover(
        runtime: MirkoriPlatformRuntime,
        session: GameIdentitySession,
        pending: PendingMirkoriPurchase,
        order: PlatformOrder,
    ): MirkoriPaymentFlowResult = MirkoriPaymentFlowResult.Notice(BillingNotice.AWAITING_PAYMENT)

    override fun close() = Unit
}

internal sealed interface MirkoriPaymentFlowResult {
    data class ExternalCheckout(val url: String) : MirkoriPaymentFlowResult

    data object Settled : MirkoriPaymentFlowResult

    data class Notice(
        val notice: BillingNotice,
        val availability: BillingAvailability = BillingAvailability.READY,
    ) : MirkoriPaymentFlowResult
}

private data object BrowserMirkoriPaymentFlow : MirkoriPaymentFlow {
    override val allowsGuest: Boolean = false

    override suspend fun start(
        runtime: MirkoriPlatformRuntime,
        session: GameIdentitySession,
        pending: PendingMirkoriPurchase,
        order: PlatformOrder,
    ): MirkoriPaymentFlowResult {
        val checkoutResult = runtime.authenticated(session) { token ->
            runtime.sdk.createCheckout(
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
        return MirkoriPaymentFlowResult.ExternalCheckout(checkoutResult.value.paymentUrl)
    }
}

internal enum class GooglePlayPurchaseState {
    NONE,
    PENDING,
    PURCHASED,
    CANCELLED,
    UNAVAILABLE,
}

internal class GooglePlayPurchase(
    val state: GooglePlayPurchaseState,
    val productId: String? = null,
    val purchaseToken: String? = null,
    val obfuscatedProfileId: String? = null,
) {
    init {
        if (state == GooglePlayPurchaseState.PURCHASED) {
            require(!productId.isNullOrBlank())
            require(purchaseToken?.length in 16..4096)
            require(!obfuscatedProfileId.isNullOrBlank())
        } else {
            require(purchaseToken == null)
        }
    }

    override fun toString(): String = "GooglePlayPurchase(state=$state, [redacted])"
}

internal interface GooglePlayBillingGateway : AutoCloseable {
    suspend fun query(productId: String, obfuscatedProfileId: String): GooglePlayPurchase

    suspend fun launch(productId: String, obfuscatedProfileId: String): GooglePlayPurchase
}

internal class GooglePlayMirkoriPaymentFlow(
    private val gateway: GooglePlayBillingGateway,
    private val expectedDistributionId: String,
    private val expectedPackageName: String,
) : MirkoriPaymentFlow {
    override val allowsGuest: Boolean = true

    override suspend fun start(
        runtime: MirkoriPlatformRuntime,
        session: GameIdentitySession,
        pending: PendingMirkoriPurchase,
        order: PlatformOrder,
    ): MirkoriPaymentFlowResult = continuePayment(runtime, session, pending, order, launchWhenMissing = true)

    override suspend fun recover(
        runtime: MirkoriPlatformRuntime,
        session: GameIdentitySession,
        pending: PendingMirkoriPurchase,
        order: PlatformOrder,
    ): MirkoriPaymentFlowResult = continuePayment(runtime, session, pending, order, launchWhenMissing = false)

    override fun close() = gateway.close()

    private suspend fun continuePayment(
        runtime: MirkoriPlatformRuntime,
        initialSession: GameIdentitySession,
        pending: PendingMirkoriPurchase,
        order: PlatformOrder,
        launchWhenMissing: Boolean,
    ): MirkoriPaymentFlowResult {
        require(order.distributionId == expectedDistributionId)
        require(order.distributionPaymentChannel == PlatformDistributionPaymentChannel.GOOGLE_PLAY)
        require(order.distributionPackageName == expectedPackageName)
        var session = initialSession
        val methodsResult = runtime.authenticated(session) { token ->
            runtime.sdk.paymentMethods(token, order.id, PlatformPaymentChannel.ANDROID)
        }
        session = methodsResult.session
        val methods = methodsResult.value
        require(methods.distributionId == expectedDistributionId)
        require(methods.distributionPaymentChannel == PlatformDistributionPaymentChannel.GOOGLE_PLAY)
        require(methods.distributionPackageName == expectedPackageName)
        val method = methods.methods.singleOrNull { candidate ->
            candidate.id == GooglePlayMethodId &&
                candidate.category == PlatformPaymentMethodCategory.STORE &&
                PlatformPaymentNextActionType.EMBEDDED_SDK in candidate.nextActionTypes
        } ?: throw CommerceContractException()
        val paymentResult = runtime.authenticated(session) { token ->
            runtime.sdk.createPayment(
                profileAccessToken = token,
                orderId = order.id,
                paymentMethodId = method.id,
                channel = PlatformPaymentChannel.ANDROID,
                idempotencyKey = pending.checkoutIdempotencyKey,
            )
        }
        session = paymentResult.session
        val payment = paymentResult.value
        require(payment.orderId == order.id)
        require(payment.currency == order.currency && payment.amountMinor == order.amountMinor)
        when (payment.status) {
            PlatformPaymentStatus.SUCCEEDED -> return MirkoriPaymentFlowResult.Settled
            PlatformPaymentStatus.PROCESSING -> return MirkoriPaymentFlowResult.Notice(
                BillingNotice.AWAITING_ENTITLEMENT,
            )
            PlatformPaymentStatus.CANCELLED,
            PlatformPaymentStatus.FAILED,
            PlatformPaymentStatus.EXPIRED,
            -> {
                runtime.rotatePaymentIdempotencyKey(pending)
                throw CommercePaymentAttemptTerminalException()
            }
            PlatformPaymentStatus.CREATING -> return MirkoriPaymentFlowResult.Notice(BillingNotice.AWAITING_PAYMENT)
            PlatformPaymentStatus.REQUIRES_ACTION -> Unit
        }
        val action = payment.nextAction ?: throw CommerceContractException()
        require(action.type == PlatformPaymentNextActionType.EMBEDDED_SDK)
        require(action.sdkAdapter == GooglePlaySdkAdapter)
        val clientToken = requireNotNull(action.clientToken)
        val restored = gateway.query(order.productId, clientToken)
        val purchase = if (restored.state == GooglePlayPurchaseState.NONE && launchWhenMissing) {
            gateway.launch(order.productId, clientToken)
        } else {
            restored
        }
        return when (purchase.state) {
            GooglePlayPurchaseState.PURCHASED -> {
                require(purchase.productId == order.productId)
                require(purchase.obfuscatedProfileId == clientToken)
                val verification = runtime.authenticated(session) { token ->
                    runtime.sdk.verifyGooglePlayPurchase(
                        profileAccessToken = token,
                        paymentId = payment.id,
                        purchaseToken = requireNotNull(purchase.purchaseToken),
                        idempotencyKey = pending.orderIdempotencyKey,
                    )
                }
                require(verification.value.order.id == order.id)
                MirkoriPaymentFlowResult.Settled
            }
            GooglePlayPurchaseState.PENDING -> MirkoriPaymentFlowResult.Notice(BillingNotice.AWAITING_PAYMENT)
            GooglePlayPurchaseState.CANCELLED -> MirkoriPaymentFlowResult.Notice(BillingNotice.PAYMENT_CANCELLED)
            GooglePlayPurchaseState.UNAVAILABLE -> MirkoriPaymentFlowResult.Notice(
                BillingNotice.PROVIDER_UNAVAILABLE,
                BillingAvailability.UNAVAILABLE,
            )
            GooglePlayPurchaseState.NONE -> MirkoriPaymentFlowResult.Notice(BillingNotice.AWAITING_PAYMENT)
        }
    }

    private fun MirkoriPlatformRuntime.rotatePaymentIdempotencyKey(pending: PendingMirkoriPurchase) {
        val state = currentPersistedState() ?: return
        if (state.pendingPurchase == pending) {
            persist(
                state.copy(
                    pendingPurchase = pending.copy(checkoutIdempotencyKey = sdk.newIdempotencyKey()),
                ),
            )
        }
    }

    private companion object {
        const val GooglePlayMethodId = "google_play"
        const val GooglePlaySdkAdapter = "google_play_billing"
    }
}

private const val LogTag = "MirkoriCommerce"
private const val EntitlementContractSchemaVersion = 1

private val TerminalPendingErrorCodes = setOf(
    "idempotency_conflict",
    "order_not_found",
    "product_not_available",
    "profile_rejected",
)
