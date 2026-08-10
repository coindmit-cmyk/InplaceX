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
  digest и fingerprints database password/ключей/GeoIP/runtime config с durable activation либо
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
Durable activation v2 хранит только SHA-256 database password и остальных
секретов, но никогда не их значения. Ротация database password пока намеренно
запрещена: сначала нужен отдельный пакет, который согласованно меняет роль
PostgreSQL, host secret и activation; простая замена файла завершится fail closed.

Registry credential является отдельным одноразовым build input и не входит в
runtime secrets. Подготовьте `/etc/inplacex-online/registry-auth.json` как
`root:root`, mode `0600`, один hard link, под полностью root-owned и не доступной
для записи group/world цепочкой каталогов. JSON имеет только такую форму:

```json
{"auths":{"registry.example":{"auth":"BASE64_USERNAME_COLON_TOKEN"}}}
```

Checklist перед build:

- top-level содержит только `auths`, внутри ровно authority из image tag и
  только inline `auth` с canonical Base64 `username:token`;
- отсутствуют `credsStore`, `credHelpers`, `plugins`, `HttpHeaders`, identity
  tokens и любые дополнительные поля;
- `stat` подтверждает `root:root 0600`, regular file и один link; путь не symlink;
- credential не записывается в Git, `backend.env`, shell arguments, manifest или
  логи; CLI получает только путь к файлу;
- после использования credential удаляется либо ротируется согласно registry
  policy.

### Ротация публичного ключа платформы

Перед заменой сохраните SHA-256 из текущего durable
`verified-activation.env`. После записи нового
`platform-public-key-x509-base64.txt` работающий backend обнаружит изменение и
завершится fail closed. Убедитесь, что старый exact container остановлен, затем
для одного deploy установите сохранённый SHA-256 в
`INPLACEX_ALLOW_PUBLIC_KEY_ROTATION_FROM_SHA256` и запустите
`deploy-backend.sh`. Deploy принимает остановленный container только когда новый
fingerprint отличается от verified activation, acknowledgement точно совпадает
со старым fingerprint, а release/image/container identity остаются прежними.
После успешного smoke уберите acknowledgement из environment. Любая остановка
без точного acknowledgement или любое другое изменение secrets продолжает
блокировать deploy до создания journal и изменения БД.

### Одноразовая миграция activation v1 -> v2

Если на host уже существует корректный `verified-activation.env` версии 1,
rollback и GeoIP rotation намеренно откажутся работать и укажут сначала выполнить
deploy-миграцию. Не переписывайте activation вручную. Для одного deploy при
неизменных secrets, GeoIP, runtime config и public key временно установите:

```text
INPLACEX_ACTIVATION_V1_MIGRATION_ACK=acknowledge-inplacex-activation-v1-to-v2
```

Deploy принимает acknowledgement только при exact activation v1. До journal,
gate и stop он сверяет все v1 identity/fingerprints, immutable image и SHA-256
database password, state key, public key и GeoIP-файла непосредственно внутри
точного работающего backend container с host SHA-256. Нельзя совмещать этот
проход с ротацией ключей, password, GeoIP или runtime config.

Проверенный v1 record остаётся durable authority до smoke нового кандидата;
кандидат получает короткий v2 permit. После smoke deploy атомарно записывает v2
record с database-password fingerprint и отдельно durably отмечает completion в
journal. Повтор после SIGKILL между этими двумя записями принимает только exact
candidate v2 и завершает ту же транзакцию. После успешного deploy немедленно
верните `INPLACEX_ACTIVATION_V1_MIGRATION_ACK=`: оставленный one-time ack будет
отклонён следующим deploy.

Этот deploy является activation compatibility boundary: его receipt помечает
previous image как v1-only, и automatic application rollback через границу
отклоняется до gate/DB mutation. Для возврата нужен отдельный v2-compatible
recovery release; откат durable activation обратно на v1 не выполняется. Новый
compatibility-bound receipt имеет `ROLLBACK_RECEIPT_VERSION=3`; старый v2 receipt
не содержит этого boundary и новым rollback-скриптом не принимается.

## Image и release identity

Backend image собирается только из единожды созданного deterministic tar exact
`HEAD`. Helper запускает Git с `--no-replace-objects` в очищенном окружении и
получает tree/blob bytes через `ls-tree` + `cat-file`, поэтому checkout filters,
ignore и attributes не участвуют. Repo-local `info/attributes` и object
alternates запрещены. Перед и после archive helper доказывает, что его собственный
script, bootstrap, common library, blob exporter и `ops/Dockerfile` побайтно
совпадают с HEAD. Dockerfile читается из того же immutable archive, который
передаётся как build context. После push helper
отдельно читает опубликованные BuildKit provenance и SBOM через `imagetools` и
не создаёт manifest, если хотя бы одна attestation отсутствует:

Builder нельзя запускать через `sudo` из обычного developer/GitHub checkout.
Source должен быть отдельным exact-SHA clone под root-owned, не доступной для
записи group/world цепочкой каталогов (например,
`/var/lib/inplacex-online/release-source-<sha>`). Builder требует обычный
embedded `.git` (не linked worktree), root-owned защищённые Git metadata и
single-link files с Git mode `100755` для builder/common library и `100644` для
bootstrap/archive helper/Dockerfile. CI создаёт такой clone через
`--no-local --no-hardlinks --no-checkout`, затем делает detached checkout
reviewed SHA. Метаданные, inode, bytes и HEAD повторно проверяются непосредственно
перед archive helper, Buildx push, чтением attestations и публикацией manifest.

```bash
sudo install -d -o root -g root -m 0700 /var/lib/inplacex-online/releases
sudo /var/lib/inplacex-online/release-source-<sha>/ops/production/build-backend-release.sh \
  registry.example/mirkori/inplacex-backend:2026.08.07.1 \
  inplacex-backend-2026.08.07.1 \
  /var/lib/inplacex-online/releases/inplacex-backend-2026.08.07.1.json \
  --push \
  --registry-auth-config /etc/inplacex-online/registry-auth.json
```

Input повторно проверяется после подготовки изолированного Docker control plane,
нормализуется и fsync-ится как единственный `config.json` в controlled
`DOCKER_CONFIG`; credential и его hash не печатаются. `--anonymous-loopback`
допустим только для exact `127.0.0.1:<port>` в acknowledged isolated destructive
CI и запрещён для production registry. Manifest destination всегда должен быть
новым путём: временный root-owned файл публикуется в том же каталоге Linux через
atomic `renameat2(RENAME_NOREPLACE)` и directory fsync. Существующие file,
directory и symlink не перезаписываются даже при конкурентном publisher; если
filesystem не поддерживает no-replace rename, builder завершается fail closed.

Из manifest без ручного пересчёта переносятся `image` в
`INPLACEX_BACKEND_IMAGE`, `imageDigest`, `releaseId`, `gitSha` и
`sourceArchiveSha256`; `INPLACEX_RELEASE_MANIFEST_PATH` указывает на
установленный root-owned файл. Локальный image ID и mutable tag доказательством
не являются. PostgreSQL image также задаётся digest-ссылкой.

Dirty/untracked application files не являются build input: используется exact
commit. Но любое отличие working-copy release toolset от HEAD считается
ошибкой, чтобы root не исполнял подменённую release-цепочку.

Новый builder пишет manifest schema v2. Помимо совместимых identity-полей v1,
он сохраняет digest каждого attestation manifest, SHA-256 извлечённых
provenance/SBOM и фактические
`SLSA.buildDefinition.buildType`/`SPDX.spdxVersion`. Deploy
продолжает принимать ранее выпущенный строгий schema-v1 manifest, но новый
release без проверенного schema-v2 attestation evidence не публикуется.
`schemaVersion` принимается только как JSON integer `1` или `2`; boolean и
числа другого типа отклоняются. Destructive CI отдельно формирует из реального
schema-v2 результата строгий положительный schema-v1 fixture и выполняет через
него первый deploy, поэтому compatibility path проверяется исполнением, а не
только отрицательными schema-тестами.

Перед deploy заполните все `REPLACE_*` в `/etc/inplacex-online/backend.env` и
особенно проверьте фактические `INPLACEX_BACKEND_LOOPBACK_PORT`,
`INPLACEX_PUBLIC_HOSTNAME`, `INPLACEX_OPERATOR_NETWORK_CIDR`,
`INPLACEX_RELEASE_STATE_DIRECTORY` и `INPLACEX_DRAIN_TIMEOUT_SECONDS`.

