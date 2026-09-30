---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/connection/03_multiplayer_rooms_party_tournaments.md
version: v1
date: 2026-04-16
---

# Multiplayer, rooms, party, tournaments

## Canonical decision

Все online режимы идут **через server authority**.
P2P не использовать.

## Room model

### private duel
Два игрока, разные секреты, ходы по очереди.

### private race
Два игрока, один секрет, параллельные ходы.

### party race
N игроков, один общий секрет, приватная компания.

### tournament room
Комната, созданная в рамках турнирной сетки.

## Lifecycle

1. owner creates room
2. players join
3. players ready
4. server starts match
5. players submit turns
6. server emits results
7. server finalizes winner
8. room becomes finished or returns to waiting state

## Why not P2P

- NAT/firewall issues
- reconnect complexity
- cheating risk
- no authoritative history
- tournaments become harder
- spectator/replay become harder

## Party support

Для режима “компанией” не нужен новый rule engine.
Нужен режим:
- `roomType = partyRace`
- один общий секрет
- несколько participants
- winner by attempts, then time

## Tournament support

Tournament layer должен опираться на те же сущности:
- room
- match
- participant

Надстройка добавляет:
- bracket
- round
- seeding
- advancing rules

## Required backend pieces before tournaments

- stable auth
- stable rooms
- stable match lifecycle
- deterministic history
- reconnect-safe realtime
