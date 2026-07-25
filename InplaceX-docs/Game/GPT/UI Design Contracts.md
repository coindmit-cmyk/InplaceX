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
- Generated mockups are reference evidence, not runtime assets or implementation authority.

## State Semantics

- primary action: cobalt
- secondary competitive action: indigo
- focus and live information: cyan
- reward and currency: amber
- confirmed success: mint
- invalid, destructive, or failed state: coral

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

Do not replace all screens in one patch. Migrate shell, home, `Race`, `Duel`, and secondary sections as independently reviewable slices with behavior-preservation evidence.
