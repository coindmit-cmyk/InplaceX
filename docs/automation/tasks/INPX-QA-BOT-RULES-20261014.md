# INPX-QA-BOT-RULES-20261014

Источники: InplaceX-docs/Game/GPT/Bot Brain.md:51, InplaceX-docs/Game/GPT/Core Engine Acceptance.md:30.

Пробел: The four existing BotRuleFlagsTest methods cover direct validation, generation and one solver run, but do not assert independent literal code acceptance after the GameConfig-to-BotMatchRules-to-GameConfig conversion boundary for enabled and disabled flags or the friend maximum run of three.

Только InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/bot/BotRuleFlagsTest.kt; production InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/bot/BotMatchRules.kt доступен для чтения.

Критерии:

- Add one independent JUnit4 method for friend rules: codeLength=6, allowDuplicates=true, forbidAdjacentDuplicates=false, forbidTripleDuplicates=false, maxConsecutiveDuplicateDigits=3. Round-trip via config.toBotMatchRules().toGameConfig().toBotMatchRules(), then rules.isValidCode must accept 111234 and 121212 and reject 111123. These are literal externally documented outcomes, not assertions calculated from GuessValidator.
- Add one independent JUnit4 method for flag preservation at the same conversion boundary. Use explicit six-digit fixtures and literal outcomes: a permissive allowDuplicates=true/all-same=false/adjacent=false/triple=false/maxRun=null fixture accepts 111111, 112345, 111234 and 121345. Disabling duplicates rejects 121345 and accepts 123456. Enabling only all-same prohibition rejects 111111 and accepts 121212; enabling only adjacent prohibition rejects 112345 and accepts 121345; enabling only triple prohibition rejects 111234 and accepts 112345. Each assertion observes rules.isValidCode on the round-tripped rules, rather than comparing two calls to the same validator or copying its algorithm.
- Keep all four original methods and assertions unchanged. Only BotRuleFlagsTest.kt may change; BotMatchRules and GameConfig are read-only. Do not touch the owner's pending GuessValidator source/test changes, tune bot behavior or add solver benchmarks.
- The exact offline BotRuleFlagsTest filter must pass all six or more non-skipped tests, and git diff --check must pass. The scope proves only this local bot-rule boundary, not Android/backend/process restoration, whole-core closure or integration.

Проверки: gradlew.bat :InplaceX-bot-core:test --tests com.mirkori.inplacex.core.bot.BotRuleFlagsTest --offline --no-daemon --console=plain; git diff --check. Baseline: 4 tests passed.

Зависимости: нет; локальная ветка от develop.

Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-BOT-RULES-20261014\REPORT.md. Full Core closure/integration/Android/device acceptance NOT_RUN; публикации и аппаратные действия запрещены.

## Фактический результат

Подготовлен локальный кандидат: 2 новых тестовых методов; адресная проверка 6 тестов, 0 failures/errors/skips. Исходные 4 методов и assertions сохранены. Production и read-only references совпадают с pre-call hashes. Карточка agent_done/needs_integrator_review, lock free, worker_ready false. Commit/integration и полный Core/Android/backend/device closure NOT_RUN.
