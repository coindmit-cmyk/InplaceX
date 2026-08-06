# Changelog

## Unreleased

- The home duel card now opens an explicit bot/online choice: bot play starts
  the local secret setup, while online play opens quick match and discloses its
  server bot fallback. Online play is disabled when no runtime is configured.
  The default PvE race is speed-based with unlimited moves, and local
  bot duels no longer apply a turn timer.

- Campaign now offers atomic, persisted daily and weekly bonus claims. Reward
  payloads and local calendar periods are centralized in a configurable policy,
  and duplicate claims cannot mutate inventory.

- The first campaign match now starts with a persisted three-step tutorial for
  the game goal, turn feedback, hints and boosts. The match timer does not start
  until the player explicitly finishes the tutorial.

- Campaign chapters now show exactly their configured ten levels and use one
  shared progression rule for generation, rewards and UI grouping. Unlocking
  the next chapter requires every preceding level plus two stars per level
  (`20` stars for chapter 2), with the coefficient kept configurable.

- Shell entrypoints, Gradle wrapper and Dockerfiles now keep LF endings on
  Windows checkouts. The backend Docker build also includes every Gradle module
  declared by the root project, so the stock local-stack verification runs
  without a normalized export or a patched Dockerfile.

- Every committed online duel revision now writes a secret-free durable event
  marker in the same PostgreSQL transaction. WebSocket connections consume the
  journal across backend instances, push viewer-specific live snapshots, resume
  retained cursors without a false replay gap, and reserve `session.replayGap`
  for missing cursors.

- The online WebSocket endpoint now enforces the published v1 bearer/subprotocol
  contract and supports `session.subscribe`, `session.resync`, and
  `session.ping` envelopes. Snapshot recovery stays PostgreSQL-authoritative,
  reconnect cursors are durable across backend instances, outbound frames are
  bounded, and unavailable replay is reported explicitly as `session.replayGap`.

- PostgreSQL-backed duel reads and commands now take command-scoped ownership
  of the durable session row. Each backend instance restores the latest
  encrypted aggregate under `FOR UPDATE`, so concurrent commands preserve
  revision conflicts, cross-instance idempotency and race-mode updates without
  treating a stale in-memory copy as authority.

- PostgreSQL private friend invites now coordinate creation, acceptance and
  expiry across backend instances. Duplicate commands return the committed
  invite, concurrent guests can create only one duel session, and stale
  runtimes refresh the durable invite before reporting or expiring it.

- PostgreSQL-backed public matchmaking now coordinates concurrent backend
  instances with row-level ticket claims, one durable command identity, and an
  atomic session-plus-ticket transaction; human pairing and bot fallback can no
  longer create two sessions for the same waiting ticket.

- PostgreSQL-backed online runtime now restores waiting matchmaking tickets,
  bot-fallback decisions, private invite codes and lobby command replays after
  backend restarts; accepted matches continue through the existing encrypted
  duel recovery path.

- Transient sign-in and online-submit operations now reset safely across recreation/cancellation, and Android backup is disabled until authenticated cloud reconciliation is available.

- Campaign completion now persists progress and economy atomically, rewards only personal-best rating improvements, and keeps chapter rewards accessible in landscape.

- Разделены сценарии `Друзья`, `Приглашения` и `Онлайн-матчи`; добавлен явный
  возврат из истории кампании и исправлено размещение настроек и переносы
  заголовков на компактных экранах.
- Добавлена однократная сохраняемая награда за завершение десяти уровней главы:
  50 монет и по одной подсказке каждого типа.
- Moved the pure match lifecycle/contracts, campaign rules, rating, progression,
  and mode definitions out of the Android app into `InplaceX-bot-core`; local
  Android and backend matches now consume one physical source of truth.
- Closed core rule gaps: impossible unique-digit configurations and non-positive
  turn limits fail early; fixed, generated, and restored secrets use the exact
  same validation; malformed winning checkpoints fail closed.
