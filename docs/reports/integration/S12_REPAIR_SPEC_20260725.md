# S12 repair specification: game localization convergence

## Preserved evidence and base

- Use the current `origin/develop` and preserve all behavior delivered by S08
  and S09.
- The interrupted attempt and its uncommitted edits remain preserved at
  `/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s12-20260725T195202Z`.
- Do not reuse that worktree or branch. A fresh Worker may inspect its diff and
  selectively reproduce useful localization additions against current
  `origin/develop`.
- Read `InplaceX-docs/Game/Human/Configs Localization Branding.md` and this
  repair specification before editing.

## Scope correction

The previous packet referenced a path that does not exist. The canonical
catalog is:

`InplaceX-android/app/src/main/java/com/mirkori/inplacex/platform/localization/GameCatalog.kt`

The task covers visible text and accessibility descriptions in:

- active game and debug game screens;
- race game;
- race setup;
- their RU/EN catalog entries;
- unit tests and Android UI tests for these surfaces.

`StaticLocalizationProvider.kt` remains forbidden. S09 already connected
`GameCatalog`; this task must not restructure the provider aggregation.

## Required behavior

- Every user-visible string and `contentDescription` in scope uses a stable
  localization key with complete RU and EN values.
- Debug tools remain available for the current testing phase and are localized.
- RU and EN expose the same key set and the same placeholder set per key.
- No used key is rendered to the user as a fallback.
- `GuessValidationReason`, `GameFieldStatus`, and `GameFieldNotice` remain typed
  mappings. Do not compare translated strings to make game decisions.
- Submitting `1111` shows the localized all-same-digits explanation in both RU
  and EN.
- Preserve S08 behavior: latest-attempt auto-scroll, `0000` plus `4060`
  deduction, confirmed facts, manual hypotheses, hints, boosts, timers,
  fixed/debug secret, and current navigation/callback contracts.
- Remove mojibake and reject the replacement character `�`.
- Do not change game rules, deduction, state ownership, lifecycle behavior,
  navigation, or route signatures in this localization task.

## Required tests and evidence

The Worker report must record:

1. `bash gradlew :app:testDebugUnitTest`
2. `bash gradlew :app:assembleDebugAndroidTest`
3. `bash gradlew :app:lintDebug`
4. `bash gradlew assembleDebug`
5. `git diff --check`
6. RU/EN catalog parity and placeholder parity tests.
7. Tests proving every used game/race/debug key resolves in both locales.
8. Tests proving typed validation/status mappings are independent of translated
   string values.
9. Static assertions or focused tests showing no hard-coded user-facing Russian
   or English phrases remain in the scoped Compose screens.

The Integrator must additionally run the existing game instrumented suite on an
emulator and smoke-test RU and EN for:

- the message after `1111`;
- race setup;
- race game status/actions;
- game accessibility descriptions;
- the debug screen.

## Stop conditions

- If a required key belongs to another catalog/provider outside the allowed
  scope, return `needs_dispatcher_repair` with the exact path instead of editing
  `StaticLocalizationProvider.kt`.
- If localization requires changing game logic, state, lifecycle, navigation,
  or public callbacks, stop and route that issue to the responsible task.
- Missing emulator access is not a reason to skip compiling AndroidTest; record
  it for the Integrator, which owns the runtime UI smoke.
