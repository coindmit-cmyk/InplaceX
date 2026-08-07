#!/usr/bin/env bash
# shellcheck disable=SC2016,SC2034
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
production_directory="$repository_root/ops/production"
# shellcheck source=ops/production/release-common.sh
source "$production_directory/release-common.sh"

temporary_directory="$(mktemp -d "$repository_root/.release-helper-test.XXXXXX")"
cleanup() { rm -rf -- "$temporary_directory"; }
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

release_validate_legacy_checksum_history '1,2,3,4,5,6,7,8|1'
release_validate_legacy_checksum_history '1,2,3,4,5,6,7,8|8'
release_validate_legacy_checksum_history '1,2,3,4,5,6,7,8,9|1'
release_validate_legacy_checksum_history '1,2,3,4,5,6,7,8,9|9'
expect_status 75 release_validate_legacy_checksum_history '1,2,3,4,5,6,7,8|9'
expect_status 75 release_validate_legacy_checksum_history '1,2,3,4,5,6,7,8,9|0'
expect_status 75 release_validate_legacy_checksum_history '1,2,3,4,5,6,7,8,9,10|1'
expect_status 75 release_validate_legacy_checksum_history '1,2,3,4,5,6,7,9|1'

mock_directory="$temporary_directory/mock-bin"
mkdir "$mock_directory"
mock_log="$temporary_directory/docker.log"
cat > "$mock_directory/docker" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$MOCK_DOCKER_LOG"
if [[ "$1 $2" == "image inspect" ]]; then
    case "$4" in
        *RepoDigests*) printf '["registry.example/app@sha256:%s"]\n' "$(printf 'a%.0s' {1..64})" ;;
        *version*) printf '%s\n' "${MOCK_RELEASE_ID:-release-1}" ;;
        *revision*) printf '%s\n' "${MOCK_GIT_SHA:-$(printf 'b%.0s' {1..40})}" ;;
        *source-archive*) printf '%s\n' "${MOCK_SOURCE_SHA:-$(printf 'c%.0s' {1..64})}" ;;
        *) exit 67 ;;
    esac
fi
MOCK
chmod +x "$mock_directory/docker"
export MOCK_DOCKER_LOG="$mock_log"
export PATH="$mock_directory:$PATH"
image="registry.example/app@sha256:$(printf 'a%.0s' {1..64})"
git_sha="$(printf 'b%.0s' {1..40})"
source_sha="$(printf 'c%.0s' {1..64})"
release_verify_pulled_image "$image" release-1 "$git_sha" "$source_sha"
grep -Fxq "pull $image" "$mock_log"
export MOCK_RELEASE_ID=hostile-label
expect_status 75 release_verify_pulled_image "$image" release-1 "$git_sha" "$source_sha"

python3 - "$production_directory/deploy-backend.sh" "$production_directory/rollback-backend.sh" <<'PY'
import pathlib
import sys

deploy = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
rollback = pathlib.Path(sys.argv[2]).read_text(encoding="utf-8")

def ordered(source, first, second):
    if source.find(first) < 0 or source.find(second) < 0 or source.find(first) >= source.find(second):
        raise SystemExit(f"Expected ordering not preserved: {first!r} before {second!r}")

ordered(deploy, 'release_verify_pulled_image "$INPLACEX_BACKEND_IMAGE"', 'release_enable_maintenance "$deployment_id"')
ordered(deploy, 'release_verify_pulled_image "$previous_image"', 'release_enable_maintenance "$deployment_id"')
ordered(deploy, 'write_journal intent', 'release_enable_maintenance "$deployment_id"')
ordered(deploy, 'compose_command stop --timeout 30 backend', 'exec pg_dump --format=custom')
ordered(rollback, '"$ROLLBACK_PREVIOUS_IMAGE" "$ROLLBACK_PREVIOUS_RELEASE_ID"', 'release_enable_maintenance "$ROLLBACK_DEPLOYMENT_ID"')
ordered(rollback, 'write_journal intent', 'release_enable_maintenance "$ROLLBACK_DEPLOYMENT_ID"')
ordered(rollback, 'release_remove_durable_file "$RELEASE_VERIFIED_ACTIVATION_FILE"', 'restore_database "$ROLLBACK_BACKUP_PATH"')
for source in (deploy, rollback):
    if 'source "$env_file"' in source or 'source "$receipt_file"' in source:
        raise SystemExit("Production data file must never be sourced")
    if '--wait-timeout' not in source:
        raise SystemExit("Production Compose waits must be bounded")
    if 'release_fault_inject' not in source or 'RELEASE_TRANSACTION_JOURNAL' not in source:
        raise SystemExit("Production mutation must retain durable journal and fault-injection hooks")
PY

echo "InplaceX release helper hostile tests passed."
