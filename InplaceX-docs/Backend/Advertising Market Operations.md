# Геомаршрутизация рекламы в production

## Что готово

Backend сам преобразует текущий сетевой IP в один из трёх результатов:

- `RUSSIA`;
- `GLOBAL`;
- `UNKNOWN`.

Сырой IP не возвращается в Android, не записывается в рекламное состояние и не
попадает в прикладной лог. `UNKNOWN` не запускает ни один рекламный SDK.

Основной production-режим использует локальную DB-IP Country Lite в формате
MMDB. Внешний GeoIP API и его ключ не нужны. База обновляется ежемесячно и
распространяется по CC BY 4.0; backend добавляет к ответу HTTP `Link` с
атрибуцией `https://db-ip.com`, не меняя ограниченное JSON-тело.

## Первичная установка базы

На сервере:

```bash
sudo install -d -m 0755 /var/lib/inplacex/geoip
sudo ./ops/ads/update-dbip-country-lite.sh
```

Скрипт скачивает только по HTTPS с ограниченными timeout, проверяет gzip и
допустимый размер, сохраняет текущую базу как `.previous`, затем атомарно
заменяет MMDB. После обновления backend нужно перезапустить, потому что читатель
открывает базу один раз при старте.

Месяц релиза можно указать явно для воспроизводимой установки:

```bash
sudo ./ops/ads/update-dbip-country-lite.sh \
  /var/lib/inplacex/geoip/dbip-country-lite.mmdb \
  2026-07
```

## Environment backend

Безопасный пример находится в `ops/ads/ad-market.environment.example`:

```text
INPLACEX_AD_MARKET_REQUIRED=true
INPLACEX_AD_MARKET_DB_PATH=/var/lib/inplacex/geoip/dbip-country-lite.mmdb
INPLACEX_AD_MARKET_CLIENT_IP_HEADER=X-InplaceX-Client-IP
INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS=localhost,127.0.0.1,::1
INPLACEX_AD_MARKET_SERVICE=inplacex-backend.service
INPLACEX_AD_MARKET_INTERNAL_URL=http://127.0.0.1:18080
```

Значения `localhost,127.0.0.1,::1` подходят, когда nginx и backend работают на
одном сервере, а backend слушает только loopback. Backend использует адрес
сетевого peer; конкретный Ktor engine может представить loopback именем или
числовым адресом. Для контейнерной сети нужно указать фактический адрес
доверенного proxy после проверки runtime-сети.

`INPLACEX_AD_MARKET_REQUIRED=true` обязателен для production: неполная
конфигурация или отсутствующая MMDB останавливает запуск вместо молчаливого
отключения рекламы.

## Граница nginx

В `http`-контексте nginx один раз создаётся зона ограничения частоты:

```nginx
limit_req_zone $binary_remote_addr zone=inplacex_ad_market:10m rate=30r/m;
```

В точном HTTPS location для market endpoint подключается:

```nginx
location = /api/v1/runtime/ad-market {
    include /path/to/checkout/ops/ads/nginx-ad-market-proxy.conf;
    proxy_pass http://127.0.0.1:18080;
}
```

Snippet всегда перезаписывает `X-InplaceX-Client-IP` значением `$remote_addr`.
Нельзя использовать входной заголовок клиента или дописывать его через
`$proxy_add_x_forwarded_for`: тогда клиент смог бы подменить рынок.

Backend-порт остаётся закрытым снаружи. Публичным является только HTTPS nginx.
Порт в `proxy_pass` и `INPLACEX_AD_MARKET_INTERNAL_URL` обязан совпадать с
фактическим портом конкретного deployment; нельзя считать `18080` универсальным.
Для этого endpoint access-log отключён, чтобы nginx не сохранял сырой IP.
Срок хранения error/security логов задаётся общей production-политикой.

## Проверка

После запуска:

```bash
./ops/ads/verify-ad-market.sh https://backend.example
```

Для проверки ожидаемого рынка:

```bash
./ops/ads/verify-ad-market.sh https://backend.example RUSSIA
```

Smoke проверяет `/health`, `/ready`, точную JSON-схему market,
`Content-Type`, `Cache-Control: no-store` и обязательную DB-IP attribution в
HTTP `Link`. Все сетевые вызовы имеют ограниченные timeout. Проверку `RUSSIA`
и `GLOBAL` нужно выполнить из двух сетей; store-аккаунт и locale на результат
не влияют.

## Обновление

DB-IP Lite обновляется ежемесячно. Операционный таймер должен:

1. запустить `update-dbip-country-lite.sh`;
2. перезапустить backend только после успешной установки;
3. выполнить `verify-ad-market.sh`;
4. при ошибке вернуть предыдущий backend image и MMDB:

```bash
sudo ./ops/ads/rollback-dbip-country-lite.sh
sudo systemctl restart <inplacex-backend-service>
./ops/ads/verify-ad-market.sh https://backend.example
```

Имя backend service определяется фактическим production-развёртыванием и не
зашивается в скрипт репозитория.

## Автоматическое ежемесячное обновление

Production unit и timer находятся в `ops/ads/systemd/`. Скрипты из `ops/ads/`
устанавливаются вместе в `/usr/local/libexec/inplacex/ads/`, после чего:

```bash
sudo install -m 0644 \
  ops/ads/systemd/inplacex-geoip-update.service \
  /etc/systemd/system/inplacex-geoip-update.service
sudo install -m 0644 \
  ops/ads/systemd/inplacex-geoip-update.timer \
  /etc/systemd/system/inplacex-geoip-update.timer
sudo systemctl daemon-reload
sudo systemctl enable --now inplacex-geoip-update.timer
```

Перед включением timer создаётся root-owned `/etc/inplacex/ads.env` с режимом
`0600`. В нём задаются точное имя backend service и реальный loopback URL из
примера `ops/ads/ad-market.environment.example`; значения нельзя угадывать по
старому deployment или порту другого сервиса.

Timer запускается пятого числа каждого месяца с задержкой до шести часов, чтобы
не зависеть от точного времени публикации новой базы. Wrapper обновляет MMDB,
перезапускает backend, проверяет health/readiness, обе ветки рынка, заголовки
кэша и атрибуцию. При ошибке он автоматически возвращает `.previous`,
перезапускает backend ещё раз и оставляет unit в failed для внимания оператора.

В репозиторий не коммитятся MMDB, `.env`, SDK IDs или доступы сервера.
