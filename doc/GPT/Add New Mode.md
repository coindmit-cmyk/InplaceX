# Add New Mode

## Procedure

1. Add a new `GameModeDefinition` to `AppConfigCatalog.gameModes`
2. Bind it to an `OpponentKind`
3. Map it into the relevant client screen flow
4. Add localization keys for title/subtitle
5. Verify `GameConfig` is sufficient before inventing new engine paths

## Guardrails

- do not fork `GameEngine`
- do not add Android-specific behavior to core contracts
- prefer orchestration changes over logic duplication
