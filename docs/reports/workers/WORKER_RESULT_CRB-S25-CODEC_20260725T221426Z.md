# Worker Result: CRB-INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S25-CODEC

- Время: `2026-07-25T22:14:26Z`
- Роль: `Worker`
- Worker: `auto-worker-5.5max`
- Ветка: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.5max/crb-intake-design-pd-inplacex-final-20260725-v002-s25-codec/s25a-strict-lexical-json-codec-retry`
- Immutable base: `df21ec9b343a6f6b12fd27199eec6921d67aed4c`
- Результат: `agent_done`
- Следующий владелец: `Integrator`
- Handoff: `integration_requested`

## Результат

S25A восстановлен как узкий transport-neutral read/security slice:

- закрытые typed snapshot, event и result contracts без raw guess/secret;
- deterministic canonical JSON с точным лимитом frame `64 KiB`;
- iterative scanner с лимитом глубины `64`, duplicate-key проверкой после
  Unicode decoding и строгой лексической грамматикой JSON primitive;
- допускаются только точные `true`, `false`, `null` и числа по грамматике
  `-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?`;
- recursive security policy проверяет forbidden names и string values во всех
  known fields, opaque ids и nested payload;
- injected server-keyed HMAC-SHA-256 fingerprint разделён по domain, session и
  participant; secret `CharArray` кодируется прямо в mutable bytes без
  immutable `String`, временные buffers очищаются в `finally`;
- safe read-log attributes содержат только typed metadata и bounded keyed
  pseudonyms session/participant.

Срез намеренно не содержит client intent, command id, authenticated actor,
authenticated-command wrapper, actor factory, public result decoder,
persistence, migration или transport behavior. Actor binding и authoritative
command application остаются за S25B.

## Изменения

- `InplaceX-backend/build.gradle.kts`
- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/session/contract/**`
- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/session/codec/**`
- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/session/security/**`
- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/session/contract/**`
- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/session/codec/**`
- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/session/security/**`
- `CHANGELOG.md`
- этот Worker report.

Все изменённые пути входят в `allowed_paths`; forbidden paths и Task Manager
state не изменялись. Production responsibilities разделены на `14` файлов,
самый крупный — `BoundedJsonScanner.kt`, `261` строка при лимите `400`.

## Покрытие

Добавлено `20` session-тестов:

- canonical round-trip snapshot и всех event variants;
- deterministic encoding всех закрытых result variants;
- exact UTF-8 `64 KiB` boundary и oversized encode/decode;
- malformed JSON, unknown fields/types/version, duplicate и Unicode-escaped
  duplicate keys;
- public decoder rejection для mixed-case literals, leading-zero numbers,
  trailing decimal point и leading plus;
- lexical acceptance точных literals, отрицательных, дробных и exponent number
  forms до последующей typed validation;
- sub-64-KiB input с `10 000` уровнями вложенности возвращает контролируемый
  `IllegalArgumentException`, а не `StackOverflowError`;
- forbidden name/value corpus, known snapshot fields, opaque ids и nested
  payload;
- отрицательный API-контракт на отсутствие intent/actor/command/result-decoder
  surfaces;
- HMAC key/session/participant/domain separation, ASCII validation и очистка
  временного secret buffer на success/failure;
- bounded safe-log pseudonyms и совместимость с общим log sanitizer.

Полный backend test XML содержит `45` тестов, `0` failures/errors/skips.

## Проверки

Process-only окружение Gradle:

```text
JAVA_HOME=/home/main/.local/jdk21
JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11
ANDROID_HOME=/home/main/.local/android-sdk
ANDROID_SDK_ROOT=/home/main/.local/android-sdk
```

| Команда | Статус | Результат |
| --- | --- | --- |
| `bash gradlew :InplaceX-backend:test --tests 'com.mirkori.inplacex.backend.session.*' --rerun-tasks --stacktrace` | passed | `20/20` session-тестов. |
| `bash gradlew :InplaceX-backend:test` | passed | `45/45` backend-тестов. |
| `bash gradlew verifyProject` | passed | `43` Gradle actions; `BUILD SUCCESSFUL`. |
| `git diff --check` | passed | Whitespace errors отсутствуют. |
| changed-path / line-count / forbidden-symbol audit | passed | Scope соответствует packet; максимум `261` production-строка; rejected API и immutable secret conversion отсутствуют. |
| JVM API/bytecode audit | passed | Codec не имеет `decodeResult`/intent API; secret encoder bytecode не вызывает `String(char[])`. |

## Freshness

- Stable Agent Core `v0.4.22.329` прочитан из актуального `origin/release/main`.
- В начале выполнения `origin/develop` и HEAD совпадали с immutable base.
- Во время проверок `origin/develop` продвинулся до `86102f1` только через
  runner-owned Task Manager state и независимый S18 Android/release-isolation
  пакет. Backend session scope, обязательные source refs и текущий packet не
  изменились; merge/rebase не выполнялся.
- Runner lock продолжает указывать на текущие worker, branch и worktree и
  действует до `2026-07-26T06:00:29Z`.

## Cleanup и handoff

- Временные файлы и фоновые процессы не создавались.
- Worker branch, изолированный worktree, source/tests и этот report оставлены
  live для commit/push центральным runner.
- Runner-managed lock намеренно оставлен активным; Worker не редактировал
  queue, locks, events, process logs или integration artifacts.
- Branch не является cleanup candidate до Integrator/Finalizer acceptance.
- Остаточных cleanup blockers нет.

Direct merge не авторизован. Integrator должен независимо перепроверить strict
lexical cases, security tests и file boundaries.

`check_status=passed`
