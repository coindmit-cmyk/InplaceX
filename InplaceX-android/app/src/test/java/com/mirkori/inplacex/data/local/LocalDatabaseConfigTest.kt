package com.mirkori.inplacex.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LocalDatabaseConfigTest {
    @Test
    fun productionDefaultsKeepTheExistingDatabaseAndClockSource() {
        val config = LocalDatabaseConfig()

        assertEquals(LocalDatabaseConfig.DEFAULT_DATABASE_NAME, config.databaseName)
    }

    @Test
    fun uniqueTestConfigUsesAnIsolatedNameAndInjectedTime() {
        val nowMs = 1_725_000_000_123L
        val config = LocalDatabaseConfig.uniqueTest("progress") { nowMs }
        val secondConfig = LocalDatabaseConfig.uniqueTest("progress") { nowMs }

        assertNotEquals(LocalDatabaseConfig.DEFAULT_DATABASE_NAME, config.databaseName)
        assertNotEquals(config.databaseName, secondConfig.databaseName)
        assertEquals(nowMs, config.nowMs())
        assertEquals(true, config.databaseName.startsWith("progress_"))
        assertEquals(true, config.databaseName.endsWith(".db"))
    }
}
