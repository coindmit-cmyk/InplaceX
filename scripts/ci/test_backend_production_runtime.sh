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

for command_name in base64 cp curl docker find git htpasswd ln nginx openssl patch python3 rm sha256sum ss stat sync tar timeout; do
    command -v "$command_name" >/dev/null || {
        echo "Missing integration-test command: $command_name" >&2
        exit 69
    }
done
docker_server_os="$(docker version --format '{{.Server.Os}}' 2>/dev/null)" || {
    echo "Production integration test requires a reachable Linux Docker daemon." >&2
    exit 69
}
[[ "$docker_server_os" == "linux" ]] || {
    echo "Production integration test requires a Linux Docker daemon." >&2
    exit 69
}

checkout_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
test_id="${GITHUB_RUN_ID:-$$}"
[[ "$test_id" =~ ^[0-9]+$ ]] || test_id="$$"
test_root="/var/lib/inplacex-production-ci-$test_id"
[[ "$test_root" =~ ^/var/lib/inplacex-production-ci-[0-9]+$ ]] || exit 70
repository_root="$test_root/source"
production_directory="$repository_root/ops/production"
registry_name="inplacex-production-registry-$test_id"
authenticated_registry_name="inplacex-production-auth-registry-$test_id"
project_name="inplacex-production-ci-$test_id"
postgres_volume="inplacex-production-ci-postgres-$test_id"
registry_port=5011
authenticated_registry_port=5012
backend_port=18081
nginx_port=443
secret_gid=21081
git_sha="$(/usr/bin/env -i \
    PATH=/usr/sbin:/usr/bin:/sbin:/bin \
    HOME=/ \
    LANG=C \
    LC_ALL=C \
    GIT_CONFIG_GLOBAL=/dev/null \
    GIT_CONFIG_NOSYSTEM=1 \
    /usr/bin/git --no-replace-objects -c safe.directory="$checkout_root" \
    -c core.fsmonitor=false -c core.hooksPath=/dev/null \
    -C "$checkout_root" rev-parse --verify 'HEAD^{commit}')"
source_archive_sha256=""
real_docker="$(command -v docker)"
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
authenticated_registry_username=inplacex-ci
authenticated_registry_password="inplacex-ci-token-$test_id"
authenticated_registry_basic="${authenticated_registry_username}:${authenticated_registry_password}"
authenticated_registry_auth_base64="$(printf '%s' "$authenticated_registry_basic" | base64 -w0)"
authenticated_registry_directory="$test_root/authenticated-registry"
compose_file="$production_directory/compose.yaml"
current_source_archive="$test_root/current-source.tar"
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
for integration_port in "$registry_port" "$authenticated_registry_port" "$backend_port"; do
    if ss -H -ltn | awk '{print $4}' | grep -Eq "(^|:)$integration_port$"; then
        echo "Integration test requires unused loopback port $integration_port." >&2
        exit 75
    fi
done

cleanup() {
    local status=$?
    local cleanup_env_file="$env_file"
    local -a leftover_release_builders=()
    trap - EXIT
    if [[ -n "${legacy_env_file:-}" && -f "$legacy_env_file" ]]; then
        cleanup_env_file="$legacy_env_file"
    fi
    if [[ -f "$env_file" ]]; then
        if [[ "$status" -ne 0 ]]; then
            docker compose --env-file "$cleanup_env_file" --project-directory "$repository_root" \
                -f "$compose_file" ps --all >&2 || true
            docker compose --env-file "$cleanup_env_file" --project-directory "$repository_root" \
                -f "$compose_file" logs --no-color --tail 200 backend postgres >&2 || true
        fi
        docker compose --env-file "$cleanup_env_file" --project-directory "$repository_root" \
            -f "$compose_file" down --remove-orphans >/dev/null 2>&1 || true
    fi
    docker rm -f "$registry_name" "$authenticated_registry_name" >/dev/null 2>&1 || true
    mapfile -t leftover_release_builders < <(
        docker ps --all --quiet \
            --filter 'name=^/buildx_buildkit_inplacex-release-' 2>/dev/null || true
    )
    if (( ${#leftover_release_builders[@]} > 0 )); then
        docker rm -f "${leftover_release_builders[@]}" >/dev/null 2>&1 || true
    fi
    docker volume rm "$postgres_volume" >/dev/null 2>&1 || true
    rm -f -- "${nginx_files[@]}"
    nginx -t >/dev/null 2>&1 && nginx -s reload >/dev/null 2>&1 || true
    if [[ -d /run/inplacex-online && ! -L /run/inplacex-online &&
        "$(stat -c '%u' -- /run/inplacex-online 2>/dev/null || true)" == "0" ]]; then
        rm -rf -- /run/inplacex-online
    fi
    rm -f -- /run/lock/mirkori-games/inplacex-online-release.lock
    rmdir -- /run/lock/mirkori-games >/dev/null 2>&1 || true
    [[ -z "${unsafe_plugin_path:-}" ]] || rm -f -- "$unsafe_plugin_path"
    rm -rf -- "$test_root"
    exit "$status"
}
trap cleanup EXIT

print_sanitized_ci_log() {
    local log_path="$1"
    [[ -f "$log_path" ]] || return 0
    python3 -I "$repository_root/scripts/ci/print_sanitized_ci_log.py" "$log_path" \
        "$authenticated_registry_password" \
        "$authenticated_registry_basic" \
        "$authenticated_registry_auth_base64" >&2
}

print_registry_diagnostics() {
    local label="$1"
    local container_name="$2"
    echo "--- $label registry diagnostics ---" >&2
    if docker inspect "$container_name" >/dev/null 2>&1; then
        docker inspect --format \
            'name={{.Name}} running={{.State.Running}} status={{.State.Status}} exit={{.State.ExitCode}} error={{json .State.Error}}' \
            "$container_name" >&2 || true
        docker logs --tail 200 "$container_name" >&2 || true
    else
        echo "Registry container does not exist: $container_name" >&2
        docker ps --all --filter "name=^/${container_name}$" \
            --format 'name={{.Names}} status={{.Status}} image={{.Image}}' >&2 || true
    fi
    echo "--- end $label registry diagnostics ---" >&2
}

assert_no_inplacex_release_builders() {
    local leftover_builders
    leftover_builders="$(docker ps --all \
        --filter 'name=^/buildx_buildkit_inplacex-release-' \
        --format '{{.ID}}|{{.Names}}')"
    [[ -z "$leftover_builders" ]] || {
        echo "Release builder lifecycle left BuildKit containers behind:" >&2
        printf '%s\n' "$leftover_builders" >&2
        return 70
    }
}

wait_for_registry() {
    local label="$1"
    local container_name="$2"
    local port="$3"
    local authentication_mode="$4"
    local attempt container_running curl_status http_code
    local -a authentication_arguments=()
    if [[ "$authentication_mode" == "authenticated" ]]; then
        authentication_arguments=(
            --user "$authenticated_registry_username:$authenticated_registry_password"
        )
    elif [[ "$authentication_mode" != "anonymous" ]]; then
        echo "Unknown registry probe authentication mode: $authentication_mode" >&2
        return 64
    fi

    for attempt in {1..30}; do
        container_running="$(docker inspect --format '{{.State.Running}}' \
            "$container_name" 2>/dev/null || true)"
        if [[ "$container_running" == "false" ]]; then
            echo "$label registry container exited before readiness (attempt=$attempt)." >&2
            print_registry_diagnostics "$label" "$container_name"
            return 70
        fi
        curl_status=0
        http_code="$(curl --silent --output /dev/null --write-out '%{http_code}' \
            "${authentication_arguments[@]}" "http://127.0.0.1:$port/v2/")" || curl_status=$?
        if [[ "$curl_status" -eq 0 && "$http_code" == "200" ]]; then
            echo "$label registry ready (attempt=$attempt, http_code=$http_code)."
            return 0
        fi
        sleep 1
    done

    echo "$label registry readiness failed after 30 attempts "\
        "(curl_status=$curl_status, http_code=${http_code:-none})." >&2
    print_registry_diagnostics "$label" "$container_name"
    return 70
}

install -d -o root -g root -m 0700 "$test_root" "$backup_directory"
install -d -o root -g "$secret_gid" -m 0750 "$secret_directory"
install -d -o root -g root -m 0700 "$test_root/clone-git-home"
protected_git=(
    /usr/bin/env -i
    PATH=/usr/sbin:/usr/bin:/sbin:/bin
    HOME="$test_root/clone-git-home"
    LANG=C
    LC_ALL=C
    GIT_CONFIG_GLOBAL=/dev/null
    GIT_CONFIG_NOSYSTEM=1
    /usr/bin/git --no-replace-objects -c core.fsmonitor=false -c core.hooksPath=/dev/null
)
"${protected_git[@]}" -c safe.directory="$checkout_root" clone \
    --no-local --no-hardlinks --no-checkout --upload-pack=/usr/bin/git-upload-pack \
    "$checkout_root" "$repository_root" >/dev/null
"${protected_git[@]}" -C "$repository_root" checkout --detach "$git_sha" >/dev/null
[[ "$("${protected_git[@]}" -C "$repository_root" rev-parse --verify 'HEAD^{commit}')" == \
    "$git_sha" ]]
chmod 0755 \
    "$production_directory/build-backend-release.sh" \
    "$production_directory/release-common.sh"
chmod 0644 \
    "$production_directory/release-shell-bootstrap.sh" \
    "$production_directory/create-source-archive.py" \
    "$repository_root/ops/Dockerfile"

assert_builder_rejects_untrusted_source() {
    local case_name="$1"
    local mutation="$2"
    local expected_error="$3"
    local case_root="$test_root/builder-trust-$case_name"
    local case_repository="$case_root/source"
    local case_log="$test_root/builder-trust-$case_name.log"
    local case_manifest="$test_root/builder-trust-$case_name.manifest.json"
    local status

    install -d -o root -g root -m 0700 "$case_root"
    cp -a -- "$repository_root" "$case_repository"
    case "$mutation" in
        writable-parent)
            chmod 0777 "$case_root"
            ;;
        symlink-tool)
            cp -- "$case_repository/ops/production/release-common.sh" \
                "$case_repository/ops/production/release-common.real.sh"
            rm -- "$case_repository/ops/production/release-common.sh"
            ln -s release-common.real.sh \
                "$case_repository/ops/production/release-common.sh"
            ;;
        hardlink-tool)
            cp -- "$case_repository/ops/Dockerfile" \
                "$case_repository/ops/Dockerfile.peer"
            rm -- "$case_repository/ops/Dockerfile"
            ln "$case_repository/ops/Dockerfile.peer" \
                "$case_repository/ops/Dockerfile"
            ;;
        *)
            echo "Unknown builder trust mutation: $mutation" >&2
            exit 64
            ;;
    esac

    set +e
    INPLACEX_RELEASE_ISOLATED_CI_ACK=acknowledge-inplacex-isolated-release-ci \
        /usr/bin/bash -p "$case_repository/ops/production/build-backend-release.sh" \
        "127.0.0.1:1/inplacex-backend:trust-$case_name" \
        "trust-$case_name" "$case_manifest" --push --anonymous-loopback \
        > "$case_log" 2>&1
    status=$?
    set -e
    local failed=false
    if [[ "$status" -eq 0 || -e "$case_manifest" ]]; then
        echo "Builder trust case '$case_name' did not fail closed (status=$status, manifest=$case_manifest)." >&2
        failed=true
    fi
    if ! grep -Fq "$expected_error" "$case_log"; then
        echo "Builder trust case '$case_name' did not report the expected error: $expected_error" >&2
        failed=true
    fi
    if [[ "$failed" == "true" ]]; then
        echo "--- builder trust case '$case_name' log ---" >&2
        sed -n '1,120p' "$case_log" >&2
        echo "--- end builder trust case '$case_name' log ---" >&2
    fi
    chmod 0700 "$case_root"
    rm -rf -- "$case_root"
    [[ "$failed" == "false" ]]
}

