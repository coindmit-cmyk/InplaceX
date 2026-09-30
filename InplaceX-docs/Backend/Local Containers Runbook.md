# Локальные контейнеры и операции PostgreSQL

## Назначение

`ops/compose.yaml` поднимает локальный backend и PostgreSQL. Данные PostgreSQL
сохраняются в Compose-scoped volume `postgres-data`, поэтому разные project name
и worktree не используют одну БД. Обычный `docker compose down` не удаляет
данные.
Backend запускает versioned JDBC-миграции при старте. Прогресс можно проверить в
`inplacex_schema_history`. Full-cycle проверка сравнивает применённые версии со
всеми файлами `V*__*.sql`, поэтому новая миграция не требует ручного списка в
скрипте.

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
секрет: без этой переменной Compose завершится ошибкой. Не добавляйте `.env` в Git. В `.env.example` допустимы только
плейсхолдеры; значения базы, пользователя и порта можно менять для
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

Удаление volume необратимо и выполняется только для точно выбранного Compose
project после проверки `docker compose ps`:

```bash
docker compose --project-directory "$PWD" -f ops/compose.yaml ps
docker compose --project-directory "$PWD" -f ops/compose.yaml down --volumes
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

Создайте custom-format dump вне репозитория или в игнорируемой папке. Скрипт
создаёт файл с приватными правами, валидирует dump и не перезаписывает уже
существующий backup:

```bash
mkdir -p "$HOME/inplacex-backups"
./ops/backup-postgres.sh "$HOME/inplacex-backups/inplacex-$(date +%Y%m%d-%H%M%S).dump"
```

Перед restore остановите запись в локальный стек. Скрипт валидирует dump, сам
останавливает backend и выполняет атомарный `pg_restore`. При ошибке изменения
откатываются транзакцией, а backend остаётся остановленным.

```bash
./ops/restore-postgres.sh "$HOME/inplacex-backups/inplacex-20260725-120000.dump"
curl --fail --silent --show-error http://localhost:8080/ready
```

Для отката миграции одного восстановления БД недостаточно: текущий backend снова
применит forward-only миграции. Поэтому `rollback-postgres.sh` требует и
pre-migration dump, и точный предыдущий backend image, уже доступный локально.

```bash
./ops/rollback-postgres.sh \
  "$HOME/inplacex-backups/pre-migration.dump" \
  "registry.example/inplacex-backend:previous-tested-version"
```

Restore и rollback перезаписывают текущие локальные данные. Перед ними всегда
сначала создайте новый backup. Скрипт rollback сначала проверяет наличие image,
атомарно восстанавливает dump и только затем запускает предыдущий backend без
пересборки.

## Проверка полного цикла

Следующая команда поднимает стек, ждёт `/ready`, проверяет применённую миграцию,
создаёт backup, изменяет контрольную запись, восстанавливает dump и убеждается,
что запись вернулась к исходному значению.

```bash
./ops/verify-local-stack.sh
```

Команда предназначена для изолированной локальной БД: она останавливает и
запускает backend и восстанавливает весь database dump. Скрипт использует
уникальный Compose project, а при завершении удаляет только созданные им
контейнеры, сеть, локальный image и scoped volume. Проверка действительно
вызывает rollback-скрипт с только что собранным совместимым локальным image.
