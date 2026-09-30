# Следующие задачи

Этот список фиксирует ближайшую работу после baseline `0.1.0-project-foundation`.

## P0: стабилизация проекта

- Прогнать `.\gradlew.bat verifyProject` после фиксации baseline и сохранять эту команду как обязательную проверку перед крупными коммитами.
- Проверить, что Android debug APK собирается через `.\gradlew.bat assembleDebug`.
- Убедиться, что `local.properties`, `.env`, `.env.local` и реальные provider ids не попадают в Git.
- Разделить будущие изменения по веткам из проектной схемы: `feature/*`, `fix/*`, `test/*`, `debug/*`, `release/*`.

## P1: продуктовый каркас

- Пройти по `InplaceX-docs/Game/Human/Roadmap.md` и отметить, какие режимы входят в ближайший playable milestone.
- Уточнить UX shell, home, profile, shop, social, company и developer экранов через `InplaceX-docs/Game/Human/Shell UI.md`.
- Согласовать, какие developer/debug элементы остаются debug-only и как они выключаются в release-сборках.

## P1: архитектура и контракты

- Сверить `InplaceX-docs/Game/GPT/Module Map.md`, `Layer Contracts.md` и `Public Interfaces.md` с фактическими Gradle-модулями.
- Описать следующий backend milestone: matchmaking, session lifecycle, persistence или server bot expansion.
- Для новых режимов использовать `GameModeDefinition`, `OpponentProvider`, shell/UX configuration и orchestration вместо копирования движка.

## P2: качество и автоматизация

- Добавить недостающие module-level тесты рядом с новой production-логикой.
- Подготовить короткий checklist для manual QA Android-сборки.
- Рассмотреть CI-проверку для `verifyProject`, когда ветки `develop`/`production` будут окончательно выбраны как основные.

## P2: архивирование

- Не удалять старые документы сразу: переносить спорные материалы в `InplaceX-docs/Legacy/` или `InplaceX-docs/Backend/Temporary/`.
- В каждом новом архивном каталоге держать `README` с датой, источником и причиной архивирования.
- Если файл был частью рабочей истории Git, предпочитать обычное перемещение в архив отдельным коммитом, чтобы история изменений оставалась видимой.
