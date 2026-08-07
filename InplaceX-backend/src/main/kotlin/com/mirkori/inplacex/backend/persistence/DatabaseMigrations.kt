package com.mirkori.inplacex.backend.persistence

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import javax.sql.DataSource

data class SqlMigration(
    val version: String,
    val description: String,
    val sql: String,
)

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
) {
    fun migrate(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS inplacex_schema_history (
                        version VARCHAR(50) PRIMARY KEY,
                        description VARCHAR(255) NOT NULL,
                        installed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """.trimIndent(),
                )
            }
            val appliedVersions = connection.prepareStatement(
                "SELECT version FROM inplacex_schema_history",
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    buildSet {
                        while (resultSet.next()) add(resultSet.getString("version"))
                    }
                }
            }

            migrations.filterNot { it.version in appliedVersions }.forEach { migration ->
                applyMigration(connection = connection, migration = migration)
            }
        }
    }

    private fun applyMigration(connection: java.sql.Connection, migration: SqlMigration) {
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
                "INSERT INTO inplacex_schema_history(version, description) VALUES (?, ?)",
            ).use { statement ->
                statement.setString(1, migration.version)
                statement.setString(2, migration.description)
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
}
