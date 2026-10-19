# INPX-QA-EASY-PROBE-20261014

Источники: InplaceX-docs/Game/GPT/Bot Brain.md:41, InplaceX-docs/Game/GPT/Bot Brain.md:79.

Пробел: Existing solver simulations check that Easy completes some secrets, but no direct public-API test proves its safe-baseline knowledge and single-position confirmation from a consistent zero-then-one feedback protocol. This scope does not test MEDIUM/grid deduction or reuse the deferred grid goal.

Только InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/bot/BotAgentEasyProbeTest.kt; production InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/bot/BotAgent.kt доступен для чтения.

Критерии:

- Create a fresh fixed-seed EASY BotAgent with permissive four-digit decimal BotMatchRules (allowDuplicates=true, all-same/adjacent/triple prohibitions=false). Before play it has no grid plan. Feed score 0 for its first emitted guess. Snapshot must still have no grid plan, history exactly that one guess with score 0, no resolved positions, candidates per position equal to the nine decimal symbols other than that emitted symbol, and safe symbols exactly that one emitted symbol. Assert these direct protocol outcomes without ScoreCalculator, generated secrets or a replica of the solver.
- In a second independent test start another fresh fixed-seed EASY agent with the same permissive rules. Feed its first guess score 0. Its next guess must differ at exactly one position from that safe baseline. Feed the second guess score 1. Snapshot must contain history [first,second] with scores [0,1], exactly one resolved position equal to the changed position and its second-guess symbol, and that position's candidate set must be the singleton confirmed symbol. This is the documented one-position deduction from a known-zero baseline, with no grid row/tenth-row logic, balance tuning or shared scoring oracle.
- Only create BotAgentEasyProbeTest.kt with two meaningful independent JUnit4 methods. Production BotAgent/BotMatchRules, all existing tests and owner edits remain read-only. Use explicit marked BotAgent API excerpts rather than its full implementation; new-file guards must reject an existing output and compare full production hashes.
- Compile-only qualification is not a test pass. After creating the file run exactly the offline BotAgentEasyProbeTest filter and require at least two non-skipped tests plus new-file whitespace check. Integration, balance benchmarks, backend/Android/state restoration and full Core closure remain NOT_RUN.

Проверки: gradlew.bat :InplaceX-bot-core:test --tests com.mirkori.inplacex.core.bot.BotAgentEasyProbeTest --offline --no-daemon --console=plain; git diff --check. Baseline: compile-only passed; tests NOT_RUN; original test count 0.

Зависимости: нет; локальная ветка от develop.

Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-EASY-PROBE-20261014\REPORT.md. Full Core closure/integration/Android/device acceptance NOT_RUN; публикации и аппаратные действия запрещены.

## Фактический результат

Подготовлен локальный кандидат: 2 новых тестовых методов; адресная проверка 2 тестов, 0 failures/errors/skips. Исходные 0 методов и assertions сохранены. Production и read-only references совпадают с pre-call hashes. Карточка agent_done/needs_integrator_review, lock free, worker_ready false. Commit/integration и полный Core/Android/backend/device closure NOT_RUN.
