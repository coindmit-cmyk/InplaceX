package com.mirkori.inplacex.core.model

data class GameConfig(
    val codeLength: Int = 6,
    val allowDuplicates: Boolean = true,
    val attemptLimit: Int = 12,
    val turnTimeLimitSeconds: Int? = null
)