assert_builder_rejects_untrusted_source \
    writable-parent writable-parent \
    'Release source parent must not be group/world writable'
assert_builder_rejects_untrusted_source \
    symlink-tool symlink-tool \
    'Release bootstrap file must be root-owned, protected, regular, and single-link'
assert_builder_rejects_untrusted_source \
    hardlink-tool hardlink-tool \
    'Release tool must be root-owned, protected, exact-mode, and single-link'

assert_builder_rejects_manifest_target() {
    local case_name="$1"
    local target_path="$2"
    local case_log="$test_root/manifest-target-$case_name.log"
    local status
    set +e
    INPLACEX_RELEASE_ISOLATED_CI_ACK=acknowledge-inplacex-isolated-release-ci \
        "$production_directory/build-backend-release.sh" \
        "127.0.0.1:1/inplacex-backend:manifest-$case_name" \
        "manifest-$case_name" "$target_path" --push --anonymous-loopback \
        > "$case_log" 2>&1
    status=$?
    set -e
    [[ "$status" -eq 66 ]]
    grep -Fq 'Manifest path must be a new absolute path' "$case_log"
}

existing_manifest_directory="$test_root/existing-manifest-directory"
existing_manifest_symlink="$test_root/existing-manifest-symlink"
install -d -o root -g root -m 0700 "$existing_manifest_directory"
ln -s "$test_root/missing-manifest-target" "$existing_manifest_symlink"
assert_builder_rejects_manifest_target directory "$existing_manifest_directory"
assert_builder_rejects_manifest_target symlink "$existing_manifest_symlink"
rm -- "$existing_manifest_symlink"
rmdir -- "$existing_manifest_directory"

assert_builder_rejects_registry_auth() {
    local case_name="$1"
    local auth_path="$2"
    local expected_error="$3"
    local case_manifest="$test_root/registry-auth-$case_name.manifest.json"
    local case_log="$test_root/registry-auth-$case_name.log"
    local status
    set +e
    "$production_directory/build-backend-release.sh" \
        "127.0.0.1:$registry_port/inplacex-backend:auth-$case_name" \
        "auth-$case_name" "$case_manifest" --push \
        --registry-auth-config "$auth_path" > "$case_log" 2>&1
    status=$?
    set -e
    [[ "$status" -ne 0 && ! -e "$case_manifest" ]]
    grep -Fq "$expected_error" "$case_log"
}

registry_auth_test_directory="$test_root/registry-auth-tests"
install -d -o root -g root -m 0700 "$registry_auth_test_directory"
valid_registry_auth="$registry_auth_test_directory/valid.json"
ambient_helper_registry_auth="$registry_auth_test_directory/ambient-helpers.json"
wrong_host_registry_auth="$registry_auth_test_directory/wrong-host.json"
wrong_mode_registry_auth="$registry_auth_test_directory/wrong-mode.json"
extra_key_registry_auth="$registry_auth_test_directory/extra-key.json"
symlink_registry_auth="$registry_auth_test_directory/symlink.json"
printf '{"auths":{"127.0.0.1:%s":{"auth":"Y2ktdXNlcjpjaS10b2tlbg=="}}}\n' \
    "$registry_port" > "$valid_registry_auth"
printf '{"auths":{"127.0.0.1:%s":{"auth":"Y2ktdXNlcjpjaS10b2tlbg=="}},"credsStore":"hostile","credHelpers":{},"plugins":{},"HttpHeaders":{}}\n' \
    "$registry_port" > "$ambient_helper_registry_auth"
printf '%s\n' \
    '{"auths":{"registry.invalid":{"auth":"Y2ktdXNlcjpjaS10b2tlbg=="}}}' \
    > "$wrong_host_registry_auth"
cp -- "$valid_registry_auth" "$wrong_mode_registry_auth"
printf '{"auths":{"127.0.0.1:%s":{"auth":"Y2ktdXNlcjpjaS10b2tlbg==","email":"forbidden"}}}\n' \
    "$registry_port" > "$extra_key_registry_auth"
ln -s "$valid_registry_auth" "$symlink_registry_auth"
chown root:root \
    "$valid_registry_auth" "$ambient_helper_registry_auth" \
    "$wrong_host_registry_auth" "$wrong_mode_registry_auth" \
    "$extra_key_registry_auth"
chmod 0600 \
    "$valid_registry_auth" "$ambient_helper_registry_auth" \
    "$wrong_host_registry_auth" "$extra_key_registry_auth"
chmod 0644 "$wrong_mode_registry_auth"
install -d -o root -g root -m 0700 "$registry_auth_test_directory/home"
normalized_registry_auth="$registry_auth_test_directory/normalized.json"
/usr/bin/env -i \
    PATH=/usr/sbin:/usr/bin:/sbin:/bin \
    HOME="$registry_auth_test_directory/home" \
    LANG=C \
    LC_ALL=C \
    /usr/bin/bash --noprofile --norc -c \
    'set -euo pipefail; source "$1"; release_normalize_registry_auth_config "$2" "$3" "$4"' \
    _ "$production_directory/release-common.sh" "$valid_registry_auth" \
    "127.0.0.1:$registry_port" "$normalized_registry_auth"
[[ "$(stat -Lc '%F %u %g %a %h' -- "$normalized_registry_auth")" == \
    "regular file 0 0 600 1" ]]
python3 -I - "$normalized_registry_auth" "127.0.0.1:$registry_port" <<'PY'
import json
import pathlib
import sys

value = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if value != {"auths": {sys.argv[2]: {"auth": "Y2ktdXNlcjpjaS10b2tlbg=="}}}:
    raise SystemExit("Normalized registry auth fixture differs from the strict schema")
PY
assert_builder_rejects_registry_auth \
    ambient-helpers "$ambient_helper_registry_auth" 'Registry auth config validation failed'
assert_builder_rejects_registry_auth \
    wrong-host "$wrong_host_registry_auth" 'Registry auth config validation failed'
assert_builder_rejects_registry_auth \
    wrong-mode "$wrong_mode_registry_auth" 'Registry auth config must be root:root 0600 with one link'
assert_builder_rejects_registry_auth \
    symlink "$symlink_registry_auth" 'Registry auth config must be an absolute regular non-symlink file'
assert_builder_rejects_registry_auth \
    extra-key "$extra_key_registry_auth" 'Registry auth config validation failed'

anonymous_manifest="$test_root/anonymous-registry-negative.manifest.json"
set +e
"$production_directory/build-backend-release.sh" \
    "127.0.0.1:$registry_port/inplacex-backend:anonymous-no-ack" \
    anonymous-no-ack "$anonymous_manifest" --push --anonymous-loopback \
    > "$test_root/anonymous-no-ack.log" 2>&1
anonymous_no_ack_status=$?
INPLACEX_RELEASE_ISOLATED_CI_ACK=acknowledge-inplacex-isolated-release-ci \
    "$production_directory/build-backend-release.sh" \
    registry.example/inplacex-backend:anonymous-wrong-host \
    anonymous-wrong-host "$anonymous_manifest" --push --anonymous-loopback \
    > "$test_root/anonymous-wrong-host.log" 2>&1
anonymous_wrong_host_status=$?
set -e
[[ "$anonymous_no_ack_status" -eq 77 && "$anonymous_wrong_host_status" -eq 77 ]]
grep -Fq 'Anonymous registry access is restricted' "$test_root/anonymous-no-ack.log"
grep -Fq 'Anonymous registry access is restricted' "$test_root/anonymous-wrong-host.log"
[[ ! -e "$anonymous_manifest" ]]

archive_helper=ops/production/create-source-archive.py
helper_object_sha256="$("${protected_git[@]}" -C "$repository_root" \
    cat-file blob "$git_sha:$archive_helper" | sha256sum | awk '{print $1}')"
