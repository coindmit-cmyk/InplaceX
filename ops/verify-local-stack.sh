#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export COMPOSE_PROJECT_NAME="inplacex-verify-$(date +%s)-$$"
export INPLACEX_POSTGRES_VOLUME="${COMPOSE_PROJECT_NAME}-postgres-data"
export INPLACEX_POSTGRES_PASSWORD="${INPLACEX_POSTGRES_PASSWORD:-local-verification-only}"
export INPLACEX_BACKEND_PORT=0
compose=(docker compose --project-directory "$repository_root" -f "$repository_root/ops/compose.yaml")
temporary_directory="$(mktemp -d)"
backup_file="$temporary_directory/inplacex-local-stack.dump"

cleanup() {
    "${compose[@]}" down --volumes --remove-orphans --rmi local >/dev/null 2>&1 || true
    rm -rf "$temporary_directory"
}
trap cleanup EXIT

"${compose[@]}" up --build --detach

for attempt in {1..30}; do
    if "${compose[@]}" exec -T backend curl --fail --silent --show-error http://localhost:8080/ready >/dev/null; then
        break
    fi
    if [[ $attempt -eq 30 ]]; then
        echo "Backend did not become ready within 150 seconds." >&2
        exit 1
    fi
    sleep 5
done

"${compose[@]}" exec -T postgres sh -ec \
    'PGPASSWORD="$POSTGRES_PASSWORD" psql --set=ON_ERROR_STOP=1 --tuples-only --no-align --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -c "SELECT version FROM inplacex_schema_history ORDER BY version"' \
    | diff -u <(printf '1\n2\n') -

"${compose[@]}" exec -T postgres sh -ec \
    'PGPASSWORD="$POSTGRES_PASSWORD" psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -c "CREATE TABLE IF NOT EXISTS ops_restore_verification (value TEXT NOT NULL); TRUNCATE ops_restore_verification; INSERT INTO ops_restore_verification(value) VALUES ('\''before-restore'\'');"'

"$repository_root/ops/backup-postgres.sh" "$backup_file"

"${compose[@]}" exec -T postgres sh -ec \
    'PGPASSWORD="$POSTGRES_PASSWORD" psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -c "UPDATE ops_restore_verification SET value = '\''after-backup'\'';"'

"$repository_root/ops/restore-postgres.sh" "$backup_file"

for attempt in {1..30}; do
    if "${compose[@]}" exec -T backend curl --fail --silent --show-error http://localhost:8080/ready >/dev/null; then
        break
    fi
    if [[ $attempt -eq 30 ]]; then
        echo "Backend did not become ready after restore within 150 seconds." >&2
        exit 1
    fi
    sleep 5
done

"${compose[@]}" exec -T postgres sh -ec \
    'PGPASSWORD="$POSTGRES_PASSWORD" psql --set=ON_ERROR_STOP=1 --tuples-only --no-align --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -c "SELECT value FROM ops_restore_verification"' \
    | grep -qx 'before-restore'

"${compose[@]}" exec -T postgres sh -ec \
    'PGPASSWORD="$POSTGRES_PASSWORD" psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" -c "DROP TABLE ops_restore_verification"'

echo "Local stack verification passed."
