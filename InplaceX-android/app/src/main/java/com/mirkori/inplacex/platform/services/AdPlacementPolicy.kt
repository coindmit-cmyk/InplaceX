package com.mirkori.inplacex.platform.services

const val GAME_BANNER_SLOT_ID = "game"

object AdPlacementPolicy {
    fun canRequestGameBanner(
        isInGame: Boolean,
        entitlements: MonetizationEntitlements,
    ): Boolean = isInGame && !entitlements.adsDisabled

    fun shouldReserveGameBanner(
        isInGame: Boolean,
        entitlements: MonetizationEntitlements,
        providerAccepted: Boolean,
    ): Boolean =
        canRequestGameBanner(isInGame, entitlements) && providerAccepted
}
