# CODEX FINAL UI PACKET

Package: `INPLACEX-FINAL-UI-20260812-v001`
Base: `develop@5f37f4e138f96cdf70c489237b28137a351b3892`

---

# Решения владельца

Статус: **утверждено для реализации**. Дополнительного творческого выбора от Codex не требуется.

1. Сохраняется яркий фон комнаты `R.drawable.toy_room_bg_v6`. Его нельзя заменять синим, тёмным или однотонным фоном, нельзя закрывать непрозрачной общей подложкой и нельзя сильно затемнять.
2. Выбранное направление — «концепт 2»: тёплая комната, кремовые игровые панели, спокойный premium-casual, яркие режимные акценты.
3. Mood reference не является pixel-perfect макетом. Текущая структура приложения важнее любой сгенерированной картинки.
4. Верхний HUD и нижняя навигация остаются на своих местах и сохраняют текущую функциональность.
5. На главной остаются ровно три действия: «Гонка», «Дуэль», «Продолжить компанию»/актуальная локализованная формулировка.
6. Игровое поле использует утверждённый breakpoint: для 4–6 цифр попытки остаются слева, кодовая матрица справа; для 7–10 попытки располагаются сверху, матрица снизу. Скрывать их во вкладках или переключать нельзя.
7. Попытка отображается компактной строкой: `1234 → 2`, `12345678 → 3`. Каждая цифра попытки не получает отдельную карточку.
8. Число после стрелки — существующий score точных совпадений. Интерфейс не сообщает конкретные совпавшие позиции.
9. В матрице цифра рисуется непосредственно в каждой ячейке, как сейчас. Отдельные подписи цифр вне сетки не нужны.
10. История попыток и матрица всегда видимы одновременно во время активной игры.
11. Основной целевой диапазон — 4–8 цифр. Существующий online-контракт 9–10 цифр нельзя ломать: для него предусмотрен сверхкомпактный режим.
12. Таблица является ядром gameplay. Её нельзя упрощать, удалять или заменять декоративными карточками.
13. Реклама остаётся в отдельном нижнем слоте и не должна перекрывать управление. В premium/no-ad состоянии игровая зона корректно использует освободившееся место.
14. Визуально меняются прежде всего блоки над фоном: поверхности, рамки, тени, плотность, состояния и типографика.
15. Нельзя превращать приложение в тяжёлый коричнево-бежевый интерфейс. Дерево остаётся фоном, а не цветом всего UI.
16. Нельзя менять игровую механику, состояние, навигационные сценарии, рекламу, покупки, авторизацию, локальную БД или online API в рамках UI-pass.
17. Нельзя добавлять bitmap-кнопки и bitmap-панели. Compose-компоненты должны оставаться адаптивными.
18. Сначала принимается gameplay vertical slice на 4/8 цифр, затем тот же язык переносится на остальные экраны.

---

# UX Contract

## 1. Цель

Сделать текущий InplaceX визуально цельным и готовым к релизу, не меняя продуктовую модель. Пользователь должен воспринимать интерфейс как яркую настольную логическую игру в тёплой комнате, а не как набор Material-форм.

## 2. Пользовательская модель

Игрок:

- быстро выбирает режим на главной;
- во время матча постоянно сопоставляет историю попыток с матрицей;
- отмечает гипотезы `Нет / Возможно / Точно`;
- вводит следующую комбинацию, не теряя контекст;
- при 4–8 цифрах не должен держать прошлые варианты в памяти;
- понимает активное, выбранное, заблокированное и автоматически доказанное состояние без чтения инструкции.

## 3. Непрерывный игровой цикл

`Статус матча → попытки + матрица → подсказки/бусты → инструмент отметки → ввод → подтверждение`.

Все элементы цикла остаются на одном портретном экране. Вертикальный скролл активного gameplay запрещён. Допускается только внутренний вертикальный скролл списка попыток.

## 4. Gameplay layout

### 4.1 Постоянные зоны

1. Верхний shell HUD.
2. Компактная статусная панель матча.
3. Главная рабочая зона: попытки и матрица располагаются адаптивно по длине кода.
4. Панель подсказок/бустов, если разрешены режимом.
5. Сегмент инструментов `Нет / Возм / Точн / PRO`.
6. Панель комбинации, цифровая клавиатура, сброс и подтверждение.
7. Нижний ad-slot только при фактически загруженной рекламе.

