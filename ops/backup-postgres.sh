#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <backup-file.dump>" >&2
    exit 64
fi

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose=(docker compose --project-directory "$repository_root" -f "$repository_root/ops/compose.yaml")
backup_file="$1"
backup_directory="$(cd "$(dirname "$backup_file")" && pwd)"
backup_name="$(basename "$backup_file")"

if [[ -e $backup_file ]]; then
    echo "Refusing to overwrite existing backup: $backup_file" >&2
    exit 73
fi

temporary_file="$(mktemp --tmpdir="$backup_directory" ".${backup_name}.tmp.XXXXXX")"

cleanup() {
    rm -f -- "$temporary_file"
}
trap cleanup EXIT

"${compose[@]}" exec -T postgres sh -ec \
    'PGPASSWORD="$POSTGRES_PASSWORD" pg_dump --format=custom --no-owner --no-privileges --username="$POSTGRES_USER" --dbname="$POSTGRES_DB"' \
    > "$temporary_file"

"${compose[@]}" exec -T postgres pg_restore --list < "$temporary_file" >/dev/null
mv -- "$temporary_file" "$backup_file"
trap - EXIT
