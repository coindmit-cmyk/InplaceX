# INPX-QA-BOT-TARGET-20261014

Источники: InplaceX-docs/Game/GPT/Bot and Campaign Systems.md:14, InplaceX-docs/Game/GPT/Bot and Campaign Systems.md:21, InplaceX-docs/Game/Human/PvP Bot and Campaign.md:22.

Пробел: BotBenchmarkRunnerTest checks requested difficulties and reference-secret completion, but no existing unit test directly checks fractional ceiling or the minimum move floor of the target budget. Simulation performance tests do not specify these deterministic profile input contracts.

Только InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/bot/BotBenchmarkRunnerTest.kt; production InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/bot/BotDifficulty.kt доступен для чтения.

Критерии:

- Add a pure profile test using explicit targetMovesMultiplier=1.25, minimumMoves=1 and expertBonusMoves=0: code lengths 4, 5 and 20 produce literal target move counts 5, 7 and 25. Never compute expected results with ceil or another reference calculator in the test.
- Add a separate pure profile test with the same multiplier, minimumMoves=9 and expertBonusMoves=0: code lengths 4, 5 and 20 produce literal targets 9, 9 and 25, proving the floor applies below its boundary without capping larger scaled budgets.
- Use BotDifficultyProfile or a copied existing profile as deterministic fixture. Preserve both original benchmark tests and assertions. Do not assert undocumented exact Expert handicap tuning or alter profile values.
- Only BotBenchmarkRunnerTest.kt changes. Run its scoped class (at least four non-skipped tests) and git diff --check; production and unrelated edits remain unchanged.
- This is contract coverage only; benchmark balance acceptance, full Core closure, integration, Android/device/online acceptance remain NOT_RUN.

Проверки: gradlew.bat :InplaceX-bot-core:test --tests com.mirkori.inplacex.core.bot.BotBenchmarkRunnerTest --offline --no-daemon --console=plain; git diff --check. Baseline: 2 tests passed.

Зависимости: нет; локальная ветка от develop.

Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-BOT-TARGET-20261014\REPORT.md. Full Core closure/integration/Android/device acceptance NOT_RUN; публикации и аппаратные действия запрещены.
