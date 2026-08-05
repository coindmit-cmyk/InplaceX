package com.mirkori.inplacex.core.retention

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

enum class RetentionRewardType(val storageKey: String) {
    DAILY("daily"),
    WEEKLY("weekly"),
}

data class RetentionRewardGrant(
    val coins: Int,
    val openPositionHints: Int = 0,
    val checkDigitHints: Int = 0,
    val checkPositionHints: Int = 0,
    val extraMovesBoosts: Int = 0,
    val extraTimeBoosts: Int = 0,
)

object RetentionRewardPolicy {
    val dailyGrant = RetentionRewardGrant(
        coins = 20,
        checkDigitHints = 1,
    )

    val weeklyGrant = RetentionRewardGrant(
        coins = 100,
        openPositionHints = 1,
        checkDigitHints = 1,
        checkPositionHints = 1,
        extraMovesBoosts = 1,
        extraTimeBoosts = 1,
    )

    fun grantFor(type: RetentionRewardType): RetentionRewardGrant = when (type) {
        RetentionRewardType.DAILY -> dailyGrant
        RetentionRewardType.WEEKLY -> weeklyGrant
    }

    fun periodKey(
        type: RetentionRewardType,
        nowMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val date = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        return when (type) {
            RetentionRewardType.DAILY -> date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            RetentionRewardType.WEEKLY -> {
                val weekFields = WeekFields.ISO
                val year = date.get(weekFields.weekBasedYear())
                val week = date.get(weekFields.weekOfWeekBasedYear())
                String.format(Locale.ROOT, "%04d-W%02d", year, week)
            }
        }
    }
}
