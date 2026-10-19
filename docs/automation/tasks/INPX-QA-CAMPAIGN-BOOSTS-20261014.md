# INPX-QA-CAMPAIGN-BOOSTS-20261014

Источники: InplaceX-docs/Game/Human/PvP Bot and Campaign.md:112, InplaceX-docs/Game/Human/PvP Bot and Campaign.md:119, InplaceX-docs/Game/GPT/Bot and Campaign Systems.md:172.

Пробел: The existing generator tests cover budgets, time limits, tiers, lengths and block roles, but never assert boostPack.extraMoves/extraSeconds or hintsAllowed/autoModeAllowed. Prior Campaign reserve and rating candidates have different goals; this fresh class avoids altering their pending test files.

Только InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/campaign/CampaignBoostContractTest.kt; production InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/campaign/CampaignLevelGenerator.kt доступен для чтения.

Критерии:

- Add one JUnit4 method using literal public generate(levelNumber) fixtures covering all three block roles in each tier: Easy levels 1/5/10 expect EASY and extraMoves=3, extraSeconds=120; Medium 11/15/20 expect MEDIUM and 2/60; Hard 81/85/90 expect HARD and 2/60; Hardcore 221/225/230 expect HARDCORE and 1/30. Assert both separate boost fields and the fixture tier directly; do not call private boostPackFor or derive expectations from generator logic.
- Add a second independent JUnit4 method asserting hintsAllowed=true and autoModeAllowed=true for the same twelve documented tier/role fixtures. This proves generator availability flags only; do not treat it as permission to bypass inventory ownership or proof that inventory/ads/purchase/UI flows work.
- Only create CampaignBoostContractTest.kt. Production generator/models, all existing tests and prepared reserve/rating candidates remain unchanged. No tuning, grants, ad/provider/DB or device work.
- Compile-only qualification remains tests NOT_RUN. The exact offline CampaignBoostContractTest class must then pass two meaningful non-skipped methods and new-file whitespace check. Local candidate only; commit/integration, full Core closure and actual boost consumption/Android/device acceptance remain NOT_RUN.

Проверки: gradlew.bat :InplaceX-bot-core:test --tests com.mirkori.inplacex.core.campaign.CampaignBoostContractTest --offline --no-daemon --console=plain; git diff --check. Baseline: compile-only passed; tests NOT_RUN; original test count 0.

Зависимости: нет; локальная ветка от develop.

Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-CAMPAIGN-BOOSTS-20261014\REPORT.md. Full Core closure/integration/Android/device acceptance NOT_RUN; публикации и аппаратные действия запрещены.

## Фактический результат

Локальный кандидат agent_done/needs_integrator_review; 2 scoped тестов, 0 failures/errors/skips; два новых метода. Host review сохранён. Full Core closure/commit/integration/device acceptance NOT_RUN.
