# Final Product Consolidation

## Why The Product Drifted

The July product stack preserved source changes but did not preserve one integrated acceptance
contract:

- Company UX v3, App Shell UX v4, and Toy Room UI v5 were stacked on separate long-lived branches.
- Toy Room v5 was implemented as a foundation with gradients and icon fallbacks, while the owner
  expected the supplied references to define the actual visual result.
- Company retained an older dark-console contract that conflicted with the new warm toy-room
  direction.
- Friends/Online implemented a separate text-field match instead of reusing the canonical Duel
  game field.
- Debug test grants existed only behind a hidden developer screen, so the installed test build did
  not expose the promised 10,000-coin test balance.

Git history still contains the gameplay fixes from PRs 3, 6, and 9. The loss was integration and
acceptance drift, not deleted commits.

## Final Integration Contract

- Start every product slice from the current remote `develop`.
- Use one scoped `feature/*` branch and one clean worktree.
- Preserve existing dirty worktrees and untracked artifacts.
- Reuse `GameScreen` for Race, local Duel, Company, and active Online Duel.
- Use `toy_room_bg_v6.png` as the single shared environment. Do not nest a second viewport
  background inside a screen.
- Keep debug grants in the debug source set and production behavior in the release source set.
- Merge only after unit, lint, debug/release assembly, emulator screenshots, online staging, and
  physical-device smoke are tied to the same exact commit.
- After integration, delete the completed feature branch and its isolated worktree.
