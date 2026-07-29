# Bot And Campaign Systems

## Canonical Direction

The current canonical mode set is:

- `pve_race`
- `pvp_bot_duel`
- `company_race`

The project does not currently target `local human vs local human`.

## PvP Bot Contracts

### Bot Difficulty

`BotDifficulty` is mapped to a `BotDifficultyProfile`.

The profile must contain at least:

- target move multiplier
- minimum move floor
- reaction delay
- random guess share
- pruning policy
- best split policy
- expert handicap or bonus move budget

Target move rule:

`targetMoves = ceil(codeLength * targetMovesMultiplier)`

This keeps balance stable when the secret length changes.

### Pre-Match State

PvP bot duel requires a pre-match phase before `ACTIVE_MATCH`.

Canonical phases:

- `SECRET_SELECTION`
- `WAITING_OPPONENT_SECRET`
- `READY_TO_START`
- `CANCELLED_TIMEOUT`

The player secret is mandatory. The bot secret can be generated after a configured delay in developer mode.

## Campaign Generator

Campaign is race-only and does not use duel logic.

`CampaignLevelGenerator.generate(levelNumber)` must output a `CampaignLevelDefinition` that includes:

- level number
- tier
- role inside the 10-level block
- code length
- attempt limit
- total time limit
- boost pack
- rating policy

### Generator Inputs

The generator uses:

- global level progression
- block role in the current 10-level segment
- tier-specific tuning

### Block Roles

- `STANDARD`
- `SPIKE`
- `HARDCORE`

### Difficulty Plateau

Difficulty should keep growing until about `300-500`, then flatten into a stable maximum band.

### Onboarding Balance

The first block keeps four-digit codes, but it must not use the general easy
multiplier unchanged. Levels `1..10` use a dedicated descending move budget:

- level `1`: `15` attempts and `360` seconds
- level `8`: `14` attempts and `300` seconds
- level `10`: `12` attempts and `270` seconds

The exact first-block attempt sequence is
`15, 15, 15, 15, 14, 14, 14, 14, 13, 12`.
The matching time sequence is
`360, 345, 330, 330, 315, 315, 300, 300, 285, 270` seconds.
Spike and hardcore roles remain visible inside this curve.

From level `11`, the medium multiplier starts at `2.8` before role and
long-run adjustments. This prevents the five-digit block from returning to a
nineteen-attempt tutorial budget.

The generated campaign uses these deterministic tier boundaries:

- `1..10`: `EASY`
- `11..80`: `MEDIUM`
- `81..220`: `HARD`
- `221+`: `HARDCORE`

Secret length grows independently so that early play does not stay flat:

- `1..10`: 4 digits
- `11..40`: 5 digits
- `41..90`: 6 digits
- `91..150`: 7 digits
- `151..220`: 8 digits
- `221..300`: 9 digits
- `301+`: 10 digits

This keeps the first 10-level block suitable for onboarding, makes the second
block a real deduction step, and reaches the maximum code length inside the
canonical `300-500` plateau window.

## Rating Policy

Campaign rating uses:

- backend points in `1..10`
- frontend stars in `0..3`

`CampaignRatingPolicy` should stay data-driven.

Inputs that affect the score:

- finish time against the target time
- attempts used against the target attempt budget
- help usage against the allowed assist budget

Assist expectations by tier:

- `Easy`: unlimited help for perfect rating
- `Medium`: soft penalty
- `Hard`: perfect rating still reachable with about `3-6` assists
- `Hardcore`: perfect rating still reachable with about `1-3` assists

## Inventory Model

The inventory model must treat analytical hints and match boosts as separate items.

Analytical hints:

- `OPEN_POSITION`
- `CHECK_POSITION`
- `CHECK_DIGIT`

Match boosts:

- `EXTRA_MOVES`
- `EXTRA_TIME`

This separation is required for balancing, monetization, and campaign progression.