### 4.2 Адаптивные пропорции рабочей зоны

| Длина кода | Компоновка | Попытки | Матрица |
|---:|---|---:|---:|
| 4 | слева / справа | 40% ширины | 60% ширины |
| 5–6 | слева / справа | 37% ширины | 63% ширины |
| 7–10 | сверху / снизу | 120–150dp, последние 3 строки | остаток ограниченной рабочей зоны |

Breakpoint обязателен: `codeLength > 6`. Для 7–10 цифр попытки находятся сверху, матрица снизу и получает всю ширину рабочей зоны. Сама рабочая зона не растягивается на свободный viewport: её высота рассчитывается из header, десяти строк матрицы, межстрочных gaps и, в stacked-режиме, трёх строк попыток.

### 4.3 История попыток

- Формат строки: `guess → score`.
- Guess рисуется моноширинно одной строкой.
- Номер попытки не показывается, если без него понятен порядок списка.
- Последняя попытка имеет мягкую подсветку и автоматически прокручивается в видимую область.
- Пустое состояние компактное; оно не должно визуально доминировать над матрицей.
- В вертикальном режиме видны последние 3–4 строки, список прокручивается, а пустое состояние выравнивается сверху.
- Score не раскладывается по позициям и не превращается в цветные маркеры.

### 4.4 Матрица

- 10 строк: цифры 0–9.
- Количество колонок равно `codeLength`.
- Ширина и высота ячейки считаются независимо во всех режимах: ширина использует панель, высота ограничена токеном 30dp (4), 28dp (5–6), 25dp (7–8), 22dp (9–10).
- Матрица заполняет остаток только внутри рассчитанной рабочей зоны и не может вытеснять tools/input/ad-slot за нижнюю границу viewport.
- Нижняя граница layout учитывает системную navigation bar: загруженный ad-slot полностью остаётся выше неё.
- Каждая ячейка содержит свою цифру.
- Компактный header `Кодовая матрица` обязателен и использует ту же геометрию, что header попыток.
- Состояние кодируется фоном, цветом рамки и насыщенностью/весом цифры, а не только цветом.
- Автоматически доказанные состояния визуально сильнее ручных.
- Нажатие, haptic и существующая логика редактирования сохраняются.

### 4.5 Инструменты

- `Нет`: красный акцент.
- `Возм`: янтарный акцент.
- `Точн`: зелёный акцент.
- `PRO`: нейтрально-голубой; сохраняет текущую логику auto/manual.
- Активный сегмент имеет более насыщенную заливку и 2dp контур; неактивный остаётся читаемым.

### 4.6 Ввод

- Слоты растягиваются равномерно и адаптируются к 4–10 позициям.
- Цифровая клавиатура остаётся одной строкой `1…0 + backspace`.
- `Подтвердить` — единственная визуально главная кнопка gameplay.
- `Сброс` — вторичная.
- Disabled-состояние различимо, но текст не исчезает.

## 5. Shell

### Верхний HUD

- Сохраняет back, energy, coins, shop и settings.
- Высота интерактивных действий — не менее 44dp.
- Используется спокойный тёмно-синий chrome, чтобы HUD не спорил с цветными режимами.
- Убираются двойные тени и чрезмерно яркие cyan-обводки.

### Нижнее меню

- Пять существующих разделов сохраняются.
- Выбранный пункт имеет светлый blue-highlight, а не отдельную массивную карточку.
- Иконка и подпись всегда видимы; подпись одна строка.

## 6. Главная

- Фон комнаты остаётся визуально заметным.
- Logo получает только мягкую тень/контур для читаемости.
- Три mode tiles имеют одинаковую анатомию и разные акценты: orange, purple, green.
- Главная не получает общую непрозрачную панель.
- Карточки не должны занимать больше места, чем требуется тексту и touch target.

## 7. Компания

