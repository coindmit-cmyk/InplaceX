# Локальные контейнеры и операции PostgreSQL

## Назначение

`ops/compose.yaml` поднимает локальный backend и PostgreSQL. Данные PostgreSQL
сохраняются в именованном Docker volume `inplacex-postgres-data` (или в имени из
`INPLACEX_POSTGRES_VOLUME`), поэтому `docker compose down` не удаляет их.
Backend запускает versioned JDBC-миграции при старте. Прогресс можно проверить в
`inplacex_schema_history`; актуальная последовательность содержит версии `1` и
`2`.

Этот стек предназначен только для локальной разработки. Он не является
разрешением на VPS-развёртывание, публикацию портов в интернет или хранение
секретов в репозитории.

## Подготовка

Нужны Docker Engine с Compose v2 и Java 21 для локальных Gradle-проверок.

```bash
umask 077
cp .env.example .env
```

В `.env` обязательно замените `INPLACEX_POSTGRES_PASSWORD` на локальный
секрет. Не добавляйте `.env` в Git. В `.env.example` допустимы только
плейсхолдеры; значения базы, пользователя, volume и порта можно менять для
изолированного локального запуска.

## Запуск и проверка

Все команды выполняются из корня репозитория.

```bash
docker compose --project-directory "$PWD" -f ops/compose.yaml config
docker compose --project-directory "$PWD" -f ops/compose.yaml up --build -d
docker compose --project-directory "$PWD" -f ops/compose.yaml ps
curl --fail --silent --show-error http://localhost:8080/health
curl --fail --silent --show-error http://localhost:8080/ready
```

`postgres` считается готовым после `pg_isready`; `backend` — после успешного
ответа `GET /ready`. `GET /health` проверяет живость процесса, а `/ready`
предназначен для готовности к приёму запросов.
Порт backend по умолчанию публикуется только на `127.0.0.1`.

Остановить стек, не трогая данные:

```bash
docker compose --project-directory "$PWD" -f ops/compose.yaml down
```

Удаление named volume необратимо и выполняется только при осознанном сбросе
локальной среды:

```bash
docker volume rm "${INPLACEX_POSTGRES_VOLUME:-inplacex-postgres-data}"
```

## Миграции

Миграции применяются при старте backend и ведут историю в
`inplacex_schema_history`. Повторный запуск не должен применять уже записанную
версию повторно.

```bash
docker compose --project-directory "$PWD" -f ops/compose.yaml restart backend
docker compose --project-directory "$PWD" -f ops/compose.yaml exec postgres sh -ec \
  'PGPASSWORD="$POSTGRES_PASSWORD" psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" \
  -c "SELECT version, description, installed_at FROM inplacex_schema_history ORDER BY version"'
```

Миграции являются forward-only: добавляйте новую версию вместо изменения уже
применённой. Откат достигается восстановлением согласованного backup, а не
удалением записи из history.

## Backup, restore и rollback

Создайте custom-format dump вне репозитория или в игнорируемой папке:

```bash
mkdir -p "$HOME/inplacex-backups"
./ops/backup-postgres.sh "$HOME/inplacex-backups/inplacex-$(date +%Y%m%d-%H%M%S).dump"
```

Перед restore остановите запись в локальный стек. Скрипт сам останавливает
backend, выполняет `pg_restore --clean --if-exists`, а затем запускает backend.

```bash
./ops/restore-postgres.sh "$HOME/inplacex-backups/inplacex-20260725-120000.dump"
curl --fail --silent --show-error http://localhost:8080/ready
```

`rollback-postgres.sh` — явный алиас restore: он возвращает базу к снимку,
созданному до нежелательной миграции или операции.

```bash
./ops/rollback-postgres.sh "$HOME/inplacex-backups/pre-migration.dump"
```

Restore и rollback перезаписывают текущие локальные данные. Перед ними всегда
сначала создайте новый backup.

## Проверка полного цикла

Следующая команда поднимает стек, ждёт `/ready`, проверяет применённую миграцию,
создаёт backup, изменяет контрольную запись, восстанавливает dump и убеждается,
что запись вернулась к исходному значению.

```bash
./ops/verify-local-stack.sh
```

Команда предназначена для изолированной локальной БД: она останавливает и
запускает backend и восстанавливает весь database dump. Скрипт использует
уникальные Compose project и volume, а при завершении удаляет только созданные
им контейнеры, сеть, локальный image и volume.
