# Match Domain Model

## Canonical Contracts

- `GameConfig`
- `GameModeDefinition`
- `MatchEngine`
- `MatchSnapshot`
- `MatchAttempt`
- `MatchPhase`
- `OpponentProvider`
- `OpponentKind`

## Lifecycle

1. `start()`
2. `submit(rawGuess)`
3. validation
4. scoring
5. phase update
6. `snapshot()`

## Invariants

- `codeLength in 4..20`
- `attemptLimit > 0`
- win iff `score == codeLength`
- lose iff `attempts.size >= attemptLimit`
- validation rules come from `GameConfig`

## Current Note

There are still legacy model shapes in the repo (`MatchState`, `GuessResult`, `GameStatus`) used by older UI flows. They are transitional and should converge toward the canonical match contracts over time.

## Terminal Presentation Contract

- `WON` and `LOST` are stable terminal phases.
- A terminal match must present an explicit result before the route can start a new match.
- Victory must not silently generate a new secret.
- Retry is an explicit player action that creates a fresh match session.
- Leaving the result returns to the mode entry screen.
