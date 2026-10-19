# INPX-QA-CAMPAIGN-RESERVE-20261014

Источники: InplaceX-docs/Game/GPT/Bot and Campaign Systems.md:98, InplaceX-docs/Game/GPT/Bot and Campaign Systems.md:115, InplaceX-docs/Game/GPT/Core Engine Acceptance.md:50.

Пробел: Existing CampaignLevelGeneratorTest asserts the full onboarding curve and selected later standard/checkpoint budgets, mostly via the shared CampaignSolverBudget oracle. It has no budget assertions for Medium/Hard/Hardcore spike roles or the Medium checkpoint; these documented reserve rows need independent literal expected limits.

Только InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/campaign/CampaignLevelGeneratorTest.kt; production InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/campaign/CampaignLevelGenerator.kt доступен для чтения.

Критерии:

- Add independent literal table cases: level 15 = MEDIUM/SPIKE/length 5/solver target 14/attempt limit 17; level 20 = MEDIUM/HARDCORE/length 5/target 14/limit 16; level 85 = HARD/SPIKE/length 6/target 15/limit 17; level 225 = HARDCORE/SPIKE/length 9/target 21/limit 22; level 305 = HARDCORE/SPIKE/length 10/target 24/limit 25.
- For each fixture assert the returned tier, role, code length, rating-policy targetAttemptsForPerfect and config.attemptLimit against those documented literals. Do not compute the expected result via CampaignSolverBudget or reproduce the production reserve/tier algorithm in the test.
- Preserve all six existing test methods and assertions. Only CampaignLevelGeneratorTest.kt is editable; generator, solver reference and prior rating candidate remain untouched.
- The scoped CampaignLevelGeneratorTest class and git diff --check pass with no skipped tests; source hash remains unchanged. Full Core closure, integration, Android and device acceptance remain NOT_RUN.

Проверки: gradlew.bat :InplaceX-bot-core:test --tests com.mirkori.inplacex.core.campaign.CampaignLevelGeneratorTest --offline --no-daemon --console=plain; git diff --check. Baseline: 6 tests passed.

Зависимости: нет; локальная ветка от develop.

Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-CAMPAIGN-RESERVE-20261014\REPORT.md. Full Core closure/integration/Android/device acceptance NOT_RUN; публикации и аппаратные действия запрещены.
