---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/backend/01_architecture_and_stack.md
version: v1
date: 2026-04-16
---

# Backend architecture and stack

## Canonical decision

Backend для InplaceX стартует как **modular monolith** в **отдельном репозитории**.

Не использовать микросервисную архитектуру на первом этапе.

## Recommended stack

### Language/runtime
- Kotlin
- Ktor server

### Data
- PostgreSQL — canonical persistent store
- Redis — ephemeral state, rate limit, matchmaking queues, websocket helper state

### Infra
- Docker Compose для local/dev
- nginx/traefik перед API
- structured JSON logging
- periodic backups for PostgreSQL

### Protocols
- REST/HTTPS — все обычные операции
- WebSocket — realtime rooms and matches

## Why this stack

- тот же язык, что и на Android
- простой старт
- мало moving parts
- удобно генерировать и править Codex'ом
- легко вырастает до нескольких игр

## Repo shape

Рекомендуемое имя репозитория:
`inplacex-platform`

Минимальная структура:
```text
inplacex-platform/
  src/main/kotlin/com/mirkori/platform/
    auth/
    player/
    config/
    ads/
    economy/
    realtime/
    games/
      inplacex/
  src/main/resources/
  build.gradle.kts
```

Допустим и более строгий вариант с internal Gradle submodules, но не обязателен.

## Domain split

### Platform modules
- `auth`
- `player`
- `config`
- `ads`
- `economy`
- `realtime`

### Game-specific modules
- `games/inplacex`

Platform-модули не должны знать про rules конкретной игры.
Game-модуль не должен переопределять auth/economy/config.

## Deployment topology

### Dev / MVP
```text
Android App
   |
HTTPS / WS
   |
API service
  |   |  Redis
  |
PostgreSQL
```

### Later
Можно добавить:
- background jobs
- metrics
- notification worker
- admin service

Но не раньше реальной необходимости.

## Security rules

- TLS only
- access token short-lived
- refresh token rotateable
- refresh token хранить хешем
- all write endpoints support idempotency
- sensitive operations may require integrity token

## RU contour option

Если сервис полноценно работает с данными пользователей из РФ, предусмотреть архитектурную опцию:
- `region = GLOBAL | RU`
- отдельный DB primary/shard для RU
- routing по region policy
- region-aware app config

Это можно не включать в код сразу в полном объёме, но интерфейсы должны допускать такой рост.
