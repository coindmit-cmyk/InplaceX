# INPX-QA-GRID-KNOWLEDGE-20261014

Фактический результат 2026-10-14: needs_worker_fix/blocked_by_budget. Адресная проверка выполнила 5 тестов: 1 failure, 0 errors/skips. Новый assertion на строке 91 сравнивает Map ожидаемых кандидатов с List из snapshot. Четыре модельных вызова учтены; repair вернул needs_context, правка не применена. Ветка test/grid-knowledge-contract-20261014 и все квитанции сохранены. Новый ID или пятый вызов для обхода лимита запрещены; точный шаг продолжения находится в agent-runtime/automation-8h-20261014/tasks/INPX-QA-GRID-KNOWLEDGE-20261014/deferred-state.json. Продуктовая задача не done; integration/full Core closure NOT_RUN.

Источники: InplaceX-docs/Game/GPT/Bot Brain.md:41, InplaceX-docs/Game/GPT/Bot Brain.md:124, InplaceX-docs/Game/GPT/Bot Brain.md:132.

Пробел: Existing BotGridCatalogTest covers catalog size, cycle geometry and scrambled first rows, while BotSolverSimulationTest covers end-to-end solving. Neither directly checks the documented early/tenth-row deduction against public BotAgent candidate/safe-symbol snapshots while preserving only actual move history.

Только InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/bot/BotGridCatalogTest.kt; production InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/bot/BotAgent.kt доступен для чтения.

Критерии:

- Add a JUnit4 method with a fresh MEDIUM BotAgent, fixed seed, permissive four-digit decimal rules (allowDuplicates=true, all-same/adjacent/triple prohibitions=false). Play the first two emitted grid guesses and provide score 2 for each. This is a consistent protocol fixture: two matches in each of two distinct rows, with total score 4. The resulting snapshot must have left GRID_SEARCH, history exactly those two guesses with scores [2,2], exactly two candidates per position equal to the two supplied row symbols, safe symbols equal to the other eight decimal digits, and no resolved positions. Do not call ScoreCalculator or reproduce grid-generation/pruning code to create an expected result.
- Add an independent JUnit4 method with another fresh fixed-seed MEDIUM agent under the same four-digit rules. Play exactly nine emitted grid guesses with score 0. Snapshot must leave GRID_SEARCH with exactly those nine actual history entries and scores all 0, one remaining candidate and one resolved symbol per position, and nine safe decimal symbols per position. The remaining symbol is independently identified as the sole decimal digit absent from the nine emitted symbols for that column; assert candidate/resolved values equal it and safe symbols equal the nine observed column symbols. Do not ask the agent/catalog for a tenth guess as the expected oracle, and do not generate a secret or compute feedback via production scoring.
- Preserve all three original catalog tests and every assertion. Only BotGridCatalogTest.kt may change; full BotAgent and BotMatchRules hashes must remain unchanged. Implementation input contains explicitly marked read-only BotAgent API excerpts (lines 1..79 and 108..130), the whole selected test, and BotMatchRules; no whole solver/project or benchmark is supplied.
- Exactly the offline BotGridCatalogTest filter must pass at least five non-skipped tests, and git diff --check must pass. This proves these public knowledge transitions only; difficulty/balance benchmarks, Android/backend/state restoration, integration and full Core closure remain NOT_RUN.

Проверки: gradlew.bat :InplaceX-bot-core:test --tests com.mirkori.inplacex.core.bot.BotGridCatalogTest --offline --no-daemon --console=plain; git diff --check. Baseline: 3 tests passed.

Зависимости: нет; локальная ветка от develop.

Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-GRID-KNOWLEDGE-20261014\REPORT.md. Full Core closure/integration/Android/device acceptance NOT_RUN; публикации и аппаратные действия запрещены.
