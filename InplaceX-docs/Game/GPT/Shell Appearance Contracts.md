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

## Localization keys

Top bar keys:

- `top.back`
- `top.energy`
- `top.coins`
- `top.settings`
- `section.shop.short`

## Current limitation

The shell top is now globally configured, but some game screens still keep their own internal match header. That header should be reduced further in a later pass if the project decides to move all navigation/status controls fully into the global `Top` shell.
