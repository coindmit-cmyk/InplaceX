package com.mirkori.inplacex.backend.persistence

import javax.sql.DataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

class LegacySchemaFingerprintPostgresIntegrationTest {
    @Test
    fun `production restore preserves exact legacy order and rejects current order`() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable)
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { postgres ->
            postgres.start()
            val source = dataSource(postgres, postgres.databaseName)
            JdbcMigrationRunner().migrate(source)
            source.connection.use { connection ->
                connection.createStatement().use {
                    it.execute("UPDATE inplacex_schema_history SET checksum = NULL")
                }
            }
            val currentOrderError = assertThrows(IllegalArgumentException::class.java) {
                JdbcMigrationRunner(allowLegacyChecksumBackfill = true).migrate(source)
            }
            assertTrue(currentOrderError.message.orEmpty().contains("schema fingerprint"))
            source.connection.use { connection ->
                connection.createStatement().use {
                    it.execute("ALTER TABLE inplacex_schema_history DROP COLUMN checksum")
                }
            }
            val dump = postgres.execInContainer(
                "pg_dump",
                "--format=custom",
                "--no-owner",
                "--no-privileges",
                "--username=${postgres.username}",
                "--dbname=${postgres.databaseName}",
                "--file=/tmp/legacy.dump",
            )
            check(dump.exitCode == 0) { dump.stderr }
            postgres.createConnection("").use { connection ->
                connection.createStatement().use { it.execute("CREATE DATABASE restored") }
            }
            val restore = postgres.execInContainer(
                "pg_restore",
                "--exit-on-error",
                "--no-owner",
                "--no-privileges",
                "--username=${postgres.username}",
                "--dbname=restored",
                "/tmp/legacy.dump",
            )
            check(restore.exitCode == 0) { restore.stderr }

            val restored = dataSource(postgres, "restored")
            JdbcMigrationRunner(allowLegacyChecksumBackfill = true).migrate(restored)
            JdbcMigrationRunner().verify(restored)
        }
    }

    @Test
    fun `only exact schema-qualified PostgreSQL legacy baseline is acknowledged`() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable)
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { postgres ->
            postgres.start()
            val exact = dataSource(postgres, postgres.databaseName)
            createLegacyV1ToV8(exact)

            JdbcMigrationRunner(allowLegacyChecksumBackfill = true).migrate(exact)
            JdbcMigrationRunner().verify(exact)
            assertEquals(9, countHistory(exact))
            exact.connection.use { connection ->
                connection.createStatement().use {
                    it.execute("ALTER TABLE inplacex_schema_history DROP COLUMN checksum")
                }
            }
            JdbcMigrationRunner(allowLegacyChecksumBackfill = true).migrate(exact)

            postgres.createConnection("").use { connection ->
                connection.createStatement().use { it.execute("CREATE DATABASE tampered") }
            }
            val tampered = dataSource(postgres, "tampered")
            createLegacyV1ToV8(tampered)
            tampered.connection.use { connection ->
                connection.createStatement().use {
                    it.execute("ALTER TABLE players ADD COLUMN injected_privilege VARCHAR(32)")
                }
            }

            assertThrows(IllegalArgumentException::class.java) {
                JdbcMigrationRunner(allowLegacyChecksumBackfill = true).migrate(tampered)
            }
        }
    }

    private fun createLegacyV1ToV8(dataSource: DataSource) {
        JdbcMigrationRunner(migrations = DatabaseMigrations.all.take(8)).migrate(dataSource)
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute("ALTER TABLE inplacex_schema_history DROP COLUMN checksum")
            }
        }
    }

    private fun dataSource(postgres: PostgreSQLContainer<Nothing>, database: String): PGSimpleDataSource =
        PGSimpleDataSource().apply {
            setServerNames(arrayOf(postgres.host))
            setPortNumbers(intArrayOf(postgres.firstMappedPort))
            databaseName = database
            user = postgres.username
            password = postgres.password
        }

    private fun countHistory(dataSource: DataSource): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM inplacex_schema_history").use { result ->
                check(result.next())
                result.getInt(1)
            }
        }
    }
}
