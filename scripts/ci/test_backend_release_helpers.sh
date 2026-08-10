#!/usr/bin/env bash
# shellcheck disable=SC2016,SC2034
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
production_directory="$repository_root/ops/production"
# shellcheck source=ops/production/release-common.sh
source "$production_directory/release-common.sh"

temporary_directory="$(mktemp -d "$repository_root/.release-helper-test.XXXXXX")"
publication_directory=""
buildkit_test_directory=""
cleanup() {
    rm -rf -- "$temporary_directory"
    if [[ "$publication_directory" == /tmp/inplacex-release-publication.* ]]; then
        rm -rf -- "$publication_directory"
    fi
    if [[ "$buildkit_test_directory" == /tmp/inplacex-buildkit-contract.* ]]; then
        rm -rf -- "$buildkit_test_directory"
    fi
}
trap cleanup EXIT

expect_status() {
    local expected="$1"
    shift
    set +e
    ("$@") >/dev/null 2>&1
    local actual=$?
    set -e
    [[ "$actual" -eq "$expected" ]] || {
        echo "Expected exit $expected, received $actual: $*" >&2
        exit 67
    }
}

sanitized_log_fixture="$temporary_directory/sanitized-ci-log.input"
sanitized_log_output="$temporary_directory/sanitized-ci-log.output"
sanitized_password='diagnostic-secret'
sanitized_basic="diagnostic-user:$sanitized_password"
sanitized_auth_base64='ZGlhZ25vc3RpYy11c2VyOmRpYWdub3N0aWMtc2VjcmV0'
python3 -I - "$sanitized_log_fixture" \
    "$sanitized_password" "$sanitized_basic" "$sanitized_auth_base64" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
lines = [f"diagnostic-line-{line_number:03d}" for line_number in range(1, 242)]
lines[0] = f"diagnostic-head {sys.argv[2]} {sys.argv[3]}"
lines[120] = f"diagnostic-middle {sys.argv[2]} {sys.argv[4]}"
lines[-1] = f"diagnostic-tail {sys.argv[3]} {sys.argv[4]}"
path.write_text("\n".join(lines) + "\n", encoding="utf-8")
PY
python3 -I "$repository_root/scripts/ci/print_sanitized_ci_log.py" \
    "$sanitized_log_fixture" \
    "$sanitized_password" "$sanitized_basic" "$sanitized_auth_base64" \
    > "$sanitized_log_output"
grep -Fxq \
    'diagnostic-head [redacted-test-credential] [redacted-test-credential]' \
    "$sanitized_log_output"
grep -Fxq \
    'diagnostic-tail [redacted-test-credential] [redacted-test-credential]' \
    "$sanitized_log_output"
grep -Fxq '... 41 additional lines omitted ...' "$sanitized_log_output"
[[ "$(wc -l < "$sanitized_log_output")" -eq 201 ]]
for sanitized_secret in \
    "$sanitized_password" "$sanitized_basic" "$sanitized_auth_base64"; do
    if grep -Fq -- "$sanitized_secret" "$sanitized_log_output"; then
        echo "Sanitized CI diagnostics exposed a protected credential variant." >&2
        exit 67
    fi
done

declare -Ar parser_allowlist=([SAFE_VALUE]=1 [EMPTY_VALUE]=1)
parse_fixture() {
    local fixture="$1" fd
    exec {fd}<"$fixture"
    release_parse_allowed_kv_fd "$fd" parser_allowlist
}

printf 'SAFE_VALUE=literal-1.2\nEMPTY_VALUE=\n' > "$temporary_directory/good.env"
parse_fixture "$temporary_directory/good.env"
[[ "$SAFE_VALUE" == "literal-1.2" && -z "$EMPTY_VALUE" ]]

marker="$temporary_directory/executed"
printf 'SAFE_VALUE=$(touch %s)\n' "$marker" > "$temporary_directory/executable.env"
expect_status 65 parse_fixture "$temporary_directory/executable.env"
[[ ! -e "$marker" ]] || {
    echo "Configuration parser executed hostile input." >&2
    exit 67
}
printf 'UNKNOWN=value\n' > "$temporary_directory/unknown.env"
expect_status 65 parse_fixture "$temporary_directory/unknown.env"
printf 'SAFE_VALUE=one\nSAFE_VALUE=two\n' > "$temporary_directory/duplicate.env"
expect_status 65 parse_fixture "$temporary_directory/duplicate.env"
printf 'SAFE_VALUE=one\ttwo\n' > "$temporary_directory/control.env"
expect_status 65 parse_fixture "$temporary_directory/control.env"

release_validate_canonical_absolute_path /var/lib/inplacex-online/release-state "test path"
release_validate_durable_directory_path /var/backups/inplacex-online "test durable path"
expect_status 65 release_validate_canonical_absolute_path /var/lib/inplacex/../run/state "test path"
expect_status 65 release_validate_canonical_absolute_path /var/lib//inplacex/state "test path"
expect_status 65 release_validate_durable_directory_path /run/inplacex-online "test durable path"
expect_status 65 release_validate_durable_directory_path /var/lib/inplacex/../../../run/state "test durable path"
expect_status 65 release_validate_durable_directory_path /tmp/inplacex-online "test durable path"

