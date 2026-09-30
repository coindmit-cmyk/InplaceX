# Bot Brain

## Canonical Direction

Bot logic is a shared domain entity, not a UI helper.

It must be reusable from:

- local offline game runtime
- backend matchmaking or fallback player runtime

The canonical entry point is `BotAgent`.

## Core Contracts

### Main Entity

`BotAgent` must own:

- active rules
- difficulty
- behavior model
- grid plan state
- candidate sets per position
- safe symbols per position
- resolved positions
- pending probe context

Recommended lifecycle:

- `prepareMatch()`
- `nextGuess()`
- `registerFeedback(guess, score)`
- `snapshot()`

### Snapshot

Snapshot should expose at least:

- stage
- grid plan id
- actual guess history
- candidate symbols per position
- safe symbols per position
- resolved positions

## Rules

The current alphabet is digits only.

Rules are carried by `BotMatchRules`, derived from `GameConfig`.

Current flags:

- `allowDuplicates`
- `forbidAllSameDigitsGuess`
- `forbidAdjacentDuplicates`
- `forbidTripleDuplicates`

These flags apply to:

- secrets
- manual guesses
- bot guesses
- grid search guesses

## Feedback Contract

Bot receives only:

- exact position match count

Bot does not receive:

- misplaced symbol count
- direct position hints

## Difficulty Strategy

### Easy

- skip grid search
- search for a safe base guess with score `0`
- resolve positions one by one

### Medium

- run grid search
- build candidate symbols per position
- when several unresolved positions remain, build a grouped probe across that unresolved tail
- subtract already resolved positions from the returned score
- split the tail into smaller isolation tasks before falling back to one-position cleanup
- resolve leftovers with safe fillers

### Hard

- run the same grid search as Medium
- create isolation tasks from positive grid observations
- split those tasks recursively
- finish leftovers with positional search

### Expert

- inherit all Hard behavior
- keep already confirmed positions fixed out of future grouped probes
- when an unresolved contiguous tail remains, create a grouped probe only for that tail
- split that tail into smaller isolation chunks before falling back to one-position cleanup
- when the unresolved tail is small enough, enumerate history-consistent tail candidates and choose the next probe from that exact reduced set

## Grid Search

Grid search is based on prebuilt cyclic plans.

Current digit catalog target:

- `40` plans

Important property:

For a 10-step cycle, each symbol appears exactly once in each position.

This means:

- total score sum across the full cycle equals `codeLength`
- if 9 grid scores are known, the 10th is deduced
- if score sum already reached `codeLength`, all remaining grid scores are `0`

## Candidate Semantics

After grid search:

- any symbol that appeared on a positive-score grid guess for a position becomes a candidate for that position
- any symbol outside that set is safe filler for that position

This works because the true symbol for a position must appear inside a positive grid hit at least once.

## Hard Isolation Tasks

Hard mode builds tasks shaped like:

- source guess
- affected positions
- exact match count inside that position subset

Then:

1. split the subset in half
2. fill all non-tested positions with safe symbols
3. subtract already resolved positions from the returned score
4. continue until single positions are resolved

## Compatibility Layer

`BotSolver` is a compatibility facade over `BotAgent`.

Existing UI and tests may continue to call:

- `nextTurn()`
- `registerFeedback()`
- `confirmedPositionsCount()`
- `solveSecret()`

But new logic should be implemented in `BotAgent`, not in the facade.
