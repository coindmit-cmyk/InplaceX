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
./ops/ads/verify-ad-market.sh https://backend.example '' /inplacex
```

Для проверки ожидаемого рынка:

```bash
./ops/ads/verify-ad-market.sh https://backend.example RUSSIA /inplacex
```

Smoke проверяет `/inplacex/health`, `/inplacex/ready`, точную JSON-схему market,
`Content-Type`, `Cache-Control: no-store` и обязательную DB-IP attribution в
HTTP `Link`. Все сетевые вызовы имеют ограниченные timeout. Проверку `RUSSIA`
и `GLOBAL` нужно выполнить из двух сетей; store-аккаунт и locale на результат
не влияют.

## Обновление

После первого production deploy активную MMDB нельзя заменять отдельно от
release activation: её SHA-256 входит в разрешение запуска backend. Каноническая
ротация использует тот же lock, durable journal, maintenance/drain gate и
activation record, что deploy и rollback:

```bash
sudo ./ops/production/rotate-geoip.sh /etc/inplacex-online/backend.env
```

Для воспроизводимой ротации можно передать месяц `YYYY-MM`, а для заранее
проверенного локального артефакта — `--candidate-file /absolute/path.mmdb`.
Скрипт проверяет текущие release/image/secrets, PostgreSQL system identifier и
старый fingerprint, сохраняет durable backup,
закрывает новые запросы, дожидается активных REST/WebSocket lease, атомарно
устанавливает candidate и выдаёт backend короткий activation permit с новым
fingerprint. Gate снимается только после exact smoke и durable activation.
Перед заменой active MMDB общий release helper требует успешный bounded backend
stop и независимо подтверждает `State.Running=false` для exact compose container.
При stop error, исчезнувшем inspection target или всё ещё running container
MMDB не меняется, automatic restore не запускается, а gate и journal остаются.
Activation v1 сначала мигрируется только через документированный deploy с
одноразовым ack; GeoIP timer и manual rotation на v1 завершаются fail closed.

При обычной ошибке до подтверждения новый файл автоматически откатывается. После
SIGKILL или reboot повтор той же команды продолжает journal; backend с новым
неподтверждённым fingerprint остаётся fail closed. Скрипты
`refresh-dbip-country-lite.sh` и `rollback-dbip-country-lite.sh` остаются только
для legacy standalone/systemd deployment и не применяются к production Compose.
Application rollback сохраняет последнюю transactionally verified MMDB и
переносит её fingerprint в activation предыдущего release; hash из старого
release receipt не откатывает GeoIP-данные.

## Автоматическое ежемесячное обновление

Production unit и timer находятся в `ops/ads/systemd/`. На host устанавливается
всё дерево `ops/` из того же проверенного release checkout: `rotate-geoip.sh`
сравнивает установленные nginx snippets со своими исходниками и использует
соседние `compose.yaml`, `release-common.sh`, `smoke-backend.sh` и downloader.
Например:

```bash
sudo install -d -o root -g root -m 0755 \
  /usr/local/libexec/inplacex/production \
  /usr/local/libexec/inplacex/ads
sudo cp -R --no-preserve=ownership ops/production/. \
  /usr/local/libexec/inplacex/production/
sudo cp -R --no-preserve=ownership ops/ads/. \
  /usr/local/libexec/inplacex/ads/
sudo chown -R root:root \
  /usr/local/libexec/inplacex/production \
  /usr/local/libexec/inplacex/ads
sudo chmod -R u=rwX,go=rX \
  /usr/local/libexec/inplacex/production \
  /usr/local/libexec/inplacex/ads
sudo chmod 0755 /usr/local/libexec/inplacex/production/rotate-geoip.sh
sudo install -m 0644 \
  ops/ads/systemd/inplacex-geoip-update.service \
  /etc/systemd/system/inplacex-geoip-update.service
sudo install -m 0644 \
  ops/ads/systemd/inplacex-geoip-update.timer \
  /etc/systemd/system/inplacex-geoip-update.timer
sudo systemctl daemon-reload
sudo systemctl enable --now inplacex-geoip-update.timer
```

Timer читает тот же root-owned `/etc/inplacex-online/backend.env` с режимом
`0600`, который использует deploy. Отдельный `/etc/inplacex/ads.env` ему не
нужен. Фактический loopback port берётся из этого файла; нельзя угадывать его по
старому deployment или порту другого сервиса.

Timer запускается пятого числа каждого месяца с задержкой до шести часов, чтобы
не зависеть от точного времени публикации новой базы. Транзакция проверяет
health/readiness и exact release identity после перезапуска. Внешние проверки
`RUSSIA` и `GLOBAL` из соответствующих сетей остаются обязательной
послеротационной эксплуатационной проверкой.

В репозиторий не коммитятся MMDB, `.env`, SDK IDs или доступы сервера.
