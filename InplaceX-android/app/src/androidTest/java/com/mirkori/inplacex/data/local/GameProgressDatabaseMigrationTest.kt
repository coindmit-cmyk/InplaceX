package com.mirkori.inplacex.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GameProgressDatabaseMigrationTest {
    @Test
    fun freshDatabaseCreatesCompleteV7Schema() {
        withIsolatedDatabase("fresh_v7", ::fixedNowMs) { context, config ->
            GameProgressDatabase(context, config).use { helper ->
                val db = helper.writableDatabase

                assertEquals(CURRENT_DATABASE_VERSION, db.version)
                assertRequiredTables(db)
            }
        }
    }

    @Test
    fun migrationFromV1ToV7PreservesSentinelData() {
        assertMigrationFrom(1)
    }

    @Test
    fun migrationFromV2ToV7PreservesSentinelData() {
        assertMigrationFrom(2)
    }

    @Test
    fun migrationFromV3ToV7PreservesSentinelData() {
        assertMigrationFrom(3)
    }

    @Test
    fun migrationFromV4ToV7PreservesSentinelData() {
        assertMigrationFrom(4)
    }

    @Test
    fun migrationFromV5ToV7PreservesSentinelData() {
        assertMigrationFrom(5)
    }

    @Test
    fun migrationFromV6ToV7PreservesSentinelData() {
        assertMigrationFrom(6)
    }

    private fun assertMigrationFrom(oldVersion: Int) {
        withIsolatedDatabase("migration_v$oldVersion", ::fixedNowMs) { context, config ->
            createLegacyDatabase(
                path = context.getDatabasePath(config.databaseName).absolutePath,
                version = oldVersion,
            )

            GameProgressDatabase(context, config).use { helper ->
                val db = helper.writableDatabase

                assertEquals(CURRENT_DATABASE_VERSION, db.version)
                assertRequiredTables(db)
                assertProgressSentinels(db, oldVersion)
                assertCampaignSentinels(db, oldVersion)
            }
        }
    }

    private fun createLegacyDatabase(path: String, version: Int) {
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(createLegacyProgressTableSql(version))
            db.insertOrThrow(
                GameProgressDatabase.TABLE_PROGRESS,
                null,
                legacyProgressValues(version),
            )

            if (version >= 3) {
                db.execSQL(
                    """
                    CREATE TABLE ${GameProgressDatabase.TABLE_CAMPAIGN_PROGRESS} (
                        ${GameProgressDatabase.COL_CAMPAIGN_LEVEL_NUMBER} INTEGER PRIMARY KEY,
                        ${GameProgressDatabase.COL_CAMPAIGN_BEST_BACKEND_RATING} INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO ${GameProgressDatabase.TABLE_CAMPAIGN_PROGRESS} (
                        ${GameProgressDatabase.COL_CAMPAIGN_LEVEL_NUMBER},
                        ${GameProgressDatabase.COL_CAMPAIGN_BEST_BACKEND_RATING}
                    ) VALUES ($SENTINEL_LEVEL, $SENTINEL_RATING)
                    """.trimIndent(),
                )
            }

            db.version = version
        }
    }

    private fun createLegacyProgressTableSql(version: Int): String {
        val columns = mutableListOf(
            "${GameProgressDatabase.COL_ID} INTEGER PRIMARY KEY",
            "${GameProgressDatabase.COL_MATCHES_PLAYED} INTEGER NOT NULL DEFAULT 0",
            "${GameProgressDatabase.COL_MATCHES_WON} INTEGER NOT NULL DEFAULT 0",
        )

        if (version == 1) {
            columns += "${GameProgressDatabase.COL_HINT_COUNT_LEGACY} INTEGER NOT NULL DEFAULT 0"
        }
        if (version >= 2) {
            columns += "${GameProgressDatabase.COL_HINT_OPEN_POSITION} INTEGER NOT NULL DEFAULT 0"
            columns += "${GameProgressDatabase.COL_HINT_CHECK_DIGIT} INTEGER NOT NULL DEFAULT 0"
            columns += "${GameProgressDatabase.COL_HINT_CHECK_POSITION} INTEGER NOT NULL DEFAULT 0"
        }
        if (version >= 3) {
            columns += "${GameProgressDatabase.COL_BOOST_EXTRA_MOVES} INTEGER NOT NULL DEFAULT 0"
            columns += "${GameProgressDatabase.COL_BOOST_EXTRA_TIME} INTEGER NOT NULL DEFAULT 0"
            columns += "${GameProgressDatabase.COL_HIGHEST_UNLOCKED_CAMPAIGN_LEVEL} INTEGER NOT NULL DEFAULT 1"
            columns += "${GameProgressDatabase.COL_TOTAL_CAMPAIGN_RATING} INTEGER NOT NULL DEFAULT 0"
        }
        if (version >= 4) {
            columns += "${GameProgressDatabase.COL_COINS} INTEGER NOT NULL DEFAULT 120"
            columns += "${GameProgressDatabase.COL_CAMPAIGN_ENERGY} INTEGER NOT NULL DEFAULT $MAX_CAMPAIGN_ENERGY"
            columns += "${GameProgressDatabase.COL_ENERGY_LAST_UPDATE_MS} INTEGER NOT NULL DEFAULT 0"
            columns += "${GameProgressDatabase.COL_PVE_WINS} INTEGER NOT NULL DEFAULT 0"
            columns += "${GameProgressDatabase.COL_PVE_LOSSES} INTEGER NOT NULL DEFAULT 0"
            columns += "${GameProgressDatabase.COL_PVP_WINS} INTEGER NOT NULL DEFAULT 0"
            columns += "${GameProgressDatabase.COL_PVP_LOSSES} INTEGER NOT NULL DEFAULT 0"
            columns += "${GameProgressDatabase.COL_COMPANY_WINS} INTEGER NOT NULL DEFAULT 0"
            columns += "${GameProgressDatabase.COL_COMPANY_LOSSES} INTEGER NOT NULL DEFAULT 0"
        }
        if (version >= 5) {
            columns += "${GameProgressDatabase.COL_PLAYER_DISPLAY_NAME} TEXT NOT NULL DEFAULT 'Player_7065'"
            columns += "${GameProgressDatabase.COL_GOOGLE_PLAY_SIGNED_IN} INTEGER NOT NULL DEFAULT 0"
            columns += "${GameProgressDatabase.COL_AD_FREE_PURCHASED} INTEGER NOT NULL DEFAULT 0"
            columns += "${GameProgressDatabase.COL_PRO_SUBSCRIPTION_ACTIVE} INTEGER NOT NULL DEFAULT 0"
            columns += "${GameProgressDatabase.COL_PRO_PLUS_SUBSCRIPTION_ACTIVE} INTEGER NOT NULL DEFAULT 0"
        }

        return """
            CREATE TABLE ${GameProgressDatabase.TABLE_PROGRESS} (
                ${columns.joinToString(",\n")}
            )
        """.trimIndent()
    }

    private fun legacyProgressValues(version: Int): ContentValues {
        return ContentValues().apply {
            put(GameProgressDatabase.COL_ID, GameProgressDatabase.PROFILE_ID)
            put(GameProgressDatabase.COL_MATCHES_PLAYED, SENTINEL_MATCHES_PLAYED)
            put(GameProgressDatabase.COL_MATCHES_WON, SENTINEL_MATCHES_WON)

            if (version == 1) {
                put(GameProgressDatabase.COL_HINT_COUNT_LEGACY, SENTINEL_LEGACY_HINTS)
            }
            if (version >= 2) {
                put(GameProgressDatabase.COL_HINT_OPEN_POSITION, SENTINEL_OPEN_POSITION_HINTS)
                put(GameProgressDatabase.COL_HINT_CHECK_DIGIT, SENTINEL_CHECK_DIGIT_HINTS)
                put(GameProgressDatabase.COL_HINT_CHECK_POSITION, SENTINEL_CHECK_POSITION_HINTS)
            }
            if (version >= 3) {
                put(GameProgressDatabase.COL_BOOST_EXTRA_MOVES, SENTINEL_EXTRA_MOVES)
                put(GameProgressDatabase.COL_BOOST_EXTRA_TIME, SENTINEL_EXTRA_TIME)
                put(GameProgressDatabase.COL_HIGHEST_UNLOCKED_CAMPAIGN_LEVEL, SENTINEL_UNLOCKED_LEVEL)
                put(GameProgressDatabase.COL_TOTAL_CAMPAIGN_RATING, SENTINEL_TOTAL_RATING)
            }
            if (version >= 4) {
                put(GameProgressDatabase.COL_COINS, SENTINEL_COINS)
                put(GameProgressDatabase.COL_CAMPAIGN_ENERGY, SENTINEL_ENERGY)
                put(GameProgressDatabase.COL_ENERGY_LAST_UPDATE_MS, SENTINEL_ENERGY_UPDATED_AT)
                put(GameProgressDatabase.COL_PVE_WINS, SENTINEL_PVE_WINS)
                put(GameProgressDatabase.COL_PVE_LOSSES, SENTINEL_PVE_LOSSES)
                put(GameProgressDatabase.COL_PVP_WINS, SENTINEL_PVP_WINS)
                put(GameProgressDatabase.COL_PVP_LOSSES, SENTINEL_PVP_LOSSES)
                put(GameProgressDatabase.COL_COMPANY_WINS, SENTINEL_COMPANY_WINS)
                put(GameProgressDatabase.COL_COMPANY_LOSSES, SENTINEL_COMPANY_LOSSES)
            }
            if (version >= 5) {
                put(GameProgressDatabase.COL_PLAYER_DISPLAY_NAME, SENTINEL_PLAYER_NAME)
                put(GameProgressDatabase.COL_GOOGLE_PLAY_SIGNED_IN, 1)
                put(GameProgressDatabase.COL_AD_FREE_PURCHASED, 1)
                put(GameProgressDatabase.COL_PRO_SUBSCRIPTION_ACTIVE, 1)
                put(GameProgressDatabase.COL_PRO_PLUS_SUBSCRIPTION_ACTIVE, 1)
            }
        }
    }

    private fun assertProgressSentinels(db: SQLiteDatabase, oldVersion: Int) {
        val cursor = db.query(
            GameProgressDatabase.TABLE_PROGRESS,
            null,
            "${GameProgressDatabase.COL_ID} = ?",
            arrayOf(GameProgressDatabase.PROFILE_ID.toString()),
            null,
            null,
            null,
        )

        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals(SENTINEL_MATCHES_PLAYED, it.int(GameProgressDatabase.COL_MATCHES_PLAYED))
            assertEquals(SENTINEL_MATCHES_WON, it.int(GameProgressDatabase.COL_MATCHES_WON))

            val expectedOpenHints = if (oldVersion == 1) SENTINEL_LEGACY_HINTS else SENTINEL_OPEN_POSITION_HINTS
            val expectedDigitHints = if (oldVersion == 1) SENTINEL_LEGACY_HINTS else SENTINEL_CHECK_DIGIT_HINTS
            val expectedPositionHints = if (oldVersion == 1) SENTINEL_LEGACY_HINTS else SENTINEL_CHECK_POSITION_HINTS
            assertEquals(expectedOpenHints, it.int(GameProgressDatabase.COL_HINT_OPEN_POSITION))
            assertEquals(expectedDigitHints, it.int(GameProgressDatabase.COL_HINT_CHECK_DIGIT))
            assertEquals(expectedPositionHints, it.int(GameProgressDatabase.COL_HINT_CHECK_POSITION))

            assertEquals(if (oldVersion >= 3) SENTINEL_EXTRA_MOVES else 0, it.int(GameProgressDatabase.COL_BOOST_EXTRA_MOVES))
            assertEquals(if (oldVersion >= 3) SENTINEL_EXTRA_TIME else 0, it.int(GameProgressDatabase.COL_BOOST_EXTRA_TIME))
            assertEquals(
                if (oldVersion >= 3) SENTINEL_UNLOCKED_LEVEL else 1,
                it.int(GameProgressDatabase.COL_HIGHEST_UNLOCKED_CAMPAIGN_LEVEL),
            )
            assertEquals(
                if (oldVersion >= 3) SENTINEL_TOTAL_RATING else 0,
                it.int(GameProgressDatabase.COL_TOTAL_CAMPAIGN_RATING),
            )

            assertEquals(if (oldVersion >= 4) SENTINEL_COINS else 120, it.int(GameProgressDatabase.COL_COINS))
            assertEquals(
                if (oldVersion >= 4) SENTINEL_ENERGY else MAX_CAMPAIGN_ENERGY,
                it.int(GameProgressDatabase.COL_CAMPAIGN_ENERGY),
            )
            assertEquals(
                if (oldVersion >= 4) SENTINEL_ENERGY_UPDATED_AT else FIXED_NOW_MS,
                it.long(GameProgressDatabase.COL_ENERGY_LAST_UPDATE_MS),
            )
            assertEquals(if (oldVersion >= 4) SENTINEL_PVE_WINS else 0, it.int(GameProgressDatabase.COL_PVE_WINS))
            assertEquals(if (oldVersion >= 4) SENTINEL_PVE_LOSSES else 0, it.int(GameProgressDatabase.COL_PVE_LOSSES))
            assertEquals(if (oldVersion >= 4) SENTINEL_PVP_WINS else 0, it.int(GameProgressDatabase.COL_PVP_WINS))
            assertEquals(if (oldVersion >= 4) SENTINEL_PVP_LOSSES else 0, it.int(GameProgressDatabase.COL_PVP_LOSSES))
            assertEquals(if (oldVersion >= 4) SENTINEL_COMPANY_WINS else 0, it.int(GameProgressDatabase.COL_COMPANY_WINS))
            assertEquals(if (oldVersion >= 4) SENTINEL_COMPANY_LOSSES else 0, it.int(GameProgressDatabase.COL_COMPANY_LOSSES))

            assertEquals(
                if (oldVersion >= 5) SENTINEL_PLAYER_NAME else "Player_7065",
                it.getString(it.getColumnIndexOrThrow(GameProgressDatabase.COL_PLAYER_DISPLAY_NAME)),
            )
            assertEquals(if (oldVersion >= 5) 1 else 0, it.int(GameProgressDatabase.COL_GOOGLE_PLAY_SIGNED_IN))
            assertEquals(if (oldVersion >= 5) 1 else 0, it.int(GameProgressDatabase.COL_AD_FREE_PURCHASED))
            assertEquals(if (oldVersion >= 5) 1 else 0, it.int(GameProgressDatabase.COL_PRO_SUBSCRIPTION_ACTIVE))
            assertEquals(if (oldVersion >= 5) 1 else 0, it.int(GameProgressDatabase.COL_PRO_PLUS_SUBSCRIPTION_ACTIVE))
            assertEquals(0L, it.long(GameProgressDatabase.COL_TEMPORARY_PRO_EXPIRES_AT_MS))
        }
    }

    private fun assertCampaignSentinels(db: SQLiteDatabase, oldVersion: Int) {
        val cursor = db.query(
            GameProgressDatabase.TABLE_CAMPAIGN_PROGRESS,
            null,
            null,
            null,
            null,
            null,
            null,
        )

        cursor.use {
            if (oldVersion < 3) {
                assertFalse(it.moveToFirst())
            } else {
                assertTrue(it.moveToFirst())
                assertEquals(SENTINEL_LEVEL, it.int(GameProgressDatabase.COL_CAMPAIGN_LEVEL_NUMBER))
                assertEquals(SENTINEL_RATING, it.int(GameProgressDatabase.COL_CAMPAIGN_BEST_BACKEND_RATING))
                assertFalse(it.moveToNext())
            }
        }
    }

    private fun assertRequiredTables(db: SQLiteDatabase) {
        val tables = mutableSetOf<String>()
        db.query(
            "sqlite_master",
            arrayOf("name"),
            "type = ?",
            arrayOf("table"),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                tables += cursor.getString(0)
            }
        }

        assertTrue(
            tables.containsAll(
                setOf(
                    GameProgressDatabase.TABLE_PROGRESS,
                    GameProgressDatabase.TABLE_CAMPAIGN_PROGRESS,
                    GameProgressDatabase.TABLE_PLAYER_PROFILE,
                    GameProgressDatabase.TABLE_IDENTITY_LINKS,
                    GameProgressDatabase.TABLE_SOCIAL_RELATIONSHIPS,
                    GameProgressDatabase.TABLE_ONLINE_ROOMS,
                    GameProgressDatabase.TABLE_ONLINE_ROOM_MEMBERS,
                    GameProgressDatabase.TABLE_ONLINE_MATCHES,
                    GameProgressDatabase.TABLE_ONLINE_MATCH_TURNS,
                    GameProgressDatabase.TABLE_SYNC_QUEUE,
                ),
            ),
        )
    }

    private fun android.database.Cursor.int(column: String): Int {
        return getInt(getColumnIndexOrThrow(column))
    }

    private fun android.database.Cursor.long(column: String): Long {
        return getLong(getColumnIndexOrThrow(column))
    }

    private fun fixedNowMs(): Long = FIXED_NOW_MS

    companion object {
        private const val CURRENT_DATABASE_VERSION = 7
        private const val FIXED_NOW_MS = 1_725_000_000_000L
        private const val SENTINEL_ENERGY_UPDATED_AT = FIXED_NOW_MS - 60_000L

        private const val SENTINEL_MATCHES_PLAYED = 31
        private const val SENTINEL_MATCHES_WON = 19
        private const val SENTINEL_LEGACY_HINTS = 7
        private const val SENTINEL_OPEN_POSITION_HINTS = 11
        private const val SENTINEL_CHECK_DIGIT_HINTS = 12
        private const val SENTINEL_CHECK_POSITION_HINTS = 13
        private const val SENTINEL_EXTRA_MOVES = 14
        private const val SENTINEL_EXTRA_TIME = 15
        private const val SENTINEL_UNLOCKED_LEVEL = 9
        private const val SENTINEL_TOTAL_RATING = 44
        private const val SENTINEL_COINS = 456
        private const val SENTINEL_ENERGY = 2
        private const val SENTINEL_PVE_WINS = 16
        private const val SENTINEL_PVE_LOSSES = 17
        private const val SENTINEL_PVP_WINS = 18
        private const val SENTINEL_PVP_LOSSES = 19
        private const val SENTINEL_COMPANY_WINS = 20
        private const val SENTINEL_COMPANY_LOSSES = 21
        private const val SENTINEL_PLAYER_NAME = "Migration Sentinel"
        private const val SENTINEL_LEVEL = 8
        private const val SENTINEL_RATING = 7
    }
}
