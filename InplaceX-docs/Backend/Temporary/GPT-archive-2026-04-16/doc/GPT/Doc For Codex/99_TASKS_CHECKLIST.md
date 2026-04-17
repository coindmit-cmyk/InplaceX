---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/99_TASKS_CHECKLIST.md
version: v1
date: 2026-04-16
---

# Очередь задач для Codex

## Wave 1 — backend foundation
- создать отдельный backend repo
- поднять Ktor service
- подключить PostgreSQL
- подключить Redis
- сделать `/api/v1/config/app`
- сделать `/api/v1/auth/guest`
- сделать `/api/v1/auth/refresh`
- сделать `/api/v1/me`
- сделать `/api/v1/me/progress/inplacex`

## Wave 2 — Android transport/auth/config
- добавить network layer
- добавить token store
- добавить auth repository
- добавить app config repository
- добавить base REST client
- добавить websocket client skeleton

## Wave 3 — ads abstraction
- добавить `ads/api`
- добавить `ads/admob`
- добавить `ads/noop`
- описать `RewardedPlacement`
- добавить backend endpoint `/api/v1/ads/reward-sessions`
- интегрировать server-backed reward flow

## Wave 4 — region provider strategy
- добавить `AdsRouter`
- добавить provider policy из app config
- подготовить `ads/yandex` интерфейс и реализацию
- сделать fallback на `NoAdsProvider`

## Wave 5 — Google link + cloud save
- сделать link account flow
- сделать restore session
- сделать sync progress
- добавить conflict policy
- добавить logout/unlink rules

## Wave 6 — multiplayer
- rooms REST API
- websocket room protocol
- submit turn
- room state snapshots
- duel/race online
- history/replay

## Wave 7 — tournaments / party
- party rooms
- tournament entities
- bracket lifecycle
- tournament websocket events
