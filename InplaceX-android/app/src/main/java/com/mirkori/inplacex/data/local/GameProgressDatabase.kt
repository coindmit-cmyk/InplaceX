package com.mirkori.inplacex.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// Создание SQLite-схемы и версионные миграции находятся отдельно от CRUD репозиториев.
internal open class GameProgressDatabase(
    context: Context,
    private val databaseConfig: LocalDatabaseConfig = LocalDatabaseConfig(),
) : SQLiteOpenHelper(
    context,
    databaseConfig.databaseName,
    null,
    DB_VERSION,
) {
    open override fun onCreate(db: SQLiteDatabase) {
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
                $COL_PRO_PLUS_SUBSCRIPTION_ACTIVE INTEGER NOT NULL DEFAULT 0,
                $COL_TEMPORARY_PRO_EXPIRES_AT_MS INTEGER NOT NULL DEFAULT 0,
                $COL_CAMPAIGN_TUTORIAL_COMPLETED INTEGER NOT NULL DEFAULT 0
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

        createCampaignChapterRewardsTable(db)
        createRetentionRewardClaimsTable(db)

        createPlatformTables(db)
    }

    open override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
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
            db.execSQL("UPDATE $TABLE_PROGRESS SET $COL_ENERGY_LAST_UPDATE_MS = ${databaseConfig.nowMs()}")
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

        if (oldVersion < 7) {
            createPlatformTables(db)
            db.execSQL(
                "ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_TEMPORARY_PRO_EXPIRES_AT_MS INTEGER NOT NULL DEFAULT 0"
            )
        }

        if (oldVersion < 8) {
            createPlatformTables(db)
            createCampaignChapterRewardsTable(db)
        }

        if (oldVersion < 9) {
            db.execSQL(
                "ALTER TABLE $TABLE_PROGRESS ADD COLUMN $COL_CAMPAIGN_TUTORIAL_COMPLETED INTEGER NOT NULL DEFAULT 0"
            )
        }

        if (oldVersion < 10) {
            createRetentionRewardClaimsTable(db)
        }
    }

    private fun createCampaignChapterRewardsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_CAMPAIGN_CHAPTER_REWARDS (
                $COL_CAMPAIGN_CHAPTER_NUMBER INTEGER PRIMARY KEY
            )
            """.trimIndent()
        )
    }

    private fun createRetentionRewardClaimsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_RETENTION_REWARD_CLAIMS (
                $COL_RETENTION_REWARD_TYPE TEXT NOT NULL,
                $COL_RETENTION_PERIOD_KEY TEXT NOT NULL,
                $COL_RETENTION_CLAIMED_AT_MS INTEGER NOT NULL,
                PRIMARY KEY ($COL_RETENTION_REWARD_TYPE, $COL_RETENTION_PERIOD_KEY)
            )
            """.trimIndent()
        )
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
        private const val DB_VERSION = 10

        const val TABLE_PROGRESS = "game_progress"
        const val TABLE_CAMPAIGN_PROGRESS = "campaign_progress"
        const val TABLE_CAMPAIGN_CHAPTER_REWARDS = "campaign_chapter_rewards"
        const val TABLE_RETENTION_REWARD_CLAIMS = "retention_reward_claims"
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
        const val COL_TEMPORARY_PRO_EXPIRES_AT_MS = "temporary_pro_expires_at_ms"
        const val COL_CAMPAIGN_TUTORIAL_COMPLETED = "campaign_tutorial_completed"
        const val COL_CAMPAIGN_LEVEL_NUMBER = "level_number"
        const val COL_CAMPAIGN_BEST_BACKEND_RATING = "best_backend_rating"
        const val COL_CAMPAIGN_CHAPTER_NUMBER = "chapter_number"
        const val COL_RETENTION_REWARD_TYPE = "reward_type"
        const val COL_RETENTION_PERIOD_KEY = "period_key"
        const val COL_RETENTION_CLAIMED_AT_MS = "claimed_at_ms"
        const val PROFILE_ID = 1
    }
}


internal const val MAX_CAMPAIGN_ENERGY = 5
internal const val ENERGY_REFILL_MINUTES = 20
