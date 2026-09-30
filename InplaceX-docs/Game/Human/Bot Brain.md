# Bot Brain

## Purpose

Bot is a standalone game entity.

The same decision logic must be reusable in two places:

- local offline bot inside the client
- server-side bot that replaces players while online is low

Only the transport layer changes. The bot brain itself must stay shared.

## Current Scope

- alphabet: digits only, `0..9`
- feedback after a guess: only the number of exact position matches
- current main duel target: 6-digit secrets
- duplicate patterns like `1212` are allowed

## Rule Flags

Rule flags apply both to:

- generated secrets
- bot guesses
- grid search guesses

Current flags:

- `allowDuplicates`
- `forbidAdjacentDuplicates`
- `forbidTripleDuplicates`
- `forbidAllSameDigitsGuess`

Examples:

- `1212` is always allowed when adjacent duplicates are enabled
- `112345` is forbidden when `forbidAdjacentDuplicates = true`
- `111234` is forbidden when `forbidTripleDuplicates = true`

## Difficulty Model

### Easy

Easy does not use the grid stage.

Flow:

1. Find a safe base guess with `0` exact matches.
2. During safe-base search, build each new guess as a full random code across all unresolved positions.
3. Do not walk easy guesses as visible sequences like `0001`, `0002`, `0003`.
4. Keep the safe symbols on all untouched positions.
5. Test one candidate digit on one target position.
6. If the score increases by `1`, the candidate is correct for that position.

Easy is intentionally slower and more human-looking.

### Medium

Medium uses the grid stage first, then switches to positional search.

Flow:

1. Run the prebuilt grid plan.
2. Build candidate digits for every position from positive grid hits.
3. When a partially solved code still has several open positions, build one grouped probe for the unresolved tail.
4. Measure how many of those tail positions are correct after subtracting already known fixed positions.
5. Split that tail into smaller parts and isolate it before falling back to single-position checks.
6. Use safe digits outside the candidate set as fillers for split probes and cleanup moves.

Medium is the main practical bot for regular PvP.

### Hard

Hard uses the same grid stage as Medium, but continues with split isolation.

Flow:

1. Take a positive grid guess, for example `123456 -> 2`.
2. Replace half of the positions with safe filler symbols.
3. Measure how many of the known matches stay inside the tested half.
4. Split again until the exact positions are isolated.
5. Finish the remaining positions with positional search if needed.

Hard should look smarter, because it reuses earlier grid results instead of checking every candidate one by one.

### Expert

Expert starts from Hard logic and adds tail isolation.

Flow:

1. Keep already confirmed positions fixed and do not spend new checks on them again.
2. When a positive guess still has an unresolved tail, probe only that tail segment.
3. Split that tail into smaller grouped chunks such as `54` and `77` instead of rechecking the known prefix.
4. Turn each positive chunk into a new isolation task until only exact positions remain.
5. When the remaining tail becomes small enough, enumerate the exact valid tail variants and finish from that reduced set.

Expert should be the most efficient mode on long secrets, because it stops wasting moves on already confirmed prefix positions.

## Stage 1: Grid Search

Grid search is used only for:

- `Medium`
- `Hard`

It is skipped for:

- `Easy`

## Grid Plan

We use a catalog of prebuilt digit grids.

Current target:

- `40` reusable plans for digit alphabet

Each plan should look visually random, for example closer to `2643` than `1234`.

But the mathematical rule must stay strict:

- inside one row, digits do not repeat
- inside one position column, the same digit does not repeat until the full 10-step cycle is finished

Example for 4 positions:

- `2643`
- `7180`
- `3059`
- ...

Important property:

For a full 10-step cycle, each digit appears exactly once in each position.

This gives two useful effects:

- after 9 real grid moves, the 10th score is deduced automatically
- if the sum of found matches already equals code length, the remaining grid guesses are known to be zero

## Grid Output

Grid stage does not solve exact positions directly.

It produces:

- candidate digits for each position
- safe digits for each position
- positive match groups for later hard splitting

Example:

- `1234 -> 1`
- `5678 -> 1`
- `7890 -> 1`
- `8901 -> 1`

This means position-level candidates are narrowed, but not yet fully solved.

## Stage 2: Exact Value Search

### Safe Base

Bot needs symbols that are safe for fixed positions.

A symbol is safe for a position when we know it cannot be correct on that exact position.

Safe symbols are used as fillers while one target position is being tested.

### Positional Search

Used by:

- `Easy`
- `Medium`
- `Hard` as fallback cleanup

Logic:

1. Keep all solved positions fixed.
2. Put one candidate symbol on the target position.
3. Fill all other unsolved positions with safe symbols.
4. Compare score against the number of already solved positions.

### Split Isolation

Used by:

- `Hard`

Logic:

1. Start from a grid guess with known match count.
2. Test only part of that guess.
3. Deduce how many matches belong to the tested subset.
4. Recurse until only exact positions remain.

## Architecture Direction

Bot implementation should stay centered around one reusable entity:

- `BotAgent`

Expected lifecycle:

1. `prepareMatch()`
2. `nextGuess()`
3. `registerFeedback(guess, score)`
4. `snapshot()`

The UI client and backend service should only wrap this entity with their own timing, networking, and presentation rules.
