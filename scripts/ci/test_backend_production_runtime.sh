#!/usr/bin/env bash
# shellcheck disable=SC2016
set -euo pipefail
umask 077

[[ ${EUID:-$(id -u)} -eq 0 ]] || {
    echo "Production integration test must run as root." >&2
    exit 77
}
[[ "${INPLACEX_PRODUCTION_INTEGRATION_TEST_ACK:-}" == "isolated-ci-host" ]] || {
    echo "Refusing production integration test without isolated-ci-host acknowledgement." >&2
    exit 77
}

for command_name in base64 curl docker git nginx openssl python3 sha256sum ss stat sync; do
    command -v "$command_name" >/dev/null || {
        echo "Missing integration-test command: $command_name" >&2
        exit 69
    }
done

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
production_directory="$repository_root/ops/production"
test_id="${GITHUB_RUN_ID:-$$}"
[[ "$test_id" =~ ^[0-9]+$ ]] || test_id="$$"
test_root="/var/lib/inplacex-production-ci-$test_id"
[[ "$test_root" =~ ^/var/lib/inplacex-production-ci-[0-9]+$ ]] || exit 70
registry_name="inplacex-production-registry-$test_id"
project_name="inplacex-production-ci-$test_id"
postgres_volume="inplacex-production-ci-postgres-$test_id"
registry_port=5011
backend_port=18081
nginx_port=443
secret_gid=21081
git_sha="$(git -C "$repository_root" rev-parse HEAD)"
source_archive_sha256="$(sha256sum "$repository_root/ops/Dockerfile" | awk '{print $1}')"
postgres_image=""
env_file="$test_root/backend.env"
backup_directory="$test_root/backups"
secret_directory="$test_root/secrets"
release_state_directory="$test_root/release-state"
geoip_file="$test_root/geoip-placeholder.mmdb"
private_key="$test_root/platform-private.pem"
tls_private_key="$test_root/nginx-private.pem"
tls_certificate="$test_root/nginx-certificate.pem"
public_hostname="inplacex-ci.invalid"
operator_network_cidr="127.0.0.1/32"
compose_file="$production_directory/compose.yaml"
nginx_origin="https://$public_hostname:$nginx_port"
nginx_curl=(curl --insecure --resolve "$public_hostname:$nginx_port:127.0.0.1")

nginx_files=(
    /etc/nginx/snippets/inplacex-online-maintenance-gate.conf
    /etc/nginx/snippets/inplacex-online-rest-rate-limit.conf
    /etc/nginx/snippets/inplacex-online-websocket-rate-limit.conf
    /etc/nginx/snippets/inplacex-online-rest-proxy.conf
    /etc/nginx/snippets/inplacex-ad-market-proxy.conf
    /etc/nginx/snippets/inplacex-online.locations.conf
    /etc/nginx/conf.d/inplacex-online-rate-zones.conf
    /etc/nginx/conf.d/inplacex-online-ci-server.conf
)
for nginx_file in "${nginx_files[@]}"; do
    [[ ! -e "$nginx_file" ]] || {
        echo "Integration test refuses to overwrite nginx state: $nginx_file" >&2
        exit 73
    }
done
[[ ! -e "$test_root" ]] || {
    echo "Integration test root already exists: $test_root" >&2
    exit 73
}
[[ ! -e /run/inplacex-online ]] || {
    echo "Integration test refuses existing InplaceX runtime state." >&2
    exit 73
}
[[ ! -e /run/lock/mirkori-games/inplacex-online-release.lock ]] || {
    echo "Integration test refuses an existing InplaceX release lock." >&2
    exit 73
}
if ss -H -ltn | awk '{print $4}' | grep -Eq '(^|:)443$'; then
    echo "Integration test requires an unused loopback TLS port 443." >&2
    exit 75
fi

