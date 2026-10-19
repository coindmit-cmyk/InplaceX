# Shell Appearance Contracts

## Canonical config

Shell visual tokens are configured in:

- `platform/config/ShellAppearanceConfig.kt`
- `platform/config/AppConfigCatalog.kt`

## Model

`ShellAppearanceConfig` contains:

- `appBackground`
- `topBar`
- `centerSurface`
- `bottomBar`

`TopBarAppearanceConfig` contains:

- `container`
- `backIcon`
- `energyIcon`
- `coinsIcon`
- `settingsIcon`

The v5 runtime also exposes a Shop shortcut outside an active match and
renders compact resource add actions. On narrow phones the Shop text may
collapse to its icon, but its semantics and touch target remain available.

Each layer/icon is currently configured as:

- `imageAssetPath`
- fallback color/tint

Current runtime behavior:

- image paths are treated as source-of-truth metadata
- runtime Compose still renders fallback colors and material icons
- this keeps the shell editable without introducing a full asset-loader yet

## Online game ownership contract

- Shell appearance follows the child's actual game state, not its destination: setup/active play owns the in-game shell; Ready, loading, waiting, error, and finished states use the ordinary menu shell.
- The online child reports state through SocialRoot. While that child owns Back, its HUD exit is forwarded and consumed once; system Back remains supported. The active-to-match setup contract is preserved, and the latest callback/disposal resets shell state.
- During gameplay the child-owned HUD exposes Back and shell navigation (including shop/tabs) is hidden. The menu shell returns after result/exit.
- A suitable SDK banner reserves bottom space only after `onAdLoaded`; menus and ineligible entitlement/consent states do not gain an ad slot. Existing ad producer and entitlement/consent policy remain authoritative.

## Localization keys

Top bar keys:

- `top.back`
- `top.energy`
- `top.coins`
- `top.settings`
- `section.shop.short`

## Current limitation

The shell top is now globally configured, but some game screens still keep their own internal match header. That header should be reduced further in a later pass if the project decides to move all navigation/status controls fully into the global `Top` shell.
