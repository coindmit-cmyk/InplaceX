#!/usr/bin/env bash
set -euo pipefail

base_url="${1:-}"
expected_market="${2:-}"

if [[ ! "$base_url" =~ ^https://[^/]+(:[0-9]+)?$ ]]; then
    echo "Usage: $0 https://backend.example [RUSSIA|GLOBAL|UNKNOWN]" >&2
    exit 2
fi
if [[ -n "$expected_market" && ! "$expected_market" =~ ^(RUSSIA|GLOBAL|UNKNOWN)$ ]]; then
    echo "Expected market must be RUSSIA, GLOBAL, or UNKNOWN." >&2
    exit 2
fi

curl --fail --silent --show-error --connect-timeout 5 --max-time 15 "${base_url}/health" \
    | grep -qx '{"status":"ok"}'
curl --fail --silent --show-error --connect-timeout 5 --max-time 15 "${base_url}/ready" \
    | grep -qx '{"status":"ready"}'

headers_file="$(mktemp)"
body_file="$(mktemp)"
cleanup() {
    rm -f -- "$headers_file" "$body_file"
}
trap cleanup EXIT

curl \
    --fail \
    --silent \
    --show-error \
    --connect-timeout 5 \
    --max-time 15 \
    --dump-header "$headers_file" \
    --output "$body_file" \
    "${base_url}/api/v1/runtime/ad-market"

market="$(sed -nE 's/^\{"market":"(RUSSIA|GLOBAL|UNKNOWN)"\}$/\1/p' "$body_file")"
if [[ -z "$market" ]]; then
    echo "Ad market response does not match the bounded contract." >&2
    exit 1
fi
if ! grep -iq '^cache-control:.*no-store' "$headers_file"; then
    echo "Ad market response is missing Cache-Control: no-store." >&2
    exit 1
fi
if ! grep -iq '^content-type:.*application/json' "$headers_file"; then
    echo "Ad market response is missing the JSON content type." >&2
    exit 1
fi
if ! grep -iq '^link:.*<https://db-ip.com>;[[:space:]]*rel="via"' "$headers_file"; then
    echo "Ad market response is missing DB-IP attribution." >&2
    exit 1
fi
if [[ -n "$expected_market" && "$market" != "$expected_market" ]]; then
    echo "Expected ${expected_market}, received ${market}." >&2
    exit 1
fi

echo "Backend health, readiness, and ad market checks passed (${market})."
