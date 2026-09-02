package com.mirkori.platform.sdk

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class MirkoriGameSdk(
    val config: MirkoriGameSdkConfig,
    private val transport: PlatformTransport,
    private val entropy: SecureEntropy = SecureEntropy.system(),
    private val releaseDecisionVerifier: PlatformReleaseDecisionVerifier? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val proSnapshotVerifier: PlatformProMembershipSnapshotVerifier? = null,
) {
    private val codec = SdkJsonCodec()
    private val baseUri = validateBaseUrl(config.platformBaseUrl, config.allowCleartextLoopback)
    private val callbackUri = validateCallbackUri(config.redirectUri)
    private val serverTimeObservation = AtomicReference<PlatformServerTimeObservation?>()

    init {
        require(config.gameId.matches(GameIdPattern))
        config.distributionId?.let { require(it.matches(ResourceIdPattern)) }
    }

    fun newInstallation(): InstallationIdentity = InstallationIdentity(
        installationId = UUID.randomUUID().toString(),
        installationSecret = entropy.token(32),
    )

    fun newIdempotencyKey(): PlatformIdempotencyKey = PlatformIdempotencyKey(entropy.token(32))

    fun latestServerTimeObservation(): PlatformServerTimeObservation? = serverTimeObservation.get()

    fun newProSessionId(): String = UUID.randomUUID().toString()

    suspend fun bootstrapGuest(
        installation: InstallationIdentity,
        platform: GameClientPlatform,
        appVersion: String? = null,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): GameIdentitySession {
        require(installation.installationId.isCanonicalUuid())
        require(installation.installationSecret.matches(HighEntropyTokenPattern))
        appVersion?.let { require(it.length in 1..64 && it.none(Char::isISOControl)) }
        val response = post(
            path = "/api/v1/auth/guest/bootstrap",
            body = codec.bootstrapRequest(config, installation, platform, appVersion),
            idempotencyKey = idempotencyKey,
        )
        val result = codec.bootstrapResponse(response)
        require(result.accountId.isCanonicalUuid())
        require(result.gamePlayerId.isCanonicalUuid())
        require(result.gameId == config.gameId)
        require(result.installationId == installation.installationId)
        return GameIdentitySession(
            accountId = result.accountId,
            gamePlayerId = result.gamePlayerId,
            gameId = result.gameId,
            installationId = result.installationId,
            authMode = PlatformAuthMode.GUEST,
            credentials = result.credentials,
        )
    }

    suspend fun refresh(
        refreshToken: String,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): PlatformCredentials {
        require(refreshToken.matches(CredentialPattern))
        return codec.credentialsResponse(
            post(
                path = "/api/v1/auth/refresh",
                body = codec.refreshRequest(refreshToken),
                idempotencyKey = idempotencyKey,
            ),
        )
    }

    suspend fun checkForUpdate(
        currentVersionCode: Long,
        platform: PlatformReleasePlatform = PlatformReleasePlatform.ANDROID,
        channel: PlatformReleaseChannel = PlatformReleaseChannel.STABLE,
    ): PlatformUpdateDecision {
        require(currentVersionCode > 0)
        return codec.updateDecisionResponse(
            get(
                "/api/v1/catalog/games/${config.gameId}/updates" +
                    "?platform=${platform.wireName}&channel=${channel.wireName}&versionCode=$currentVersionCode",
            ),
        ).also { decision ->
            require(decision.gameId == config.gameId)
            require(decision.platform == platform && decision.channel == channel)
            require(decision.currentVersionCode == currentVersionCode)
            require(decision.required.not() || decision.updateAvailable)
            require(decision.updateAvailable == (decision.release != null))
            decision.release?.let { release ->
                validateRelease(release, currentVersionCode, platform, channel)
                require(decision.required == (currentVersionCode < release.minimumSupportedVersionCode))
            }
        }
    }

    suspend fun checkForDistributionUpdate(
        currentVersionCode: Long,
        channel: PlatformReleaseChannel = PlatformReleaseChannel.STABLE,
    ): PlatformDistributionUpdateDecision {
        require(currentVersionCode > 0)
        val distributionId = requireNotNull(config.distributionId) {
            "Distribution-aware updates require an immutable SDK distributionId"
        }
        return codec.distributionUpdateDecisionResponse(
            get(
                "/api/v2/catalog/games/${config.gameId}/updates" +
                    "?distributionId=${urlEncode(distributionId)}&channel=${channel.wireName}" +
                    "&versionCode=$currentVersionCode",
            ),
        ).also { decision ->
            require(decision.gameId == config.gameId)
            validateDistribution(decision.distribution, distributionId, channel)
            require(decision.channel == channel && decision.currentVersionCode == currentVersionCode)
            require(decision.required.not() || decision.updateAvailable)
            require(decision.updateAvailable == (decision.release != null))
            decision.release?.let { release ->
                validateDistributionRelease(release, decision.distribution, currentVersionCode, channel)
                require(decision.required == (currentVersionCode < release.minimumSupportedVersionCode))
            }
        }
    }

    suspend fun checkInstalledBuildDecision(
        installedReleaseId: String,
        installedVersionCode: Long,
        channel: PlatformReleaseChannel = PlatformReleaseChannel.STABLE,
    ): PlatformInstalledBuildDecision {
        require(installedReleaseId.matches(ResourceIdPattern))
        require(installedVersionCode > 0)
        val distributionId = requireNotNull(config.distributionId) {
            "Installed-build decisions require an immutable SDK distributionId"
        }
        val verifier = requireNotNull(releaseDecisionVerifier) {
            "Installed-build decisions require pinned platform decision keys"
        }
        return codec.installedBuildDecisionResponse(
            get(
                "/api/v3/catalog/games/${config.gameId}/installed-build-decision" +
                    "?distributionId=${urlEncode(distributionId)}&channel=${channel.wireName}" +
                    "&releaseId=${urlEncode(installedReleaseId)}&versionCode=$installedVersionCode",
            ),
            verifier,
        ).also { decision ->
            require(decision.gameId == config.gameId && decision.distributionId == distributionId)
            require(decision.channel == channel)
            require(decision.installedReleaseId == installedReleaseId)
            require(decision.installedVersionCode == installedVersionCode)
            require(decision.policyVersion > 0)
            val now = latestServerTimeObservation()
                ?.let { observation -> Instant.ofEpochMilli(observation.serverEpochMs) }
                ?: clock.instant()
            require(!decision.issuedAt.isAfter(now.plus(AllowedDecisionClockSkew)))
            require(decision.expiresAt.isAfter(now.minus(AllowedDecisionClockSkew)))
            val lifetime = Duration.between(decision.issuedAt, decision.expiresAt)
            require(lifetime.seconds in 60..900)
            when (decision.status) {
                PlatformInstalledBuildStatus.UP_TO_DATE -> {
                    require(decision.launchAllowed && decision.release == null && decision.distribution != null)
                    require(decision.reasonCode == null && decision.supportPath == null)
                }
                PlatformInstalledBuildStatus.OPTIONAL -> {
                    require(decision.launchAllowed && decision.release != null && decision.distribution != null)
                    require(decision.reasonCode == null && decision.supportPath == null)
                }
                PlatformInstalledBuildStatus.REQUIRED -> {
                    require(!decision.launchAllowed && decision.release != null && decision.distribution != null)
                    require(decision.reasonCode == null && decision.supportPath == null)
                }
                PlatformInstalledBuildStatus.UNAVAILABLE -> {
                    require(!decision.launchAllowed)
                    require(decision.reasonCode?.matches(ReasonCodePattern) == true)
                    require(decision.supportPath?.isSafeSupportPath() == true)
                    require(decision.release == null)
                }
                PlatformInstalledBuildStatus.RECALLED -> {
                    require(!decision.launchAllowed && decision.distribution != null)
                    require(decision.reasonCode?.matches(ReasonCodePattern) == true)
                    require(decision.supportPath?.isSafeSupportPath() == true)
                }
            }
            decision.distribution?.let { validateDistribution(it, distributionId, channel) }
            decision.release?.let { release ->
                validateDistributionRelease(
                    release,
                    requireNotNull(decision.distribution),
                    installedVersionCode,
                    channel,
                )
            }
        }
    }

    suspend fun proMembershipSnapshot(
        profileAccessToken: String,
        accountId: String,
        trustedServerTimeFloor: Instant? = null,
    ): PlatformProMembershipSnapshot {
        require(profileAccessToken.matches(CredentialPattern))
        require(accountId.isCanonicalUuid())
        val distributionId = requireProDistribution()
        val verifier = requireNotNull(proSnapshotVerifier) {
            "Pro membership snapshots require separately pinned Pro keys"
        }
        return proApi {
            verifier.verify(
                envelopeJson = get(
                    "/api/v1/pro/snapshot?distributionId=${urlEncode(distributionId)}",
                    profileAccessToken,
                ),
                expectedAccountId = accountId,
                expectedGameId = config.gameId,
                expectedDistributionId = distributionId,
                trustedServerTimeFloor = trustedServerTimeFloor,
            )
        }
    }

    suspend fun startProSession(
        profileAccessToken: String,
        accountId: String,
        installationId: String,
        sessionId: String,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): PlatformProSessionLease {
        validateProInputs(profileAccessToken, accountId, installationId, sessionId)
        val distributionId = requireProDistribution()
        return proApi {
            codec.proLeaseResponse(post(
                path = "/api/v1/pro/leases",
                body = codec.proLeaseRequest(distributionId, installationId, sessionId),
                idempotencyKey = idempotencyKey,
                bearerToken = profileAccessToken,
            ))
        }.also { validateProLease(it, accountId, installationId, sessionId) }
    }

    suspend fun heartbeatProSession(
        profileAccessToken: String,
        lease: PlatformProSessionLease,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): PlatformProSessionLease {
        validateProInputs(profileAccessToken, lease.accountId, lease.installationId, lease.sessionId)
        validateProLease(lease, lease.accountId, lease.installationId, lease.sessionId)
        require(lease.status == PlatformProSessionLeaseStatus.ACTIVE)
        val result = proApi {
            codec.proLeaseResponse(post(
                path = "/api/v1/pro/leases/${urlEncode(lease.id)}/heartbeat",
                body = codec.proLeaseRequest(requireProDistribution(), lease.installationId, lease.sessionId),
                idempotencyKey = idempotencyKey,
                bearerToken = profileAccessToken,
            ))
        }
        require(result.id == lease.id && result.createdAt == lease.createdAt)
        validateProLease(result, lease.accountId, lease.installationId, lease.sessionId)
        return result
    }

    suspend fun releaseProSession(
        profileAccessToken: String,
        lease: PlatformProSessionLease,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): PlatformProSessionLease {
        validateProInputs(profileAccessToken, lease.accountId, lease.installationId, lease.sessionId)
        validateProLease(lease, lease.accountId, lease.installationId, lease.sessionId)
        val result = proApi {
            codec.proLeaseResponse(post(
                path = "/api/v1/pro/leases/${urlEncode(lease.id)}/release",
                body = codec.proLeaseRequest(requireProDistribution(), lease.installationId, lease.sessionId),
                idempotencyKey = idempotencyKey,
                bearerToken = profileAccessToken,
            ))
        }
        require(result.id == lease.id && result.createdAt == lease.createdAt)
        validateProLease(result, lease.accountId, lease.installationId, lease.sessionId)
        require(result.status == PlatformProSessionLeaseStatus.RELEASED && result.releasedAt != null)
        return result
    }

    suspend fun beginAccountLogin(
        profileAccessToken: String,
        installationId: String,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): PendingGameLogin {
        require(profileAccessToken.matches(CredentialPattern))
        require(installationId.isCanonicalUuid())
        val verifier = entropy.token(32)
        val state = entropy.token(32)
        val response = post(
            path = "/api/v1/game-auth/sessions",
            body = codec.gameAuthSessionRequest(
                installationId = installationId,
                redirectUri = callbackUri.toASCIIString(),
                state = state,
                challenge = pkceChallenge(verifier),
            ),
            idempotencyKey = idempotencyKey,
            bearerToken = profileAccessToken,
        )
        val result = codec.gameAuthSessionResponse(response)
        require(result.session.matches(SessionPattern))
        validateConnectUrl(result.connectUrl, result.session)
        return PendingGameLogin(
            session = result.session,
            state = state,
            codeVerifier = verifier,
            connectUrl = result.connectUrl,
            expiresAt = result.expiresAt,
        )
    }

    suspend fun completeAccountLogin(
        callbackUrl: String,
        pending: PendingGameLogin,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): GameIdentitySession {
        require(pending.session.matches(SessionPattern))
        require(pending.state.matches(PkceValuePattern))
        require(pending.codeVerifier.matches(PkceValuePattern))
        val callback = parseCallback(callbackUrl)
        if (!constantTimeEqual(callback.session, pending.session) || !constantTimeEqual(callback.state, pending.state)) {
            throw PlatformCallbackRejectedException()
        }
        if (callback.error != null) {
            if (callback.error == "profile_conflict") throw PlatformProfileConflictException()
            throw PlatformCallbackRejectedException()
        }
        return gameIdentitySession(
            codec.exchangeResponse(
                post(
                    path = "/api/v1/game-auth/exchange",
                    body = codec.exchangeRequest(pending.session, pending.codeVerifier),
                    idempotencyKey = idempotencyKey,
                ),
            ),
        )
    }

    suspend fun completeGoogleAccountLogin(
        profileAccessToken: String,
        idToken: String,
        pending: PendingGameLogin,
        conflictResolution: PlatformProfileConflictResolution = PlatformProfileConflictResolution.KEEP_CURRENT_PROFILE,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): GameIdentitySession {
        require(profileAccessToken.matches(CredentialPattern))
        require(idToken.length in 100..8_192 && idToken.none(Char::isWhitespace))
        require(pending.session.matches(SessionPattern))
        require(pending.state.matches(PkceValuePattern))
        require(pending.codeVerifier.matches(PkceValuePattern))
        val result = try {
            codec.exchangeResponse(
                post(
                    path = "/api/v1/game-auth/google",
                    body = codec.nativeGoogleGameAuthRequest(
                        pending.session,
                        pending.codeVerifier,
                        idToken,
                        conflictResolution,
                    ),
                    idempotencyKey = idempotencyKey,
                    bearerToken = profileAccessToken,
                ),
            )
        } catch (error: PlatformApiException) {
            if (error.status == 409 && error.errorCode == "profile_conflict") throw PlatformProfileConflictException()
            throw error
        }
        return gameIdentitySession(result)
    }

    private fun gameIdentitySession(result: SdkJsonCodec.ExchangeResponse): GameIdentitySession {
        require(result.accountId.isCanonicalUuid())
        require(result.gamePlayerId.isCanonicalUuid())
        require(result.gameId == config.gameId)
        require(result.authMode != PlatformAuthMode.GUEST)
        return GameIdentitySession(
            accountId = result.accountId,
            gamePlayerId = result.gamePlayerId,
            gameId = result.gameId,
            installationId = null,
            authMode = result.authMode,
            credentials = result.credentials,
        )
    }

    suspend fun products(currency: String): List<PlatformProductOffer> {
        require(currency.matches(CurrencyPattern))
        return codec.productsResponse(
            get("/api/v1/commerce/games/${config.gameId}/products?currency=$currency"),
        ).also { products ->
            products.forEach { product ->
                require(product.gameId == config.gameId)
                require(product.id.matches(ResourceIdPattern))
                require(product.slug.matches(ResourceIdPattern))
                require(product.price.currency == currency && product.price.amountMinor > 0)
                require(product.version > 0 && product.grants.isNotEmpty())
                product.grants.forEach { grant ->
                    require(grant.entitlementKey.matches(ResourceIdPattern) && grant.quantity > 0)
                    require(
                        if (grant.type == PlatformEntitlementType.TIMED) {
                            grant.durationSeconds != null && grant.durationSeconds > 0
                        } else {
                            grant.durationSeconds == null
                        },
                    )
                }
            }
        }
    }

    suspend fun createOrder(
        profileAccessToken: String,
        productId: String,
        currency: String,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): PlatformOrder {
        require(profileAccessToken.matches(CredentialPattern))
        require(productId.matches(ResourceIdPattern))
        require(currency.matches(CurrencyPattern))
        return codec.orderResponse(
            post(
                path = "/api/v1/commerce/orders",
                body = codec.createOrderRequest(productId, currency),
                idempotencyKey = idempotencyKey,
                bearerToken = profileAccessToken,
            ),
        ).also { order ->
            validateOrder(order)
            require(order.productId == productId && order.currency == currency)
        }
    }

    suspend fun createGuestCheckoutHandoff(
        guestProfileAccessToken: String,
        productId: String,
        currency: String,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): PlatformGuestCheckoutHandoff {
        require(guestProfileAccessToken.matches(CredentialPattern))
        require(productId.matches(ResourceIdPattern))
        require(currency.matches(CurrencyPattern))
        return codec.guestCheckoutHandoffResponse(
            post(
                path = "/api/v1/commerce/guest-checkout-handoffs",
                body = codec.guestCheckoutHandoffRequest(productId, currency),
                idempotencyKey = idempotencyKey,
                bearerToken = guestProfileAccessToken,
            ),
        ).also { handoff ->
            require(handoff.id.isCanonicalUuid())
            require(handoff.productId == productId && handoff.currency == currency)
            require(handoff.expiresAt > clock.instant())
            validateExternalHttpsUrl(handoff.checkoutUrl)
        }
    }

    suspend fun createCheckout(
        profileAccessToken: String,
        orderId: String,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): PlatformCheckout {
        require(profileAccessToken.matches(CredentialPattern))
        require(orderId.isCanonicalUuid())
        return codec.checkoutResponse(
            post(
                path = "/api/v1/commerce/orders/$orderId/checkout",
                body = "{}",
                idempotencyKey = idempotencyKey,
                bearerToken = profileAccessToken,
            ),
        ).also { checkout ->
            require(checkout.id.isCanonicalUuid() && checkout.orderId == orderId)
            require(checkout.provider.matches(ProviderPattern))
            require(checkout.status == PlatformCheckoutStatus.READY)
            require(checkout.expiresAt > checkout.createdAt && checkout.updatedAt >= checkout.createdAt)
            validateExternalHttpsUrl(checkout.paymentUrl)
        }
    }

    suspend fun paymentMethods(
        profileAccessToken: String,
        orderId: String,
        channel: PlatformPaymentChannel,
    ): PlatformPaymentMethods {
        require(profileAccessToken.matches(CredentialPattern))
        require(orderId.isCanonicalUuid())
        return codec.paymentMethodsResponse(
            get(
                "/api/v1/commerce/orders/$orderId/payment-methods?channel=${channel.wireName}",
                profileAccessToken,
            ),
        ).also { result ->
            require(result.orderId == orderId && result.currency.matches(CurrencyPattern) && result.amountMinor > 0)
            require(result.countryCode == null || result.countryCode.matches(Regex("[A-Z]{2}")))
            result.methods.forEach { method ->
                require(method.id.matches(ResourceIdPattern) && method.nextActionTypes.isNotEmpty())
            }
        }
    }

    suspend fun createPayment(
        profileAccessToken: String,
        orderId: String,
        paymentMethodId: String,
        channel: PlatformPaymentChannel,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): PlatformPayment {
        require(profileAccessToken.matches(CredentialPattern))
        require(orderId.isCanonicalUuid())
        require(paymentMethodId.matches(ResourceIdPattern))
        return codec.paymentResponse(
            post(
                path = "/api/v1/commerce/orders/$orderId/payments",
                body = codec.createPaymentRequest(paymentMethodId, channel),
                idempotencyKey = idempotencyKey,
                bearerToken = profileAccessToken,
            ),
        ).also { payment ->
            validatePayment(payment)
            require(
                payment.orderId == orderId && payment.paymentMethodId == paymentMethodId && payment.channel == channel,
            )
        }
    }

    suspend fun payment(profileAccessToken: String, paymentId: String): PlatformPayment {
        require(profileAccessToken.matches(CredentialPattern))
        require(paymentId.isCanonicalUuid())
        return codec.paymentResponse(
            get("/api/v1/commerce/payments/$paymentId", profileAccessToken),
        ).also { payment ->
            validatePayment(payment)
            require(payment.id == paymentId)
        }
    }

    suspend fun order(profileAccessToken: String, orderId: String): PlatformOrder {
        require(profileAccessToken.matches(CredentialPattern))
        require(orderId.isCanonicalUuid())
        return codec.orderResponse(
            get("/api/v1/commerce/orders/$orderId", profileAccessToken),
        ).also { order ->
            validateOrder(order)
            require(order.id == orderId)
        }
    }

    suspend fun orders(profileAccessToken: String): List<PlatformOrder> {
        require(profileAccessToken.matches(CredentialPattern))
        return codec.ordersResponse(
            get("/api/v1/commerce/orders", profileAccessToken),
        ).also { orders -> orders.forEach(::validateOrder) }
    }

    suspend fun pendingOrders(profileAccessToken: String): List<PlatformOrder> {
        require(profileAccessToken.matches(CredentialPattern))
        return codec.ordersResponse(
            get("/api/v1/commerce/orders/pending", profileAccessToken),
        ).also { orders ->
            orders.forEach { order ->
                validateOrder(order)
                require(order.status == PlatformOrderStatus.PENDING)
            }
        }
    }

    suspend fun entitlements(profileAccessToken: String): List<PlatformEntitlement> {
        require(profileAccessToken.matches(CredentialPattern))
        return codec.entitlementsResponse(
            get("/api/v1/commerce/entitlements", profileAccessToken),
        ).also { entitlements ->
            entitlements.forEach { entitlement ->
                require(entitlement.key.matches(ResourceIdPattern) && entitlement.quantity > 0)
                require(
                    if (entitlement.type == PlatformEntitlementType.TIMED) {
                        entitlement.validUntil != null
                    } else {
                        entitlement.validUntil == null
                    },
                )
            }
        }
    }

    suspend fun pendingGameDeliveries(
        profileAccessToken: String,
        limit: Int = 50,
    ): List<PlatformGameEntitlementDelivery> {
        require(profileAccessToken.matches(CredentialPattern))
        require(limit in 1..100)
        return codec.gameDeliveriesResponse(
            get("/api/v1/commerce/game-deliveries?limit=$limit", profileAccessToken),
        ).also { deliveries ->
            deliveries.forEach { delivery ->
                require(delivery.id.isCanonicalUuid())
                require(delivery.entitlementEventId.isCanonicalUuid())
                require(delivery.entitlementId.isCanonicalUuid())
                require(delivery.sequenceNumber > 0 && delivery.gameId == config.gameId)
                require(delivery.productId.matches(ResourceIdPattern))
                require(delivery.orderId.isCanonicalUuid())
                require(delivery.entitlementKey.matches(ResourceIdPattern))
                require(delivery.correctionQuantity >= 0)
                require(delivery.payloadSha256.matches(Sha256Pattern))
                require(
                    if (delivery.entitlementKind == PlatformEntitlementKind.TIME_BOUNDED_PRO) {
                        delivery.expiresAt != null && delivery.expiresAt > delivery.validFrom
                    } else {
                        delivery.expiresAt == null
                    },
                )
                require(
                    delivery.action == PlatformGameDeliveryAction.GRANT && delivery.quantityDelta > 0 ||
                        delivery.action == PlatformGameDeliveryAction.REVOKE && delivery.quantityDelta <= 0,
                )
            }
        }
    }

    suspend fun acknowledgeGameDelivery(
        profileAccessToken: String,
        deliveryId: String,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): PlatformGameDeliveryAcknowledgement {
        require(profileAccessToken.matches(CredentialPattern))
        require(deliveryId.isCanonicalUuid())
        return codec.gameDeliveryAcknowledgementResponse(
            post(
                path = "/api/v1/commerce/game-deliveries/$deliveryId/ack",
                body = codec.gameDeliveryAcknowledgementRequest(),
                idempotencyKey = idempotencyKey,
                bearerToken = profileAccessToken,
            ),
        ).also { acknowledgement -> require(acknowledgement.deliveryId == deliveryId) }
    }

    suspend fun consumeEntitlement(
        profileAccessToken: String,
        entitlementKey: String,
        quantity: Long,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): PlatformConsumptionReceipt {
        require(profileAccessToken.matches(CredentialPattern))
        require(entitlementKey.matches(ResourceIdPattern))
        require(quantity > 0)
        return codec.consumptionResponse(
            post(
                path = "/api/v2/commerce/entitlements/$entitlementKey/consume",
                body = codec.consumptionRequest(quantity),
                idempotencyKey = idempotencyKey,
                bearerToken = profileAccessToken,
            ),
        ).also { receipt ->
            require(receipt.id.isCanonicalUuid())
            require(receipt.entitlementKey == entitlementKey)
            require(receipt.quantity == quantity && receipt.remainingQuantity >= 0)
        }
    }

    suspend fun publicProfile(profileAccessToken: String): PlatformPublicPlayerProfile {
        require(profileAccessToken.matches(CredentialPattern))
        return codec.publicProfileResponse(
            get("/api/v1/game-profiles/me/public-profile", profileAccessToken),
        ).also(::validatePublicProfile)
    }

    suspend fun searchPlayers(
        profileAccessToken: String,
        query: String,
    ): List<PlatformPublicPlayerProfile> {
        require(profileAccessToken.matches(CredentialPattern))
        val normalized = query.trim().removePrefix("@").trim()
        require(normalized.length in 1..64 && normalized.none(Char::isISOControl))
        val encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.name())
        return codec.publicProfileSearchResponse(
            get("/api/v1/game-profiles/search?query=$encoded", profileAccessToken),
        ).also { results -> results.forEach(::validatePublicProfile) }
    }

    suspend fun updatePublicProfile(
        profileAccessToken: String,
        handle: String,
        displayName: String?,
        idempotencyKey: PlatformIdempotencyKey,
    ): PlatformPublicPlayerProfile = updatePublicProfile(
        profileAccessToken = profileAccessToken,
        handle = handle,
        displayName = displayName,
        avatarKey = null,
        idempotencyKey = idempotencyKey,
    )

    suspend fun updatePublicProfile(
        profileAccessToken: String,
        handle: String? = null,
        displayName: String? = null,
        avatarKey: String? = null,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): PlatformPublicPlayerProfile {
        require(profileAccessToken.matches(CredentialPattern))
        val normalizedHandle = handle?.trim()?.removePrefix("@")?.lowercase()?.also { value ->
            require(value.matches(PublicHandlePattern))
        }
        val normalizedDisplayName = displayName?.trim()?.also { value ->
            require(value.length in 1..120 && value.none(Char::isISOControl))
        }
        val normalizedAvatarKey = avatarKey?.trim()?.lowercase()?.also { value ->
            require(value in PublicAvatarKeys)
        }
        require(normalizedHandle != null || normalizedDisplayName != null || normalizedAvatarKey != null)
        return codec.publicProfileResponse(
            put(
                path = "/api/v1/game-profiles/me/public-profile",
                body = codec.publicProfileUpdateRequest(
                    normalizedHandle,
                    normalizedDisplayName,
                    normalizedAvatarKey,
                ),
                idempotencyKey = idempotencyKey,
                bearerToken = profileAccessToken,
            ),
        ).also(::validatePublicProfile)
    }

    suspend fun createFriendRequest(
        profileAccessToken: String,
        targetGamePlayerId: String,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): PlatformFriendRequest {
        require(profileAccessToken.matches(CredentialPattern))
        require(targetGamePlayerId.isCanonicalUuid())
        return codec.friendRequestResponse(
            post(
                path = "/api/v1/game-profiles/me/friend-requests",
                body = codec.friendRequest(targetGamePlayerId),
                idempotencyKey = idempotencyKey,
                bearerToken = profileAccessToken,
            ),
        ).also(::validateFriendRequest)
    }

    suspend fun incomingFriendRequests(profileAccessToken: String): List<PlatformFriendRequest> {
        require(profileAccessToken.matches(CredentialPattern))
        return codec.friendRequestsResponse(
            get("/api/v1/game-profiles/me/friend-requests/incoming", profileAccessToken),
        ).also { requests -> requests.forEach(::validateFriendRequest) }
    }

    suspend fun acceptFriendRequest(
        profileAccessToken: String,
        requestId: String,
        idempotencyKey: PlatformIdempotencyKey = newIdempotencyKey(),
    ): PlatformFriendRequest {
        require(profileAccessToken.matches(CredentialPattern))
        require(requestId.isCanonicalUuid())
        return codec.friendRequestResponse(
            post(
                path = "/api/v1/game-profiles/me/friend-requests/$requestId/accept",
                body = "{}",
                idempotencyKey = idempotencyKey,
                bearerToken = profileAccessToken,
            ),
        ).also(::validateFriendRequest)
    }

    suspend fun friends(profileAccessToken: String): List<PlatformPublicPlayerProfile> {
        require(profileAccessToken.matches(CredentialPattern))
        return codec.friendsResponse(
            get("/api/v1/game-profiles/me/friends", profileAccessToken),
        ).also { profiles -> profiles.forEach(::validatePublicProfile) }
    }

    private suspend fun post(
        path: String,
        body: String,
        idempotencyKey: PlatformIdempotencyKey,
        bearerToken: String? = null,
    ): String = request(
        method = PlatformHttpMethod.POST,
        path = path,
        body = body,
        idempotencyKey = idempotencyKey,
        bearerToken = bearerToken,
    )

    private fun validateFriendRequest(request: PlatformFriendRequest) {
        require(request.requestId.isCanonicalUuid())
        validatePublicProfile(request.player)
    }

    private suspend fun get(path: String, bearerToken: String? = null): String = request(
        method = PlatformHttpMethod.GET,
        path = path,
        body = "",
        bearerToken = bearerToken,
    )

    private suspend fun put(
        path: String,
        body: String,
        idempotencyKey: PlatformIdempotencyKey,
        bearerToken: String,
    ): String = request(
        method = PlatformHttpMethod.PUT,
        path = path,
        body = body,
        idempotencyKey = idempotencyKey,
        bearerToken = bearerToken,
    )

    private suspend fun request(
        method: PlatformHttpMethod,
        path: String,
        body: String,
        idempotencyKey: PlatformIdempotencyKey? = null,
        bearerToken: String? = null,
    ): String {
        val headers = linkedMapOf(
            "Accept" to "application/json",
        )
        if (method != PlatformHttpMethod.GET) headers["Content-Type"] = "application/json"
        idempotencyKey?.let { headers["Idempotency-Key"] = it.value }
        bearerToken?.let { headers["Authorization"] = "Bearer $it" }
        val response = transport.execute(
            PlatformHttpRequest(
                method = method,
                url = endpoint(path),
                headers = headers,
                body = body,
            ),
        )
        response.serverTime
            ?.takeIf { baseUri.scheme.equals("https", ignoreCase = true) }
            ?.let { serverTime ->
                serverTimeObservation.updateAndGet { previous ->
                    PlatformServerTimeObservation(
                        serverEpochMs = serverTime.toEpochMilli(),
                        revision = (previous?.revision ?: 0L) + 1L,
                    )
                }
            }
        if (response.status !in 200..299) {
            throw PlatformApiException(response.status, codec.errorCode(response.body))
        }
        return response.body
    }

    private suspend fun <T> proApi(block: suspend () -> T): T = try {
        block()
    } catch (error: PlatformApiException) {
        when (error.errorCode) {
            "pro_configuration_unavailable" -> throw PlatformProConfigurationUnavailableException()
            "pro_concurrency_limit" -> throw PlatformProConcurrencyLimitException()
            "pro_unavailable", "pro_lease_unavailable" -> throw PlatformProBenefitUnavailableException()
            else -> throw error
        }
    }

    private fun requireProDistribution(): String = requireNotNull(config.distributionId) {
        "Pro requires an immutable SDK distributionId"
    }

    private fun validateProInputs(
        profileAccessToken: String,
        accountId: String,
        installationId: String,
        sessionId: String,
    ) {
        require(profileAccessToken.matches(CredentialPattern))
        require(accountId.isCanonicalUuid())
        require(installationId.isCanonicalUuid())
        require(sessionId.isCanonicalUuid())
    }

    private fun validateProLease(
        lease: PlatformProSessionLease,
        accountId: String,
        installationId: String,
        sessionId: String,
    ) {
        require(lease.id.isCanonicalUuid())
        require(lease.accountId == accountId && lease.accountId.isCanonicalUuid())
        require(lease.gameId == config.gameId)
        require(lease.distributionId == requireProDistribution())
        require(lease.installationId == installationId && lease.installationId.isCanonicalUuid())
        require(lease.sessionId == sessionId && lease.sessionId.isCanonicalUuid())
        require(lease.benefitContentId.matches(ResourceIdPattern))
        require(lease.membershipVersion > 0 && lease.participationVersion > 0 && lease.policyVersion > 0)
        require(lease.maxConcurrentSessions in 2..3)
        require(lease.lastHeartbeatAt >= lease.createdAt && lease.expiresAt > lease.createdAt)
        require((lease.status == PlatformProSessionLeaseStatus.ACTIVE && lease.releasedAt == null) ||
            (lease.status == PlatformProSessionLeaseStatus.RELEASED && lease.releasedAt != null &&
                lease.releasedAt >= lease.createdAt))
    }

    private fun validateOrder(order: PlatformOrder) {
        require(order.id.isCanonicalUuid())
        require(order.gameId == config.gameId)
        require(order.gamePlayerId.isCanonicalUuid())
        require(order.productId.matches(ResourceIdPattern))
        require(order.currency.matches(CurrencyPattern) && order.amountMinor > 0)
        require(order.updatedAt >= order.createdAt)
    }

    private fun validateRelease(
        release: PlatformGameRelease,
        currentVersionCode: Long,
        platform: PlatformReleasePlatform,
        channel: PlatformReleaseChannel,
    ) {
        require(release.id.matches(ResourceIdPattern) && release.gameId == config.gameId)
        require(release.platform == platform && release.channel == channel)
        require(release.versionName.length in 1..64 && release.versionName.none(Char::isISOControl))
        require(release.versionCode > currentVersionCode)
        require(release.minimumSupportedVersionCode in 1..release.versionCode)
        require(release.changelog.length in 1..4000 && release.changelog.trim() == release.changelog)
        require(release.fileName.matches(FileNamePattern))
        require(release.sizeBytes in 1..MaximumArtifactBytes)
        require(release.sha256.matches(Sha256Pattern))
        validateArtifactUrl(release.downloadUrl, release.id, release.fileName)
        if (platform == PlatformReleasePlatform.ANDROID) {
            require(release.minimumAndroidSdk in 21..100)
            require(release.fileName.endsWith(".apk", ignoreCase = true))
            require(release.packageName?.matches(AndroidPackagePattern) == true)
            require(release.signingCertificateSha256Fingerprints.isNotEmpty())
            require(release.signingCertificateSha256Fingerprints.distinct().size == release.signingCertificateSha256Fingerprints.size)
            require(release.signingCertificateSha256Fingerprints.all(CertificateFingerprintPattern::matches))
        } else {
            require(release.minimumAndroidSdk == null && release.packageName == null)
            require(release.signingCertificateSha256Fingerprints.isEmpty())
        }
    }

    private fun validateDistribution(
        distribution: PlatformDistributionVariant,
        expectedDistributionId: String,
        expectedChannel: PlatformReleaseChannel,
    ) {
        require(distribution.id == expectedDistributionId && distribution.gameId == config.gameId)
        require(distribution.platform == PlatformReleasePlatform.ANDROID)
        require(distribution.packageName.matches(AndroidPackagePattern))
        require(distribution.signingIdentityRef.matches(ResourceIdPattern))
        require(distribution.signingCertificateSha256Fingerprints.isNotEmpty())
        require(
            distribution.signingCertificateSha256Fingerprints.distinct().size ==
                distribution.signingCertificateSha256Fingerprints.size,
        )
        require(distribution.signingCertificateSha256Fingerprints.all(CertificateFingerprintPattern::matches))
        require(expectedChannel in distribution.releaseChannels)
        require(distribution.status == PlatformDistributionStatus.ACTIVE)
        require(distribution.effectiveConfigurationVersion > 0)
        require(
            distribution.marketScope == PlatformDistributionMarketScope.RF &&
                distribution.paymentChannel == PlatformDistributionPaymentChannel.MIRKORI ||
                distribution.marketScope == PlatformDistributionMarketScope.GLOBAL &&
                distribution.paymentChannel == PlatformDistributionPaymentChannel.GOOGLE_PLAY,
        )
        require(
            distribution.marketScope == PlatformDistributionMarketScope.RF &&
                distribution.deliveryChannel == PlatformDistributionDeliveryChannel.DIRECT_APK ||
                distribution.marketScope == PlatformDistributionMarketScope.GLOBAL &&
                distribution.deliveryChannel == PlatformDistributionDeliveryChannel.GOOGLE_PLAY,
        )
    }

    private fun validateDistributionRelease(
        release: PlatformDistributionGameRelease,
        distribution: PlatformDistributionVariant,
        currentVersionCode: Long,
        channel: PlatformReleaseChannel,
    ) {
        require(release.id.matches(ResourceIdPattern) && release.gameId == config.gameId)
        require(release.distributionId == distribution.id)
        require(release.platform == distribution.platform && release.channel == channel)
        require(release.versionName.length in 1..64 && release.versionName.none(Char::isISOControl))
        require(release.versionCode > currentVersionCode)
        require(release.minimumSupportedVersionCode in 1..release.versionCode)
        require(release.minimumAndroidSdk in 21..100)
        require(release.changelogs.keys == setOf("ru", "en"))
        require(release.changelogs.values.all { it.length in 1..4000 && it.trim() == it })
        require(release.fileName.matches(FileNamePattern) && release.fileName.endsWith(".apk", ignoreCase = true))
        require(release.sizeBytes in 1..MaximumArtifactBytes)
        require(release.sha256.matches(Sha256Pattern))
        when (distribution.deliveryChannel) {
            PlatformDistributionDeliveryChannel.DIRECT_APK ->
                validateArtifactUrl(requireNotNull(release.downloadUrl), release.id, release.fileName)
            PlatformDistributionDeliveryChannel.GOOGLE_PLAY -> require(release.downloadUrl == null)
        }
        require(release.packageName == distribution.packageName)
        require(release.signingIdentityRef == distribution.signingIdentityRef)
        require(
            release.signingCertificateSha256Fingerprints == distribution.signingCertificateSha256Fingerprints,
        )
    }

    private fun validateArtifactUrl(value: String, releaseId: String, fileName: String) {
        validateExternalHttpsUrl(value)
        val uri = URI(value)
        require(uri.query == null)
        require(uri.rawPath == "/downloads/${urlEncode(releaseId)}/${urlEncode(fileName)}")
    }

    private fun validatePayment(payment: PlatformPayment) {
        require(payment.id.isCanonicalUuid() && payment.orderId.isCanonicalUuid())
        require(payment.paymentMethodId.matches(ResourceIdPattern))
        require(payment.currency.matches(CurrencyPattern) && payment.amountMinor > 0)
        require(payment.updatedAt >= payment.createdAt)
        payment.expiresAt?.let { require(it > payment.createdAt) }
        payment.nextAction?.let { action ->
            when (action.type) {
                PlatformPaymentNextActionType.REDIRECT -> {
                    requireNotNull(action.url).let(::validateExternalHttpsUrl)
                    require(action.fallbackUrl == null && action.sdkAdapter == null && action.clientToken == null)
                }
                PlatformPaymentNextActionType.DEEP_LINK -> {
                    requireNotNull(action.url).let(::validatePaymentDeepLink)
                    action.fallbackUrl?.let(::validateExternalHttpsUrl)
                    require(action.sdkAdapter == null && action.clientToken == null)
                }
                PlatformPaymentNextActionType.EMBEDDED_SDK -> {
                    require(action.url == null && action.fallbackUrl == null)
                    require(action.sdkAdapter?.matches(ProviderPattern) == true)
                    require(action.clientToken?.length in 16..8192)
                }
            }
        }
        require(payment.status == PlatformPaymentStatus.REQUIRES_ACTION || payment.nextAction == null)
    }

    private fun validatePaymentDeepLink(value: String) {
        require(value.length in 1..4096 && value.none(Char::isISOControl))
        val uri = URI(value)
        require(
            !uri.scheme.isNullOrBlank() && !uri.scheme.equals("http", true) && !uri.scheme.equals("https", true) &&
                uri.userInfo == null && uri.fragment == null,
        )
    }

    private fun validatePublicProfile(profile: PlatformPublicPlayerProfile) {
        require(profile.gamePlayerId.isCanonicalUuid())
        profile.handle?.let { require(it.matches(PublicHandlePattern)) }
        require(profile.displayName.length in 1..120 && profile.displayName.none(Char::isISOControl))
        profile.avatarUrl?.let(::validateExternalHttpsUrl)
    }

    private fun validateExternalHttpsUrl(value: String) {
        require(value.length in 1..4096 && value.none(Char::isISOControl))
        val uri = URI(value)
        require(
            uri.scheme.equals("https", ignoreCase = true) && uri.host != null && uri.userInfo == null &&
                uri.fragment == null
        )
    }

    private fun endpoint(path: String): String {
        require(path.startsWith('/') && !path.startsWith("//"))
        return baseUri.toASCIIString().removeSuffix("/") + path
    }

    private fun validateConnectUrl(value: String, session: String) {
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Invalid connect URL") }
        val basePort = effectivePort(baseUri)
        require(
            uri.scheme.equals(baseUri.scheme, ignoreCase = true) &&
                uri.host.equals(baseUri.host, ignoreCase = true) &&
                effectivePort(uri) == basePort && uri.path == "/connect" &&
                uri.userInfo == null && uri.fragment == null
        )
        val query = parseQuery(uri.rawQuery)
        require(query.keys == setOf("session") && query["session"] == session)
    }

    private fun parseCallback(value: String): Callback {
        val uri = runCatching { URI(value) }.getOrElse { throw PlatformCallbackRejectedException() }
        if (!sameEndpoint(uri, callbackUri) || uri.fragment != null) throw PlatformCallbackRejectedException()
        val query = runCatching { parseQuery(uri.rawQuery) }.getOrElse { throw PlatformCallbackRejectedException() }
        if (!query.keys.all(setOf("session", "state", "error")::contains)) throw PlatformCallbackRejectedException()
        val session = query["session"]?.takeIf { it.matches(SessionPattern) }
            ?: throw PlatformCallbackRejectedException()
        val state = query["state"]?.takeIf { it.matches(PkceValuePattern) }
            ?: throw PlatformCallbackRejectedException()
        val error = query["error"]?.takeIf { it.matches(ErrorCodePattern) }
        if (query.containsKey("error") && error == null) throw PlatformCallbackRejectedException()
        return Callback(session, state, error)
    }

    private data class Callback(val session: String, val state: String, val error: String?)

    private companion object {
        val GameIdPattern = Regex("[a-z0-9][a-z0-9-]{0,63}")
        val HighEntropyTokenPattern = Regex("[A-Za-z0-9_-]{43,128}")
        val CredentialPattern = Regex("\\S{32,8192}")
        val SessionPattern = Regex("[A-Za-z0-9_-]{64}")
        val PkceValuePattern = Regex("[A-Za-z0-9._~-]{43,128}")
        val ErrorCodePattern = Regex("[a-z0-9_]{1,64}")
        val ResourceIdPattern = Regex("[a-z0-9][a-z0-9._-]{1,63}")
        val CurrencyPattern = Regex("[A-Z]{3}")
        val ProviderPattern = Regex("[a-z0-9][a-z0-9_-]{1,31}")
        val ReasonCodePattern = Regex("[a-z0-9][a-z0-9._-]{2,63}")
        val PublicHandlePattern = Regex("[a-z0-9_]{3,24}")
        val FileNamePattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val Sha256Pattern = Regex("[0-9a-f]{64}")
        val AndroidPackagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        val CertificateFingerprintPattern = Regex("(?:[0-9A-F]{2}:){31}[0-9A-F]{2}")
        val PublicAvatarKeys = setOf("rocket", "robot", "star", "gamepad", "heart", "bolt")
        const val MaximumArtifactBytes = 4L * 1024L * 1024L * 1024L
        val AllowedDecisionClockSkew: Duration = Duration.ofSeconds(30)
    }
}

