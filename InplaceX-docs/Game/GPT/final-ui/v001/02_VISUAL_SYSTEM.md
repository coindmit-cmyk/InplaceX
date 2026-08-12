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
