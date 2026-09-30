# Worker Result: INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S25

- Время: `2026-07-25T20:42:07Z`
- Worker: `auto-worker-5.5max`
- Ветка: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5max/intake-design-pd-inplacex-final-20260725-v002-s25/idempotent-session-persistence-and-reconnect-retry-20260725T201816Z`
- Immutable base: `e4c2801d145c3722bddc2d90f97ff2221aad6f7c`
- Результат: `agent_done`
- Следующий владелец: `Integrator`
- Событие handoff: `integration_requested`

## Результат

Реализована durable persistence-граница публичного состояния дуэли:

- V3 создаёт отдельные таблицы публичного состояния, snapshot checkpoints,
  упорядоченных событий и immutable command receipts.
- Legacy `duel_events` получают детерминированный `event_seq`, но их
  непроверенные payload не копируются в публичный replay.
- Legacy-сессии помечаются как `public_state_available = FALSE`; `config_json`
  не выдаётся за авторитетный snapshot.
- Существующий `JdbcSessionRepository.appendCommand` оставлен как узкий
  compatibility seam и делегирует единственной typed atomic реализации.
- Команда, optimistic revision, snapshot, событие, receipt и retention
  обновляются в одной транзакции.
- Deduplication имеет scope `session + actor + clientCommandId`; SHA-256
  fingerprint вычисляется сервером из валидированного typed command content.
- Receipt хранит исходные typed result и snapshot, поэтому старый retry после
  последующих ходов возвращает исходный immutable результат.
- Reconnect возвращает ровно один режим: contiguous replay, checkpoint snapshot
  со строго более поздними событиями или явный replay gap.
- Reconnect читает captured upper cursor в repeatable-read транзакции, а выдача
  и retention имеют жёсткий bound.
- Snapshot, event и command-result проходят closed schema, canonical JSON,
  recursive forbidden-field/value guard и лимит `64 KiB` по UTF-8.
- На persistence/reconnect переходах добавлены безопасные структурированные
  логи без command content и публичных payload.

## Граница восстановления

Результат обеспечивает durable public reconnect state. Он не заявляет полное
восстановление активного матча после рестарта процесса: для этого отдельно
нужны encrypted secret persistence и rehydration authoritative aggregate.
Dispatcher/Architect должен завести блокирующую design-задачу на эту границу до
любого заявления о full active-match recovery.

## Изменённые пути

- `CHANGELOG.md`
- `InplaceX-backend/build.gradle.kts`
- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/persistence/DatabaseMigrations.kt`
- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/persistence/JdbcRepositories.kt`
- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/persistence/session/JdbcDurableDuelSessionRepository.kt`
- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/persistence/session/PublicSessionSchema.kt`
- `InplaceX-backend/src/main/resources/db/migration/V3__add_durable_duel_session_state.sql`
- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/persistence/JdbcPersistenceTest.kt`
- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/persistence/session/JdbcDurableDuelSessionPostgresTest.kt`
- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/persistence/session/JdbcDurableDuelSessionRepositoryTest.kt`
- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/persistence/session/PublicSessionSchemaTest.kt`
- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/persistence/session/SessionTestFixtures.kt`
- `docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S25_20260725T204207Z.md`

Все пути входят в `allowed_paths`. `AiStudio/Task_manager`, forbidden paths и
старые Worker-отчёты не изменялись.

## Проверки

Окружение Gradle:

```text
JAVA_HOME=/home/main/.local/jdk21
JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11
```

| Команда | Статус | Результат |
| --- | --- | --- |
| `bash gradlew :InplaceX-backend:compileKotlin :InplaceX-backend:compileTestKotlin --stacktrace` | passed | Production и test Kotlin скомпилированы. |
| `bash gradlew :InplaceX-backend:test --tests 'com.mirkori.inplacex.backend.persistence.JdbcPersistenceTest' --tests 'com.mirkori.inplacex.backend.persistence.session.PublicSessionSchemaTest' --tests 'com.mirkori.inplacex.backend.persistence.session.JdbcDurableDuelSessionRepositoryTest'` | passed | 13 H2/codec тестов, 0 failures. Первый прогон выявил только тестовую разницу `Integer`/`Long`; assertion исправлен, повторный прогон зелёный. |
| `bash gradlew :InplaceX-backend:test --tests 'com.mirkori.inplacex.backend.persistence.session.JdbcDurableDuelSessionPostgresTest' --stacktrace` | passed | 4 реальных PostgreSQL 16/Testcontainers теста, 0 failures/skips. |
| `bash gradlew :InplaceX-backend:test` | passed | 37 backend тестов, 0 failures/errors/skips. |
| `bash gradlew verifyProject` | passed | Первый прогон: 43 actions (7 executed, 25 from cache, 11 up-to-date); финальный прогон после codec hardening: 43 up-to-date. |
| `git diff --check` | passed | Whitespace errors отсутствуют. |

## Покрытие обязательной repair-спецификации

1. Backend suite — зелёный.
2. `verifyProject` — зелёный.
3. `git diff --check` — зелёный.
4. PostgreSQL migration V1/V2 → replacement V3 проверена с legacy backfill,
   unique constraint и запретом replay непроверенных payload.
5. Rollback проверен свежей реально исполняемой V4 на H2 и PostgreSQL.
6. Два PostgreSQL commit на одной revision: ровно один успех, один
   `SessionRevisionConflictException`, без частичных строк.
7. Retry старой команды после новых ходов возвращает исходные receipt/result и
   не создаёт второе событие.
8. Изменённый content с тем же actor/command id даёт
   `IdempotencyKeyReusedException`; другой actor имеет независимый scope.
9. Инъекция отказа между state update и event insert откатывает session, state,
   event, snapshot и receipt.
10. Покрыты contiguous replay, snapshot + later events, replay gap, retention,
    repository restart и concurrent commit во время reconnect.
11. Security corpus покрывает invalid JSON, unknown fields/types, nested и
    Unicode-escaped forbidden keys, token-shaped/raw digit values и точную
    границу `64 KiB` для snapshot и event.
12. Все существующие backend тесты сохранены и зелёные.

## Cleanup

Временные файлы и фоновые процессы не оставлены. Изолированный worktree и
runner-managed lock сохранены для центрального runner, который выполнит commit,
push и дальнейшую синхронизацию.

`check_status=passed`
