# INPX-QA-AUTHORITATIVE-HINT-20261014

Источники: InplaceX-docs/Game/GPT/Core Engine Acceptance.md:48, InplaceX-docs/Game/Human/Game Modes and Rules.md:21.

Пробел: No existing core test calls checkPosition, openPosition or checkDigitCount. Public result types are in MatchContracts.kt lines 65-80 and MatchEngine methods 105-109; current GameEngine implements them. Deduction, UI inventory/entitlement, purchases/rewarded grants and hint gating are separate responsibilities. This independent task tests only truth of core hint values, not the deferred checkpoint repair or terminal-state goal.

Только InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/engine/AuthoritativeHintContractTest.kt; production InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/engine/GameEngine.kt доступен для чтения.

Критерии:

- Create exactly two meaningful JUnit4 methods in the new AuthoritativeHintContractTest using only public GameEngine/MatchHintResult and GameConfig(codeLength=4, allowDuplicates=true). No existing test or production file may change. Use explicit fixed secret fixtures and literal expected values; no generated reference calculator, secret[index], secret.count, digitToInt, scoring helper, randomness, reflection or sleeps in expected-value computation.
- First method: start secretOverride=1234, explicitly check openPosition indices 0,1,2,3 against literal PositionOpened(0,1), (1,2), (2,3), (3,4). Also assert literal PositionChecked(digit=3, position=2, isMatch=true) and PositionChecked(digit=9, position=2, isMatch=false) from public checkPosition. Do not calculate expected digits from a secret string or reproduce the production lookup.
- Second method: start secretOverride=1123 under duplicate-allowed rules; explicitly check checkDigitCount for digits 1,2,3,0,9 against literal DigitCountChecked(1,2), (2,1), (3,1), (0,0), (9,0). Assert the typed outcomes only; no copied counting algorithm or target helper used as an oracle.
- The exact offline AuthoritativeHintContractTest filter must pass two non-skipped tests and whitespace check. New-file compile qualification has original_test_count=0 and tests NOT_RUN until the file exists. These results prove raw core hint truth only: hintsAllowed gates, inventory consumption, rewarded ads/purchases, evidence propagation, Android/server UI permissions and full Core/device/commit/integration acceptance remain NOT_RUN. Preserve primary owner edits and all receipts.

Проверки: gradlew.bat :InplaceX-bot-core:test --tests com.mirkori.inplacex.core.engine.AuthoritativeHintContractTest --offline --no-daemon --console=plain; git diff --check. Baseline: compile-only passed; tests NOT_RUN; original test count 0.

Зависимости: нет; локальная ветка от develop.

Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-AUTHORITATIVE-HINT-20261014\REPORT.md. Full Core closure/integration/Android/device acceptance NOT_RUN; публикации и аппаратные действия запрещены.

## Фактический результат

Подготовлен локальный кандидат: 2 новых тестовых методов; адресная проверка 2 тестов, 0 failures/errors/skips. Исходные 0 методов и assertions сохранены. Production и read-only references совпадают с pre-call hashes. Карточка agent_done/needs_integrator_review, lock free, worker_ready false. Commit/integration и полный Core/Android/backend/device closure NOT_RUN.
