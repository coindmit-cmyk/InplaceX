package com.mirkori.inplacex.core.match

enum class OpponentKind {
    BOT,
    LOCAL_HUMAN,
    REMOTE_PLAYER,
}

interface OpponentProvider {
    val id: String
    val kind: OpponentKind
    val displayName: String
}
