# Codex Master Task — InplaceX Final UI Pass

Execution recommendation: resolve through the repository routing policy; reasoning high; complexity XL for the complete package. Implement as the four bounded tasks in `07_TASK_CANDIDATES.json`, not as one uncontrolled rewrite.

## Orientation

Before editing:

1. Read `AGENTS.md`.
2. Read `.agent/START_HERE.md`, `.agent/CODEX_CHAT.md`, `.agent/general.md`, `.agent/project.md`, `.agent/modules.md`, `.agent/workflows.md`.
3. Run the GitHub freshness guard and prove the checkout is exact at current `origin/develop`.
4. Read this package in order: `00_OWNER_DECISIONS.md` through `06_ACCEPTANCE_AND_QA.md`.
5. Read `07_TASK_CANDIDATES.json` and execute only the first eligible task.
6. Inspect current code before copying provided Kotlin primitives.

Package authored against `develop@5f37f4e138f96cdf70c489237b28137a351b3892`. If current `develop` differs, report the changed UI files and adapt; do not reset owner work and do not blindly overwrite newer code.

## Goal

Apply the final warm-room/polished-casual UI system to the existing Android app while preserving all behavior. This is a visual convergence pass, not a product redesign.

## Non-negotiable owner decisions

- Keep `toy_room_bg_v6` bright and visible.
- Keep attempts left and code matrix right for 4–6 digits; stack attempts above the matrix for 7–10 digits.
- Attempt rows are `1234 → 2`, not per-digit cards.
- Digits stay inside matrix cells.
- Attempts and matrix remain simultaneously visible.
- Optimize 4–8 digits and preserve the existing 9–10 online fallback.
- Keep top HUD, five-item bottom navigation and ad slot semantics.
- Do not change game/domain/state/network/purchase/ad behavior.

## Use the supplied implementation kit

- Copy/adapt `code/FinalUiTokens.kt` to `ui/theme/FinalUiTokens.kt`.
- Copy/adapt `code/FinalUiPrimitives.kt` to `ui/common/FinalUiPrimitives.kt`.
- Follow `code/IntegrationExamples.md`.
- Treat technical layouts as geometry evidence.
- Treat `approved_mood_only.png` as mood only.

## Task sequence

### INPX-UX-201 — gameplay

Implement tokens/primitives and refactor presentation only in `GamePresentationComponents.kt`. Preserve `GameScreen` boundary, callbacks, test tags, auto-scroll, facts/marks and dialogs. Produce all 4/6/8/10 gameplay captures. Stop for owner visual approval.

### INPX-UX-202 — shell/home

After explicit owner approval, update `AppTopBar`, `AppBottomMenu`, `AppBottomAd`, shared scene components and Home. Preserve shell modes, background and navigation.

### INPX-UX-203 — company/social

Converge Company and Social visuals. Do not change progression or online session logic.

### INPX-UX-204 — remaining/QA

Converge Shop/Profile/Settings, run full visual capture matrix and release checks.

## Forbidden expansion

Do not:

- alter `GameFieldScreen`, state holder, ViewModel, engine, data or backend for visual convenience;
- reduce code-length contracts;
- introduce bitmap panels, new font binaries or external UI dependencies;
- rename routes or change localization-backed behavior;
- silently fix adjacent non-UI issues in the same PR.

Route discoveries outside scope to a separate input package.

## Mandatory evidence

For every task return:

- exact base SHA;
- changed files;
- checks and results;
- captures required for that task;
- deviations/blockers;
- branch/commit/PR;
- `integration_requested` only when the task output contract is fully satisfied.

If a required check or capture cannot run, return `needs_worker_fix` or `visual_qa_blocked`, not success.
