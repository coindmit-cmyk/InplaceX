package com.mirkori.inplacex

import com.mirkori.inplacex.data.local.GameProgressState
import com.mirkori.inplacex.data.local.ModeStats

internal fun paidEntitlementTestProgress(
    adFree: Boolean = false,
    pro: Boolean = false,
    proPlus: Boolean = false,
): GameProgressState = GameProgressState(
    playerDisplayName = "Player",
    googlePlaySignedIn = false,
    openPositionHints = 0,
    checkDigitHints = 0,
    checkPositionHints = 0,
    extraMovesBoosts = 0,
    extraTimeBoosts = 0,
    coins = 0,
    campaignEnergy = 1,
    campaignEnergyMax = 5,
    campaignEnergyRefillMinutes = 30,
    matchesPlayed = 0,
    matchesWon = 0,
    highestUnlockedCampaignLevel = 1,
    totalCampaignRating = 0,
    pveStats = ModeStats(0, 0),
    pvpStats = ModeStats(0, 0),
    companyStats = ModeStats(0, 0),
    adFreePurchased = adFree,
    proSubscriptionActive = pro,
    proPlusSubscriptionActive = proPlus,
    temporaryProExpiresAtMs = 0L,
)
