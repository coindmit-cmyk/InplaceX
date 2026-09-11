package com.mirkori.inplacex.platform.mirkori

import com.mirkori.inplacex.data.local.GameProgressRepository
import com.mirkori.inplacex.data.local.LocalMirkoriGameDelivery
import com.mirkori.inplacex.data.local.MirkoriDeliveryApplication
import com.mirkori.platform.sdk.PlatformGameEntitlementDelivery
import com.mirkori.platform.sdk.PlatformGameDeliveryAction
import com.mirkori.platform.sdk.PlatformEntitlementKind
import com.mirkori.platform.sdk.PlatformEntitlementType
import com.mirkori.platform.sdk.PlatformIdempotencyKey
import com.mirkori.platform.sdk.PlatformProductKind
import com.mirkori.platform.sdk.PlatformProductOffer

internal data class PreparedMirkoriDeliveryAcknowledgement(
    val idempotencyKey: PlatformIdempotencyKey,
)

internal interface MirkoriGameDeliveryApplier {
    fun prepare(
        accountId: String,
        gamePlayerId: String,
        delivery: PlatformGameEntitlementDelivery,
        productOffer: PlatformProductOffer?,
        newIdempotencyKey: PlatformIdempotencyKey,
    ): PreparedMirkoriDeliveryAcknowledgement?

    fun markAcknowledged(
        deliveryId: String,
        idempotencyKey: PlatformIdempotencyKey,
        acknowledgedAtMs: Long,
    )
}

internal class InplaceXMirkoriGameDeliveryApplier(
    private val progressRepository: GameProgressRepository,
) : MirkoriGameDeliveryApplier {
    override fun prepare(
        accountId: String,
        gamePlayerId: String,
        delivery: PlatformGameEntitlementDelivery,
        productOffer: PlatformProductOffer?,
        newIdempotencyKey: PlatformIdempotencyKey,
    ): PreparedMirkoriDeliveryAcknowledgement? {
        val application = delivery.validatedApplication(productOffer) ?: return null
        val prepared = progressRepository.prepareMirkoriGameDelivery(
            delivery = LocalMirkoriGameDelivery(
                deliveryId = delivery.id,
                accountId = accountId,
                gamePlayerId = gamePlayerId,
                entitlementEventId = delivery.entitlementEventId,
                entitlementId = delivery.entitlementId,
                sequenceNumber = delivery.sequenceNumber,
                action = delivery.action.wireName,
                gameId = delivery.gameId,
                productId = delivery.productId,
                orderId = delivery.orderId,
                entitlementKey = delivery.entitlementKey,
                entitlementKind = delivery.entitlementKind.wireName,
                quantityDelta = delivery.quantityDelta,
                validFromMs = delivery.validFrom.toEpochMilli(),
                expiresAtMs = delivery.expiresAt?.toEpochMilli(),
                correctionQuantity = delivery.correctionQuantity,
                payloadSha256 = delivery.payloadSha256,
                createdAtMs = delivery.createdAt.toEpochMilli(),
                application = application,
            ),
            newAcknowledgementIdempotencyKey = newIdempotencyKey.value,
        )
        return PreparedMirkoriDeliveryAcknowledgement(
            PlatformIdempotencyKey(prepared.acknowledgementIdempotencyKey),
        )
    }

    override fun markAcknowledged(
        deliveryId: String,
        idempotencyKey: PlatformIdempotencyKey,
        acknowledgedAtMs: Long,
    ) {
        progressRepository.markMirkoriGameDeliveryAcknowledged(
            deliveryId = deliveryId,
            acknowledgementIdempotencyKey = idempotencyKey.value,
            acknowledgedAtMs = acknowledgedAtMs,
        )
    }

}

internal fun PlatformGameEntitlementDelivery.validatedApplication(
    productOffer: PlatformProductOffer?,
): MirkoriDeliveryApplication? {
    if (
        gameId != InplaceXGameId ||
        productOffer == null ||
        productOffer.id != productId ||
        productOffer.gameId != gameId
    ) {
        return null
    }
    val expectedType = when (entitlementKind) {
        PlatformEntitlementKind.CONSUMABLE_BALANCE -> PlatformEntitlementType.CONSUMABLE
        PlatformEntitlementKind.DURABLE_ADDON -> PlatformEntitlementType.DURABLE
        PlatformEntitlementKind.TIME_BOUNDED_PRO -> PlatformEntitlementType.TIMED
        PlatformEntitlementKind.PERMANENT_GAME -> return null
    }
    val expectedGrant = productOffer.grants.singleOrNull {
        it.entitlementKey == entitlementKey && it.type == expectedType
    } ?: return null
    if (!matchesExpectedQuantity(expectedGrant.quantity)) return null

    return when (entitlementKind) {
        PlatformEntitlementKind.CONSUMABLE_BALANCE -> {
            if (productOffer.kind != PlatformProductKind.CURRENCY || entitlementKey !in CoinsEntitlementKeys) {
                return null
            }
            MirkoriDeliveryApplication.COINS
        }

        PlatformEntitlementKind.DURABLE_ADDON -> {
            if (
                productOffer.kind != PlatformProductKind.ADDON ||
                entitlementKey != RemoveAdsEntitlementKey ||
                correctionQuantity != 0L
            ) {
                return null
            }
            MirkoriDeliveryApplication.SERVER_ENTITLEMENT_PROJECTION
        }

        PlatformEntitlementKind.TIME_BOUNDED_PRO -> {
            if (
                productOffer.kind != PlatformProductKind.ADDON ||
                entitlementKey !in ProEntitlementKeys ||
                correctionQuantity != 0L
            ) {
                return null
            }
            MirkoriDeliveryApplication.SERVER_ENTITLEMENT_PROJECTION
        }

        PlatformEntitlementKind.PERMANENT_GAME -> null
    }
}

private fun PlatformGameEntitlementDelivery.matchesExpectedQuantity(expectedQuantity: Long): Boolean = try {
    when (action) {
        PlatformGameDeliveryAction.GRANT -> correctionQuantity == 0L && quantityDelta == expectedQuantity
        PlatformGameDeliveryAction.REVOKE ->
            Math.addExact(Math.negateExact(quantityDelta), correctionQuantity) == expectedQuantity
    }
} catch (_: ArithmeticException) {
    false
}

private const val InplaceXGameId = "inplacex"
private const val RemoveAdsEntitlementKey = "ads.disabled"
private val CoinsEntitlementKeys = setOf("coins", "currency.coins")
private val ProEntitlementKeys = setOf("pro.active", "pro-plus.active")
