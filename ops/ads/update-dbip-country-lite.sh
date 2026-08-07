#!/usr/bin/env bash
set -euo pipefail

target_path="${1:-/var/lib/inplacex/geoip/dbip-country-lite.mmdb}"
release_month="${2:-$(date -u +%Y-%m)}"

if [[ ! "$release_month" =~ ^[0-9]{4}-(0[1-9]|1[0-2])$ ]]; then
    echo "Release month must use YYYY-MM format." >&2
    exit 2
fi

target_directory="$(dirname "$target_path")"
target_name="$(basename "$target_path")"
previous_path="${target_path}.previous"
download_url="https://download.db-ip.com/free/dbip-country-lite-${release_month}.mmdb.gz"
temporary_directory="$(mktemp -d)"
archive_path="$temporary_directory/dbip-country-lite.mmdb.gz"
database_path="$temporary_directory/dbip-country-lite.mmdb"

cleanup() {
    rm -rf -- "$temporary_directory"
}
trap cleanup EXIT

mkdir -p -- "$target_directory"
curl \
    --fail \
    --location \
    --proto '=https' \
    --connect-timeout 10 \
    --max-time 120 \
    --retry 3 \
    --retry-all-errors \
    --silent \
    --show-error \
    --output "$archive_path" \
    "$download_url"
gzip --test "$archive_path"
gzip --decompress --stdout "$archive_path" > "$database_path"

database_size="$(wc -c < "$database_path")"
if (( database_size < 1048576 || database_size > 67108864 )); then
    echo "Downloaded database size is outside the expected 1-64 MiB range." >&2
    exit 1
fi

chmod 0644 "$database_path"
mv -- "$database_path" "$target_directory/.${target_name}.new"
if [[ -f "$target_path" ]]; then
    cp -- "$target_path" "$target_directory/.${target_name}.previous.new"
    chmod 0644 "$target_directory/.${target_name}.previous.new"
    mv -- "$target_directory/.${target_name}.previous.new" "$previous_path"
fi
mv -- "$target_directory/.${target_name}.new" "$target_path"

echo "Installed DB-IP Country Lite ${release_month} at ${target_path}."
if [[ -f "$previous_path" ]]; then
    echo "Previous database is available at ${previous_path}."
fi
echo "Restart the InplaceX backend so the new database is opened."
