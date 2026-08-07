# Data Driven Model

## Must Move Out of Screen-Level Hardcode

- section titles
- section short labels
- shell reserve texts
- game mode titles
- game mode subtitles
- mode parameters
- feature flags
- branding values
- icon source paths

## Current Implementation

Initial centralization lives in:

- `platform.config.AppConfigCatalog`
- `platform.localization.StaticLocalizationProvider`
- `ui.navigation.AppSectionIconCatalog`
- `image/icon`

`GameModeDefinition.moveLimit` is the user-visible move cap. `null` means that
the mode is unlimited; it must not be represented by an invalid zero
`GameConfig.attemptLimit`. Likewise, a missing turn timer is represented by
`turnTimeLimitSeconds = null`.

## Non-Goal

Do not move match rules into arbitrary config blobs. Logic stays in code.