[[ "$(sha256sum "$repository_root/$archive_helper" | awk '{print $1}')" == "$helper_object_sha256" ]]
install -d -o root -g root -m 0700 "$test_root/archive-git-home"
python3 -I "$repository_root/$archive_helper" \
    "$repository_root" "$git_sha" "$current_source_archive" "$test_root/archive-git-home"
source_archive_sha256="$(sha256sum "$current_source_archive" | awk '{print $1}')"
[[ "$source_archive_sha256" =~ ^[0-9a-f]{64}$ ]]
hostile_docker_directory="$test_root/hostile-bin"
hostile_docker_log="$test_root/hostile-docker.log"
install -d -o root -g root -m 0700 "$hostile_docker_directory"
cat > "$hostile_docker_directory/docker" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$INPLACEX_TEST_DOCKER_LOG"
if [[ "${1:-}" == "info" && "${2:-}" == "--format" && "${3:-}" == '{{json .}}' &&
    "${INPLACEX_TEST_CONTROL_MODE:-stable}" == "daemon-changed" ]]; then
    control_count=0
    [[ ! -f "${INPLACEX_TEST_CONTROL_COUNTER:-}" ]] ||
        control_count="$(cat "$INPLACEX_TEST_CONTROL_COUNTER")"
    control_count=$((control_count + 1))
    printf '%s\n' "$control_count" > "$INPLACEX_TEST_CONTROL_COUNTER"
    if [[ "$control_count" -gt 1 ]]; then
        "$INPLACEX_TEST_REAL_DOCKER" "$@" | python3 -I -c \
            'import json,sys; value=json.load(sys.stdin); value["ID"]="hostile-daemon-id"; json.dump(value,sys.stdout)'
        exit 0
    fi
fi
if [[ "${1:-}" == "info" && "${2:-}" == "--format" &&
    "${3:-}" == '{{json .ClientInfo.Plugins}}' &&
    "${INPLACEX_TEST_CONTROL_MODE:-stable}" == "unsafe-plugin" ]]; then
    "$INPLACEX_TEST_REAL_DOCKER" "$@" | python3 -I -c \
        'import json,os,sys; value=json.load(sys.stdin); [item.__setitem__("Path" if "Path" in item else "path",os.environ["INPLACEX_TEST_UNSAFE_PLUGIN"]) for item in value if item.get("Name",item.get("name")) == "compose"]; json.dump(value,sys.stdout)'
    exit 0
fi
case "$*" in
    *pg_dump*|*pg_restore*|*'DROP DATABASE'*)
        printf 'FORBIDDEN_STATE_MUTATION %s\n' "$*" >> "$INPLACEX_TEST_DOCKER_LOG"
        exit 98
        ;;
esac
if [[ "$*" == *' stop --timeout 30 backend' ]]; then
    printf 'INTERCEPTED_BACKEND_STOP %s\n' "$INPLACEX_TEST_STOP_MODE" >> "$INPLACEX_TEST_DOCKER_LOG"
    case "$INPLACEX_TEST_STOP_MODE" in
        failure) exit 42 ;;
        still-running) exit 0 ;;
        *) exit 67 ;;
    esac
fi
exec "$INPLACEX_TEST_REAL_DOCKER" "$@"
MOCK
chmod 0700 "$hostile_docker_directory/docker"
unsafe_plugin_path="$(mktemp /tmp/inplacex-hostile-docker-plugin.XXXXXX)"
printf '#!/usr/bin/env bash\nexit 99\n' > "$unsafe_plugin_path"
chown root:root "$unsafe_plugin_path"
chmod 0700 "$unsafe_plugin_path"
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

if ! docker run --detach --name "$registry_name" \
    --publish "127.0.0.1:$registry_port:5000" registry:2 >/dev/null; then
    echo "Anonymous registry container failed to start." >&2
    print_registry_diagnostics anonymous "$registry_name"
    exit 70
fi
wait_for_registry anonymous "$registry_name" "$registry_port" anonymous

install -d -o root -g root -m 0700 "$authenticated_registry_directory"
if ! htpasswd -Bbn "$authenticated_registry_username" "$authenticated_registry_password" \
    > "$authenticated_registry_directory/htpasswd"; then
    echo "Authenticated registry htpasswd fixture generation failed." >&2
    exit 70
fi
chown root:root "$authenticated_registry_directory/htpasswd"
chmod 0600 "$authenticated_registry_directory/htpasswd"
if [[ "$(stat -Lc '%F %u %g %a %h' -- "$authenticated_registry_directory/htpasswd")" != \
    "regular file 0 0 600 1" ]]; then
    echo "Authenticated registry htpasswd fixture identity is unsafe." >&2
    exit 70
fi
if ! docker run --detach --name "$authenticated_registry_name" \
    --publish "127.0.0.1:$authenticated_registry_port:5000" \
    --env REGISTRY_AUTH=htpasswd \
    --env REGISTRY_AUTH_HTPASSWD_REALM=InplaceX-CI \
    --env REGISTRY_AUTH_HTPASSWD_PATH=/auth/htpasswd \
    --volume "$authenticated_registry_directory:/auth:ro" \
    registry:2 >/dev/null; then
    echo "Authenticated registry container failed to start." >&2
    print_registry_diagnostics authenticated "$authenticated_registry_name"
    exit 70
fi
wait_for_registry authenticated "$authenticated_registry_name" \
    "$authenticated_registry_port" authenticated
unauthenticated_registry_status=0
unauthenticated_registry_http_code="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    "http://127.0.0.1:$authenticated_registry_port/v2/")" || unauthenticated_registry_status=$?
if [[ "$unauthenticated_registry_status" -ne 0 || \
    "$unauthenticated_registry_http_code" != "401" ]]; then
    echo "Authenticated registry did not fail closed for an anonymous request "\
        "(curl_status=$unauthenticated_registry_status, "\
        "http_code=${unauthenticated_registry_http_code:-none})." >&2
    print_registry_diagnostics authenticated "$authenticated_registry_name"
    exit 70
fi
echo "Authenticated registry rejected anonymous access (http_code=401)."

postgres_pull_log="$test_root/postgres-image.pull.log"
if ! docker pull postgres:16-alpine > "$postgres_pull_log" 2>&1; then
    echo "PostgreSQL integration image pull failed." >&2
    print_sanitized_ci_log "$postgres_pull_log"
    exit 69
fi
postgres_image="$(docker image inspect --format '{{index .RepoDigests 0}}' \
    postgres:16-alpine 2>/dev/null || true)"
if [[ ! "$postgres_image" =~ @sha256:[0-9a-f]{64}$ ]]; then
    echo "PostgreSQL integration image has no immutable repository digest: "\
        "${postgres_image:-missing}." >&2
    print_sanitized_ci_log "$postgres_pull_log"
    exit 70
fi
echo "PostgreSQL integration image resolved to ${postgres_image##*@}."
docker volume create \
    --label com.mirkori.product=inplacex \
    --label com.mirkori.component=online-postgres \
    --label com.mirkori.managed=true \
    "$postgres_volume" >/dev/null

build_authenticated_registry_probe() {
    local release_id="registry-auth-$test_id"
    local image_tag="127.0.0.1:$authenticated_registry_port/inplacex-backend:$release_id"
    local manifest_path="$test_root/$release_id.manifest.json"
    local build_log="$test_root/$release_id.build.log"
    local registry_auth_config="$registry_auth_test_directory/authenticated-registry.json"
    local docker_client_root="$test_root/authenticated-registry-client"
    local inspect_log="$test_root/$release_id.imagetools.json"
    local pull_log="$test_root/$release_id.pull.log"
    local image_inspect_log="$test_root/$release_id.image-inspect.json"
    local build_status=0
    local immutable_image repo_digests credential_marker

    printf '{"auths":{"127.0.0.1:%s":{"auth":"%s"}}}\n' \
        "$authenticated_registry_port" "$authenticated_registry_auth_base64" \
        > "$registry_auth_config"
    chown root:root "$registry_auth_config"
    chmod 0600 "$registry_auth_config"

    "$production_directory/build-backend-release.sh" \
        "$image_tag" "$release_id" "$manifest_path" --push \
        --registry-auth-config "$registry_auth_config" > "$build_log" 2>&1 || build_status=$?
    if [[ "$build_status" -ne 0 ]]; then
        echo "Authenticated registry release build failed (status=$build_status)." >&2
        echo "--- authenticated registry release build log ---" >&2
        print_sanitized_ci_log "$build_log"
        echo "--- end authenticated registry release build log ---" >&2
        print_registry_diagnostics authenticated "$authenticated_registry_name"
        return "$build_status"
    fi
    assert_no_inplacex_release_builders
    immutable_image="$(python3 -I - "$manifest_path" "$release_id" "$git_sha" \
        "$source_archive_sha256" <<'PY'
import json
import pathlib
import re
import sys

manifest = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if manifest.get("schemaVersion") != 2:
    raise SystemExit("Authenticated builder did not emit schema v2")
if manifest.get("releaseId") != sys.argv[2] or manifest.get("gitSha") != sys.argv[3]:
    raise SystemExit("Authenticated builder manifest identity differs from exact HEAD")
if manifest.get("sourceArchiveSha256") != sys.argv[4]:
    raise SystemExit("Authenticated builder source archive identity differs")
image = manifest.get("image")
if not isinstance(image, str) or re.search(r"@sha256:[0-9a-f]{64}$", image) is None:
    raise SystemExit("Authenticated builder manifest has no immutable image")
evidence = manifest.get("attestationEvidence")
if not isinstance(evidence, dict) or not evidence.get("attestationManifestDigests"):
    raise SystemExit("Authenticated builder manifest has no attestation evidence")
print(image)
PY
)"

    install -d -o root -g root -m 0700 \
        "$docker_client_root" "$docker_client_root/home" "$docker_client_root/config"
    install -o root -g root -m 0600 \
        "$registry_auth_config" "$docker_client_root/config/config.json"
    local -a authenticated_docker=(
        /usr/bin/env -i
        PATH=/usr/sbin:/usr/bin:/sbin:/bin
        HOME="$docker_client_root/home"
        DOCKER_CONFIG="$docker_client_root/config"
        DOCKER_HOST=unix:///var/run/docker.sock
        LANG=C
        LC_ALL=C
        "$real_docker"
    )
    "${authenticated_docker[@]}" buildx imagetools inspect "$immutable_image" --raw \
        > "$inspect_log"
    "${authenticated_docker[@]}" pull "$immutable_image" > "$pull_log" 2>&1
    "${authenticated_docker[@]}" image inspect "$immutable_image" > "$image_inspect_log"
    repo_digests="$("${authenticated_docker[@]}" image inspect \
        --format '{{json .RepoDigests}}' "$immutable_image")"
    python3 -I - "$repo_digests" "$immutable_image" <<'PY'
