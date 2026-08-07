package com.mirkori.inplacex.backend.persistence

import com.mirkori.inplacex.backend.app.DatabaseRuntimeConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.Closeable
import javax.sql.DataSource

class PostgresDatabase private constructor(
    private val pooledDataSource: HikariDataSource,
    private val acknowledgeLegacyChecksumBaseline: Boolean,
) : Closeable {
    val dataSource: DataSource
        get() = pooledDataSource

    fun migrate() {
        JdbcMigrationRunner(
            allowLegacyChecksumBackfill = acknowledgeLegacyChecksumBaseline,
        ).migrate(dataSource)
    }

    fun isReady(onFailure: (Throwable) -> Unit = {}): Boolean = try {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT 1").use { statement ->
                statement.queryTimeout = ReadinessQueryTimeoutSeconds
                statement.executeQuery().use { result -> require(result.next() && result.getInt(1) == 1) }
            }
        }
        JdbcMigrationRunner().verify(dataSource)
        true
    } catch (error: Throwable) {
        onFailure(error)
        false
    }

    override fun close() {
        pooledDataSource.close()
    }

    companion object {
        fun connect(config: DatabaseRuntimeConfig): PostgresDatabase {
            val hikariConfig = HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.username
                password = config.password
                maximumPoolSize = 10
                minimumIdle = 1
                poolName = "inplacex-backend"
                connectionTimeout = ConnectionTimeoutMillis
                validationTimeout = ValidationTimeoutMillis
                initializationFailTimeout = InitializationTimeoutMillis
                addDataSourceProperty("connectTimeout", DriverConnectTimeoutSeconds.toString())
                addDataSourceProperty("socketTimeout", DriverSocketTimeoutSeconds.toString())
                addDataSourceProperty("cancelSignalTimeout", DriverCancelTimeoutSeconds.toString())
                addDataSourceProperty("ApplicationName", "inplacex-backend")
                addDataSourceProperty(
                    "options",
                    "-c statement_timeout=$StatementTimeoutMillis " +
                        "-c lock_timeout=$LockTimeoutMillis " +
                        "-c idle_in_transaction_session_timeout=$IdleTransactionTimeoutMillis",
                )
            }
            return PostgresDatabase(
                pooledDataSource = HikariDataSource(hikariConfig),
                acknowledgeLegacyChecksumBaseline = config.acknowledgeLegacyChecksumBaseline,
            )
        }

        private const val ConnectionTimeoutMillis = 5_000L
        private const val ValidationTimeoutMillis = 2_000L
        private const val InitializationTimeoutMillis = 5_000L
        private const val DriverConnectTimeoutSeconds = 5
        private const val DriverSocketTimeoutSeconds = 15
        private const val DriverCancelTimeoutSeconds = 3
        private const val ReadinessQueryTimeoutSeconds = 3
        private const val StatementTimeoutMillis = 30_000
        private const val LockTimeoutMillis = 5_000
        private const val IdleTransactionTimeoutMillis = 15_000
    }
}
