# Production deployment InplaceX online backend

## Граница и гарантии

Этот runbook разворачивает отдельные InplaceX backend и PostgreSQL. Mirkori
Games Platform остаётся отдельным сервисом, хранит общую базу пользователей и
является единственным production issuer game-токенов. Сервисы могут разделять
VPS и HTTPS origin, но обязаны иметь разные Compose projects, volumes, БД и
loopback-порты.

Release scripts работают fail closed:

- читают environment и rollback receipt как данные `KEY=VALUE` из уже
  открытого файлового дескриптора; shell-код, подстановки, неизвестные и
  повторные ключи запрещены;
- требуют digest-pinned images, проверяют точный `RepoDigests` и OCI labels
  `org.opencontainers.image.version/revision` до включения maintenance;
- проверяют текущие PostgreSQL image/database/user/volume и backend loopback
  binding до изменения runtime;
- сначала закрывают все публичные InplaceX routes maintenance-файлом, затем
  останавливают backend и только после drain создают snapshot;
- сохраняют release journal и подтверждённую activation в root-owned каталоге
  вне `/run`; временный activation permit живёт не более восьми секунд;
- backend запускается только при точном совпадении release ID, Git SHA, image
  digest и fingerprints ключей/GeoIP/runtime config с durable activation либо
  действующим permit, а потеря authorization во время работы завершает процесс
  с кодом 78;
- снимают gate только после smoke кандидата или успешно восстановленной
  предыдущей версии;
- привязывают одноразовый rollback к deployment UUID, system identifier БД,
  SHA-256 backup, ключам и публичной конфигурации.

Скрипты не выбирают порт, не создают production volume и не обновляют major
PostgreSQL. Эти решения остаются явными operator actions.

## Однократная подготовка host

Выберите отдельный системный GID, например `21081`. Он нужен только для чтения
file secrets непривилегированными UID внутри backend и PostgreSQL containers.

```bash
sudo groupadd --system --gid 21081 inplacex-runtime-secrets
sudo install -d -o root -g 21081 -m 0750 /etc/inplacex-online/secrets
sudo install -d -o root -g root -m 0700 /var/backups/inplacex-online
sudo install -d -o root -g root -m 0700 /var/lib/inplacex-online
sudo install -d -o root -g root -m 0700 /var/lib/inplacex-online/releases
sudo install -d -o root -g root -m 0700 /var/lib/inplacex-online/release-state
sudo install -o root -g root -m 0644 \
  ops/production/mirkori-games-release-lock.conf \
  /etc/tmpfiles.d/mirkori-games-release-lock.conf
sudo systemd-tmpfiles --create \
  /etc/tmpfiles.d/mirkori-games-release-lock.conf
sudo install -o root -g root -m 0600 \
  ops/production/backend.env.example \
  /etc/inplacex-online/backend.env
```

`tmpfiles.d` восстанавливает после reboot общий закрытый каталог
`/run/lock/mirkori-games` (`root:root`, `0700`). InplaceX использует внутри него
собственный `inplacex-online-release.lock`; Mirkori Platform должна использовать
другое имя. Deploy/rollback безопасно создают отсутствующий lock, открывают inode,
проверяют `root:root`, mode `0600`, один hard link и только затем берут `flock`.
Durable release state находится в `/var/lib`, поэтому reboot удаляет только
короткоживущие permits и не разрешает неподтверждённый candidate.

Создайте внешний volume один раз:

```bash
sudo docker volume create \
  --label com.mirkori.product=inplacex \
  --label com.mirkori.component=online-postgres \
  --label com.mirkori.managed=true \
  inplacex-online-postgres-data
```

Compose объявляет volume как `external: true`, поэтому опечатка не создаст
пустую БД. Для первого запуска установите `INPLACEX_INITIAL_DEPLOY=true`;
скрипт допустит его только без project containers и только с пустым volume.
После первого успеха сразу верните `false`.

## Secrets и provider boundary

В `/etc/inplacex-online/secrets` нужны три файла:

- `database-password.txt` — отдельный случайный пароль PostgreSQL;
- `online-state-key-base64.txt` — Base64 ровно 32 случайных байтов; потеря ключа
  делает сохранённые online-сессии невосстановимыми;
- `platform-public-key-x509-base64.txt` — X.509 Base64 public RSA key Mirkori
  Platform, минимум 2048 бит. Private signing key здесь не хранится.

