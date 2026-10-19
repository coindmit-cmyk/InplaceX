# INPX-QA-CAMPAIGN-RATING-20261014 — revision 2

Campaign rating preserves documented attempt, time and assistance behavior

Sources: InplaceX-docs/Game/GPT/Core Engine Acceptance.md:43, InplaceX-docs/Game/GPT/Bot and Campaign Systems.md:145, InplaceX-docs/Game/Human/PvP Bot and Campaign.md:121, InplaceX-docs/Game/GPT/Bot and Campaign Systems.md:156, InplaceX-docs/Game/GPT/Bot and Campaign Systems.md:158, InplaceX-docs/Game/Human/PvP Bot and Campaign.md:134, InplaceX-docs/Game/Human/PvP Bot and Campaign.md:136.

Existing CampaignRatingCalculatorTest checks loss, final attempt and solver references, but does not assert target/half-reserve rewards or unlimited Easy assist neutrality. Revision 2: all six current tests use timely wins or unlimited assistance. No test proves documented time sensitivity or each finite assist-budget input; extend the same rating card without exact intermediate penalty assumptions.

Criteria:

- Preserve all six existing CampaignRatingCalculatorTest methods/assertions and its explicitRatingFixture helper byte-for-byte. CampaignModels.kt read-only. Continue the same task/branch with all two prior receipts and the original four-call/0.5-credit cap.
- Insert exactly one time-sensitivity JUnit4 test. Use the existing explicitRatingFixture() with attemptLimit=20, target attempts=10, target time=60 and unlimited assists. A win at attemptsUsed=10, hintUses=0, boostUses=0 at elapsedSeconds=59 and 60 earns literal rating 10 and 3 stars. A delayed win at elapsedSeconds=120 must earn less than 10 while staying within documented winning range 1..10. Do not assert an undocumented exact late score, penalty cadence or implementation-derived formula.
- Insert exactly one finite-assist JUnit4 test. Copy the existing explicit fixture ratingPolicy with CampaignAssistBudget(perfectHintsBudget=2, perfectBoostsBudget=1). A timely target win with hints=2 and boosts=1 earns rating 10 and 3 stars. Separately compare hints=100, boosts=1 and hints=2, boosts=100: each rating is below 10 and in 1..10. This demonstrates both independently documented help inputs and winning-score bounds. No oracle or exact over-budget penalty, and no assertions about inventory/entitlement/UI/provider behavior.
- Only insert both methods before the unchanged class-closing newline brace; reuse the existing helper and use fully qualified org.junit.Assert.assertTrue for inequalities without modifying imports. Exact CampaignRatingCalculatorTest filter must pass eight non-skipped methods and whitespace. Previous six methods and production remain unchanged. Commit/integration/full Core/device acceptance NOT_RUN.

Report: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-CAMPAIGN-RATING-20261014\REPORT.md. Eight scoped methods/whitespace passed; source/helper/six earlier methods unchanged. Same original four-call/0.5-credit cap. Integration/full closure NOT_RUN.
