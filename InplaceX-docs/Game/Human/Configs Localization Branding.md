# Configs, Localization, Branding

## Что должно быть вне хардкода

- названия секций
- короткие подписи кнопок
- reserve/placeholder тексты shell
- названия режимов
- branding-значения
- feature flags
- mode parameters
- исходники иконок секций и top actions

## Что остаётся в коде

- игровая логика
- match lifecycle
- валидация
- scoring
- правила перехода между фазами

## Текущая реализация

На этом этапе часть конфигурации уже сведена в центральный каталог приложения:

- platform config
- branding config
- mode definitions
- static localization provider
- icon source folder: `image/icon`

Это промежуточный этап перед более глубокой externalized-конфигурацией.

## Иконки

Каноническая папка исходников иконок:

- `image/icon`

Там хранятся исходные файлы иконок, а приложение использует их как ожидаемый стандарт имён. Пока Android UI работает через fallback-иконки в коде, но структура под реальные ассеты уже зафиксирована.