```bash
sudo chown root:21081 /etc/inplacex-online/secrets/*.txt
sudo chmod 0640 /etc/inplacex-online/secrets/*.txt
sudo chmod 0750 /etc/inplacex-online/secrets
```

`INPLACEX_RUNTIME_SECRET_GID=21081` добавляется как supplementary group обоим
containers. Deploy дополнительно определяет фактический PID 1 UID каждого
container и выполняет `test -r` от его имени. Доступ, подтверждённый только
host modes или запуском от root, не считается доказательством.

Production использует только `_PATH` варианты:

- `INPLACEX_DATABASE_PASSWORD_PATH`;
- `INPLACEX_ONLINE_PUBLIC_KEY_X509_BASE64_PATH`;
- `INPLACEX_ONLINE_STATE_KEY_BASE64_PATH`.

Compose задаёт их внутренними `/run/secrets/*` путями. Inline secret variables
не помещаются в environment, receipt, Compose config или логи.

## Image и release identity

Backend image собирается только из clean `HEAD`, публикуется вместе с BuildKit
provenance/SBOM, а helper создаёт точный release manifest:

```bash
manifest_stage="$(mktemp)"
ops/production/build-backend-release.sh \
  registry.example/mirkori/inplacex-backend:2026.08.07.1 \
  inplacex-backend-2026.08.07.1 \
  "$manifest_stage" \
  --push
sudo install -o root -g root -m 0600 "$manifest_stage" \
  /var/lib/inplacex-online/releases/inplacex-backend-2026.08.07.1.json
rm -f -- "$manifest_stage"
```

Из manifest без ручного пересчёта переносятся `image` в
`INPLACEX_BACKEND_IMAGE`, `imageDigest`, `releaseId`, `gitSha` и
`sourceArchiveSha256`; `INPLACEX_RELEASE_MANIFEST_PATH` указывает на
установленный root-owned файл. Локальный image ID и mutable tag доказательством
не являются. PostgreSQL image также задаётся digest-ссылкой.

Перед deploy заполните все `REPLACE_*` в `/etc/inplacex-online/backend.env` и
особенно проверьте фактические `INPLACEX_BACKEND_LOOPBACK_PORT`,
`INPLACEX_PUBLIC_HOSTNAME`, `INPLACEX_OPERATOR_NETWORK_CIDR`,
`INPLACEX_RELEASE_STATE_DIRECTORY` и `INPLACEX_DRAIN_TIMEOUT_SECONDS`.

## Порты и текущий runtime

Порты Mirkori Platform и InplaceX могут отличаться на каждом host. Перед
изменением environment всегда инвентаризируйте фактический runtime:

```bash
sudo ss -H -ltnp
sudo docker ps --all --format '{{.Names}} {{.Ports}} {{.Image}}'
sudo docker volume inspect inplacex-online-postgres-data
```

`INPLACEX_BACKEND_LOOPBACK_PORT` должен быть свободен для initial deploy либо
точно принадлежать текущему backend этого Compose project. Контейнер публикует
только `127.0.0.1:<port>:8080`.

## Nginx perimeter

Установите snippets и общие rate-limit zones:

```bash
sudo install -o root -g root -m 0644 \
  ops/production/inplacex-online-maintenance-gate.conf \
  ops/production/inplacex-online-rest-rate-limit.conf \
  ops/production/inplacex-online-websocket-rate-limit.conf \
  ops/production/inplacex-online-rest-proxy.conf \
  /etc/nginx/snippets/
sudo install -o root -g root -m 0644 \
  ops/production/inplacex-online-rate-zones.conf \
  /etc/nginx/conf.d/inplacex-online-rate-zones.conf
sudo install -o root -g root -m 0644 \
  ops/ads/nginx-ad-market-proxy.conf \
  /etc/nginx/snippets/inplacex-ad-market-proxy.conf
sudo ops/production/render-nginx-config.sh \
  18081 192.0.2.10/32 \
  /etc/nginx/snippets/inplacex-online.locations.conf
```

Подключите rendered locations внутри нужного HTTPS `server`. Все восемь
публичных InplaceX locations включают один и тот же файловый gate
`/run/inplacex-online/maintenance.flag`. REST и WebSocket сохраняют
`Authorization`; WebSocket также сохраняет `Sec-WebSocket-Protocol`, а nginx
перезаписывает `X-Real-IP` своим `$remote_addr`.