- Сохраняются chapter navigation, reward, history, mission timeline/cards и нижний action bar.
- Снижается количество конкурирующих рамок и теней.
- Выбранный уровень должен быть очевиден по одной сильной рамке/подсветке.
- Locked, available, completed и selected — четыре различимых состояния.
- Нельзя менять progression rules или доступность уровней.

## 8. Друзья и приглашения

- Сохраняются все текущие сценарии и состояния online runtime.
- Стандартные Material form controls приводятся к общей warm-surface системе.
- Заголовок, настройки матча, создание/ввод кода и primary action визуально разделяются.
- Ошибка, loading, offline, waiting и ready имеют отдельные, но компактные состояния.
- Не добавлять новые шаги в flow.

## 9. Магазин, профиль, настройки

- Переносятся те же токены и primitives.
- Не перестраивать IA и commerce/auth flows.
- Один экран — один явный primary action; остальные действия вторичные.

## 10. Accessibility и semantics

- Все обычные кнопки и пункты навигации: минимум 44dp.
- Матрица — осознанное исключение плотного игрового поля; целевой размер 24–32dp для 4–8 и минимум 20dp для 9–10.
- Каждая ячейка получает state description: `цифра`, `позиция`, `состояние`.
- Не полагаться только на цвет: состояние дополнительно различается рамкой и font weight.
- Контраст основного текста к warm panel не ниже 4.5:1.
- Проверка обязательна на RU/EN и font scale 1.0/1.15.

## 11. Motion

- Press scale: 0.98, 100–140ms.
- Selection/fill transition: 120–180ms.
- Screen/modal transition: использовать существующую навигацию; не добавлять длинные cinematic-анимации.
- Никаких бесконечных glow/pulse на рабочем поле.

## 12. Поведенческий freeze

UI-pass не меняет:

- scoring и значение `MatchAttempt.score`;
- validation, deduction, proven facts и manual marks;
- timers, hints, boosts, purchases и ad policy;
- SavedState/recreation;
- online snapshots и server authority;
- navigation destinations;
- локализацию как источник бизнес-логики.

---

# Visual System — Warm Room / Polished Casual

## 1. Принцип

Яркий иллюстрированный фон создаёт атмосферу. UI-поверхности создают читаемость. Они не должны превращаться в вторую картинку поверх фона.

## 2. Цвета

Точные значения находятся также в `code/FinalUiTokens.kt`.

| Token | HEX | Назначение |
|---|---|---|
| `WarmPanelTop` | `#FFF9EC` | верх кремовой панели |
| `WarmPanelBottom` | `#F6E5C7` | низ кремовой панели |
| `WarmPanelSolid` | `#FFF4DE` | fallback/простые поверхности |
| `WarmBorder` | `#D8B879` | основной тёплый контур |
| `WarmDivider` | `#B9955F` @ 42% | разделители |
| `WarmText` | `#3B2918` | основной текст |
| `WarmTextMuted` | `#725A3C` | вторичный текст |
| `ChromeTop` | `#365678` | верх chrome gradient |
| `Chrome` | `#223C5A` | HUD/nav surface |
| `ChromeDeep` | `#11263F` | низ chrome gradient |
| `ChromeBorder` | `#72B7EA` @ 62% | тонкий синий контур |
| `PrimaryTop` | `#2C82D8` | primary button top |
| `Primary` | `#1769B5` | primary button |
| `PrimaryDeep` | `#0D4E91` | primary button bottom |
| `ModeOrangeTop` | `#F8CA6A` | Гонка |
| `ModeOrange` | `#EBA62E` | Гонка |
| `ModePurpleTop` | `#9B73DC` | Дуэль |
| `ModePurple` | `#704BB8` | Дуэль |
| `ModeGreenTop` | `#97C751` | Компания |
| `ModeGreen` | `#62962E` | Компания |
| `StateNo` | `#E97872` | Нет |
| `StateMaybe` | `#E6B83E` | Возможно |
| `StateExact` | `#79B95D` | Точно |
| `StatePro` | `#AEBEC9` | PRO/auto |
| `LockedNo` | `#C95D5D` | доказано невозможно |
| `LockedExact` | `#4C9A45` | доказано точно |

Запрещены глобальный синий фон, общий коричневый tint и непрозрачная бежевая подложка на весь экран.

## 3. Градиенты

