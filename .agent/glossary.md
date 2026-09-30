# InplaceX Glossary

- `secret`: The hidden digit sequence a player or bot tries to guess.
- `guess`: A submitted digit sequence.
- `score`: Exact-position match count for a guess.
- `GameConfig`: Shared rules for code length, attempt limits, duplicate policy, and validation constraints.
- `GameModeDefinition`: Mode-level definition that should carry mode differences instead of duplicating the engine.
- `MatchEngine`: Canonical match lifecycle owner.
- `OpponentProvider`: Boundary for human, bot, local, remote, or future online opponents.
- `Race`: Mode family where participants work against one shared secret.
- `Duel`: Mode family where each participant owns a secret and guesses the opponent's secret.
- `BotAgent`: Canonical shared bot brain.
- `BotSolver`: Compatibility facade over `BotAgent`.
- `ServerBotPlayer`: Backend-side bot participant adapter.
- `PlatformConfig`: Central platform configuration object.
- `ProviderConfig`: Runtime provider ids and environment data loaded from local/build configuration.
- `shell`: Shared app frame: top bar, bottom areas, navigation, ads/premium slots, and common screen chrome.
- `canonical docs`: The source-of-truth documentation under `InplaceX-docs/Game/Human` and `InplaceX-docs/Game/GPT`.
