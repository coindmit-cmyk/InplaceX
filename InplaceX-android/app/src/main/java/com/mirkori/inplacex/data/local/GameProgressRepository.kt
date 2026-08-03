package com.mirkori.inplacex.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.mirkori.inplacex.core.campaign.CampaignChapterRewardPolicy
import com.mirkori.inplacex.core.match.RaceRewardPolicy
import com.mirkori.inplacex.core.monetization.TemporaryProPolicy

enum class HintStockType {
    OPEN_POSITION,
    CHECK_DIGIT,
    CHECK_POSITION,
}

enum class BoostStockType {
    EXTRA_MOVES,
    EXTRA_TIME,
}

enum class GameModeStatType {
    PVE_RACE,
    PVP_DUEL,
    COMPANY,
}

enum class MonetizationProductType {
    REMOVE_ADS,
    PRO_SUBSCRIPTION,
    PRO_PLUS_SUBSCRIPTION,
}

data class CampaignLevelProgress(
    val levelNumber: Int,
    val bestBackendRating: Int,
)

data class ModeStats(
    val wins: Int,
    val losses: Int,
)

data class GameProgressState(
    val playerDisplayName: String,
    val googlePlaySignedIn: Boolean,
    val openPositionHints: Int,
    val checkDigitHints: Int,
    val checkPositionHints: Int,
    val extraMovesBoosts: Int,
    val extraTimeBoosts: Int,
    val coins: Int,
    val campaignEnergy: Int,
    val campaignEnergyMax: Int,
    val campaignEnergyRefillMinutes: Int,
    val matchesPlayed: Int,
    val matchesWon: Int,
    val highestUnlockedCampaignLevel: Int,
    val totalCampaignRating: Int,
    val pveStats: ModeStats,
    val pvpStats: ModeStats,
    val companyStats: ModeStats,
    val adFreePurchased: Boolean,
    val proSubscriptionActive: Boolean,
    val proPlusSubscriptionActive: Boolean,
    val temporaryProExpiresAtMs: Long,
) {
    fun temporaryProActiveAt(nowMs: Long): Boolean =
        TemporaryProPolicy.isActive(temporaryProExpiresAtMs, nowMs)

    fun adsDisabledAt(nowMs: Long): Boolean =
        adFreePurchased || proSubscriptionActive || proPlusSubscriptionActive || temporaryProActiveAt(nowMs)

    fun autoTableAssistEnabledAt(nowMs: Long): Boolean =
        proSubscriptionActive || proPlusSubscriptionActive || temporaryProActiveAt(nowMs)

    val adsDisabled: Boolean
        get() = adsDisabledAt(System.currentTimeMillis())

    val autoTableAssistEnabled: Boolean
        get() = autoTableAssistEnabledAt(System.currentTimeMillis())

    val infiniteHintsEnabled: Boolean
        get() = proPlusSubscriptionActive
}

