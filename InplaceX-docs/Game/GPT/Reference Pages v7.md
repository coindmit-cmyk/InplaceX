---
artifact: game-ui-contract
version: 1
project: InplaceX
feature: reference-pages-v7
ui_level: strict
status: implementation
target_orientation: portrait
---

# Reference pages v7

## Inputs and authority

Owner reference UI-VIS-001: `codex-clipboard-c07dc4a7-9c2c-47ed-a848-924c3798fd9d.png`, 2026-08-26.
The owner explicitly requests this visual direction. It supersedes the flatter
v6 page treatment, not the game/auth/billing contracts. Reference values and
illustrated players are examples, not data to seed into production.

UX source: `final-ui/v001/01_UX_CONTRACT.md`, sections 5–12. This is Kotlin
Compose, not Unity; implementation stays in the existing Android module.
Execution recommendation: large multi-screen UI task, frontier model with high
reasoning; sequential bounded implementation, local build and emulator review.

Fresh base: automation/develop b5a4d507. Isolated candidate integrates existing
PR95 (66036751, includes PR93/94) and PR96 (bc927282) through Git, preserving
pending auth/invite/input fixes. Integration is local only; no approval of those
PRs, merge to develop, provider configuration or deployment is implied.

## Player tasks, surfaces and navigation

| ID | Purpose / priority | Input and state ownership | Presentation |
|---|---|---|---|
| UI-SUR-001 | Shared resources and 5 destinations, persistent | Existing shell callbacks/insets; no game state change | Room backdrop, compact navy resource pills, navy bottom bar |
| UI-SUR-002 | Friends / invite / find match | Existing Social destinations, profile-scoped requests and pending invite | White heading, cream friends/request panels, purple + blue actions, green match strip |
| UI-SUR-003 | Select and play campaign level | Existing selection, unlock, stars, energy, reward/history actions | Teal heading, chapter panel, illustrated forest map with interactive nodes, sticky details/play |
| UI-SUR-004 | Supplies and premium | Existing prices, stock, billing callbacks and availability | Purple reward panel, orange segmented control, cream 2-column product grid |
| UI-SUR-005 | Account identity/connections/stats | Server account state, real avatar, existing edit/sign-in callbacks | Blue player card, cream connections and stat tiles |

Back/modal ownership and restoration remain unchanged. Maximum modal depth stays
the existing one-dialog flow; no new purchase/auth step. Rendering never pauses
network updates. Menus scroll; gameplay is excluded from this visual pass.

## Components and states

| ID | Binding | States and test hooks |
|---|---|---|
| UI-CMP-001 | ContentCard, PageHeroCard, SceneActionTile | Cream bevel, colored gloss; disabled remains labelled; existing semantics |
| UI-CMP-002 | FriendRequestInbox + real friend preview | Empty/count/single/multiple/loading/error; no invented online presence |
| UI-CMP-003 | Campaign map nodes | Locked/available/completed/selected, number + lock/star, company-level-N tags retained |
| UI-CMP-004 | ShopItemCard | Real icon/price/stock; disabled insufficient funds, callback unchanged |
| UI-CMP-005 | Profile card + stats | Guest/linked/provider error; no inferred account association |

## Layout, visual system and art

- UI-VIS-001 is the production direction for all four pages. Home uses the same
  chrome and background without changing its mode navigation.
- Cream #FFF4DE to #FFEBC7, gold #D8B36D, navy #173B5E to #092A49,
  blue #0B6EDB, purple #704BB8, green #386B27. Warm dark text on cream.
- 12dp page gutters, 10dp gaps, 16dp cards, 12dp buttons, one soft shadow.
  Thin inner top highlight adds volume. No full-screen blue surface.
- Typical 412dp portrait; validate 360dp small, 412dp typical, 600dp wide,
  RU 1.0 and EN 1.5 text. Cards grow for text; shop stacks at large font/narrow
  width. Compact campaign windows keep the play/rules bar and move the objective
  text to the existing rules dialog so the route remains visible. Narrow large-font
  navigation uses a fitted 9sp base (still scaled by the user's font setting).
  Targets >=48dp. Menu controls observe system bar insets; gameplay geometry is unchanged.
- Existing room and hint art reused. UI-ART-001 is a new forest map painting,
  no text/numbers/buttons baked in. Numbered nodes remain Compose and accessible.
  Existing avatars remain the actual player's selected/remote avatar.
- String keys stay localized. Decorative art is contentDescription=null;
  actionable controls retain labels, selected/state semantics and test tags.
- No new motion; static bitmaps decoded by painterResource, no per-frame work.

## Acceptance / review

| ID | Condition | Evidence / unit |
|---|---|---|
| AC-UI-001 | Shared warm room/navy chrome, beveled cream cards and colored hero/actions match reference hierarchy | 4 page screenshots, WU-02/03/04 |
| AC-UI-002 | Campaign visibly uses forest route and distinct real level states; rewards/history/play preserved | campaign tests/screens, WU-03/04 |
| AC-UI-003 | Friends, account links, invites, free-position input and shop callbacks still work | regression tests, WU-04 |
| AC-UI-004 | RU/EN and narrow/large-font layouts remain readable/tappable, all content reachable | emulator matrix, WU-04 |
| AC-UI-005 | No fabricated data, prices, inventory or provider state; no gameplay engine changes | diff review, WU-04 |

## Plan snapshot

contract_version: 1.0; run_id: reference-pages-v7; plan_revision: 1.
All units required, sequential, parent-executed; no delegated mutation.

| Unit | Dependency | Bounded objective | Checks/status |
|---|---|---|---|
| WU-01 | none | Reference contract and isolated baseline | current refs + preserved auth merge; succeeded |
| WU-02 | WU-01 | Common surfaces and map art | asset inspect, compile; succeeded |
| WU-03 | WU-02 | Four page compositions | preserved callbacks/state, compile; succeeded |
| WU-04 | WU-03 | Tests, screenshots, Git handoff | unit/instrumented/build/diff passed; see report |

Synthesis barrier: all ACs must have direct evidence. No final pixel-match claim
from a build alone. Physical phone/release validation is separate and not implied.
Open question (non-blocking): exact source illustration files are unavailable;
new map art is an interpretation of the supplied reference, not its source asset.
