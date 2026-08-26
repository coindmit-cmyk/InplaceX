# Reference pages v7 — implementation and visual review

Owner request: implement the supplied four-screen reference in the Android app.
Run `reference-pages-v7`, plan revision 1; contract and art provenance are in
`InplaceX-docs/Game/GPT/Reference Pages v7.md` and `Reference Pages v7 Assets.md`.

## Repository / authority

- Started from verified automation/develop b5a4d507 in a new worktree and
  `feature/reference-pages-v7` branch. Integrated PR95 and PR96 locally through
  Git (89b1a1ef); resolved Profile by preserving server-owned Google connection
  checks and the shared page components. Existing owner worktrees are untouched.
- This is owner-directed Codex Chat work, not an automated Worker claim.
  Historical S35/S37/S38 remain blocked_by_dependency, worker_ready=false;
  no active repository locks or additional owner directives were found.
  No task readiness, registry/history, remote runtime or provider config changed.
- PR candidate includes pending dependencies PR93/94/95/96. Do not treat it as
  an independent production-ready release or merge it through unresolved gates.

## Changes

- Shared cream cards, beveled/glossy primary actions, colored navy-backed page
  headers, compact spacing; menu safe-area handling and fitted narrow nav labels.
- Social: real friend preview, compact inbox preserved, paired purple invite and
  blue online actions; single-column fallback for large fonts. Offline and request
  error states remain real. No fabricated presence indicators or match history.
- Campaign: generated forest with a native interactive path, numbered/locked/
  selected/completed nodes. Unlock policy, rewards/history and play callbacks
  preserved. Compact windows show conditions through the existing rules dialog.
- Shop: two-column supplies with existing artwork, real price and stock, explicit
  insufficient-funds labels; generated reward illustration. Large fonts stack cards.
- Profile: blue identity card, generic illustrated fallback avatar, compact copy-ID
  action; real connection state and existing auth choices preserved. No fake XP.
- Rendering-only work adds no noisy logs; existing runtime/integration logging remains.

## Validation

- `verifyProject`: passed, including Android unit tests and 21 release-distribution
  tests. Android `testDebugUnitTest`: 258 tests, zero failures/errors.
- `:app:assembleDebug :app:assembleDebugAndroidTest`: passed on the owner's PC.
- `:app:lintDebug`: zero errors, 45 warnings and 3 hints. No claim of warning-free lint.
- API35 emulator 412dp: 47 instrumented tests passed across UnifiedPagesVisualTest,
  ShellSectionsSmokeTest, FriendRequestInboxTest, OnlineDuelInputTest and
  OnlineInviteRecoveryTest. Includes five keypad presses producing 1115511 and
  invitation recovery after temporary failure/recreation.
- Visual suite also passed at 360x640dp and 600x960dp, RU 1.0 / EN 1.5. Final
  narrow navigation/compact-bar adjustment was rebuilt and retested at 360dp.
  The last two adjustments only apply to compact layout; the 47-test run predates
  them. Source review confirmed no gameplay/auth changes in those adjustments.
- Initial broad run caught a non-clickable friend preview and lost visible
  insufficient-funds text. Both fixed before the successful 47-test rerun.
- Manual screenshot review caught narrow navigation truncation and over-tall
  compact campaign details; corrected and captured again. `git diff --check` clean.

## Evidence

Local artifact directory:
`D:/Work/DevOps/MobileGame/InplaceX-artifacts/reference-v7-20260826/`.

- `index.html`: actual four-screen comparison plus owner reference.
- `typical/`, `narrow/`, `wide/`: device captures, including large-font states.
- `typical-tests.txt`, `narrow-final-tests.txt`, `wide-tests.txt`: test output.
- Synthetic fixtures only; no real accounts, payments or friend requests used.
- Generated PNGs and exact prompts/checksums are committed; screenshots are local
  review artifacts, not screenshots of a publicly deployed build.

## Result envelopes / synthesis

All records: contract_version=1.0, run_id=reference-pages-v7, plan_revision=1.

| Unit | Result | Acceptance / direct evidence |
|---|---|---|
| WU-01 | succeeded | Isolated baseline and strict owner-reference contract; 89b1a1ef |
| WU-02 | succeeded | Shared components, inspected generated assets, successful compilation |
| WU-03 | succeeded | Four page layouts, real data/callback bindings; source diff and screenshots |
| WU-04 | succeeded | Tests/build/visual matrix above; corrected observed regressions |

Accepted units: WU-01, WU-02, WU-03, WU-04. Missing/failed/invalidated/duplicate/
orphaned units: none. Initial failed test runs are diagnostic history, not accepted
validation. AC-UI-001..005 met for the implemented reference-guided candidate.
Aggregate status: degraded (physical device and owner visual acceptance outstanding).

Explicitly excluded claims: pixel-identical reproduction; working newly-shipped
OAuth; production deployment; phone installation; real two-phone E2E; ready-to-merge
CI. This candidate uses the existing game structure: no fake reference-only data,
extra inventory, XP system or completed-match screen was invented for visual parity.

Next owner: project owner visual review, then Integrator for the pending dependency
stack and release gates. No VPS builds, no website publication, no phone updates.