- Warm panel: `WarmPanelTop → WarmPanelBottom`, вертикально, разница мягкая.
- Chrome: `ChromeTop → Chrome → ChromeDeep`.
- Primary: `PrimaryTop → Primary → PrimaryDeep`.
- Mode tiles: соответствующая пара `Top → base`.
- Не использовать более трёх stop и не добавлять glow к каждой поверхности.

## 4. Радиусы

| Элемент | Radius |
|---|---:|
| shell chrome outer | 20dp |
| общая panel | 18dp |
| mode tile | 20dp |
| inner group/input panel | 14dp |
| button | 12dp |
| attempt row | 8dp |
| matrix cell | 5–6dp |
| selected bottom nav item | 14dp |

Не использовать 22–28dp повсеместно. Большой radius только у крупных mode tiles.

## 5. Контуры

- Обычная panel: 1dp `WarmBorder`.
- Выбранный элемент: 2dp соответствующего accent.
- Chrome: 1dp `ChromeBorder`.
- Matrix locked: 2dp `LockedNo/LockedExact`.
- Не сочетать 2–3dp border с сильной внешней тенью на каждом элементе.

## 6. Тени

Использовать один механизм тени на компонент, без одновременного `Modifier.shadow` и `shadowElevation`.

| Уровень | Elevation |
|---|---:|
| panel | 3dp |
| mode tile | 4dp |
| chrome | 4dp |
| selected/floating | 5dp max |

Цвет тени стандартный, alpha невысокая. Blur/soft-light поверх всего экрана запрещён.

## 7. Сетка и отступы

Базовая сетка — 4dp.

| Token | Value |
|---|---:|
| screen padding | 4dp |
| section gap | 4dp |
| panel padding | 8dp |
| compact panel padding | 6dp |
| inner gap | 4dp |
| major content gap | 8dp |
| mode tile horizontal padding | 16dp |
| mode tile vertical padding | 14dp |

## 8. Типографика

Новый font asset не добавляется. Используется системный `FontFamily.Default`; для guess и числовых рядов — `FontFamily.Monospace`.

| Роль | Size / line | Weight |
|---|---|---|
| Logo | 34sp / 38sp | Black |
| Screen title | 24sp / 29sp | Bold |
| Mode title | 20sp / 24sp | Bold |
| Panel title | 15sp / 18sp | SemiBold |
| Body | 14sp / 19sp | Normal/Medium |
| Meta/status | 12sp / 15sp | Normal |
| Button | 14sp / 17sp | SemiBold |
| Attempt guess | 14→11.5sp | Medium, Mono |
| Matrix digit | 12→9.5sp | Medium/Bold |
| Bottom nav | 10sp / 11sp | SemiBold |

Текст не должен переноситься посимвольно. Для плотных зон использовать `maxLines = 1`, адаптивный размер и корректные веса, а не случайное уменьшение всей типографики.

## 9. Иконки

- На релизном pass используются существующие Material icons и текущие PNG hint/boost assets.
- Стиль: округлённая line/filled комбинация без смешивания 3D-рендеров с Material внутри одного ряда.
- Уникальные иллюстративные иконки можно добавить позднее отдельным asset pass, но это не блокирует UI-finalization.

## 10. Состояния

### Matrix

| State | Fill | Border | Digit |
|---|---|---|---|
| empty | transparent/cream 40% | warm 55% 1dp | warm text |
| no | StateNo 18% | StateNo 1dp | muted + medium |
| maybe | StateMaybe 26% | StateMaybe 1dp | warm text |
| exact | StateExact 30% | StateExact 2dp | bold |
| locked no | LockedNo 28% | LockedNo 2dp | LockedNo/bold |
| locked exact | LockedExact 55% | LockedExact 2dp | white/bold |
| disabled | neutral 22% | neutral 1dp | 55% alpha |

### Buttons

- Primary: blue gradient, white text.
- Secondary: warm panel, primary text, warm border.
- Destructive/reset не делается красным: сброс — neutral secondary.
- Disabled: сохранён label, fill desaturated, alpha компонента не ниже 0.55.

---

# Screen Specifications

## 1. AppShell

### Keep

