# InplaceX

InplaceX — Kotlin/Gradle проект логической игры на угадывание цифровой последовательности. Первый целевой клиент — Android, но архитектура держится как reusable game platform для будущих режимов, online/backend сценариев и похожих игр.

## Структура

- `InplaceX-android` — Android-клиент, Compose UI, shell, ресурсы и текущий app runtime.
- `InplaceX-bot-core` — общая JVM-логика бота, правил, scoring, validation и shared game config.
- `InplaceX-logging` — общий контракт логирования, уровни, sink-и и redaction чувствительных атрибутов.
- `InplaceX-backend` — backend-facing JVM-модуль, включая server-side bot player adapter.
- `InplaceX-docs` — каноническая документация продукта, архитектуры, ADR, UX и legacy notes.
- `schemas/online` — versioned machine-readable REST/WebSocket/security contracts.
- `docs` — короткие корневые справки по архитектуре, модулям, разработке и тестированию.
- `.agent` и `AGENTS.md` — правила и контекст для AI-агентов.

## Локальная настройка

- `local.properties` не коммитится. Android Studio может пересоздать `sdk.dir`; CLI-сборки также могут использовать `ANDROID_HOME` или `ANDROID_SDK_ROOT`.
- Используйте локальный JDK через `JAVA_HOME`. JBR из Android Studio подходит для локальных CLI-проверок.
- Безопасные placeholder-настройки provider ids лежат в `InplaceX-android/provider-config.example.properties`.
- Реальные токены, ключи, provider ids и секреты не должны попадать в репозиторий.

## Команды

Из корня репозитория в PowerShell:

```powershell
.\gradlew.bat verifyProject
.\gradlew.bat assembleDebug
.\gradlew.bat cleanLocalDiagnostics
```

На Unix-like shell используйте `./gradlew`.

`verifyProject` запускает backend, bot-core, logging и Android debug unit tests. `assembleDebug` собирает Android debug APK. `cleanLocalDiagnostics` удаляет локальные logcat dumps, heap dumps и screenshot captures.

## CI и артефакты

GitHub Actions запускает blocking-проверки `verifyProject` и `assembleDebug` с
Java 21 launcher и Java 11 project toolchains. Debug APK сопровождается
манифестом версии, полного commit SHA и SHA-256; детали описаны в
[`docs/automation/CI_FOUNDATION.md`](docs/automation/CI_FOUNDATION.md).

Instrumentation и release checks пока отображаются как non-blocking jobs до
завершения выделенных задач.

## Рабочее разделение

- Gameplay, UI, mobile integration: `InplaceX-android`.
- Shared bot/game logic: `InplaceX-bot-core`.
- Shared logging contracts: `InplaceX-logging`.
- Sync, PvP services, server bot runtime, ranking, cloud logic: `InplaceX-backend`.
- Product, architecture, ADR, design references: `InplaceX-docs`.

## Документация

Быстрый старт:

- `docs/architecture.md`
- `docs/modules.md`
- `docs/development.md`
- `docs/testing.md`
- `InplaceX-logging/README.md`

Канонические документы:

- `InplaceX-docs/Game/Human/README.md`
- `InplaceX-docs/Game/GPT/README.md`
- `InplaceX-docs/Backend/Online Contracts.md`
- `schemas/online/v1/README.md`

Перед работой AI-агента:

- `AGENTS.md`
- `.agent/general.md`
- `.agent/project.md`
