#!/usr/bin/env bash
set -euo pipefail

target_path="${1:-/var/lib/inplacex/geoip/dbip-country-lite.mmdb}"
previous_path="${target_path}.previous"
target_directory="$(dirname "$target_path")"
target_name="$(basename "$target_path")"
rollback_path="$target_directory/.${target_name}.rollback.new"

if [[ ! -f "$previous_path" ]]; then
    echo "Previous DB-IP database does not exist at ${previous_path}." >&2
    exit 1
fi

cp -- "$previous_path" "$rollback_path"
chmod 0644 "$rollback_path"
mv -- "$rollback_path" "$target_path"

echo "Restored ${target_path} from ${previous_path}."
echo "Restart the InplaceX backend so the restored database is opened."
