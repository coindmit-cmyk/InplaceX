# Worker Result INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S31

- Generated: `2026-07-25T19:08:48Z`
- Worker: `auto-worker-5.3`
- Model: `gpt-5.6-terra`
- Branch: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s31/local-containers-migrations-and-operations-runbo`
- Worktree: `/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s31-20260725T185418Z`
- Result: `agent_done`
- Check evidence: `passed`
- Next owner: `Integrator`

## Check Evidence

```json
{
  "ok": true,
  "route": "agent_done",
  "check_status": "passed",
  "integration_status": "pending",
  "reason": "worker output contains explicit passed check_status",
  "negative_markers": [],
  "positive_markers": [
    "passed",
    "git diff --check",
    "check_status=passed"
  ]
}
```

## stdout

```text
Готово: добавлен локальный backend/PostgreSQL стек, операции backup/restore/rollback и русскоязычный runbook.

Изменения:

- [ops/compose.yaml](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s31-20260725T185418Z/ops/compose.yaml) и [Dockerfile](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s31-20260725T185418Z/ops/Dockerfile)
- Скрипты backup/restore/rollback и полной проверки в `ops/`
- [Local Containers Runbook.md](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s31-20260725T185418Z/InplaceX-docs/Backend/Local%20Containers%20Runbook.md)
- Безопасные placeholders в [.env.example](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s31-20260725T185418Z/.env.example)
- Worker report: [WORKER_RESULT…S31](/mnt/d/agent-runtime/worker-worktrees/inplacex/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s31-20260725T185418Z/docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S31_20260725T190700Z.md)

Проверки:

- `COMPOSE_FILE=ops/compose.yaml docker compose config` — passed
- `bash gradlew :InplaceX-backend:test` — passed
- `./ops/verify-local-stack.sh` — passed: health, миграция V1, backup, restore и rollback-проверка
- `git diff --check` — passed

Тестовые Docker-контейнеры, сеть, образ и volume удалены после проверки. `check_status=passed`.