(
    COMPOSE_PROJECT_NAME=inplacex-online
    INPLACEX_POSTGRES_IMAGE=postgres@sha256:fixture
    INPLACEX_POSTGRES_DB=inplacex
    INPLACEX_POSTGRES_USER=inplacex
    INPLACEX_POSTGRES_VOLUME=inplacex-postgres
    INPLACEX_BACKEND_LOOPBACK_PORT=18080
    INPLACEX_PUBLIC_HOSTNAME=online.example.com
    INPLACEX_OPERATOR_NETWORK_CIDR=192.0.2.10/32
    INPLACEX_SECRET_DIRECTORY=/etc/inplacex-online/secrets
    INPLACEX_RUNTIME_SECRET_GID=991
    INPLACEX_GEOIP_DB_PATH=/var/lib/inplacex/geoip.mmdb
    INPLACEX_ONLINE_TOKEN_ISSUER=https://games.example.com
    INPLACEX_ONLINE_TOKEN_AUDIENCE=inplacex
    INPLACEX_MATCHMAKING_BOT_FALLBACK_SECONDS=5
    INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS=172.16.0.1
    INPLACEX_AD_MARKET_COUNTRY_HEADER=X-Country
    INPLACEX_AD_MARKET_CONTAINER_DB_PATH=/var/lib/inplacex/geoip.mmdb
    baseline_runtime_fingerprint="$(release_runtime_config_fingerprint)"
    INPLACEX_PUBLIC_HOSTNAME=online-next.example.com
    [[ "$(release_runtime_config_fingerprint)" != "$baseline_runtime_fingerprint" ]]
    INPLACEX_PUBLIC_HOSTNAME=online.example.com
    INPLACEX_OPERATOR_NETWORK_CIDR=198.51.100.0/24
    [[ "$(release_runtime_config_fingerprint)" != "$baseline_runtime_fingerprint" ]]
)

buildkit_test_directory="$(mktemp -d /tmp/inplacex-buildkit-contract.XXXXXX)"
local_buildkitd_config="$buildkit_test_directory/local-buildkitd.toml"
remote_buildkitd_config="$buildkit_test_directory/remote-buildkitd.toml"
release_write_buildkitd_config "$local_buildkitd_config" "127.0.0.1:5012"
release_write_buildkitd_config "$remote_buildkitd_config" "registry.example"
[[ "$(<"$local_buildkitd_config")" == \
    $'debug = false\n\n[registry."127.0.0.1:5012"]\n  http = true' ]]
[[ "$(<"$remote_buildkitd_config")" == 'debug = false' ]]
if grep -Fq 'insecure' "$local_buildkitd_config" "$remote_buildkitd_config"; then
    echo "Generated BuildKit configuration enabled insecure TLS handling." >&2
    exit 67
fi

buildx_lifecycle_log="$buildkit_test_directory/buildx-lifecycle.log"
(
    RELEASE_DOCKER_HOST=unix:///var/run/docker.sock
    readonly buildkitd_config="$buildkit_test_directory/caller-readonly.toml"
    release_docker() {
        printf '%s\n' "$*" >> "$buildx_lifecycle_log"
    }
    release_create_isolated_buildx_builder \
        local-builder "$local_buildkitd_config" "127.0.0.1:5012"
    release_create_isolated_buildx_builder \
        remote-builder "$remote_buildkitd_config" "registry.example"
    release_remove_isolated_buildx_builder local-builder
    release_remove_isolated_buildx_builder remote-builder
)
grep -Fxq "buildx create --name local-builder --driver docker-container --driver-opt image=$RELEASE_BUILDKIT_IMAGE --buildkitd-config $local_buildkitd_config --driver-opt network=host unix:///var/run/docker.sock" \
    "$buildx_lifecycle_log"
grep -Fxq "buildx create --name remote-builder --driver docker-container --driver-opt image=$RELEASE_BUILDKIT_IMAGE --buildkitd-config $remote_buildkitd_config unix:///var/run/docker.sock" \
    "$buildx_lifecycle_log"
grep -Fxq 'buildx rm --force --timeout 30s local-builder' "$buildx_lifecycle_log"
grep -Fxq 'buildx rm --force --timeout 30s remote-builder' "$buildx_lifecycle_log"
if grep -F 'remote-builder' "$buildx_lifecycle_log" | grep -Eq 'network=host|http|insecure'; then
    echo "Remote Buildx builder inherited a loopback-only transport option." >&2
    exit 67
fi

(
    container_enumeration_mode=empty
    release_docker() {
        [[ "$#" -eq 5 && "$1" == "ps" && "$2" == "--all" &&
            "$3" == "--quiet" && "$4" == "--filter" &&
            "$5" == 'name=^/buildx_buildkit_test-builder0$' ]] || return 92
        case "$container_enumeration_mode" in
            empty) return 0 ;;
            present) printf '%s\n' '0123456789abcdef' ;;
            failure) return 91 ;;
            *) return 93 ;;
        esac
    }

    release_assert_docker_container_absent_exact buildx_buildkit_test-builder0
    container_enumeration_mode=present
    expect_status 75 \
        release_assert_docker_container_absent_exact buildx_buildkit_test-builder0
    container_enumeration_mode=failure
    expect_status 70 \
        release_assert_docker_container_absent_exact buildx_buildkit_test-builder0
)

tampered_buildkitd_config="$buildkit_test_directory/tampered-buildkitd.toml"
printf '%s\n' 'debug = false' 'insecure = true' > "$tampered_buildkitd_config"
chmod 0600 "$tampered_buildkitd_config"
RELEASE_DOCKER_HOST=unix:///var/run/docker.sock
expect_status 77 release_create_isolated_buildx_builder \
    rejected-builder "$tampered_buildkitd_config" "registry.example"

release_validate_legacy_checksum_history '1,2,3,4,5,6,7,8|1'
release_validate_legacy_checksum_history '1,2,3,4,5,6,7,8|8'
release_validate_legacy_checksum_history '1,2,3,4,5,6,7,8,9|1'
release_validate_legacy_checksum_history '1,2,3,4,5,6,7,8,9|9'
release_validate_legacy_checksum_history '1,2,3,4,5,6,7,8,9,10|1'
release_validate_legacy_checksum_history '1,2,3,4,5,6,7,8,9,10|10'
release_validate_completed_legacy_checksum_history '1,2,3,4,5,6,7,8|0'
release_validate_completed_legacy_checksum_history '1,2,3,4,5,6,7,8,9|0'
release_validate_completed_legacy_checksum_history '1,2,3,4,5,6,7,8,9,10|0'
expect_status 75 release_validate_legacy_checksum_history '1,2,3,4,5,6,7,8|9'
expect_status 75 release_validate_legacy_checksum_history '1,2,3,4,5,6,7,8,9|0'
expect_status 75 release_validate_legacy_checksum_history '1,2,3,4,5,6,7,8,9,10|11'
expect_status 75 release_validate_legacy_checksum_history '1,2,3,4,5,6,7,9|1'
expect_status 75 release_validate_completed_legacy_checksum_history '1,2,3,4,5,6,7,8|1'

