---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/backend/03_database_schema.md
version: v1
date: 2026-04-16
---

# Database schema notes

Ниже логическая схема. SQL-черновик лежит в `backend/sql/inplacex_platform_schema.sql`.

## Main tables

### players
Хранит базовую запись игрока.

### player_identities
Хранит привязанные провайдеры логина.

### refresh_sessions
Хранит refresh sessions:
- `refreshSessionId`
- `playerId`
- `refreshTokenHash`
- `expiresAt`
- `revokedAt`

### player_devices
Устройства / installationId.

### player_progress
Снимок состояния игрока по игре.

Ключ:
- `(playerId, gameSlug)`

### ad_reward_sessions
Серверный трекинг рекламной награды.

### economy_ledger
Аудит движения подсказок/валюты.

### rooms
Комнаты.

### room_members
Участники комнаты.

### matches
Матчи.

### match_participants
Состояние участника в матче.

### match_turns
Все ходы.

### processed_idempotency_keys
Защита от повторного исполнения write-операций.

## Important indexes

### player_identities
- unique `(provider, providerSubject)`

### player_progress
- unique `(playerId, gameSlug)`

### ad_reward_sessions
- index `(playerId, createdAt desc)`
- unique `(provider, providerTransactionId)` where not null

### room_members
- unique `(roomId, playerId)`

### match_turns
- unique `(matchId, playerId, clientTurnId)`
- index `(matchId, createdAt)`

## Secret storage

Для online PvP server authoritative match logic должна иметь доступ к секрету.
Рекомендуемый компромисс:
- хранить `secretValueEnc`
- отдельно хранить `secretSha256`
- никогда не отдавать секрет наружу через API

## Economy rule

Награды и траты не менять “в лоб” через апдейт balance.
Всегда писать запись в `economy_ledger`, а агрегированное значение обновлять транзакционно.

## Region-ready note

Если включается RU-контур, схема может быть:
- одинаковая по структуре
- разнесённая по разным кластерам / базам
- routing по `regionCode` или account region