```

## stderr

```text
D="$POSTGRES_PASSWORD" psql --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" \
+  -c "SELECT version, description, installed_at FROM inplacex_schema_history ORDER BY version"'
+```
+
+Миграции являются forward-only: добавляйте новую версию вместо изменения уже
+применённой. Откат достигается восстановлением согласованного backup, а не
+удалением записи из history.
+
+## Backup, restore и rollback
+
+Создайте custom-format dump вне репозитория или в игнорируемой папке:
+
+```bash
+mkdir -p "$HOME/inplacex-backups"
+./ops/backup-postgres.sh "$HOME/inplacex-backups/inplacex-$(date +%Y%m%d-%H%M%S).dump"
+```
+
+Перед restore остановите запись в локальный стек. Скрипт сам останавливает
+backend, выполняет `pg_restore --clean --if-exists`, а затем запускает backend.
+
+```bash
+./ops/restore-postgres.sh "$HOME/inplacex-backups/inplacex-20260725-120000.dump"
+curl --fail --silent --show-error http://localhost:8080/ready
+```
+
+`rollback-postgres.sh` — явный алиас restore: он возвращает базу к снимку,
+созданному до нежелательной миграции или операции.
+
+```bash
+./ops/rollback-postgres.sh "$HOME/inplacex-backups/pre-migration.dump"
+```
+
+Restore и rollback перезаписывают текущие локальные данные. Перед ними всегда
+сначала создайте новый backup.
+
+## Проверка полного цикла
+
+Следующая команда поднимает стек, ждёт `/ready`, проверяет применённую миграцию,
+создаёт backup, изменяет контрольную запись, восстанавливает dump и убеждается,
+что запись вернулась к исходному значению.
+
+```bash
+./ops/verify-local-stack.sh
+```
+
+Команда предназначена для изолированной локальной БД: она останавливает и
+запускает backend и восстанавливает весь database dump. Не запускайте её против
+данных, которые нельзя перезаписывать.
diff --git a/InplaceX-docs/Backend/README.md b/InplaceX-docs/Backend/README.md
index 5f2fd811e163075815326f1dab51c99eef49dc00..1963f2e7e09bed54146e8afca4e74ba8c1a2ebc4
--- a/InplaceX-docs/Backend/README.md
+++ b/InplaceX-docs/Backend/README.md
@@ -11,3 +11,4 @@
 - cloud save
 - entitlement validation
 - [Ktor Architecture](Ktor%20Architecture.md)
+- [Локальные контейнеры и операции PostgreSQL](Local%20Containers%20Runbook.md)
diff --git a/docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S31_20260725T190700Z.md b/docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S31_20260725T190700Z.md
new file mode 100644
index 0000000000000000000000000000000000000000..e31c488978b35bb49c26c16d79cb5bc47c164d37
--- /dev/null
+++ b/docs/reports/workers/WORKER_RESULT_INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S31_20260725T190700Z.md
@@ -0,0 +1,37 @@
+# Worker result: local containers, migrations and operations runbook
+
+- Task: `INTAKE-DESIGN-PD-INPLACEX-FINAL-20260725-V002-S31`
+- Branch: `AiStudio/Agent/worker/aistudio-manual/auto-worker-5.3/intake-design-pd-inplacex-final-20260725-v002-s31/local-containers-migrations-and-operations-runbo`
+- Result: `agent_done` proposed; central runner must publish the worker branch and emit `integration_requested`.
+
+## Delivered
+
+- Added `ops/compose.yaml` with a persistent named PostgreSQL volume, database
+  and backend health checks, explicit startup dependency, and environment-only
+  database credentials.
+- Added a multi-stage backend Docker image that builds the Gradle application
+  with Java 21 and runs its Java 11-compatible distribution.
+- Added executable backup, restore, rollback, and local stack verification
+  scripts. Rollback deliberately restores a pre-migration dump because the
+  JDBC migration sequence is forward-only.
+- Added a Russian operational runbook and safe placeholders to `.env.example`.
+
+## Verification
+
+- `COMPOSE_FILE=ops/compose.yaml docker compose config` — passed.
+- `bash gradlew :InplaceX-backend:test` — passed.
+- `./ops/verify-local-stack.sh` — passed: containers became healthy, migration
+  `V1` was found, a custom-format dump was restored, and the control value was
+  returned to its pre-backup value.
+- `git diff --check` — passed.
+
+The temporary Docker containers, network, image, and `inplacex-postgres-data`
+volume created for the verification were removed with
+`docker compose --project-directory "$PWD" -f ops/compose.yaml down --volumes --rmi local`.
+
+## Freshness note
+
+`origin/develop` advanced by one commit after the immutable task base. Its
+changed paths were only `AiStudio/Task_manager/agent_locks.json` and
+`AiStudio/Task_manager/task_queue.json`; no implementation or packet source
+path overlapped this task.
diff --git a/ops/Dockerfile b/ops/Dockerfile
new file mode 100644
index 0000000000000000000000000000000000000000..795db81cde56c5cab6ddebdec54c34e2176fcd5a
--- /dev/null
+++ b/ops/Dockerfile
@@ -0,0 +1,27 @@
+FROM gradle:9.3.1-jdk21 AS build
+
+WORKDIR /workspace
+
+COPY gradle gradle
+COPY gradlew settings.gradle.kts build.gradle.kts ./
+COPY InplaceX-bot-core InplaceX-bot-core
+COPY InplaceX-logging InplaceX-logging
+COPY InplaceX-test-support InplaceX-test-support
+COPY InplaceX-backend InplaceX-backend
+COPY InplaceX-android/app InplaceX-android/app
+
+RUN ./gradlew --no-daemon :InplaceX-backend:installDist
+
+FROM eclipse-temurin:11-jre-jammy
+
+RUN apt-get update \
+    && apt-get install --yes --no-install-recommends curl \
+    && rm -rf /var/lib/apt/lists/*
+
+WORKDIR /app
+
+COPY --from=build /workspace/InplaceX-backend/build/install/InplaceX-backend/ ./
+
+EXPOSE 8080
+
+ENTRYPOINT ["/app/bin/InplaceX-backend"]
diff --git a/ops/backup-postgres.sh b/ops/backup-postgres.sh
new file mode 100644
index 0000000000000000000000000000000000000000..8d1e63c5310a18d09530a0111e746202f82b137b
--- /dev/null
+++ b/ops/backup-postgres.sh
@@ -0,0 +1,14 @@
+#!/usr/bin/env bash
+set -euo pipefail
+
+if [[ $# -ne 1 ]]; then
+    echo "Usage: $0 <backup-file.dump>" >&2
+    exit 64
+fi
+
+repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
+compose=(docker compose --project-directory "$repository_root" -f "$repository_root/ops/compose.yaml")
+
+"${compose[@]}" exec -T postgres sh -ec \
+    'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump --format=custom --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
+    > "$1"
diff --git a/ops/compose.yaml b/ops/compose.yaml
new file mode 100644
index 0000000000000000000000000000000000000000..ee20e9b6c581dbf2493160aa6486e5fa9afd423d
--- /dev/null
+++ b/ops/compose.yaml
@@ -0,0 +1,42 @@
+services:
+  postgres:
+    image: postgres:16-alpine
+    environment:
+      POSTGRES_DB: ${INPLACEX_POSTGRES_DB:-inplacex}
+      POSTGRES_USER: ${INPLACEX_POSTGRES_USER:-inplacex}
+      POSTGRES_PASSWORD: ${INPLACEX_POSTGRES_PASSWORD:-change-me-before-use}
+    volumes:
+      - postgres-data:/var/lib/postgresql/data
+    healthcheck:
+      test: ["CMD-SHELL", "pg_isready -U $$POSTGRES_USER -d $$POSTGRES_DB"]
+      interval: 5s
+      timeout: 3s
+      retries: 20
+      start_period: 5s
+
+  backend:
+    build:
+      context: .
+      dockerfile: ops/Dockerfile
+    depends_on:
+      postgres:
+        condition: service_healthy
+    environment:
+      INPLACEX_BACKEND_ENVIRONMENT: local
+      INPLACEX_BACKEND_HOST: 0.0.0.0
+      INPLACEX_BACKEND_PORT: 8080
+      INPLACEX_DATABASE_JDBC_URL: jdbc:postgresql://postgres:5432/${INPLACEX_POSTGRES_DB:-inplacex}
+      INPLACEX_DATABASE_USERNAME: ${INPLACEX_POSTGRES_USER:-inplacex}
+      INPLACEX_DATABASE_PASSWORD: ${INPLACEX_POSTGRES_PASSWORD:-change-me-before-use}
+    ports:
+      - "${INPLACEX_BACKEND_PORT:-8080}:8080"
+    healthcheck:
+      test: ["CMD-SHELL", "curl --fail --silent --show-error http://localhost:8080/ready >/dev/null"]
+      interval: 5s
+      timeout: 3s
+      retries: 20
+      start_period: 20s
+
+volumes:
+  postgres-data:
+    name: ${INPLACEX_POSTGRES_VOLUME:-inplacex-postgres-data}
diff --git a/ops/restore-postgres.sh b/ops/restore-postgres.sh
new file mode 100644
index 0000000000000000000000000000000000000000..dfa7b7a4097e61e455ef78095328926a36c801fd
--- /dev/null
+++ b/ops/restore-postgres.sh
@@ -0,0 +1,20 @@
+#!/usr/bin/env bash
+set -euo pipefail
+
+if [[ $# -ne 1 || ! -f $1 ]]; then
+    echo "Usage: $0 <backup-file.dump>" >&2
+    exit 64
+fi
+
+repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
+compose=(docker compose --project-directory "$repository_root" -f "$repository_root/ops/compose.yaml")
+backup_file="$1"
+
+"${compose[@]}" stop backend
+if ! "${compose[@]}" exec -T postgres sh -ec \
+    'PGPASSWORD="$POSTGRES_PASSWORD" pg_restore --clean --if-exists --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
+    < "$backup_file"; then
+    "${compose[@]}" up -d backend
+    exit 1
+fi
+"${compose[@]}" up -d backend
diff --git a/ops/rollback-postgres.sh b/ops/rollback-postgres.sh
new file mode 100644
index 0000000000000000000000000000000000000000..3ae3bb6ba04f20a53c843da284fe0beae10e9dcb
--- /dev/null
+++ b/ops/rollback-postgres.sh
@@ -0,0 +1,6 @@
+#!/usr/bin/env bash
+set -euo pipefail
+
+repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
+
+exec "$repository_root/ops/restore-postgres.sh" "$@"
diff --git a/ops/verify-local-stack.sh b/ops/verify-local-stack.sh
new file mode 100644
index 0000000000000000000000000000000000000000..e79a8605693ac7ce0c388485468bc2d2c542c36f
--- /dev/null
+++ b/ops/verify-local-stack.sh
@@ -0,0 +1,59 @@
+#!/usr/bin/env bash
+set -euo pipefail
+
+repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
+compose=(docker compose --project-directory "$repository_root" -f "$repository_root/ops/compose.yaml")
+temporary_directory="$(mktemp -d)"
+backup_file="$temporary_directory/inplacex-local-stack.dump"
+
+cleanup() {
+    rm -rf "$temporary_directory"
+}
+trap cleanup EXIT
+
+"${compose[@]}" up --build --detach
+
+for attempt in {1..30}; do
+    if "${compose[@]}" exec -T backend curl --fail --silent --show-error http://localhost:8080/ready >/dev/null; then
+        break
+    fi
+    if [[ $attempt -eq 30 ]]; then
+        echo "Backend did not become ready within 150 seconds." >&2
+        exit 1
+    fi
+    sleep 5
+done
+
+"${compose[@]}" exec -T postgres sh -ec \
+    'PGPASSWORD="$POSTGRES_PASSWORD" psql --set=ON_ERROR_STOP=1 --tuples-only --no-align --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -c "SELECT version FROM inplacex_schema_history WHERE version = '\''1'\''"' \
+    | grep -qx '1'
+
+"${compose[@]}" exec -T postgres sh -ec \
+    'PGPASSWORD="$POSTGRES_PASSWORD" psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -c "CREATE TABLE IF NOT EXISTS ops_restore_verification (value TEXT NOT NULL); TRUNCATE ops_restore_verification; INSERT INTO ops_restore_verification(value) VALUES ('\''before-restore'\'');"'
+
+"$repository_root/ops/backup-postgres.sh" "$backup_file"
+
+"${compose[@]}" exec -T postgres sh -ec \
+    'PGPASSWORD="$POSTGRES_PASSWORD" psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -c "UPDATE ops_restore_verification SET value = '\''after-backup'\'';"'
+
+"$repository_root/ops/restore-postgres.sh" "$backup_file"
+
+for attempt in {1..30}; do
+    if "${compose[@]}" exec -T backend curl --fail --silent --show-error http://localhost:8080/ready >/dev/null; then
+        break
+    fi
+    if [[ $attempt -eq 30 ]]; then
+        echo "Backend did not become ready after restore within 150 seconds." >&2
+        exit 1
+    fi
+    sleep 5
+done
+
+"${compose[@]}" exec -T postgres sh -ec \
+    'PGPASSWORD="$POSTGRES_PASSWORD" psql --set=ON_ERROR_STOP=1 --tuples-only --no-align --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -c "SELECT value FROM ops_restore_verification"' \
+    | grep -qx 'before-restore'
+
+"${compose[@]}" exec -T postgres sh -ec \
+    'PGPASSWORD="$POSTGRES_PASSWORD" psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -c "DROP TABLE ops_restore_verification"'
+
+echo "Local stack verification passed."

tokens used
144 942

```
