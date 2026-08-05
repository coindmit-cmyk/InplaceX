package com.mirkori.inplacex.data.local

import com.mirkori.inplacex.core.retention.RetentionRewardType
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.database.sqlite.SQLiteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalRepositoriesInstrumentedTest {
    @Test
    fun campaignCompletionIsAtomicAndOnlyRatingImprovementsGrantCoins() {
        withIsolatedDatabase("campaign_completion_atomic", { FIXED_NOW_MS }) { context, config ->
            val repository = GameProgressRepository(context, config)

            val first = repository.recordCampaignCompletion(levelNumber = 1, backendRating = 8)
            assertEquals(128, first.coins)
            assertEquals(8, first.totalCampaignRating)
            assertEquals(1, first.companyStats.wins)

            val replay = repository.recordCampaignCompletion(levelNumber = 1, backendRating = 6)
            assertEquals(128, replay.coins)
            assertEquals(8, replay.totalCampaignRating)
            assertEquals(2, replay.companyStats.wins)

            val improved = repository.recordCampaignCompletion(levelNumber = 1, backendRating = 10)
            assertEquals(130, improved.coins)
            assertEquals(10, improved.totalCampaignRating)
            assertEquals(3, improved.companyStats.wins)

            val beforeFailure = repository.loadState()
            val db = GameProgressDbHelper(context, config).writableDatabase
            db.execSQL(
                """
                CREATE TRIGGER fail_campaign_profile_update
                BEFORE INSERT ON ${GameProgressDatabase.TABLE_PROGRESS}
                WHEN NEW.${GameProgressDatabase.COL_COMPANY_WINS} = 4
                BEGIN
                    SELECT RAISE(ABORT, 'forced campaign rollback');
                END
                """.trimIndent(),
            )
            try {
                repository.recordCampaignCompletion(levelNumber = 2, backendRating = 7)
                fail("Expected the forced profile update failure")
            } catch (_: SQLiteException) {
                // The profile write fails after the level progress write; the transaction must roll both back.
            }

            assertEquals(beforeFailure, repository.loadState())
            assertEquals(CampaignLevelProgress(2, 0), repository.loadCampaignProgress(2))
        }
    }

    @Test
    fun campaignChapterRewardRequiresCompletionAndCanOnlyBeClaimedOnce() {
        withIsolatedDatabase("campaign_chapter_reward", { FIXED_NOW_MS }) { context, config ->
            val outcomes = mutableListOf<Pair<Int, CampaignChapterRewardClaimOutcome>>()
            val repository = GameProgressRepository(context, config) { chapter, outcome ->
                outcomes += chapter to outcome
            }

            assertEquals(null, repository.claimCampaignChapterReward(1))
            (1..10).forEach { level ->
                repository.recordCampaignCompletion(levelNumber = level, backendRating = 10)
            }

            val claimed = requireNotNull(repository.claimCampaignChapterReward(1))
            assertEquals(270, claimed.coins)
            assertEquals(1, claimed.openPositionHints)
            assertEquals(1, claimed.checkDigitHints)
            assertEquals(1, claimed.checkPositionHints)
            assertEquals(setOf(1), repository.loadClaimedCampaignChapters())

            assertEquals(null, repository.claimCampaignChapterReward(1))
            assertEquals(270, repository.loadState().coins)
            assertEquals(
                listOf(
                    1 to CampaignChapterRewardClaimOutcome.INCOMPLETE,
                    1 to CampaignChapterRewardClaimOutcome.CLAIMED,
                    1 to CampaignChapterRewardClaimOutcome.ALREADY_CLAIMED,
                ),
                outcomes,
            )
        }
    }

    @Test
    fun temporaryProPurchasePersistsExtendsAndExpiresWithoutGrantingProPlus() {
        var nowMs = FIXED_NOW_MS
        withIsolatedDatabase("temporary_pro_repository", { nowMs }) { context, config ->
            val repository = GameProgressRepository(context, config)

            assertTrue(repository.buyTemporaryPro())
            val firstPurchase = repository.loadState()
            assertEquals(60, firstPurchase.coins)
            assertEquals(FIXED_NOW_MS + 60 * 60_000L, firstPurchase.temporaryProExpiresAtMs)
            assertTrue(firstPurchase.temporaryProActiveAt(nowMs))
            assertTrue(firstPurchase.adsDisabledAt(nowMs))
            assertTrue(firstPurchase.autoTableAssistEnabledAt(nowMs))
            assertFalse(firstPurchase.infiniteHintsEnabled)

            nowMs += 15 * 60_000L
            assertTrue(repository.buyTemporaryPro())
            val extended = GameProgressRepository(context, config).loadState()
            assertEquals(0, extended.coins)
            assertEquals(FIXED_NOW_MS + 2 * 60 * 60_000L, extended.temporaryProExpiresAtMs)

            nowMs = extended.temporaryProExpiresAtMs
            val expired = repository.loadState()
            assertFalse(expired.temporaryProActiveAt(nowMs))
            assertFalse(expired.adsDisabledAt(nowMs))
            assertFalse(expired.autoTableAssistEnabledAt(nowMs))
        }
    }

    @Test
    fun permanentProPreventsTemporaryProFromSpendingCoins() {
        withIsolatedDatabase("temporary_pro_permanent_guard", { FIXED_NOW_MS }) { context, config ->
            val repository = GameProgressRepository(context, config)
            repository.activateProduct(MonetizationProductType.PRO_SUBSCRIPTION)
            val before = repository.loadState()

            assertFalse(repository.buyTemporaryPro())
            val after = repository.loadState()
            assertEquals(before.coins, after.coins)
            assertEquals(0L, after.temporaryProExpiresAtMs)
        }
    }

    @Test
    fun gameProgressRepositoryRoundTripsProgressInventoryEntitlementsAndEnergy() {
        var nowMs = FIXED_NOW_MS
        withIsolatedDatabase("progress_repository", { nowMs }) { context, config ->
            val repository = GameProgressRepository(context, config)

            repository.addAllHelpers(3)
            repository.addCoins(80)
            assertTrue(repository.buyHint(HintStockType.CHECK_DIGIT, costCoins = 7))
            assertTrue(repository.buyBoost(BoostStockType.EXTRA_TIME, costCoins = 11))
            repository.activateProduct(MonetizationProductType.REMOVE_ADS)
            repository.activateProduct(MonetizationProductType.PRO_SUBSCRIPTION)
            repository.activateProduct(MonetizationProductType.PRO_PLUS_SUBSCRIPTION)
            repository.signInWithGooglePlay("Repository Player")
            repository.recordMatchStarted()
            repository.recordModeResult(GameModeStatType.PVE_RACE, won = true)
            repository.recordCompanyLoss()
            repository.recordCampaignCompletion(levelNumber = 3, backendRating = 8)
            repository.completeCampaignTutorial()

            val reloadedRepository = GameProgressRepository(context, config)
            val state = reloadedRepository.loadState()

            assertEquals("Repository Player", state.playerDisplayName)
            assertTrue(state.googlePlaySignedIn)
            assertEquals(3, state.openPositionHints)
            assertEquals(4, state.checkDigitHints)
            assertEquals(3, state.checkPositionHints)
            assertEquals(3, state.extraMovesBoosts)
            assertEquals(4, state.extraTimeBoosts)
            assertEquals(200, state.coins)
            assertEquals(4, state.campaignEnergy)
            assertEquals(1, state.matchesPlayed)
            assertEquals(2, state.matchesWon)
            assertEquals(4, state.highestUnlockedCampaignLevel)
            assertEquals(8, state.totalCampaignRating)
            assertEquals(ModeStats(wins = 1, losses = 0), state.pveStats)
            assertEquals(ModeStats(wins = 0, losses = 0), state.pvpStats)
            assertEquals(ModeStats(wins = 1, losses = 1), state.companyStats)
            assertTrue(state.adFreePurchased)
            assertTrue(state.proSubscriptionActive)
            assertTrue(state.proPlusSubscriptionActive)
            assertTrue(state.adsDisabled)
            assertTrue(state.autoTableAssistEnabled)
            assertTrue(state.infiniteHintsEnabled)
            assertTrue(state.campaignTutorialCompleted)
            assertEquals(
                CampaignLevelProgress(levelNumber = 3, bestBackendRating = 8),
                reloadedRepository.loadCampaignProgress(3),
            )

            val coinsBeforeLoss = state.coins
            reloadedRepository.recordModeResult(GameModeStatType.PVE_RACE, won = false)
            assertEquals(coinsBeforeLoss, reloadedRepository.loadState().coins)

            nowMs += ENERGY_REFILL_MINUTES * 60_000L
            val regenerated = GameProgressRepository(context, config).loadState()

            assertEquals(MAX_CAMPAIGN_ENERGY, regenerated.campaignEnergy)
        }
    }

    @Test
    fun retentionRewardsAreAtomicPersistentAndLimitedToTheirCalendarPeriod() {
        var nowMs = 1_754_395_200_000L // 2025-08-05T12:00:00Z
        withIsolatedDatabase("retention_rewards", { nowMs }) { context, config ->
            val repository = GameProgressRepository(context, config)

            assertTrue(repository.loadRetentionRewardStatus().dailyAvailable)
            assertTrue(repository.loadRetentionRewardStatus().weeklyAvailable)

            val daily = repository.claimRetentionReward(RetentionRewardType.DAILY)
            assertEquals(140, daily?.coins)
            assertEquals(1, daily?.checkDigitHints)
            assertEquals(null, repository.claimRetentionReward(RetentionRewardType.DAILY))

            val weekly = repository.claimRetentionReward(RetentionRewardType.WEEKLY)
            assertEquals(240, weekly?.coins)
            assertEquals(1, weekly?.openPositionHints)
            assertEquals(2, weekly?.checkDigitHints)
            assertEquals(1, weekly?.checkPositionHints)
            assertEquals(1, weekly?.extraMovesBoosts)
            assertEquals(1, weekly?.extraTimeBoosts)
            assertEquals(null, repository.claimRetentionReward(RetentionRewardType.WEEKLY))

            val reloaded = GameProgressRepository(context, config)
            assertFalse(reloaded.loadRetentionRewardStatus().dailyAvailable)
            assertFalse(reloaded.loadRetentionRewardStatus().weeklyAvailable)
            assertEquals(240, reloaded.loadState().coins)

            nowMs += 24 * 60 * 60_000L
            assertTrue(reloaded.loadRetentionRewardStatus().dailyAvailable)
            assertFalse(reloaded.loadRetentionRewardStatus().weeklyAvailable)

            nowMs += 6 * 24 * 60 * 60_000L
            assertTrue(reloaded.loadRetentionRewardStatus().weeklyAvailable)
        }
    }

    @Test
    fun platformRepositoryRoundTripsIdentityRoomTurnAndSyncQueue() {
        var nowMs = FIXED_NOW_MS
        withIsolatedDatabase("platform_repository", { nowMs }) { context, config ->
            GameProgressRepository(context, config).signInWithGooglePlay(PLAYER_NAME)
            val repository = PlatformLocalRepository(context, config)

            val profile = LocalPlayerProfile(
                playerId = PLAYER_ID,
                installationId = "installation-42",
                displayName = PLAYER_NAME,
                avatarUrl = "https://example.invalid/avatar.png",
                authProvider = LocalAuthProvider.GOOGLE_PLAY,
                isGuest = false,
                isOnline = true,
                locale = "ru-RU",
                regionCode = "RU",
                cloudRevision = 9,
                lastSeenAt = FIXED_NOW_MS - 3_000,
                createdAt = FIXED_NOW_MS - 2_000,
                updatedAt = FIXED_NOW_MS - 1_000,
            )
            assertEquals(profile, repository.upsertPlayerProfile(profile))
            assertEquals(profile, repository.loadPlayerProfile())

            val identityLinks = listOf(
                LocalIdentityLink(
                    id = "identity-google",
                    provider = LocalAuthProvider.GOOGLE_PLAY,
                    providerSubject = "google-subject",
                    playerId = PLAYER_ID,
                    displayName = PLAYER_NAME,
                    email = "player@example.invalid",
                    isPrimary = true,
                    linkedAt = FIXED_NOW_MS - 500,
                    lastRefreshedAt = FIXED_NOW_MS - 400,
                ),
                LocalIdentityLink(
                    id = "identity-email",
                    provider = LocalAuthProvider.EMAIL,
                    providerSubject = "email-subject",
                    playerId = PLAYER_ID,
                    displayName = null,
                    email = "backup@example.invalid",
                    isPrimary = false,
                    linkedAt = FIXED_NOW_MS - 300,
                    lastRefreshedAt = FIXED_NOW_MS - 200,
                ),
            )
            repository.replaceIdentityLinks(PLAYER_ID, identityLinks)
            assertEquals(identityLinks, repository.loadIdentityLinks())

            val relationship = LocalSocialRelationship(
                id = "relationship-1",
                playerId = PLAYER_ID,
                targetPlayerId = "player-99",
                targetDisplayName = "Friendly Rival",
                relationshipType = LocalRelationshipType.FRIEND,
                status = LocalRelationshipStatus.ACTIVE,
                source = "server",
                note = "sentinel",
                createdAt = FIXED_NOW_MS - 900,
                updatedAt = FIXED_NOW_MS - 800,
            )
            assertEquals(relationship, repository.upsertRelationship(relationship))
            assertEquals(listOf(relationship), repository.loadRelationships(LocalRelationshipStatus.ACTIVE))

            val room = LocalOnlineRoom(
                roomId = ROOM_ID,
                roomName = "Repository Room",
                inviteCode = "ROOM42",
                visibility = LocalRoomVisibility.FRIENDS_ONLY,
                hostPlayerId = PLAYER_ID,
                status = LocalRoomStatus.READY,
                maxMembers = 4,
                configJson = """{"codeLength":6}""",
                serverRevision = 12,
                createdAt = FIXED_NOW_MS - 700,
                updatedAt = FIXED_NOW_MS - 600,
            )
            assertEquals(room, repository.upsertRoom(room))
            assertEquals(listOf(room), repository.loadRooms(LocalRoomStatus.READY))

            val members = listOf(
                LocalRoomMember(
                    id = "member-host",
                    roomId = ROOM_ID,
                    playerId = PLAYER_ID,
                    displayName = PLAYER_NAME,
                    role = LocalRoomMemberRole.HOST,
                    status = LocalRoomMemberStatus.READY,
                    seatNo = 1,
                    joinedAt = FIXED_NOW_MS - 500,
                    updatedAt = FIXED_NOW_MS - 400,
                ),
                LocalRoomMember(
                    id = "member-rival",
                    roomId = ROOM_ID,
                    playerId = "player-99",
                    displayName = "Friendly Rival",
                    role = LocalRoomMemberRole.MEMBER,
                    status = LocalRoomMemberStatus.READY,
                    seatNo = 2,
                    joinedAt = FIXED_NOW_MS - 300,
                    updatedAt = FIXED_NOW_MS - 200,
                ),
            )
            repository.replaceRoomMembers(ROOM_ID, members)
            assertEquals(members, repository.loadRoomMembers(ROOM_ID))

            val match = LocalMatchRecord(
                matchId = MATCH_ID,
                roomId = ROOM_ID,
                localPlayerId = PLAYER_ID,
                opponentPlayerId = "player-99",
                status = LocalMatchStatus.ACTIVE,
                mode = "PVP_DUEL",
                codeLength = 6,
                allowDuplicates = true,
                attemptLimit = 12,
                turnTimeLimitSec = 45,
                playerSecretHash = "player-secret-hash",
                opponentSecretHash = "opponent-secret-hash",
                localResult = null,
                remoteResult = null,
                startedAt = FIXED_NOW_MS - 100,
                finishedAt = 0,
                updatedAt = FIXED_NOW_MS,
            )
            assertEquals(match, repository.upsertMatch(match))
            assertEquals(listOf(match), repository.loadMatches(LocalMatchStatus.ACTIVE))

            val turns = listOf(
                LocalMatchTurn(
                    id = "turn-0",
                    matchId = MATCH_ID,
                    playerId = PLAYER_ID,
                    turnIndex = 0,
                    guess = "123456",
                    score = 2,
                    serverAcknowledged = true,
                    createdAt = FIXED_NOW_MS + 100,
                ),
                LocalMatchTurn(
                    id = "turn-1",
                    matchId = MATCH_ID,
                    playerId = PLAYER_ID,
                    turnIndex = 1,
                    guess = "654321",
                    score = 4,
                    serverAcknowledged = false,
                    createdAt = FIXED_NOW_MS + 200,
                ),
            )
            turns.forEach { assertEquals(it, repository.recordMatchTurn(it)) }
            assertEquals(turns, repository.loadMatchTurns(MATCH_ID))

            val pendingOperation = PendingSyncOperation(
                id = "sync-1",
                scope = "match",
                entityId = MATCH_ID,
                operationType = SyncOperationType.SUBMIT_TURN,
                payloadJson = """{"turn":1}""",
                endpointPath = "/api/v1/matches/$MATCH_ID/turns",
                method = "POST",
                idempotencyKey = "turn-idempotency-1",
                status = SyncOperationStatus.PENDING,
                retryCount = 2,
                lastError = null,
                createdAt = FIXED_NOW_MS + 300,
                updatedAt = FIXED_NOW_MS + 300,
            )
            assertEquals(pendingOperation, repository.enqueueSyncOperation(pendingOperation))
            assertEquals(
                listOf(pendingOperation),
                repository.loadPendingSyncOperations(SyncOperationStatus.PENDING),
            )

            nowMs += 1_000
            repository.updateSyncOperationStatus(
                operationId = pendingOperation.id,
                status = SyncOperationStatus.FAILED,
                lastError = "network sentinel",
                incrementRetryCount = true,
            )
            val failedOperation = pendingOperation.copy(
                status = SyncOperationStatus.FAILED,
                retryCount = 3,
                lastError = "network sentinel",
                updatedAt = nowMs,
            )
            assertEquals(
                listOf(failedOperation),
                repository.loadPendingSyncOperations(SyncOperationStatus.FAILED),
            )
            assertFalse(repository.loadPendingSyncOperations(SyncOperationStatus.PENDING).contains(failedOperation))

            val snapshot = repository.loadPlatformSnapshot(campaignUpperBound = 3)
            assertEquals(profile, snapshot.playerProfile)
            assertEquals(identityLinks, snapshot.identityLinks)
            assertEquals(listOf(relationship), snapshot.relationships)
            assertEquals(listOf(room), snapshot.rooms)
            assertEquals(listOf(match), snapshot.matches)
            assertEquals(listOf(failedOperation), snapshot.pendingSyncOperations)
        }
    }

    companion object {
        private const val FIXED_NOW_MS = 1_725_000_000_000L
        private const val PLAYER_ID = "player-42"
        private const val PLAYER_NAME = "Persistence Player"
        private const val ROOM_ID = "room-42"
        private const val MATCH_ID = "match-42"
    }
}
