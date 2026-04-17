package com.mirkori.inplacex.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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
) {
    val adsDisabled: Boolean
        get() = adFreePurchased || proSubscriptionActive || proPlusSubscriptionActive

    val autoTableAssistEnabled: Boolean
        get() = proSubscriptionActive || proPlusSubscriptionActive

    val infiniteHintsEnabled: Boolean
        get() = proPlusSubscriptionActive
}

class GameProgressRepository(context: Context) {
    private val helper = GameProgressDbHelper(context.applicationContext)

    fun loadState(nowMs: Long = System.currentTimeMillis()): GameProgressState {
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

    fun consumeCampaignEnergyForLoss(nowMs: Long = System.currentTimeMillis()): GameProgressState {
        return mutate(nowMs) { row ->
            row.copy(campaignEnergy = (row.campaignEnergy - 1).coerceAtLeast(0))
        }
    }

    fun recordCompanyLoss(nowMs: Long = System.currentTimeMillis()): GameProgressState {
        return mutate(nowMs) { row ->
            row.copy(
                campaignEnergy = (row.campaignEnergy - 1).coerceAtLeast(0),
                companyLosses = row.companyLosses + 1,
            )
        }
    }

    fun canStartCampaign(nowMs: Long = System.currentTimeMillis()): Boolean {
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

    private fun consumeResource(
        currentValue: (ProgressRow) -> Int,
        updater: (ProgressRow) -> ProgressRow,
    ): Boolean {
        val db = helper.writableDatabase
        ensureDefaultRow(db)
        val row = applyEnergyRegen(loadRow(db), System.currentTimeMillis())
        if (currentValue(row) <= 0) return false
        writeRow(db, updater(row))
        return true
    }

    private fun spendCoinsAndMutate(costCoins: Int, updater: (ProgressRow) -> ProgressRow): Boolean {
        require(costCoins >= 0) { "costCoins must be >= 0" }
        val db = helper.writableDatabase
        ensureDefaultRow(db)
        val row = applyEnergyRegen(loadRow(db), System.currentTimeMillis())
        if (row.coins < costCoins) return false
        writeRow(db, updater(row.copy(coins = row.coins - costCoins)))
        return true
    }

    private fun mutate(
        nowMs: Long = System.currentTimeMillis(),
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
        val state = ProgressRow.default()
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
            ),
            "${GameProgressDbHelper.COL_ID} = ?",
            arrayOf(GameProgressDbHelper.PROFILE_ID.toString()),
            null,
            null,
            null
        )

        cursor.use {
            if (!it.moveToFirst()) {
                return ProgressRow.default()
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
            )
        }

        companion object {
            fun default(nowMs: Long = System.currentTimeMillis()): ProgressRow {
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
                )
            }
        }
    }
}