publication_directory="$(mktemp -d /tmp/inplacex-release-publication.XXXXXX)"
publication_source_one="$publication_directory/source-one"
publication_source_two="$publication_directory/source-two"
publication_destination="$publication_directory/manifest.json"
printf 'manifest-one\n' > "$publication_source_one"
printf 'manifest-two\n' > "$publication_source_two"
chmod 0600 "$publication_source_one" "$publication_source_two"
(
    set +e
    release_publish_new_file_no_replace \
        "$publication_source_one" "$publication_destination" \
        > "$publication_directory/one.log" 2>&1
    publication_status=$?
    set -e
    printf '%s\n' "$publication_status" > "$publication_directory/one.status"
) &
publication_pid_one=$!
(
    set +e
    release_publish_new_file_no_replace \
        "$publication_source_two" "$publication_destination" \
        > "$publication_directory/two.log" 2>&1
    publication_status=$?
    set -e
    printf '%s\n' "$publication_status" > "$publication_directory/two.status"
) &
publication_pid_two=$!
wait "$publication_pid_one" "$publication_pid_two"
publication_status_one="$(<"$publication_directory/one.status")"
publication_status_two="$(<"$publication_directory/two.status")"
if [[ "$publication_status_one" == "0" ]]; then
    [[ "$publication_status_two" == "75" &&
        "$(<"$publication_destination")" == "manifest-one" &&
        ! -e "$publication_source_one" && -f "$publication_source_two" ]]
else
    [[ "$publication_status_one" == "75" && "$publication_status_two" == "0" &&
        "$(<"$publication_destination")" == "manifest-two" &&
        -f "$publication_source_one" && ! -e "$publication_source_two" ]]
fi
[[ "$(stat -Lc '%F %a %h' -- "$publication_destination")" == "regular file 600 1" ]]

mock_directory="$temporary_directory/mock-bin"
mkdir "$mock_directory"
mock_log="$temporary_directory/docker.log"
cat > "$mock_directory/docker" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
for forbidden_environment_name in \
    BUILDX_CONFIG EXPERIMENTAL_BUILDKIT_SOURCE_POLICY DOCKER_DEFAULT_PLATFORM \
    DOCKER_CLI_PLUGIN_EXTRA_DIRS; do
    [[ -z "${!forbidden_environment_name+x}" ]] || {
        printf 'forbidden environment reached Docker: %s\n' "$forbidden_environment_name" >&2
        exit 91
    }
done
printf '%s\n' "$*" >> "$INPLACEX_TEST_DOCKER_LOG"
if [[ "$1" == "inspect" && "$2" == "--format" && "$3" == "{{.State.Running}}" ]]; then
    printf '%s\n' "${INPLACEX_TEST_BACKEND_STATE:-false}"
    exit 0
fi
if [[ "$1 $2" == "image inspect" ]]; then
    case "$4" in
        *RepoDigests*) printf '["registry.example/app@sha256:%s"]\n' "$(printf 'a%.0s' {1..64})" ;;
        *version*) printf '%s\n' "${INPLACEX_TEST_RELEASE_ID:-release-1}" ;;
        *revision*) printf '%s\n' "${INPLACEX_TEST_GIT_SHA:-$(printf 'b%.0s' {1..40})}" ;;
        *source-archive*) printf '%s\n' "${INPLACEX_TEST_SOURCE_SHA:-$(printf 'c%.0s' {1..64})}" ;;
        *) exit 67 ;;
    esac
fi
MOCK
chmod +x "$mock_directory/docker"
export MOCK_DOCKER_LOG="$mock_log"
export INPLACEX_RELEASE_ISOLATED_CI_ACK=acknowledge-inplacex-isolated-release-ci
export INPLACEX_RELEASE_TEST_DOCKER_BIN="$mock_directory/docker"
export INPLACEX_TEST_DOCKER_LOG="$mock_log"
RELEASE_DOCKER_BIN="$mock_directory/docker"
image="registry.example/app@sha256:$(printf 'a%.0s' {1..64})"
git_sha="$(printf 'b%.0s' {1..40})"
source_sha="$(printf 'c%.0s' {1..64})"
export BUILDX_CONFIG="$temporary_directory/hostile-buildx-state"
export EXPERIMENTAL_BUILDKIT_SOURCE_POLICY="$temporary_directory/hostile-source-policy.json"
export DOCKER_DEFAULT_PLATFORM=linux/arm64
export DOCKER_CLI_PLUGIN_EXTRA_DIRS="$temporary_directory/hostile-plugins"
release_verify_pulled_image "$image" release-1 "$git_sha" "$source_sha"
unset BUILDX_CONFIG EXPERIMENTAL_BUILDKIT_SOURCE_POLICY DOCKER_DEFAULT_PLATFORM
unset DOCKER_CLI_PLUGIN_EXTRA_DIRS
grep -Fxq "pull $image" "$mock_log"
export INPLACEX_TEST_RELEASE_ID=hostile-label
expect_status 75 release_verify_pulled_image "$image" release-1 "$git_sha" "$source_sha"
unset INPLACEX_TEST_RELEASE_ID

