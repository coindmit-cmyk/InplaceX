---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/backend/02_domain_modules.md
version: v1
date: 2026-04-16
---

# Domain modules

## Core entities

### Player
Уникальный пользователь платформы.

Поля:
- `playerId`
- `createdAt`
- `displayName`
- `locale`
- `regionCode`
- `status`

### PlayerIdentity
Внешняя идентичность, привязанная к player.

Поля:
- `provider`
- `providerSubject`
- `linkedAt`

Поддерживаемые провайдеры:
- `guest`
- `google`
- `facebook`
- `apple`
- `email`

### PlayerProgress
Состояние игры для `gameSlug = inplacex`.

Поля:
- `cloudRevision`
- `campaignState`
- `stats`
- `softCurrency`
- `hintBalance`
- `updatedAt`

### AdRewardSession
Сессия выдачи награды за рекламу.

Поля:
- `rewardSessionId`
- `playerId`
- `placement`
- `provider`
- `state`
- `rewardKind`
- `rewardAmount`
- `createdAt`
- `expiresAt`
- `grantedAt`

States:
- `created`
- `shown`
- `completedClient`
- `verifiedProvider`
- `granted`
- `rejected`
- `expired`

### Room
Лобби для матча.

Поля:
- `roomId`
- `gameSlug`
- `roomType`
- `status`
- `inviteCode`
- `config`
- `ownerPlayerId`

Room types:
- `privateDuel`
- `privateRace`
- `partyRace`
- `tournamentRoom`

Room states:
- `waiting`
- `ready`
- `inProgress`
- `finished`
- `cancelled`

### Match
Конкретная игровая сессия.

Поля:
- `matchId`
- `roomId`
- `mode`
- `status`
- `codeLength`
- `allowDuplicates`
- `attemptLimit`
- `turnTimeLimitSec`
- `secretSource`
- `seed`
- `winnerPlayerId`

### MatchParticipant
Участник матча.

Поля:
- `playerId`
- `status`
- `attemptsUsed`
- `elapsedMs`
- `finishedAt`
- `secretValueEnc`
- `secretSha256`

### MatchTurn
Один ход игрока.

Поля:
- `turnId`
- `matchId`
- `playerId`
- `clientTurnId`
- `turnIndex`
- `guess`
- `score`
- `createdAt`

## Service boundaries

### auth
- guest login
- provider link
- token issue/refresh
- logout/session revoke

### player
- profile
- devices
- region
- basic preferences

### config
- app config
- feature flags
- provider policy

### ads
- reward sessions
- provider callbacks
- reward grants
- audit

### economy
- soft currency
- hint balance
- ledger-like updates

### realtime
- websocket sessions
- room subscriptions
- event fanout

### games/inplacex
- room config validation
- match creation
- turn validation
- score calculation
- winner calculation
- history/replay

## Multi-game rule

Все общие сущности должны иметь `gameSlug`, если есть вероятность их использования другими играми платформы.
