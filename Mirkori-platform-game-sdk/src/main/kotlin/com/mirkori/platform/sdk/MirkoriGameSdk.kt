package com.mirkori.platform.sdk

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class MirkoriGameSdk(
    val config: MirkoriGameSdkConfig,
    private val transport: PlatformTransport,
    private val entropy: SecureEntropy = SecureEntropy.system(),
) {
    private val codec = SdkJsonCodec()
    private val baseUri = validateBaseUrl(config.platformBaseUrl, config.allowCleartextLoopback)
    private val callbackUri = validateCallbackUri(config.redirectUri)
    private val serverTimeObservation = AtomicReference<PlatformServerTimeObservation?>()

    init {
        require(config.gameId.matches(GameIdPattern))
    }

    fun newInstallation(): InstallationIdentity = InstallationIdentity(
        installationId = UUID.randomUUID().toString(),
        installationSecret = entropy.token(32),
    )

    fun newIdempotencyKey(): PlatformIdempotencyKey = PlatformIdempotencyKey(entropy.token(32))

    fun latestServerTimeObservation(): PlatformServerTimeObservation? = serverTimeObservation.get()

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
                    body = codec.nativeGoogleGameAuthRequest(pending.session, pending.codeVerifier, idToken),
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

    private fun validateOrder(order: PlatformOrder) {
        require(order.id.isCanonicalUuid())
        require(order.gameId == config.gameId)
        require(order.gamePlayerId.isCanonicalUuid())
        require(order.productId.matches(ResourceIdPattern))
        require(order.currency.matches(CurrencyPattern) && order.amountMinor > 0)
        require(order.updatedAt >= order.createdAt)
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
        val PublicHandlePattern = Regex("[a-z0-9_]{3,24}")
        val PublicAvatarKeys = setOf("rocket", "robot", "star", "gamepad", "heart", "bolt")
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

private fun String.isCanonicalUuid(): Boolean = runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)

private val LoopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")
