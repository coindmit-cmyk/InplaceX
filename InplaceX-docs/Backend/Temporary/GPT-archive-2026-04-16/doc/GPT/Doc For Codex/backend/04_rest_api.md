---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/backend/04_rest_api.md
version: v1
date: 2026-04-16
---

# REST API

Base path:
`/api/v1`

JSON policy:
- camelCase
- timestamps in ISO-8601 UTC
- IDs are UUID strings

## Auth

### POST /auth/guest
Создаёт или восстанавливает guest session по `installationId`.

Request:
```json
{
  "installationId": "uuid",
  "platform": "android",
  "appVersion": "1.0.0",
  "locale": "ru-RU",
  "regionHint": "RU"
}
```

Response:
```json
{
  "playerId": "uuid",
  "accessToken": "jwt",
  "refreshToken": "opaque-or-jwt",
  "expiresInSec": 3600,
  "account": {
    "providers": ["guest"]
  }
}
```

### POST /auth/google
Логин через Google ID token.

Request:
```json
{
  "idToken": "google-id-token",
  "installationId": "uuid"
}
```

### POST /auth/link/google
Линкует Google к уже авторизованному игроку.

### POST /auth/refresh
Обновляет access token.

## Me / profile / progress

### GET /me
Возвращает профиль и базовые возможности.

### GET /me/progress/inplacex
Возвращает cloud save snapshot.

Response:
```json
{
  "gameSlug": "inplacex",
  "cloudRevision": 12,
  "state": {
    "campaign": {
      "highestUnlockedLevel": 24,
      "stars": 51
    },
    "softCurrency": 120,
    "hintBalance": 4,
    "stats": {
      "pveWins": 18,
      "pvpWins": 3
    }
  },
  "updatedAt": "2026-04-16T10:00:00Z"
}
```

### PUT /me/progress/inplacex
Обновляет snapshot состояния.

Request:
```json
{
  "baseRevision": 12,
  "state": {
    "campaign": {
      "highestUnlockedLevel": 25,
      "stars": 54
    },
    "softCurrency": 130,
    "hintBalance": 3,
    "stats": {
      "pveWins": 19,
      "pvpWins": 3
    }
  }
}
```

Conflict response:
`409 Conflict` with current cloud state.

## Config

### GET /config/app
Возвращает:
- api feature flags
- websocket URL
- ad policy
- region policy
- maintenance flags

Example:
```json
{
  "region": "GLOBAL",
  "wsUrl": "wss://api.example.com/api/v1/realtime/ws",
  "features": {
    "adsEnabled": true,
    "googleLinkEnabled": true,
    "pvpEnabled": false
  },
  "adsPolicy": {
    "placements": {
      "hintPosition": {
        "providers": ["admob", "yandex", "noop"]
      }
    }
  }
}
```

## Ads

### POST /ads/reward-sessions
Создаёт reward session.

Request:
```json
{
  "placement": "hintPosition",
  "rewardKind": "hint",
  "rewardAmount": 1,
  "providerHint": null
}
```

Response:
```json
{
  "rewardSessionId": "uuid",
  "provider": "admob",
  "status": "created",
  "expiresAt": "2026-04-16T10:05:00Z",
  "providerPayload": {
    "adUnitId": "ca-app-pub-xxx/yyy",
    "ssvCustomData": "rewardSessionId=uuid"
  }
}
```

### GET /ads/reward-sessions/{rewardSessionId}
Возвращает состояние:
- `created`
- `shown`
- `completedClient`
- `verifiedProvider`
- `granted`
- `rejected`
- `expired`

Response example:
```json
{
  "rewardSessionId": "uuid",
  "status": "granted",
  "grant": {
    "rewardKind": "hint",
    "rewardAmount": 1,
    "newHintBalance": 5
  }
}
```

### POST /ads/reward-sessions/{rewardSessionId}/client-complete
Используется клиентом после успешного completion callback от ad SDK.
Не выдаёт награду сам по себе, только отмечает client-side completion.

## Rooms / matches

### POST /games/inplacex/rooms
Создаёт комнату.

Request:
```json
{
  "roomType": "privateDuel",
  "config": {
    "mode": "PVP_DUEL",
    "codeLength": 6,
    "allowDuplicates": true,
    "attemptLimit": 12,
    "turnTimeLimitSec": 45,
    "secretSource": "PLAYER",
    "seed": null,
    "hintsEnabled": false
  }
}
```

### POST /games/inplacex/rooms/{roomId}/join
Входит в комнату по `roomId` или invite code.

### POST /games/inplacex/rooms/{roomId}/ready
Помечает игрока готовым.

### POST /games/inplacex/rooms/{roomId}/leave
Выход из комнаты.

### GET /games/inplacex/rooms/{roomId}
Возвращает snapshot комнаты.

### POST /games/inplacex/matches/{matchId}/turns
Отправляет ход.

Request:
```json
{
  "clientTurnId": "uuid",
  "guess": "123456"
}
```

Response:
```json
{
  "matchId": "uuid",
  "turnIndex": 4,
  "score": 2,
  "isSolved": false,
  "matchStatus": "running"
}
```

### GET /games/inplacex/matches/{matchId}
Возвращает текущий state матча.

### GET /games/inplacex/matches/{matchId}/history
Возвращает историю ходов.

## Health

### GET /system/ping
Liveness probe.

### GET /system/health
Readiness probe.

## Error model

Все ошибки в формате:
```json
{
  "errorCode": "progress_revision_conflict",
  "message": "Cloud revision mismatch",
  "details": {}
}
```

## Idempotency

Все write-запросы должны принимать `Idempotency-Key` header.
Особенно:
- `/ads/reward-sessions`
- `/ads/reward-sessions/{id}/client-complete`
- `/games/inplacex/matches/{id}/turns`
