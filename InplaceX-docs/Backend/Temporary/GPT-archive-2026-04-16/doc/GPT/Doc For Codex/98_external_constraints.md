---
project: InplaceX
audience: Codex
file: doc/GPT/Doc For Codex/98_external_constraints.md
version: v1
date: 2026-04-16
---

# Внешние ограничения, которые Codex должен учитывать

## 1. AdMob нельзя считать универсальным решением для РФ
Архитектура рекламы обязана поддерживать замену провайдера по региону.

## 2. Для rewarded ads награда не должна жить только на клиенте
Нужен backend flow с `rewardSessionId`.

## 3. Play Games Services не является главным хранилищем прогресса
Cloud save и inventory должны жить в собственном backend.

## 4. Android логин — через modern stack
Ориентир:
- Credential Manager
- Sign in with Google
- optional Firebase anonymous/linking

## 5. Для чувствительных действий нужен server-side trust
Использовать:
- access token
- refresh token
- idempotency key
- при необходимости Play Integrity

## 6. РФ-контур нельзя оставлять “на потом”, если там будут личные данные
Если в РФ будут:
- login
- cloud save
- social identity
- push token
- support data

то нужно заранее предусмотреть:
- regional routing
- separate DB primary/shard
- отдельный контур хранения

## 7. Слабая сеть считается нормальным сценарием
Клиент обязан:
- не падать без рекламы
- уметь повторять запросы
- не дублировать критические действия
- различать offline / timeout / provider failure

## 8. Multi-game platform
Backend проектируется так, чтобы обслуживать не только InplaceX.

Следствие:
- platform modules отдельно
- game-specific logic отдельно
- `gameSlug = "inplacex"`
