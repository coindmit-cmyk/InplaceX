# INPX-UX-207 — Shared visual polish against final reference

- Status: `integration_requested`
- Role: `make-human`
- Branch: `codex/make-human/INPX-UX-207-gameplay-visual-polish`
- Base: `automation/develop@83f529a89b59c2b06c4fee281604b4bdbb1994cf`
- Package: `INPLACEX-FINAL-UI-20260812-v001`

## Result

Aligned the shared Android visual layer with the approved warm-room reference
without changing navigation, game rules, ViewModels, persistence, online
contracts, ads, or purchases.

- Replaced bright cyan shell chrome with the common deep-navy token set.
- Reduced global panel radii, padding, borders, and elevation.
- Removed duplicate shadows from the top HUD, bottom navigation, and scene cards.
- Rebuilt Home mode cards with quieter three-stop orange, purple, and green gradients.
- Condensed the gameplay status strip into one visual row.
- Replaced Material tonal keypad pills with compact warm-surface controls.
- Preserved a 44dp keypad hit height while keeping the visible keys compact.
- Kept the status strip height adaptive for increased system font scale.
- Kept the approved gameplay breakpoint: 4–6 side-by-side, 7–10 stacked.
- Restyled the shared Race/Duel opponent chooser with compact warm and primary actions.
- Updated the canonical UI contract, token copy, consolidated packet, and checksums.

## Verification

- `:app:testDebugUnitTest` — passed.
- `:app:lintDebug` — passed.
- `:app:assembleDebug` — passed.
- `:app:assembleDebugAndroidTest` — passed.
- Targeted Android instrumentation for 4, 8, and 10 digits on Samsung SM-S926B — passed, `1/1` each.
- Debug APK installed and launched on Samsung SM-S926B and OnePlus 9 Pro through ADB server port 5030.
- `git diff --check` — passed.
- Final UI package SHA-256 manifest — passed against normalized repository content.

## Visual evidence

Local owner-review captures:

- `D:\tmp\Dmit\inplacex-visual-polish-v1-final\oneplus-live.png`
- `D:\tmp\Dmit\inplacex-visual-polish-v1-final\samsung-live.png`
- `D:\tmp\Dmit\inplacex-visual-polish-v1-final\visual-final-a11y-4.png`
- `D:\tmp\Dmit\inplacex-visual-polish-v1-final\visual-final-a11y-8.png`
- `D:\tmp\Dmit\inplacex-visual-polish-v1-final\visual-final-a11y-10.png`

## Integration request

The scoped visual slice is ready for normal PR review and integration into
`develop`. Remaining final-art assets and music stay outside this package.