import json
import sys

repo_digests = json.loads(sys.argv[1])
if not isinstance(repo_digests, list) or sys.argv[2] not in repo_digests:
    raise SystemExit("Authenticated pull did not retain the exact repository digest")
PY

    for credential_marker in \
        "$authenticated_registry_password" \
        "$authenticated_registry_basic" \
        "$authenticated_registry_auth_base64"; do
        if grep -Fq -- "$credential_marker" \
            "$build_log" "$manifest_path" "$inspect_log" "$pull_log" "$image_inspect_log"; then
            echo "Authenticated registry credential leaked into release evidence or logs." >&2
            exit 70
        fi
    done
    rm -rf -- "$docker_client_root"
    rm -f -- "$registry_auth_config"
    [[ -z "$(find /run -maxdepth 1 -name 'inplacex-release-build.*' -print -quit)" ]] || {
        echo "Authenticated builder left its credential-bearing temporary directory behind." >&2
        exit 70
    }
}

build_release_image() {
    local release_id="$1"
    local image_tag="127.0.0.1:$registry_port/inplacex-backend:$release_id"
    local immutable_image manifest_path build_log
    manifest_path="$test_root/$release_id.manifest.json"
    build_log="$test_root/$release_id.build.log"
    INPLACEX_RELEASE_ISOLATED_CI_ACK=acknowledge-inplacex-isolated-release-ci \
        "$production_directory/build-backend-release.sh" \
        "$image_tag" "$release_id" "$manifest_path" --push --anonymous-loopback \
        > "$build_log"
    assert_no_inplacex_release_builders
    immutable_image="$(python3 -I - "$manifest_path" "$release_id" "$git_sha" \
        "$source_archive_sha256" <<'PY'
import json
import pathlib
import re
import sys

manifest = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if manifest.get("schemaVersion") != 2:
    raise SystemExit("Production builder did not emit release manifest schema v2")
if manifest.get("releaseId") != sys.argv[2] or manifest.get("gitSha") != sys.argv[3]:
    raise SystemExit("Production builder manifest identity differs from clean HEAD")
if manifest.get("sourceArchiveSha256") != sys.argv[4]:
    raise SystemExit("Production builder source archive differs from CI immutable archive")
image = manifest.get("image")
if not isinstance(image, str) or re.search(r"@sha256:[0-9a-f]{64}$", image) is None:
    raise SystemExit("Production builder manifest has no immutable image")
evidence = manifest.get("attestationEvidence")
if not isinstance(evidence, dict) or not evidence.get("attestationManifestDigests"):
    raise SystemExit("Production builder manifest has no attestation evidence")
print(image)
PY
    )"
    [[ "$immutable_image" =~ @sha256:[0-9a-f]{64}$ ]]
    printf '%s\n' "$immutable_image"
}

build_legacy_activation_v1_image() {
    local release_id="$1"
    local legacy_source_root="$test_root/legacy-activation-v1-source"
    local fixture_patch="$legacy_source_root/scripts/ci/fixtures/runtime-activation-v1.patch"
    local fixture_sha256=f6af7acbc341481c640855cccd8a34e3c128cbd31b2aebfa453882af5a94a2e6
    local activation_guard=InplaceX-backend/src/main/kotlin/com/mirkori/inplacex/backend/app/RuntimeActivationGuard.kt
    local image_tag="127.0.0.1:$registry_port/inplacex-backend:$release_id-activation-v1"
    local immutable_image manifest_path fixture_git_sha fixture_source_sha
    local build_log="$test_root/$release_id-activation-v1.build.log"
    local -a fixture_git
    install -d -o root -g root -m 0700 "$test_root/fixture-git-home"
    fixture_git=(
        /usr/bin/env -i
        PATH=/usr/sbin:/usr/bin:/sbin:/bin
        HOME="$test_root/fixture-git-home"
        LANG=C
        LC_ALL=C
        GIT_CONFIG_GLOBAL=/dev/null
        GIT_CONFIG_NOSYSTEM=1
        GIT_AUTHOR_NAME=InplaceX-CI
        GIT_AUTHOR_EMAIL=ci@inplacex.invalid
        GIT_COMMITTER_NAME=InplaceX-CI
        GIT_COMMITTER_EMAIL=ci@inplacex.invalid
        GIT_AUTHOR_DATE=2000-01-01T00:00:00Z
        GIT_COMMITTER_DATE=2000-01-01T00:00:00Z
        /usr/bin/git --no-replace-objects -c core.fsmonitor=false -c core.hooksPath=/dev/null
    )
    install -d -o root -g root -m 0700 "$legacy_source_root"
    tar -xf "$current_source_archive" -C "$legacy_source_root"
    [[ "$(sha256sum "$fixture_patch" | awk '{print $1}')" == "$fixture_sha256" ]] || {
        echo "Immutable runtime-activation v1 fixture digest changed." >&2
        exit 70
    }
    grep -q 'private const val StateVersion = "2"' "$legacy_source_root/$activation_guard"
    patch --directory="$legacy_source_root" --strip=1 --reverse --fuzz=0 < "$fixture_patch"
    grep -q 'private const val StateVersion = "1"' \
        "$legacy_source_root/$activation_guard"
    if grep -q 'DatabasePasswordShaField' "$legacy_source_root/$activation_guard"; then
        echo "Runtime-activation v1 fixture still contains v2 fields." >&2
        exit 70
    fi
    chmod 0755 \
        "$legacy_source_root/ops/production/build-backend-release.sh" \
        "$legacy_source_root/ops/production/release-common.sh"
    chmod 0644 \
        "$legacy_source_root/ops/production/release-shell-bootstrap.sh" \
        "$legacy_source_root/ops/production/create-source-archive.py" \
        "$legacy_source_root/ops/Dockerfile"
    "${fixture_git[@]}" -C "$legacy_source_root" \
        init --initial-branch=activation-v1-fixture >/dev/null
    "${fixture_git[@]}" -C "$legacy_source_root" add --all
    "${fixture_git[@]}" -C "$legacy_source_root" \
        commit --message='Immutable activation v1 runtime fixture' >/dev/null
    fixture_git_sha="$("${fixture_git[@]}" -C "$legacy_source_root" rev-parse HEAD)"
    manifest_path="$test_root/$release_id.manifest.json"
    INPLACEX_RELEASE_ISOLATED_CI_ACK=acknowledge-inplacex-isolated-release-ci \
        "$legacy_source_root/ops/production/build-backend-release.sh" \
        "$image_tag" "$release_id" "$manifest_path" --push --anonymous-loopback \
        > "$build_log"
    assert_no_inplacex_release_builders
    IFS='|' read -r immutable_image fixture_git_sha fixture_source_sha < <(
        python3 -I - "$manifest_path" "$release_id" "$fixture_git_sha" <<'PY'
import json
import pathlib
import re
import sys

manifest = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
if manifest.get("schemaVersion") != 2 or manifest.get("releaseId") != sys.argv[2]:
    raise SystemExit("Activation-v1 builder manifest schema/release identity is invalid")
if manifest.get("gitSha") != sys.argv[3]:
    raise SystemExit("Activation-v1 builder manifest is not bound to its fixture commit")
image = manifest.get("image")
source_sha = manifest.get("sourceArchiveSha256")
if not isinstance(image, str) or re.search(r"@sha256:[0-9a-f]{64}$", image) is None:
    raise SystemExit("Activation-v1 builder did not publish an immutable image")
if not isinstance(source_sha, str) or re.fullmatch(r"[0-9a-f]{64}", source_sha) is None:
    raise SystemExit("Activation-v1 builder source archive identity is invalid")
print(f"{image}|{manifest['gitSha']}|{source_sha}")
PY
    )
    [[ "$immutable_image" =~ @sha256:[0-9a-f]{64}$ ]]
    printf '%s|%s|%s\n' "$immutable_image" "$fixture_git_sha" "$fixture_source_sha"
}

write_environment() {
    local release_id="$1"
    local backend_image="$2"
    local initial_deploy="$3"
    local legacy_checksum_ack="${4:-}"
    local activation_v1_migration_ack="${5:-}"
    local environment_git_sha="${6:-$git_sha}"
    local environment_source_archive_sha256="${7:-$source_archive_sha256}"
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
        printf 'INPLACEX_GIT_SHA=%s\n' "$environment_git_sha"
        printf 'INPLACEX_IMAGE_DIGEST=%s\n' "$image_digest"
        printf 'INPLACEX_SOURCE_ARCHIVE_SHA256=%s\n' "$environment_source_archive_sha256"
        printf 'INPLACEX_RELEASE_MANIFEST_PATH=%s\n' "$manifest_path"
        printf 'INPLACEX_INITIAL_DEPLOY=%s\n' "$initial_deploy"
        printf 'INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS=180\n'
        printf 'INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK=%s\n' "$legacy_checksum_ack"
        printf 'INPLACEX_ALLOW_PUBLIC_KEY_ROTATION_FROM_SHA256=\n'
        printf 'INPLACEX_ACTIVATION_V1_MIGRATION_ACK=%s\n' "$activation_v1_migration_ack"
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
}

