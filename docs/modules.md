# Модули InplaceX

## `:app`

Путь: `InplaceX-android/app`

Назначение: Android-клиент, Compose UI, shell, навигация, экраны, Android-ресурсы, локальные репозитории и интеграция с platform/service контрактами.

Проверки:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

## `:InplaceX-bot-core`

Путь: `InplaceX-bot-core`

Назначение: общая transport-agnostic логика бота и игры: `BotAgent`, `BotSolver`, правила бота, grid catalog, scoring, validation, secret generation и `GameConfig`.

Проверки:

```powershell
.\gradlew.bat :InplaceX-bot-core:test
```

Дополнительные benchmark и diagnostic runner'ы находятся в модуле и относятся к dev/test инфраструктуре.

## `:InplaceX-logging`

Путь: `InplaceX-logging`

Назначение: общий logging contract для модулей проекта: уровни логов, `LogEvent`, `LogSink`, no-op sink и redaction чувствительных атрибутов по ключам.

Подробности: `InplaceX-logging/README.md`.

Проверки:

```powershell
.\gradlew.bat :InplaceX-logging:test
```

## `:InplaceX-test-support`

Путь: `InplaceX-test-support`

Назначение: общая JVM-библиотека для тестовых sink'ов и test-only инфраструктуры, которую используют unit-тесты и ручные benchmark/diagnostic runner'ы.

Подробности: `InplaceX-test-support/README.md`.

Проверки:

```powershell
.\gradlew.bat :InplaceX-test-support:test
```

## `:InplaceX-backend`

Путь: `InplaceX-backend`

Назначение: backend-facing runtime, сейчас серверный бот-участник и связанные контракты. Будущие зоны ответственности: matchmaking, PvP services, ranking, cloud sync и entitlement validation.

Проверки:

```powershell
.\gradlew.bat :InplaceX-backend:test
```

## `InplaceX-docs`

Путь: `InplaceX-docs`

Назначение: главный источник продуктовых, UX, архитектурных и машинно-ориентированных документов.

Индексы:

- `InplaceX-docs/Game/Human/README.md`
- `InplaceX-docs/Game/GPT/README.md`