mock_backend_id="$(printf 'd%.0s' {1..64})"
mock_mutation_log="$temporary_directory/state-mutations.log"
mock_gate="$temporary_directory/maintenance.flag"
mock_journal="$temporary_directory/release-transaction.env"
mock_compose() {
    printf 'compose %s\n' "$*" >> "$MOCK_DOCKER_LOG"
    if [[ "$1 $2 $3 $4" == "ps --all -q backend" ]]; then
        printf '%s\n' "${MOCK_BACKEND_IDS:-$mock_backend_id}"
        return 0
    fi
    if [[ "$1 $2 $3 $4" == "stop --timeout 30 backend" ]]; then
        return "${MOCK_COMPOSE_STOP_STATUS:-0}"
    fi
    return 67
}
guarded_database_mutation() {
    release_stop_backend_fail_closed mock_compose || return 1
    printf '%s\n' pg_dump pg_restore 'DROP DATABASE' >> "$mock_mutation_log"
}
guarded_geoip_mutation() {
    release_stop_backend_fail_closed mock_compose || return 1
    printf '%s\n' MMDB_SWAP >> "$mock_mutation_log"
}

: > "$mock_gate"
: > "$mock_journal"
export MOCK_COMPOSE_STOP_STATUS=42 INPLACEX_TEST_BACKEND_STATE=false
RELEASE_BACKEND_STOP_PROOF_FAILED=false
set +e
guarded_database_mutation >/dev/null 2>&1
guard_status=$?
set -e
[[ "$guard_status" -eq 1 && "$RELEASE_BACKEND_STOP_PROOF_FAILED" == "true" ]]
[[ -f "$mock_gate" && -f "$mock_journal" && ! -e "$mock_mutation_log" ]]
export MOCK_COMPOSE_STOP_STATUS=0 INPLACEX_TEST_BACKEND_STATE=true
RELEASE_BACKEND_STOP_PROOF_FAILED=false
set +e
guarded_geoip_mutation >/dev/null 2>&1
guard_status=$?
set -e
[[ "$guard_status" -eq 1 && "$RELEASE_BACKEND_STOP_PROOF_FAILED" == "true" ]]
[[ -f "$mock_gate" && -f "$mock_journal" && ! -e "$mock_mutation_log" ]]
MOCK_BACKEND_IDS=$'dddddddddddd\neeeeeeeeeeee'
export MOCK_COMPOSE_STOP_STATUS=0 INPLACEX_TEST_BACKEND_STATE=false
RELEASE_BACKEND_STOP_PROOF_FAILED=false
set +e
guarded_database_mutation >/dev/null 2>&1
guard_status=$?
set -e
[[ "$guard_status" -eq 1 && "$RELEASE_BACKEND_STOP_PROOF_FAILED" == "true" ]]
[[ -f "$mock_gate" && -f "$mock_journal" && ! -e "$mock_mutation_log" ]]
unset MOCK_BACKEND_IDS
export INPLACEX_TEST_BACKEND_STATE=false
RELEASE_BACKEND_STOP_PROOF_FAILED=false
guarded_database_mutation
grep -Fxq pg_dump "$mock_mutation_log"

entrypoint_probe_log="$temporary_directory/entrypoint-probe.log"
cat > "$mock_directory/git" <<'MOCK'
#!/usr/bin/env bash
printf 'hostile git\n' >> "$ENTRYPOINT_PROBE_LOG"
exit 70
MOCK
chmod +x "$mock_directory/git"
export ENTRYPOINT_PROBE_LOG="$entrypoint_probe_log"
if [[ -z "${WSL_DISTRO_NAME:-}" ]]; then
    expect_status 77 env DOCKER_HOST=tcp://127.0.0.1:2375 \
        bash "$production_directory/build-backend-release.sh"
    expect_status 77 env BASH_ENV=/dev/null \
        bash "$production_directory/build-backend-release.sh"
    expect_status 77 env DOCKER_CONFIG="$temporary_directory/ambient-docker-config" \
        bash --noprofile --norc -c 'builtin source "$1"' _ \
            "$production_directory/release-shell-bootstrap.sh"
    for hostile_docker_environment in \
        BUILDX_CONFIG EXPERIMENTAL_BUILDKIT_SOURCE_POLICY DOCKER_DEFAULT_PLATFORM \
        DOCKER_CLI_PLUGIN_EXTRA_DIRS; do
        expect_status 77 env "$hostile_docker_environment=$temporary_directory/hostile-value" \
            bash --noprofile --norc -c 'builtin source "$1"' _ \
                "$production_directory/release-shell-bootstrap.sh"
    done
    expect_status 77 env LD_PRELOAD=/definitely/missing/inplacex-hostile.so \
        bash --noprofile --norc -c 'builtin source "$1"' _ \
            "$production_directory/release-shell-bootstrap.sh"
    env PATH="$mock_directory:$PATH" \
        bash --noprofile --norc -c 'builtin source "$1"' _ \
            "$production_directory/release-shell-bootstrap.sh"
fi
[[ ! -e "$entrypoint_probe_log" ]] || {
    echo "Release bootstrap executed a hostile PATH command." >&2
    exit 67
}

filter_repository="$temporary_directory/hostile-filter-repository"
filter_home="$temporary_directory/hostile-filter-home"
filter_archive="$temporary_directory/hostile-filter.tar"
filter_marker="$temporary_directory/hostile-filter.executed"
filter_command="$temporary_directory/hostile-filter.sh"
mkdir "$filter_repository" "$filter_home"
cat > "$filter_command" <<MOCK
#!/usr/bin/env bash
touch "$filter_marker"
cat
MOCK
chmod +x "$filter_command"
/usr/bin/git -C "$filter_repository" init --initial-branch=fixture >/dev/null
printf 'payload.txt filter=hostile\n' > "$filter_repository/.gitattributes"
printf 'immutable-payload\n' > "$filter_repository/payload.txt"
/usr/bin/git -C "$filter_repository" config filter.hostile.clean "$filter_command"
/usr/bin/git -C "$filter_repository" add --all
/usr/bin/git -C "$filter_repository" \
    -c user.name=InplaceX-CI -c user.email=ci@inplacex.invalid \
    commit --message='hostile filter fixture' >/dev/null
