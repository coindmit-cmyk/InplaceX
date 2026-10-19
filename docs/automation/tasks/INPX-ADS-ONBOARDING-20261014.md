# INPX-ADS-ONBOARDING-20261014

Источники: InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md:65, InplaceX-docs/Game/GPT/Auth Ads Billing Contracts.md:66.

Пробел: AdPolicy currently checks matchesPlayed < 20 and permits count 20. The current Android counter increments at match start (GameProgressRepository.recordMatchStarted), so the post-result count includes the twentieth match; this conflicts with the approved first-twenty protection. The old test also incorrectly expects Allowed at 20. This independent pure placement-policy fix does not activate or bypass blocked real-provider SDK tasks.

Production patch и meaningful tests разрешены только в InplaceX-ads-core/src/main/kotlin/com/mirkori/inplacex/ads/AdContracts.kt и InplaceX-ads-core/src/test/kotlin/com/mirkori/inplacex/ads/AdPolicyTest.kt. Planned/needs_task_packet до квалификации стандартного host adapter.

Критерии:

- Make the minimal post-match onboarding boundary correction so matchesPlayed=20 still returns Blocked(EARLY_PLAYER); keep the threshold constant 20, fourth-match cadence, format validation, entitlement precedence and completed-only reward behavior unchanged.
- Strengthen only the existing count-20 expectation from Allowed to Blocked(EARLY_PLAYER), retaining the other baseline assertions and all three methods. This follows the approved first-twenty rule; do not weaken acceptance to fit the old implementation.
- Add at least one independent JUnit4 method using literal outcomes: counts 0/1/19/20 are EARLY_PLAYER; 21/22/23/25/27 are CADENCE; 24 and 28 are Allowed when adsDisabled=false and placement/format are POST_MATCH_INTERSTITIAL/INTERSTITIAL. Do not derive expected outcomes from AdPolicy constants/modulo or run a provider.
- Exactly the offline AdPolicyTest filter must pass at least four non-skipped tests and git diff --check. Only pure JVM code/test within the two allowed files; Android runtime acceptance, SDK activation, integration and full Core closure remain NOT_RUN.

Проверки: gradlew.bat :InplaceX-ads-core:test --tests com.mirkori.inplacex.ads.AdPolicyTest --offline --no-daemon --console=plain; git diff --check. Baseline: 3 tests passed.

Зависимости: нет; локальная ветка от develop.

Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-ADS-ONBOARDING-20261014\REPORT.md. Full Core closure/integration/Android/device acceptance NOT_RUN; публикации и аппаратные действия запрещены.

## Фактический результат

agent_done/needs_integrator_review: 4 scoped тестов, 0 failures/errors/skips; source/test diff проверен и сохранён. Commit/integration/full Core/device closure NOT_RUN.
