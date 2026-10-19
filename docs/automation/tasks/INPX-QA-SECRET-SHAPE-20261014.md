# INPX-QA-SECRET-SHAPE-20261014

Источники: InplaceX-docs/Game/GPT/Core Engine Acceptance.md:24, InplaceX-docs/Game/GPT/Match Domain Model.md:25, .agent/project.md:69.

Пробел: Existing SecretGeneratorTest covers all rule configurations by delegating its oracle to GuessValidator, plus seeded reproducibility. It lacks direct structural assertions independent of that shared validator, so a matching generator/validator regression could pass existing tests.

Только InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/engine/SecretGeneratorTest.kt; production InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/engine/SecretGenerator.kt доступен для чтения.

Критерии:

- For duplicate-enabled GameConfig boundaries codeLength=4 and codeLength=20, attemptLimit=40, maxConsecutiveDuplicateDigits=3 and explicit fixed seeds 0, 1 and 42, directly assert exact length, every character in '0'..'9', and no four-character window consisting of the same digit.
- For codeLength=10 with allowDuplicates=false and the same explicit seeds, directly assert length 10, distinct count 10 and the set of characters equal to the decimal alphabet. These are invariant checks, not assertions of an exact seeded secret.
- The new tests must not call GuessValidator as their oracle, duplicate the generator algorithm, depend on random-frequency statistics or print hidden secret strings. All original tests and assertions remain intact.
- Only SecretGeneratorTest.kt changes; the scoped class executes at least four non-skipped tests and git diff --check passes. Production source, owner edits to GuessValidator and all unrelated files are preserved.
- This is a local test-only candidate. Full Core closure, integration, Android and physical-device acceptance remain NOT_RUN.

Проверки: gradlew.bat :InplaceX-bot-core:test --tests com.mirkori.inplacex.core.engine.SecretGeneratorTest --offline --no-daemon --console=plain; git diff --check. Baseline: 2 tests passed.

Зависимости: нет; локальная ветка от develop.

Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-SECRET-SHAPE-20261014\REPORT.md. Full Core closure/integration/Android/device acceptance NOT_RUN; публикации и аппаратные действия запрещены.
