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
