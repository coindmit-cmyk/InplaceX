---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/connection/02_auth_cloud_save.md
version: v1
date: 2026-04-16
---

# Auth and cloud save

## Identity model

Canonical identity hierarchy:
1. guest
2. guest linked to Google
3. later optional link to other providers

## Guest flow

### First launch
1. generate `installationId`
2. call `POST /auth/guest`
3. store tokens
4. fetch `GET /me`
5. fetch `GET /me/progress/inplacex`

## Google link flow

1. user initiates link
2. client gets Google ID token
3. call `POST /auth/link/google`
4. backend links identity
5. client updates account state

## Token storage

Recommended:
- access token in memory
- refresh token in encrypted local storage
- never store raw access token inside UI state

## Cloud save rule

Server is source of truth.

### Read
- on app start
- on successful login/link
- on foreground restore if session changed

### Write
- after meaningful progress change
- after match finished
- after reward grant
- after hint spend

## Conflict policy

MVP policy:
- client sends `baseRevision`
- server rejects outdated write with `409`
- client refetches latest cloud state
- resolver decides merge or overwrite based on policy

Recommended merge policy:
- `highestUnlockedLevel` -> max
- per-level best result -> min attempts / min time
- balances -> server authoritative
- stats counters -> additive only if event origin known

Если merge слишком сложный на первом этапе, сервер authoritative snapshot допускается как MVP,
но conflict handling должен быть явно реализован.

## Logout / unlink rules

- guest identity нельзя “отвязать”
- linked Google можно отвязать только если останется другой valid login path
- logout не должен удалять cloud save
