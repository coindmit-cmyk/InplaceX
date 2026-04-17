---
project: InplaceX
audience: Human
file: doc/Human/03_Модули_ads_auth_network_для_Android.md
version: v1
date: 2026-04-16
---

# Какие модули добавить в Android-проект

Сейчас проект ещё маленький. На первом шаге **не обязательно** распиливать его на отдельные Gradle modules.

Достаточно сделать отдельные **пакеты-блоки** внутри `app`, а потом при росте вынести их в модули.

## Практический вариант

Внутри `app/src/main/java/com/mirkori/inplacex/` добавить:
- `ads/`
- `auth/`
- `network/`
- `sync/`
- `integrity/`
- `config/`

## Что где лежит

### ads
Только рекламная интеграция:
- provider interfaces
- admob implementation
- yandex implementation
- router
- reward launcher

### auth
Только учётка:
- guest session
- token store
- Google link
- sign out
- session restore

### network
Только транспорт:
- Retrofit API
- OkHttp interceptors
- WebSocket client
- DTO

### sync
Только синхронизация прогресса:
- load cloud
- push local
- resolve conflicts
- retry logic

### integrity
Только проверки клиента перед чувствительными запросами:
- Play Integrity gateway
- binding request hash
- submit token to backend

### config
Только удалённая конфигурация:
- base URL
- websocket URL
- ad policy
- feature flags

## Почему это лучше

Так ты не получишь:
- ad sdk прямо в screen
- auth логику в ViewModel матча
- network-код внутри UI
- reward выдачу из callback в Activity

## Когда уже выносить в Gradle modules

Если появится минимум один из пунктов:
- код разросся
- появились тесты по слоям
- понадобились разные store/build flavor
- несколько человек работают параллельно
- нужен reusable блок для другой игры

Тогда можно выносить в:
- `:core:network`
- `:core:auth`
- `:core:ads`
- `:core:config`

Но сейчас это не обязательно.