rm -f -- "$filter_marker"
filter_commit="$(/usr/bin/git -C "$filter_repository" rev-parse HEAD)"
python3 -I "$production_directory/create-source-archive.py" \
    "$filter_repository" "$filter_commit" "$filter_archive" "$filter_home"
[[ ! -e "$filter_marker" ]]
[[ "$(tar -xOf "$filter_archive" payload.txt)" == "immutable-payload" ]]
printf 'payload.txt filter=hostile\n' > "$filter_repository/.git/info/attributes"
expect_status 1 python3 -I "$production_directory/create-source-archive.py" \
    "$filter_repository" "$filter_commit" "$temporary_directory/rejected-filter.tar" "$filter_home"
[[ ! -e "$filter_marker" ]]

python3 -I - \
    "$production_directory/deploy-backend.sh" \
    "$production_directory/rollback-backend.sh" \
    "$production_directory/rotate-geoip.sh" \
    "$production_directory/build-backend-release.sh" \
    "$production_directory/release-shell-bootstrap.sh" \
    "$repository_root/scripts/ci/test_backend_production_runtime.sh" \
    "$temporary_directory" <<'PY'
import pathlib
import re
import subprocess
import sys

deploy = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
rollback = pathlib.Path(sys.argv[2]).read_text(encoding="utf-8")
geoip = pathlib.Path(sys.argv[3]).read_text(encoding="utf-8")
builder = pathlib.Path(sys.argv[4]).read_text(encoding="utf-8")
bootstrap = pathlib.Path(sys.argv[5]).read_text(encoding="utf-8")
runtime_test = pathlib.Path(sys.argv[6]).read_text(encoding="utf-8")
test_directory = pathlib.Path(sys.argv[7])
common = pathlib.Path(sys.argv[1]).with_name('release-common.sh').read_text(encoding='utf-8')

validator_marker = '''    python3 -I - "$nginx_configuration" "$RELEASE_INSTALLED_LOCATIONS" "$INPLACEX_PUBLIC_HOSTNAME" <<'PY'\n'''
validator_start = common.find(validator_marker)
validator_end = common.find("\nPY\n", validator_start + len(validator_marker))
if validator_start < 0 or validator_end < 0:
    raise SystemExit("Cannot locate the production nginx server validator")
nginx_validator = common[validator_start + len(validator_marker):validator_end] + "\n"
installed_locations = "/etc/nginx/snippets/inplacex-online.locations.conf"
hostname = "online.example.com"
for case_name, server_names, expected_success in (
    ("exact", hostname, True),
    ("exact-among-multiple", f"api.example.com {hostname}", True),
    ("prefixed-substring", f"evil-{hostname}", False),
    ("suffixed-substring", f"{hostname}.evil", False),
):
    configuration = test_directory / f"nginx-server-name-{case_name}.conf"
    configuration.write_text(
        "\n".join((
            "server {",
            "    listen 443 ssl;",
            f"    server_name {server_names};",
            f"    include {installed_locations};",
            "}",
            "",
        )),
        encoding="utf-8",
    )
    result = subprocess.run(
        [sys.executable, "-I", "-", str(configuration), installed_locations, hostname],
        input=nginx_validator,
        text=True,
        capture_output=True,
        check=False,
    )
    if (result.returncode == 0) != expected_success:
        raise SystemExit(
            f"Production nginx hostname validator case {case_name!r} returned {result.returncode}: "
            f"{result.stderr.strip()}"
        )

def ordered(source, first, second):
    first_index = source.find(first)
    second_index = source.find(second, first_index + len(first)) if first_index >= 0 else -1
    if first_index < 0 or second_index < 0:
        raise SystemExit(f"Expected ordering not preserved: {first!r} before {second!r}")

legacy_ack_definition = (
    'readonly RELEASE_LEGACY_CHECKSUM_BASELINE_ACK="acknowledge-inplacex-schema-v1-v8"'
)
if common.count(legacy_ack_definition) != 1 or 'acknowledge-inplacex-schema-v1-v8' in deploy:
    raise SystemExit("Deploy must use one shared legacy checksum acknowledgement")

recovery_start = deploy.find('recover_previous_release() {')
recovery_end = deploy.find('\non_exit() {', recovery_start)
if recovery_start < 0 or recovery_end < 0:
    raise SystemExit("Cannot locate deploy recovery function")
recovery = deploy[recovery_start:recovery_end]
cursor = 0
for marker in (
    'restore_database_backup || return 1',
    'export INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK="$RELEASE_LEGACY_CHECKSUM_BASELINE_ACK"',
    'compose_command up --detach --force-recreate --wait',
    'export INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK=""',
    'release_write_sanitized_env',
    'release_start_activation_lease',
    'compose_command up --detach --force-recreate --wait',
    '"$smoke_script" loopback',
):
    cursor = recovery.find(marker, cursor)
    if cursor < 0:
        raise SystemExit(f"Legacy checksum recovery order is incomplete at: {marker}")
    cursor += len(marker)

on_exit_start = deploy.find('on_exit() {', recovery_end)
on_exit_end = deploy.find('\ntrap on_exit EXIT', on_exit_start)
if on_exit_start < 0 or on_exit_end < 0:
    raise SystemExit("Cannot locate deploy exit recovery")
on_exit = deploy[on_exit_start:on_exit_end]
failed_recovery_start = on_exit.find('elif recover_previous_release; then')
failed_recovery_end = on_exit.find('\n        fi', failed_recovery_start)
if failed_recovery_start < 0 or failed_recovery_end < 0:
    raise SystemExit("Cannot locate deploy failed-recovery branch")
