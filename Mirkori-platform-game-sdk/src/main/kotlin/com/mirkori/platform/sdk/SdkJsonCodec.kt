package com.mirkori.platform.sdk

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    fun createOrderRequest(productId: String, currency: String): String = buildJsonObject {
        put("productId", productId)
        put("currency", currency)
    }.toString()

    fun consumptionRequest(quantity: Long): String = buildJsonObject {
        put("quantity", quantity)
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

    fun productsResponse(body: String): List<PlatformProductOffer> {
        val root = objectBody(body)
        root.requireExactFields("schemaVersion", "products")
        require(root.long("schemaVersion") == 1L)
        return root.array("products", 256).map(::product)
    }

    fun orderResponse(body: String): PlatformOrder = order(objectBody(body))

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

    fun errorCode(body: String): String = runCatching {
        val root = objectBody(body)
        root.requireExactFields("error")
        root.string("error", 128)
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

    private fun JsonObject.credential(name: String, maximum: Int): String =
        string(name, maximum).takeIf { it.none(Char::isWhitespace) } ?: reject()

    private fun JsonObject.long(name: String): Long =
        runCatching { get(name)?.jsonPrimitive?.long }.getOrNull() ?: reject()

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
        const val MaximumResponseBytes = 64 * 1024
    }
}
