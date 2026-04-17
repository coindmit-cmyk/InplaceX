package com.mirkori.inplacex.core.bot

import kotlin.random.Random

data class BotGridPlan(
    val id: String,
    val symbolOrder: List<Char>,
    val rowOrder: List<Int>,
    val columnOffsets: List<Int>,
) {
    fun guesses(codeLength: Int): List<String> {
        require(codeLength in 1..columnOffsets.size) { "codeLength must fit column offsets size" }
        require(symbolOrder.size == rowOrder.size) { "symbolOrder and rowOrder must have same size" }

        return rowOrder.map { rowSeed ->
            buildString {
                repeat(codeLength) { position ->
                    val symbolIndex = (rowSeed + columnOffsets[position]) % symbolOrder.size
                    append(symbolOrder[symbolIndex])
                }
            }
        }
    }
}

object BotGridCatalog {
    private const val DIGIT_PLAN_COUNT = 40
    private val digitPlans: List<BotGridPlan> = buildDigitPlans()

    fun randomDigitPlan(random: Random): BotGridPlan {
        return digitPlans.random(random)
    }

    fun allDigitPlans(): List<BotGridPlan> = digitPlans

    private fun buildDigitPlans(): List<BotGridPlan> {
        val digits = ('0'..'9').toList()
        return (0 until DIGIT_PLAN_COUNT).map { planIndex ->
            val random = Random(20_240L + planIndex * 977L)
            BotGridPlan(
                id = "digits-random-${planIndex + 1}",
                symbolOrder = digits.shuffled(random),
                rowOrder = digits.indices.shuffled(random),
                columnOffsets = digits.indices.shuffled(random),
            )
        }
    }
}
