package com.mirkori.inplacex.data.local

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

internal inline fun <T> withIsolatedDatabase(
    prefix: String,
    noinline nowMs: () -> Long,
    block: (Context, LocalDatabaseConfig) -> T,
): T {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val config = LocalDatabaseConfig.uniqueTest(prefix, nowMs = nowMs)
    val databaseFile = context.getDatabasePath(config.databaseName)

    assertNotEquals(LocalDatabaseConfig.DEFAULT_DATABASE_NAME, config.databaseName)
    assertFalse(databaseFile.exists())

    return try {
        block(context, config)
    } finally {
        val deleted = context.deleteDatabase(config.databaseName)
        assertTrue(
            "Не удалось удалить изолированную БД ${config.databaseName}",
            deleted || !databaseFile.exists(),
        )
        assertFalse(databaseFile.exists())
        assertFalse(context.databaseList().contains(config.databaseName))
    }
}
