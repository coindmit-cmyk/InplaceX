# Архитектура InplaceX

InplaceX — Kotlin/Gradle multi-module проект с Android-клиентом, общим bot-core, общим logging contract, отдельной test-only инфраструктурой и backend-facing runtime. Подробные канонические документы находятся в `InplaceX-docs/`; этот файл нужен как быстрый вход в архитектуру.

## Основные слои

- `game core`: матчевый цикл, конфиг игры, генерация секрета, валидация, scoring, доменные модели.
- `bot core`: общий мозг бота, правила, стратегии сложности, grid-планы и совместимый фасад.
- `logging`: общий контракт логирования, уровни, sink'и и redaction чувствительных атрибутов.
- `test support`: общие test-only sink'и и helper'ы для unit-тестов и ручных runner'ов.
- `game platform`: конфигурация, локализация, online/service boundaries, навигационные и provider-контракты.
- `app/client`: Android Compose UI, shell, экраны, ресурсы и интеграция с платформенными сервисами.
- `backend/runtime`: серверные адаптеры, сейчас в первую очередь `ServerBotPlayer` как участник матча.

## Физические модули

- `InplaceX-android/app` (`:app`) — Android приложение.
- `InplaceX-bot-core` (`:InplaceX-bot-core`) — общая JVM-библиотека для бота и части игровой логики.
- `InplaceX-logging` (`:InplaceX-logging`) — общая JVM-библиотека для безопасного логирования.
- `InplaceX-test-support` (`:InplaceX-test-support`) — общая JVM-библиотека для test-only sink'ов и helper'ов.
- `InplaceX-backend` (`:InplaceX-backend`) — JVM-модуль для backend runtime.
- `InplaceX-docs` — каноническая документация продукта и архитектуры.

## Правила зависимостей

- Android app может зависеть от `InplaceX-bot-core` и `InplaceX-logging`.
- Test source set'ы и ручные benchmark/diagnostic runner'ы могут зависеть от `InplaceX-test-support`.
- Backend может зависеть от `InplaceX-bot-core` и `InplaceX-logging`.
- `InplaceX-bot-core` не должен зависеть от Android UI, Android framework или backend-модуля.
- Новые режимы должны добавляться через `GameModeDefinition`, `OpponentProvider`, UX/shell-настройки и orchestration, а не через копирование движка.

## Где читать подробнее

- `InplaceX-docs/Game/GPT/Module Map.md`
- `InplaceX-docs/Game/GPT/Layer Contracts.md`
- `InplaceX-docs/Game/GPT/Match Domain Model.md`
- `InplaceX-docs/Game/GPT/Public Interfaces.md`
- `InplaceX-docs/Game/GPT/Server Bot Runtime.md`
