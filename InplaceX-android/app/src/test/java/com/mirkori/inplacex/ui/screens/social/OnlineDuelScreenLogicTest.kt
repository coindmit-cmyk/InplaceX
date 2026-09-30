package com.mirkori.inplacex.ui.screens.social

import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineDuelScreenLogicTest {

    @Test
    fun inviteCodeIsNormalizedToServerAlphabetAndLength() {
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
    fun invitationShareTextContainsExactRoomCode() {
        assertEquals(
            "Join me with code ABCD2345",
            formatFriendInviteShareText(
                template = "Join me with code {code}",
                code = "ABCD2345",
            ),
        )
    }
}
