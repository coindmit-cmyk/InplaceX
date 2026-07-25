package com.mirkori.inplacex.backend.persistence

import com.mirkori.inplacex.backend.app.DatabaseRuntimeConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.Closeable
import javax.sql.DataSource

class PostgresDatabase private constructor(
    private val pooledDataSource: HikariDataSource,
) : Closeable {
    val dataSource: DataSource
        get() = pooledDataSource

    fun migrate() {
        JdbcMigrationRunner().migrate(dataSource)
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
            }
            return PostgresDatabase(HikariDataSource(hikariConfig))
        }
    }
}
