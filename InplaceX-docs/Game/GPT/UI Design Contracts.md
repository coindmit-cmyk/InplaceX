# UI Design Contracts

## Canonical Sources

- Product intent: `../Human/Design System v2.md`
- Runtime color tokens: `InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/theme/Color.kt`
- Runtime theme: `InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/theme/Theme.kt`
- Shared scene primitives: `InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/screens/shared/SceneChrome.kt`
- Shell appearance metadata: `InplaceX-android/app/src/main/java/com/mirkori/inplacex/platform/config/ShellAppearanceConfig.kt`

## Stability Rules

- Production branding must not depend on Android dynamic color.
- Screens consume semantic theme tokens instead of introducing new raw hex values.
- Shared visual behavior belongs in theme, shell, or shared scene primitives.
- `Duel` and `Race` remain the only base game-field families.
- A visual refactor must not duplicate match orchestration or change match rules.
- Generated full-screen mockups are reference evidence, not runtime screens. Approved independent
  decorative assets derived from them may ship when they contain no controls, labels, or game state.

## Toy Room v5 Visual Direction

The owner-approved visual references define the current art direction for
Home, Company, Friends/Online, Shop, Profile, and the Race game field.

- The environment reads as a warm, softly lit tabletop or study room rather
  than a dark digital dashboard.
- Global chrome uses glossy cobalt-to-navy panels, bright cyan edge light,
  white labels, gold resource symbols, and small green add actions.
- Content cards use warm cream faces, caramel outlines, rounded corners, and
  visible depth.
- Active game panels use the same cream face and caramel outline instead of
  unrelated Material surface colors; compact tool rows must preserve complete
  RU and EN labels without changing their game semantics.
- Primary mode identity is stable: Race is orange, Duel is purple, and Company
  is green. Blue remains navigation and structural chrome.
- Interactive controls should look like tactile game pieces while preserving
  Compose semantics, minimum target sizes, localization, and adaptive layout.
- `toy_room_bg_v6.png` is the canonical shared environment for shell and active matches. Screens
  place native Compose content directly over this environment and must not add a second
  full-viewport cream or white background.
- Rich illustrations remain independent decorative assets. Interactive controls, labels, live
  values, focus, accessibility, and game state stay native Compose.
- Screenshots remain visual references only; they must not be embedded as
  interactive screen backgrounds.

## State Semantics

- structural navigation and chrome: glossy blue
- Race and urgency: orange
- Duel and social competition: purple
- Company and confirmed progression: green
- focus and selected navigation: cyan edge light
- reward and currency: gold
- content surfaces: warm cream
- invalid, destructive, or failed state: red

Every state also requires at least one non-color signal such as text, icon, border, shape, or position.

## Screen Contract

Each first-level screen must preserve:

- global top resource and settings access;
- global bottom navigation outside active matches;
- safe drawing and navigation bar insets;
- minimum `48dp` touch targets for primary actions;
- localized user-facing strings;
- release isolation for debug-only controls.

Home mode cards keep titles to one line and descriptions to two lines on phone
layouts. The shared opponent-choice surface groups code length and opponent
actions inside one cream card and uses the current mode color only for the
primary action.

## Gameplay Color Pass Contract

- Scope is visual only: keep every screen, block, breakpoint, weight, height,
  spacing value, navigation callback, game-state transition, and ad slot
  unchanged.
- Gameplay surfaces consume `FinalUiColors`; new gameplay hex values do not
  live in presentation components.
- Status uses the current mode accent. Attempts use structural blue, analysis
  uses violet, helpers use semantic multi-color accents, tools use
  red/yellow/green, and input uses blue with warm cream/gold keys.
- Empty, selected, manual, locked, disabled, and focus states remain distinct
  by border weight, fill, text, and existing non-color signals.
- Color treatment must remain readable at 4, 8, and 10 digits and must not
  change the 4–6 side-by-side / 7–10 stacked gameplay geometry.
- The banner/rewarded ad implementation and banner allocation remain outside
  this pass; visual work must not wrap, replace, cover, or resize the ad slot.

## Online Entry And Invitation Contract

- Both Race and Duel open an opponent choice before gameplay: bot or online.
- The opponent-choice screen exposes secret length for both Race and Duel. The
  selected length applies unchanged to the bot match or becomes the initial
  value of the online match settings.
- The online transition carries the selected play style; it must not silently
  fall back to the screen default.
- `Play` on a saved friend opens private match settings and the primary action
  is `Send invitation`, never public matchmaking.
- Friend search uses the Mirkori display name, public handle, or full player
  UUID. An outgoing request remains a non-playable pending item until accepted;
  only the authoritative server friends list may produce playable friends.
- An incoming friend request is visible on the Social root and inside Friends,
  where the recipient can accept it without relying on a system notification.
- Profile exposes a copy action for the current full player ID.
- A direct invitation produces both a recipient-only in-app Social badge/card
  and an eight-character fallback code for that same recipient. While the app
  process is active, polling also emits an Android system notification after
  the runtime permission is granted.
- Provider push after the application process has been removed remains a
  separate concern and must not be implied by the polling state.

## Online Opponent Progress Contract

- Active online Race and Duel matches show one compact opponent-progress panel
  between the match status and the shared work board.
- The panel shows only server-authoritative redacted information: opponent
  activity, attempt count, latest exact-match score, and best exact-match score.
- Opponent guesses and secrets never enter the presentation state.
- Local, bot, and campaign routes keep the same geometry and do not render the
  panel. The shared game-field renderer remains the only visual implementation.

## Verification

For each redesigned screen:

1. record the current navigation and behavior inventory;
2. implement through shared tokens and components;
3. run `:app:testDebugUnitTest`;
4. build `assembleDebug`;
5. capture phone screenshots for the supported compact and tall layouts;
6. manually verify Russian and English strings, large font scale, contrast, and back navigation.

## Migration Rule

New work starts from the current `develop`, uses one scoped `feature/*` branch, and returns to
`develop` only after its behavior-preservation checks pass. Stacked product branches are not a
substitute for an integrated acceptance build.
