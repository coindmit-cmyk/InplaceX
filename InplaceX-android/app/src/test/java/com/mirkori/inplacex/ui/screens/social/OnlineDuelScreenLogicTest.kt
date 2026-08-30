package com.mirkori.inplacex.ui.screens.social

import com.mirkori.inplacex.platform.localization.AppLanguage
import com.mirkori.inplacex.platform.localization.StaticLocalizationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineDuelScreenLogicTest {

    @Test
    fun inviteCodeIsNormalizedToServerAlphabetAndLength() {
        assertEquals(8, FriendInviteCodeLength)
        assertEquals(
            "ABCD2345",
            normalizeFriendInviteCode("a-b-c-d-2-3-4-5-6"),
        )
        assertEquals(
            "23456789",
            normalizeFriendInviteCode("01io23456789"),
        )
    }

    @Test
    fun invitationExpiryUsesExactServerTimestamp() {
        val now = 1_000_000L

        assertEquals("10:00", formatFriendInviteRemainingClock(now + 600_000L, now))
        assertEquals("00:01", formatFriendInviteRemainingClock(now + 1L, now))
        assertEquals("00:00", formatFriendInviteRemainingClock(now - 1L, now))
    }

    @Test
    fun invitationShareTextContainsExactRoomCode() {
        assertEquals(
            "Join me with code ABCD2345",
            formatFriendInviteShareText(
                template = "Join me with code {code}",
                code = "ABCD2345",
            ),
        )
    }

    @Test
    fun codeLengthUsesLocalizedGrammar() {
        val russian = StaticLocalizationProvider.forLanguage(AppLanguage.RU)
        val english = StaticLocalizationProvider.forLanguage(AppLanguage.EN)

        assertEquals("4 цифры", formatOnlineCodeLength(russian, 4))
        assertEquals("5 цифр", formatOnlineCodeLength(russian, 5))
        assertEquals("4 digits", formatOnlineCodeLength(english, 4))
        assertEquals("10 digits", formatOnlineCodeLength(english, 10))
    }

    @Test
    fun mainMenuCodeLengthIsNormalizedForOnlineMatches() {
        assertEquals(4, normalizeOnlineCodeLength(3))
        assertEquals(8, normalizeOnlineCodeLength(8))
        assertEquals(10, normalizeOnlineCodeLength(11))
    }
}