failed_recovery = on_exit[failed_recovery_start:failed_recovery_end]
ordered(
    failed_recovery,
    'release_stop_activation_lease || true',
    'release_stop_backend_fail_closed compose_command',
)
failed_recovery_harness = test_directory / "deploy-failed-recovery-harness.sh"
failed_recovery_permit = test_directory / "deploy-failed-recovery.permit"
failed_recovery_env = test_directory / "deploy-failed-recovery.env"
failed_recovery_harness.write_text(
    """#!/usr/bin/env bash
set -u
permit="$1"
sanitized_env="$2"
activation_v1_migration_acknowledged=false
maintenance_active=true
candidate_activated=false
deployment_succeeded=false
RELEASE_BACKEND_STOP_PROOF_FAILED=false
release_stop_activation_lease() { rm -f -- "$permit"; }
verified_activation_matches_requested_candidate() { return 1; }
recover_previous_release() { printf 'pending\n' > "$permit"; return 1; }
release_stop_backend_fail_closed() { [[ ! -e "$permit" ]]; }
compose_command() { return 0; }
""" + on_exit + """
: > "$sanitized_env"
set +e
false
on_exit
""",
    encoding="utf-8",
)
failed_recovery_result = subprocess.run(
    ["bash", str(failed_recovery_harness), str(failed_recovery_permit), str(failed_recovery_env)],
    text=True,
    capture_output=True,
    check=False,
)
if (
    failed_recovery_result.returncode != 1
    or failed_recovery_permit.exists()
    or failed_recovery_env.exists()
    or "Automatic recovery could not be verified" not in failed_recovery_result.stderr
):
    raise SystemExit(
        "Deploy failed-recovery path did not revoke its replacement activation lease: "
        f"status={failed_recovery_result.returncode}, stderr={failed_recovery_result.stderr.strip()!r}"
    )

ordered(deploy, 'if ! compose_command up --detach --force-recreate --wait',
        'compose_command logs --no-color --tail 200 backend >&2 || true')
ordered(deploy, 'compose_command logs --no-color --tail 200 backend >&2 || true',
        'release_die 70 "Candidate backend failed to become healthy"')

exact_fault_assertions = re.findall(
    r'\[\[ "\$[a-z0-9_]+_fault_status" -eq 137 \]\]', runtime_test
)
if len(exact_fault_assertions) != 13:
    raise SystemExit("Every SIGKILL release fault must assert exact status 137")
if re.search(r'\$[a-z0-9_]+_fault_status" -ne (?:0|124)', runtime_test):
    raise SystemExit("Release faults must not accept generic startup failure or timeout status")

ordered(deploy, 'release_verify_pulled_image "$INPLACEX_BACKEND_IMAGE"', 'release_enable_maintenance "$deployment_id"')
ordered(deploy, 'release_verify_pulled_image "$previous_image"', 'release_enable_maintenance "$deployment_id"')
ordered(deploy, 'write_journal intent', 'release_enable_maintenance "$deployment_id"')
ordered(deploy, 'release_enable_maintenance "$deployment_id"', 'release_stop_backend_fail_closed compose_command')
ordered(deploy, 'release_stop_backend_fail_closed compose_command', 'exec pg_dump --format=custom')
ordered(deploy, 'release_fault_inject during_backup_staging', 'exec pg_dump --format=custom')
ordered(deploy, '> "$backup_staging_path"', 'mv -- "$backup_staging_path" "$backup_path"')
ordered(deploy, 'write_journal activation_committed', 'release_disable_maintenance')
ordered(deploy, 'release_write_activation_record', 'release_fault_inject after_activation')
ordered(deploy, 'release_fault_inject deploy_after_gates_removed', 'release_remove_durable_file "$RELEASE_TRANSACTION_JOURNAL"')
ordered(rollback, '"$ROLLBACK_PREVIOUS_IMAGE" "$ROLLBACK_PREVIOUS_RELEASE_ID"', 'release_enable_maintenance "$ROLLBACK_DEPLOYMENT_ID"')
ordered(rollback, 'write_journal intent', 'release_enable_maintenance "$ROLLBACK_DEPLOYMENT_ID"')
ordered(rollback, 'release_remove_durable_file "$RELEASE_VERIFIED_ACTIVATION_FILE"', 'restore_database "$ROLLBACK_BACKUP_PATH"')
ordered(rollback, 'release_enable_maintenance "$ROLLBACK_DEPLOYMENT_ID"', 'release_stop_backend_fail_closed compose_command')
ordered(rollback, 'release_stop_backend_fail_closed compose_command', 'exec pg_dump --format=custom')
ordered(rollback, '> "$emergency_backup_staging"', 'mv -- "$emergency_backup_staging" "$emergency_backup"')
ordered(rollback, 'RELEASE_POINTER_STATE=rolled_back', 'release_disable_maintenance')
ordered(rollback, 'release_write_activation_record', 'release_fault_inject rollback_after_verified_activation')
ordered(rollback, 'release_disable_maintenance', 'release_remove_durable_file "$RELEASE_TRANSACTION_JOURNAL"')
ordered(geoip, 'write_journal candidate_ready', 'release_enable_maintenance "$deployment_id"')
ordered(geoip, 'release_enable_maintenance "$deployment_id"', 'release_stop_backend_fail_closed compose_command')
ordered(geoip, 'release_stop_backend_fail_closed compose_command', 'mv -- "$candidate_path" "$INPLACEX_GEOIP_DB_PATH"')
ordered(geoip, 'write_journal activation_committed', 'release_disable_maintenance')
ordered(geoip, 'release_write_activation_record', 'release_fault_inject geoip_after_verified_activation')
ordered(geoip, 'release_disable_maintenance', 'release_remove_durable_file "$RELEASE_TRANSACTION_JOURNAL"')
if '> "$backup_path"' in deploy or '> "$emergency_backup"' in rollback:
    raise SystemExit("PostgreSQL backups must never stream directly into their durable final path")
if 'information_schema.columns' not in deploy:
    raise SystemExit("Legacy checksum preflight must feature-detect the checksum column")
if 'GeoIP database differs from the durable verified activation; use rotate-geoip.sh' not in deploy:
    raise SystemExit("Deploy must reject an unmanaged GeoIP fingerprint change")