run_with_hostile_backend_stop() {
    local stop_mode="$1"
    local hostile_status
    shift
    : > "$hostile_docker_log"
    set +e
    env \
        INPLACEX_RELEASE_ISOLATED_CI_ACK=acknowledge-inplacex-isolated-release-ci \
        INPLACEX_RELEASE_TEST_DOCKER_BIN="$hostile_docker_directory/docker" \
        INPLACEX_TEST_REAL_DOCKER="$real_docker" \
        INPLACEX_TEST_DOCKER_LOG="$hostile_docker_log" \
        INPLACEX_TEST_STOP_MODE="$stop_mode" \
        "$@"
    hostile_status=$?
    set -e
    [[ "$hostile_status" -ne 0 ]]
    grep -Fq "INTERCEPTED_BACKEND_STOP $stop_mode" "$hostile_docker_log"
    if grep -Fq 'FORBIDDEN_STATE_MUTATION' "$hostile_docker_log"; then
        echo "A database command ran after an unproven backend stop." >&2
        exit 70
    fi
    [[ -f /run/inplacex-online/maintenance.flag ]]
    [[ -f /run/inplacex-online/drain.flag ]]
    [[ -f "$release_state_directory/release-transaction.env" ]]
}

run_with_hostile_docker_control() {
    local control_mode="$1"
    local hostile_status
    shift
    : > "$hostile_docker_log"
    rm -f -- "$test_root/hostile-control-counter"
    set +e
    env \
        INPLACEX_RELEASE_ISOLATED_CI_ACK=acknowledge-inplacex-isolated-release-ci \
        INPLACEX_RELEASE_TEST_DOCKER_BIN="$hostile_docker_directory/docker" \
        INPLACEX_TEST_REAL_DOCKER="$real_docker" \
        INPLACEX_TEST_DOCKER_LOG="$hostile_docker_log" \
        INPLACEX_TEST_CONTROL_MODE="$control_mode" \
        INPLACEX_TEST_CONTROL_COUNTER="$test_root/hostile-control-counter" \
        INPLACEX_TEST_UNSAFE_PLUGIN="$unsafe_plugin_path" \
        "$@"
    hostile_status=$?
    set -e
    [[ "$hostile_status" -ne 0 ]]
    if grep -Fq 'FORBIDDEN_STATE_MUTATION' "$hostile_docker_log"; then
        echo "A release mutation ran after hostile Docker control-plane selection." >&2
        exit 70
    fi
    [[ ! -e "$release_state_directory/release-transaction.env" ]]
    [[ ! -e /run/inplacex-online/maintenance.flag && ! -e /run/inplacex-online/drain.flag ]]
    curl --fail --silent "http://127.0.0.1:$backend_port/ready" >/dev/null
}

clear_hostile_release_attempt() {
    rm -f -- /run/inplacex-online/maintenance.flag /run/inplacex-online/drain.flag
    rm -f -- "$release_state_directory/release-transaction.env"
    sync -f /run/inplacex-online
    sync -f "$release_state_directory"
}

build_authenticated_registry_probe

release_v1="integration-v1-$test_id"
image_v1="$(build_release_image "$release_v1")"
write_environment "$release_v1" "$image_v1" true
schema_v1_manifest="$test_root/$release_v1.schema-v1.manifest.json"
schema_v1_environment="$test_root/backend-schema-v1.env"
python3 -I - \
    "$test_root/$release_v1.manifest.json" "$schema_v1_manifest" \
    "$env_file" "$schema_v1_environment" <<'PY'
import json
import pathlib
import sys

source_manifest = pathlib.Path(sys.argv[1])
schema_v1_manifest = pathlib.Path(sys.argv[2])
source_environment = pathlib.Path(sys.argv[3])
schema_v1_environment = pathlib.Path(sys.argv[4])

manifest = json.loads(source_manifest.read_text(encoding="utf-8"))
manifest["schemaVersion"] = 1
manifest.pop("attestationEvidence", None)
expected_fields = {
    "schemaVersion", "component", "releaseId", "gitSha", "sourceArchiveSha256",
    "image", "imageDigest", "builderBase", "runtimeBase", "attestations",
}
if set(manifest) != expected_fields:
    raise SystemExit("Strict schema-v1 compatibility fixture has unexpected fields")
