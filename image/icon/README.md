# Icon Source Folder

`image/icon` — каноническая папка для исходников иконок проекта.

## Назначение

- хранить исходные файлы иконок
- держать единые имена ассетов
- использовать как источник для Android-ресурсов и будущих других клиентов

## Текущее правило

Пока приложение использует встроенные Material Icons как fallback.

Когда появятся финальные иконки, они должны складываться сюда с устойчивыми именами:

- `section_home.svg`
- `section_social.svg`
- `section_tournaments.svg`
- `section_shop.svg`
- `section_profile.svg`
- `top_back.svg`
- `top_settings.svg`

## Практика

1. Исходник кладётся в `image/icon`
2. Затем для Android делается соответствующий runtime-ресурс
3. После этого fallback в коде можно заменить на asset/resource-иконку

## Почему не грузим напрямую из корня проекта

Android runtime надёжнее работает с `res/drawable` или `assets` внутри app-модуля.

Поэтому `image/icon` — это source-of-truth для дизайна и структуры, а не финальная runtime-папка Android.
