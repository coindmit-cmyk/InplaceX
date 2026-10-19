# INPX-QA-LATE-CHAPTER-20261014

Источники: InplaceX-docs/Game/Human/Company UX v3.md:15, InplaceX-docs/Game/Human/Company UX v3.md:52, InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/campaign/CampaignModels.kt:28, InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/campaign/CampaignModels.kt:134.

Пробел: No core tests exercise CampaignProgressionRules. CompanyCampaignLogicTest checks only the early 9/10 completed-level and 14/15-star boundary through a delegating wrapper. There is no late-block transition test for the existing default lateBlockStartsFrom=6 and late average=1.75 at 50 completed levels, requiring 88 integral stars, nor for withholding chapter 6 before the previous 50 levels are complete. This is independent of rating/solver-budget tests and of owner-modified progress persistence. Those files are excluded.

Только InplaceX-bot-core/src/test/kotlin/com/mirkori/inplacex/core/campaign/CampaignLateChapterContractTest.kt; production InplaceX-bot-core/src/main/kotlin/com/mirkori/inplacex/core/campaign/CampaignModels.kt доступен для чтения.

Критерии:

- Create exactly two meaningful JUnit4 methods in the specified new class/package. Production and all existing core/Android tests read-only. Invoke public CampaignProgressionRules methods directly with literal fixture values; no copied loop, ceil calculation, helper oracle, mutable global config or Android dependency.
- First method: assert requiredStarsForNextBlock(nextBlockNumber=6, completedLevelsCount=50) equals literal 88. Assert computeUnlockedBlock(50,87) equals 5 and computeUnlockedBlock(50,88) equals 6. This tests the existing late fractional-star threshold from the documented core authority, without inventing a new balance rule.
- Second method: with realistic abundant stars, computeUnlockedBlock(49,147) equals 5 while computeUnlockedBlock(50,150) equals 6. Stars cannot substitute for completing all 50 prior levels. Do not duplicate the already tested early 9/10 and 14/15 boundary.
- The exact CampaignLateChapterContractTest filter must pass two non-skipped methods plus new-file whitespace. Qualification is compile-only with tests NOT_RUN until the candidate check. Preserve source hashes, prior cards/candidates/receipts and all owner edits. No commit/integration, chapter UI closure, energy/history/persistence change, core-stage/device/provider acceptance or external action.

Проверки: gradlew.bat :InplaceX-bot-core:test --tests com.mirkori.inplacex.core.campaign.CampaignLateChapterContractTest --offline --no-daemon --console=plain; git diff --check. Baseline: compile-only passed; tests NOT_RUN; original test count 0.

Зависимости: нет; локальная ветка от develop.

Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-LATE-CHAPTER-20261014\REPORT.md. Full Core closure/integration/Android/device acceptance NOT_RUN; публикации и аппаратные действия запрещены.

## Фактический результат

Два адресных теста и new-file whitespace прошли; production hash сохранён. Candidate agent_done/needs_integrator_review, lock free, worker_ready false. Compile-only baseline не считается выполненным unit test. Full Core, UI, commit/integration/device closure NOT_RUN.
