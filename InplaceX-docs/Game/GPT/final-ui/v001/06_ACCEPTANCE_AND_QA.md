# Acceptance and QA

## 1. Hard-fail criteria

Изменение не принимается, если выполняется хотя бы одно:

- фон комнаты заменён или закрыт общей непрозрачной поверхностью;
- попытки и матрица не видны одновременно;
- рабочая зона стала vertical stack/tabbed;
- строка попытки разбита на отдельные digit cards;
- на 8 цифрах есть horizontal scroll, clipping или наложение;
- production online length 9–10 перестал работать;
- реклама перекрывает input/tools;
- изменены game rules, state, scoring, online/API, purchases или ad policy;
- существующие test tags/semantics исчезли;
- RU/EN текст обрезан или переносится посимвольно;
- используется двойная тень на большинстве компонентов;
- интерфейс превратился в общий коричнево-бежевый слой.

## 2. Required viewport matrix

### Compact portrait

- 360×740dp, font 1.0, RU.
- 360×740dp, font 1.15, RU.

### Baseline portrait

- 360×800dp, font 1.0, RU/EN.
- 393×873dp, font 1.0, RU.

### Wide portrait

- 412×915dp, font 1.0, EN.

## 3. Gameplay cases

Для каждого обязательны empty и filled states:

- 4 digits;
- 6 digits;
- 8 digits;
- 10 digits compact fallback.

Дополнительно:

- latest attempt visible;
- NO/MAYBE/YES selected;
- manual marks + locked proven facts;
- hints enabled/disabled;
- boosts enabled;
- waiting opponent/disabled input;
- win/loss/result dialog;
- banner loaded;
- banner loading/no slot;
- premium/no ads.

## 4. Screen cases

- Home.
- PVE/PVP mode selection.
- Company main: selected, locked, completed, no energy.
- Social root.
- Invitations ready/loading/waiting/error.
- Online active field.
- Shop.
- Profile guest/authenticated.
- Settings + consent dialog.

## 5. Visual checks

- фон читается, панели читаются поверх фона;
- одна доминирующая primary action на экран;
- aligned baselines;
- одинаковые radii/outline/elevation уровни;
- selected/disabled/locked различимы;
- touch targets не пересекаются;
- статус и цифры читаются на реальном телефоне, не только на desktop preview.

## 6. Automated checks

Минимум после каждого вертикального среза:

```bash
python scripts/agent_control/github_freshness_guard.py --project-root . --base-ref origin/develop --fetch --json
bash gradlew :app:testDebugUnitTest
bash gradlew :app:assembleDebug
bash gradlew :app:assembleDebugAndroidTest
git diff --check
```

Перед интеграцией финального pass:

```bash
bash gradlew verifyProject
bash gradlew lint
bash gradlew :app:connectedDebugAndroidTest
bash gradlew assembleRelease
git diff --check
```

Если emulator/physical-device check недоступен, статус не `done`, а `visual_qa_blocked` или `needs_worker_fix` с точным blocker.

## 7. Evidence contract

Codex/Worker возвращает:

- base SHA;
- branch/commit/PR;
- список файлов;
- список выполненных checks и exact result;
- скриншоты по `08_VISUAL_CAPTURE_MANIFEST.json`;
- краткое описание visual deviations от пакета;
- `integration_requested` только после зелёных обязательных проверок.

## 8. Performance

- не добавлять blur layer на весь экран;
- не создавать bitmap panels;
- избегать тяжёлых animated infinite transitions;
- stable data classes/remember для adaptive metrics;
- LazyColumn только для attempts/существующих списков;
- recomposition timer не должна перестраивать тяжёлый background/asset tree.