- `ScreenBackgroundStyle.DrawableResource(R.drawable.toy_room_bg_v6, ...)`.
- `TopLayerMode.OVERLAY`.
- `CenterLayerMode.TRANSPARENT`.
- текущую модель bottom modes `MENU / AD / AD_LOADING / NONE`.

### Change

- Только визуальные токены shell, размеры и двойные тени.
- Center content не получает общую surface.
- Safe area и существующий расчёт слотов сохраняются.

## 2. Top HUD

- Back/settings/shop: 46–48dp square, radius 14dp.
- Stat pill: min height 42dp; icon 20dp; value 14–16sp; add 20dp.
- Gap: 4dp compact, 6dp normal.
- Deep navy chrome; cyan border alpha 0.62.
- Не рисовать отдельный текст «Магазин» в compact.

## 3. Bottom navigation

- Outer radius top corners 20dp.
- Selected item radius 14dp, one 1dp blue outline.
- Icon box 28–32dp; label 10sp.
- Vertical padding 5dp.
- Notification badge сохраняется.

## 4. Home

- `HomeSelectionScreen` сохраняет текущую IA.
- Logo + subtitle занимают не более 22% полезной высоты.
- Три action tiles, vertical gap 10–12dp.
- Tile min height: 94dp compact, 104dp normal.
- Leading icon area: 52dp; trailing arrow: 42dp.
- Orange/Purple/Green gradients из token set.
- Белый текст только если contrast проходит; для orange допускается `WarmText`.
- Фон должен оставаться виден вокруг и между карточками.

## 5. Gameplay

### Status panel

- Высота 56–68dp в зависимости от status lines.
- Первая строка: mode + moves + total + turn.
- Внутренние metrics разделяются тонкими vertical dividers, а не четырьмя вложенными карточками.
- Status text — одна строка, 12sp.

### Work board

- Outer gap 4dp.
- Для 4–6 цифр используются left/right weights из UX contract.
- Для 7–10 цифр попытки располагаются сверху (120–150dp), матрица снизу и занимает остаток рассчитанной, а не экранной, высоты work board.
- Work board измеряется по десяти строкам матрицы; запрещён `weight(fill = true)`, растягивающий его на весь свободный viewport.
- Каждая panel использует `WarmPanel`, radius 16–18dp.
- Titles 15sp Semibold.

### Attempts

- Row height: 30dp (4), 29dp (5–6), 28dp (7–8), 26dp (9–10).
- Формат: `1234 → 2`.
- Horizontal padding 6dp; vertical 3dp.
- Latest row: `Primary` 10% fill + 1dp primary border.
- Остальные: transparent; optional 1dp divider.
- В stacked-режиме список показывает последние 3–4 строки, остаётся прокручиваемым и автоматически доводит новую попытку в видимую область.

### Matrix

- Для 4–10 цифр ширина ячейки рассчитывается из ширины панели, высота — независимо и ограничивается токеном 30/28/25/22dp.
- Header `Кодовая матрица` виден над строкой `0` и совпадает по высоте с header `Попытки`.
- Gap: 3dp (4), 2.5dp (5–6), 2dp (7–8), 1dp (9–10).
- Cell radius: 6dp до 6 колонок, 5dp от 7.
- Строки 0–9, колонки positions.
- Никакого horizontal scroll до 10 цифр.

### Helpers

- 40–44dp height.
- Icon 18dp; count/label одна строка.
- Selected hint имеет primary outline, но не меняет высоту.

### Tools

- 38–40dp height.
- Один общий segmented container, а не четыре независимые Material cards.
- Labels: текущая локализация; RU compact допускает `Нет / Возм / Точн / PRO`.

### Input

- Panel padding 8dp.
- Slots: 42/40/36/32dp по length groups.
- Keypad: 36–38dp; одна строка.
- Action row: 44dp.
- Confirm weight 1.35, reset weight 1.0.

### Ad

- Отдельный slot, radius 14–16dp.
- App container не добавляет фиолетовую рамку вокруг реального banner view.
- Placeholder/debug может иметь маркировку AD, production banner отображается без декоративного фрейма, который конфликтует с рекламным креативом.

## 6. Company

### Header