if 'RELEASE_TRANSACTION_PREVIOUS_PUBLIC_KEY_SHA256' not in deploy:
    raise SystemExit("Deploy must preserve the previous public-key fingerprint for graceful key rotation")
if 'previous_backend_running' not in deploy or 'unless an exact public-key rotation is acknowledged' not in deploy:
    raise SystemExit("Deploy must admit an exact stopped predecessor only for acknowledged public-key rotation")
if 'INPLACEX_ACTIVATION_DATABASE_PASSWORD_SHA256' not in pathlib.Path(sys.argv[1]).with_name('release-common.sh').read_text(encoding='utf-8'):
    raise SystemExit("Durable activation must bind the database-password fingerprint")
if 'RELEASE_TRANSACTION_DATABASE_PASSWORD_SHA256' not in deploy:
    raise SystemExit("Deploy journal must bind the database-password fingerprint")
if 'ROLLBACK_PREVIOUS_ACTIVATION_VERSION' not in deploy or 'ROLLBACK_PREVIOUS_ACTIVATION_VERSION' not in rollback:
    raise SystemExit("Rollback receipts must mark the activation v1-to-v2 compatibility boundary")
if 'ROLLBACK_RECEIPT_VERSION=3' not in deploy or 'ROLLBACK_RECEIPT_VERSION" == "3"' not in rollback:
    raise SystemExit("The compatibility-bound rollback receipt schema must be version 3")
for field in (
    'RELEASE_TRANSACTION_ACTIVATION_V1_MIGRATION_ACKNOWLEDGED',
    'RELEASE_TRANSACTION_ACTIVATION_V1_MIGRATION_COMPLETED',
):
    if field not in deploy:
        raise SystemExit(f"Activation v1 migration recovery must journal {field}")
if 'release_verify_v1_activation_migration_source' not in deploy:
    raise SystemExit("Deploy must prove the exact running activation v1 source")
if 'activation_v1_after_v2_record' not in deploy:
    raise SystemExit("Activation v1 migration needs a crash boundary after the atomic v2 record")
if '"$VERIFIED_PUBLIC_KEY_SHA256" == "$public_key_sha256"' not in deploy:
    raise SystemExit("Same-release fast path must reject an unactivated public-key rotation")
for field in (
    'RELEASE_TRANSACTION_LEGACY_CHECKSUM_ACKNOWLEDGED',
    'RELEASE_TRANSACTION_LEGACY_CHECKSUM_COMPLETED',
):
    if field not in deploy:
        raise SystemExit(f"Legacy checksum recovery must journal {field}")
ordered(
    deploy,
    'legacy_checksum_completed=true\n        write_journal "$transaction_phase"',
    'release_fault_inject after_legacy_checksum_completed',
)
ordered(
    rollback,
    'compose_command config --quiet',
    'Rollback to $ROLLBACK_PREVIOUS_RELEASE_ID was already durably finalized.',
)
if '"$geoip_sha256" == "$ROLLBACK_GEOIP_SHA256"' in rollback:
    raise SystemExit("Rollback must preserve the current transactionally verified GeoIP artifact")
for source in (deploy, rollback, geoip):
    if 'source "$env_file"' in source or 'source "$receipt_file"' in source:
        raise SystemExit("Production data file must never be sourced")
    if '--wait-timeout' not in source:
        raise SystemExit("Production Compose waits must be bounded")
    if 'release_fault_inject' not in source or 'RELEASE_TRANSACTION_JOURNAL' not in source:
        raise SystemExit("Production mutation must retain durable journal and fault-injection hooks")
    if 'release_running_backend_matches_verified_activation' not in source:
        raise SystemExit("Production resume must drain only an exact durable running activation")
    if 'compose_command stop --timeout 30 backend' in source:
        raise SystemExit("Production state mutation must not bypass the common stop-and-inspect proof")
    if 'release_stop_backend_fail_closed compose_command' not in source:
        raise SystemExit("Production state mutation must use the common stop-and-inspect proof")
    if 'release_prepare_docker_control_plane' not in source or 'release_docker compose' not in source:
        raise SystemExit("Production state mutation must pin and verify its Docker control plane")

if deploy.count('ORDER BY version::bigint') != 2:
    raise SystemExit("Legacy migration histories must be ordered numerically")

for required in (
    '/usr/bin/git --no-replace-objects -c core.fsmonitor=false -c core.hooksPath=/dev/null',
    'GIT_CONFIG_GLOBAL=/dev/null',
    'GIT_CONFIG_NOSYSTEM=1',
    '"$repository_root/.git/HEAD"',
    '"755|$invoked_builder_path"',
    'rev-parse --path-format=absolute --git-common-dir',
    'find "$repository_root/.git" -xdev',
    'rev-parse --show-toplevel',
    'verify_release_toolset',
    'cat-file blob "$git_sha:$tool_path"',
    '/proc/self/fd/$opened_fd',
    'regular file|0|0|$working_mode|1|',
    'create-source-archive.py',
    '--registry-auth-config',
    '--anonymous-loopback',
    'release_normalize_registry_auth_config',
    '! -e "$manifest_path" && ! -L "$manifest_path"',
    'release_publish_new_file_no_replace "$temporary_manifest" "$manifest_path"',
    '--file "$archive_dockerfile"',
    '--push - < "$source_archive"',
    'release_prepare_docker_control_plane buildx "$temporary_directory/docker-cli"',
    'release_write_buildkitd_config "$buildkitd_config" "$registry_authority"',
    'release_create_isolated_buildx_builder',
    'buildx_builder_cleanup_required=true',
    'cleanup_buildx_builder || exit 70',
    'print_buildx_diagnostics',
    'verify_buildx_builder_runtime_identity',
    'buildx_buildkit_${buildx_builder_name}0',
    'release_assert_docker_container_absent_exact',
    'attestationManifestDigests',
    '"schemaVersion": 2',
):
    if required not in builder:
        raise SystemExit(f"Release builder lost immutable-source binding: {required}")
