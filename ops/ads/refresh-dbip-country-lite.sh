#!/usr/bin/env bash
set -euo pipefail

service_name="${1:-${INPLACEX_AD_MARKET_SERVICE:-}}"
database_path="${INPLACEX_AD_MARKET_DB_PATH:-/var/lib/inplacex/geoip/dbip-country-lite.mmdb}"
backend_url="${INPLACEX_AD_MARKET_INTERNAL_URL:-http://127.0.0.1:18080}"
client_ip_header="${INPLACEX_AD_MARKET_CLIENT_IP_HEADER:-X-InplaceX-Client-IP}"
script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
update_script="$script_directory/update-dbip-country-lite.sh"
rollback_script="$script_directory/rollback-dbip-country-lite.sh"

if [[ ! "$service_name" =~ ^[A-Za-z0-9_.@-]+\.service$ ]]; then
    echo "Pass a concrete systemd backend service name ending in .service." >&2
    exit 2
fi
if [[ ! "$backend_url" =~ ^http://127\.0\.0\.1:[0-9]+$ ]]; then
    echo "The internal backend URL must use loopback HTTP and an explicit port." >&2
    exit 2
fi
if [[ ! "$client_ip_header" =~ ^[A-Za-z0-9-]{1,64}$ ]]; then
    echo "The trusted client IP header name is invalid." >&2
    exit 2
fi
if [[ ! -x "$update_script" || ! -x "$rollback_script" ]]; then
    echo "GeoIP update and rollback scripts must be executable beside this script." >&2
    exit 1
fi

wait_for_backend() {
    local attempt
    for attempt in $(seq 1 30); do
        if curl \
            --fail \
            --silent \
            --show-error \
            --connect-timeout 1 \
            --max-time 2 \
            "$backend_url/health" \
            | grep -qx '{"status":"ok"}'; then
            return 0
        fi
        if ! systemctl is-active --quiet "$service_name"; then
            return 1
        fi
        sleep 1
    done
    return 1
}

verify_backend() {
    local headers_file
    local body_file
    headers_file="$(mktemp)"
    body_file="$(mktemp)"

    curl \
        --fail \
        --silent \
        --show-error \
        --connect-timeout 2 \
        --max-time 5 \
        "$backend_url/ready" \
        | grep -qx '{"status":"ready"}' || {
            rm -f -- "$headers_file" "$body_file"
            return 1
        }
    curl \
        --fail \
        --silent \
        --show-error \
        --connect-timeout 2 \
        --max-time 5 \
        --header "$client_ip_header: 8.8.8.8" \
        --dump-header "$headers_file" \
        --output "$body_file" \
        "$backend_url/api/v1/runtime/ad-market" || {
            rm -f -- "$headers_file" "$body_file"
            return 1
        }
    grep -qx '{"market":"GLOBAL"}' "$body_file" \
        && grep -iq '^cache-control:.*no-store' "$headers_file" \
        && grep -iq '^content-type:.*application/json' "$headers_file" \
        && grep -iq '^link:.*<https://db-ip.com>;[[:space:]]*rel="via"' "$headers_file" || {
            rm -f -- "$headers_file" "$body_file"
            return 1
        }
    curl \
        --fail \
        --silent \
        --show-error \
        --connect-timeout 2 \
        --max-time 5 \
        --header "$client_ip_header: 77.88.8.8" \
        "$backend_url/api/v1/runtime/ad-market" \
        | grep -qx '{"market":"RUSSIA"}' || {
            rm -f -- "$headers_file" "$body_file"
            return 1
        }

    rm -f -- "$headers_file" "$body_file"
}

restart_and_verify() {
    systemctl restart "$service_name" \
        && wait_for_backend \
        && verify_backend
}

"$update_script" "$database_path"
if restart_and_verify; then
    echo "GeoIP database refresh and backend verification passed."
    exit 0
fi

echo "GeoIP verification failed; restoring the previous database." >&2
if "$rollback_script" "$database_path" && restart_and_verify; then
    echo "Previous GeoIP database restored and verified." >&2
else
    echo "Automatic GeoIP recovery failed; backend needs operator attention." >&2
fi
exit 1