- На portrait phone использовать компактный вариант: title chip + reward/history actions.
- Не показывать одновременно большой InplaceX logo, большой жёлтый «Компания» и subtitle, если это съедает вертикальную высоту.
- Header actions соответствуют shell chrome.

### Chapter hero

- Одна warm progress panel + side badge/reward action.
- Progress и stars читаются с первого взгляда.

### Mission timeline/cards

- Card radius 16dp, border 1dp.
- Selected: 2dp primary/orange border.
- Locked: muted neutral, lock icon.
- Completed: stars visible, но карточка не становится кислотной.
- Current play action остаётся в нижнем `CompanyActionBar`.

### Action bar

- Primary play button — blue или green only when playable.
- Rules — secondary text/button.
- No energy — primary заменяется на понятное buy/recover action, существующая логика сохраняется.

## 7. Social / invites

### Hero

- Warm panel с небольшим blue title accent; не сплошная ярко-синяя большая карточка.
- Online status banner компактный.

### Match settings

- Format selector — segmented control.
- Secret length — stepper row в одной warm panel.
- Create/find match — primary full width.
- Friend code input + join — отдельная логическая группа.

### States

- Loading: spinner + одна строка.
- Waiting: invite code крупно, copy/share actions вторичные.
- Offline/error: warm panel + error accent; retry primary.
- Active online match использует общий `GameScreen`, поэтому отдельный gameplay design не создаётся.

## 8. Shop

- Категории и карточки сохраняются.
- Product tiles используют warm panels; price/action имеет единый baseline.
- Paid entitlements и debug/release isolation не затрагиваются.

## 9. Profile

- Account state, login, statistics и entitlement sections сохраняются.
- Один compact profile header, затем warm sections.
- Sign-in/out визуально различаются, но auth behavior не меняется.

## 10. Settings and dialogs

- Material `AlertDialog` допустим на первом pass, но colors/shapes должны соответствовать theme.
- Критичные dialog copy и button order не менять.
- Form fields: warm surface, radius 12dp, clear focus border.

## 11. Landscape

Текущая поддержка landscape в отдельных company-компонентах сохраняется. Основной продукт portrait-first; landscape не должен ломаться, но не является источником компромиссов для portrait gameplay.

---

# Component Contracts

## 1. `WarmPanel`

Назначение: базовая кремовая поверхность для gameplay и form groups.

- gradient top/bottom;
- radius 18dp default;
- border 1dp warm;
- elevation 3dp;
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

---

# Implementation Map

Проверено против `develop@5f37f4e138f96cdf70c489237b28137a351b3892`.

## 1. Архитектурная граница

`GameFieldScreen` и `OnlineDuelGameField` уже адаптируют состояние к общему stateless `GameScreen`. Финальный UI реализуется на presentation boundary, без переноса логики.

## 2. Новые файлы

Рекомендуемые пути:

```text
InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/theme/FinalUiTokens.kt
InplaceX-android/app/src/main/java/com/mirkori/inplacex/ui/common/FinalUiPrimitives.kt
```

Исходники находятся в `code/` этого пакета.

## 3. Gameplay vertical slice

### Основной файл

`ui/screens/game/presentation/GamePresentationComponents.kt`

Изменения по функциям:

- `GamePresentationLayout`: использовать breakpoint `codeLength > 6` и единый 4dp spacing; 4–6 остаются side-by-side, 7–10 становятся stacked.
- `PresentationCard`: заменить реализацией `WarmPanel` или удалить wrapper, где он создаёт лишнюю вложенность.
- `GameTopPanel`: заменить nested `GameInfoChip` surfaces на info strip + dividers.
- `GameAttemptsPanel`: передавать structured attempts; формат `guess → score`.
- `GameAttemptList`: использовать `CompactAttemptRow`, сохранить auto-scroll и tags.
- `GameAnalysisPanel`: для stacked-режима считать ширину и высоту cell независимо; digit остаётся в cell.
- `GameHelpersPanel`: compact counters.
- `GameToolsPanel`: segmented control.
- `GameInputPanel`: adaptive slot/keypad metrics и final buttons.
- `analysisVisualFor` и domain mapping не менять по смыслу; меняется только visual mapping.

### Не трогать