if (
    'git archive' in builder
    or 'status --porcelain' in builder
    or '--file "$dockerfile"' in builder
):
    raise SystemExit("Release builder must not mix a mutable Dockerfile with an archive context")
if builder.count('--builder "$buildx_builder_name"') != 4:
    raise SystemExit("Release build and every attestation inspection must select the exact isolated builder")
ordered(
    builder,
    'buildx_builder_cleanup_required=true',
    'release_create_isolated_buildx_builder',
)
ordered(
    builder,
    'release_docker buildx imagetools inspect --builder "$buildx_builder_name"',
    'cleanup_buildx_builder || exit 70',
)
for required in (
    'BASH_ENV', 'ENV', 'BASH_FUNC_', 'LD_PRELOAD', 'DOCKER_HOST', 'DOCKER_CONTEXT',
    'BUILDX_CONFIG', 'EXPERIMENTAL_BUILDKIT_SOURCE_POLICY', 'DOCKER_DEFAULT_PLATFORM',
    'DOCKER_CLI_PLUGIN_EXTRA_DIRS', 'PATH=/usr/sbin:/usr/bin:/sbin:/bin',
):
    if required not in bootstrap:
        raise SystemExit(f"Release shell bootstrap lost hostile-environment guard: {required}")
if 'runtime-activation-v1.patch' not in runtime_test or 'apply --reverse --check "$fixture_patch"' not in runtime_test:
    raise SystemExit("Destructive runtime test must build the immutable activation-v1 fixture")
if 'apply --reverse --verbose "$fixture_patch" >&2' not in runtime_test:
    raise SystemExit("Activation-v1 fixture patch progress must stay off the process-substitution result pipe")
if (
    'find /run/inplacex-online -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +' not in runtime_test
    or 'runtime_directory_inode' not in runtime_test
):
    raise SystemExit("Ephemeral-state recovery tests must preserve the bind-mounted runtime directory inode")
if 'install -m 0644 "$repository_root/$activation_guard"' in runtime_test:
    raise SystemExit("Activation-v1 fixture must not copy mutable checkout source")
if '"$production_directory/build-backend-release.sh"' not in runtime_test:
    raise SystemExit("Destructive runtime CI must invoke the real production release builder")
for required in (
    'REGISTRY_AUTH=htpasswd',
    '--registry-auth-config "$registry_auth_config"',
    'Authenticated registry credential leaked into release evidence or logs.',
    'assert_no_inplacex_release_builders',
    'name=^/buildx_buildkit_inplacex-release-',
    'schema_v1_environment',
    'print_sanitized_ci_log.py',
    'run_with_failed_candidate_start',
    'COMPLETED_BACKEND_UP fail-once',
    '[[ "$(cat "$backend_up_counter")" == "3" ]]',
    '[[ "$recovered_migration_state" == "10|0" ]]',
    'final_legacy_ack_environment',
):
    if required not in runtime_test:
        raise SystemExit(f"Destructive runtime CI lost production release coverage: {required}")
if runtime_test.count('assert_no_inplacex_release_builders') < 4:
    raise SystemExit("Every destructive release-builder path must prove successful builder cleanup")
if 'docker build \\' in runtime_test or 'docker push "$image_tag"' in runtime_test:
    raise SystemExit("Destructive runtime CI must not hand-build release images")
if 'tar -xf "$current_source_archive"' not in runtime_test:
    raise SystemExit("Activation-v1 fixture must not infer v1 behavior from HEAD")
if 'git archive' in runtime_test:
    raise SystemExit("Destructive CI source fixtures must bypass Git attributes and filters")
for required in (
    'release_normalize_registry_auth_config()',
    'moby/buildkit:buildx-stable-1@sha256:2f5adac4ecd194d9f8c10b7b5d7bceb5186853db1b26e5abd3a657af0b7e26ec',
    'release_expected_buildkitd_config()',
    'release_write_buildkitd_config()',
    'release_create_isolated_buildx_builder()',
    'release_remove_isolated_buildx_builder()',
    'release_assert_docker_container_absent_exact()',
    '--driver docker-container',
    '--driver-opt "image=$RELEASE_BUILDKIT_IMAGE"',
    'create_arguments+=(--driver-opt network=host)',
    'create_arguments+=("$RELEASE_DOCKER_HOST")',
    'release_docker buildx rm --force --timeout 30s "$builder_name"',
    'release_docker ps --all --quiet',
    'set(value) != {"auths"}',
    'set(auths) != {authority}',
    'set(entry) != {"auth"}',
    'regular file 0 0 600 1',
    'type(schema_version) is not int',
    '/usr/bin/env -i',
    'renameat2',
):
    if required not in common:
        raise SystemExit(f"Protected registry/manifest validation is missing: {required}")
if 'insecure = true' in common or 'insecure = true' in builder:
    raise SystemExit("Production builder must never configure insecure registry TLS handling")
for required in (
    '--no-local --no-hardlinks --no-checkout',
    'assert_builder_rejects_untrusted_source',
    'writable-parent writable-parent',
    'symlink-tool symlink-tool',
    'hardlink-tool hardlink-tool',
    'assert_builder_rejects_manifest_target',
    'assert_builder_rejects_registry_auth',
    'ambient-helpers',
    'wrong-host',
    'wrong-mode',
    'schema_bool_manifest',
):
    if required not in runtime_test:
        raise SystemExit(f"Destructive CI lost protected-builder source coverage: {required}")
for required in (
    'RELEASE_DOCKER_HOME', 'RELEASE_DOCKER_CONFIG', 'DockerRootDir',
    '.ClientInfo.Plugins', 'RELEASE_DOCKER_PLUGIN_IDENTITY',
):
    if required not in pathlib.Path(sys.argv[1]).with_name('release-common.sh').read_text(encoding='utf-8'):
        raise SystemExit(f"Docker control-plane identity contract is missing: {required}")
PY

echo "InplaceX release helper hostile tests passed."
