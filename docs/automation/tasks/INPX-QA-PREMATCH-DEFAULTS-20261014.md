# INPX-QA-PREMATCH-DEFAULTS-20261014

Источники: InplaceX-docs/Game/Human/PvP Bot and Campaign.md:14, InplaceX-docs/Game/GPT/Bot and Campaign Systems.md:35.

Пробел: PreMatchConfig defines the documented defaults, but the active core test tree contains no direct PreMatchConfig default-contract test; UI/timer behavior is outside this card and contains preserved owner work.

Критерии:

- Add a JUnit4 method asserting PreMatchConfig() has secretSelectionTimeoutSeconds=60, the approved secret-selection timeout.
- Add an independent JUnit4 method asserting PreMatchConfig() has devBotSecretDelaySeconds=5, the approved developer bot-secret delay after player confirmation.
- Only the new PreMatchConfigTest.kt is editable; production is already correct and stays read-only. No generated-data copy tests, real-time sleeps or implementation-mirroring timer simulator.
- Qualify new-file host adapter using the same direct_packet/apply_edits guards, missing-file/hash evidence and class filter; run exactly this class offline (2 non-skipped tests) and git diff --check including the new file.
- These are default configuration contracts; actual pre-match timing, Android routing, mandatory-secret flow and full Core/device acceptance remain NOT_RUN.

Область: только новый InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/match/PreMatchConfigTest.kt; источник InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/match/PreMatchContracts.kt read-only. Зависимости от Backend/ServerBot отсутствуют. Проверки: gradlew.bat :InplaceX-bot-core:test --tests com.mirkori.inplacex.core.match.PreMatchConfigTest --offline --no-daemon --console=plain; git diff --check including new test file.

Статус planned/needs_task_packet до проверки new-file adapter и среды. Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-PREMATCH-DEFAULTS-20261014\REPORT.md.

## Фактический результат

New-file adapter квалифицирован: missing-file/hash guards и whitespace-проверка прошли. Scoped PreMatchConfigTest: 2 теста, 0 failures/errors/skips. Проверены буквальные defaults 60 и 5 секунд; production hash совпадает с исходным snapshot. Карточка agent_done/needs_integrator_review, lock free, worker_ready false; commit/integration, реальное предматчевое время и полный Core/device closure NOT_RUN. Предыдущий planned-статус выше относится к созданию карточки.