```text
ui/screens/game/GameFieldScreen.kt
ui/screens/game/state/**
ui/viewmodel/GameFieldViewModel.kt
core/**
data/**
platform/online/**
```

## 4. Shell

- `ui/shell/AppTopBar.kt`: токены chrome, одна тень, менее агрессивная обводка.
- `ui/shell/AppBottomMenu.kt`: те же tokens, compact selected state.
- `ui/shell/AppBottomAd.kt`: убрать фиолетовый production frame; debug placeholder отделить.
- `ui/shell/AppShell.kt`: геометрию менять только при доказанном clipping; transparent center и layer modes сохранить.
- `ui/layout/UiLayoutConfig.kt`: при необходимости только tokenized values, без изменения slot semantics.

## 5. Shared scene layer

`ui/screens/shared/SceneChrome.kt`

- `SceneCard` → warm panel system.
- `SceneActionTile` → `PolishedActionTile`.
- `SceneBadge` → compact warm badge.
- Сохранить signatures, semantics и call sites, где возможно.

## 6. Home

`ui/screens/home/HomeRootScreen.kt`

- `HomeSelectionScreen` не менять по flow.
- Обновить logo readability, spacing и action tile calls.
- Не создавать четвёртую карточку.
- `PveModesScreen.kt` и `PvpModesScreen.kt` приводятся к тем же primitives без изменения выбора режимов.

## 7. Company

Файлы:

```text
ui/screens/company/CompanyHeaderComponents.kt
ui/screens/company/CompanyMissionTimeline.kt
ui/screens/company/CompanyActionBar.kt
ui/screens/company/CompanySceneScreen.kt
ui/screens/company/CampaignHistoryScreen.kt
ui/screens/company/CampaignTutorialDialog.kt
```

Изменять presentation only. `CompanyCampaignLogic.kt`, progression/domain правила и repository calls не менять.

## 8. Social

Файлы:

```text
ui/screens/social/SocialRootScreen.kt
ui/screens/social/OnlineDuelScreen.kt
```

Допустимо менять Compose markup внутри этих файлов, но:

- network calls, polling, session state, invite normalization и callbacks не менять;
- `OnlineDuelGameField.kt` трогать только при необходимости layout wrapper, общий renderer уже используется;
- `platform/online/**` запрещён.

## 9. Remaining screens

```text
ui/screens/shop/ShopRootScreen.kt
ui/screens/profile/ProfileRootScreen.kt
ui/screens/settings/SettingsRootScreen.kt
ui/screens/settings/AdPrivacyConsentDialog.kt
```

Только visual consistency pass.

## 10. Theme

- `Color.kt`: существующие публичные colors не удалять; новые final tokens держать отдельно.
- `Theme.kt`: глобальную light scheme менять минимально, чтобы не вызвать непредсказуемый cascade.
- `Type.kt`: не менять глобальные размеры радикально. Dense gameplay styles задавать локально через tokens.

## 11. Тесты

Обновить/добавить:

```text
src/test/.../ui/screens/game/presentation/GamePresentationComponentsTest.kt
src/androidTest/.../ui/screens/game/GameFieldValidationTest.kt
src/androidTest/.../ui/screens/game/GameLocalizationSmokeTest.kt
src/androidTest/.../ui/screens/shell/ShellSectionsSmokeTest.kt
src/test/.../ui/screens/home/**
src/test/.../ui/screens/company/**
src/test/.../ui/screens/social/**
```

Проверять contract/semantics и bounds, а не private composable implementation.

## 12. Commit strategy

1. Tokens + primitives + tests.
2. Gameplay 4/6/8/10.
3. Shell + Home.
4. Company + Social.
5. Shop/Profile/Settings + final QA.

После commit 2 нужен owner screenshot review до массового переноса стиля.

---

# Acceptance and QA

## 1. Hard-fail criteria

Изменение не принимается, если выполняется хотя бы одно:

