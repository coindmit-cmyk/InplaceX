---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/00_INDEX.md
version: v1
date: 2026-04-16
---

# InplaceX — индекс машинной документации

Этот пакет нужен как рабочая спецификация для генерации и правки кода.

## Контекст проекта

Текущий Android-проект уже существует.
В репозитории есть:
- `app`
- `doc`
- один Android-модуль `:app`
- namespace `com.mirkori.inplacex`

Значит, внедрение нужно проектировать поверх существующего Android-клиента,
а backend держать отдельно.

## Главные решения

1. Backend стартует сразу.
2. Backend делается как modular monolith.
3. Источник истины для прогресса и наград — свой backend.
4. Ads идут через provider abstraction.
5. Reward не выдаётся напрямую из client callback.
6. PvP, party и tournaments идут через authoritative server.
7. Для рынка РФ описан отдельный RU-контур.
8. Игровая логика не смешивается с UI, auth, ads и network.

## Как читать

### Backend
1. `backend/01_architecture_and_stack.md`
2. `backend/02_domain_modules.md`
3. `backend/03_database_schema.md`
4. `backend/04_rest_api.md`
5. `backend/05_realtime_ws_protocol.md`
6. `backend/openapi/inplacex-platform-api.yaml`
7. `backend/sql/inplacex_platform_schema.sql`

### Ads
1. `ads/01_ads_module_overview.md`
2. `ads/02_provider_strategy_and_regions.md`
3. `ads/03_android_integration_contracts.md`
4. `ads/04_reward_verification_flow.md`

### Connection
1. `connection/01_client_network_architecture.md`
2. `connection/02_auth_cloud_save.md`
3. `connection/03_multiplayer_rooms_party_tournaments.md`
4. `connection/04_failures_retries_integrity.md`

### Внешние ограничения
- `98_external_constraints.md`

### Очередь задач
- `99_TASKS_CHECKLIST.md`

## Канонические запреты

Не делать:
- reward logic внутри screen/activity
- backend как набор микросервисов
- P2P для PvP
- Google Play Games как основное хранение прогресса
- hardcoded ad provider в UI

Делать:
- отдельные интерфейсы
- отдельные папки
- отдельные документы
- серверный источник истины
- feature flags и remote config
