package com.mirkori.inplacex.backend.bot

import com.mirkori.inplacex.core.bot.BotBehaviorModel
import com.mirkori.inplacex.core.bot.BotDifficulty
import com.mirkori.inplacex.core.bot.BotSolver
import com.mirkori.inplacex.core.engine.SecretGenerator
import com.mirkori.inplacex.core.model.GameConfig

internal object DeterministicServerBotPlayerFactory {
    fun create(
        config: GameConfig,
        difficulty: BotDifficulty,
        behavior: BotBehaviorModel = BotBehaviorModel.BALANCED,
        botId: String = "test-server-bot",
        displayName: String = "Test Server Bot",
        secret: String? = null,
        secretSeed: Long = 0L,
        brainSeed: Long = 0L,
    ): ServerBotPlayer = ServerBotPlayer(
        profile = ServerBotProfile(
            botId = botId,
            displayName = displayName,
            difficulty = difficulty,
            behavior = behavior,
        ),
        config = config,
        hiddenSecret = secret ?: SecretGenerator.generate(config.copy(seed = secretSeed)),
        brainSeed = brainSeed,
        solver = BotSolver(
            config = config,
            difficulty = difficulty,
            behavior = behavior,
            seed = brainSeed,
        ),
    )
}
