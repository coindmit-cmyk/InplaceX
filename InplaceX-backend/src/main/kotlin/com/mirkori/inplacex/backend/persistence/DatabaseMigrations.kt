package com.mirkori.inplacex.backend.persistence

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import javax.sql.DataSource

data class SqlMigration(
    val version: String,
    val description: String,
    val sql: String,
) {
    private val canonicalSql: String = sql.replace("\r\n", "\n").replace('\r', '\n')
    val checksum: String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalSql.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

object DatabaseMigrations {
    val all: List<SqlMigration> = listOf(
        SqlMigration(
            version = "1",
            description = "create backend persistence",
            sql = readResource("db/migration/V1__create_backend_persistence.sql"),
        ),
        SqlMigration(
            version = "2",
            description = "add identity profile and idempotent save storage",
            sql = readResource("db/migration/V2__add_identity_profile_and_save_storage.sql"),
        ),
        SqlMigration(
            version = "3",
            description = "add Google player identities and auth challenges",
            sql = readResource("db/migration/V3__add_google_player_identities.sql"),
        ),
        SqlMigration(
            version = "4",
            description = "add persistent auth idempotency results",
            sql = readResource("db/migration/V4__add_auth_idempotency_results.sql"),
        ),
        SqlMigration(
            version = "5",
            description = "add durable online state storage",
            sql = readResource("db/migration/V5__add_durable_online_state.sql"),
        ),
        SqlMigration(
            version = "6",
            description = "coordinate online matchmaking commands",
            sql = readResource("db/migration/V6__coordinate_online_matchmaking.sql"),
        ),
        SqlMigration(
            version = "7",
            description = "coordinate private invite commands",
            sql = readResource("db/migration/V7__coordinate_private_invites.sql"),
        ),
        SqlMigration(
            version = "8",
            description = "add online session event replay",
            sql = readResource("db/migration/V8__add_online_session_event_replay.sql"),
        ),
        SqlMigration(
            version = "9",
            description = "add one-time legacy online membership migration",
            sql = readResource("db/migration/V9__add_legacy_online_membership_migration.sql"),
        ),
    )

    private fun readResource(path: String): String = requireNotNull(
        DatabaseMigrations::class.java.classLoader.getResourceAsStream(path),
    ) { "Missing database migration resource: $path" }
        .use { stream -> InputStreamReader(stream, StandardCharsets.UTF_8).readText() }
}

class JdbcMigrationRunner(
    private val migrations: List<SqlMigration> = DatabaseMigrations.all,
    private val allowLegacyChecksumBackfill: Boolean = false,
) {
    fun migrate(dataSource: DataSource) {
        validateMigrationSet()
        dataSource.connection.use { connection ->
            withMigrationLock(connection) {
                ensureHistoryTable(connection)
                val applied = readHistory(connection)
                validateAppliedHistory(connection, applied, backfillChecksums = true, requireComplete = false)
                migrations.filterNot { it.version in applied }.forEach { migration ->
                    applyMigration(connection = connection, migration = migration)
                }
            }
        }
    }

    fun verify(dataSource: DataSource) {
        validateMigrationSet()
        dataSource.connection.use { connection ->
            val applied = readHistory(connection)
            validateAppliedHistory(connection, applied, backfillChecksums = false, requireComplete = true)
        }
    }

    private fun validateMigrationSet() {
        require(migrations.isNotEmpty()) { "At least one database migration is required" }
        require(migrations.map(SqlMigration::version).distinct().size == migrations.size) {
            "Database migration versions must be unique"
        }
        migrations.forEach { migration ->
            require(migration.version.matches(MigrationVersionPattern)) { "Invalid database migration version" }
            require(migration.description.length in 1..255 && migration.description.trim() == migration.description)
            require(migration.sql.isNotBlank())
        }
    }

    private fun ensureHistoryTable(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE IF NOT EXISTS inplacex_schema_history (
                    version VARCHAR(50) PRIMARY KEY,
                    description VARCHAR(255) NOT NULL,
                    checksum VARCHAR(64),
                    installed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """.trimIndent(),
            )
            statement.execute(
                "ALTER TABLE inplacex_schema_history ADD COLUMN IF NOT EXISTS checksum VARCHAR(64)",
            )
        }
    }

    private fun readHistory(connection: Connection): Map<String, AppliedMigration> = connection.prepareStatement(
        "SELECT version, description, checksum FROM inplacex_schema_history",
    ).use { statement ->
        statement.executeQuery().use { resultSet ->
            buildMap {
                while (resultSet.next()) {
                    put(
                        resultSet.getString("version"),
                        AppliedMigration(
                            description = resultSet.getString("description"),
                            checksum = resultSet.getString("checksum"),
                        ),
                    )
                }
            }
        }
    }

    private fun validateAppliedHistory(
        connection: Connection,
        applied: Map<String, AppliedMigration>,
        backfillChecksums: Boolean,
        requireComplete: Boolean,
    ) {
        val expectedByVersion = migrations.associateBy(SqlMigration::version)
        require(applied.keys.all(expectedByVersion::containsKey)) {
            "Database contains a migration version unknown to this backend artifact"
        }
        val missingChecksums = applied.filterValues { it.checksum == null }.keys
        if (missingChecksums.isNotEmpty() && backfillChecksums) {
            require(allowLegacyChecksumBackfill) {
                "Legacy migration checksum baseline requires the explicit one-time acknowledgement"
            }
            validateKnownLegacySchema(connection, applied.keys, missingChecksums)
        }
        applied.forEach { (version, installed) ->
            val expected = requireNotNull(expectedByVersion[version])
            require(installed.description == expected.description) {
                "Database migration $version description does not match the backend artifact"
            }
            if (installed.checksum != null || !backfillChecksums) {
                require(installed.checksum == expected.checksum) {
                    "Database migration $version checksum does not match the backend artifact"
                }
            }
        }
        if (missingChecksums.isNotEmpty() && backfillChecksums) {
            backfillLegacyChecksums(connection, missingChecksums, expectedByVersion)
        }
        if (requireComplete) {
            require(applied.keys == expectedByVersion.keys) { "Database migrations are incomplete" }
        }
    }

    private fun backfillLegacyChecksums(
        connection: Connection,
        missingChecksums: Set<String>,
        expectedByVersion: Map<String, SqlMigration>,
    ) {
        val previousAutoCommit = connection.autoCommit
        require(previousAutoCommit) { "Legacy checksum backfill requires an idle JDBC connection" }
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                "UPDATE inplacex_schema_history SET checksum = ? WHERE version = ? AND checksum IS NULL",
            ).use { statement ->
                missingChecksums.sortedBy(String::toLong).forEach { version ->
                    statement.setString(1, requireNotNull(expectedByVersion[version]).checksum)
                    statement.setString(2, version)
                    require(statement.executeUpdate() == 1) {
                        "Database migration $version checksum backfill raced"
                    }
                }
            }
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private fun <T> withMigrationLock(connection: Connection, block: () -> T): T {
        val postgres = connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)
        if (!postgres) return block()
        val deadlineNanos = System.nanoTime() + MigrationLockTimeoutNanos
        var acquired = false
        while (!acquired) {
            connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use { statement ->
                statement.setLong(1, MigrationAdvisoryLockId)
                statement.executeQuery().use { result ->
                    acquired = result.next() && result.getBoolean(1)
                }
            }
            if (!acquired) {
                require(System.nanoTime() < deadlineNanos) {
                    "Timed out waiting for the database migration lock"
                }
                try {
                    Thread.sleep(MigrationLockPollMillis)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("Interrupted while waiting for the database migration lock", interrupted)
                }
            }
        }
        return try {
            block()
        } finally {
            connection.prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
                statement.setLong(1, MigrationAdvisoryLockId)
                statement.executeQuery().use { result ->
                    require(result.next() && result.getBoolean(1)) { "Database migration lock ownership was lost" }
                }
            }
        }
    }

    private fun validateKnownLegacySchema(
        connection: Connection,
        appliedVersions: Set<String>,
        missingChecksumVersions: Set<String>,
    ) {
        require(missingChecksumVersions.isNotEmpty())
        require(appliedVersions == LegacyV1ToV8 || appliedVersions == LegacyV1ToV9) {
            "Legacy checksum baseline requires an exact known migration history"
        }
        if (connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)) {
            val expected = if (appliedVersions == LegacyV1ToV8) {
                LegacyPostgresV1ToV8SchemaSha256
            } else {
                LegacyPostgresV1ToV9SchemaSha256
            }
            val actual = postgresSchemaFingerprint(connection)
            require(actual == expected) {
                "Legacy PostgreSQL schema fingerprint does not match the exact reviewed baseline (actual=$actual)"
            }
            return
        }
        val metadata = connection.metaData
        val schema = connection.schema
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Legacy schema gate requires an explicit current schema")
        val columnsByTable = buildMap<String, MutableSet<String>> {
            metadata.getColumns(connection.catalog, schema, "%", "%").use { result ->
                while (result.next()) {
                    val table = result.getString("TABLE_NAME").lowercase()
                    val column = result.getString("COLUMN_NAME").lowercase()
                    getOrPut(table) { mutableSetOf() }.add(column)
                }
            }
        }
        appliedVersions.forEach { version ->
            KnownLegacyColumnsByVersion[version].orEmpty().forEach { (table, requiredColumns) ->
                val actualColumns = columnsByTable[table].orEmpty()
                require(actualColumns.containsAll(requiredColumns)) {
                    "Legacy schema gate failed for migration $version table $table"
                }
            }
            KnownLegacyIndexesByVersion[version].orEmpty().forEach { (table, requiredIndexes) ->
                val actualIndexes = buildSet {
                    listOf(table, table.uppercase()).forEach { tablePattern ->
                        runCatching {
                            metadata.getIndexInfo(connection.catalog, schema, tablePattern, false, false).use { result ->
                                while (result.next()) result.getString("INDEX_NAME")?.lowercase()?.let(::add)
                            }
                        }
                    }
                }
                require(actualIndexes.containsAll(requiredIndexes)) {
                    "Legacy schema gate failed for migration $version indexes on $table"
                }
            }
        }
    }

    private fun postgresSchemaFingerprint(connection: Connection): String {
        val canonical = connection.prepareStatement(PostgresSchemaFingerprintSql).use { statement ->
            statement.executeQuery().use { result ->
                require(result.next()) { "PostgreSQL schema fingerprint query returned no row" }
                result.getString(1) ?: ""
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun applyMigration(connection: Connection, migration: SqlMigration) {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            migration.sql
                .split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach { statementSql ->
                    connection.createStatement().use { statement -> statement.execute(statementSql) }
                }
            connection.prepareStatement(
                "INSERT INTO inplacex_schema_history(version, description, checksum) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, migration.version)
                statement.setString(2, migration.description)
                statement.setString(3, migration.checksum)
                statement.executeUpdate()
            }
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw IllegalStateException("Failed database migration ${migration.version}: ${migration.description}", error)
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private data class AppliedMigration(val description: String, val checksum: String?)

    private companion object {
        const val MigrationAdvisoryLockId = 0x494e50584d494752L
        const val MigrationLockTimeoutNanos = 30_000_000_000L
        const val MigrationLockPollMillis = 100L
        val MigrationVersionPattern = Regex("[0-9]{1,40}")
        val LegacyV1ToV8 = (1..8).map(Int::toString).toSet()
        val LegacyV1ToV9 = (1..9).map(Int::toString).toSet()
        const val LegacyPostgresV1ToV8SchemaSha256 =
            "2b4467c0d68a20de18ee4df08f285a4386e246d73c868387ebc30d17a76aae5d"
        const val LegacyPostgresV1ToV9SchemaSha256 =
            "a612721cba6f83be81c6827458b185686a1f2d061d92300636382fe7ce272640"
        val PostgresSchemaFingerprintSql =
            """
            WITH managed_tables AS (
                SELECT table_class.oid, namespace.nspname AS schema_name, table_class.relname AS table_name
                FROM pg_class table_class
                JOIN pg_namespace namespace ON namespace.oid = table_class.relnamespace
                WHERE namespace.nspname = current_schema()
                  AND table_class.relkind IN ('r', 'p')
            ), schema_objects AS (
                SELECT 'table|' || schema_name || '|' || table_name AS line
                FROM managed_tables
                UNION ALL
                SELECT 'column|' || tables.schema_name || '|' || tables.table_name || '|' ||
                       row_number() OVER (PARTITION BY tables.oid ORDER BY attribute.attnum) || '|' ||
                       attribute.attname || '|' || format_type(attribute.atttypid, attribute.atttypmod) || '|' ||
                       attribute.attnotnull::text || '|' || attribute.attidentity::text || '|' ||
                       attribute.attgenerated::text || '|' ||
                       COALESCE(pg_get_expr(default_value.adbin, default_value.adrelid, false), '')
                FROM managed_tables tables
                JOIN pg_attribute attribute ON attribute.attrelid = tables.oid
                LEFT JOIN pg_attrdef default_value
                  ON default_value.adrelid = attribute.attrelid AND default_value.adnum = attribute.attnum
                WHERE attribute.attnum > 0 AND NOT attribute.attisdropped
                UNION ALL
                SELECT 'constraint|' || tables.schema_name || '|' || tables.table_name || '|' ||
                       constraint_value.conname || '|' || constraint_value.contype::text || '|' ||
                       constraint_value.condeferrable::text || '|' || constraint_value.condeferred::text || '|' ||
                       constraint_value.convalidated::text || '|' ||
                       pg_get_constraintdef(constraint_value.oid, false)
                FROM managed_tables tables
                JOIN pg_constraint constraint_value ON constraint_value.conrelid = tables.oid
                UNION ALL
                SELECT 'index|' || tables.schema_name || '|' || tables.table_name || '|' ||
                       index_class.relname || '|' || pg_get_indexdef(index_class.oid, 0, false)
                FROM managed_tables tables
                JOIN pg_index index_value ON index_value.indrelid = tables.oid
                JOIN pg_class index_class ON index_class.oid = index_value.indexrelid
            )
            SELECT COALESCE(string_agg(line, chr(10) ORDER BY line), '') FROM schema_objects
            """.trimIndent()
        val KnownLegacyColumnsByVersion: Map<String, Map<String, Set<String>>> = mapOf(
            "1" to mapOf(
                "players" to setOf("id", "display_name", "created_at"),
                "save_heads" to setOf("player_id", "latest_revision"),
                "save_revisions" to setOf("player_id", "revision", "payload_json", "schema_version"),
                "matchmaking_tickets" to setOf("id", "player_id", "mode", "status", "expires_at"),
                "duel_sessions" to setOf("id", "mode", "status", "config_json", "version"),
                "duel_commands" to setOf("session_id", "client_command_id", "version", "command_type"),
                "duel_events" to setOf("session_id", "event_type", "payload_json"),
            ),
            "2" to mapOf(
                "players" to setOf("account_kind"),
                "player_profiles" to setOf("player_id"),
                "guest_installations" to setOf("installation_hash", "player_id"),
                "refresh_token_families" to setOf("id", "player_id"),
                "refresh_tokens" to setOf("token_hash", "family_id"),
                "save_commands" to setOf("player_id", "command_id"),
            ),
            "3" to mapOf(
                "player_identities" to setOf("player_id", "provider", "provider_subject"),
                "google_auth_challenges" to setOf("player_id", "nonce_hash"),
            ),
            "4" to mapOf(
                "auth_idempotency_results" to setOf("operation", "idempotency_key", "request_fingerprint"),
            ),
            "5" to mapOf(
                "matchmaking_tickets" to setOf("command_id", "rules_json", "session_id", "matched_with_bot"),
                "duel_sessions" to setOf("state_iv", "state_ciphertext", "expires_at"),
                "duel_participants" to setOf("session_id", "player_id"),
                "duel_secrets" to setOf("session_id", "secret_iv", "secret_ciphertext"),
                "duel_turns" to setOf("session_id", "turn_number"),
                "private_duel_invites" to setOf("invite_code", "owner_player_id", "create_command_id"),
                "online_command_results" to setOf("operation", "actor_key", "command_id"),
            ),
            "6" to emptyMap(),
            "7" to emptyMap(),
            "8" to mapOf("duel_events" to setOf("session_revision")),
            "9" to mapOf(
                "legacy_online_session_migrations" to setOf(
                    "session_id",
                    "platform_player_id",
                    "legacy_player_id",
                    "command_id",
                    "request_fingerprint",
                    "migrated_at",
                ),
            ),
        )
        val KnownLegacyIndexesByVersion: Map<String, Map<String, Set<String>>> = mapOf(
            "6" to mapOf("matchmaking_tickets" to setOf("idx_matchmaking_command_replay")),
            "7" to mapOf(
                "private_duel_invites" to setOf(
                    "idx_private_invite_create_command",
                    "idx_private_invite_accept_command",
                ),
            ),
            "8" to mapOf("duel_events" to setOf("idx_duel_events_session_cursor")),
        )
    }
}