```bash
sudo nginx -t
sudo nginx -s reload
```

Deploy откажется продолжать, если snippets отличаются от репозитория, rendered
locations не совпадают с портом/CIDR или подключены не ровно один раз в HTTPS
`server` на `443` с точным `INPLACEX_PUBLIC_HOSTNAME`.

## Deploy

```bash
sudo ops/production/deploy-backend.sh \
  /etc/inplacex-online/backend.env \
  /var/backups/inplacex-online
```

Порядок операции:

1. lock, строгая конфигурация, modes/owners/parent dirs, durable state, secrets,
   volume и текущий runtime;
2. pull и проверка candidate, PostgreSQL и previous images;
3. durable transaction journal, maintenance gate, bounded drain активных
   REST/WebSocket запросов и graceful stop backend;
4. PostgreSQL readiness, фактическое чтение secrets, system identifier,
   `pg_dump`, `pg_restore --list` и SHA-256;
5. bounded Compose start (`--wait-timeout`), migrations, runtime UID smoke и
   exact `/health`, `/ready`, `/meta/release`;
6. durable verified activation, атомарные receipt и
   `latest-inplacex-backend-release.env`;
7. удаление transaction journal и снятие drain/maintenance gates.

При ошибке после snapshot скрипт останавливает candidate, восстанавливает БД и
запускает заранее pulled/verified previous image. Если previous smoke не
проходит, backend остаётся остановленным, gate остаётся включённым, а backup
сохраняется.

### Одноразовое восстановление legacy checksum

Нормальное значение
`INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK` пустое. Только для известной
истории ровно v1-v8 либо v1-v9 с отсутствующими checksum допустимо временно
установить:

```text
INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK=acknowledge-inplacex-schema-v1-v8
```

Строка acknowledgement сохраняет историческое имя для совместимости, но deploy
принимает только exact последовательность v1-v8 или v1-v9 и хотя бы один
отсутствующий checksum. Приложение до любых записей сверяет всю историю и
известный fingerprint таблиц, колонок, constraints и индексов, затем заполняет
checksum в одной транзакции. Deploy повторно создаёт backend уже без
acknowledgement. Сразу после успешного прохода удалите значение из operator
`backend.env`: следующий deploy с оставленным ack будет отклонён, поскольку
missing checksum уже нет. Любая неизвестная схема, версия или разница остаётся
startup error.

## Rollback

Rollback разрешён только для active receipt из одноразового latest pointer:

```bash
sudo ops/production/rollback-backend.sh \
  /etc/inplacex-online/backend.env \
  /var/backups/inplacex-online/<deployment-id>.release.env \
  --confirm-data-restore
```

До gate и destructive restore скрипт повторно pull/verify candidate,
PostgreSQL и previous images; сверяет current containers, system identifier,
backup checksum, state/public key fingerprints и public-config fingerprint.
Затем создаётся durable rollback journal, verified activation кандидата
отзывается, создаётся emergency backup и восстанавливается receipt-bound
snapshot. Pointer остаётся `active`, пока предыдущая версия не получит новый
durable activation; только после successful smoke он атомарно становится
`rolled_back`, journal удаляется и gate снимается. После SIGKILL или reboot
повтор той же команды продолжает точную фазу journal, а backend без activation
остаётся fail closed. Receipt нельзя использовать второй раз.

## Проверки после deploy

```bash
ops/production/smoke-backend.sh loopback \
  http://127.0.0.1:18081 \
  <release-id> <git-sha> sha256:<digest>
ops/production/smoke-backend.sh external \
  https://online.example.com \
  <release-id> <git-sha> sha256:<digest>
ops/ads/verify-ad-market.sh https://online.example.com RUSSIA /inplacex
```

Допуск к rollout также требует реальный Platform token (`gid=inplacex`), REST
match creation/read, WebSocket upgrade с `inplacex.online.v1`, restart recovery,
физический Android E2E и проверку `RUSSIA/GLOBAL/UNKNOWN`. CI выполняет отдельный
pinned PostgreSQL/backend deploy-v1, restart recovery, deploy-v2 и manual
rollback test; это не заменяет проверку production credentials и VPS network.
