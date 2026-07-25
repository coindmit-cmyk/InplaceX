#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! -f $1 ]]; then
    echo "Usage: $0 <backup-file.dump>" >&2
    exit 64
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose=(docker compose --project-directory "$repository_root" -f "$repository_root/ops/compose.yaml")
backup_file="$1"

"${compose[@]}" stop backend
if ! "${compose[@]}" exec -T postgres sh -ec \
    'PGPASSWORD="$POSTGRES_PASSWORD" pg_restore --clean --if-exists --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
    < "$backup_file"; then
    "${compose[@]}" up -d backend
    exit 1
fi
"${compose[@]}" up -d backend
