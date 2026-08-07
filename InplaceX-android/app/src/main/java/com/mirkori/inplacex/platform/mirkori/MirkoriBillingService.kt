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
        when (error.errorCode) {
            "product_already_owned" -> {
                clearPendingPurchase()
                recoverCommerceAfterServerSignal(
                    config,
                    currency,
                    previousProducts,
                    BillingNotice.PRODUCT_ALREADY_ACTIVE,
                    "refresh",
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
                recoverCommerceAfterServerSignal(
                    config,
                    currency,
                    previousProducts,
                    BillingNotice.PRODUCT_ALREADY_ACTIVE,
                    "purchase",
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
): BillingState = try {
    synchronizeCommerceLocked(config, currency, notice)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (retryError: Exception) {
    commerceFailureState(config, previousProducts, retryError, operation)
}

private suspend fun MirkoriPlatformRuntime.synchronizeCommerceLocked(
    config: BillingProviderConfig,
    currency: String,
    initialNotice: BillingNotice,
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
        availability = BillingAvailability.READY,
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

private const val LogTag = "MirkoriCommerce"
private const val EntitlementContractSchemaVersion = 1

private val TerminalPendingErrorCodes = setOf(
    "idempotency_conflict",
    "order_not_found",
    "product_not_available",
    "profile_rejected",
)
