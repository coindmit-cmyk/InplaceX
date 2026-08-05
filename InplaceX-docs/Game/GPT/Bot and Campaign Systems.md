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

### First-Match Tutorial

Level `1` is gated by a three-step tutorial until the local progress flag
`campaignTutorialCompleted` is set. The steps explain the goal and input,
turn feedback, and inventory-backed hints and boosts. `GameFieldScreen` must
not be composed before completion, so its timer and match-start callback cannot
run behind the tutorial. Completing the final step persists the flag before
the level is shown. Exiting the dialog returns to the campaign map without
marking the tutorial complete.

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

### Chapter Progression

`CampaignProgressionRules` is the single source for chapter size and unlock
thresholds. The default configuration is:

- `levelsPerChapter = 10`
- `requiredStarsPerLevel = 2.0`

Chapter `n` becomes available only when every level in chapters `1..n-1` is
complete and the cumulative star count reaches:

`ceil((n - 1) * levelsPerChapter * requiredStarsPerLevel)`

The campaign UI renders exactly one chapter range at a time and may expose the
nearest locked chapter for progress preview. Chapter rewards, level generation,
unlock calculations and UI grouping must use the same configured chapter size.

### Difficulty Plateau

Difficulty should keep growing until about `300-500`, then flatten into a stable maximum band.

### Onboarding Balance

The first block keeps four-digit codes. Its budget is the measured deterministic
expert solver reference (`13` attempts for four digits) plus an explicit role
reserve:

- standard level: `+7`, giving `20` attempts
- spike level: `+5`, giving `18` attempts
- hardcore checkpoint: `+3`, giving `16` attempts

The exact first-block attempt sequence is
`20, 20, 20, 20, 18, 20, 20, 20, 20, 16`.
The matching time sequence is
`360, 345, 330, 330, 315, 315, 300, 300, 285, 270` seconds.
This keeps a forgiving onboarding reserve while making level `10` a visible
checkpoint.

All later levels use the same authoritative formula:

`attemptLimit = expertSolverTarget(codeLength) + reserve(tier, role)`

The reference is versioned in `CampaignSolverBudget`. It is derived from a
fixed-seed 200-match benchmark and rounded to a playable deterministic budget:

| Code length | Reference attempts |
|---:|---:|
| 4 | 13 |
| 5 | 14 |
| 6 | 15 |
| 7 | 17 |
| 8 | 19 |
| 9 | 21 |
| 10 | 24 |

Reserve values are:

| Tier | Standard | Spike | Hardcore |
|---|---:|---:|---:|
| Easy | `+7` | `+5` | `+3` |
| Medium | `+4` | `+3` | `+2` |
| Hard | `+3` | `+2` | `+1` |
| Hardcore | `+2` | `+1` | `+1` |

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

The attempt penalty is normalized across the complete reserve. Reaching the
expert target keeps the attempt component perfect; consuming half the reserve
falls into the two-star band; winning on the final allowed attempt always
reduces the match to the one-star band before time and assist penalties.

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

## Retention Rewards

`RetentionRewardPolicy` is the single source for daily and weekly reward
payloads and calendar period keys. Daily keys use the player's local date;
weekly keys use the ISO week-based year and week number.

Claims are stored in `retention_reward_claims` with a composite primary key of
reward type and period key. Claim insertion and inventory mutation must share
one SQLite transaction. A conflict means the period was already claimed and
must not mutate inventory. The MVP is local-first and therefore trusts the
device clock; a server-authoritative clock and claim ledger are required before
these rewards participate in competitive or paid economies.
