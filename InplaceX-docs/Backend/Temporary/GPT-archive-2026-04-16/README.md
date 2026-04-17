---
project: InplaceX
audience: Both
file: README.md
version: v1
date: 2026-04-16
---

# Пакет документации для InplaceX

Архив подготовлен так, чтобы его можно было распаковать **в корень репозитория**.

Структура подогнана под текущий репозиторий:
- рядом с уже существующей `doc/Human`
- рядом с уже существующей `doc/GPT/ Doc For GPT`
- новая машинная документация лежит в `doc/GPT/Doc For Codex`

## Что внутри

- `doc/GPT/Doc For Codex/backend` — backend-спецификация
- `doc/GPT/Doc For Codex/ads` — рекламный блок
- `doc/GPT/Doc For Codex/connection` — auth, sync, REST, WebSocket
- `doc/Human` — короткая документация для человека
- `doc/GPT/Doc For Codex/backend/openapi` — черновик OpenAPI
- `doc/GPT/Doc For Codex/backend/sql` — черновик SQL-схемы

## Как читать

1. `doc/Human/01_Как_поднять_свой_бэк.md`
2. `doc/Human/02_Реклама_по_странам_и_RU.md`
3. `doc/GPT/Doc For Codex/00_INDEX.md`
4. `doc/GPT/Doc For Codex/backend/04_rest_api.md`
5. `doc/GPT/Doc For Codex/backend/openapi/inplacex-platform-api.yaml`

## Зафиксированные решения

- backend нужен **с первого этапа**
- backend делается как **modular monolith**
- источник истины для прогресса и наград — **свой backend**
- реклама идёт через **provider abstraction**
- reward не выдаётся напрямую из ad SDK callback
- PvP, комнаты, компания и турниры идут **через authoritative server**
- для РФ отдельно описан вариант с **RU-контуром хранения**

## Что этот архив не делает

- не меняет игровую механику
- не привязывает проект навсегда к одному ad provider
- не содержит production-код