- Preserved every duplicate/run rule across mode-to-route conversion and
  Android process recreation, and made configured turn timeout terminate the
  match explicitly.
- Rebased campaign budgets on deterministic measured solver performance,
  centralized rating/progression in core, and guaranteed zero stars for losses
  and one star for a last-attempt win.
- Added provider-neutral `InplaceX-auth-core` security rules for Google,
  passwordless email codes, opaque provider subjects, and signed/fresh Telegram
  login payloads; provider secrets remain outside Android and Git.
- Added `InplaceX-ads-core` as the single entitlement-first policy for banner,
  rewarded, and post-match placements, and wired Android banner/interstitial
  eligibility to it without falsely enabling an unavailable ad SDK.
- Renamed the private online room choices to the player-facing formats
  `На время` and `По очереди`, added concise rule explanations in Russian and
  English, and kept the stable `race`/`turn_based` server contract unchanged.
- Rebased campaign move budgets on the expert solver target plus a
  tier-and-role reserve: easy standard levels now have a generous margin,
  level 10 remains a stricter checkpoint, and hard/hardcore levels keep only
  a small explicit reserve.
- Normalized the attempt rating across each level's full reserve so a win on
  the final allowed attempt earns one star instead of always retaining three.
- Added a debug-only Mirkori Bot test friend backed by VPS quick matchmaking,
  plus an in-app invitation guide and copy/share actions for real two-phone
  friend codes; release builds contain no fake friend.
- Restored the configured online endpoint in the test-build workflow so the
  preserved private-friend-duel code is no longer shipped as an offline UI.
- Removed the nested Company room image and made `company_room_bg_v2` the
  single shell background for both the campaign map and campaign matches.
- Added the first banner placement policy: the shell reserves the game banner
  only after provider acceptance, while remove-ads, PRO and PRO+ always skip
  the provider and the slot.
- Added a dedicated purple game-banner container with an explicit AD badge and
  a stable Compose test tag, ready for the provider-owned banner view.
- Hid the developer entry point from normal settings in test APKs and stopped
  debug tooling from overriding premium ad suppression.
- Reduced the duplicated in-game header to one compact row for mode, moves and
  timers; global shell controls remain the sole back/settings navigation.
- Increased the debug-only test wallet action to grant 10,000 coins per tap;
  release builds still contain no developer currency grant.
- Rebalanced campaign levels 1-10 after device playtesting: level 8 now allows
  12 attempts and 4:00, while level 10 allows 10 attempts and 3:15.
- Google challenge endpoints missing from an older server deployment now show
  the truthful provider-not-enabled state instead of blaming Google for a
  rejected account.
- Removed the remaining shell-level white gameplay canvas: game panels now sit
  directly on the shared scene background in every active game mode.
- Added private friend duels for two phones: one authenticated guest creates an
  eight-character expiring invite, another guest joins it, and both clients
  automatically poll the same server-authoritative setup, turns, and result.
- Added a fail-closed Mirkori Games Telegram distribution service template.
  It serves only allowlisted, manifest-declared APKs whose SHA-256 is verified
  immediately before delivery; bot credentials remain VPS-only.
- Mirkori Games now returns a verified HTTPS download button instead of
  uploading APK binaries through Telegram.
- Removed the opaque white Company gameplay backdrop: campaign levels now
  retain the same warm toy-room scene used by the campaign map.
- Added real Google account authentication through Android Credential Manager
  and the isolated identity process. The server verifies Google ID tokens and
  one-time nonces, links the provider subject to the existing guest player,
  stores the link in PostgreSQL, and returns ordinary rotating InplaceX
  credentials without persisting raw provider tokens or email addresses.
- Consolidated the independently preserved phone-regression/UX work and the
  VPS online bot-fallback implementation into one integration branch while
  retaining both source branches as recovery points.