cleanup() {
    local status=$?
    trap - EXIT
    if [[ -f "$env_file" ]]; then
        if [[ "$status" -ne 0 ]]; then
            docker compose --env-file "$env_file" --project-directory "$repository_root" \
                -f "$compose_file" ps --all >&2 || true
            docker compose --env-file "$env_file" --project-directory "$repository_root" \
                -f "$compose_file" logs --no-color --tail 200 backend postgres >&2 || true
        fi
        docker compose --env-file "$env_file" --project-directory "$repository_root" \
            -f "$compose_file" down --remove-orphans >/dev/null 2>&1 || true
    fi
    docker rm -f "$registry_name" >/dev/null 2>&1 || true
    docker volume rm "$postgres_volume" >/dev/null 2>&1 || true
    rm -f -- "${nginx_files[@]}"
    nginx -t >/dev/null 2>&1 && nginx -s reload >/dev/null 2>&1 || true
    if [[ -d /run/inplacex-online && ! -L /run/inplacex-online &&
        "$(stat -c '%u' -- /run/inplacex-online 2>/dev/null || true)" == "0" ]]; then
        rm -rf -- /run/inplacex-online
    fi
    rm -f -- /run/lock/mirkori-games/inplacex-online-release.lock
    rmdir -- /run/lock/mirkori-games >/dev/null 2>&1 || true
    rm -rf -- "$test_root"
    exit "$status"
}
trap cleanup EXIT

install -d -o root -g root -m 0700 "$test_root" "$backup_directory"
install -d -o root -g "$secret_gid" -m 0750 "$secret_directory"
printf 'integration-database-password-%s\n' "$test_id" > "$secret_directory/database-password.txt"
openssl rand -base64 32 > "$secret_directory/online-state-key-base64.txt"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$private_key" >/dev/null 2>&1
openssl pkey -in "$private_key" -pubout -outform DER 2>/dev/null |
    base64 -w0 > "$secret_directory/platform-public-key-x509-base64.txt"
