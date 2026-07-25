#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <pre-migration-backup.dump> <previous-backend-image>" >&2
    exit 64
fi

backup_file="$1"
export INPLACEX_BACKEND_IMAGE="$2"
export INPLACEX_START_BACKEND_AFTER_RESTORE=0
compose=(docker compose --project-directory "$repository_root" -f "$repository_root/ops/compose.yaml")

docker image inspect "$INPLACEX_BACKEND_IMAGE" >/dev/null
"$repository_root/ops/restore-postgres.sh" "$backup_file"
"${compose[@]}" up -d --no-build backend
