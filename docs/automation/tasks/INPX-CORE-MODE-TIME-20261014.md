# INPX-CORE-MODE-TIME-20261014

Источники: InplaceX-docs/Game/GPT/Core Engine Acceptance.md:27, InplaceX-docs/Game/GPT/Match Domain Model.md:6.

Пробел: GameConfig validates its turn-time field, but GameModeDefinition exposes a separate nullable turnTimeLimitSeconds without an init guard. Constructor/copy therefore accept 0/-1 as configured mode timers. The existing Config QA card tests GameConfig only. No GameModeDefinition tests exist; current mode catalog uses 30/null and selected core files are clean. Android owner edits are outside scope.

Production patch и meaningful tests разрешены только в InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/model/GameModeDefinition.kt и InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/model/GameModeDefinitionTest.kt. Planned/needs_task_packet до квалификации стандартного host adapter.

Критерии:

- Add the minimal GameModeDefinition invariant: turnTimeLimitSeconds must be null (unconfigured) or >0. Do not change totalTimeLimitSeconds, synchronize the nested GameConfig timer, alter defaults/families/fields, add timer execution or change Android callers.
- Create one independent JUnit4 method proving direct mode construction rejects turnTimeLimitSeconds=0 and -1 with IllegalArgumentException, accepts null, 1 and 30, and retains accepted values. Use a valid GameConfig and OpponentKind.BOT; exercise the public constructor, not a copy of its require expression.
- Create a second independent method proving a valid mode.copy(turnTimeLimitSeconds=0/-1) is rejected by the same invariant, while copies with null and 60 retain those values. Verify ordinary identity/config fields remain unchanged for valid copies; no reflection, clock simulation, production oracle or weakening.
- Only the selected GameModeDefinition source and new GameModeDefinitionTest may change. All read-only refs, existing tests, owner changes and prepared candidates must remain unchanged. The exact offline new class must pass at least two non-skipped tests and whitespace check, with initial compile-only qualification recorded as tests NOT_RUN. Integration, whole Core/app/backend/Android/device closure remain NOT_RUN.

Проверки: gradlew.bat :InplaceX-bot-core:test --tests com.mirkori.inplacex.core.model.GameModeDefinitionTest --offline --no-daemon --console=plain; git diff --check. Baseline: compile-only passed; tests NOT_RUN; original test count 0.

Зависимости: нет; локальная ветка от develop.

Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-CORE-MODE-TIME-20261014\REPORT.md. Full Core closure/integration/Android/device acceptance NOT_RUN; публикации и аппаратные действия запрещены.

## Фактический результат

agent_done/needs_integrator_review: 2 scoped тестов, 0 failures/errors/skips; source/test diff проверен и сохранён. Commit/integration/full Core/device closure NOT_RUN.
