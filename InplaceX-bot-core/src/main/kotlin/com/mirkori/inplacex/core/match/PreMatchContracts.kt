package com.mirkori.inplacex.core.match

enum class SecretSetupKind {
    GENERATED,
    PLAYER_SELECTED,
}

enum class PreMatchPhase {
    SECRET_SELECTION,
    WAITING_OPPONENT_SECRET,
    READY_TO_START,
    CANCELLED_TIMEOUT,
}

data class PreMatchConfig(
    val secretSelectionTimeoutSeconds: Int = 60,
    val devBotSecretDelaySeconds: Int = 5,
    val secretSetupKind: SecretSetupKind = SecretSetupKind.GENERATED,
)

data class PreMatchState(
    val phase: PreMatchPhase,
    val playerSecretReady: Boolean,
    val opponentSecretReady: Boolean,
    val timeoutSecondsLeft: Int,
    val waitingSecondsLeft: Int = 0,
)