fun interface SecureEntropy {
    fun bytes(count: Int): ByteArray

    fun token(count: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(count))

    companion object {
        fun system(): SecureEntropy {
            val random = SecureRandom()
            return SecureEntropy { count -> ByteArray(count).also(random::nextBytes) }
        }
    }
}

private fun validateBaseUrl(value: String, allowCleartextLoopback: Boolean): URI {
    val uri = URI(value)
    require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
    require(uri.path.isNullOrEmpty() || uri.path == "/")
    val secure = uri.scheme.equals("https", ignoreCase = true)
    val loopback = uri.scheme.equals("http", ignoreCase = true) && uri.host.lowercase() in LoopbackHosts
    require(secure || allowCleartextLoopback && loopback)
    require(effectivePort(uri) in 1..65535)
    return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, null, null, null)
}

private fun validateCallbackUri(value: String): URI {
    val uri = URI(value)
    require(
        uri.scheme.equals("https", ignoreCase = true) && uri.host != null && uri.userInfo == null &&
            !uri.path.isNullOrEmpty() && uri.query == null && uri.fragment == null && effectivePort(uri) in 1..65535
    )
    return uri
}

private fun parseQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrEmpty()) return emptyMap()
    val result = linkedMapOf<String, String>()
    rawQuery.split('&').forEach { entry ->
        require(entry.isNotEmpty())
        val parts = entry.split('=', limit = 2)
        require(parts.size == 2)
        val name = URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name())
        val value = URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
        require(name !in result)
        result[name] = value
    }
    return result
}

private fun sameEndpoint(first: URI, second: URI): Boolean =
    first.scheme.equals(second.scheme, ignoreCase = true) &&
        first.host.equals(second.host, ignoreCase = true) &&
        effectivePort(first) == effectivePort(second) && first.path == second.path && first.userInfo == null

private fun effectivePort(uri: URI): Int = when {
    uri.port >= 0 -> uri.port
    uri.scheme.equals("https", ignoreCase = true) -> 443
    uri.scheme.equals("http", ignoreCase = true) -> 80
    else -> -1
}

private fun pkceChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
)

private fun constantTimeEqual(first: String, second: String): Boolean = MessageDigest.isEqual(
    first.toByteArray(StandardCharsets.US_ASCII),
    second.toByteArray(StandardCharsets.US_ASCII),
)

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun String.isCanonicalUuid(): Boolean = runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)

private fun String.isSafeSupportPath(): Boolean =
    matches(Regex("/[A-Za-z0-9._~/-]{1,255}")) && !startsWith("//") && '?' !in this && '#' !in this &&
        split('/').drop(1).all { it.isNotEmpty() && it != "." && it != ".." }

private val LoopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")
