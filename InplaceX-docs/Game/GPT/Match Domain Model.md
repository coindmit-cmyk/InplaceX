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

Online duel orchestration may pass a nullable duel attempt limit while keeping
the underlying `GameConfig.attemptLimit` positive. A null duel limit means that
move count cannot end the match; the server clock or a solved secret is the
authoritative terminal condition.

## Current Note

There are still legacy model shapes in the repo (`MatchState`, `GuessResult`, `GameStatus`) used by older UI flows. They are transitional and should converge toward the canonical match contracts over time.

## Automatic table: grouped evidence

Accepted exact-match scores also form exact sums over position/symbol groups.
Subtracting a contained group can prove the remainder impossible or exact even
when the Cartesian search space is above the enumeration budget. For example,
`000111=1`, `111323=0`, `000323=1` excludes `1` in every position but does not
identify which of the first three zeros matches. Manual hypotheses constrain
candidates but never become authoritative facts. Group comparisons and derived
groups have explicit conservative budgets; exhausting one means fewer deductions,
not guessed facts or unbounded search.

## Terminal Presentation Contract

- `WON` and `LOST` are stable terminal phases.
- A terminal match must present an explicit result before the route can start a new match.
- Victory must not silently generate a new secret.
- Retry is an explicit player action that creates a fresh match session.
- Leaving the result returns to the mode entry screen.
