# INPX-QA-CHECKPOINT-RESTORE-20261014

Источники: InplaceX-docs/Game/GPT/Core Engine Acceptance.md:37, InplaceX-docs/Game/GPT/Core Engine Acceptance.md:39, InplaceX-docs/Game/GPT/Core Engine Acceptance.md:40, InplaceX-docs/Game/GPT/Core Engine Acceptance.md:41.

Пробел: Current GameEngineTest covers valid ACTIVE/NOT_STARTED restore, invalid secret, one run-rule mismatch and a history after an earlier win. It does not cover atomic rejection of corrupt attempt score/ordinal/isWin/budget and incompatible phases, or valid WON/LOST restoration. The previously prepared terminal-match task covers normal submit/start, not persisted restore. Existing owner-modified GameEngineTest and GuessValidator/Evidence sources are excluded; only a new test file is allowed, from the same clean local develop implementation.

Только InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/engine/CheckpointRestoreContractTest.kt; production InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/engine/GameEngine.kt доступен для чтения.

Критерии:

- Create exactly two meaningful JUnit4 methods in CheckpointRestoreContractTest using public GameEngine, MatchCheckpoint and MatchAttempt. No copied validation/scoring algorithm, reflection, randomized inputs, sleeps, production change or baseline test edit. Use fixed literal outcomes and only valid unique-digit guesses unless that fixture is explicitly malformed.
- First method: target GameEngine(GameConfig(codeLength=4, attemptLimit=2)) starts secret 1234 and accepts guess 1235. Preserve before snapshot and checkpoint. Independently attempt malformed checkpoints for the same 1234 secret: a 1235 attempt with score=2 instead of literal 3; ordinal number=2 for its sole attempt; isWin=true on score=3; malformed guess 12x5; extraAttemptBudget=-1; ACTIVE with exhausted two nonwinning attempts [1235 score3 number1, 5678 score0 number2]; and WON with only the nonwinning 1235 score3 attempt. Every restoreCheckpoint returns false and both snapshot and checkpoint remain exactly equal to the saved target state after every failure. Use literal fixtures/copies of a known valid checkpoint, not a reference validator.
- Second method: independently restore two valid literal terminal checkpoints into an already active, different target match (target initially starts 9012 and submits 9013). Use config codeLength=4, attemptLimit=2. WON fixture: secret=1234, phase=WON, attempts=[MatchAttempt(1234,4,1,true)], extraAttemptBudget=0; LOST fixture: secret=1234, phase=LOST, attempts=[MatchAttempt(5678,0,1,false), MatchAttempt(9012,0,2,false)], extraAttemptBudget=0. Each restore returns true, checkpoint equals the supplied literal fixture, snapshot phase/secret/history match it, and attemptsLeft is respectively 1 and 0. A subsequent submit must leave the checkpoint and those snapshot state fields unchanged. Compare state fields rather than presentation message/feedback, which may differ.
- The exact offline CheckpointRestoreContractTest filter must pass two non-skipped tests and whitespace check. Initial new-file compile qualification has original_test_count=0 and tests NOT_RUN until this file exists. Preserve all receipts and production/reference hashes. Owner edits in the primary checkout, Android persistence/serialization/UI navigation, commit/integration and whole Core/device acceptance remain untouched or NOT_RUN.

Проверки: gradlew.bat :InplaceX-bot-core:test --tests com.mirkori.inplacex.core.engine.CheckpointRestoreContractTest --offline --no-daemon --console=plain; git diff --check. Baseline: compile-only passed; tests NOT_RUN; original test count 0.

Зависимости: нет; локальная ветка от develop.

Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-CHECKPOINT-RESTORE-20261014\REPORT.md. Full Core closure/integration/Android/device acceptance NOT_RUN; публикации и аппаратные действия запрещены.


## Deferred outcome

Four-call cap exhausted. Initial two JUnit tests passed, but host acceptance failed: independent active target setup and after-submit budget assertion missing. Valid Luna diagnosis obtained; fourth response had truncated before/after values and was rejected before application.

Report: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-CHECKPOINT-RESTORE-20261014\REPORT.md
