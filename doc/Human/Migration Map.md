# Migration Map

Этот файл фиксирует переход от старой структуры документации к новой канонической базе.

## Новая база

Основой проекта считаются:

- `Vision.md`
- `Game Modes and Rules.md`
- `Platform Vision.md`
- `UX Flows and Screen Map.md`
- `Configs Localization Branding.md`
- `Integrations.md`
- `Roadmap.md`
- `ADR Index.md`

## Где теперь legacy

- старые human-документы: `doc/Human/Legacy/V1`
- старые GPT-документы: `doc/GPT/Legacy/V1`

## Соответствие старых документов

- `1. Общая концепция.md` -> `Vision.md`
- `2. Параметры игры.md` -> `Game Modes and Rules.md`
- `3. Игровые режимы.md` -> `Game Modes and Rules.md`
- `8. UX и интерфейс.md` -> `UX Flows and Screen Map.md`
- `9. Платформы и приоритеты.md` -> `Platform Vision.md`
- `Основы V1.md` -> набор новых базовых документов и ADR
- `Devops/Screens/*` -> historical screen notes, не канонический источник

## Правило дальше

Новые решения вносятся только в новую структуру.

Legacy обновляется только если нужно сохранить исторический контекст, но не как рабочая база для проектирования.
