# INPX-QA-SERVER-BOT-20261014

Источники: InplaceX-docs/Game/GPT/Server Bot Runtime.md:20, InplaceX-docs/Game/GPT/Server Bot Runtime.md:31, InplaceX-docs/Game/GPT/Core Engine Acceptance.md:36, .agent/project.md:100.

Пробел: Existing ServerBotPlayerTest covers generated-secret validation, scoring by comparison with the same calculator, pending-turn idempotence and offensive solving. It lacks hidden-secret noninterference of default snapshots, literal defensive outcomes/own-side terminal flag assertions, and an unchanged-state assertion for rejected defensive input.

Только InplaceX-backend/src/test/kotlin/com/mirkori/inplacex/backend/bot/ServerBotPlayerTest.kt; production InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/bot/ServerBotPlayer.kt доступен для чтения.

Критерии:

- Create two bots with identical GameConfig(codeLength=4, allowDuplicates=true, attemptLimit=40), EASY profile, same brainSeed=5 and distinct fixed secrets 1234 and 4321. Default snapshots must be equal before play and after each requests its first pending turn. Assert equality as a boolean with a generic message so a failure does not print snapshot/secret content; do not assume a field layout that forbids nullable redaction fields.
- For a bot with fixed secret 1234, incoming guess 1243 yields literal exactMatches=2, solvedSecret=false and one defensive history item. Guess 1234 then yields exactMatches=4, solvedSecret=true, two history items, secretSolvedByOpponent=true and solvedOpponentSecret=false. nextTurnOrNull returns null after that terminal defensive result.
- A too-short incoming guess 123 is rejected with IllegalArgumentException and leaves the default snapshot and defensive history unchanged. Use a boolean snapshot comparison to avoid printing hidden data.
- Add three independent JUnit methods and preserve all original tests/assertions. Only ServerBotPlayerTest.kt may change; ServerBotPlayer/Contracts and all core/Android files remain read-only. Use fixed fixture literals rather than production score/validator calls as new-test oracles.
- Run only :InplaceX-backend:test filtered to ServerBotPlayerTest, at least six non-skipped tests, and git diff --check. No DB access, backend server startup, socket flow, real credentials, deployment or hardware actions. This is a local candidate; full Core closure/integration/online/device acceptance remains NOT_RUN.

Проверки: gradlew.bat :InplaceX-backend:test --tests com.mirkori.inplacex.backend.bot.ServerBotPlayerTest --offline --no-daemon --console=plain; git diff --check. Baseline: failed before tests; NOT_RUN; missing offline dependency cache.

Зависимости: нет; локальная ветка от develop.

Отчёт: D:\Work\DevOps\MobileGame\agent-runtime\automation-8h-20261014\tasks\INPX-QA-SERVER-BOT-20261014\REPORT.md. Full Core closure/integration/Android/device acceptance NOT_RUN; публикации и аппаратные действия запрещены.
