# Component Contracts

## 1. `WarmPanel`

Назначение: базовая кремовая поверхность для gameplay и form groups.

- gradient top/bottom;
- radius 14dp default;
- border 1dp warm;
- elevation 2dp;
- content padding передаётся явно;
- не добавляет ещё одну вложенную `Surface` без необходимости.

## 2. `PolishedActionTile`

Назначение: карточки главной и крупные переходы.

- min height 94dp;
- leading icon area 52dp;
- title 20sp bold;
- subtitle 14sp;
- trailing arrow 42dp;
- radius 20dp;
- one border, one shadow;
- press feedback scale 0.98.

## 3. `CompactAttemptRow`

API:

```kotlin
CompactAttemptRow(
    guess: String,
    score: Int,
    latest: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
)
```

Contract:

- рисует одну строку `guess → score`;
- guess — monospace;
- не создаёт отдельные digit boxes;
- latest не меняет layout size;
- сохраняет `game-attempt-N` test tag на вызывающей стороне.

## 4. `WarmAnalysisCell`

API:

```kotlin
WarmAnalysisCell(
    digit: Char,
    state: AnalysisCellVisualState,
    enabled: Boolean,
    contentDescription: String,
    preserveSquare: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
)
```

States:

- EMPTY
- NO
- MAYBE
- EXACT
- LOCKED_NO
- LOCKED_EXACT
- DISABLED

Контракт: digit всегда виден; layout size не меняется между states; selected/locked различаются border weight и font weight. В gameplay matrix используется `preserveSquare=false`: ширина заполняет доступную колонку, а фиксированная по длине кода высота не позволяет рабочему полю растягиваться по viewport.

## 5. `GameInfoStrip`

- одна warm panel;
- mode занимает гибкую left zone;
- metrics имеют label + value;
- metrics разделяются dividers;
- не использовать nested chip surfaces.

## 6. `CompactHelperCounter`

- 40–44dp;
- image asset 18dp;
- count/infinity;
- selected outline;
- no long label in portrait gameplay.

## 7. `ToolSegment`

- единый parent container;
- четыре равных segment;
- active fill + 2dp accent border;
- state semantics `selected`.

## 8. `InputSlot`

- равный weight;
- adaptive height/gap;
- open-position hint selected state через primary outline;
- proven exact digit остаётся заполненным и визуально locked.

## 9. `WarmPrimaryButton`

Перед action buttons gameplay используется `GameKeypadButton`: compact warm fill, 1dp warm border, одинаковая геометрия цифр/backspace и adaptive visual height внутри сенсорной высоты не менее 44dp. Он не использует `FilledTonalButton` и сохраняет текущие callbacks/test tags.

- min height 44dp;
- blue gradient;
- white semibold text;
- radius 12dp;
- disabled label видим.

## 10. `WarmSecondaryButton`

- min height 44dp;
- warm fill;
- warm border;
- text primary/blue;
- используется для reset, cancel, rules, copy/share.

## 11. `ChromeActionButton` / `ChromeStatPill`

- deep navy gradient;
- radius 14dp;
- one 1dp blue border;
- no duplicate shadow;
- icon tint white, resource accents сохраняются.

Общие `SceneCard` и `SceneActionTile` следуют тем же правилам: одна тень, один контур, radius 16/20dp и без вложенных конкурирующих surfaces.

## 12. `AdSlotFrame`

- управляет только layout reservation и clipping;
- не перекрашивает production ad content;
- debug placeholder может быть стилизован отдельно.

## 13. Test tags

Существующие tags должны быть сохранены:

- `game-status`
- `game-attempt-N`
- `game-analysis-D-P`
- `game-guess-slot-P`
- `game-guess-value-P`
- `game-digit-D`
- `game-banner-slot`
- company tags и shell tags.

Новые wrappers не должны скрывать semantics/test nodes.
