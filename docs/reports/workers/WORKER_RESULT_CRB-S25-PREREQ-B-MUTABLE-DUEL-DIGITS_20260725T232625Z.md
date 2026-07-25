# Worker result: CRB-S25-PREREQ-B-MUTABLE-DUEL-DIGITS

## Итог

- Task: `CRB-S25-PREREQ-B-MUTABLE-DUEL-DIGITS`
- Worker: `auto-worker-5.5max`
- Result: `agent_done`
- `check_status=passed`
- Next owner: `Integrator`
- Required event: `integration_requested`

Канонический duel engine переведён на одноразовые mutable digit commands без
создания immutable plaintext-копий. `DuelMatch` больше не имеет `String`
overload для секрета или догадки, не сохраняет raw guesses и очищает private
secret buffers при завершении матча, явном `close()` и неожиданной ошибке.

## Execution authority и freshness

- Immutable execution base:
  `f0f79da20dbcb0ebdab3c4670e313f268b0d6c2c`.
- До первой записи `HEAD` и свежий `origin/develop` совпадали с immutable base.
- Task row имел `worker_ready=true`, `packet_schema_version=2` и
  `status=in_progress`.
- Task row, runner-owned lock, worker id, machine id, branch и isolated
  worktree совпали; lease действовал до `2026-07-26T07:11:54Z`.
- Task Manager, lock, events и runner-owned state Worker не изменял.

## Реализация

- `GuessValidator` получил parity overload для `CharArray`; обе input-дорожки
  теперь принимают только ASCII `0..9` и сохраняют прежний порядок typed
  validation reasons.
- `ScoreCalculator` получил allocation-free `CharArray` overload с прежней
  exact-position семантикой.
- `MutableDuelCommand.Secret` и `.Guess`:
  - копируют digits во owned buffer;
  - немедленно очищают caller buffer;
  - допускают только одно consume;
  - очищают owned buffer в `finally` на success, rejection и exception;
  - очищают неиспользованный buffer при `close()`;
  - не имеют `String` factory/getter и возвращают только redacted `toString()`.
- `DuelMatch`:
  - принимает только типизированные mutable-команды;
  - хранит секреты только в private `CharArray`;
  - сохраняет в `DuelAttempt` только attacker, exact score и turn number;
  - очищает оба секрета при win/attempt-limit finish, close и fail-closed
    exception route;
  - сохраняет viewer-neutral readiness после terminal zeroization;
  - отклоняет повторную установку секрета вне setup;
  - сохраняет atomic state на typed rejection.
- Добавлены reflection и classfile constant-pool guards против `String(char[])`
  и `concatToString`, а также hostile zeroization, non-ASCII, config parity,
  double-consume и state-atomicity тесты.
- `CHANGELOG.md` обновлён записью о secure mutable duel boundary.

## Изменённые пути

- `InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/engine/GuessValidator.kt`
- `InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/engine/ScoreCalculator.kt`
- `InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/engine/GuessValidatorTest.kt`
- `InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/engine/ScoreCalculatorTest.kt`
- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/domain/duel/DuelMatch.kt`
- `InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/session/domain/MutableDuelCommand.kt`
- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/domain/duel/DuelMatchTest.kt`
- `InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/session/domain/MutableDuelCommandTest.kt`
- `CHANGELOG.md`
- этот Worker report.

## Проверки

Process-only environment:

```text
JAVA_HOME=/home/main/.local/jdk21
JAVA_TOOL_OPTIONS=-Dorg.gradle.java.installations.paths=/home/main/.local/jdk11
ANDROID_HOME=/home/main/.local/android-sdk
ANDROID_SDK_ROOT=/home/main/.local/android-sdk
```

1. `bash gradlew :InplaceX-bot-core:test --rerun-tasks`
   — `PASSED`, `BUILD SUCCESSFUL`.
2. `bash gradlew :InplaceX-backend:test --rerun-tasks`
   — final run `PASSED`, `BUILD SUCCESSFUL`.
3. `bash gradlew verifyProject`
   — `PASSED`, `BUILD SUCCESSFUL`.
4. `git diff --check`
   — `PASSED`.

Во время разработки первый backend compile выявил недоступность private nested
constructor, а следующий тестовый прогон — ложноположительный descriptor-only
bytecode guard. Оба дефекта исправлены до финальных обязательных прогонов:
фабрики остались ownership-safe, а guard теперь разрешает собственный
`CharArray` constructor и отклоняет только MethodRef на
`java/lang/String.<init>([C...)`.

## Cleanup и handoff

- Временные файлы и фоновые процессы не создавались.
- Worktree и branch оставлены центральному runner для commit/push.
- Runner-owned lock оставлен активным и не изменялся.
- Cleanup candidates отсутствуют.
- Integrator должен выполнить independent strong review и принять
  `integration_requested` handoff; direct merge не авторизован.
