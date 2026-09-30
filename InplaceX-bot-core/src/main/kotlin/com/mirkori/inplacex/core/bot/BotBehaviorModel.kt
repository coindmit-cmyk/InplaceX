package com.mirkori.inplacex.core.bot

enum class BotBehaviorModel {
    SCOUT,
    BALANCED,
    ANALYST,
}

data class BotBehaviorProfile(
    val model: BotBehaviorModel,
    val title: String,
    val description: String,
    val randomGuessMultiplier: Double,
    val evaluationSampleMultiplier: Double,
    val partitionSampleMultiplier: Double,
    val topChoiceWindowBonus: Int,
)

object BotBehaviorProfiles {
    val scout = BotBehaviorProfile(
        model = BotBehaviorModel.SCOUT,
        title = "Scout",
        description = "Быстро прощупывает варианты и дольше играет на интуиции.",
        randomGuessMultiplier = 1.35,
        evaluationSampleMultiplier = 0.85,
        partitionSampleMultiplier = 0.85,
        topChoiceWindowBonus = 2,
    )

    val balanced = BotBehaviorProfile(
        model = BotBehaviorModel.BALANCED,
        title = "Balanced",
        description = "Ровное поведение без перекоса в риск или жадный анализ.",
        randomGuessMultiplier = 1.0,
        evaluationSampleMultiplier = 1.0,
        partitionSampleMultiplier = 1.0,
        topChoiceWindowBonus = 0,
    )

    val analyst = BotBehaviorProfile(
        model = BotBehaviorModel.ANALYST,
        title = "Analyst",
        description = "Сильнее полагается на сужение множества кандидатов.",
        randomGuessMultiplier = 0.55,
        evaluationSampleMultiplier = 1.35,
        partitionSampleMultiplier = 1.4,
        topChoiceWindowBonus = -1,
    )

    fun forModel(model: BotBehaviorModel): BotBehaviorProfile {
        return when (model) {
            BotBehaviorModel.SCOUT -> scout
            BotBehaviorModel.BALANCED -> balanced
            BotBehaviorModel.ANALYST -> analyst
        }
    }
}
