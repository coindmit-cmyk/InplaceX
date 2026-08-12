# INPX-UX-201 — Final gameplay vertical slice

- Status: `visual_qa_pending_owner`
- Branch: `codex/make-human/INPX-UX-201-final-gameplay`
- Base: `automation/develop@5f37f4e138f96cdf70c489237b28137a351b3892`
- Package: `INPLACEX-FINAL-UI-20260812-v001`

## Result

Implemented the first bounded final-UI slice without changing game rules, state,
ViewModels, persistence, online contracts, or the legacy `GameFieldScreen` route.

- Added the approved warm visual tokens and reusable gameplay primitives.
- Kept attempts on the left and the analysis matrix on the right.
- Replaced per-digit attempt cards with compact `guess -> score` rows.
- Added adaptive 4, 6, 8, and 10 digit geometry without horizontal scrolling.
- Preserved callbacks, semantics, haptics, sound hooks, and existing test tags.
- Kept the ad slot outside the gameplay input panel and verified it does not overlap.
- Added deterministic screenshot instrumentation for required gameplay states.

## Verification

- `:app:testDebugUnitTest` — passed.
- `:app:assembleDebug` — passed.
- `:app:assembleDebugAndroidTest` — passed.
- `:app:lintDebug` — passed.
- Android instrumentation on isolated API 35 emulator — `73/73` passed.
- `git diff --check` — passed.
- GitHub freshness guard — branch base exactly matched current `automation/develop`.
- Physical phones were discovered on ADB port 5038 but were not selected or modified.

## Visual evidence

Required captures are stored durably under
`InplaceX-docs/Game/GPT/final-ui/v001/captures/` (and generated locally under
this report's ignored `captures/` directory):

- `game_4_empty_ad.png`
- `game_4_filled.png`
- `game_6_filled.png`
- `game_8_empty.png`
- `game_8_filled.png`
- `game_10_filled_en.png`
- `game_wait_opponent.png`
- `game_helpers_boosts.png`

## Stop gate

`owner_gameplay_visual_approval` is intentionally still open. No PR or merge is
requested until the owner accepts the gameplay screenshots or supplies corrections.
