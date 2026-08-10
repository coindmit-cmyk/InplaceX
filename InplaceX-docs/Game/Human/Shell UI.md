# Shell UI

Экран собирается из 3 постоянных блоков:

- `Top`
- `Center`
- `Bottom`

Раздел Social в нижнем меню показывает badge с количеством непринятых
адресных приглашений, пока приложение запущено.

## Top

Верхний блок теперь рассматривается как постоянный shell-слой.

Состав по умолчанию:

- кнопка `Back` когда нужен возврат
- `Energy`
- `Coins`
- кнопка `Settings`

Текущая реализация использует fallback-цвета и material-иконки, но все пути к будущим изображениям уже вынесены в конфиг.

## Backgrounds

Для каждого уровня можно отдельно задать:

- общий фон приложения
- фон `Top`
- фон `Center`
- фон `Bottom`

Сейчас это заведено через конфиги с `imageAssetPath + fallbackColor`.

Каноническая точка настройки:

- `app/src/main/java/com/mirkori/inplacex/platform/config/AppConfigCatalog.kt`
- `app/src/main/java/com/mirkori/inplacex/platform/config/ShellAppearanceConfig.kt`

Иконки лежат в:

- `image/icon`

Верхние shell-иконки:

- `image/icon/top_back.svg`
- `image/icon/top_energy.svg`
- `image/icon/top_coins.svg`
- `image/icon/top_settings.svg`

## Localization

Подписи верхней панели вынесены в языковые пакеты:

- `top.back`
- `top.energy`
- `top.coins`
- `top.settings`

Точка настройки:

- `app/src/main/java/com/mirkori/inplacex/platform/localization/StaticLocalizationProvider.kt`
