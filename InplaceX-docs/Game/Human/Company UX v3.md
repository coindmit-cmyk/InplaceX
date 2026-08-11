# Company UX v3

## Outcome

The Company screen becomes a native Compose mission console instead of a narrow decorative stone path. The redesign keeps the existing campaign progression, energy, history, match launch, result, rating, and unlock behavior.

The owner-provided Toy Room references and the shared `toy_room_bg_v6.png` environment define the
shipped direction. Mission controls remain semantic, adaptive Compose components.

## Preserved behavior

- The highest unlocked or next gated level remains the initial focus.
- Completed levels remain replayable.
- Campaign energy is required to start a level and can be replenished from the screen.
- Chapter gates still use completed-level and star thresholds from `CampaignProgressionRules`.
- History shows completed levels and launches the selected replay.
- Match exit, completion, failure, rating, stars, hints, and boosts keep their existing contracts.

## Interaction model

1. Opening Company scrolls the mission timeline so the current level and the next mission are visible.
2. Tapping any mission selects it and expands its real rules: code length, move budget, time, duplicate policy, and personal best.
3. Starting a mission requires a separate, sticky primary action. This prevents accidental launches while browsing.
4. Locked missions explain whether the player needs more stars or must complete the previous level.
5. Energy and History remain reachable in the screen header.
6. Level rules are available without starting a match.

## Visual and accessibility contract

- A single warm study/toy-room environment sits behind the route. The Company screen must not draw
  a second white or cream full-screen background.
- Glossy cobalt navigation and chapter chrome, warm cream mission cards, green completion,
  amber progress/rewards, cyan focus, and coral only for high difficulty or errors.
- State is never communicated by color alone: completed, selected, and locked missions also use text, icons, borders, and controls.
- Primary controls are at least 48 dp; the launch action is at least 56 dp.
- Compact phones use reduced spacing and labels while preserving the same information hierarchy.
- Russian and English copy comes only from the localization catalog.
- Stable test tags: `company-mission-list`, `company-level-{n}`, `company-history`, `company-energy`, and `company-play`.
- Chapter rewards remain reachable in both compact and tall portrait; compact height keeps the reward action accessible in the screen header.

## Architecture

- `CompanyRootScreen.kt`: route state, match lifecycle, dialogs, and callbacks.
- `CompanySceneScreen.kt`: screen state, selection, and adaptive list orchestration.
- `CompanyHeaderComponents.kt`: Company header and chapter progress.
- `CompanyMissionTimeline.kt`: mission states and expanded mission facts.
- `CompanyActionBar.kt`: sticky launch action and rules dialog.
- `CampaignHistoryScreen.kt`: completed-level history.
- `CompanyCampaignLogic.kt`: testable progression and rating helpers.

## Acceptance

- Unit tests cover ordered mission projection, rating-to-star thresholds, and chapter unlock thresholds.
- Localization parity remains green for RU and EN.
- `verifyProject`, debug assembly, and a physical-phone smoke are required before integration.
- Screenshots must be checked on one compact and one tall portrait viewport.

## Physical-phone evidence

- `assets/company-v3-phone-portrait.png`: tall portrait behavior with expanded mission details and upcoming locked levels.
