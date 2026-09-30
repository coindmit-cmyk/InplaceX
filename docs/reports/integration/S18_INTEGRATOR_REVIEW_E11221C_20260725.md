# S18 integrator review — `e11221c`

## Решение

`NEEDS_DISPATCHER_REPAIR`. Commit содержит только blocker report; production и
test code не изменялись.

## Подтверждённый blocker

Собранный release APK содержит:

- `DeveloperRootScreen`, `BotLabScreen`;
- `SettingsRootScreen`, `DebugSecretAdSlot`;
- `GameFieldDebugScreen`, `GameDebugAdSlot`, `GameDebugAdSlotContent`;
- `game.debug.secret`, `developer.action.add_coins`.

`MainActivity` включает developer mode и передаёт secret в debug slot,
`SettingsRootScreen` показывает developer control, а game presentation держит
ещё один debug ad slot. Эти точки находятся вне исходного `allowed_paths`.

Baseline checks `assembleDebug`, `assembleRelease`, `testDebugUnitTest` и
`git diff --check` прошли, но acceptance criteria не выполнены.

## Retry contract

- Сохранить все owner debug tools в debug variant.
- Вынести developer/devbot/debug screens и secret-rendering code из release
  variant физически, а не только скрыть runtime-флагом.
- Release не должен содержать debug fixtures, debug localization keys, secret
  rendering или developer navigation.
- Добавить отдельные debug/release variant tests и обязательный DEX scan
  собранного release APK на запрещённые symbols/keys.

