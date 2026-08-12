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
- Left/right weights из UX contract.
- Каждая side panel использует `WarmPanel`, radius 16–18dp.
- Titles 15sp Semibold.

### Attempts

- Row height: 30dp (4), 29dp (5–6), 28dp (7–8), 26dp (9–10).
- Формат: `1234 → 2`.
- Horizontal padding 6dp; vertical 3dp.
- Latest row: `Primary` 10% fill + 1dp primary border.
- Остальные: transparent; optional 1dp divider.

### Matrix

- Заполняет доступную side panel по min(width constraint, height constraint).
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
