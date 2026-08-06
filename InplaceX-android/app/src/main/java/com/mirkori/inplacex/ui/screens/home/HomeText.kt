package com.mirkori.inplacex.ui.screens.home

import com.mirkori.inplacex.platform.localization.LocalizationProvider
import java.util.Locale

internal fun LocalizationProvider.homeCodeLength(codeLength: Int): String =
    formatHomeText("home.code_length", codeLength)

internal fun LocalizationProvider.homeDuelResultBotWin(score: Int): String =
    formatHomeText("home.duel.result.bot_win", score)

internal fun LocalizationProvider.homeDuelStatus(score: Int, confirmed: Int, codeLength: Int): String =
    formatHomeText("home.duel.status.with_score", score, confirmed, codeLength)

internal fun LocalizationProvider.homeDuelWaiting(confirmed: Int, codeLength: Int): String =
    formatHomeText("home.duel.status.waiting", confirmed, codeLength)

internal fun LocalizationProvider.homeSecretLabel(codeLength: Int): String =
    formatHomeText("home.dialog.setup.secret_label", codeLength)

internal fun LocalizationProvider.homeTimeLeft(seconds: Int): String =
    formatHomeText("home.dialog.setup.time_left", seconds)

internal fun LocalizationProvider.homeBotReady(seconds: Int): String =
    formatHomeText("home.dialog.setup.bot_ready", seconds)

internal fun LocalizationProvider.homeEnterDigits(codeLength: Int): String =
    formatHomeText("home.dialog.setup.enter_digits", codeLength)

internal fun LocalizationProvider.homeRaceAttempts(used: Int, limit: Int?): String =
    if (limit == null) {
        formatHomeText("home.race.result.attempts_unlimited", used)
    } else {
        formatHomeText("home.race.result.attempts", used, limit)
    }

internal fun LocalizationProvider.homeRaceTime(elapsedSeconds: Int): String =
    formatHomeText("home.race.result.time", formatRaceElapsed(elapsedSeconds))

internal fun LocalizationProvider.homeRaceReward(coins: Int): String =
    formatHomeText("home.race.result.reward", coins)

internal fun formatRaceElapsed(totalSeconds: Int): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0)
    val minutes = safeSeconds / 60
    val seconds = safeSeconds % 60
    return "%02d:%02d".format(Locale.ROOT, minutes, seconds)
}

private fun LocalizationProvider.formatHomeText(key: String, vararg args: Any): String =
    String.format(Locale.ROOT, text(key), *args)
