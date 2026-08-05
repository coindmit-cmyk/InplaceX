package com.mirkori.inplacex.data.local

import java.time.ZoneId
import java.util.UUID

/**
 * Параметры runtime-доступа к локальному SQLite.
 *
 * Значения по умолчанию сохраняют существующие production-БД и системные
 * часы. Тесты используют [uniqueTest], чтобы не затрагивать пользовательские
 * данные и детерминированно проверять поведение, зависящее от времени.
 */
data class LocalDatabaseConfig(
    val databaseName: String = DEFAULT_DATABASE_NAME,
    val zoneId: ZoneId = ZoneId.systemDefault(),
    val nowMs: () -> Long = ::systemNowMs,
) {
    init {
        require(databaseName.isNotBlank()) { "databaseName must not be blank" }
    }

    companion object {
        const val DEFAULT_DATABASE_NAME = "inplacex_progress.db"

        /**
         * Создаёт изолированную конфигурацию БД для одного теста.
         * UUID исключает случайное совместное использование БД тестами и
         * гарантирует, что production-БД не попадёт в тестовый scope.
         */
        fun uniqueTest(
            prefix: String = "inplacex_test",
            zoneId: ZoneId = ZoneId.systemDefault(),
            nowMs: () -> Long,
        ): LocalDatabaseConfig {
            require(prefix.isNotBlank()) { "prefix must not be blank" }
            return LocalDatabaseConfig(
                databaseName = "${prefix}_${UUID.randomUUID()}.db",
                nowMs = nowMs,
                zoneId = zoneId,
            )
        }
    }
}

private fun systemNowMs(): Long = System.currentTimeMillis()
