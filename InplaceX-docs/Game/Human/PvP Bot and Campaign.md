# PvP Bot And Campaign

## Scope

This document fixes the current direction for two large systems:

- PvP as `human vs bot` now, with `human vs remote` later
- Campaign as a race mode with long-term progression

`local human vs local human` is not part of the current target product.

## PvP Versus Bot

### Match Shape

- The player chooses a duel mode against a bot.
- Before the match starts, the player must set a secret in a popup.
- The match does not begin until both sides have a secret.
- In developer mode the bot waits 5 seconds after the player confirms the secret.
- Secret selection is limited to 60 seconds. If time runs out, the match is cancelled.

### Bot Difficulty Model

Bot balance is defined by target moves:

`targetMoves = ceil(cn * n)`

Where:

- `cn` is the number of digits in the secret
- `n` is the difficulty multiplier

Current targets:

- `Easy`: around `4.5`
- `Medium`: around `3.5`
- `Hard`: around `2.5`
- `Expert`: around `2.0`, but with a small safety margin so the player still has a chance

### Bot Personality

- `Easy` is close to random and wastes information often.
- `Medium` keeps simple exclusions and uses obvious deductions.
- `Hard` reduces the candidate space well, but is still not a perfect solver.
- `Expert` uses the strongest search profile in the game, but with a small handicap.

## Campaign

### Match Shape

Campaign is a race mode. It is not a duel.

The campaign generator can change:

- secret length from `4` to `10`
- attempt limit
- time limit

Attempt limits use the same general formula:

`attemptLimit = ceil(cn * n)`

The generator lowers `n` over time as levels get harder.

### Block Structure

Campaign levels are grouped by 10:

- `1, 2, 3, 4`: standard
- `5`: spike
- `6, 7, 8, 9`: standard
- `10`: hardcore

Difficulty keeps rising until about levels `300-500`, then reaches a plateau of maximum difficulty.

The first 10 levels are the onboarding block with 4-digit codes. Starting at
level 11, the campaign moves to medium difficulty and 5-digit codes. Code
length then grows in controlled bands until it reaches 10 digits at level 301.
This makes the second campaign block noticeably harder without removing the
10-level pattern of standard, spike, and hardcore missions.

### Hints And Boosts

Campaign allows gameplay assistance systems, but the player may need to own them first.

Helpers are split into two groups.

Hints:

- `Open position`
- `Check position`
- `Check digit`

Boosts:

- `Add moves`
- `Add time`

Boost values by level tier:

- `Easy`: `+3 moves`, `+2 minutes`
- `Medium`: `+2 moves`, `+1 minute`
- `Hard`: `+2 moves`, `+1 minute`
- `Hardcore`: `+1 move`, `+30 seconds`

Hints and auto mode are allowed in campaign, but they are still inventory-driven and can be earned, purchased, or granted from ads.

## Rating And Stars

Campaign stores two progress values:

- highest unlocked level
- per-level performance rating

Backend rating uses a `1..10` score.

Player-facing UI shows up to `3` stars.

The rating depends on:

- completion time
- attempts used
- amount of help used

Perfect play should remain possible even on hard content.

Help penalties by tier:

- `Easy`: no rating penalty
- `Medium`: soft penalty
- `Hard`: 3 stars should remain possible with about `3-6` helps
- `Hardcore`: 3 stars should remain possible with about `1-3` helps

The design goal is to encourage mastery without making the ideal rating impossible.