- Fixed two phone regressions: manual `YES` table marks now prefill every
  following attempt until changed or removed; duel setup state now survives
  the shell's transition into gameplay, while malformed restored secrets are
  rejected before scoring instead of crashing on the first submission.
- Brought the Company scene closer to the approved toy-room references with a
  dedicated warm 3D study background, a larger game-logo treatment, and
  clearer separation between the campaign route and decorative scenery.
- Tightened the campaign difficulty curve after playtesting: four-digit levels
  now fall from 14 attempts at level 1 to 10 at level 8 and 8 at level 10,
  with shorter match timers; level 17 now uses 14 attempts and 4:30 instead of
  the overly forgiving 19 attempts and 6:40.
- Added a one-hour temporary PRO purchase for 60 profile coins. The entitlement
  persists across restarts, extends from any remaining time, updates its
  countdown live in Shop and Profile, enables auto-table assistance, and
  suppresses ads without granting PRO+ infinite hints.
- Made the first bot-duel turn fail safely: solver or restored-state errors no
  longer escape the Compose coroutine and close the app; the duel stays open,
  control returns to the player, and a localized recovery message is shown.
  Added end-to-end coverage for both a normal first exchange and a first-guess
  win, plus focused unit coverage for bot-turn failures and cancellation.
- Completed the PvE Race terminal loop: exhausted moves now show a defeat
  result, wins no longer silently restart, result details include attempts and
  elapsed time, retry is explicit, and each win grants 10 profile coins.
- Rebalanced campaign progression so the first block remains an onboarding
  experience while level 11 starts a meaningful five-digit medium-difficulty
  step; later code-length and tier bands now grow toward the intended
  level-300 plateau instead of keeping levels 1 through 55 nearly identical.
- Added the Toy Room UI v5 foundation from the approved visual references:
  warm desk scenery, glossy blue resource chrome, cream raised cards, vivid
  orange/purple/green mode hierarchy, compact top actions, and an illuminated
  blue bottom navigation shared by Home, Friends, Company, Shop, and Profile.
- Rebuilt the Company campaign map in the same toy-room language with a yellow
  title plaque, compact chapter/reward dashboard, descending mission route,
  numbered locked levels, cream code cards, selected-level gold emphasis, and
  a green primary play action while preserving campaign behavior.
- Added the Android online transport foundation for the versioned REST and
  WebSocket contracts: HTTPS/WSS enforcement, transport-owned bearer
  authentication, single-flight refresh, bounded deterministic retries,
  idempotency requirements, reconnect cursors, frame limits, and redacted
  diagnostics.
- Hardened the authoritative duel engine with single-use mutable digit
  commands, ASCII-only validation, viewer-neutral attempt snapshots, and
  deterministic secret-buffer zeroization after finish, close, or failure.
- Isolated sandbox provider stubs and test identifiers to the debug Android variant. Release provider wiring now fails closed until real Google Play, Billing, and AdMob SDK results are integrated.
- Added closed backend session read contracts with deterministic 64 KiB JSON
  frames, strict bounded JSON scanning, server-keyed secret fingerprints, and
  pseudonymous read-log attributes. Client intents, authenticated actor binding,
  and caller-authored outcome decoding remain outside this slice.
- Added a local Docker Compose backend/PostgreSQL stack with persistent storage,
  health checks, environment-only credentials, and an executable backup,
  restore, rollback, and migration verification runbook.
- Added the modular Ktor backend foundation with environment-driven runtime
  configuration and health/readiness endpoints.
- Added PostgreSQL-backed versioned persistence migrations for players, cloud-save
  revisions, matchmaking tickets, duel sessions, idempotent commands, and events.
  Database connection credentials remain external process configuration.
- Added an isolated local Docker Compose backend/PostgreSQL stack with migration,
  backup/restore, rollback verification, localhost-only port binding, and a
  build-context secret exclusion policy.
