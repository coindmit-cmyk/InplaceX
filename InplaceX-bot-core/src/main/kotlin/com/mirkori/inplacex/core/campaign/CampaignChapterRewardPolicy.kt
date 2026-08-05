package com.mirkori.inplacex.core.campaign

data class CampaignChapterReward(
    val coins: Int,
    val openPositionHints: Int,
    val checkDigitHints: Int,
    val checkPositionHints: Int,
)

object CampaignChapterRewardPolicy {
    val LEVELS_PER_CHAPTER: Int
        get() = CampaignProgressionRules.unlockConfig.levelsPerChapter

    fun levelRange(chapterNumber: Int): IntRange {
        return CampaignProgressionRules.levelRange(chapterNumber)
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
