---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/backend/06_rollout_plan.md
version: v1
date: 2026-04-16
---

# Backend rollout plan

## Phase 1 — foundation
Deliver:
- guest auth
- refresh flow
- me/profile
- config/app
- progress snapshot
- PostgreSQL
- Redis
- health endpoints

## Phase 2 — ads reward path
Deliver:
- reward sessions
- reward status polling
- server grant path
- economy ledger
- provider callback endpoints

## Phase 3 — account linking
Deliver:
- google login
- link/unlink
- session restore
- cloud sync conflict handling

## Phase 4 — rooms and matches
Deliver:
- rooms
- room members
- ready/start
- match state
- turn submit
- history

## Phase 5 — realtime
Deliver:
- websocket auth
- room subscription
- match events
- reconnect snapshot

## Phase 6 — party and tournaments
Deliver:
- party rooms
- bracket entities
- tournament match generation
- tournament events

## Non-goals before Phase 4
Do not build:
- full social graph
- clan system
- microservices split
- cross-game admin panel