internal class GameProgressDbHelper(context: Context) : SQLiteOpenHelper(
    context,
    DB_NAME,
    null,
    DB_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_PROGRESS (
                $COL_ID INTEGER PRIMARY KEY,
                $COL_PLAYER_DISPLAY_NAME TEXT NOT NULL DEFAULT 'Player_7065',
                $COL_GOOGLE_PLAY_SIGNED_IN INTEGER NOT NULL DEFAULT 0,
                $COL_HINT_OPEN_POSITION INTEGER NOT NULL DEFAULT 0,
                $COL_HINT_CHECK_DIGIT INTEGER NOT NULL DEFAULT 0,
                $COL_HINT_CHECK_POSITION INTEGER NOT NULL DEFAULT 0,
                $COL_BOOST_EXTRA_MOVES INTEGER NOT NULL DEFAULT 0,
                $COL_BOOST_EXTRA_TIME INTEGER NOT NULL DEFAULT 0,
                $COL_COINS INTEGER NOT NULL DEFAULT 120,
                $COL_CAMPAIGN_ENERGY INTEGER NOT NULL DEFAULT $MAX_CAMPAIGN_ENERGY,
                $COL_ENERGY_LAST_UPDATE_MS INTEGER NOT NULL DEFAULT 0,
                $COL_MATCHES_PLAYED INTEGER NOT NULL DEFAULT 0,
                $COL_MATCHES_WON INTEGER NOT NULL DEFAULT 0,
                $COL_HIGHEST_UNLOCKED_CAMPAIGN_LEVEL INTEGER NOT NULL DEFAULT 1,
                $COL_TOTAL_CAMPAIGN_RATING INTEGER NOT NULL DEFAULT 0,
                $COL_PVE_WINS INTEGER NOT NULL DEFAULT 0,
                $COL_PVE_LOSSES INTEGER NOT NULL DEFAULT 0,
                $COL_PVP_WINS INTEGER NOT NULL DEFAULT 0,
                $COL_PVP_LOSSES INTEGER NOT NULL DEFAULT 0,
                $COL_COMPANY_WINS INTEGER NOT NULL DEFAULT 0,
                $COL_COMPANY_LOSSES INTEGER NOT NULL DEFAULT 0,
                $COL_AD_FREE_PURCHASED INTEGER NOT NULL DEFAULT 0,
                $COL_PRO_SUBSCRIPTION_ACTIVE INTEGER NOT NULL DEFAULT 0,
                $COL_PRO_PLUS_SUBSCRIPTION_ACTIVE INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE $TABLE_CAMPAIGN_PROGRESS (
                $COL_CAMPAIGN_LEVEL_NUMBER INTEGER PRIMARY KEY,
                $COL_CAMPAIGN_BEST_BACKEND_RATING INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        createPlatformTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_HINT_OPEN_POSITION INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_HINT_CHECK_DIGIT INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_HINT_CHECK_POSITION INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE $TABLE_PROGRESS SET $COL_HINT_OPEN_POSITION = COALESCE($COL_HINT_COUNT_LEGACY, 0)")
            db.execSQL("UPDATE $TABLE_PROGRESS SET $COL_HINT_CHECK_DIGIT = COALESCE($COL_HINT_COUNT_LEGACY, 0)")
            db.execSQL("UPDATE $TABLE_PROGRESS SET $COL_HINT_CHECK_POSITION = COALESCE($COL_HINT_COUNT_LEGACY, 0)")
        }

        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_BOOST_EXTRA_MOVES INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_BOOST_EXTRA_TIME INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_HIGHEST_UNLOCKED_CAMPAIGN_LEVEL INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_TOTAL_CAMPAIGN_RATING INTEGER NOT NULL DEFAULT 0")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_CAMPAIGN_PROGRESS (
                    $COL_CAMPAIGN_LEVEL_NUMBER INTEGER PRIMARY KEY,
                    $COL_CAMPAIGN_BEST_BACKEND_RATING INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }

        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_COINS INTEGER NOT NULL DEFAULT 120")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_CAMPAIGN_ENERGY INTEGER NOT NULL DEFAULT $MAX_CAMPAIGN_ENERGY")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_ENERGY_LAST_UPDATE_MS INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_PVE_WINS INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_PVE_LOSSES INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_PVP_WINS INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_PVP_LOSSES INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_COMPANY_WINS INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_COMPANY_LOSSES INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE $TABLE_PROGRESS SET $COL_ENERGY_LAST_UPDATE_MS = ${System.currentTimeMillis()}")
        }

        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_PLAYER_DISPLAY_NAME TEXT NOT NULL DEFAULT 'Player_7065'")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_GOOGLE_PLAY_SIGNED_IN INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_AD_FREE_PURCHASED INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_PRO_SUBSCRIPTION_ACTIVE INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_PRO_PLUS_SUBSCRIPTION_ACTIVE INTEGER NOT NULL DEFAULT 0")
        }

        if (oldVersion < 6) {
            createPlatformTables(db)
        }
    }

    private fun createPlatformTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLAYER_PROFILE (
                profile_id INTEGER PRIMARY KEY,
                player_id TEXT NOT NULL,
                installation_id TEXT NOT NULL,
                display_name TEXT NOT NULL DEFAULT 'Player_7065',
                avatar_url TEXT,
                auth_provider TEXT NOT NULL DEFAULT 'guest',
                is_guest INTEGER NOT NULL DEFAULT 1,
                is_online INTEGER NOT NULL DEFAULT 0,
                locale TEXT,
                region_code TEXT,
                cloud_revision INTEGER NOT NULL DEFAULT 0,
                last_seen_at INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_IDENTITY_LINKS (
                id TEXT PRIMARY KEY,
                provider TEXT NOT NULL,
                provider_subject TEXT NOT NULL,
                player_id TEXT NOT NULL,
                display_name TEXT,
                email TEXT,
                is_primary INTEGER NOT NULL DEFAULT 0,
                linked_at INTEGER NOT NULL DEFAULT 0,
                last_refreshed_at INTEGER NOT NULL DEFAULT 0,
                UNIQUE(provider, provider_subject)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SOCIAL_RELATIONSHIPS (
                id TEXT PRIMARY KEY,
                player_id TEXT NOT NULL,
                target_player_id TEXT NOT NULL,
                target_display_name TEXT NOT NULL,
                relationship_type TEXT NOT NULL,
                status TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT 'local',
                note TEXT,
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                UNIQUE(player_id, target_player_id, relationship_type)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ONLINE_ROOMS (
                room_id TEXT PRIMARY KEY,
                game_slug TEXT NOT NULL,
                room_name TEXT NOT NULL,
                invite_code TEXT,
                visibility TEXT NOT NULL,
                host_player_id TEXT NOT NULL,
                status TEXT NOT NULL,
                max_members INTEGER NOT NULL DEFAULT 2,
                config_json TEXT NOT NULL DEFAULT '{}',
                server_revision INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ONLINE_ROOM_MEMBERS (
                id TEXT PRIMARY KEY,
                room_id TEXT NOT NULL,
                player_id TEXT NOT NULL,
                display_name TEXT NOT NULL,
                role TEXT NOT NULL,
                status TEXT NOT NULL,
                seat_no INTEGER,
                joined_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                UNIQUE(room_id, player_id)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ONLINE_MATCHES (
                match_id TEXT PRIMARY KEY,
                room_id TEXT,
                game_slug TEXT NOT NULL,
                local_player_id TEXT NOT NULL,
                opponent_player_id TEXT,
                status TEXT NOT NULL,
                mode TEXT NOT NULL,
                code_length INTEGER NOT NULL,
                allow_duplicates INTEGER NOT NULL DEFAULT 0,
                attempt_limit INTEGER NOT NULL,
                turn_time_limit_sec INTEGER,
                player_secret_hash TEXT,
                opponent_secret_hash TEXT,
                local_result TEXT,
                remote_result TEXT,
                started_at INTEGER NOT NULL DEFAULT 0,
                finished_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ONLINE_MATCH_TURNS (
                id TEXT PRIMARY KEY,
                match_id TEXT NOT NULL,
                player_id TEXT NOT NULL,
                turn_index INTEGER NOT NULL,
                guess TEXT NOT NULL,
                score INTEGER NOT NULL,
                server_acknowledged INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL DEFAULT 0,
                UNIQUE(match_id, player_id, turn_index)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_SYNC_QUEUE (
                id TEXT PRIMARY KEY,
                scope TEXT NOT NULL,
                entity_id TEXT,
                operation_type TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                endpoint_path TEXT NOT NULL,
                method TEXT NOT NULL,
                idempotency_key TEXT,
                status TEXT NOT NULL,
                retry_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                created_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_social_relationships_player_status
            ON $TABLE_SOCIAL_RELATIONSHIPS(player_id, status)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_online_rooms_status_updated
            ON $TABLE_ONLINE_ROOMS(status, updated_at)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_online_match_turns_match_created
            ON $TABLE_ONLINE_MATCH_TURNS(match_id, created_at)
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS idx_sync_queue_status_created
            ON $TABLE_SYNC_QUEUE(status, created_at)
            """.trimIndent()
        )
    }

    companion object {
        private const val DB_NAME = "inplacex_progress.db"
        private const val DB_VERSION = 6

        const val TABLE_PROGRESS = "game_progress"
        const val TABLE_CAMPAIGN_PROGRESS = "campaign_progress"
        const val TABLE_PLAYER_PROFILE = "player_profile"
        const val TABLE_IDENTITY_LINKS = "identity_links"
        const val TABLE_SOCIAL_RELATIONSHIPS = "social_relationships"
        const val TABLE_ONLINE_ROOMS = "online_rooms"
        const val TABLE_ONLINE_ROOM_MEMBERS = "online_room_members"
        const val TABLE_ONLINE_MATCHES = "online_matches"
        const val TABLE_ONLINE_MATCH_TURNS = "online_match_turns"
        const val TABLE_SYNC_QUEUE = "sync_queue"
        const val COL_ID = "id"
        const val COL_PLAYER_DISPLAY_NAME = "player_display_name"
        const val COL_GOOGLE_PLAY_SIGNED_IN = "google_play_signed_in"
        const val COL_HINT_COUNT_LEGACY = "hint_count"
        const val COL_HINT_OPEN_POSITION = "hint_open_position"
        const val COL_HINT_CHECK_DIGIT = "hint_check_digit"
        const val COL_HINT_CHECK_POSITION = "hint_check_position"
        const val COL_BOOST_EXTRA_MOVES = "boost_extra_moves"
        const val COL_BOOST_EXTRA_TIME = "boost_extra_time"
        const val COL_COINS = "coins"
        const val COL_CAMPAIGN_ENERGY = "campaign_energy"
        const val COL_ENERGY_LAST_UPDATE_MS = "energy_last_update_ms"
        const val COL_MATCHES_PLAYED = "matches_played"
        const val COL_MATCHES_WON = "matches_won"
        const val COL_HIGHEST_UNLOCKED_CAMPAIGN_LEVEL = "highest_unlocked_campaign_level"
        const val COL_TOTAL_CAMPAIGN_RATING = "total_campaign_rating"
        const val COL_PVE_WINS = "pve_wins"
        const val COL_PVE_LOSSES = "pve_losses"
        const val COL_PVP_WINS = "pvp_wins"
        const val COL_PVP_LOSSES = "pvp_losses"
        const val COL_COMPANY_WINS = "company_wins"
        const val COL_COMPANY_LOSSES = "company_losses"
        const val COL_AD_FREE_PURCHASED = "ad_free_purchased"
        const val COL_PRO_SUBSCRIPTION_ACTIVE = "pro_subscription_active"
        const val COL_PRO_PLUS_SUBSCRIPTION_ACTIVE = "pro_plus_subscription_active"
        const val COL_CAMPAIGN_LEVEL_NUMBER = "level_number"
        const val COL_CAMPAIGN_BEST_BACKEND_RATING = "best_backend_rating"
        const val PROFILE_ID = 1
    }
}

private const val MAX_CAMPAIGN_ENERGY = 5
private const val ENERGY_REFILL_MINUTES = 20
