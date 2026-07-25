# S12 integrator review — e6b9689

## Verdict

`REJECT` for integration. Preserve commit `e6b9689647e21f8efa368f562949f1ef1b9323b8`
as the candidate baseline and run one narrow append-only repair.

## Blocking finding

`GameFieldDebugScreen` renders validation feedback through
`debugFeedbackText(feedback, strings::text)`. That wrapper delegates to the
shared typed `feedbackText`, but it does not receive the configured code length.
For `GuessValidationReason.INVALID_LENGTH`, the catalog value therefore reaches
the UI with the placeholder still present:

- `Введите {count} цифр`
- `Enter {count} digits`

The debug screen permits submitting an empty or short guess, so this is a
reachable user-visible defect.

## Required repair

1. Start from current `origin/develop` and preserve the accepted S12 candidate
   by cherry-picking `e6b9689647e21f8efa368f562949f1ef1b9323b8`.
2. Pass `codeLength` into the debug feedback mapping and replace `{count}` for
   `INVALID_LENGTH` without parsing translated strings.
3. Add a focused unit test for the typed invalid-length mapping.
4. Add RU and EN Android UI checks for submitting a short guess.
5. Re-run all S12 packet checks. `lintDebug` may only match the three existing
   baseline `UnusedBoxWithConstraintsScope` findings; no new lint finding is
   allowed.

Do not change game rules, state ownership, deduction, lifecycle, navigation,
callbacks, `StaticLocalizationProvider`, or files outside the existing S12
scope.

## Independent evidence

- Unit tests: passed.
- `assembleDebugAndroidTest`: passed.
- `assembleDebug`: passed.
- `git diff --check`: passed.
- Emulator: 10/10 current localization and S08 regression tests passed.
- Lint delta: zero; worker and base contain the same three baseline errors.
- RU/EN catalog parity: 116/116.
- Raw `state.message` and `phase.toString()` rendering: removed.
- Accessibility semantics for race glyph controls and setup steppers: present.

The green matrix does not waive the visible unresolved placeholder.