printf '\n' >> "$secret_directory/platform-public-key-x509-base64.txt"
chown root:"$secret_gid" "$secret_directory"/*.txt
chmod 0640 "$secret_directory"/*.txt
printf 'country-header-mode-does-not-read-this-file\n' > "$geoip_file"
chown root:root "$geoip_file"
chmod 0644 "$geoip_file"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
    -subj "/CN=$public_hostname" \
    -addext "subjectAltName=DNS:$public_hostname" \
    -keyout "$tls_private_key" -out "$tls_certificate" >/dev/null 2>&1
chown root:root "$tls_private_key" "$tls_certificate"
chmod 0600 "$tls_private_key"
chmod 0644 "$tls_certificate"

install -o root -g root -m 0644 \
    "$production_directory/inplacex-online-maintenance-gate.conf" \
    /etc/nginx/snippets/inplacex-online-maintenance-gate.conf
install -o root -g root -m 0644 \
    "$production_directory/inplacex-online-rest-rate-limit.conf" \
    /etc/nginx/snippets/inplacex-online-rest-rate-limit.conf
install -o root -g root -m 0644 \
    "$production_directory/inplacex-online-websocket-rate-limit.conf" \
    /etc/nginx/snippets/inplacex-online-websocket-rate-limit.conf
install -o root -g root -m 0644 \
    "$production_directory/inplacex-online-rest-proxy.conf" \
    /etc/nginx/snippets/inplacex-online-rest-proxy.conf
install -o root -g root -m 0644 \
    "$repository_root/ops/ads/nginx-ad-market-proxy.conf" \
    /etc/nginx/snippets/inplacex-ad-market-proxy.conf
install -o root -g root -m 0644 \
    "$production_directory/inplacex-online-rate-zones.conf" \
    /etc/nginx/conf.d/inplacex-online-rate-zones.conf
bash "$production_directory/render-nginx-config.sh" \
    "$backend_port" "$operator_network_cidr" /etc/nginx/snippets/inplacex-online.locations.conf
printf '%s\n' \
    'server {' \
    "    listen 127.0.0.1:$nginx_port ssl;" \
    "    server_name $public_hostname;" \
    "    ssl_certificate $tls_certificate;" \
    "    ssl_certificate_key $tls_private_key;" \
    '    ssl_protocols TLSv1.2 TLSv1.3;' \
    '    include /etc/nginx/snippets/inplacex-online.locations.conf;' \
    '}' > /etc/nginx/conf.d/inplacex-online-ci-server.conf
chmod 0644 /etc/nginx/conf.d/inplacex-online-ci-server.conf
nginx -t
nginx -s reload >/dev/null 2>&1 || nginx

docker run --detach --name "$registry_name" \
    --publish "127.0.0.1:$registry_port:5000" registry:2 >/dev/null
for _ in {1..30}; do
    curl --fail --silent "http://127.0.0.1:$registry_port/v2/" >/dev/null && break
    sleep 1
done
curl --fail --silent "http://127.0.0.1:$registry_port/v2/" >/dev/null

docker pull postgres:16-alpine >/dev/null
postgres_image="$(docker image inspect --format '{{index .RepoDigests 0}}' postgres:16-alpine)"
[[ "$postgres_image" =~ @sha256:[0-9a-f]{64}$ ]]
docker volume create \
    --label com.mirkori.product=inplacex \
    --label com.mirkori.component=online-postgres \
    --label com.mirkori.managed=true \
    "$postgres_volume" >/dev/null

build_release_image() {
    local release_id="$1"
    local image_tag="127.0.0.1:$registry_port/inplacex-backend:$release_id"
    local immutable_image manifest_path
    docker build \
        --file "$repository_root/ops/Dockerfile" \
        --build-arg "INPLACEX_BUILD_VERSION=$release_id" \
        --build-arg "INPLACEX_BUILD_REVISION=$git_sha" \
        --build-arg "INPLACEX_SOURCE_ARCHIVE_SHA256=$source_archive_sha256" \
        --tag "$image_tag" \
        "$repository_root" >/dev/null
    docker push "$image_tag" >/dev/null
    immutable_image="$(docker image inspect --format '{{index .RepoDigests 0}}' "$image_tag")"
    [[ "$immutable_image" =~ @sha256:[0-9a-f]{64}$ ]]
    manifest_path="$test_root/$release_id.manifest.json"
    python3 - "$manifest_path" "$release_id" "$git_sha" "$source_archive_sha256" \
        "$immutable_image" "${immutable_image##*@}" <<'PY'
import json
import pathlib
import sys

manifest = {
    "schemaVersion": 1,
    "component": "inplacex-online-backend",
    "releaseId": sys.argv[2],
    "gitSha": sys.argv[3],
    "sourceArchiveSha256": sys.argv[4],
    "image": sys.argv[5],
    "imageDigest": sys.argv[6],
    "builderBase": "gradle:9.3.1-jdk21@sha256:f3784cc59d7fbab1e0ddb09c4cd082f13e16d3fb8c50b7922b7aeae8e9507da5",
    "runtimeBase": "eclipse-temurin:11-jre-jammy@sha256:e8acde9cc75b96765f005857cfeb7f826409177482c3f70400d5a94328689d56",
    "attestations": ["slsa-provenance-mode-max", "spdx-sbom"],
}
pathlib.Path(sys.argv[1]).write_text(
    json.dumps(manifest, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY
    chown root:root "$manifest_path"
    chmod 0600 "$manifest_path"
    sync -f "$manifest_path"
    sync -f "$test_root"
    printf '%s\n' "$immutable_image"
}

write_environment() {
    local release_id="$1"
    local backend_image="$2"
    local initial_deploy="$3"
    local image_digest="${backend_image##*@}"
    local manifest_path="$test_root/$release_id.manifest.json"
    [[ -f "$manifest_path" ]]
    {
        printf 'COMPOSE_PROJECT_NAME=%s\n' "$project_name"
        printf 'INPLACEX_BACKEND_IMAGE=%s\n' "$backend_image"
        printf 'INPLACEX_POSTGRES_IMAGE=%s\n' "$postgres_image"
        printf 'INPLACEX_BACKEND_LOOPBACK_PORT=%s\n' "$backend_port"
        printf 'INPLACEX_POSTGRES_DB=inplacex\n'
        printf 'INPLACEX_POSTGRES_USER=inplacex\n'
        printf 'INPLACEX_POSTGRES_VOLUME=%s\n' "$postgres_volume"
        printf 'INPLACEX_SECRET_DIRECTORY=%s\n' "$secret_directory"
        printf 'INPLACEX_RUNTIME_SECRET_GID=%s\n' "$secret_gid"
        printf 'INPLACEX_GEOIP_DB_PATH=%s\n' "$geoip_file"
        printf 'INPLACEX_RELEASE_STATE_DIRECTORY=%s\n' "$release_state_directory"
        printf 'INPLACEX_PUBLIC_HOSTNAME=%s\n' "$public_hostname"
        printf 'INPLACEX_OPERATOR_NETWORK_CIDR=%s\n' "$operator_network_cidr"
        printf 'INPLACEX_DRAIN_TIMEOUT_SECONDS=30\n'
        printf 'INPLACEX_ONLINE_TOKEN_ISSUER=integration-platform\n'
        printf 'INPLACEX_ONLINE_TOKEN_AUDIENCE=integration-games\n'
        printf 'INPLACEX_MATCHMAKING_BOT_FALLBACK_SECONDS=1\n'
        printf 'INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS=127.0.0.1\n'
        printf 'INPLACEX_AD_MARKET_COUNTRY_HEADER=X-InplaceX-Country\n'
        printf 'INPLACEX_AD_MARKET_CONTAINER_DB_PATH=\n'
        printf 'INPLACEX_RELEASE_ID=%s\n' "$release_id"
        printf 'INPLACEX_GIT_SHA=%s\n' "$git_sha"
        printf 'INPLACEX_IMAGE_DIGEST=%s\n' "$image_digest"
        printf 'INPLACEX_SOURCE_ARCHIVE_SHA256=%s\n' "$source_archive_sha256"
        printf 'INPLACEX_RELEASE_MANIFEST_PATH=%s\n' "$manifest_path"
        printf 'INPLACEX_INITIAL_DEPLOY=%s\n' "$initial_deploy"
        printf 'INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS=180\n'
        printf 'INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK=\n'
        printf 'INPLACEX_ALLOW_PUBLIC_KEY_ROTATION_FROM_SHA256=\n'
    } > "$env_file"
    chown root:root "$env_file"
    chmod 0600 "$env_file"
}

base64url() {
    base64 -w0 | tr '+/' '-_' | tr -d '='
}

create_platform_token() {
    local account_id="$1"
    local player_id="$2"
    local now expires token_id header payload signing_input signature
    now="$(date +%s)"
    expires=$((now + 840))
    token_id="$(cat /proc/sys/kernel/random/uuid)"
    header="$(printf '%s' '{"alg":"RS256","typ":"JWT"}' | base64url)"
    payload="$(printf '{"iss":"integration-platform","aud":"integration-games","sub":"%s","pid":"%s","gid":"inplacex","iat":%s,"exp":%s,"jti":"%s"}' \
        "$account_id" "$player_id" "$now" "$expires" "$token_id" | base64url)"
    signing_input="$header.$payload"
    signature="$(printf '%s' "$signing_input" | openssl dgst -sha256 -sign "$private_key" | base64url)"
    printf '%s.%s\n' "$signing_input" "$signature"
}

reset_ephemeral_release_state() {
    [[ -d /run/inplacex-online && ! -L /run/inplacex-online &&
        "$(stat -c '%u %g %a' -- /run/inplacex-online)" == "0 0 755" ]] || {
        echo "Refusing to reset unexpected runtime state." >&2
        exit 77
    }
    rm -rf -- /run/inplacex-online
    sync -f /run
}

assert_backend_fails_closed() {
    local container_id="$1"
    docker restart "$container_id" >/dev/null 2>&1 || true
    sleep 2
    for _ in {1..8}; do
        if curl --fail --silent "http://127.0.0.1:$backend_port/ready" >/dev/null 2>&1; then
            echo "Backend became ready without a durable or leased activation." >&2
            exit 70
        fi
        sleep 1
    done
    docker stop --time 5 "$container_id" >/dev/null 2>&1 || true
}

release_v1="integration-v1-$test_id"
image_v1="$(build_release_image "$release_v1")"
write_environment "$release_v1" "$image_v1" true
bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"

[[ "$(stat -c '%u %g %a' -- "$release_state_directory")" == "0 0 700" ]]
[[ "$(stat -c '%u %g %a' -- "$release_state_directory/activation")" == "0 $secret_gid 750" ]]
[[ "$(stat -c '%u %g %a' -- "$release_state_directory/activation/verified-activation.env")" == \
    "0 $secret_gid 440" ]]
[[ "$(stat -c '%u %g %a' -- /run/lock/mirkori-games)" == "0 0 700" ]]
[[ "$(stat -c '%u %g %a' -- /run/lock/mirkori-games/inplacex-online-release.lock)" == "0 0 600" ]]
[[ ! -e "$release_state_directory/release-transaction.env" ]]

"${nginx_curl[@]}" --fail --silent "$nginx_origin/inplacex/ready" >/dev/null
metrics="$(curl --fail --silent "http://127.0.0.1:$backend_port/metrics")"
grep -q '^inplacex_backend_readiness_checks_total [0-9][0-9]*$' <<< "$metrics"
if grep -Eiq 'password|jdbc:|exception|token|secret' <<< "$metrics"; then
    echo "Readiness metrics contain unsafe details." >&2
    exit 67
fi
printf 'integration-gate\n' > /run/inplacex-online/maintenance.flag
chown root:root /run/inplacex-online/maintenance.flag
chmod 0644 /run/inplacex-online/maintenance.flag
[[ "$("${nginx_curl[@]}" --silent --output /dev/null --write-out '%{http_code}' \
    "$nginx_origin/inplacex/health")" == "503" ]]
rm -f -- /run/inplacex-online/maintenance.flag

account_id="$(cat /proc/sys/kernel/random/uuid)"
player_id="$(cat /proc/sys/kernel/random/uuid)"
token="$(create_platform_token "$account_id" "$player_id")"
command_id="$(cat /proc/sys/kernel/random/uuid)"
ticket_response="$(curl --fail --silent \
    --header "Authorization: Bearer $token" \
    --header "Idempotency-Key: $command_id" \
    --header 'Content-Type: application/json' \
    --data "{\"commandId\":\"$command_id\",\"mode\":\"classic\",\"playStyle\":\"race\",\"codeLength\":6}" \
    "http://127.0.0.1:$backend_port/api/v1/matchmaking/tickets")"
ticket_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["ticketId"])' <<< "$ticket_response")"
sleep 2
matched_response="$(curl --fail --silent \
    --header "Authorization: Bearer $token" \
    "http://127.0.0.1:$backend_port/api/v1/matchmaking/tickets/$ticket_id")"
session_id="$(python3 -c 'import json,sys; value=json.load(sys.stdin)["sessionId"]; assert value; print(value)' <<< "$matched_response")"

python3 - "$nginx_port" "$backend_port" "$public_hostname" "$session_id" "$token" <<'PY'
import base64
import json
import os
import socket
import ssl
import sys
import urllib.request

port = int(sys.argv[1])
backend_port = int(sys.argv[2])
hostname = sys.argv[3]
session_id = sys.argv[4]
token = sys.argv[5]
websocket_key = base64.b64encode(os.urandom(16)).decode("ascii")
request = (
    f"GET /api/v1/ws/sessions/{session_id} HTTP/1.1\r\n"
    f"Host: {hostname}\r\n"
    "Connection: Upgrade\r\n"
    "Upgrade: websocket\r\n"
    "Sec-WebSocket-Version: 13\r\n"
    f"Sec-WebSocket-Key: {websocket_key}\r\n"
    "Sec-WebSocket-Protocol: inplacex.online.v1\r\n"
    f"Authorization: Bearer {token}\r\n\r\n"
).encode("ascii")
context = ssl.create_default_context()
context.check_hostname = False
context.verify_mode = ssl.CERT_NONE
with socket.create_connection(("127.0.0.1", port), timeout=10) as raw_connection:
    with context.wrap_socket(raw_connection, server_hostname=hostname) as connection:
        connection.sendall(request)
        response = b""
        while b"\r\n\r\n" not in response and len(response) < 16384:
            response += connection.recv(4096)
        headers = response.split(b"\r\n\r\n", 1)[0].decode("iso-8859-1").lower()
        if not headers.startswith("http/1.1 101"):
            raise SystemExit(f"WebSocket upgrade failed: {headers.splitlines()[0]}")
        if "sec-websocket-protocol: inplacex.online.v1" not in headers:
            raise SystemExit("WebSocket subprotocol was not preserved through nginx")
        with urllib.request.urlopen(
            f"http://127.0.0.1:{backend_port}/admin/drain/status",
            timeout=5,
        ) as status_response:
            drain_status = json.load(status_response)
        if drain_status["draining"] is not False or drain_status["activeRequests"] < 1:
            raise SystemExit("Open WebSocket was not tracked as an active drain lease")
PY

printf 'integration-drain\n' > /run/inplacex-online/drain.flag
chown root:root /run/inplacex-online/drain.flag
chmod 0644 /run/inplacex-online/drain.flag
[[ "$(curl --silent --output /dev/null --write-out '%{http_code}' \
    "http://127.0.0.1:$backend_port/api/v1/matchmaking/tickets/not-a-uuid")" == "503" ]]
drain_status="$(curl --fail --silent "http://127.0.0.1:$backend_port/admin/drain/status")"
[[ "$drain_status" == '{"draining":true,"activeRequests":0}' ]]
rm -f -- /run/inplacex-online/drain.flag

last_status=""
last_headers="$test_root/invalid-auth.headers"
for _ in {1..31}; do
    last_status="$(curl --silent --dump-header "$last_headers" --output /dev/null --write-out '%{http_code}' \
        "http://127.0.0.1:$backend_port/api/v1/matchmaking/tickets/not-a-uuid")"
done
[[ "$last_status" == "429" ]]
grep -Eiq '^Retry-After: [1-9][0-9]*' "$last_headers"

compose=(docker compose --env-file "$env_file" --project-directory "$repository_root" -f "$compose_file")
backend_container="$("${compose[@]}" ps -q backend)"
docker restart "$backend_container" >/dev/null
for _ in {1..90}; do
    curl --fail --silent "http://127.0.0.1:$backend_port/ready" >/dev/null 2>&1 && break
    sleep 1
done
curl --fail --silent \
    --header "Authorization: Bearer $token" \
    "http://127.0.0.1:$backend_port/api/v1/sessions/$session_id" >/dev/null
migration_state="$("${compose[@]}" exec -T postgres sh -ec \
    'export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"; exec psql --tuples-only --no-align --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --command="SELECT count(*), count(*) FILTER (WHERE checksum IS NULL) FROM inplacex_schema_history;"' |
    tr -d '[:space:]')"
[[ "$migration_state" == "9|0" ]]

release_v2="integration-v2-$test_id"
image_v2="$(build_release_image "$release_v2")"
write_environment "$release_v2" "$image_v2" false
set +e
INPLACEX_RELEASE_FAULT_PHASE=after_candidate_start \
INPLACEX_RELEASE_FAULT_TEST_ACK=isolated-ci-host \
    bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"
deploy_fault_status=$?
set -e
[[ "$deploy_fault_status" -ne 0 ]]
grep -qx 'RELEASE_TRANSACTION_OPERATION=deploy' "$release_state_directory/release-transaction.env"
grep -qx 'RELEASE_TRANSACTION_PHASE=candidate_starting' "$release_state_directory/release-transaction.env"
[[ -f /run/inplacex-online/maintenance.flag ]]
sleep 2

compose=(docker compose --env-file "$env_file" --project-directory "$repository_root" -f "$compose_file")
backend_container="$("${compose[@]}" ps --all -q backend)"
reset_ephemeral_release_state
assert_backend_fails_closed "$backend_container"
bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"
[[ ! -e "$release_state_directory/release-transaction.env" ]]
grep -qx "INPLACEX_ACTIVATION_RELEASE_ID=$release_v2" \
    "$release_state_directory/activation/verified-activation.env"

latest_pointer="$backup_directory/latest-inplacex-backend-release.env"
receipt_path="$(sed -n 's/^RELEASE_POINTER_RECEIPT_PATH=//p' "$latest_pointer")"
[[ -f "$receipt_path" ]]
set +e
INPLACEX_RELEASE_FAULT_PHASE=rollback_after_database_restore \
INPLACEX_RELEASE_FAULT_TEST_ACK=isolated-ci-host \
    bash "$production_directory/rollback-backend.sh" \
        "$env_file" "$receipt_path" --confirm-data-restore
rollback_fault_status=$?
set -e
[[ "$rollback_fault_status" -ne 0 ]]
grep -qx 'RELEASE_TRANSACTION_OPERATION=rollback' "$release_state_directory/release-transaction.env"
grep -qx 'RELEASE_TRANSACTION_PHASE=database_restored' "$release_state_directory/release-transaction.env"
[[ ! -e "$release_state_directory/activation/verified-activation.env" ]]

backend_container="$("${compose[@]}" ps --all -q backend)"
reset_ephemeral_release_state
assert_backend_fails_closed "$backend_container"
bash "$production_directory/rollback-backend.sh" \
    "$env_file" "$receipt_path" --confirm-data-restore
[[ ! -e "$release_state_directory/release-transaction.env" ]]
grep -qx "INPLACEX_ACTIVATION_RELEASE_ID=$release_v1" \
    "$release_state_directory/activation/verified-activation.env"

release_json="$(curl --fail --silent "http://127.0.0.1:$backend_port/meta/release")"
python3 - "$release_json" "$release_v1" "$git_sha" "${image_v1##*@}" <<'PY'
import json
import sys

actual = json.loads(sys.argv[1])
expected = {"releaseId": sys.argv[2], "gitSha": sys.argv[3], "imageDigest": sys.argv[4]}
if actual != expected:
    raise SystemExit("Rollback release identity mismatch")
PY
curl --fail --silent \
    --header "Authorization: Bearer $token" \
    "http://127.0.0.1:$backend_port/api/v1/sessions/$session_id" >/dev/null

echo "InplaceX production runtime deploy, restart recovery, and rollback integration passed."
