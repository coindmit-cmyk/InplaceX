# INPX-QA-CONFIG-20261014 — Границы GameConfig по утверждённому контракту

Источник: InplaceX-docs/Game/GPT/Core Engine Acceptance.md, Match Domain Model.md; .agent/project.md.

Утверждённое требование: Core Engine Acceptance.md, Configuration and rules: code length 4..20; no-duplicates limited to ten decimal digits; attempt limits and configured turn limits positive. Match Domain Model.md, Invariants: codeLength in 4..20, attemptLimit > 0.

Подтверждённый пробел: Existing three tests cover the no-duplicates upper limit, duplicate-mode length 20 and turn limits. There are no explicit lower/upper invalid length or attempt-limit boundary assertions.

Область: только InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/model/GameConfigTest.kt. Production source InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/model/GameConfig.kt доступен лишь для чтения.

Критерии приёмки:

- Preserve the three existing tests and every assertion.
- Add contract tests accepting lengths 4 and 20 with duplicates, rejecting 3 and 21, and accepting unique length 10 while retaining rejection of 11.
- Add attempt-limit tests rejecting 0 and -1 and accepting 1; prove configured positive values are retained.
- Prove GameConfig.copy cannot bypass the same invalid length/attempt constraints.
- Only the selected test file changes; no production source changes; scoped class tests and git diff --check pass.

Проверка: gradlew.bat :InplaceX-bot-core:test --tests com.mirkori.inplacex.core.model.GameConfigTest --offline --no-daemon --console=plain; git diff --check.

Зависимостей от других новых карточек нет. Изолированная ветка от текущего локального develop. Test-only Script + Luna через ограниченный host adapter, без изменений production кода.

Общие Core Engine Closure Gates, Android/device/online acceptance не выполняются этой карточкой и не становятся done. Никаких push, публикаций, production или аппаратных действий.
