package com.mirkori.inplacex.core.campaign

data class CampaignChapterReward(
    val coins: Int,
    val openPositionHints: Int,
    val checkDigitHints: Int,
    val checkPositionHints: Int,
)

object CampaignChapterRewardPolicy {
    const val LEVELS_PER_CHAPTER = 10

    fun levelRange(chapterNumber: Int): IntRange {
        require(chapterNumber > 0) { "chapterNumber must be > 0" }
        val firstLevel = (chapterNumber - 1) * LEVELS_PER_CHAPTER + 1
        return firstLevel..(firstLevel + LEVELS_PER_CHAPTER - 1)
    }

    fun rewardFor(chapterNumber: Int): CampaignChapterReward {
        require(chapterNumber > 0) { "chapterNumber must be > 0" }
        return CampaignChapterReward(
            coins = 50,
            openPositionHints = 1,
            checkDigitHints = 1,
            checkPositionHints = 1,
        )
    }
}
