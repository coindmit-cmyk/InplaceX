# Project Baseline

Дата фиксации: 2026-06-03.

Условная версия состояния: `0.1.0-project-foundation`.

Этот baseline фиксирует не пользовательский релиз APK, а рабочую архитектурную точку проекта: текущие модули, правила разработки, документацию, проверки и ближайшие ограничения.

## Зафиксированное состояние

- Android-клиент живет в `InplaceX-android/app` и остается текущим app runtime.
- Общая логика игры и бота живет в `InplaceX-bot-core`.
- Backend-facing runtime живет в `InplaceX-backend`; сейчас основной готовый сценарий там связан с server-side bot participant.
- Общий контракт логирования вынесен в `InplaceX-logging`.
- Общая test-only инфраструктура вынесена в `InplaceX-test-support`.
- Короткая корневая документация живет в `docs/`.
- Каноническая продуктовая и архитектурная документация остается в `InplaceX-docs/`.
- Правила для AI-агентов зафиксированы в `AGENTS.md` и `.agent/`.

## Рабочие команды

```powershell
.\gradlew.bat verifyProject
.\gradlew.bat assembleDebug
.\gradlew.bat cleanLocalDiagnostics
```

`verifyProject` должен запускать:

- `:InplaceX-backend:test`
- `:InplaceX-bot-core:test`
- `:InplaceX-logging:test`
- `:InplaceX-test-support:test`
- `:app:testDebugUnitTest`

## Документы-источники

- Быстрый вход: `README.md`.
- Разработка: `docs/development.md`.
- Модули: `docs/modules.md`.
- Архитектура: `docs/architecture.md`.
- Тестирование: `docs/testing.md`.
- Каноника для людей: `InplaceX-docs/Game/Human/README.md`.
- Каноника для GPT/агентов: `InplaceX-docs/Game/GPT/README.md`.
- ADR: `InplaceX-docs/Game/GPT/ADR/`.

## Архивы и legacy

Старые материалы не удаляются без отдельного решения. Для просмотра истории и устаревших источников используются:

- `InplaceX-docs/Legacy/`
- `InplaceX-docs/Game/Human/Legacy/`
- `InplaceX-docs/Game/GPT/Legacy/`
- `InplaceX-docs/Backend/Temporary/`

Новые лишние или временные документы следует переносить в подходящий legacy/temporary каталог с коротким `README`, объясняющим происхождение и статус.

## Ограничения baseline

- Это не production release и не финальная игровая версия.
- Backend matchmaking, persistence, online transport, ranking и cloud sync остаются будущей работой.
- Provider ids, токены, ключи и локальная конфигурация не входят в baseline и не должны коммититься.
- Перед будущей работой нужно начинать с `git status`, `AGENTS.md`, `.agent/` и релевантных документов из `InplaceX-docs/`.
