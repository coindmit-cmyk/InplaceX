# INPX-QA-SCORE-20261014 — Независимые ожидаемые результаты точного счёта

Источник: InplaceX-docs/Game/GPT/Core Engine Acceptance.md, Match Domain Model.md; .agent/project.md.

Утверждённое требование: .agent/project.md, Game Rules And Contracts: exact-position score; win iff score equals code length; codeLength 4..20. Match Domain Model.md, Invariants: win iff score == codeLength.

Подтверждённый пробел: Existing tests compare String and CharArray results without independent expected values, check differing lengths and bytecode hygiene. They do not assert known exact-position scores for leading-zero and length-20 inputs.

Область: только InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/engine/ScoreCalculatorTest.kt. Production source InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/engine/ScoreCalculator.kt доступен лишь для чтения.

Критерии приёмки:

- Preserve all three existing tests and assertions, including bytecode and length guards.
- Assert literal independent expected scores for BOTH String and CharArray overloads: 01234567890123456789 / same => 20; / 10234567890123456789 => 18; / 98765432109876543210 => 0.
- Assert 10011001 / 10101010 => 4 and 0123 / 3012 => 0, demonstrating exact positions rather than symbol frequency.
- Mutable overload keeps input arrays unchanged on these fixtures.
- Only the selected test file changes; no scoring implementation change or reference calculator copied from production; scoped tests and git diff --check pass.

Проверка: gradlew.bat :InplaceX-bot-core:test --tests com.mirkori.inplacex.core.engine.ScoreCalculatorTest --offline --no-daemon --console=plain; git diff --check.

Зависимостей от других новых карточек нет. Изолированная ветка от текущего локального develop. Test-only Script + Luna через ограниченный host adapter, без изменений production кода.

Общие Core Engine Closure Gates, Android/device/online acceptance не выполняются этой карточкой и не становятся done. Никаких push, публикаций, production или аппаратных действий.