- Activated the state-backed `GameFieldScreen → GameFieldViewModel → GameScreen`
  route while keeping manual analysis marks non-authoritative and preserving
  localized validation, hints, boosts, timers, attempt scrolling, and deduction.

## 2026-07-25

### Added
- Added a transport-agnostic authoritative backend duel aggregate with ordered secret setup, turn and score ownership, terminal win handling, and secret-free public snapshots.
- Added a stateless `GameScreen` presentation boundary with independent top, attempts, analysis, helpers, tools, input, and optional debug-slot components.
- Added a GitHub Actions CI foundation with Java 21 Gradle launcher, Java 11 project toolchains, identified debug artifacts, and visible non-blocking instrumentation/release checks.
- Added the InplaceX Design System v2 direction, Compose UI contract, and a visual concept for the Home and Race screens.
- Added a planning-only AiStudio handoff and dependency-aware finalization backlog covering preservation, UI migration, match-contract convergence, online backend, VPS staging, E2E, and release hardening.
- Added a stable semantic color-token set for the Android client.
- Added versioned online REST/WebSocket/security contracts with machine-readable v1 schemas for guest auth, cloud save, matchmaking, duel commands, snapshots, reconnect, idempotency, concurrency, redaction, and secret ownership.

### Changed
- Localized secondary, developer, and Bot Lab screen labels for Russian and English while retaining diagnostic identifiers and controls.
- Replaced the default dynamic Material palette with stable InplaceX brand colors and shapes.
- Updated the global background, top resources, bottom navigation, shared scene components, Home identity, and player-facing Race/Duel labels.
- Restored visible game status and validation feedback after the UI redesign, including a localized explanation when every entered digit is identical.
- Made the in-match attempt history automatically follow the newest accepted result.
- Extended auto-mode deduction so every remaining possible position is locked when the score proves that all of them are exact matches.

### Safety
- Redesign work starts from `baseline/pre-redesign-2026-07-25`.
- The automation handoff remains planning-only and does not authorize Worker launch or VPS activation.

## 2026-07-17

### Added
- Added machine-readable and human-readable Project Maps so agent work and Second Brain history can bind to the current InplaceX modules.

## 2026-06-03

### Added
- Added `docs/project-baseline.md` as the baseline for `0.1.0-project-foundation`.
- Added `docs/next-work.md` with the next stabilization, product, architecture, quality, and archive tasks.

### Changed
- Moved short root onboarding docs into the intended `docs/` directory.
- Updated agent context to include `:InplaceX-test-support`.
- Increased Gradle wrapper download timeout to make first-time setup more reliable on slow connections.

### Notes
- This baseline is an internal project foundation checkpoint, not a production APK release.

## 2026-05-11

### Added
- Added root AI agent entry point and project-specific `.agent/` context.
- Added `InplaceX-logging` as a shared logging contract module with unit tests and sensitive-key redaction.
- Added `InplaceX-test-support` as a shared JVM test infrastructure module for reusable log sinks and test-only console adapters.
- Added logging module documentation.
- Added short root onboarding docs for architecture, modules, development, and testing.
- Added repository-level examples/placeholders for environment and editor configuration.
- Added reserved `scripts/` and `tests/` documentation.

### Changed
- Included `:InplaceX-logging:test` in the root `verifyProject` workflow.
- Included `:InplaceX-test-support:test` in the root `verifyProject` workflow.
- Clarified root README and `.gitignore` expectations for local configuration and diagnostics.
- Documented the project Git branch workflow and the rule to avoid new `codex/*` work branches.
- Documented the rule that production code changes should include logging and tests in the same change.
- Replaced direct `android.util.Log` calls in app production code with the shared logging contract plus Android sink adapter.
- Replaced direct `println` usage in bot benchmark and trace runners with the shared logging contract plus test-support console sink.

### Verification
- Reviewed repository structure, Gradle modules, existing docs, and current Git state before creating files.
- Verified local branch names and checked updated workflow docs with `git diff --check`.