Release entrypoints до lock и любых изменений отказываются работать с
`BASH_ENV`, `ENV`, imported `BASH_FUNC_*`, dynamic-loader/locale overrides,
ambient Docker context/host/config, `BUILDX_CONFIG`, BuildKit source policy,
default platform и дополнительные каталоги CLI plugins; затем
заменяют входной `PATH` на системный allowlist. Production Docker всегда
вызывается через защищённый `/usr/bin/docker` и root-owned
`/var/run/docker.sock`. Перед первым вызовом создаются отдельные root-owned
пустые `HOME` и `DOCKER_CONFIG`, а фактический Compose либо Buildx plugin
берётся из client inventory и связывается с path/inode/SHA-256. На каждом
следующем Docker вызове повторно проверяются inode socket, daemon Server ID,
Docker data-root и, при использовании plugin, его identity. Любая смена
останавливает операцию. Каждый Docker/Buildx subprocess запускается через
`/usr/bin/env -i` только с явными `PATH`, `HOME`, `DOCKER_CONFIG`, `DOCKER_HOST`
и locale; CI-only test variables пропускаются лишь при точном isolated-CI
acknowledgement и совпадении mock Docker path. Не запускайте release через shell
profile/wrapper и не задавайте Docker/Buildx overrides в operator environment.

Destructive CI поднимает второй loopback registry с bcrypt `htpasswd`, передаёт
builder настоящий root-owned auth config, выполняет build/push, raw
`imagetools inspect`, pull и проверку exact `RepoDigest`. Логи, manifest и
сохранённые inspection outputs проверяются на отсутствие raw/base64 credential;
на production этот CI credential не используется.

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
   REST/WebSocket запросов и graceful stop backend; успешный `docker compose
   stop` недостаточен сам по себе — перед mutation повторно проверяется exact
   container и `State.Running=false`;
4. PostgreSQL readiness, фактическое чтение secrets, system identifier,
   `pg_dump` во временный файл, `pg_restore --list`, durable atomic rename и
   SHA-256;
5. bounded Compose start (`--wait-timeout`), migrations, runtime UID smoke и
   exact `/health`, `/ready`, `/meta/release`;
6. durable verified activation, атомарные receipt и
   `latest-inplacex-backend-release.env`;
7. снятие drain/maintenance gates и последующее удаление transaction journal.

При ошибке после snapshot скрипт останавливает candidate, восстанавливает БД и
запускает заранее pulled/verified previous image. Если previous smoke не
проходит, backend остаётся остановленным, gate остаётся включённым, а backup
сохраняется.

Если stop вернул ошибку, container исчез из inspection или остался running,
deploy/rollback не вызывают `pg_dump`, destructive restore либо `DROP DATABASE`.
Automatic recovery mutation также не выполняется: maintenance/drain gates и
transaction journal сохраняются для операторского разбора и безопасного resume.

## Ротация GeoIP без смены release

GeoIP fingerprint является частью verified activation, поэтому прямой вызов
downloader поверх активной MMDB сделает следующий запуск backend
неавторизованным. Для ежемесячного обновления используйте только:

```bash
sudo ops/production/rotate-geoip.sh /etc/inplacex-online/backend.env [YYYY-MM]
```

Скрипт принимает также `--candidate-file <absolute-mmdb>`, если артефакт уже
доставлен на host. Он блокируется тем же release lock, сверяет exact active
release и PostgreSQL system identifier, выполняет bounded drain, атомарно
меняет MMDB и создаёт новое durable activation только после smoke. Backup и
journal позволяют безопасно повторить ту же команду после SIGKILL или reboot;
до подтверждения новый fingerprint не даёт backend запуститься самостоятельно.
Системный timer и порядок установки описаны в
`Advertising Market Operations.md`.

### Одноразовое восстановление legacy checksum

Нормальное значение
`INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK` пустое. Только для известной
истории ровно v1-v8, v1-v9 либо v1-v10 с отсутствующими checksum допустимо временно
установить:

```text
INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK=acknowledge-inplacex-schema-v1-v8
```

Строка acknowledgement сохраняет историческое имя для совместимости, но deploy
принимает только exact последовательность v1-v8, v1-v9 или v1-v10 и хотя бы один
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
GeoIP является отдельно ротируемым runtime artifact: rollback требует, чтобы
текущая MMDB совпадала с текущей durable activation, сохраняет этот fingerprint
при возврате previous release и не откатывает MMDB к deploy-time hash из receipt.
Затем создаётся durable rollback journal, verified activation кандидата
отзывается, создаётся emergency backup и восстанавливается receipt-bound
snapshot. Pointer остаётся `active`, пока предыдущая версия не получит новый
durable activation; только после successful smoke он атомарно становится
`rolled_back`, gate снимается и journal удаляется последним. После SIGKILL или reboot
повтор той же команды продолжает точную фазу journal, а backend без activation
остаётся fail closed. Receipt нельзя использовать второй раз.

Rollback намеренно не переписывает operator-owned
`/etc/inplacex-online/backend.env`. После успеха перенесите в него identity и
manifest предыдущего release до следующего deploy или запуска GeoIP timer;
иначе следующая production-операция корректно остановится на несовпадении
environment с durable activation.

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
