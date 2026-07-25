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

private fun LocalizationProvider.formatHomeText(key: String, vararg args: Any): String =
    String.format(Locale.ROOT, text(key), *args)
