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

Перед самым первым матчем кампании игра показывает короткое обучение из трёх
экранов: цель и ввод числа, чтение результата хода, подсказки и усилители.
Таймер и матч запускаются только после кнопки «Начать игру». После завершения
обучение сохраняется локально и больше не прерывает следующие матчи; выход по
кнопке «Вернуться» оставляет обучение незавершённым и показывает его при
следующем входе на первый уровень.

The campaign generator can change:

- secret length from `4` to `10`
- attempt limit
- time limit

Attempt limits use a fixed deterministic expert-solver benchmark plus an
explicit safety reserve:

`attemptLimit = expertSolverTarget(codeLength) + reserve(tier, role)`

The measured reference is `13, 14, 15, 17, 19, 21, 24` attempts for secret
lengths `4..10`. Easy standard levels receive `+7` moves, while the hardest
hardcore checkpoints receive `+1`. This keeps onboarding forgiving and makes
later budgets track the repository solver without coupling campaign balance to
the opponent's personality or animation delay.

### Block Structure

Campaign levels are grouped by 10:

- `1, 2, 3, 4`: standard
- `5`: spike
- `6, 7, 8, 9`: standard
- `10`: hardcore

Экран показывает только десять уровней выбранной главы. Между уже доступными
главами и ближайшей закрытой главой игрок переключается отдельными кнопками,
поэтому уровни соседней главы не выглядят частью текущей.

Difficulty keeps rising until about levels `300-500`, then reaches a plateau of maximum difficulty.

The first 10 levels are the onboarding block with 4-digit codes. Starting at
level 11, the campaign moves to medium difficulty and 5-digit codes. Code
length then grows in controlled bands until it reaches 10 digits at level 301.
This makes the second campaign block noticeably harder without removing the
10-level pattern of standard, spike, and hardcore missions.

Внутри первого блока обычные уровни имеют `20` попыток, пятый уровень — `18`,
а контрольный десятый — `16`. Лимит времени
снижается с `6:00` до `5:00` на восьмом и `4:30` на десятом. Четыре цифры
сохраняются как понятный обучающий формат с большим запасом, но десятый уровень
остаётся заметно сложнее.

На уровне 17 пятизначный код получает `18` попыток и `4:30`.

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

Следующая глава открывается только после прохождения всех уровней перед ней и
набора в среднем не менее двух звёзд за уровень:

`requiredStars = completedChapterLevels × requiredStarsPerLevel`

Текущее значение `requiredStarsPerLevel = 2.0` хранится в конфигурации правил
прогрессии. Для второй главы это означает все `10` уровней первой главы и
минимум `20` звёзд; для третьей — `20` завершённых уровней и `40` звёзд.

The rating depends on:

- completion time
- attempts used
- amount of help used

The attempt score is spread across the whole reserve above the expert target.
Solving at the target keeps the maximum score, using about half of the reserve
falls to two stars, and solving on the final allowed attempt gives one star.

Perfect play should remain possible even on hard content.

## Chapter Rewards

После прохождения всех десяти уровней главы игрок может один раз забрать
награду главы: `50` монет и по одной подсказке каждого аналитического типа.
Получение сохраняется локально и не повторяется после перезапуска приложения.

Завершение уровня атомарно сохраняет лучший рейтинг, открытие следующего уровня,
монеты и статистику. Повторные победы учитываются в статистике, но монеты за
уровень выдаются только за прирост личного лучшего рейтинга: первое прохождение
даёт число монет, равное рейтингу, улучшение — только разницу, а результат не
выше рекорда не выдаёт монет. Это сохраняет replay и исключает бесконечный
farming на уже освоенном уровне.

Help penalties by tier:

- `Easy`: no rating penalty
- `Medium`: soft penalty
- `Hard`: 3 stars should remain possible with about `3-6` helps
- `Hardcore`: 3 stars should remain possible with about `1-3` helps

The design goal is to encourage mastery without making the ideal rating impossible.
