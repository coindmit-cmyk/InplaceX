package com.mirkori.platform.sdk

import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal class SdkJsonCodec {
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    fun bootstrapRequest(
        config: MirkoriGameSdkConfig,
        installation: InstallationIdentity,
        platform: GameClientPlatform,
        appVersion: String?,
    ): String = buildJsonObject {
        put("gameId", config.gameId)
        put("installationId", installation.installationId)
        put("installationSecret", installation.installationSecret)
        put("platform", platform.wireName)
        appVersion?.let { put("appVersion", it) }
    }.toString()

    fun refreshRequest(refreshToken: String): String = buildJsonObject {
        put("refreshToken", refreshToken)
    }.toString()

    fun gameAuthSessionRequest(
        installationId: String,
        redirectUri: String,
        state: String,
        challenge: String,
    ): String = buildJsonObject {
        put("installationId", installationId)
        put("redirectUri", redirectUri)
        put("state", state)
        put("codeChallenge", challenge)
        put("codeChallengeMethod", "S256")
    }.toString()

    fun exchangeRequest(session: String, verifier: String): String = buildJsonObject {
        put("session", session)
        put("codeVerifier", verifier)
    }.toString()

    fun nativeGoogleGameAuthRequest(
        session: String,
        verifier: String,
        credential: String,
        conflictResolution: PlatformProfileConflictResolution,
    ): String =
        buildJsonObject {
            put("session", session)
            put("codeVerifier", verifier)
            put("credential", credential)
            if (conflictResolution != PlatformProfileConflictResolution.KEEP_CURRENT_PROFILE) {
                put("conflictResolution", conflictResolution.wireName)
            }
        }.toString()

    fun createOrderRequest(productId: String, currency: String): String = buildJsonObject {
        put("productId", productId)
        put("currency", currency)
    }.toString()

    fun guestCheckoutHandoffRequest(productId: String, currency: String): String =
        createOrderRequest(productId, currency)

    fun proLeaseRequest(distributionId: String, installationId: String, sessionId: String): String = buildJsonObject {
        put("distributionId", distributionId)
        put("installationId", installationId)
        put("sessionId", sessionId)
    }.toString()

    fun proLeaseResponse(body: String): PlatformProSessionLease {
        val root = objectBody(body)
        val required = setOf(
            "schemaVersion", "leaseId", "accountId", "gameId", "distributionId", "installationId",
            "sessionId", "benefitContentId", "membershipVersion", "participationVersion", "policyVersion",
            "maxConcurrentSessions", "status", "createdAt", "lastHeartbeatAt", "expiresAt",
        )
        require(root.keys == required || root.keys == required + "releasedAt")
        require(root.long("schemaVersion") == 1L)
        val maxConcurrentSessions = root.long("maxConcurrentSessions")
        require(maxConcurrentSessions in 2..3)
        return PlatformProSessionLease(
            id = root.string("leaseId", 64),
            accountId = root.string("accountId", 64),
            gameId = root.string("gameId", 64),
            distributionId = root.string("distributionId", 64),
            installationId = root.string("installationId", 64),
            sessionId = root.string("sessionId", 64),
            benefitContentId = root.string("benefitContentId", 128),
            membershipVersion = root.long("membershipVersion"),
            participationVersion = root.long("participationVersion"),
            policyVersion = root.long("policyVersion"),
            maxConcurrentSessions = maxConcurrentSessions.toInt(),
            status = PlatformProSessionLeaseStatus.fromWireName(root.string("status", 16)) ?: reject(),
            createdAt = root.instant("createdAt"),
            lastHeartbeatAt = root.instant("lastHeartbeatAt"),
            expiresAt = root.instant("expiresAt"),
            releasedAt = root["releasedAt"]?.let { root.instant("releasedAt") },
        )
    }

    fun createPaymentRequest(methodId: String, channel: PlatformPaymentChannel): String = buildJsonObject {
        put("paymentMethodId", methodId)
        put("channel", channel.wireName)
    }.toString()

    fun consumptionRequest(quantity: Long): String = buildJsonObject {
        put("quantity", quantity)
    }.toString()

    fun publicProfileUpdateRequest(handle: String?, displayName: String?, avatarKey: String?): String = buildJsonObject {
        handle?.let { put("handle", it) }
        displayName?.let { put("displayName", it) }
        avatarKey?.let { put("avatarKey", it) }
    }.toString()

    fun friendRequest(targetGamePlayerId: String): String = buildJsonObject {
        put("targetGamePlayerId", targetGamePlayerId)
    }.toString()

    fun bootstrapResponse(body: String): BootstrapResponse {
        val root = objectBody(body)
        root.requireExactFields("accountId", "gamePlayerId", "gameId", "installationId", "credentials")
        return BootstrapResponse(
            accountId = root.string("accountId", 64),
            gamePlayerId = root.string("gamePlayerId", 64),
            gameId = root.string("gameId", 64),
            installationId = root.string("installationId", 64),
            credentials = credentials(root["credentials"]?.jsonObject ?: reject()),
        )
    }

    fun credentialsResponse(body: String): PlatformCredentials = credentials(objectBody(body))

    fun gameAuthSessionResponse(body: String): GameAuthSessionResponse {
        val root = objectBody(body)
        root.requireExactFields("session", "connectUrl", "expiresAtEpochMs")
        return GameAuthSessionResponse(
            session = root.string("session", 128),
            connectUrl = root.string("connectUrl", 4096),
            expiresAt = Instant.ofEpochMilli(root.long("expiresAtEpochMs")),
        )
    }

    fun exchangeResponse(body: String): ExchangeResponse {
        val root = objectBody(body)
        root.requireExactFields("accountId", "gamePlayerId", "gameId", "authMode", "credentials")
        return ExchangeResponse(
            accountId = root.string("accountId", 64),
            gamePlayerId = root.string("gamePlayerId", 64),
            gameId = root.string("gameId", 64),
            authMode = PlatformAuthMode.fromWireName(root.string("authMode", 16)) ?: reject(),
            credentials = credentials(root["credentials"]?.jsonObject ?: reject()),
        )
    }

    fun updateDecisionResponse(body: String): PlatformUpdateDecision {
        val root = objectBody(body)
        require(
            root.keys == setOf(
                "schemaVersion", "gameId", "platform", "channel", "currentVersionCode",
                "updateAvailable", "required",
            ) || root.keys == setOf(
                "schemaVersion", "gameId", "platform", "channel", "currentVersionCode",
                "updateAvailable", "required", "release",
            ),
        )
        require(root.long("schemaVersion") == 1L)
        return PlatformUpdateDecision(
            gameId = root.string("gameId", 64),
            platform = PlatformReleasePlatform.fromWireName(root.string("platform", 16)) ?: reject(),
            channel = PlatformReleaseChannel.fromWireName(root.string("channel", 16)) ?: reject(),
            currentVersionCode = root.long("currentVersionCode"),
            updateAvailable = root.boolean("updateAvailable"),
            required = root.boolean("required"),
            release = root["release"]?.let { release(it.objectValue()) },
        )
    }

    fun distributionUpdateDecisionResponse(body: String): PlatformDistributionUpdateDecision {
        val root = objectBody(body)
        require(
            root.keys == setOf(
                "schemaVersion", "gameId", "distribution", "channel", "currentVersionCode",
                "updateAvailable", "required",
            ) || root.keys == setOf(
                "schemaVersion", "gameId", "distribution", "channel", "currentVersionCode",
                "updateAvailable", "required", "release",
            ),
        )
        require(root.long("schemaVersion") == 2L)
        return PlatformDistributionUpdateDecision(
            gameId = root.string("gameId", 64),
            distribution = distribution(root["distribution"]?.objectValue() ?: reject()),
            channel = PlatformReleaseChannel.fromWireName(root.string("channel", 16)) ?: reject(),
            currentVersionCode = root.long("currentVersionCode"),
            updateAvailable = root.boolean("updateAvailable"),
            required = root.boolean("required"),
            release = root["release"]?.let { distributionRelease(it.objectValue()) },
        )
    }

    fun installedBuildDecisionResponse(
        body: String,
        verifier: PlatformReleaseDecisionVerifier,
    ): PlatformInstalledBuildDecision {
        val envelope = objectBody(body)
        envelope.requireExactFields("schemaVersion", "payload", "signature")
        require(envelope.long("schemaVersion") == 3L)
        val encodedPayload = envelope.string("payload", MaximumEncodedDecisionBytes)
        require(encodedPayload.matches(Base64UrlPattern))
        val signature = envelope["signature"]?.objectValue() ?: reject()
        signature.requireExactFields("algorithm", "keyId", "value")
        val algorithm = signature.string("algorithm", 16)
        val keyId = signature.string("keyId", 64)
        val encodedSignature = signature.string("value", MaximumEncodedSignatureBytes)
        require(verifier.verify(keyId, algorithm, encodedPayload, encodedSignature))
        val payloadBytes = runCatching { Base64.getUrlDecoder().decode(encodedPayload) }.getOrNull() ?: reject()
        require(payloadBytes.size in 2..MaximumDecodedDecisionBytes)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes) == encodedPayload)
        val payload = try {
            json.parseToJsonElement(payloadBytes.toString(Charsets.UTF_8)) as? JsonObject ?: reject()
        } finally {
            payloadBytes.fill(0)
        }
        val requiredFields = setOf(
            "schemaVersion", "gameId", "distributionId", "channel", "installedReleaseId",
            "installedVersionCode", "status", "launchAllowed", "policyVersion", "issuedAt", "expiresAt",
        )
        val optionalFields = setOf("distribution", "reasonCode", "supportPath", "release")
        require(payload.keys.containsAll(requiredFields) && payload.keys.all { it in requiredFields || it in optionalFields })
        require(payload.long("schemaVersion") == 3L)
        return PlatformInstalledBuildDecision(
            gameId = payload.string("gameId", 64),
            distributionId = payload.string("distributionId", 64),
            channel = PlatformReleaseChannel.fromWireName(payload.string("channel", 16)) ?: reject(),
            installedReleaseId = payload.string("installedReleaseId", 64),
            installedVersionCode = payload.long("installedVersionCode"),
            status = PlatformInstalledBuildStatus.fromWireName(payload.string("status", 32)) ?: reject(),
            launchAllowed = payload.boolean("launchAllowed"),
            policyVersion = payload.long("policyVersion"),
            issuedAt = payload.instant("issuedAt"),
            expiresAt = payload.instant("expiresAt"),
            distribution = payload["distribution"]?.let { distribution(it.objectValue()) },
            reasonCode = payload["reasonCode"]?.let { payload.string("reasonCode", 64) },
            supportPath = payload["supportPath"]?.let { payload.string("supportPath", 256) },
            release = payload["release"]?.let { distributionRelease(it.objectValue()) },
            signatureKeyId = keyId,
        )
    }

    fun productsResponse(body: String): List<PlatformProductOffer> {
        val root = objectBody(body)
        root.requireExactFields("schemaVersion", "products")
        require(root.long("schemaVersion") == 1L)
        return root.array("products", 256).map(::product)
    }

    fun orderResponse(body: String): PlatformOrder = order(objectBody(body))

    fun guestCheckoutHandoffResponse(body: String): PlatformGuestCheckoutHandoff {
        val root = objectBody(body)
        root.requireExactFields("schemaVersion", "handoffId", "productId", "currency", "checkoutUrl", "expiresAt")
        require(root.long("schemaVersion") == 1L)
        return PlatformGuestCheckoutHandoff(
            id = root.string("handoffId", 64),
            productId = root.string("productId", 64),
            currency = root.string("currency", 3),
            checkoutUrl = root.string("checkoutUrl", 4096),
            expiresAt = root.instant("expiresAt"),
        )
    }

    fun ordersResponse(body: String): List<PlatformOrder> {
        val root = objectBody(body)
        root.requireExactFields("schemaVersion", "orders")
        require(root.long("schemaVersion") == 1L)
        return root.array("orders", 100).map { order(it.objectValue()) }
    }

    fun checkoutResponse(body: String): PlatformCheckout {
        val root = objectBody(body)
        root.requireExactFields("schemaVersion", "checkout", "paymentUrl")
        require(root.long("schemaVersion") == 1L)
        val checkout = root["checkout"]?.objectValue() ?: reject()
        checkout.requireExactFields(
            "id", "orderId", "provider", "status", "expiresAt", "createdAt", "updatedAt",
        )
        return PlatformCheckout(
            id = checkout.string("id", 64),
            orderId = checkout.string("orderId", 64),
            provider = checkout.string("provider", 32),
            status = PlatformCheckoutStatus.fromWireName(checkout.string("status", 16)) ?: reject(),
            paymentUrl = root.string("paymentUrl", 4096),
            expiresAt = checkout.instant("expiresAt"),
            createdAt = checkout.instant("createdAt"),
            updatedAt = checkout.instant("updatedAt"),
        )
    }

    fun paymentMethodsResponse(body: String): PlatformPaymentMethods {
        val root = objectBody(body)
        require(
            root.keys == setOf("schemaVersion", "orderId", "currency", "amountMinor", "methods") ||
                root.keys == setOf("schemaVersion", "orderId", "currency", "amountMinor", "countryCode", "methods")
        )
        require(root.long("schemaVersion") == 1L)
        return PlatformPaymentMethods(
            orderId = root.string("orderId", 64),
            currency = root.string("currency", 3),
            amountMinor = root.long("amountMinor"),
            countryCode = root["countryCode"]?.let { root.string("countryCode", 2) },
            methods = root.array("methods", 32).map { element ->
                val method = element.objectValue()
                method.requireExactFields("id", "category", "displayName", "nextActionTypes")
                PlatformPaymentMethod(
                    id = method.string("id", 32),
                    category = PlatformPaymentMethodCategory.fromWireName(method.string("category", 32)) ?: reject(),
                    displayName = method.string("displayName", 120),
                    nextActionTypes = method.array("nextActionTypes", 3).mapTo(linkedSetOf()) {
                        PlatformPaymentNextActionType.fromWireName(
                            (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: reject(),
                        ) ?: reject()
                    },
                )
            },
        )
    }

    fun paymentResponse(body: String): PlatformPayment {
        val root = objectBody(body)
        root.requireExactFields("schemaVersion", "payment")
        require(root.long("schemaVersion") == 1L)
        val payment = root["payment"]?.objectValue() ?: reject()
        val required = setOf(
            "id", "orderId", "status", "paymentMethodId", "channel", "currency", "amountMinor", "createdAt", "updatedAt",
        )
        require(payment.keys - setOf("expiresAt", "nextAction") == required)
        val action = payment["nextAction"]?.objectValue()?.let { value ->
            require(value.keys - setOf("url", "fallbackUrl", "sdkAdapter", "clientToken") == setOf("type"))
            PlatformPaymentNextAction(
                type = PlatformPaymentNextActionType.fromWireName(value.string("type", 32)) ?: reject(),
                url = value["url"]?.let { value.string("url", 4096) },
                fallbackUrl = value["fallbackUrl"]?.let { value.string("fallbackUrl", 4096) },
                sdkAdapter = value["sdkAdapter"]?.let { value.string("sdkAdapter", 32) },
                clientToken = value["clientToken"]?.let { value.string("clientToken", 8192) },
            )
        }
        return PlatformPayment(
            id = payment.string("id", 64),
            orderId = payment.string("orderId", 64),
            status = PlatformPaymentStatus.fromWireName(payment.string("status", 24)) ?: reject(),
            paymentMethodId = payment.string("paymentMethodId", 32),
            channel = PlatformPaymentChannel.fromWireName(payment.string("channel", 16)) ?: reject(),
            currency = payment.string("currency", 3),
            amountMinor = payment.long("amountMinor"),
            expiresAt = payment["expiresAt"]?.let { payment.instant("expiresAt") },
            createdAt = payment.instant("createdAt"),
            updatedAt = payment.instant("updatedAt"),
            nextAction = action,
        )
    }

    fun entitlementsResponse(body: String): List<PlatformEntitlement> {
        val root = objectBody(body)
        root.requireExactFields("schemaVersion", "entitlements")
        require(root.long("schemaVersion") == 1L)
        return root.array("entitlements", 256).map(::entitlement)
    }

    fun consumptionResponse(body: String): PlatformConsumptionReceipt {
        val root = objectBody(body)
        root.requireExactFields("schemaVersion", "consumption", "entitlements")
        require(root.long("schemaVersion") == 2L)
        val consumption = root["consumption"]?.objectValue() ?: reject()
        consumption.requireExactFields("id", "entitlementKey", "quantity", "remainingQuantity", "createdAt")
        return PlatformConsumptionReceipt(
            id = consumption.string("id", 64),
            entitlementKey = consumption.string("entitlementKey", 64),
            quantity = consumption.long("quantity"),
            remainingQuantity = consumption.long("remainingQuantity"),
            createdAt = consumption.instant("createdAt"),
        )
    }

    fun gameDeliveryAcknowledgementRequest(): String = buildJsonObject {
        put("applied", true)
    }.toString()

    fun gameDeliveriesResponse(body: String): List<PlatformGameEntitlementDelivery> {
        val root = objectBody(body)
        root.requireExactFields("schemaVersion", "deliveries")
        require(root.long("schemaVersion") == 1L)
        return root.array("deliveries", 100).map { element ->
            val value = element.objectValue()
            val required = setOf(
                "id", "entitlementEventId", "entitlementId", "sequenceNumber", "action",
                "gameId", "productId", "orderId", "entitlementKey", "entitlementKind",
                "quantityDelta", "validFrom", "correctionQuantity", "payloadSha256", "createdAt",
            )
            require(value.keys == required || value.keys == required + "expiresAt")
            PlatformGameEntitlementDelivery(
                id = value.string("id", 64),
                entitlementEventId = value.string("entitlementEventId", 64),
                entitlementId = value.string("entitlementId", 64),
                sequenceNumber = value.long("sequenceNumber"),
                action = PlatformGameDeliveryAction.fromWireName(value.string("action", 16)) ?: reject(),
                gameId = value.string("gameId", 64),
                productId = value.string("productId", 64),
                orderId = value.string("orderId", 64),
                entitlementKey = value.string("entitlementKey", 64),
                entitlementKind = PlatformEntitlementKind.fromWireName(value.string("entitlementKind", 32))
                    ?: reject(),
                quantityDelta = value.long("quantityDelta"),
                validFrom = value.instant("validFrom"),
                expiresAt = value["expiresAt"]?.let { value.instant("expiresAt") },
                correctionQuantity = value.long("correctionQuantity"),
                payloadSha256 = value.string("payloadSha256", 64),
                createdAt = value.instant("createdAt"),
            )
        }
    }

    fun gameDeliveryAcknowledgementResponse(body: String): PlatformGameDeliveryAcknowledgement {
        val root = objectBody(body)
        root.requireExactFields("schemaVersion", "acknowledgement")
        require(root.long("schemaVersion") == 1L)
        val acknowledgement = root["acknowledgement"]?.objectValue() ?: reject()
        acknowledgement.requireExactFields("deliveryId", "acknowledgedAt")
        return PlatformGameDeliveryAcknowledgement(
            deliveryId = acknowledgement.string("deliveryId", 64),
            acknowledgedAt = acknowledgement.instant("acknowledgedAt"),
        )
    }

    fun publicProfileResponse(body: String): PlatformPublicPlayerProfile = publicProfile(objectBody(body))

    fun publicProfileSearchResponse(body: String): List<PlatformPublicPlayerProfile> {
        val root = objectBody(body)
        root.requireExactFields("schemaVersion", "players")
        require(root.long("schemaVersion") == 1L)
        return root.array("players", 20).map { publicProfile(it.objectValue()) }
    }

    fun friendRequestResponse(body: String): PlatformFriendRequest = friendRequest(objectBody(body))

    fun friendRequestsResponse(body: String): List<PlatformFriendRequest> {
        val root = objectBody(body)
        root.requireExactFields("schemaVersion", "requests")
        require(root.long("schemaVersion") == 1L)
        return root.array("requests", 50).map { friendRequest(it.objectValue()) }
    }

    fun friendsResponse(body: String): List<PlatformPublicPlayerProfile> {
        val root = objectBody(body)
        root.requireExactFields("schemaVersion", "players")
        require(root.long("schemaVersion") == 1L)
        return root.array("players", 500).map { publicProfile(it.objectValue()) }
    }

    fun errorCode(body: String): String = runCatching {
        val root = objectBody(body)
        root.requireExactFields("error")
        root.string("error", 64).takeIf { it.matches(ErrorCodePattern) } ?: reject()
    }.getOrDefault("invalid_response")

    private fun credentials(value: JsonObject): PlatformCredentials {
        value.requireExactFields(
            "accessToken",
            "refreshToken",
            "accessExpiresAtEpochMs",
            "refreshExpiresAtEpochMs",
        )
        return PlatformCredentials(
            accessToken = value.credential("accessToken", 8192),
            refreshToken = value.credential("refreshToken", 512),
            accessExpiresAt = Instant.ofEpochMilli(value.long("accessExpiresAtEpochMs")),
            refreshExpiresAt = Instant.ofEpochMilli(value.long("refreshExpiresAtEpochMs")),
        )
    }

    private fun product(element: JsonElement): PlatformProductOffer {
        val value = element.objectValue()
        value.requireExactFields(
            "id", "gameId", "slug", "displayName", "description", "productKind", "version", "price", "grants",
        )
        val price = value["price"]?.objectValue() ?: reject()
        price.requireExactFields("currency", "amountMinor")
        return PlatformProductOffer(
            id = value.string("id", 64),
            gameId = value.string("gameId", 64),
            slug = value.string("slug", 64),
            displayName = value.string("displayName", 120),
            description = value.string("description", 1000),
            kind = PlatformProductKind.fromWireName(value.string("productKind", 16)) ?: reject(),
            version = value.long("version"),
            price = PlatformProductPrice(price.string("currency", 3), price.long("amountMinor")),
            grants = value.array("grants", 16).map(::grant),
        )
    }

    private fun release(value: JsonObject): PlatformGameRelease {
        val commonFields = setOf(
            "id", "gameId", "platform", "channel", "versionName", "versionCode",
            "minimumSupportedVersionCode", "publishedAt", "changelog", "fileName",
            "sizeBytes", "sha256", "downloadUrl",
        )
        val androidFields = setOf(
            "minimumAndroidSdk", "packageName", "signingCertificateSha256Fingerprints",
        )
        require(value.keys == commonFields || value.keys == commonFields + androidFields)
        val platform = PlatformReleasePlatform.fromWireName(value.string("platform", 16)) ?: reject()
        require((platform == PlatformReleasePlatform.ANDROID) == value.keys.containsAll(androidFields))
        return PlatformGameRelease(
            id = value.string("id", 64),
            gameId = value.string("gameId", 64),
            platform = platform,
            channel = PlatformReleaseChannel.fromWireName(value.string("channel", 16)) ?: reject(),
            versionName = value.string("versionName", 64),
            versionCode = value.long("versionCode"),
            minimumSupportedVersionCode = value.long("minimumSupportedVersionCode"),
            minimumAndroidSdk = value["minimumAndroidSdk"]?.let {
                value.long("minimumAndroidSdk").takeIf { sdk -> sdk in 21..100 }?.toInt() ?: reject()
            },
            publishedAt = value.instant("publishedAt"),
            changelog = value.text("changelog", 4000),
            fileName = value.string("fileName", 128),
            sizeBytes = value.long("sizeBytes"),
            sha256 = value.string("sha256", 64),
            downloadUrl = value.string("downloadUrl", 4096),
            packageName = value["packageName"]?.let { value.string("packageName", 255) },
            signingCertificateSha256Fingerprints = value["signingCertificateSha256Fingerprints"]?.let {
                value.array("signingCertificateSha256Fingerprints", 16).map { element ->
                    val primitive = element as? JsonPrimitive ?: reject()
                    require(primitive.isString)
                    primitive.content.takeIf { fingerprint -> fingerprint.length == 95 } ?: reject()
                }
            }.orEmpty(),
        )
    }

    private fun distribution(value: JsonObject): PlatformDistributionVariant {
        value.requireExactFields(
            "id", "gameId", "platform", "marketScope", "packageName", "signingIdentityRef",
            "signingCertificateSha256Fingerprints", "paymentChannel", "deliveryChannel", "releaseChannels", "status",
            "effectiveConfigurationVersion",
        )
        val releaseChannels = value.array("releaseChannels", 2).map { element ->
            val primitive = element as? JsonPrimitive ?: reject()
            require(primitive.isString)
            PlatformReleaseChannel.fromWireName(primitive.content) ?: reject()
        }
        require(releaseChannels.isNotEmpty() && releaseChannels.distinct().size == releaseChannels.size)
        return PlatformDistributionVariant(
            id = value.string("id", 64),
            gameId = value.string("gameId", 64),
            platform = PlatformReleasePlatform.fromWireName(value.string("platform", 16)) ?: reject(),
            marketScope = PlatformDistributionMarketScope.fromWireName(value.string("marketScope", 16)) ?: reject(),
            packageName = value.string("packageName", 255),
            signingIdentityRef = value.string("signingIdentityRef", 64),
            signingCertificateSha256Fingerprints = value.fingerprints(),
            paymentChannel = PlatformDistributionPaymentChannel.fromWireName(
                value.string("paymentChannel", 32),
            ) ?: reject(),
            deliveryChannel = PlatformDistributionDeliveryChannel.fromWireName(
                value.string("deliveryChannel", 32),
            ) ?: reject(),
            releaseChannels = releaseChannels.toSet(),
            status = PlatformDistributionStatus.fromWireName(value.string("status", 16)) ?: reject(),
            effectiveConfigurationVersion = value.long("effectiveConfigurationVersion"),
        )
    }

    private fun distributionRelease(value: JsonObject): PlatformDistributionGameRelease {
        val commonFields = setOf(
            "id", "gameId", "distributionId", "platform", "channel", "versionName", "versionCode",
            "minimumSupportedVersionCode", "minimumAndroidSdk", "publishedAt", "changelogs", "fileName",
            "sizeBytes", "sha256", "packageName", "signingIdentityRef",
            "signingCertificateSha256Fingerprints",
        )
        require(value.keys == commonFields || value.keys == commonFields + "downloadUrl")
        val changelogs = value["changelogs"]?.objectValue() ?: reject()
        changelogs.requireExactFields("ru", "en")
        return PlatformDistributionGameRelease(
            id = value.string("id", 64),
            gameId = value.string("gameId", 64),
            distributionId = value.string("distributionId", 64),
            platform = PlatformReleasePlatform.fromWireName(value.string("platform", 16)) ?: reject(),
            channel = PlatformReleaseChannel.fromWireName(value.string("channel", 16)) ?: reject(),
            versionName = value.string("versionName", 64),
            versionCode = value.long("versionCode"),
            minimumSupportedVersionCode = value.long("minimumSupportedVersionCode"),
            minimumAndroidSdk = value.long("minimumAndroidSdk").takeIf { it in 21..100 }?.toInt() ?: reject(),
            publishedAt = value.instant("publishedAt"),
            changelogs = linkedMapOf(
                "ru" to changelogs.text("ru", 4000),
                "en" to changelogs.text("en", 4000),
            ),
            fileName = value.string("fileName", 128),
            sizeBytes = value.long("sizeBytes"),
            sha256 = value.string("sha256", 64),
            downloadUrl = value["downloadUrl"]?.let { value.string("downloadUrl", 4096) },
            packageName = value.string("packageName", 255),
            signingIdentityRef = value.string("signingIdentityRef", 64),
            signingCertificateSha256Fingerprints = value.fingerprints(),
        )
    }

    private fun JsonObject.fingerprints(): List<String> =
        array("signingCertificateSha256Fingerprints", 16).map { element ->
            val primitive = element as? JsonPrimitive ?: reject()
            require(primitive.isString)
            primitive.content.takeIf { fingerprint -> fingerprint.length == 95 } ?: reject()
        }

    private fun grant(element: JsonElement): PlatformProductGrant {
        val value = element.objectValue()
        require(
            value.keys == setOf("entitlementKey", "type", "quantity") ||
                value.keys == setOf("entitlementKey", "type", "quantity", "durationSeconds")
        )
        return PlatformProductGrant(
            entitlementKey = value.string("entitlementKey", 64),
            type = PlatformEntitlementType.fromWireName(value.string("type", 16)) ?: reject(),
            quantity = value.long("quantity"),
            durationSeconds = value["durationSeconds"]?.let { runCatching { it.jsonPrimitive.long }.getOrNull() ?: reject() },
        )
    }

    private fun order(value: JsonObject): PlatformOrder {
        value.requireExactFields(
            "id", "gameId", "gamePlayerId", "productId", "currency", "amountMinor", "status", "createdAt", "updatedAt",
        )
        return PlatformOrder(
            id = value.string("id", 64),
            gameId = value.string("gameId", 64),
            gamePlayerId = value.string("gamePlayerId", 64),
            productId = value.string("productId", 64),
            currency = value.string("currency", 3),
            amountMinor = value.long("amountMinor"),
            status = PlatformOrderStatus.fromWireName(value.string("status", 16)) ?: reject(),
            createdAt = value.instant("createdAt"),
            updatedAt = value.instant("updatedAt"),
        )
    }

    private fun entitlement(element: JsonElement): PlatformEntitlement {
        val value = element.objectValue()
        require(
            value.keys == setOf("key", "type", "quantity") ||
                value.keys == setOf("key", "type", "quantity", "validUntil")
        )
        return PlatformEntitlement(
            key = value.string("key", 64),
            type = PlatformEntitlementType.fromWireName(value.string("type", 16)) ?: reject(),
            quantity = value.long("quantity"),
            validUntil = value["validUntil"]?.let { value.instant("validUntil") },
        )
    }

    private fun publicProfile(value: JsonObject): PlatformPublicPlayerProfile {
        value.requireExactFields("gamePlayerId", "handle", "displayName", "avatarUrl")
        return PlatformPublicPlayerProfile(
            gamePlayerId = value.string("gamePlayerId", 64),
            handle = value.nullableString("handle", 24),
            displayName = value.string("displayName", 120),
            avatarUrl = value.nullableString("avatarUrl", 2048),
        )
    }

    private fun friendRequest(value: JsonObject): PlatformFriendRequest {
        value.requireExactFields("requestId", "status", "player", "createdAtEpochMs")
        return PlatformFriendRequest(
            requestId = value.string("requestId", 64),
            status = PlatformFriendRequestStatus.fromWireName(value.string("status", 16)) ?: reject(),
            player = publicProfile(value["player"]?.objectValue() ?: reject()),
            createdAt = Instant.ofEpochMilli(value.long("createdAtEpochMs")),
        )
    }

    private fun objectBody(body: String): JsonObject {
        require(body.toByteArray(Charsets.UTF_8).size in 2..MaximumResponseBytes)
        return json.parseToJsonElement(body) as? JsonObject ?: reject()
    }

    private fun JsonObject.requireExactFields(vararg names: String) {
        require(keys == names.toSet())
    }

    private fun JsonObject.string(name: String, maximum: Int): String {
        val primitive = get(name) as? JsonPrimitive ?: reject()
        require(primitive.isString)
        return primitive.content.takeIf { it.length in 1..maximum && it.none(Char::isISOControl) } ?: reject()
    }

    private fun JsonObject.text(name: String, maximum: Int): String {
        val primitive = get(name) as? JsonPrimitive ?: reject()
        require(primitive.isString)
        return primitive.content.takeIf { value ->
            value.length in 1..maximum && value.trim() == value &&
                value.none { it.isISOControl() && it !in setOf('\n', '\r', '\t') }
        } ?: reject()
    }

    private fun JsonObject.credential(name: String, maximum: Int): String =
        string(name, maximum).takeIf { it.none(Char::isWhitespace) } ?: reject()

    private fun JsonObject.nullableString(name: String, maximum: Int): String? {
        val element = get(name) ?: reject()
        if (element.toString() == "null") return null
        val primitive = element as? JsonPrimitive ?: reject()
        require(primitive.isString)
        return primitive.content.takeIf { it.length in 1..maximum && it.none(Char::isISOControl) } ?: reject()
    }

    private fun JsonObject.long(name: String): Long =
        runCatching { get(name)?.jsonPrimitive?.long }.getOrNull() ?: reject()

    private fun JsonObject.boolean(name: String): Boolean =
        runCatching { get(name)?.jsonPrimitive?.boolean }.getOrNull() ?: reject()

    private fun JsonObject.instant(name: String): Instant = runCatching {
        Instant.parse(string(name, 64))
    }.getOrElse { reject() }

    private fun JsonObject.array(name: String, maximum: Int): JsonArray =
        (get(name) as? JsonArray)?.takeIf { it.size <= maximum } ?: reject()

    private fun JsonElement.objectValue(): JsonObject = this as? JsonObject ?: reject()

    private fun reject(): Nothing = throw IllegalArgumentException("Invalid platform response")

    internal data class BootstrapResponse(
        val accountId: String,
        val gamePlayerId: String,
        val gameId: String,
        val installationId: String,
        val credentials: PlatformCredentials,
    )

    internal data class GameAuthSessionResponse(
        val session: String,
        val connectUrl: String,
        val expiresAt: Instant,
    )

    internal data class ExchangeResponse(
        val accountId: String,
        val gamePlayerId: String,
        val gameId: String,
        val authMode: PlatformAuthMode,
        val credentials: PlatformCredentials,
    )

    private companion object {
        val ErrorCodePattern = Regex("[a-z][a-z0-9_]{0,63}")
        val Base64UrlPattern = Regex("[A-Za-z0-9_-]{2,65535}")
        const val MaximumResponseBytes = 64 * 1024
        const val MaximumEncodedDecisionBytes = 60 * 1024
        const val MaximumDecodedDecisionBytes = 45 * 1024
        const val MaximumEncodedSignatureBytes = 2048
    }
}