schema_v1_manifest.write_text(
    json.dumps(manifest, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)

lines = source_environment.read_text(encoding="utf-8").splitlines()
matches = [
    index for index, line in enumerate(lines)
    if line.startswith("INPLACEX_RELEASE_MANIFEST_PATH=")
]
if len(matches) != 1:
    raise SystemExit("Integration environment has no exact release-manifest field")
lines[matches[0]] = f"INPLACEX_RELEASE_MANIFEST_PATH={schema_v1_manifest}"
schema_v1_environment.write_text("\n".join(lines) + "\n", encoding="utf-8")
PY
chown root:root "$schema_v1_manifest" "$schema_v1_environment"
chmod 0600 "$schema_v1_manifest" "$schema_v1_environment"
schema_bool_manifest="$test_root/$release_v1.schema-bool.manifest.json"
schema_bool_environment="$test_root/backend-schema-bool.env"
python3 -I - "$test_root/$release_v1.manifest.json" "$schema_bool_manifest" <<'PY'
import json
import pathlib
import sys

value = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
value["schemaVersion"] = True
pathlib.Path(sys.argv[2]).write_text(
    json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY
python3 -I - "$env_file" "$schema_bool_environment" "$schema_bool_manifest" <<'PY'
import pathlib
import sys

lines = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
replacement = f"INPLACEX_RELEASE_MANIFEST_PATH={sys.argv[3]}"
matches = [index for index, line in enumerate(lines) if line.startswith("INPLACEX_RELEASE_MANIFEST_PATH=")]
if len(matches) != 1:
    raise SystemExit("Integration environment has no exact release-manifest field")
lines[matches[0]] = replacement
pathlib.Path(sys.argv[2]).write_text("\n".join(lines) + "\n", encoding="utf-8")
PY
chown root:root "$schema_bool_manifest" "$schema_bool_environment"
chmod 0600 "$schema_bool_manifest" "$schema_bool_environment"
set +e
bash "$production_directory/deploy-backend.sh" \
    "$schema_bool_environment" "$backup_directory" \
    > "$test_root/schema-bool-deploy.log" 2>&1
schema_bool_status=$?
set -e
[[ "$schema_bool_status" -ne 0 ]]
grep -Fq 'schemaVersion must be the integer 1 or 2' \
    "$test_root/schema-bool-deploy.log"
[[ ! -e "$release_state_directory/release-transaction.env" ]]
[[ ! -e /run/inplacex-online/maintenance.flag && ! -e /run/inplacex-online/drain.flag ]]
bash "$production_directory/deploy-backend.sh" "$schema_v1_environment" "$backup_directory"

run_with_hostile_docker_control \
    daemon-changed \
    bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"
run_with_hostile_docker_control \
    unsafe-plugin \
    bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"

[[ "$(stat -c '%u %g %a' -- "$release_state_directory")" == "0 0 700" ]]
[[ "$(stat -c '%u %g %a' -- "$release_state_directory/activation")" == "0 $secret_gid 750" ]]
[[ "$(stat -c '%u %g %a' -- "$release_state_directory/activation/verified-activation.env")" == \
    "0 $secret_gid 440" ]]
[[ "$(stat -c '%u %g %a' -- /run/lock/mirkori-games)" == "0 0 700" ]]
[[ "$(stat -c '%u %g %a' -- /run/lock/mirkori-games/inplacex-online-release.lock)" == "0 0 600" ]]
[[ ! -e "$release_state_directory/release-transaction.env" ]]

database_password_file="$secret_directory/database-password.txt"
database_password_original="$test_root/database-password.original"
cp -- "$database_password_file" "$database_password_original"
printf 'unmanaged-database-password-drift\n' > "$database_password_file"
chown root:"$secret_gid" "$database_password_file"
chmod 0640 "$database_password_file"
set +e
bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"
database_password_drift_status=$?
set -e
[[ "$database_password_drift_status" -ne 0 ]]
[[ ! -e "$release_state_directory/release-transaction.env" ]]
[[ ! -e /run/inplacex-online/maintenance.flag && ! -e /run/inplacex-online/drain.flag ]]
cp -- "$database_password_original" "$database_password_file"
chown root:"$secret_gid" "$database_password_file"
chmod 0640 "$database_password_file"
sync -f "$database_password_file"

geoip_original="$test_root/geoip-original.mmdb"
cp -- "$geoip_file" "$geoip_original"
printf 'unmanaged-geoip-drift\n' > "$geoip_file"
chown root:root "$geoip_file"
chmod 0644 "$geoip_file"
set +e
bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"
geoip_drift_status=$?
set -e
[[ "$geoip_drift_status" -ne 0 ]]
[[ ! -e "$release_state_directory/release-transaction.env" ]]
[[ ! -e /run/inplacex-online/maintenance.flag && ! -e /run/inplacex-online/drain.flag ]]
curl --fail --silent "http://127.0.0.1:$backend_port/ready" >/dev/null
cp -- "$geoip_original" "$geoip_file"
chown root:root "$geoip_file"
chmod 0644 "$geoip_file"
sync -f "$geoip_file"

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
ticket_id="$(python3 -I -c 'import json,sys; print(json.load(sys.stdin)["ticketId"])' <<< "$ticket_response")"
sleep 2
matched_response="$(curl --fail --silent \
    --header "Authorization: Bearer $token" \
    "http://127.0.0.1:$backend_port/api/v1/matchmaking/tickets/$ticket_id")"
session_id="$(python3 -I -c 'import json,sys; value=json.load(sys.stdin)["sessionId"]; assert value; print(value)' <<< "$matched_response")"

websocket_ready="$test_root/websocket.ready"
websocket_result="$test_root/websocket.result"
python3 -I - \
    "$nginx_port" "$backend_port" "$public_hostname" "$session_id" "$token" \
    "$websocket_ready" "$websocket_result" <<'PY' &
import base64
import json
import os
import pathlib
import socket
import ssl
import struct
import sys
import urllib.request

port = int(sys.argv[1])
backend_port = int(sys.argv[2])
hostname = sys.argv[3]
session_id = sys.argv[4]
token = sys.argv[5]
ready_path = pathlib.Path(sys.argv[6])
result_path = pathlib.Path(sys.argv[7])
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
        ready_path.write_text("ready\n", encoding="utf-8")

        buffered = bytearray(response.split(b"\r\n\r\n", 1)[1])

        def read_exact(length):
            while len(buffered) < length:
                chunk = connection.recv(4096)
                if not chunk:
                    raise SystemExit("WebSocket closed without a close frame")
                buffered.extend(chunk)
            value = bytes(buffered[:length])
            del buffered[:length]
            return value

        connection.settimeout(45)
        while True:
            first, second = read_exact(2)
            opcode = first & 0x0F
            masked = bool(second & 0x80)
            length = second & 0x7F
            if length == 126:
                length = struct.unpack("!H", read_exact(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", read_exact(8))[0]
            mask = read_exact(4) if masked else b""
            payload = read_exact(length)
            if masked:
                payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
            if opcode == 8:
                code = struct.unpack("!H", payload[:2])[0] if len(payload) >= 2 else 1005
                reason = payload[2:].decode("utf-8")
                if code != 1013 or reason != "service_draining":
                    raise SystemExit(f"Unexpected drain close: {code} {reason!r}")
                result_path.write_text(f"{code} {reason}\n", encoding="utf-8")
                break
PY
websocket_pid=$!
for _ in {1..100}; do
    [[ -f "$websocket_ready" ]] && break
    kill -0 "$websocket_pid" 2>/dev/null || {
        wait "$websocket_pid"
        echo "WebSocket drain probe exited before becoming ready." >&2
        exit 70
    }
    sleep 0.1
done
[[ -f "$websocket_ready" ]] || {
    kill "$websocket_pid" >/dev/null 2>&1 || true
    wait "$websocket_pid" >/dev/null 2>&1 || true
    echo "WebSocket drain probe did not become ready." >&2
    exit 70
}

printf 'integration-drain\n' > /run/inplacex-online/drain.flag
chown root:root /run/inplacex-online/drain.flag
chmod 0644 /run/inplacex-online/drain.flag
wait "$websocket_pid"
grep -qx '1013 service_draining' "$websocket_result"
[[ "$(curl --silent --output /dev/null --write-out '%{http_code}' \
    "http://127.0.0.1:$backend_port/api/v1/matchmaking/tickets/not-a-uuid")" == "503" ]]
drain_status=""
for _ in {1..30}; do
    drain_status="$(curl --fail --silent "http://127.0.0.1:$backend_port/admin/drain/status")"
    [[ "$drain_status" == '{"draining":true,"activeRequests":0}' ]] && break
    sleep 0.1
done
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

legacy_runtime_config_sha256="$(sed -n 's/^INPLACEX_ACTIVATION_RUNTIME_CONFIG_SHA256=//p' \
    "$release_state_directory/activation/verified-activation.env")"
[[ "$legacy_runtime_config_sha256" =~ ^[0-9a-f]{64}$ ]]
mv -- "$test_root/$release_v1.manifest.json" \
    "$test_root/$release_v1.current-head.manifest.json"
sync -f "$test_root/$release_v1.current-head.manifest.json"
sync -f "$test_root"
IFS='|' read -r legacy_image_v1 legacy_git_sha legacy_source_archive_sha256 < <(
    build_legacy_activation_v1_image "$release_v1"
)
backend_container="$("${compose[@]}" ps --all -q backend)"
docker stop --time 5 "$backend_container" >/dev/null
legacy_activation_staging="$release_state_directory/activation/.legacy-v1.activation"
printf '%s\n' \
    'INPLACEX_ACTIVATION_VERSION=1' \
    "INPLACEX_ACTIVATION_RELEASE_ID=$release_v1" \
    "INPLACEX_ACTIVATION_GIT_SHA=$legacy_git_sha" \
    "INPLACEX_ACTIVATION_IMAGE_DIGEST=${legacy_image_v1##*@}" \
    "INPLACEX_ACTIVATION_STATE_KEY_SHA256=$(sha256sum "$secret_directory/online-state-key-base64.txt" | awk '{print $1}')" \
    "INPLACEX_ACTIVATION_PUBLIC_KEY_SHA256=$(sha256sum "$secret_directory/platform-public-key-x509-base64.txt" | awk '{print $1}')" \
    "INPLACEX_ACTIVATION_GEOIP_SHA256=$(sha256sum "$geoip_file" | awk '{print $1}')" \
    "INPLACEX_ACTIVATION_RUNTIME_CONFIG_SHA256=$legacy_runtime_config_sha256" \
    > "$legacy_activation_staging"
chown root:"$secret_gid" "$legacy_activation_staging"
chmod 0440 "$legacy_activation_staging"
mv -f -- "$legacy_activation_staging" \
    "$release_state_directory/activation/verified-activation.env"
sync -f "$release_state_directory/activation/verified-activation.env"
sync -f "$release_state_directory/activation"

write_environment \
    "$release_v1" "$legacy_image_v1" false "" "" \
    "$legacy_git_sha" "$legacy_source_archive_sha256"
legacy_env_file="$test_root/backend-legacy-v1.env"
cp -- "$env_file" "$legacy_env_file"
printf 'INPLACEX_RUNTIME_CONFIG_SHA256=%s\n' "$legacy_runtime_config_sha256" >> "$legacy_env_file"
chown root:root "$legacy_env_file"
chmod 0600 "$legacy_env_file"
legacy_compose=(docker compose --env-file "$legacy_env_file" --project-directory "$repository_root" -f "$compose_file")
"${legacy_compose[@]}" up --detach --force-recreate --wait --wait-timeout 180 backend
backend_container="$("${legacy_compose[@]}" ps -q backend)"
[[ "$(docker exec "$backend_container" sha256sum -- /run/secrets/inplacex_database_password | awk '{print $1}')" == \
    "$(sha256sum "$secret_directory/database-password.txt" | awk '{print $1}')" ]]
"$production_directory/smoke-backend.sh" loopback \
    "http://127.0.0.1:$backend_port" "$release_v1" "$legacy_git_sha" "${legacy_image_v1##*@}"
image_v1="$legacy_image_v1"
legacy_pointer="$backup_directory/latest-inplacex-backend-release.env"
legacy_receipt="$(sed -n 's/^RELEASE_POINTER_RECEIPT_PATH=//p' "$legacy_pointer")"
legacy_rollback_error="$test_root/legacy-rollback.error"
set +e
bash "$production_directory/rollback-backend.sh" \
    "$env_file" "$legacy_receipt" --confirm-data-restore 2> "$legacy_rollback_error"
legacy_rollback_status=$?
set -e
[[ "$legacy_rollback_status" -eq 75 ]]
grep -Fq 'Verified activation v1 must first be migrated by deploy-backend.sh' \
    "$legacy_rollback_error"
[[ ! -e "$release_state_directory/release-transaction.env" ]]
[[ ! -e /run/inplacex-online/maintenance.flag && ! -e /run/inplacex-online/drain.flag ]]

release_v2="integration-v2-$test_id"
image_v2="$(build_release_image "$release_v2")"
"${compose[@]}" exec -T postgres sh -ec \
    'export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"; exec psql --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --command="UPDATE inplacex_schema_history SET checksum = NULL;"' \
    >/dev/null
write_environment \
    "$release_v2" "$image_v2" false acknowledge-inplacex-schema-v1-v8 \
    acknowledge-inplacex-activation-v1-to-v2
compose=(docker compose --env-file "$env_file" --project-directory "$repository_root" -f "$compose_file")
for hostile_stop_mode in failure still-running; do
    run_with_hostile_backend_stop \
        "$hostile_stop_mode" \
        bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"
    grep -qx 'RELEASE_TRANSACTION_OPERATION=deploy' "$release_state_directory/release-transaction.env"
    grep -qx 'RELEASE_TRANSACTION_PHASE=intent' "$release_state_directory/release-transaction.env"
    hostile_backup_path="$(sed -n 's/^RELEASE_TRANSACTION_BACKUP_PATH=//p' \
        "$release_state_directory/release-transaction.env")"
    [[ ! -e "$hostile_backup_path" && ! -e "$hostile_backup_path.partial" ]]
    grep -qx "INPLACEX_ACTIVATION_RELEASE_ID=$release_v1" \
        "$release_state_directory/activation/verified-activation.env"
    [[ "$(docker inspect --format '{{.State.Running}}' "$("${compose[@]}" ps --all -q backend)")" == "true" ]]
    clear_hostile_release_attempt
done
set +e
INPLACEX_RELEASE_FAULT_PHASE=during_backup_staging \
INPLACEX_RELEASE_FAULT_TEST_ACK=isolated-ci-host \
    bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"
deploy_fault_status=$?
set -e
[[ "$deploy_fault_status" -ne 0 ]]
grep -qx 'RELEASE_TRANSACTION_OPERATION=deploy' "$release_state_directory/release-transaction.env"
grep -qx 'RELEASE_TRANSACTION_PHASE=postgres_ready' "$release_state_directory/release-transaction.env"
pending_backup_path="$(sed -n 's/^RELEASE_TRANSACTION_BACKUP_PATH=//p' \
    "$release_state_directory/release-transaction.env")"
[[ ! -e "$pending_backup_path" && -f "$pending_backup_path.partial" ]]
printf 'simulated-interrupted-pg-dump\n' > "$pending_backup_path.partial"
chown root:root "$pending_backup_path.partial"
chmod 0600 "$pending_backup_path.partial"
[[ -f /run/inplacex-online/maintenance.flag ]]
compgen -G '/run/inplacex-online/compose-*.env' >/dev/null

set +e
INPLACEX_RELEASE_FAULT_PHASE=after_candidate_start \
INPLACEX_RELEASE_FAULT_TEST_ACK=isolated-ci-host \
    bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"
deploy_start_fault_status=$?
set -e
[[ "$deploy_start_fault_status" -ne 0 ]]
grep -qx 'RELEASE_TRANSACTION_PHASE=candidate_starting' "$release_state_directory/release-transaction.env"
[[ -f "$pending_backup_path" && ! -e "$pending_backup_path.partial" ]]
[[ -f /run/inplacex-online/maintenance.flag ]]
sleep 2

compose=(docker compose --env-file "$env_file" --project-directory "$repository_root" -f "$compose_file")
backend_container="$("${compose[@]}" ps --all -q backend)"
reset_ephemeral_release_state
assert_backend_fails_closed "$backend_container"
set +e
timeout 25 env \
    INPLACEX_RELEASE_FAULT_PHASE=after_legacy_checksum_completed \
    INPLACEX_RELEASE_FAULT_TEST_ACK=isolated-ci-host \
    bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"
legacy_checksum_fault_status=$?
set -e
[[ "$legacy_checksum_fault_status" -ne 0 ]]
[[ "$legacy_checksum_fault_status" -ne 124 ]]
grep -qx 'RELEASE_TRANSACTION_PHASE=candidate_starting' \
    "$release_state_directory/release-transaction.env"
grep -qx 'RELEASE_TRANSACTION_LEGACY_CHECKSUM_ACKNOWLEDGED=true' \
    "$release_state_directory/release-transaction.env"
grep -qx 'RELEASE_TRANSACTION_LEGACY_CHECKSUM_COMPLETED=true' \
    "$release_state_directory/release-transaction.env"

backend_container="$("${compose[@]}" ps --all -q backend)"
reset_ephemeral_release_state
assert_backend_fails_closed "$backend_container"
set +e
timeout 25 env \
    INPLACEX_RELEASE_FAULT_PHASE=activation_v1_after_v2_record \
    INPLACEX_RELEASE_FAULT_TEST_ACK=isolated-ci-host \
    bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"
activation_v1_record_fault_status=$?
set -e
[[ "$activation_v1_record_fault_status" -ne 0 ]]
[[ "$activation_v1_record_fault_status" -ne 124 ]]
grep -qx 'RELEASE_TRANSACTION_PHASE=candidate_verified' \
    "$release_state_directory/release-transaction.env"
grep -qx 'RELEASE_TRANSACTION_ACTIVATION_V1_MIGRATION_ACKNOWLEDGED=true' \
    "$release_state_directory/release-transaction.env"
grep -qx 'RELEASE_TRANSACTION_ACTIVATION_V1_MIGRATION_COMPLETED=false' \
    "$release_state_directory/release-transaction.env"
grep -qx 'INPLACEX_ACTIVATION_VERSION=2' \
    "$release_state_directory/activation/verified-activation.env"
grep -qx "INPLACEX_ACTIVATION_RELEASE_ID=$release_v2" \
    "$release_state_directory/activation/verified-activation.env"

reset_ephemeral_release_state
curl --fail --silent "http://127.0.0.1:$backend_port/ready" >/dev/null
set +e
timeout 25 env \
    INPLACEX_RELEASE_FAULT_PHASE=after_activation \
    INPLACEX_RELEASE_FAULT_TEST_ACK=isolated-ci-host \
    bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"
deploy_activation_fault_status=$?
set -e
[[ "$deploy_activation_fault_status" -ne 0 ]]
[[ "$deploy_activation_fault_status" -ne 124 ]]
grep -qx 'RELEASE_TRANSACTION_PHASE=candidate_verified' \
    "$release_state_directory/release-transaction.env"
grep -qx 'RELEASE_TRANSACTION_ACTIVATION_V1_MIGRATION_COMPLETED=true' \
    "$release_state_directory/release-transaction.env"
grep -qx "INPLACEX_ACTIVATION_RELEASE_ID=$release_v2" \
    "$release_state_directory/activation/verified-activation.env"

reset_ephemeral_release_state
curl --fail --silent "http://127.0.0.1:$backend_port/ready" >/dev/null
set +e
timeout 25 env \
    INPLACEX_RELEASE_FAULT_PHASE=after_gate \
    INPLACEX_RELEASE_FAULT_TEST_ACK=isolated-ci-host \
    bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"
deploy_redrain_fault_status=$?
set -e
[[ "$deploy_redrain_fault_status" -ne 0 ]]
[[ "$deploy_redrain_fault_status" -ne 124 ]]
grep -qx 'RELEASE_TRANSACTION_PHASE=candidate_verified' \
    "$release_state_directory/release-transaction.env"
[[ -f /run/inplacex-online/maintenance.flag && -f /run/inplacex-online/drain.flag ]]

set +e
INPLACEX_RELEASE_FAULT_PHASE=deploy_after_gates_removed \
INPLACEX_RELEASE_FAULT_TEST_ACK=isolated-ci-host \
    bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"
deploy_finalize_fault_status=$?
set -e
[[ "$deploy_finalize_fault_status" -ne 0 ]]
grep -qx 'RELEASE_TRANSACTION_PHASE=activation_committed' \
    "$release_state_directory/release-transaction.env"
[[ ! -e /run/inplacex-online/maintenance.flag && ! -e /run/inplacex-online/drain.flag ]]
grep -qx "INPLACEX_ACTIVATION_RELEASE_ID=$release_v2" \
    "$release_state_directory/activation/verified-activation.env"

bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"
[[ ! -e "$release_state_directory/release-transaction.env" ]]
grep -qx "INPLACEX_ACTIVATION_RELEASE_ID=$release_v2" \
    "$release_state_directory/activation/verified-activation.env"
write_environment "$release_v2" "$image_v2" false
migration_pointer="$backup_directory/latest-inplacex-backend-release.env"
migration_receipt="$(sed -n 's/^RELEASE_POINTER_RECEIPT_PATH=//p' "$migration_pointer")"
grep -qx 'ROLLBACK_PREVIOUS_ACTIVATION_VERSION=1' "$migration_receipt"
migration_rollback_error="$test_root/migration-rollback.error"
set +e
bash "$production_directory/rollback-backend.sh" \
    "$env_file" "$migration_receipt" --confirm-data-restore 2> "$migration_rollback_error"
migration_rollback_status=$?
set -e
[[ "$migration_rollback_status" -eq 75 ]]
grep -Fq 'Rollback across the activation v1-to-v2 migration boundary is unsafe' \
    "$migration_rollback_error"
[[ ! -e "$release_state_directory/release-transaction.env" ]]
[[ ! -e /run/inplacex-online/maintenance.flag && ! -e /run/inplacex-online/drain.flag ]]

geoip_candidate="$test_root/geoip-candidate.mmdb"
printf 'country-header-mode-rotated-artifact\n' > "$geoip_candidate"
chown root:root "$geoip_candidate"
chmod 0644 "$geoip_candidate"
geoip_candidate_sha256="$(sha256sum "$geoip_candidate" | awk '{print $1}')"
geoip_before_hostile_sha256="$(sha256sum "$geoip_file" | awk '{print $1}')"
run_with_hostile_backend_stop \
    still-running \
    bash "$production_directory/rotate-geoip.sh" \
        "$env_file" --candidate-file "$geoip_candidate"
grep -qx 'RELEASE_TRANSACTION_OPERATION=geoip' "$release_state_directory/release-transaction.env"
grep -qx 'RELEASE_TRANSACTION_PHASE=candidate_ready' "$release_state_directory/release-transaction.env"
[[ "$(sha256sum "$geoip_file" | awk '{print $1}')" == "$geoip_before_hostile_sha256" ]]
[[ -f "$release_state_directory/geoip-rotation.pending.previous.mmdb" ]]
[[ -f "$(dirname -- "$geoip_file")/.geoip-rotation.pending.candidate.mmdb" ]]
clear_hostile_release_attempt
rm -f -- \
    "$release_state_directory/geoip-rotation.pending.previous.mmdb" \
    "$(dirname -- "$geoip_file")/.geoip-rotation.pending.candidate.mmdb"
sync -f "$release_state_directory"
sync -f "$(dirname -- "$geoip_file")"
set +e
INPLACEX_RELEASE_FAULT_PHASE=geoip_after_install \
INPLACEX_RELEASE_FAULT_TEST_ACK=isolated-ci-host \
    bash "$production_directory/rotate-geoip.sh" \
        "$env_file" --candidate-file "$geoip_candidate"
geoip_install_fault_status=$?
set -e
[[ "$geoip_install_fault_status" -ne 0 ]]
grep -qx 'RELEASE_TRANSACTION_OPERATION=geoip' "$release_state_directory/release-transaction.env"
grep -qx 'RELEASE_TRANSACTION_PHASE=geoip_installed' \
    "$release_state_directory/release-transaction.env"
[[ -f /run/inplacex-online/maintenance.flag ]]

backend_container="$("${compose[@]}" ps --all -q backend)"
reset_ephemeral_release_state
assert_backend_fails_closed "$backend_container"
set +e
timeout 25 env \
    INPLACEX_RELEASE_FAULT_PHASE=geoip_after_verified_activation \
    INPLACEX_RELEASE_FAULT_TEST_ACK=isolated-ci-host \
    bash "$production_directory/rotate-geoip.sh" \
        "$env_file" --candidate-file "$geoip_candidate"
geoip_verified_activation_fault_status=$?
set -e
[[ "$geoip_verified_activation_fault_status" -ne 0 ]]
[[ "$geoip_verified_activation_fault_status" -ne 124 ]]
grep -qx 'RELEASE_TRANSACTION_PHASE=geoip_installed' \
    "$release_state_directory/release-transaction.env"
grep -qx "INPLACEX_ACTIVATION_GEOIP_SHA256=$geoip_candidate_sha256" \
    "$release_state_directory/activation/verified-activation.env"

reset_ephemeral_release_state
curl --fail --silent "http://127.0.0.1:$backend_port/ready" >/dev/null
set +e
timeout 25 env \
    INPLACEX_RELEASE_FAULT_PHASE=geoip_after_activation \
    INPLACEX_RELEASE_FAULT_TEST_ACK=isolated-ci-host \
    bash "$production_directory/rotate-geoip.sh" \
        "$env_file" --candidate-file "$geoip_candidate"
geoip_activation_fault_status=$?
set -e
[[ "$geoip_activation_fault_status" -ne 0 ]]
[[ "$geoip_activation_fault_status" -ne 124 ]]
grep -qx 'RELEASE_TRANSACTION_PHASE=activation_committed' \
    "$release_state_directory/release-transaction.env"
grep -qx "INPLACEX_ACTIVATION_GEOIP_SHA256=$geoip_candidate_sha256" \
    "$release_state_directory/activation/verified-activation.env"
[[ -f /run/inplacex-online/maintenance.flag && -f /run/inplacex-online/drain.flag ]]

bash "$production_directory/rotate-geoip.sh" \
    "$env_file" --candidate-file "$geoip_candidate"
[[ ! -e "$release_state_directory/release-transaction.env" ]]
[[ ! -e /run/inplacex-online/maintenance.flag && ! -e /run/inplacex-online/drain.flag ]]
[[ "$(sha256sum "$geoip_file" | awk '{print $1}')" == "$geoip_candidate_sha256" ]]
curl --fail --silent "http://127.0.0.1:$backend_port/ready" >/dev/null

release_v3="integration-v3-$test_id"
image_v3="$(build_release_image "$release_v3")"
write_environment "$release_v3" "$image_v3" false
bash "$production_directory/deploy-backend.sh" "$env_file" "$backup_directory"
grep -qx "INPLACEX_ACTIVATION_RELEASE_ID=$release_v3" \
    "$release_state_directory/activation/verified-activation.env"

latest_pointer="$backup_directory/latest-inplacex-backend-release.env"
receipt_path="$(sed -n 's/^RELEASE_POINTER_RECEIPT_PATH=//p' "$latest_pointer")"
[[ -f "$receipt_path" ]]
grep -qx 'ROLLBACK_PREVIOUS_ACTIVATION_VERSION=2' "$receipt_path"
run_with_hostile_backend_stop \
    failure \
    bash "$production_directory/rollback-backend.sh" \
        "$env_file" "$receipt_path" --confirm-data-restore
grep -qx 'RELEASE_TRANSACTION_OPERATION=rollback' "$release_state_directory/release-transaction.env"
grep -qx 'RELEASE_TRANSACTION_PHASE=intent' "$release_state_directory/release-transaction.env"
hostile_emergency_backup="$(sed -n 's/^RELEASE_TRANSACTION_EMERGENCY_BACKUP_PATH=//p' \
    "$release_state_directory/release-transaction.env")"
[[ ! -e "$hostile_emergency_backup" && ! -e "$hostile_emergency_backup.partial" ]]
grep -qx "INPLACEX_ACTIVATION_RELEASE_ID=$release_v3" \
    "$release_state_directory/activation/verified-activation.env"
clear_hostile_release_attempt

receipt_original="$test_root/rollback-receipt.original"
receipt_staging="$backup_directory/.rollback-receipt.integration"
cp -- "$receipt_path" "$receipt_original"
python3 -I - "$receipt_path" "$receipt_staging" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines()
target = pathlib.Path(sys.argv[2])
rewritten = []
found = False
for line in source:
    if line.startswith("ROLLBACK_DATABASE_SYSTEM_IDENTIFIER="):
        value = int(line.split("=", 1)[1])
        rewritten.append(f"ROLLBACK_DATABASE_SYSTEM_IDENTIFIER={value + 1}")
        found = True
    else:
        rewritten.append(line)
if not found:
    raise SystemExit("Rollback receipt has no PostgreSQL system identifier")
target.write_text("\n".join(rewritten) + "\n", encoding="utf-8")
PY
chown root:root "$receipt_staging"
chmod 0600 "$receipt_staging"
mv -f -- "$receipt_staging" "$receipt_path"
sync -f "$receipt_path"
sync -f "$backup_directory"
set +e
bash "$production_directory/rollback-backend.sh" \
    "$env_file" "$receipt_path" --confirm-data-restore
foreign_database_status=$?
set -e
[[ "$foreign_database_status" -ne 0 ]]
grep -qx 'RELEASE_TRANSACTION_OPERATION=rollback' "$release_state_directory/release-transaction.env"
grep -qx 'RELEASE_TRANSACTION_PHASE=intent' "$release_state_directory/release-transaction.env"
[[ ! -e /run/inplacex-online/maintenance.flag && ! -e /run/inplacex-online/drain.flag ]]
curl --fail --silent "http://127.0.0.1:$backend_port/ready" >/dev/null
grep -qx "INPLACEX_ACTIVATION_RELEASE_ID=$release_v3" \
    "$release_state_directory/activation/verified-activation.env"
cp -- "$receipt_original" "$receipt_staging"
chown root:root "$receipt_staging"
chmod 0600 "$receipt_staging"
mv -f -- "$receipt_staging" "$receipt_path"
sync -f "$receipt_path"
sync -f "$backup_directory"

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
set +e
timeout 25 env \
    INPLACEX_RELEASE_FAULT_PHASE=rollback_after_verified_activation \
    INPLACEX_RELEASE_FAULT_TEST_ACK=isolated-ci-host \
    bash "$production_directory/rollback-backend.sh" \
        "$env_file" "$receipt_path" --confirm-data-restore
rollback_verified_activation_fault_status=$?
set -e
[[ "$rollback_verified_activation_fault_status" -ne 0 ]]
[[ "$rollback_verified_activation_fault_status" -ne 124 ]]
grep -qx 'RELEASE_TRANSACTION_PHASE=previous_starting' \
    "$release_state_directory/release-transaction.env"
grep -qx "INPLACEX_ACTIVATION_RELEASE_ID=$release_v2" \
    "$release_state_directory/activation/verified-activation.env"

reset_ephemeral_release_state
curl --fail --silent "http://127.0.0.1:$backend_port/ready" >/dev/null
set +e
timeout 25 env \
    INPLACEX_RELEASE_FAULT_PHASE=rollback_after_pointer_committed \
    INPLACEX_RELEASE_FAULT_TEST_ACK=isolated-ci-host \
    bash "$production_directory/rollback-backend.sh" \
        "$env_file" "$receipt_path" --confirm-data-restore
rollback_finalize_fault_status=$?
set -e
[[ "$rollback_finalize_fault_status" -ne 0 ]]
[[ "$rollback_finalize_fault_status" -ne 124 ]]
grep -qx 'RELEASE_TRANSACTION_PHASE=activation_committed' \
    "$release_state_directory/release-transaction.env"
grep -qx 'RELEASE_POINTER_STATE=rolled_back' "$latest_pointer"
[[ -f /run/inplacex-online/maintenance.flag && -f /run/inplacex-online/drain.flag ]]

bash "$production_directory/rollback-backend.sh" \
    "$env_file" "$receipt_path" --confirm-data-restore
[[ ! -e "$release_state_directory/release-transaction.env" ]]
grep -qx "INPLACEX_ACTIVATION_RELEASE_ID=$release_v2" \
    "$release_state_directory/activation/verified-activation.env"

release_json="$(curl --fail --silent "http://127.0.0.1:$backend_port/meta/release")"
python3 -I - "$release_json" "$release_v2" "$git_sha" "${image_v2##*@}" <<'PY'
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

[[ "$(sha256sum "$geoip_file" | awk '{print $1}')" == "$geoip_candidate_sha256" ]]
grep -qx "INPLACEX_ACTIVATION_GEOIP_SHA256=$geoip_candidate_sha256" \
    "$release_state_directory/activation/verified-activation.env"

backend_container="$(docker compose --env-file "$env_file" --project-directory "$repository_root" \
    -f "$compose_file" ps --all -q backend)"
docker stop --time 5 "$backend_container" >/dev/null
printf 'completed-rollback-recovery\n' > /run/inplacex-online/maintenance.flag
printf 'completed-rollback-recovery\n' > /run/inplacex-online/drain.flag
chown root:root /run/inplacex-online/maintenance.flag /run/inplacex-online/drain.flag
chmod 0644 /run/inplacex-online/maintenance.flag /run/inplacex-online/drain.flag
bash "$production_directory/rollback-backend.sh" \
    "$env_file" "$receipt_path" --confirm-data-restore
[[ ! -e /run/inplacex-online/maintenance.flag && ! -e /run/inplacex-online/drain.flag ]]
curl --fail --silent "http://127.0.0.1:$backend_port/ready" >/dev/null
grep -qx "INPLACEX_ACTIVATION_RELEASE_ID=$release_v2" \
    "$release_state_directory/activation/verified-activation.env"
curl --fail --silent "http://127.0.0.1:$backend_port/ready" >/dev/null

echo "InplaceX production deploy, restart recovery, rollback, and GeoIP rotation integration passed."
