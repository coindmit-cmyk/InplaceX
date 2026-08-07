# Настройка провайдеров

## Цель

Android и backend уже содержат рабочие границы для авторизации, рекламы и
биллинга. Значения из кабинетов и секреты остаются вне Git: Android читает
идентификаторы из `local.properties`, а сервер получает секреты только через
переменные окружения или утверждённое хранилище секретов.

Изолированная рабочая копия или CI могут читать общий приватный файл:

```powershell
.\gradlew.bat <task> -PinplacexProviderConfigFile=C:\private\local.properties
```

## Android-параметры

Для каждого варианта используются отдельные ключи: `<variant>` — `debug` или
`release`.

- Backend:
  - `online.<variant>.baseUrl`
- Google Play:
  - `provider.<variant>.googlePlay.webClientId`
  - `provider.<variant>.googlePlay.serverClientId`
  - `provider.<variant>.googlePlay.gamesProjectId`
- Личный Yandex владельца:
  - `provider.<variant>.ads.yandex.owner.banner.game`
  - `provider.<variant>.ads.yandex.owner.rewarded.general`
  - `provider.<variant>.ads.yandex.owner.interstitial.postMatch`
- Ограничения post-match рекламы:
  - `provider.<variant>.ads.interstitial.minimumCompletedMatches`
  - `provider.<variant>.ads.interstitial.minimumForegroundSeconds`
  - `provider.<variant>.ads.interstitial.gamesBetweenImpressions`
- Биллинг:
  - `provider.<variant>.billing.removeAdsProductId`
  - `provider.<variant>.billing.proSubscriptionId`
  - `provider.<variant>.billing.proPlusSubscriptionId`

Безопасный шаблон находится в
`InplaceX-android/provider-config.example.properties`.

## Текущая рекламная схема

- Yandex принадлежит владельцу и используется только для российского рынка.
- Для `GLOBAL` отдельный провайдер пока не подключён, поэтому реклама там
  безопасно отключена.
- Неизвестный рынок, отсутствие privacy-решения или неполная конфигурация
  приводят к нулю рекламных запросов.
- `UNKNOWN` не кэшируется; активный игровой экран повторяет banner-проверку с
  паузой, поэтому показ восстанавливается после возвращения сети.
- Banner, rewarded и optional post-match interstitial используют один
  Yandex-маршрут.
- Reward выдаётся только после reward callback рекламной сети.
- `Remove Ads`, временный `Pro`, постоянный `Pro` и `Pro+` отключают
  принудительный banner и post-match interstitial. Добровольные rewarded
  предложения за подсказки и монеты остаются доступны.
- Release по умолчанию допускает первый post-match interstitial не раньше
  `20` завершённых игр и `1800` секунд активного использования, затем через
  каждые `4` завершённые игры.

## Release-gate

`preReleaseBuild` и `preInternalDistributionBuild` автоматически запускают
`:app:validateReleaseAdsConfig`.

Проверка:

- требует HTTPS origin backend без user info, path, query и fragment;
- требует Yandex banner и rewarded placement ID владельца;
- допускает пустой post-match interstitial ID;
- требует разные ID для всех настроенных Yandex placements;
- отклоняет управляющие символы и чрезмерно длинные значения;
- не выводит настроенные значения.

Таким образом release-сборка не создаётся с частично активированной рекламой.

## Backend и определение рынка

Предпочтительный production-режим использует локальную MMDB:

- `INPLACEX_AD_MARKET_REQUIRED=true`
- `INPLACEX_AD_MARKET_DB_PATH=/var/lib/inplacex/geoip/dbip-country-lite.mmdb`
- `INPLACEX_AD_MARKET_CLIENT_IP_HEADER=X-InplaceX-Client-IP`
- `INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS=127.0.0.1,::1`

Nginx обязан перезаписывать client-IP header своим `$remote_addr`. Backend
принимает его только от явно доверенного proxy host. Прямой числовой IP
определяется без заголовка. Поддельный, неоднозначный или невалидный адрес даёт
`UNKNOWN`, и Android не показывает рекламу.

Старый trusted-country-header режим сохранён только как взаимоисключающая
совместимость. Полный production runbook:
`InplaceX-docs/Backend/Advertising Market Operations.md`.

## Что требуется для активации

1. Добавить отдельные реальные ID для banner и rewarded Yandex; post-match
   interstitial можно добавить позже.
2. Подтвердить privacy-текст, договорную схему получателей дохода и store
   disclosures.
3. Развернуть подготовленные backend/nginx/MMDB-настройки в production после
   отдельного разрешения владельца.
4. Проверить demo/test placements, затем реальные placements на разрешённом
   тестовом устройстве.

Email и Telegram дополнительно требуют server-side delivery adapters,
одноразового challenge storage и rate limiting. Наличие общего verifier само
по себе не означает активный вход.
