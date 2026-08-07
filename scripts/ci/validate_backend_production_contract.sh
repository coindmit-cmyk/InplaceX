#!/usr/bin/env bash
# shellcheck disable=SC2016,SC2155
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
production_directory="$repository_root/ops/production"

for command_name in grep mktemp; do
    command -v "$command_name" >/dev/null || {
        echo "Required production-contract command is missing: $command_name" >&2
        exit 69
    }
done
if command -v docker.exe >/dev/null && command -v wslpath >/dev/null; then
    docker_command=(docker.exe)
    windows_docker=true
    compose_project_directory="$(wslpath -w "$repository_root")"
    compose_file="$(wslpath -w "$production_directory/compose.yaml")"
elif command -v docker >/dev/null; then
    docker_command=(docker)
    windows_docker=false
    compose_project_directory="$repository_root"
    compose_file="$production_directory/compose.yaml"
else
    echo "Required production-contract command is missing: docker" >&2
    exit 69
fi
shell_scripts=("$production_directory"/*.sh "$repository_root/ops/ads/verify-ad-market.sh")
bash -n "${shell_scripts[@]}"
if command -v shellcheck >/dev/null; then
    shellcheck "${shell_scripts[@]}"
elif command -v shellcheck.exe >/dev/null && command -v wslpath >/dev/null; then
    windows_shell_scripts=()
    for shell_script in "${shell_scripts[@]}"; do
        windows_shell_scripts+=("$(wslpath -w "$shell_script")")
    done
    shellcheck.exe "${windows_shell_scripts[@]}"
else
    echo "Required production-contract command is missing: shellcheck" >&2
    exit 69
fi

temporary_directory="$(mktemp -d "$repository_root/.production-contract.XXXXXX")"
cleanup() { rm -rf -- "$temporary_directory"; }
trap cleanup EXIT

expect_exit() {
    local expected_status="$1"
    shift
    set +e
    "$@" >/dev/null 2>&1
    local actual_status=$?
    set -e
    if [[ "$actual_status" -ne "$expected_status" ]]; then
        echo "Expected exit $expected_status, received $actual_status: $*" >&2
        exit 67
    fi
}

rendered_nginx="$temporary_directory/inplacex-online.locations.conf"
bash "$production_directory/render-nginx-config.sh" 18081 192.0.2.10/32 "$rendered_nginx"
grep -q '127.0.0.1:18081' "$rendered_nginx"
grep -q 'location = /api/v1/runtime/ad-market' "$rendered_nginx"
grep -q 'location ^~ /api/v1/ws/' "$rendered_nginx"
grep -q 'location ^~ /api/v1/matchmaking/' "$rendered_nginx"
grep -q 'location ^~ /api/v1/friends/' "$rendered_nginx"
grep -q 'location ^~ /api/v1/sessions/' "$rendered_nginx"
grep -q 'location = /inplacex/health' "$rendered_nginx"
grep -q 'location = /inplacex/ready' "$rendered_nginx"
grep -q 'location = /inplacex/meta/release' "$rendered_nginx"
[[ "$(grep -Fc 'include /etc/nginx/snippets/inplacex-online-maintenance-gate.conf;' "$rendered_nginx")" -eq 8 ]]
grep -q 'proxy_set_header Authorization \$http_authorization;' "$rendered_nginx"
grep -q 'proxy_set_header Sec-WebSocket-Protocol \$http_sec_websocket_protocol;' "$rendered_nginx"
grep -q 'proxy_set_header X-Real-IP \$remote_addr;' "$rendered_nginx"
grep -q 'add_header Retry-After 1 always;' "$rendered_nginx"
[[ "$(grep -Fc 'allow 192.0.2.10/32;' "$rendered_nginx")" -eq 3 ]]
[[ "$(grep -Fc 'deny all;' "$rendered_nginx")" -eq 3 ]]
grep -q 'limit_req zone=inplacex_online_operator' "$rendered_nginx"
if grep -q '@@INPLACEX_' "$rendered_nginx"; then
    echo "Rendered nginx contract contains an unresolved placeholder." >&2
    exit 67
fi
expect_exit 65 bash "$production_directory/render-nginx-config.sh" 0 192.0.2.10/32 "$temporary_directory/invalid.conf"
expect_exit 65 bash "$production_directory/render-nginx-config.sh" 18081 192.0.2.10/24 "$temporary_directory/invalid-cidr.conf"

export COMPOSE_PROJECT_NAME=inplacex-production-contract-test
export INPLACEX_BACKEND_IMAGE="registry.example/inplacex-backend@sha256:$(printf 'a%.0s' {1..64})"
export INPLACEX_POSTGRES_IMAGE="postgres@sha256:$(printf 'b%.0s' {1..64})"
export INPLACEX_BACKEND_LOOPBACK_PORT=18081
export INPLACEX_POSTGRES_DB=inplacex
export INPLACEX_POSTGRES_USER=inplacex
export INPLACEX_POSTGRES_VOLUME=inplacex-production-contract-test
export INPLACEX_SECRET_DIRECTORY=/tmp/inplacex-production-contract-test
export INPLACEX_RUNTIME_SECRET_GID=21081
export INPLACEX_GEOIP_DB_PATH=/tmp/inplacex-production-contract-test.mmdb
export INPLACEX_ONLINE_TOKEN_ISSUER=mirkori-platform
export INPLACEX_ONLINE_TOKEN_AUDIENCE=mirkori-games
export INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS=172.18.0.1
export INPLACEX_RELEASE_ID=inplacex-backend-contract-test
export INPLACEX_GIT_SHA="$(printf 'c%.0s' {1..40})"
export INPLACEX_IMAGE_DIGEST="sha256:$(printf 'a%.0s' {1..64})"
export INPLACEX_SOURCE_ARCHIVE_SHA256="$(printf 'd%.0s' {1..64})"
export INPLACEX_RUNTIME_CONFIG_SHA256="$(printf 'e%.0s' {1..64})"
export INPLACEX_RELEASE_STATE_DIRECTORY=/var/lib/inplacex-online/contract-release-state
export INPLACEX_INITIAL_DEPLOY=false
export INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS=120

rendered_compose="$temporary_directory/compose.yaml"
compose_environment="$temporary_directory/compose.env"
{
    printf 'COMPOSE_PROJECT_NAME=%s\n' "$COMPOSE_PROJECT_NAME"
    printf 'INPLACEX_BACKEND_IMAGE=%s\n' "$INPLACEX_BACKEND_IMAGE"
    printf 'INPLACEX_POSTGRES_IMAGE=%s\n' "$INPLACEX_POSTGRES_IMAGE"
    printf 'INPLACEX_BACKEND_LOOPBACK_PORT=%s\n' "$INPLACEX_BACKEND_LOOPBACK_PORT"
    printf 'INPLACEX_POSTGRES_DB=%s\n' "$INPLACEX_POSTGRES_DB"
    printf 'INPLACEX_POSTGRES_USER=%s\n' "$INPLACEX_POSTGRES_USER"
    printf 'INPLACEX_POSTGRES_VOLUME=%s\n' "$INPLACEX_POSTGRES_VOLUME"
    printf 'INPLACEX_SECRET_DIRECTORY=%s\n' "$INPLACEX_SECRET_DIRECTORY"
    printf 'INPLACEX_RUNTIME_SECRET_GID=%s\n' "$INPLACEX_RUNTIME_SECRET_GID"
    printf 'INPLACEX_GEOIP_DB_PATH=%s\n' "$INPLACEX_GEOIP_DB_PATH"
    printf 'INPLACEX_ONLINE_TOKEN_ISSUER=%s\n' "$INPLACEX_ONLINE_TOKEN_ISSUER"
    printf 'INPLACEX_ONLINE_TOKEN_AUDIENCE=%s\n' "$INPLACEX_ONLINE_TOKEN_AUDIENCE"
    printf 'INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS=%s\n' "$INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS"
    printf 'INPLACEX_RELEASE_ID=%s\n' "$INPLACEX_RELEASE_ID"
    printf 'INPLACEX_GIT_SHA=%s\n' "$INPLACEX_GIT_SHA"
    printf 'INPLACEX_IMAGE_DIGEST=%s\n' "$INPLACEX_IMAGE_DIGEST"
    printf 'INPLACEX_SOURCE_ARCHIVE_SHA256=%s\n' "$INPLACEX_SOURCE_ARCHIVE_SHA256"
    printf 'INPLACEX_RUNTIME_CONFIG_SHA256=%s\n' "$INPLACEX_RUNTIME_CONFIG_SHA256"
    printf 'INPLACEX_RELEASE_STATE_DIRECTORY=%s\n' "$INPLACEX_RELEASE_STATE_DIRECTORY"
    printf 'INPLACEX_INITIAL_DEPLOY=%s\n' "$INPLACEX_INITIAL_DEPLOY"
    printf 'INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS=%s\n' "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS"
} > "$compose_environment"
compose_environment_argument="$compose_environment"
if [[ "$windows_docker" == "true" ]]; then
    compose_environment_argument="$(wslpath -w "$compose_environment")"
fi
"${docker_command[@]}" compose \
    --env-file "$compose_environment_argument" \
    --project-directory "$compose_project_directory" \
    -f "$compose_file" \
    config > "$rendered_compose"
grep -q 'host_ip: 127.0.0.1' "$rendered_compose"
grep -q 'published: "18081"' "$rendered_compose"
grep -q 'target: 8080' "$rendered_compose"
grep -q "image: $INPLACEX_BACKEND_IMAGE" "$rendered_compose"
grep -q "image: $INPLACEX_POSTGRES_IMAGE" "$rendered_compose"
grep -q 'INPLACEX_BACKEND_ENVIRONMENT: production' "$rendered_compose"
grep -q 'external: true' "$rendered_compose"
grep -q 'group_add:' "$rendered_compose"
grep -q 'source-archive-sha256' "$repository_root/ops/Dockerfile"
grep -q 'INPLACEX_ACTIVATION_GEOIP_FINGERPRINT_PATH: /var/lib/inplacex/geoip/dbip-country-lite.mmdb' "$rendered_compose"
grep -q '/run/inplacex-activation/verified' "$rendered_compose"
grep -q '/run/inplacex-control' "$rendered_compose"
grep -q 'init: true' "$rendered_compose"
grep -q -- '- "21081"' "$rendered_compose"
if grep -Eq 'REPLACE_WITH|<digest>|@@INPLACEX_' "$rendered_compose"; then
    echo "Rendered Compose contract contains an unresolved placeholder." >&2
    exit 67
fi

expect_exit 65 bash "$production_directory/smoke-backend.sh" external \
    http://example.invalid "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_IMAGE_DIGEST"

bash "$repository_root/scripts/ci/test_backend_release_helpers.sh"

echo "InplaceX backend production contract is valid."
