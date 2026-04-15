# Platform Vision

## Три слоя

### Game Core

- правила матча
- scoring
- генерация секрета
- валидаторы
- opponent contracts
- mode definitions

### Game Platform

- shell
- навигация
- настройки
- локализация
- branding
- ads/analytics/profile/social hooks
- screen adaptation
- feature flags

### App Client

- Android UI
- конкретные composable-экраны
- интеграция shell и game core

## Границы

- `game core` не знает про Android
- `game platform` не знает внутренности конкретной игры глубже публичных контрактов
- `app/client` может зависеть от platform и game

## Риски текущего состояния

- пока всё сосредоточено в одном `app`-модуле
- reusable shell уже просматривается, но ещё не отделён полностью
- часть конфигов и строк всё ещё находится в UI-коде
- есть legacy-модели, которые постепенно нужно свести к каноническим контрактам