class GameProgressRepository(
    context: Context,
    private val databaseConfig: LocalDatabaseConfig = LocalDatabaseConfig(),
) {
    private val helper = GameProgressDbHelper(context.applicationContext, databaseConfig)

    fun loadState(nowMs: Long = databaseConfig.nowMs()): GameProgressState {
        val db = helper.writableDatabase
        ensureDefaultRow(db)
        val row = loadRow(db)
        val refreshed = applyEnergyRegen(row, nowMs)
        if (refreshed != row) {
            writeRow(db, refreshed)
        }
        return refreshed.toState()
    }

    fun loadCampaignProgress(levelNumber: Int): CampaignLevelProgress {
        val db = helper.readableDatabase
        val cursor = db.query(
            GameProgressDbHelper.TABLE_CAMPAIGN_PROGRESS,
            arrayOf(
                GameProgressDbHelper.COL_CAMPAIGN_LEVEL_NUMBER,
                GameProgressDbHelper.COL_CAMPAIGN_BEST_BACKEND_RATING,
            ),
            "${GameProgressDbHelper.COL_CAMPAIGN_LEVEL_NUMBER} = ?",
            arrayOf(levelNumber.toString()),
            null,
            null,
            null
        )

        cursor.use {
            if (it.moveToFirst()) {
                return CampaignLevelProgress(
                    levelNumber = it.getInt(0),
                    bestBackendRating = it.getInt(1),
                )
            }
        }

        return CampaignLevelProgress(levelNumber = levelNumber, bestBackendRating = 0)
    }

    fun loadCampaignProgressRange(fromLevel: Int, toLevel: Int): List<CampaignLevelProgress> {
        require(fromLevel > 0) { "fromLevel must be > 0" }
        require(toLevel >= fromLevel) { "toLevel must be >= fromLevel" }

        val db = helper.readableDatabase
        val result = mutableMapOf<Int, CampaignLevelProgress>()
        val cursor = db.query(
            GameProgressDbHelper.TABLE_CAMPAIGN_PROGRESS,
            arrayOf(
                GameProgressDbHelper.COL_CAMPAIGN_LEVEL_NUMBER,
                GameProgressDbHelper.COL_CAMPAIGN_BEST_BACKEND_RATING,
            ),
            "${GameProgressDbHelper.COL_CAMPAIGN_LEVEL_NUMBER} BETWEEN ? AND ?",
            arrayOf(fromLevel.toString(), toLevel.toString()),
            null,
            null,
            "${GameProgressDbHelper.COL_CAMPAIGN_LEVEL_NUMBER} ASC"
        )

        cursor.use {
            while (it.moveToNext()) {
                val level = it.getInt(0)
                result[level] = CampaignLevelProgress(
                    levelNumber = level,
                    bestBackendRating = it.getInt(1),
                )
            }
        }

        return (fromLevel..toLevel).map { level ->
            result[level] ?: CampaignLevelProgress(levelNumber = level, bestBackendRating = 0)
        }
    }

    fun addAllHelpers(amount: Int): GameProgressState {
        require(amount > 0) { "amount must be > 0" }
        return mutate { row ->
            row.copy(
                hintOpenPosition = row.hintOpenPosition + amount,
                hintCheckDigit = row.hintCheckDigit + amount,
                hintCheckPosition = row.hintCheckPosition + amount,
                boostExtraMoves = row.boostExtraMoves + amount,
                boostExtraTime = row.boostExtraTime + amount,
            )
        }
    }

    fun clearBoosts(): GameProgressState {
        return mutate { row ->
            row.copy(
                boostExtraMoves = 0,
                boostExtraTime = 0,
            )
        }
    }

    fun consumeHint(type: HintStockType): Boolean = consumeResource(
        currentValue = { row ->
            when (type) {
                HintStockType.OPEN_POSITION -> row.hintOpenPosition
                HintStockType.CHECK_DIGIT -> row.hintCheckDigit
                HintStockType.CHECK_POSITION -> row.hintCheckPosition
            }
        },
        updater = { row ->
            when (type) {
                HintStockType.OPEN_POSITION -> row.copy(hintOpenPosition = row.hintOpenPosition - 1)
                HintStockType.CHECK_DIGIT -> row.copy(hintCheckDigit = row.hintCheckDigit - 1)
                HintStockType.CHECK_POSITION -> row.copy(hintCheckPosition = row.hintCheckPosition - 1)
            }
        }
    )

    fun consumeBoost(type: BoostStockType): Boolean = consumeResource(
        currentValue = { row ->
            when (type) {
                BoostStockType.EXTRA_MOVES -> row.boostExtraMoves
                BoostStockType.EXTRA_TIME -> row.boostExtraTime
            }
        },
        updater = { row ->
            when (type) {
                BoostStockType.EXTRA_MOVES -> row.copy(boostExtraMoves = row.boostExtraMoves - 1)
                BoostStockType.EXTRA_TIME -> row.copy(boostExtraTime = row.boostExtraTime - 1)
            }
        }
    )

    fun buyHint(type: HintStockType, costCoins: Int): Boolean = spendCoinsAndMutate(costCoins) { row ->
        when (type) {
            HintStockType.OPEN_POSITION -> row.copy(hintOpenPosition = row.hintOpenPosition + 1)
            HintStockType.CHECK_DIGIT -> row.copy(hintCheckDigit = row.hintCheckDigit + 1)
            HintStockType.CHECK_POSITION -> row.copy(hintCheckPosition = row.hintCheckPosition + 1)
        }
    }

    fun buyBoost(type: BoostStockType, costCoins: Int): Boolean = spendCoinsAndMutate(costCoins) { row ->
        when (type) {
            BoostStockType.EXTRA_MOVES -> row.copy(boostExtraMoves = row.boostExtraMoves + 1)
            BoostStockType.EXTRA_TIME -> row.copy(boostExtraTime = row.boostExtraTime + 1)
        }
    }

    fun buyCampaignEnergy(costCoins: Int, amount: Int = 1): Boolean = spendCoinsAndMutate(costCoins) { row ->
        row.copy(campaignEnergy = (row.campaignEnergy + amount).coerceAtMost(MAX_CAMPAIGN_ENERGY))
    }

    fun buyTemporaryPro(nowMs: Long = databaseConfig.nowMs()): Boolean {
        val db = helper.writableDatabase
        ensureDefaultRow(db)
        val row = applyEnergyRegen(loadRow(db), nowMs)
        if (row.proSubscriptionActive || row.proPlusSubscriptionActive) return false
        if (row.coins < TemporaryProPolicy.PRICE_COINS) return false

        writeRow(
            db,
            row.copy(
                coins = row.coins - TemporaryProPolicy.PRICE_COINS,
                temporaryProExpiresAtMs = TemporaryProPolicy.extendExpiration(
                    currentExpiresAtMs = row.temporaryProExpiresAtMs,
                    nowMs = nowMs,
                ),
            ),
        )
        return true
    }

    fun addCoins(amount: Int): GameProgressState {
        require(amount >= 0) { "amount must be >= 0" }
        return mutate { row -> row.copy(coins = row.coins + amount) }
    }

    fun grantRewardedCoins(amount: Int): GameProgressState {
        require(amount > 0) { "amount must be > 0" }
        return mutate { row -> row.copy(coins = row.coins + amount) }
    }

    fun signInWithGooglePlay(playerName: String): GameProgressState {
        return mutate { row ->
            row.copy(
                googlePlaySignedIn = true,
                playerDisplayName = playerName,
            )
        }
    }

    fun signOutFromGooglePlay(): GameProgressState {
        return mutate { row ->
            row.copy(
                googlePlaySignedIn = false,
            )
        }
    }

    fun activateProduct(type: MonetizationProductType): GameProgressState {
        return mutate { row ->
            when (type) {
                MonetizationProductType.REMOVE_ADS -> row.copy(adFreePurchased = true)
                MonetizationProductType.PRO_SUBSCRIPTION -> row.copy(proSubscriptionActive = true)
                MonetizationProductType.PRO_PLUS_SUBSCRIPTION -> row.copy(proPlusSubscriptionActive = true)
            }
        }
    }

    fun deactivateProduct(type: MonetizationProductType): GameProgressState {
        return mutate { row ->
            when (type) {
                MonetizationProductType.REMOVE_ADS -> row.copy(adFreePurchased = false)
                MonetizationProductType.PRO_SUBSCRIPTION -> row.copy(proSubscriptionActive = false)
                MonetizationProductType.PRO_PLUS_SUBSCRIPTION -> row.copy(proPlusSubscriptionActive = false)
            }
        }
    }

    fun recordMatchStarted(): GameProgressState {
        return mutate { row -> row.copy(matchesPlayed = row.matchesPlayed + 1) }
    }

    fun recordModeResult(mode: GameModeStatType, won: Boolean): GameProgressState {
        return mutate { row ->
            when (mode) {
                GameModeStatType.PVE_RACE -> row.copy(
                    pveWins = row.pveWins + if (won) 1 else 0,
                    pveLosses = row.pveLosses + if (won) 0 else 1,
                    matchesWon = row.matchesWon + if (won) 1 else 0,
                    coins = row.coins + RaceRewardPolicy.coinsFor(won),
                )

                GameModeStatType.PVP_DUEL -> row.copy(
                    pvpWins = row.pvpWins + if (won) 1 else 0,
                    pvpLosses = row.pvpLosses + if (won) 0 else 1,
                    matchesWon = row.matchesWon + if (won) 1 else 0,
                )

                GameModeStatType.COMPANY -> row.copy(
                    companyWins = row.companyWins + if (won) 1 else 0,
                    companyLosses = row.companyLosses + if (won) 0 else 1,
                    matchesWon = row.matchesWon + if (won) 1 else 0,
                )
            }
        }
    }

    fun consumeCampaignEnergyForLoss(nowMs: Long = databaseConfig.nowMs()): GameProgressState {
        return mutate(nowMs) { row ->
            row.copy(campaignEnergy = (row.campaignEnergy - 1).coerceAtLeast(0))
        }
    }

    fun recordCompanyLoss(nowMs: Long = databaseConfig.nowMs()): GameProgressState {
        return mutate(nowMs) { row ->
            row.copy(
                campaignEnergy = (row.campaignEnergy - 1).coerceAtLeast(0),
                companyLosses = row.companyLosses + 1,
            )
        }
    }

    fun canStartCampaign(nowMs: Long = databaseConfig.nowMs()): Boolean {
        return loadState(nowMs).campaignEnergy > 0
    }

    fun recordCampaignCompletion(levelNumber: Int, backendRating: Int): GameProgressState {
        require(levelNumber > 0) { "levelNumber must be > 0" }
        require(backendRating in 1..10) { "backendRating must be in 1..10" }

        val db = helper.writableDatabase
        ensureDefaultRow(db)
        val previousProgress = loadCampaignProgress(levelNumber)
        val newBest = maxOf(previousProgress.bestBackendRating, backendRating)
        val ratingDelta = newBest - previousProgress.bestBackendRating

        val progressValues = ContentValues().apply {
            put(GameProgressDbHelper.COL_CAMPAIGN_LEVEL_NUMBER, levelNumber)
            put(GameProgressDbHelper.COL_CAMPAIGN_BEST_BACKEND_RATING, newBest)
        }
        db.insertWithOnConflict(
            GameProgressDbHelper.TABLE_CAMPAIGN_PROGRESS,
            null,
            progressValues,
            SQLiteDatabase.CONFLICT_REPLACE,
        )

        return mutate(db = db) { row ->
            row.copy(
                highestUnlockedCampaignLevel = maxOf(row.highestUnlockedCampaignLevel, levelNumber + 1),
                totalCampaignRating = row.totalCampaignRating + ratingDelta,
                coins = row.coins + backendRating,
                companyWins = row.companyWins + 1,
                matchesWon = row.matchesWon + 1,
            )
        }
    }

    fun loadClaimedCampaignChapters(): Set<Int> {
        val db = helper.readableDatabase
        val claimed = linkedSetOf<Int>()
        db.query(
            GameProgressDbHelper.TABLE_CAMPAIGN_CHAPTER_REWARDS,
            arrayOf(GameProgressDbHelper.COL_CAMPAIGN_CHAPTER_NUMBER),
            null,
            null,
            null,
            null,
            "${GameProgressDbHelper.COL_CAMPAIGN_CHAPTER_NUMBER} ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                claimed += cursor.getInt(0)
            }
        }
        return claimed
    }

    fun claimCampaignChapterReward(chapterNumber: Int): GameProgressState? {
        val levels = CampaignChapterRewardPolicy.levelRange(chapterNumber)
        val reward = CampaignChapterRewardPolicy.rewardFor(chapterNumber)
        val db = helper.writableDatabase
        ensureDefaultRow(db)

        db.beginTransaction()
        return try {
            val completedLevels = db.rawQuery(
                """
                SELECT COUNT(*)
                FROM ${GameProgressDbHelper.TABLE_CAMPAIGN_PROGRESS}
                WHERE ${GameProgressDbHelper.COL_CAMPAIGN_LEVEL_NUMBER} BETWEEN ? AND ?
                  AND ${GameProgressDbHelper.COL_CAMPAIGN_BEST_BACKEND_RATING} > 0
                """.trimIndent(),
                arrayOf(levels.first.toString(), levels.last.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
            if (completedLevels != CampaignChapterRewardPolicy.LEVELS_PER_CHAPTER) {
                null
            } else {
                val inserted = db.insertWithOnConflict(
                    GameProgressDbHelper.TABLE_CAMPAIGN_CHAPTER_REWARDS,
                    null,
                    ContentValues().apply {
                        put(GameProgressDbHelper.COL_CAMPAIGN_CHAPTER_NUMBER, chapterNumber)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
                if (inserted == -1L) {
                    null
                } else {
                    val row = applyEnergyRegen(loadRow(db), databaseConfig.nowMs())
                    val updated = row.copy(
                        coins = row.coins + reward.coins,
                        hintOpenPosition = row.hintOpenPosition + reward.openPositionHints,
                        hintCheckDigit = row.hintCheckDigit + reward.checkDigitHints,
                        hintCheckPosition = row.hintCheckPosition + reward.checkPositionHints,
                    )
                    writeRow(db, updated)
                    db.setTransactionSuccessful()
                    updated.toState()
                }
            }
        } finally {
            db.endTransaction()
        }
    }

    private fun consumeResource(
        currentValue: (ProgressRow) -> Int,
        updater: (ProgressRow) -> ProgressRow,
    ): Boolean {
        val db = helper.writableDatabase
        ensureDefaultRow(db)
        val row = applyEnergyRegen(loadRow(db), databaseConfig.nowMs())
        if (currentValue(row) <= 0) return false
        writeRow(db, updater(row))
        return true
    }

    private fun spendCoinsAndMutate(costCoins: Int, updater: (ProgressRow) -> ProgressRow): Boolean {
        require(costCoins >= 0) { "costCoins must be >= 0" }
        val db = helper.writableDatabase
        ensureDefaultRow(db)
        val row = applyEnergyRegen(loadRow(db), databaseConfig.nowMs())
        if (row.coins < costCoins) return false
        writeRow(db, updater(row.copy(coins = row.coins - costCoins)))
        return true
    }

    private fun mutate(
        nowMs: Long = databaseConfig.nowMs(),
        db: SQLiteDatabase = helper.writableDatabase,
        transform: (ProgressRow) -> ProgressRow,
    ): GameProgressState {
        ensureDefaultRow(db)
        val current = applyEnergyRegen(loadRow(db), nowMs)
        val updated = transform(current)
        writeRow(db, updated)
        return updated.toState()
    }

    private fun applyEnergyRegen(row: ProgressRow, nowMs: Long): ProgressRow {
        if (row.campaignEnergy >= MAX_CAMPAIGN_ENERGY) {
            return row.copy(energyLastUpdateMs = nowMs)
        }
        val elapsed = (nowMs - row.energyLastUpdateMs).coerceAtLeast(0L)
        val intervalMs = ENERGY_REFILL_MINUTES * 60_000L
        val restored = (elapsed / intervalMs).toInt()
        if (restored <= 0) return row

        val newEnergy = (row.campaignEnergy + restored).coerceAtMost(MAX_CAMPAIGN_ENERGY)
        val consumedIntervals = minOf(restored, MAX_CAMPAIGN_ENERGY - row.campaignEnergy)
        val newTimestamp = row.energyLastUpdateMs + consumedIntervals * intervalMs
        return row.copy(
            campaignEnergy = newEnergy,
            energyLastUpdateMs = if (newEnergy == MAX_CAMPAIGN_ENERGY) nowMs else newTimestamp,
        )
    }

    private fun ensureDefaultRow(db: SQLiteDatabase) {
        val state = ProgressRow.default(databaseConfig.nowMs())
        val values = ContentValues().apply {
            put(GameProgressDbHelper.COL_ID, GameProgressDbHelper.PROFILE_ID)
            put(GameProgressDbHelper.COL_PLAYER_DISPLAY_NAME, state.playerDisplayName)
            put(GameProgressDbHelper.COL_GOOGLE_PLAY_SIGNED_IN, if (state.googlePlaySignedIn) 1 else 0)
            put(GameProgressDbHelper.COL_HINT_OPEN_POSITION, state.hintOpenPosition)
            put(GameProgressDbHelper.COL_HINT_CHECK_DIGIT, state.hintCheckDigit)
            put(GameProgressDbHelper.COL_HINT_CHECK_POSITION, state.hintCheckPosition)
            put(GameProgressDbHelper.COL_BOOST_EXTRA_MOVES, state.boostExtraMoves)
            put(GameProgressDbHelper.COL_BOOST_EXTRA_TIME, state.boostExtraTime)
            put(GameProgressDbHelper.COL_COINS, state.coins)
            put(GameProgressDbHelper.COL_CAMPAIGN_ENERGY, state.campaignEnergy)
            put(GameProgressDbHelper.COL_ENERGY_LAST_UPDATE_MS, state.energyLastUpdateMs)
            put(GameProgressDbHelper.COL_MATCHES_PLAYED, state.matchesPlayed)
            put(GameProgressDbHelper.COL_MATCHES_WON, state.matchesWon)
            put(GameProgressDbHelper.COL_HIGHEST_UNLOCKED_CAMPAIGN_LEVEL, state.highestUnlockedCampaignLevel)
            put(GameProgressDbHelper.COL_TOTAL_CAMPAIGN_RATING, state.totalCampaignRating)
            put(GameProgressDbHelper.COL_PVE_WINS, state.pveWins)
            put(GameProgressDbHelper.COL_PVE_LOSSES, state.pveLosses)
            put(GameProgressDbHelper.COL_PVP_WINS, state.pvpWins)
            put(GameProgressDbHelper.COL_PVP_LOSSES, state.pvpLosses)
            put(GameProgressDbHelper.COL_COMPANY_WINS, state.companyWins)
            put(GameProgressDbHelper.COL_COMPANY_LOSSES, state.companyLosses)
            put(GameProgressDbHelper.COL_AD_FREE_PURCHASED, if (state.adFreePurchased) 1 else 0)
            put(GameProgressDbHelper.COL_PRO_SUBSCRIPTION_ACTIVE, if (state.proSubscriptionActive) 1 else 0)
            put(GameProgressDbHelper.COL_PRO_PLUS_SUBSCRIPTION_ACTIVE, if (state.proPlusSubscriptionActive) 1 else 0)
            put(GameProgressDbHelper.COL_TEMPORARY_PRO_EXPIRES_AT_MS, state.temporaryProExpiresAtMs)
        }
        db.insertWithOnConflict(
            GameProgressDbHelper.TABLE_PROGRESS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    private fun loadRow(db: SQLiteDatabase): ProgressRow {
        val cursor = db.query(
            GameProgressDbHelper.TABLE_PROGRESS,
            arrayOf(
                GameProgressDbHelper.COL_HINT_OPEN_POSITION,
                GameProgressDbHelper.COL_PLAYER_DISPLAY_NAME,
                GameProgressDbHelper.COL_GOOGLE_PLAY_SIGNED_IN,
                GameProgressDbHelper.COL_HINT_CHECK_DIGIT,
                GameProgressDbHelper.COL_HINT_CHECK_POSITION,
                GameProgressDbHelper.COL_BOOST_EXTRA_MOVES,
                GameProgressDbHelper.COL_BOOST_EXTRA_TIME,
                GameProgressDbHelper.COL_COINS,
                GameProgressDbHelper.COL_CAMPAIGN_ENERGY,
                GameProgressDbHelper.COL_ENERGY_LAST_UPDATE_MS,
                GameProgressDbHelper.COL_MATCHES_PLAYED,
                GameProgressDbHelper.COL_MATCHES_WON,
                GameProgressDbHelper.COL_HIGHEST_UNLOCKED_CAMPAIGN_LEVEL,
                GameProgressDbHelper.COL_TOTAL_CAMPAIGN_RATING,
                GameProgressDbHelper.COL_PVE_WINS,
                GameProgressDbHelper.COL_PVE_LOSSES,
                GameProgressDbHelper.COL_PVP_WINS,
                GameProgressDbHelper.COL_PVP_LOSSES,
                GameProgressDbHelper.COL_COMPANY_WINS,
                GameProgressDbHelper.COL_COMPANY_LOSSES,
                GameProgressDbHelper.COL_AD_FREE_PURCHASED,
                GameProgressDbHelper.COL_PRO_SUBSCRIPTION_ACTIVE,
                GameProgressDbHelper.COL_PRO_PLUS_SUBSCRIPTION_ACTIVE,
                GameProgressDbHelper.COL_TEMPORARY_PRO_EXPIRES_AT_MS,
            ),
            "${GameProgressDbHelper.COL_ID} = ?",
            arrayOf(GameProgressDbHelper.PROFILE_ID.toString()),
            null,
            null,
            null
        )

        cursor.use {
            if (!it.moveToFirst()) {
                return ProgressRow.default(databaseConfig.nowMs())
            }
            return ProgressRow(
                hintOpenPosition = it.getInt(0),
                playerDisplayName = it.getString(1) ?: "Guest",
                googlePlaySignedIn = it.getInt(2) != 0,
                hintCheckDigit = it.getInt(3),
                hintCheckPosition = it.getInt(4),
                boostExtraMoves = it.getInt(5),
                boostExtraTime = it.getInt(6),
                coins = it.getInt(7),
                campaignEnergy = it.getInt(8),
                energyLastUpdateMs = it.getLong(9),
                matchesPlayed = it.getInt(10),
                matchesWon = it.getInt(11),
                highestUnlockedCampaignLevel = it.getInt(12),
                totalCampaignRating = it.getInt(13),
                pveWins = it.getInt(14),
                pveLosses = it.getInt(15),
                pvpWins = it.getInt(16),
                pvpLosses = it.getInt(17),
                companyWins = it.getInt(18),
                companyLosses = it.getInt(19),
                adFreePurchased = it.getInt(20) != 0,
                proSubscriptionActive = it.getInt(21) != 0,
                proPlusSubscriptionActive = it.getInt(22) != 0,
                temporaryProExpiresAtMs = it.getLong(23),
            )
        }
    }

    private fun writeRow(db: SQLiteDatabase, row: ProgressRow) {
        val values = ContentValues().apply {
            put(GameProgressDbHelper.COL_ID, GameProgressDbHelper.PROFILE_ID)
            put(GameProgressDbHelper.COL_PLAYER_DISPLAY_NAME, row.playerDisplayName)
            put(GameProgressDbHelper.COL_GOOGLE_PLAY_SIGNED_IN, if (row.googlePlaySignedIn) 1 else 0)
            put(GameProgressDbHelper.COL_HINT_OPEN_POSITION, row.hintOpenPosition)
            put(GameProgressDbHelper.COL_HINT_CHECK_DIGIT, row.hintCheckDigit)
            put(GameProgressDbHelper.COL_HINT_CHECK_POSITION, row.hintCheckPosition)
            put(GameProgressDbHelper.COL_BOOST_EXTRA_MOVES, row.boostExtraMoves)
            put(GameProgressDbHelper.COL_BOOST_EXTRA_TIME, row.boostExtraTime)
            put(GameProgressDbHelper.COL_COINS, row.coins)
            put(GameProgressDbHelper.COL_CAMPAIGN_ENERGY, row.campaignEnergy)
            put(GameProgressDbHelper.COL_ENERGY_LAST_UPDATE_MS, row.energyLastUpdateMs)
            put(GameProgressDbHelper.COL_MATCHES_PLAYED, row.matchesPlayed)
            put(GameProgressDbHelper.COL_MATCHES_WON, row.matchesWon)
            put(GameProgressDbHelper.COL_HIGHEST_UNLOCKED_CAMPAIGN_LEVEL, row.highestUnlockedCampaignLevel)
            put(GameProgressDbHelper.COL_TOTAL_CAMPAIGN_RATING, row.totalCampaignRating)
            put(GameProgressDbHelper.COL_PVE_WINS, row.pveWins)
            put(GameProgressDbHelper.COL_PVE_LOSSES, row.pveLosses)
            put(GameProgressDbHelper.COL_PVP_WINS, row.pvpWins)
            put(GameProgressDbHelper.COL_PVP_LOSSES, row.pvpLosses)
            put(GameProgressDbHelper.COL_COMPANY_WINS, row.companyWins)
            put(GameProgressDbHelper.COL_COMPANY_LOSSES, row.companyLosses)
            put(GameProgressDbHelper.COL_AD_FREE_PURCHASED, if (row.adFreePurchased) 1 else 0)
            put(GameProgressDbHelper.COL_PRO_SUBSCRIPTION_ACTIVE, if (row.proSubscriptionActive) 1 else 0)
            put(GameProgressDbHelper.COL_PRO_PLUS_SUBSCRIPTION_ACTIVE, if (row.proPlusSubscriptionActive) 1 else 0)
            put(GameProgressDbHelper.COL_TEMPORARY_PRO_EXPIRES_AT_MS, row.temporaryProExpiresAtMs)
        }
        db.insertWithOnConflict(
            GameProgressDbHelper.TABLE_PROGRESS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private data class ProgressRow(
        val playerDisplayName: String,
        val googlePlaySignedIn: Boolean,
        val hintOpenPosition: Int,
        val hintCheckDigit: Int,
        val hintCheckPosition: Int,
        val boostExtraMoves: Int,
        val boostExtraTime: Int,
        val coins: Int,
        val campaignEnergy: Int,
        val energyLastUpdateMs: Long,
        val matchesPlayed: Int,
        val matchesWon: Int,
        val highestUnlockedCampaignLevel: Int,
        val totalCampaignRating: Int,
        val pveWins: Int,
        val pveLosses: Int,
        val pvpWins: Int,
        val pvpLosses: Int,
        val companyWins: Int,
        val companyLosses: Int,
        val adFreePurchased: Boolean,
        val proSubscriptionActive: Boolean,
        val proPlusSubscriptionActive: Boolean,
        val temporaryProExpiresAtMs: Long,
    ) {
        fun toState(): GameProgressState {
            return GameProgressState(
                playerDisplayName = playerDisplayName,
                googlePlaySignedIn = googlePlaySignedIn,
                openPositionHints = hintOpenPosition,
                checkDigitHints = hintCheckDigit,
                checkPositionHints = hintCheckPosition,
                extraMovesBoosts = boostExtraMoves,
                extraTimeBoosts = boostExtraTime,
                coins = coins,
                campaignEnergy = campaignEnergy,
                campaignEnergyMax = MAX_CAMPAIGN_ENERGY,
                campaignEnergyRefillMinutes = ENERGY_REFILL_MINUTES,
                matchesPlayed = matchesPlayed,
                matchesWon = matchesWon,
                highestUnlockedCampaignLevel = highestUnlockedCampaignLevel,
                totalCampaignRating = totalCampaignRating,
                pveStats = ModeStats(wins = pveWins, losses = pveLosses),
                pvpStats = ModeStats(wins = pvpWins, losses = pvpLosses),
                companyStats = ModeStats(wins = companyWins, losses = companyLosses),
                adFreePurchased = adFreePurchased,
                proSubscriptionActive = proSubscriptionActive,
                proPlusSubscriptionActive = proPlusSubscriptionActive,
                temporaryProExpiresAtMs = temporaryProExpiresAtMs,
            )
        }

        companion object {
            fun default(nowMs: Long): ProgressRow {
                return ProgressRow(
                    playerDisplayName = "Player_7065",
                    googlePlaySignedIn = false,
                    hintOpenPosition = 0,
                    hintCheckDigit = 0,
                    hintCheckPosition = 0,
                    boostExtraMoves = 0,
                    boostExtraTime = 0,
                    coins = 120,
                    campaignEnergy = MAX_CAMPAIGN_ENERGY,
                    energyLastUpdateMs = nowMs,
                    matchesPlayed = 0,
                    matchesWon = 0,
                    highestUnlockedCampaignLevel = 1,
                    totalCampaignRating = 0,
                    pveWins = 0,
                    pveLosses = 0,
                    pvpWins = 0,
                    pvpLosses = 0,
                    companyWins = 0,
                    companyLosses = 0,
                    adFreePurchased = false,
                    proSubscriptionActive = false,
                    proPlusSubscriptionActive = false,
                    temporaryProExpiresAtMs = 0L,
                )
            }
        }
    }
}

/**
 * Совместимый фасад над выделенным schema/migration helper.
 *
 * Репозитории и существующие callers продолжают использовать прежнее имя,
 * а SQL-схема и миграции остаются в [GameProgressDatabase].
 */
internal class GameProgressDbHelper(
    context: Context,
    databaseConfig: LocalDatabaseConfig = LocalDatabaseConfig(),
) : GameProgressDatabase(context, databaseConfig) {
    override fun onCreate(db: SQLiteDatabase) {
        super.onCreate(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        super.onUpgrade(db, oldVersion, newVersion)
    }

    companion object {
        const val TABLE_PROGRESS = GameProgressDatabase.TABLE_PROGRESS
        const val TABLE_CAMPAIGN_PROGRESS = GameProgressDatabase.TABLE_CAMPAIGN_PROGRESS
        const val TABLE_CAMPAIGN_CHAPTER_REWARDS = GameProgressDatabase.TABLE_CAMPAIGN_CHAPTER_REWARDS
        const val TABLE_PLAYER_PROFILE = GameProgressDatabase.TABLE_PLAYER_PROFILE
        const val TABLE_IDENTITY_LINKS = GameProgressDatabase.TABLE_IDENTITY_LINKS
        const val TABLE_SOCIAL_RELATIONSHIPS = GameProgressDatabase.TABLE_SOCIAL_RELATIONSHIPS
        const val TABLE_ONLINE_ROOMS = GameProgressDatabase.TABLE_ONLINE_ROOMS
        const val TABLE_ONLINE_ROOM_MEMBERS = GameProgressDatabase.TABLE_ONLINE_ROOM_MEMBERS
        const val TABLE_ONLINE_MATCHES = GameProgressDatabase.TABLE_ONLINE_MATCHES
        const val TABLE_ONLINE_MATCH_TURNS = GameProgressDatabase.TABLE_ONLINE_MATCH_TURNS
        const val TABLE_SYNC_QUEUE = GameProgressDatabase.TABLE_SYNC_QUEUE
        const val COL_ID = GameProgressDatabase.COL_ID
        const val COL_PLAYER_DISPLAY_NAME = GameProgressDatabase.COL_PLAYER_DISPLAY_NAME
        const val COL_GOOGLE_PLAY_SIGNED_IN = GameProgressDatabase.COL_GOOGLE_PLAY_SIGNED_IN
        const val COL_HINT_COUNT_LEGACY = GameProgressDatabase.COL_HINT_COUNT_LEGACY
        const val COL_HINT_OPEN_POSITION = GameProgressDatabase.COL_HINT_OPEN_POSITION
        const val COL_HINT_CHECK_DIGIT = GameProgressDatabase.COL_HINT_CHECK_DIGIT
        const val COL_HINT_CHECK_POSITION = GameProgressDatabase.COL_HINT_CHECK_POSITION
        const val COL_BOOST_EXTRA_MOVES = GameProgressDatabase.COL_BOOST_EXTRA_MOVES
        const val COL_BOOST_EXTRA_TIME = GameProgressDatabase.COL_BOOST_EXTRA_TIME
        const val COL_COINS = GameProgressDatabase.COL_COINS
        const val COL_CAMPAIGN_ENERGY = GameProgressDatabase.COL_CAMPAIGN_ENERGY
        const val COL_ENERGY_LAST_UPDATE_MS = GameProgressDatabase.COL_ENERGY_LAST_UPDATE_MS
        const val COL_MATCHES_PLAYED = GameProgressDatabase.COL_MATCHES_PLAYED
        const val COL_MATCHES_WON = GameProgressDatabase.COL_MATCHES_WON
        const val COL_HIGHEST_UNLOCKED_CAMPAIGN_LEVEL = GameProgressDatabase.COL_HIGHEST_UNLOCKED_CAMPAIGN_LEVEL
        const val COL_TOTAL_CAMPAIGN_RATING = GameProgressDatabase.COL_TOTAL_CAMPAIGN_RATING
        const val COL_PVE_WINS = GameProgressDatabase.COL_PVE_WINS
        const val COL_PVE_LOSSES = GameProgressDatabase.COL_PVE_LOSSES
        const val COL_PVP_WINS = GameProgressDatabase.COL_PVP_WINS
        const val COL_PVP_LOSSES = GameProgressDatabase.COL_PVP_LOSSES
        const val COL_COMPANY_WINS = GameProgressDatabase.COL_COMPANY_WINS
        const val COL_COMPANY_LOSSES = GameProgressDatabase.COL_COMPANY_LOSSES
        const val COL_AD_FREE_PURCHASED = GameProgressDatabase.COL_AD_FREE_PURCHASED
        const val COL_PRO_SUBSCRIPTION_ACTIVE = GameProgressDatabase.COL_PRO_SUBSCRIPTION_ACTIVE
        const val COL_PRO_PLUS_SUBSCRIPTION_ACTIVE = GameProgressDatabase.COL_PRO_PLUS_SUBSCRIPTION_ACTIVE
        const val COL_TEMPORARY_PRO_EXPIRES_AT_MS = GameProgressDatabase.COL_TEMPORARY_PRO_EXPIRES_AT_MS
        const val COL_CAMPAIGN_LEVEL_NUMBER = GameProgressDatabase.COL_CAMPAIGN_LEVEL_NUMBER
        const val COL_CAMPAIGN_BEST_BACKEND_RATING = GameProgressDatabase.COL_CAMPAIGN_BEST_BACKEND_RATING
        const val COL_CAMPAIGN_CHAPTER_NUMBER = GameProgressDatabase.COL_CAMPAIGN_CHAPTER_NUMBER
        const val PROFILE_ID = GameProgressDatabase.PROFILE_ID
    }
}