- фон комнаты заменён или закрыт общей непрозрачной поверхностью;
- попытки и матрица не видны одновременно;
- рабочая зона 4–6 цифр стала vertical stack/tabbed или 7–10 цифр осталась в тесном left/right режиме;
- строка попытки разбита на отдельные digit cards;
- на 8 цифрах есть horizontal scroll, clipping или наложение;
- production online length 9–10 перестал работать;
- реклама перекрывает input/tools;
- загруженный ad-slot попадает под системную navigation bar;
- work board растянут свободной высотой viewport, matrix row выше 30dp или confirm уходит за нижнюю границу;
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
- 6 digits: attempts слева, matrix справа;
- 7 digits: attempts сверху, matrix снизу;
- 10 digits: matrix использует всю ширину без horizontal scroll;
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

---

# Codex Master Task — InplaceX Final UI Pass

Execution recommendation: resolve through the repository routing policy; reasoning high; complexity XL for the complete package. Implement as the four bounded tasks in `07_TASK_CANDIDATES.json`, not as one uncontrolled rewrite.

## Orientation

Before editing:

1. Read `AGENTS.md`.
2. Read `.agent/START_HERE.md`, `.agent/CODEX_CHAT.md`, `.agent/general.md`, `.agent/project.md`, `.agent/modules.md`, `.agent/workflows.md`.
3. Run the GitHub freshness guard and prove the checkout is exact at current `origin/develop`.
4. Read this package in order: `00_OWNER_DECISIONS.md` through `06_ACCEPTANCE_AND_QA.md`.
5. Read `07_TASK_CANDIDATES.json` and execute only the first eligible task.
6. Inspect current code before copying provided Kotlin primitives.

Package authored against `develop@5f37f4e138f96cdf70c489237b28137a351b3892`. If current `develop` differs, report the changed UI files and adapt; do not reset owner work and do not blindly overwrite newer code.

## Goal

Apply the final warm-room/polished-casual UI system to the existing Android app while preserving all behavior. This is a visual convergence pass, not a product redesign.

## Non-negotiable owner decisions

- Keep `toy_room_bg_v6` bright and visible.
- Keep attempts left and code matrix right for 4–6 digits; stack attempts above the matrix for 7–10 digits.
- Attempt rows are `1234 → 2`, not per-digit cards.
- Digits stay inside matrix cells.
- Attempts and matrix remain simultaneously visible.
- Optimize 4–8 digits and preserve the existing 9–10 online fallback.
- Keep top HUD, five-item bottom navigation and ad slot semantics.
- Do not change game/domain/state/network/purchase/ad behavior.

## Use the supplied implementation kit

- Copy/adapt `code/FinalUiTokens.kt` to `ui/theme/FinalUiTokens.kt`.
- Copy/adapt `code/FinalUiPrimitives.kt` to `ui/common/FinalUiPrimitives.kt`.
- Follow `code/IntegrationExamples.md`.
- Treat technical layouts as geometry evidence.
- Treat `approved_mood_only.png` as mood only.

## Task sequence

### INPX-UX-201 — gameplay

Implement tokens/primitives and refactor presentation only in `GamePresentationComponents.kt`. Preserve `GameScreen` boundary, callbacks, test tags, auto-scroll, facts/marks and dialogs. Produce all 4/6/8/10 gameplay captures. Stop for owner visual approval.

### INPX-UX-202 — shell/home

After explicit owner approval, update `AppTopBar`, `AppBottomMenu`, `AppBottomAd`, shared scene components and Home. Preserve shell modes, background and navigation.

### INPX-UX-203 — company/social

Converge Company and Social visuals. Do not change progression or online session logic.

### INPX-UX-204 — remaining/QA

Converge Shop/Profile/Settings, run full visual capture matrix and release checks.

## Forbidden expansion

Do not:

- alter `GameFieldScreen`, state holder, ViewModel, engine, data or backend for visual convenience;
- reduce code-length contracts;
- introduce bitmap panels, new font binaries or external UI dependencies;
- rename routes or change localization-backed behavior;
- silently fix adjacent non-UI issues in the same PR.

Route discoveries outside scope to a separate input package.

## Mandatory evidence

For every task return:

- exact base SHA;
- changed files;
- checks and results;
- captures required for that task;
- deviations/blockers;
- branch/commit/PR;
- `integration_requested` only when the task output contract is fully satisfied.

If a required check or capture cannot run, return `needs_worker_fix` or `visual_qa_blocked`, not success.
