#!/usr/bin/env bash
# shellcheck disable=SC2016

# Shared, static shell functions only. Production configuration and receipts are
# parsed as data from already-open file descriptors; neither file is sourced.

readonly RELEASE_LOCK_DIRECTORY="/run/lock/mirkori-games"
readonly RELEASE_LOCK_FILE="$RELEASE_LOCK_DIRECTORY/inplacex-online-release.lock"
readonly RELEASE_RUNTIME_DIRECTORY="/run/inplacex-online"
readonly RELEASE_MAINTENANCE_FILE="$RELEASE_RUNTIME_DIRECTORY/maintenance.flag"
readonly RELEASE_DRAIN_FILE="$RELEASE_RUNTIME_DIRECTORY/drain.flag"
readonly RELEASE_PENDING_ACTIVATION_DIRECTORY="$RELEASE_RUNTIME_DIRECTORY/activation"
readonly RELEASE_PENDING_ACTIVATION_FILE="$RELEASE_PENDING_ACTIVATION_DIRECTORY/pending-activation.env"
readonly RELEASE_MAINTENANCE_SNIPPET="/etc/nginx/snippets/inplacex-online-maintenance-gate.conf"
readonly RELEASE_INSTALLED_LOCATIONS="/etc/nginx/snippets/inplacex-online.locations.conf"
readonly RELEASE_ACTIVATION_V1_MIGRATION_ACK="acknowledge-inplacex-activation-v1-to-v2"
readonly RELEASE_BUILDKIT_IMAGE="moby/buildkit:buildx-stable-1@sha256:2f5adac4ecd194d9f8c10b7b5d7bceb5186853db1b26e5abd3a657af0b7e26ec"
if [[ -z "${RELEASE_DOCKER_BIN+x}" ]]; then
    RELEASE_DOCKER_BIN=docker
fi
if [[ -z "${RELEASE_DOCKER_SOCKET+x}" ]]; then
    RELEASE_DOCKER_SOCKET=""
fi
RELEASE_DOCKER_SOCKET_IDENTITY=""
RELEASE_DOCKER_DAEMON_ID=""
RELEASE_DOCKER_DATA_ROOT=""
RELEASE_DOCKER_PLUGIN_NAME=""
RELEASE_DOCKER_PLUGIN_PATH=""
RELEASE_DOCKER_PLUGIN_IDENTITY=""
RELEASE_DOCKER_HOME=""
RELEASE_DOCKER_CONFIG=""
RELEASE_PRODUCTION_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly RELEASE_PRODUCTION_DIRECTORY
RELEASE_BACKEND_STOP_PROOF_FAILED="${RELEASE_BACKEND_STOP_PROOF_FAILED:-false}"

release_die() {
    local status="$1"
    shift
    printf '%s\n' "$*" >&2
    exit "$status"
}

release_require_commands() {
    local command_name
    for command_name in "$@"; do
        command -v "$command_name" >/dev/null ||
            release_die 69 "Required command is missing: $command_name"
    done
}

release_validate_protected_executable() {
    local executable_path="$1"
    local label="$2"
    [[ "$executable_path" == /* ]] ||
        release_die 77 "$label must be an absolute path"
    release_validate_absolute_parent_chain "$executable_path"
    [[ -f "$executable_path" && -x "$executable_path" && ! -L "$executable_path" ]] ||
        release_die 77 "$label is not a protected regular file: $executable_path"
    [[ "$(stat -c '%u' -- "$executable_path")" == "0" ]] ||
        release_die 77 "$label must be owned by root: $executable_path"
    local executable_mode
    executable_mode="$(stat -c '%a' -- "$executable_path")"
    (( (8#$executable_mode & 022) == 0 )) ||
        release_die 77 "$label must not be group/world writable: $executable_path"
}

release_docker_raw() {
    local -a docker_environment=(
        /usr/bin/env -i
        PATH=/usr/sbin:/usr/bin:/sbin:/bin
        HOME="$RELEASE_DOCKER_HOME"
        DOCKER_CONFIG="$RELEASE_DOCKER_CONFIG"
        DOCKER_HOST="${RELEASE_DOCKER_HOST:-unix:///var/run/docker.sock}"
        LANG=C
        LC_ALL=C
    )
    if [[ "${INPLACEX_RELEASE_ISOLATED_CI_ACK:-}" == \
            "${RELEASE_ISOLATED_CI_ACK:-acknowledge-inplacex-isolated-release-ci}" &&
        -n "${INPLACEX_RELEASE_TEST_DOCKER_BIN:-}" &&
        "$RELEASE_DOCKER_BIN" == "$INPLACEX_RELEASE_TEST_DOCKER_BIN" ]]; then
        local test_environment_name
        for test_environment_name in \
            INPLACEX_TEST_REAL_DOCKER INPLACEX_TEST_DOCKER_LOG \
            INPLACEX_TEST_STOP_MODE INPLACEX_TEST_CONTROL_MODE \
            INPLACEX_TEST_CONTROL_COUNTER INPLACEX_TEST_UNSAFE_PLUGIN \
            INPLACEX_TEST_BACKEND_STATE INPLACEX_TEST_RELEASE_ID \
            INPLACEX_TEST_GIT_SHA INPLACEX_TEST_SOURCE_SHA; do
            if [[ -n "${!test_environment_name+x}" ]]; then
                docker_environment+=("$test_environment_name=${!test_environment_name}")
            fi
        done
    fi
    "${docker_environment[@]}" "$RELEASE_DOCKER_BIN" "$@"
}

release_read_docker_daemon_identity() {
    local docker_info
    docker_info="$(release_docker_raw info --format '{{json .}}')" ||
        release_die 69 "Cannot read Docker daemon identity"
    python3 -I - "$docker_info" <<'PY'
import json
import re
import sys

value = json.loads(sys.argv[1])
daemon_id = value.get("ID")
data_root = value.get("DockerRootDir")
if not isinstance(daemon_id, str) or re.fullmatch(r"[A-Za-z0-9._:+-]{8,128}", daemon_id) is None:
    raise SystemExit("Docker daemon ID is missing or invalid")
if not isinstance(data_root, str) or re.fullmatch(r"/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*", data_root) is None:
    raise SystemExit("Docker data root is missing or non-canonical")
print(daemon_id)
print(data_root)
PY
}

release_prepare_docker_control_plane() {
    local required_plugin="${1:-compose}"
    local environment_root="${2:-$RELEASE_RUNTIME_DIRECTORY/docker-cli}"
    [[ "$required_plugin" == "compose" || "$required_plugin" == "buildx" ]] ||
        release_die 64 "Docker control plane requires compose or buildx plugin identity"
    [[ "$RELEASE_DOCKER_BIN" == /* ]] || {
        [[ -z "$RELEASE_DOCKER_SOCKET" ]] && return 0
        release_die 77 "Production Docker executable must be an absolute path"
    }
    release_validate_protected_executable "$RELEASE_DOCKER_BIN" "Docker executable"

    if [[ -n "$RELEASE_DOCKER_SOCKET" ]]; then
        [[ -S "$RELEASE_DOCKER_SOCKET" && ! -L "$RELEASE_DOCKER_SOCKET" ]] ||
            release_die 77 "Docker daemon socket is missing or unsafe: $RELEASE_DOCKER_SOCKET"
        [[ "$(stat -c '%u' -- "$RELEASE_DOCKER_SOCKET")" == "0" ]] ||
            release_die 77 "Docker daemon socket must be owned by root"
        RELEASE_DOCKER_SOCKET_IDENTITY="$(stat -c '%d:%i' -- "$RELEASE_DOCKER_SOCKET")"
    fi

    release_validate_canonical_absolute_path "$environment_root" "Docker CLI environment root"
    release_validate_absolute_parent_chain "$environment_root"
    if [[ ! -e "$environment_root" ]]; then
        install -d -o root -g root -m 0700 "$environment_root"
    fi
    [[ -d "$environment_root" && ! -L "$environment_root" &&
        "$(stat -Lc '%F %u %g %a' -- "$environment_root")" == "directory 0 0 700" ]] ||
        release_die 77 "Docker CLI environment root must be root:root 0700"
    RELEASE_DOCKER_HOME="$environment_root/home"
    RELEASE_DOCKER_CONFIG="$environment_root/config"
    install -d -o root -g root -m 0700 "$RELEASE_DOCKER_HOME" "$RELEASE_DOCKER_CONFIG"
    local controlled_directory
    for controlled_directory in "$RELEASE_DOCKER_HOME" "$RELEASE_DOCKER_CONFIG"; do
        [[ "$(stat -Lc '%F %u %g %a' -- "$controlled_directory")" == "directory 0 0 700" ]] ||
            release_die 77 "Docker HOME/config must be root:root 0700"
        [[ -z "$(find "$controlled_directory" -mindepth 1 -print -quit)" ]] ||
            release_die 77 "Docker HOME/config must be empty before release use"
    done

    local -a daemon_identity=()
    mapfile -t daemon_identity < <(release_read_docker_daemon_identity)
    [[ ${#daemon_identity[@]} -eq 2 ]] || release_die 69 "Cannot parse Docker daemon identity"
    RELEASE_DOCKER_DAEMON_ID="${daemon_identity[0]}"
    RELEASE_DOCKER_DATA_ROOT="${daemon_identity[1]}"

    local plugin_inventory
    plugin_inventory="$(release_docker_raw info --format '{{json .ClientInfo.Plugins}}')" ||
        release_die 69 "Cannot read Docker CLI plugin inventory"
    RELEASE_DOCKER_PLUGIN_PATH="$(python3 -I - "$plugin_inventory" "$required_plugin" <<'PY'
import json
import sys

plugins = json.loads(sys.argv[1])
if not isinstance(plugins, list):
    raise SystemExit("Docker CLI plugin inventory is unavailable")
matches = []
for plugin in plugins:
    if not isinstance(plugin, dict):
        continue
    name = plugin.get("Name", plugin.get("name"))
    path = plugin.get("Path", plugin.get("path"))
    if name == sys.argv[2] and isinstance(path, str):
        matches.append(path)
if len(matches) != 1:
    raise SystemExit("Required Docker CLI plugin identity is missing or ambiguous")
print(matches[0])
PY
)"
    release_validate_protected_executable \
        "$RELEASE_DOCKER_PLUGIN_PATH" "Docker $required_plugin plugin"
    RELEASE_DOCKER_PLUGIN_NAME="$required_plugin"
    RELEASE_DOCKER_PLUGIN_IDENTITY="$(stat -c '%d:%i' -- "$RELEASE_DOCKER_PLUGIN_PATH"):$(sha256sum "$RELEASE_DOCKER_PLUGIN_PATH" | awk '{print $1}')"
    [[ "$RELEASE_DOCKER_PLUGIN_IDENTITY" =~ ^[0-9]+:[0-9]+:[0-9a-f]{64}$ ]] ||
        release_die 70 "Cannot fingerprint Docker $required_plugin plugin"
    release_docker_raw "$required_plugin" version >/dev/null

    readonly RELEASE_DOCKER_SOCKET_IDENTITY RELEASE_DOCKER_DAEMON_ID RELEASE_DOCKER_DATA_ROOT
    readonly RELEASE_DOCKER_PLUGIN_NAME RELEASE_DOCKER_PLUGIN_PATH RELEASE_DOCKER_PLUGIN_IDENTITY
    readonly RELEASE_DOCKER_HOME RELEASE_DOCKER_CONFIG
}

release_docker() {
    if [[ -n "$RELEASE_DOCKER_SOCKET" ]]; then
        [[ -S "$RELEASE_DOCKER_SOCKET" && ! -L "$RELEASE_DOCKER_SOCKET" &&
            "$(stat -c '%d:%i' -- "$RELEASE_DOCKER_SOCKET")" == "$RELEASE_DOCKER_SOCKET_IDENTITY" ]] ||
            release_die 77 "Docker daemon identity changed during the release transaction"
    fi
    if [[ -n "$RELEASE_DOCKER_DAEMON_ID" ]]; then
        local -a current_daemon_identity=()
        mapfile -t current_daemon_identity < <(release_read_docker_daemon_identity)
        [[ ${#current_daemon_identity[@]} -eq 2 &&
            "${current_daemon_identity[0]}" == "$RELEASE_DOCKER_DAEMON_ID" &&
            "${current_daemon_identity[1]}" == "$RELEASE_DOCKER_DATA_ROOT" ]] ||
            release_die 77 "Docker daemon ID or data root changed during the release transaction"
    fi
    if [[ -n "$RELEASE_DOCKER_PLUGIN_NAME" && "${1:-}" == "$RELEASE_DOCKER_PLUGIN_NAME" ]]; then
        [[ -f "$RELEASE_DOCKER_PLUGIN_PATH" && ! -L "$RELEASE_DOCKER_PLUGIN_PATH" &&
            "$(stat -c '%d:%i' -- "$RELEASE_DOCKER_PLUGIN_PATH"):$(sha256sum "$RELEASE_DOCKER_PLUGIN_PATH" | awk '{print $1}')" == "$RELEASE_DOCKER_PLUGIN_IDENTITY" ]] ||
            release_die 77 "Docker $RELEASE_DOCKER_PLUGIN_NAME plugin identity changed"
    fi
    release_docker_raw "$@"
}

release_expected_buildkitd_config() {
    local registry_authority="$1"
    local registry_port=""
    [[ "$registry_authority" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[1-9][0-9]{0,4})?$ ]] ||
        release_die 65 "BuildKit registry authority is invalid"
    if [[ "$registry_authority" == *:* ]]; then
        registry_port="${registry_authority##*:}"
        (( 10#$registry_port <= 65535 )) ||
            release_die 65 "BuildKit registry port is invalid"
    fi

    printf '%s\n' 'debug = false'
    if [[ "$registry_authority" =~ ^127\.0\.0\.1:([1-9][0-9]{0,4})$ ]]; then
        printf '\n[registry."%s"]\n  http = true\n' "$registry_authority"
    fi
}

release_write_buildkitd_config() {
    local destination_path="$1"
    local registry_authority="$2"
    [[ "$destination_path" == /* && ! -e "$destination_path" &&
        ! -L "$destination_path" ]] ||
        release_die 66 "BuildKit configuration destination must be a new absolute path"
    local destination_parent="${destination_path%/*}"
    [[ -n "$destination_parent" ]] || destination_parent=/
    [[ -d "$destination_parent" && ! -L "$destination_parent" ]] ||
        release_die 66 "BuildKit configuration parent must be a real directory"

    local expected_config
    expected_config="$(release_expected_buildkitd_config "$registry_authority")"
    (umask 077; printf '%s\n' "$expected_config" > "$destination_path")
    chmod 0600 "$destination_path"
    [[ -f "$destination_path" && ! -L "$destination_path" &&
        "$(stat -Lc '%F %u %a %h' -- "$destination_path")" == \
            "regular file ${EUID:-$(id -u)} 600 1" &&
        "$(<"$destination_path")" == "$expected_config" ]] ||
        release_die 77 "Generated BuildKit configuration identity is unsafe"
}

release_create_isolated_buildx_builder() {
    local builder_name="$1"
    local buildkit_config_path="$2"
    local registry_authority="$3"
    [[ "$builder_name" =~ ^[a-z0-9][a-z0-9_.-]{0,63}$ ]] ||
        release_die 65 "Buildx builder name is invalid"
    [[ "${RELEASE_DOCKER_HOST:-}" == "unix:///var/run/docker.sock" ]] ||
        release_die 77 "Buildx builder requires the exact local Docker socket"
    [[ -f "$buildkit_config_path" && ! -L "$buildkit_config_path" &&
        "$(stat -Lc '%F %u %a %h' -- "$buildkit_config_path")" == \
        "regular file ${EUID:-$(id -u)} 600 1" &&
        "$(<"$buildkit_config_path")" == \
            "$(release_expected_buildkitd_config "$registry_authority")" ]] ||
        release_die 77 "BuildKit daemon configuration is not the exact generated contract"

    local -a create_arguments=(
        buildx create
        --name "$builder_name"
        --driver docker-container
        --driver-opt "image=$RELEASE_BUILDKIT_IMAGE"
        --buildkitd-config "$buildkit_config_path"
    )
    if [[ "$registry_authority" =~ ^127\.0\.0\.1:([1-9][0-9]{0,4})$ ]]; then
        create_arguments+=(--driver-opt network=host)
    fi
    create_arguments+=("$RELEASE_DOCKER_HOST")
    release_docker "${create_arguments[@]}"
}

release_remove_isolated_buildx_builder() {
    local builder_name="$1"
    [[ "$builder_name" =~ ^[a-z0-9][a-z0-9_.-]{0,63}$ ]] ||
        release_die 65 "Buildx builder name is invalid"
    release_docker buildx rm --force --timeout 30s "$builder_name"
}

release_assert_docker_container_absent_exact() {
    local container_name="$1"
    [[ "$container_name" =~ ^[a-z0-9][a-z0-9_-]{0,127}$ ]] ||
        release_die 65 "Docker container name is invalid"

    local container_ids
    if ! container_ids="$(release_docker ps --all --quiet \
        --filter "name=^/${container_name}$")"; then
        echo "Cannot verify Docker container absence: $container_name" >&2
        return 70
    fi
    if [[ -n "$container_ids" ]]; then
        echo "Docker container still exists after cleanup: $container_name" >&2
        printf '%s\n' "$container_ids" >&2
        return 75
    fi
}

release_validate_absolute_parent_chain() {
    local path="$1"
    [[ "$path" == /* && "$path" != *$'\n'* && "$path" != *$'\r'* ]] ||
        release_die 66 "Path must be absolute and single-line: $path"

    local current
    current="$(dirname -- "$path")"
    while :; do
        [[ -d "$current" && ! -L "$current" ]] ||
            release_die 66 "Path parent must be a real directory: $current"
        [[ "$(stat -c '%u' -- "$current")" == "0" ]] ||
            release_die 77 "Path parent must be owned by root: $current"
        local mode
        mode="$(stat -c '%a' -- "$current")"
        (( (8#$mode & 022) == 0 )) ||
            release_die 77 "Path parent must not be group/world writable: $current"
        [[ "$current" == "/" ]] && break
        current="$(dirname -- "$current")"
    done
}

release_open_root_config() {
    local path="$1"
    local output_variable="$2"
    release_validate_absolute_parent_chain "$path"
    [[ ! -L "$path" ]] || release_die 66 "Refusing a symlink configuration file: $path"

    local opened_fd
    exec {opened_fd}<"$path" || release_die 66 "Cannot open configuration file: $path"
    local metadata path_identity descriptor_identity
    metadata="$(stat -Lc '%F %u %a %h' -- "/proc/self/fd/$opened_fd")"
    [[ "$metadata" =~ ^regular\ file\ 0\ ([0-7]+)\ 1$ ]] ||
        release_die 77 "Configuration must be a root-owned, single-link regular file: $path"
    local mode="${BASH_REMATCH[1]}"
    (( (8#$mode & 077) == 0 )) ||
        release_die 77 "Configuration must not be accessible by group/world: $path"
    path_identity="$(stat -Lc '%d:%i' -- "$path")"
    descriptor_identity="$(stat -Lc '%d:%i' -- "/proc/self/fd/$opened_fd")"
    [[ "$path_identity" == "$descriptor_identity" ]] ||
        release_die 75 "Configuration path changed while it was opened: $path"
    printf -v "$output_variable" '%s' "$opened_fd"
}

release_normalize_registry_auth_config() {
    local source_path="$1"
    local registry_authority="$2"
    local destination_path="$3"
    local registry_port=""
    [[ "$registry_authority" =~ ^[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[1-9][0-9]{0,4})?$ ]] ||
        release_die 65 "Registry auth authority is invalid"
    if [[ "$registry_authority" == *:* ]]; then
        registry_port="${registry_authority##*:}"
        (( 10#$registry_port <= 65535 )) || release_die 65 "Registry auth port is invalid"
    fi
    release_validate_absolute_parent_chain "$source_path"
    [[ "$source_path" == /* && -f "$source_path" && ! -L "$source_path" ]] ||
        release_die 66 "Registry auth config must be an absolute regular non-symlink file"
    [[ "$(stat -Lc '%F %u %g %a %h' -- "$source_path")" == "regular file 0 0 600 1" ]] ||
        release_die 77 "Registry auth config must be root:root 0600 with one link"
    release_validate_absolute_parent_chain "$destination_path"
    [[ "$destination_path" == /* && ! -e "$destination_path" && ! -L "$destination_path" ]] ||
        release_die 66 "Normalized registry auth destination must be a new absolute path"

    local auth_fd auth_size normalization_status=0
    release_open_root_config "$source_path" auth_fd
    [[ "$(stat -Lc '%F %u %g %a %h' -- "/proc/self/fd/$auth_fd")" == \
        "regular file 0 0 600 1" ]] ||
        release_die 77 "Opened registry auth config identity is unsafe"
    auth_size="$(stat -Lc '%s' -- "/proc/self/fd/$auth_fd")"
    if [[ ! "$auth_size" =~ ^[0-9]+$ ]] || (( auth_size == 0 || auth_size > 16384 )); then
        release_die 65 "Registry auth config size is invalid"
    fi
    python3 -I /dev/fd/3 "$registry_authority" "$destination_path" \
        3<<'PY' <&"$auth_fd" || normalization_status=$?
import base64
import binascii
import json
import os
import pathlib
import re
import sys

authority = sys.argv[1]
destination = pathlib.Path(sys.argv[2])

def unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate JSON key")
        value[key] = item
    return value

try:
    raw = sys.stdin.buffer.read(16385)
    if not raw or len(raw) > 16384:
        raise ValueError("invalid input size")
    value = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)
    if type(value) is not dict or set(value) != {"auths"}:
        raise ValueError("invalid top-level fields")
    auths = value["auths"]
    if type(auths) is not dict or set(auths) != {authority}:
        raise ValueError("registry authority mismatch")
    entry = auths[authority]
    if type(entry) is not dict or set(entry) != {"auth"}:
        raise ValueError("invalid registry auth fields")
    auth = entry["auth"]
    if (
        type(auth) is not str
        or len(auth) > 8192
        or re.fullmatch(r"[A-Za-z0-9+/]+={0,2}", auth) is None
        or len(auth) % 4 != 0
    ):
        raise ValueError("invalid inline auth")
    decoded = base64.b64decode(auth, validate=True)
    if base64.b64encode(decoded).decode("ascii") != auth:
        raise ValueError("non-canonical inline auth")
    username, separator, password = decoded.partition(b":")
    if (
        separator != b":"
        or not username
        or not password
        or len(decoded) > 4096
        or any(byte < 0x20 or byte == 0x7F for byte in decoded)
    ):
        raise ValueError("invalid basic-auth payload")
except (UnicodeDecodeError, ValueError, TypeError, binascii.Error, json.JSONDecodeError):
    raise SystemExit("Registry auth config does not match the strict inline-auth schema")

normalized = json.dumps(
    {"auths": {authority: {"auth": auth}}},
    ensure_ascii=True,
    separators=(",", ":"),
    sort_keys=True,
).encode("ascii") + b"\n"
flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
if hasattr(os, "O_NOFOLLOW"):
    flags |= os.O_NOFOLLOW
descriptor = os.open(destination, flags, 0o600)
try:
    with os.fdopen(descriptor, "wb", closefd=False) as output:
        output.write(normalized)
        output.flush()
        os.fchmod(descriptor, 0o600)
        os.fsync(descriptor)
finally:
    os.close(descriptor)
PY
    exec {auth_fd}<&-
    if (( normalization_status != 0 )); then
        rm -f -- "$destination_path"
        release_die 65 "Registry auth config validation failed"
    fi
    chown root:root "$destination_path"
    chmod 0600 "$destination_path"
    [[ "$(stat -Lc '%F %u %g %a %h' -- "$destination_path")" == \
        "regular file 0 0 600 1" ]] ||
        release_die 77 "Normalized registry auth config identity is unsafe"
    sync -f "$destination_path"
    sync -f "$(dirname -- "$destination_path")"
}

release_parse_allowed_kv_fd() {
    local descriptor="$1"
    local allowlist_name="$2"
    local -n allowed_keys="$allowlist_name"
    local -A seen=()
    local line key value line_number=0

    for key in "${!allowed_keys[@]}"; do
        unset "$key"
    done

    while IFS= read -r line <&"$descriptor" || [[ -n "$line" ]]; do
        line_number=$((line_number + 1))
        [[ "$line" != *$'\r'* && "$line" != *$'\n'* && "$line" != *$'\t'* ]] ||
            release_die 65 "Configuration contains control characters at line $line_number"
        [[ -z "$line" || "$line" == \#* ]] && continue
        [[ "$line" =~ ^([A-Z][A-Z0-9_]*)=(.*)$ ]] ||
            release_die 65 "Configuration must use literal KEY=VALUE lines at line $line_number"
        key="${BASH_REMATCH[1]}"
        value="${BASH_REMATCH[2]}"
        [[ -n "${allowed_keys[$key]+present}" ]] ||
            release_die 65 "Configuration key is not allowed: $key"
        [[ -z "${seen[$key]+present}" ]] ||
            release_die 65 "Configuration key is duplicated: $key"
        [[ "$value" =~ ^[A-Za-z0-9_.:/@,+-]*$ ]] ||
            release_die 65 "Configuration value has unsafe characters: $key"
        seen["$key"]=1
        printf -v "$key" '%s' "$value"
        export "${key?}"
    done
}

release_require_variables() {
    local variable_name
    for variable_name in "$@"; do
        [[ -n "${!variable_name:-}" ]] ||
            release_die 65 "Required production variable is missing: $variable_name"
    done
}

release_validate_canonical_absolute_path() {
    local path="$1"
    local label="${2:-Path}"
    [[ "$path" =~ ^/[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$ ]] ||
        release_die 65 "$label must be a canonical absolute path"
    local component relative_path="${path#/}"
    local -a path_components=()
    IFS=/ read -r -a path_components <<< "$relative_path"
    for component in "${path_components[@]}"; do
        [[ "$component" != "." && "$component" != ".." ]] ||
            release_die 65 "$label must not contain dot segments"
    done
}

release_validate_durable_directory_path() {
    local path="$1"
    local label="${2:-Durable directory}"
    release_validate_canonical_absolute_path "$path" "$label"
    case "$path" in
        /run|/run/*|/tmp|/tmp/*|/var/tmp|/var/tmp/*|/dev|/dev/*|/proc|/proc/*|/sys|/sys/*)
            release_die 65 "$label must stay outside volatile runtime filesystems"
            ;;
    esac
}

release_write_sanitized_env() {
    local output_path="$1"
    local allowlist_name="$2"
    local -n allowed_keys="$allowlist_name"
    [[ "$output_path" == "$RELEASE_RUNTIME_DIRECTORY/"* ]] ||
        release_die 66 "Sanitized Compose environment must stay in $RELEASE_RUNTIME_DIRECTORY"
    [[ ! -L "$output_path" ]] || release_die 77 "Sanitized Compose environment must not be a symlink"
    local temporary
    temporary="$(mktemp "$RELEASE_RUNTIME_DIRECTORY/.compose-env.XXXXXX")"
    chmod 0600 "$temporary"
    local key
    while IFS= read -r key; do
        [[ -n "${!key+x}" ]] && printf '%s=%s\n' "$key" "${!key}" >> "$temporary"
    done < <(printf '%s\n' "${!allowed_keys[@]}" | sort)
    if [[ -e "$output_path" ]]; then
        [[ -f "$output_path" && ! -L "$output_path" ]] || {
            rm -f -- "$temporary"
            release_die 77 "Sanitized Compose environment became unsafe"
        }
        [[ "$(stat -Lc '%F %u %g %a %h' -- "$output_path")" == "regular file 0 0 600 1" ]] || {
            rm -f -- "$temporary"
            release_die 77 "Sanitized Compose environment ownership or mode is invalid"
        }
        if cmp -s -- "$temporary" "$output_path"; then
            rm -f -- "$temporary"
            return 0
        fi
    fi
    mv -f -- "$temporary" "$output_path"
}

release_validate_env_values() {
    release_require_variables \
        COMPOSE_PROJECT_NAME INPLACEX_BACKEND_IMAGE INPLACEX_POSTGRES_IMAGE \
        INPLACEX_BACKEND_LOOPBACK_PORT INPLACEX_POSTGRES_DB INPLACEX_POSTGRES_USER \
        INPLACEX_POSTGRES_VOLUME INPLACEX_SECRET_DIRECTORY INPLACEX_RUNTIME_SECRET_GID \
        INPLACEX_GEOIP_DB_PATH INPLACEX_ONLINE_TOKEN_ISSUER INPLACEX_ONLINE_TOKEN_AUDIENCE \
        INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS INPLACEX_RELEASE_ID INPLACEX_GIT_SHA \
        INPLACEX_IMAGE_DIGEST INPLACEX_SOURCE_ARCHIVE_SHA256 INPLACEX_RELEASE_MANIFEST_PATH \
        INPLACEX_INITIAL_DEPLOY INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS \
        INPLACEX_RELEASE_STATE_DIRECTORY INPLACEX_PUBLIC_HOSTNAME INPLACEX_OPERATOR_NETWORK_CIDR \
        INPLACEX_DRAIN_TIMEOUT_SECONDS

    [[ "$COMPOSE_PROJECT_NAME" =~ ^[a-z0-9][a-z0-9_-]{0,62}$ ]] ||
        release_die 65 "COMPOSE_PROJECT_NAME has an invalid format"
    release_validate_image_reference "$INPLACEX_BACKEND_IMAGE"
    release_validate_image_reference "$INPLACEX_POSTGRES_IMAGE"
    [[ "$INPLACEX_IMAGE_DIGEST" == "${INPLACEX_BACKEND_IMAGE##*@}" ]] ||
        release_die 65 "INPLACEX_IMAGE_DIGEST does not match INPLACEX_BACKEND_IMAGE"
    release_validate_port "$INPLACEX_BACKEND_LOOPBACK_PORT"
    [[ "$INPLACEX_POSTGRES_DB" =~ ^[A-Za-z_][A-Za-z0-9_]{0,62}$ ]] ||
        release_die 65 "INPLACEX_POSTGRES_DB has an invalid format"
    [[ "$INPLACEX_POSTGRES_USER" =~ ^[A-Za-z_][A-Za-z0-9_]{0,62}$ ]] ||
        release_die 65 "INPLACEX_POSTGRES_USER has an invalid format"
    [[ "$INPLACEX_POSTGRES_VOLUME" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$ ]] ||
        release_die 65 "INPLACEX_POSTGRES_VOLUME has an invalid format"
    if [[ ! "$INPLACEX_RUNTIME_SECRET_GID" =~ ^[0-9]+$ || ${#INPLACEX_RUNTIME_SECRET_GID} -gt 10 ]] ||
        (( 10#$INPLACEX_RUNTIME_SECRET_GID < 1 || 10#$INPLACEX_RUNTIME_SECRET_GID > 2147483647 )); then
        release_die 65 "INPLACEX_RUNTIME_SECRET_GID must be a positive numeric GID"
    fi
    [[ "$INPLACEX_RELEASE_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] ||
        release_die 65 "INPLACEX_RELEASE_ID has an invalid format"
    [[ "$INPLACEX_GIT_SHA" =~ ^[0-9a-f]{40}$ ]] ||
        release_die 65 "INPLACEX_GIT_SHA must be a lowercase 40-character SHA"
    [[ "$INPLACEX_SOURCE_ARCHIVE_SHA256" =~ ^[0-9a-f]{64}$ ]] ||
        release_die 65 "INPLACEX_SOURCE_ARCHIVE_SHA256 must be a lowercase SHA-256"
    [[ "$INPLACEX_RELEASE_MANIFEST_PATH" == /* ]] ||
        release_die 65 "INPLACEX_RELEASE_MANIFEST_PATH must be absolute"
    [[ "$INPLACEX_INITIAL_DEPLOY" == "true" || "$INPLACEX_INITIAL_DEPLOY" == "false" ]] ||
        release_die 65 "INPLACEX_INITIAL_DEPLOY must be true or false"
    if [[ ! "$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS" =~ ^[0-9]+$ || ${#INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS} -gt 3 ]] ||
        (( 10#$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS < 30 || 10#$INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS > 600 )); then
        release_die 65 "INPLACEX_COMPOSE_WAIT_TIMEOUT_SECONDS must be in 30..600"
    fi
    [[ -z "${INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK:-}" ||
        "$INPLACEX_DATABASE_LEGACY_CHECKSUM_BASELINE_ACK" == "acknowledge-inplacex-schema-v1-v8" ]] ||
        release_die 65 "Legacy checksum acknowledgement is not the exact documented value"
    local fallback_seconds="${INPLACEX_MATCHMAKING_BOT_FALLBACK_SECONDS:-5}"
    if [[ ! "$fallback_seconds" =~ ^[0-9]+$ || ${#fallback_seconds} -gt 2 ]] ||
        (( 10#$fallback_seconds < 1 || 10#$fallback_seconds > 60 )); then
        release_die 65 "INPLACEX_MATCHMAKING_BOT_FALLBACK_SECONDS must be in 1..60"
    fi
    [[ "$INPLACEX_SECRET_DIRECTORY" == /* && "$INPLACEX_GEOIP_DB_PATH" == /* ]] ||
        release_die 65 "Runtime file paths must be absolute"
    release_validate_durable_directory_path \
        "$INPLACEX_RELEASE_STATE_DIRECTORY" "Release state directory"
    python3 -I - "$INPLACEX_OPERATOR_NETWORK_CIDR" "$INPLACEX_PUBLIC_HOSTNAME" <<'PY'
import ipaddress
import re
import sys

try:
    network = ipaddress.ip_network(sys.argv[1], strict=True)
except ValueError as error:
    raise SystemExit("INPLACEX_OPERATOR_NETWORK_CIDR must be one canonical IPv4 or IPv6 CIDR") from error
if str(network) != sys.argv[1].lower():
    raise SystemExit("INPLACEX_OPERATOR_NETWORK_CIDR must use its canonical network address")

hostname = sys.argv[2].lower()
if hostname != sys.argv[2] or not 4 <= len(hostname) <= 253 or "." not in hostname:
    raise SystemExit("INPLACEX_PUBLIC_HOSTNAME must be one lowercase DNS hostname")
label_pattern = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
if any(label_pattern.fullmatch(label) is None for label in hostname.split(".")):
    raise SystemExit("INPLACEX_PUBLIC_HOSTNAME contains an invalid DNS label")
PY
    if [[ ! "$INPLACEX_DRAIN_TIMEOUT_SECONDS" =~ ^[0-9]+$ || ${#INPLACEX_DRAIN_TIMEOUT_SECONDS} -gt 3 ]] ||
        (( 10#$INPLACEX_DRAIN_TIMEOUT_SECONDS < 10 || 10#$INPLACEX_DRAIN_TIMEOUT_SECONDS > 300 )); then
        release_die 65 "INPLACEX_DRAIN_TIMEOUT_SECONDS must be in 10..300"
    fi
    local rotation_ack="${INPLACEX_ALLOW_PUBLIC_KEY_ROTATION_FROM_SHA256:-}"
    [[ -z "$rotation_ack" || "$rotation_ack" =~ ^[0-9a-f]{64}$ ]] ||
        release_die 65 "Public-key rotation acknowledgement must be the exact previous SHA-256"
    local activation_migration_ack="${INPLACEX_ACTIVATION_V1_MIGRATION_ACK:-}"
    [[ -z "$activation_migration_ack" || "$activation_migration_ack" == "$RELEASE_ACTIVATION_V1_MIGRATION_ACK" ]] ||
        release_die 65 "Activation v1 migration acknowledgement is not the exact documented value"
    local country_header="${INPLACEX_AD_MARKET_COUNTRY_HEADER:-}"
    local container_database_path="${INPLACEX_AD_MARKET_CONTAINER_DB_PATH-/var/lib/inplacex/geoip/dbip-country-lite.mmdb}"
    if [[ -n "$country_header" ]]; then
        [[ "$country_header" =~ ^[A-Za-z0-9-]{1,64}$ && -z "$container_database_path" ]] ||
            release_die 65 "Country-header mode requires a safe header and an empty container database path"
    else
        [[ "$container_database_path" == "/var/lib/inplacex/geoip/dbip-country-lite.mmdb" ]] ||
            release_die 65 "MMDB mode requires the canonical read-only container path"
    fi
}

release_validate_legacy_checksum_history() {
    local legacy_history="$1"
    [[ "$legacy_history" =~ ^(1,2,3,4,5,6,7,8\|[1-8]|1,2,3,4,5,6,7,8,9\|[1-9])$ ]] ||
        release_die 75 "Legacy checksum acknowledgement requires exact known v1-v8 or v1-v9 history with missing checksums"
}

release_validate_completed_legacy_checksum_history() {
    local legacy_history="$1"
    [[ "$legacy_history" =~ ^(1,2,3,4,5,6,7,8|1,2,3,4,5,6,7,8,9)\|0$ ]] ||
        release_die 75 "Completed legacy checksum baseline requires exact known v1-v8 or v1-v9 history"
}

release_validate_port() {
    local port="$1"
    if [[ ! "$port" =~ ^[0-9]+$ || ${#port} -gt 5 ]] || (( 10#$port < 1 || 10#$port > 65535 )); then
        release_die 65 "Port must be an integer in 1..65535"
    fi
}

release_validate_image_reference() {
    local image="$1"
    [[ "$image" =~ ^[^[:space:]@]+@sha256:[0-9a-f]{64}$ ]] ||
        release_die 65 "Image must be an immutable @sha256 reference: $image"
}

release_acquire_lock() {
    local lock_fd
    [[ -d /run/lock && ! -L /run/lock && "$(stat -c '%u' -- /run/lock)" == "0" ]] ||
        release_die 77 "/run/lock must be a real root-owned directory"
    if [[ -e "$RELEASE_LOCK_DIRECTORY" || -L "$RELEASE_LOCK_DIRECTORY" ]]; then
        [[ -d "$RELEASE_LOCK_DIRECTORY" && ! -L "$RELEASE_LOCK_DIRECTORY" ]] ||
            release_die 77 "Shared release lock path must be a real directory"
    else
        install -d -o root -g root -m 0700 "$RELEASE_LOCK_DIRECTORY"
    fi
    [[ "$(stat -c '%u %g %a' -- "$RELEASE_LOCK_DIRECTORY")" == "0 0 700" ]] ||
        release_die 77 "Shared release lock directory must be root:root mode 0700"
    if [[ -e "$RELEASE_LOCK_FILE" || -L "$RELEASE_LOCK_FILE" ]]; then
        [[ -f "$RELEASE_LOCK_FILE" && ! -L "$RELEASE_LOCK_FILE" ]] ||
            release_die 77 "Release lock must be a regular non-symlink file"
    else
        ( set -C; : > "$RELEASE_LOCK_FILE" ) || release_die 73 "Cannot create release lock safely"
        chown root:root "$RELEASE_LOCK_FILE"
        chmod 0600 "$RELEASE_LOCK_FILE"
    fi
    exec {lock_fd}<>"$RELEASE_LOCK_FILE" ||
        release_die 77 "Cannot open the private InplaceX release lock"
    [[ -f "/proc/self/fd/$lock_fd" ]] ||
        release_die 77 "Release lock must be a regular file"
    [[ "$(stat -Lc '%u %g %a %h' -- "/proc/self/fd/$lock_fd")" == "0 0 600 1" ]] ||
        release_die 77 "Release lock must be a root:root 0600 single-link regular file"
    [[ ! -L "$RELEASE_LOCK_FILE" ]] || release_die 77 "Release lock must not be a symlink"
    [[ "$(stat -Lc '%d:%i' -- "$RELEASE_LOCK_FILE")" == "$(stat -Lc '%d:%i' -- "/proc/self/fd/$lock_fd")" ]] ||
        release_die 75 "Release lock path changed while it was opened"
    flock -n "$lock_fd" || release_die 75 "Another Mirkori/InplaceX release operation is active"
    RELEASE_LOCK_FD="$lock_fd"
    export RELEASE_LOCK_FD
}

release_prepare_runtime_directory() {
    [[ -d /run && ! -L /run && "$(stat -c '%u' -- /run)" == "0" ]] ||
        release_die 77 "/run must be a real root-owned directory"
    if [[ ! -e "$RELEASE_RUNTIME_DIRECTORY" ]]; then
        install -d -o root -g root -m 0755 "$RELEASE_RUNTIME_DIRECTORY"
    fi
    [[ -d "$RELEASE_RUNTIME_DIRECTORY" && ! -L "$RELEASE_RUNTIME_DIRECTORY" ]] ||
        release_die 77 "Release runtime path must be a directory"
    [[ "$(stat -c '%u %g %a' -- "$RELEASE_RUNTIME_DIRECTORY")" == "0 0 755" ]] ||
        release_die 77 "Release runtime directory must be root:root mode 0755"
}

release_prepare_state_directories() {
    local durable_directory="$INPLACEX_RELEASE_STATE_DIRECTORY"
    release_validate_durable_directory_path "$durable_directory" "Release state directory"
    release_validate_absolute_parent_chain "$durable_directory"
    if [[ ! -e "$durable_directory" ]]; then
        install -d -o root -g root -m 0700 "$durable_directory"
        release_sync_directory "$(dirname -- "$durable_directory")"
    fi
    [[ -d "$durable_directory" && ! -L "$durable_directory" ]] ||
        release_die 77 "Release state path must be a real directory"
    [[ "$(stat -c '%u %g %a' -- "$durable_directory")" == "0 0 700" ]] ||
        release_die 77 "Release state directory must be root:root mode 0700"

    RELEASE_ACTIVATION_DIRECTORY="$durable_directory/activation"
    RELEASE_VERIFIED_ACTIVATION_FILE="$RELEASE_ACTIVATION_DIRECTORY/verified-activation.env"
    RELEASE_TRANSACTION_JOURNAL="$durable_directory/release-transaction.env"
    export RELEASE_ACTIVATION_DIRECTORY RELEASE_VERIFIED_ACTIVATION_FILE RELEASE_TRANSACTION_JOURNAL
    if [[ ! -e "$RELEASE_ACTIVATION_DIRECTORY" ]]; then
        install -d -o root -g "$INPLACEX_RUNTIME_SECRET_GID" -m 0750 "$RELEASE_ACTIVATION_DIRECTORY"
        release_sync_directory "$durable_directory"
    fi
    [[ -d "$RELEASE_ACTIVATION_DIRECTORY" && ! -L "$RELEASE_ACTIVATION_DIRECTORY" ]] ||
        release_die 77 "Activation state path must be a real directory"
    [[ "$(stat -c '%u %g %a' -- "$RELEASE_ACTIVATION_DIRECTORY")" == "0 $INPLACEX_RUNTIME_SECRET_GID 750" ]] ||
        release_die 77 "Activation state directory ownership or mode is invalid"

    if [[ ! -e "$RELEASE_PENDING_ACTIVATION_DIRECTORY" ]]; then
        install -d -o root -g "$INPLACEX_RUNTIME_SECRET_GID" -m 0750 "$RELEASE_PENDING_ACTIVATION_DIRECTORY"
    fi
    [[ -d "$RELEASE_PENDING_ACTIVATION_DIRECTORY" && ! -L "$RELEASE_PENDING_ACTIVATION_DIRECTORY" ]] ||
        release_die 77 "Pending activation path must be a real directory"
    [[ "$(stat -c '%u %g %a' -- "$RELEASE_PENDING_ACTIVATION_DIRECTORY")" == "0 $INPLACEX_RUNTIME_SECRET_GID 750" ]] ||
        release_die 77 "Pending activation directory ownership or mode is invalid"
}

release_validate_secret_tree() {
    local directory="$INPLACEX_SECRET_DIRECTORY"
    release_validate_absolute_parent_chain "$directory"
    [[ -d "$directory" && ! -L "$directory" ]] ||
        release_die 66 "Secret directory must be a real directory"
    [[ "$(stat -c '%u %g %a' -- "$directory")" == "0 $INPLACEX_RUNTIME_SECRET_GID 750" ]] ||
        release_die 77 "Secret directory must be root:$INPLACEX_RUNTIME_SECRET_GID mode 0750"

    local secret_path
    for secret_path in \
        "$directory/database-password.txt" \
        "$directory/platform-public-key-x509-base64.txt" \
        "$directory/online-state-key-base64.txt"; do
        release_validate_absolute_parent_chain "$secret_path"
        [[ -f "$secret_path" && ! -L "$secret_path" ]] ||
            release_die 66 "Secret must be a regular non-symlink file: $secret_path"
        [[ "$(stat -Lc '%F %u %g %a %h' -- "$secret_path")" == "regular file 0 $INPLACEX_RUNTIME_SECRET_GID 640 1" ]] ||
            release_die 77 "Secret must be root:$INPLACEX_RUNTIME_SECRET_GID mode 0640 with one link: $secret_path"
    done
}

release_validate_secret_payloads() {
    python3 -I - "$INPLACEX_SECRET_DIRECTORY/database-password.txt" \
        "$INPLACEX_SECRET_DIRECTORY/online-state-key-base64.txt" <<'PY'
import base64
import binascii
import pathlib
import sys

password = pathlib.Path(sys.argv[1]).read_bytes()
if len(password) > 512:
    raise SystemExit("Database password file is too large")
try:
    password_text = password.decode("utf-8").rstrip("\r\n")
except UnicodeDecodeError as error:
    raise SystemExit("Database password must be UTF-8") from error
if len(password_text) < 16 or any(character.isspace() or ord(character) < 32 for character in password_text):
    raise SystemExit("Database password must contain 16..512 non-whitespace UTF-8 characters")

encoded_state_key = pathlib.Path(sys.argv[2]).read_text(encoding="utf-8").rstrip("\r\n")
try:
    state_key = base64.b64decode(encoded_state_key, validate=True)
except (binascii.Error, ValueError) as error:
    raise SystemExit("Online state key is not strict Base64") from error
if len(state_key) != 32:
    raise SystemExit("Online state key must decode to exactly 32 bytes")
PY

    local public_key_description
    public_key_description="$({
        python3 -I - "$INPLACEX_SECRET_DIRECTORY/platform-public-key-x509-base64.txt" <<'PY'
import base64
import binascii
import pathlib
import sys

encoded = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").rstrip("\r\n")
try:
    decoded = base64.b64decode(encoded, validate=True)
except (binascii.Error, ValueError) as error:
    raise SystemExit("Platform public key is not strict Base64") from error
if not 128 <= len(decoded) <= 8192:
    raise SystemExit("Platform public key has an invalid encoded size")
sys.stdout.buffer.write(decoded)
PY
    } | openssl pkey -pubin -inform DER -text_pub -noout)" ||
        release_die 65 "Platform public key is not a valid X509 RSA public key"
    local modulus_bits
    modulus_bits="$(sed -nE 's/^Public-Key: \(([0-9]+) bit\)$/\1/p' <<< "$public_key_description" | head -n 1)"
    if [[ ! "$modulus_bits" =~ ^[0-9]+$ ]] || (( 10#$modulus_bits < 2048 )); then
        release_die 65 "Platform RSA public key must contain at least 2048 bits"
    fi
}

release_validate_geoip_file() {
    release_validate_absolute_parent_chain "$INPLACEX_GEOIP_DB_PATH"
    [[ -f "$INPLACEX_GEOIP_DB_PATH" && ! -L "$INPLACEX_GEOIP_DB_PATH" ]] ||
        release_die 66 "GeoIP input must be a regular non-symlink file"
    local metadata mode
    metadata="$(stat -Lc '%F %u %a %h' -- "$INPLACEX_GEOIP_DB_PATH")"
    [[ "$metadata" =~ ^regular\ file\ 0\ ([0-7]+)\ 1$ ]] ||
        release_die 77 "GeoIP input must be root-owned with one hard link"
    mode="${BASH_REMATCH[1]}"
    (( (8#$mode & 022) == 0 )) || release_die 77 "GeoIP input must not be group/world writable"
}

release_validate_geoip_payload() {
    [[ -n "${INPLACEX_AD_MARKET_COUNTRY_HEADER:-}" ]] && return 0
    python3 -I - "$INPLACEX_GEOIP_DB_PATH" <<'PY'
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
size = path.stat().st_size
if not 65_536 <= size <= 256 * 1024 * 1024:
    raise SystemExit("GeoIP MMDB size is outside the production bound")
with path.open("rb") as source:
    source.seek(max(0, size - 131_072))
    trailer = source.read()
if b"\xab\xcd\xefMaxMind.com" not in trailer:
    raise SystemExit("GeoIP file is missing the MaxMind DB metadata marker")
PY
}

release_validate_backup_directory() {
    local directory="$1"
    release_validate_durable_directory_path "$directory" "Backup directory"
    release_validate_absolute_parent_chain "$directory"
    [[ -d "$directory" && ! -L "$directory" ]] ||
        release_die 66 "Backup directory must be a real absolute directory"
    [[ "$(stat -c '%u %g %a' -- "$directory")" == "0 0 700" ]] ||
        release_die 77 "Backup directory must be root:root mode 0700"
}

release_verify_pulled_image() {
    local image="$1"
    local expected_release_id="${2:-}"
    local expected_git_sha="${3:-}"
    local expected_source_archive_sha256="${4:-}"
    release_validate_image_reference "$image"
    release_docker pull "$image" >/dev/null
    local expected_digest="${image##*@}"
    local repo_digests
    repo_digests="$(release_docker image inspect --format '{{json .RepoDigests}}' "$image")"
    python3 -I - "$repo_digests" "$image" "$expected_digest" <<'PY'
import json
import sys

repo_digests = json.loads(sys.argv[1])
configured = sys.argv[2]
expected_digest = sys.argv[3]
repository = configured.rsplit("@", 1)[0]
last_slash = repository.rfind("/")
last_colon = repository.rfind(":")
if last_colon > last_slash:
    repository = repository[:last_colon]
expected = repository + "@" + expected_digest
if not isinstance(repo_digests, list) or expected not in repo_digests:
    raise SystemExit("Pulled image RepoDigests do not contain the exact configured digest")
PY
    if [[ -n "$expected_release_id" || -n "$expected_git_sha" ]]; then
        [[ -n "$expected_release_id" && -n "$expected_git_sha" && -n "$expected_source_archive_sha256" ]] ||
            release_die 65 "Application image label expectations must be provided together"
        local actual_release_id actual_git_sha actual_source_archive_sha256
        actual_release_id="$(release_docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.version" }}' "$image")"
        actual_git_sha="$(release_docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$image")"
        actual_source_archive_sha256="$(release_docker image inspect --format '{{ index .Config.Labels "com.mirkori.inplacex.source-archive-sha256" }}' "$image")"
        [[ "$actual_release_id" == "$expected_release_id" ]] ||
            release_die 75 "Application image OCI version label does not match the release ID"
        [[ "$actual_git_sha" == "$expected_git_sha" ]] ||
            release_die 75 "Application image OCI revision label does not match the Git SHA"
        [[ "$actual_source_archive_sha256" == "$expected_source_archive_sha256" ]] ||
            release_die 75 "Application image source archive label does not match the release manifest"
    fi
}

release_validate_release_manifest() {
    local path="$INPLACEX_RELEASE_MANIFEST_PATH"
    release_validate_absolute_parent_chain "$path"
    [[ -f "$path" && ! -L "$path" ]] || release_die 66 "Release manifest must be a regular non-symlink file"
    [[ "$(stat -Lc '%F %u %g %a %h' -- "$path")" == "regular file 0 0 600 1" ]] ||
        release_die 77 "Release manifest must be root:root 0600 with one link"
    python3 -I - "$path" "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_SOURCE_ARCHIVE_SHA256" \
        "$INPLACEX_BACKEND_IMAGE" "$INPLACEX_IMAGE_DIGEST" <<'PY'
import json
import pathlib
import re
import sys

value = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
base_fields = {
    "schemaVersion", "component", "releaseId", "gitSha", "sourceArchiveSha256",
    "image", "imageDigest", "builderBase", "runtimeBase", "attestations",
}
schema_version = value.get("schemaVersion")
if type(schema_version) is not int or schema_version not in {1, 2}:
    raise SystemExit("Release manifest schemaVersion must be the integer 1 or 2")
required = base_fields if schema_version == 1 else base_fields | {"attestationEvidence"}
if set(value) != required:
    raise SystemExit("Release manifest fields differ from the reviewed v1/v2 schemas")
expected_base = {
    "schemaVersion": schema_version,
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
if {key: value[key] for key in base_fields} != expected_base:
    raise SystemExit("Release manifest does not match the exact requested artifact")
if schema_version == 2:
    evidence = value["attestationEvidence"]
    evidence_fields = {
        "attestationManifestDigests", "provenancePredicate", "provenanceBuildType",
        "provenanceSha256", "sbomPredicate", "sbomSpdxVersion", "sbomSha256",
    }
    if not isinstance(evidence, dict) or set(evidence) != evidence_fields:
        raise SystemExit("Release attestation evidence fields differ from schema v2")
    digests = evidence["attestationManifestDigests"]
    if (
        not isinstance(digests, list)
        or not digests
        or digests != sorted(set(digests))
        or any(not isinstance(item, str) or re.fullmatch(r"sha256:[0-9a-f]{64}", item) is None for item in digests)
    ):
        raise SystemExit("Release attestation manifest digests are invalid")
    if evidence["provenancePredicate"] != "SLSA":
        raise SystemExit("Release provenance predicate identity is invalid")
    if not isinstance(evidence["provenanceBuildType"], str) or not evidence["provenanceBuildType"].startswith("https://"):
        raise SystemExit("Release provenance build type is invalid")
    if (
        evidence["sbomPredicate"] != "SPDX"
        or not isinstance(evidence["sbomSpdxVersion"], str)
        or re.fullmatch(r"SPDX-[0-9]+\.[0-9]+", evidence["sbomSpdxVersion"]) is None
    ):
        raise SystemExit("Release SBOM predicate identity is invalid")
    for field in ("provenanceSha256", "sbomSha256"):
        if not isinstance(evidence[field], str) or re.fullmatch(r"[0-9a-f]{64}", evidence[field]) is None:
            raise SystemExit(f"Release attestation evidence digest is invalid: {field}")
PY
}

release_inspect_environment() {
    local container_id="$1"
    local key="$2"
    release_docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container_id" |
        sed -n "s/^${key}=//p" | head -n 1
}

release_validate_container_id() {
    local container_id="$1"
    local service_name="$2"
    [[ -z "$container_id" || "$container_id" =~ ^[0-9a-f]{12,64}$ ]] ||
        release_die 75 "Expected at most one exact $service_name container"
}

release_stop_backend_fail_closed() {
    local compose_name="$1"
    local before_container after_container stop_status=0 state

    if ! before_container="$("$compose_name" ps --all -q backend 2>/dev/null)"; then
        RELEASE_BACKEND_STOP_PROOF_FAILED=true
        echo "Cannot identify the backend container before stop; refusing state mutation." >&2
        return 1
    fi
    if [[ -n "$before_container" && ! "$before_container" =~ ^[0-9a-f]{12,64}$ ]]; then
        RELEASE_BACKEND_STOP_PROOF_FAILED=true
        echo "Backend container identity before stop is ambiguous or invalid; refusing state mutation." >&2
        return 1
    fi

    "$compose_name" stop --timeout 30 backend >/dev/null 2>&1 || stop_status=$?

    if ! after_container="$("$compose_name" ps --all -q backend 2>/dev/null)"; then
        RELEASE_BACKEND_STOP_PROOF_FAILED=true
        echo "Cannot identify the backend container after stop; refusing state mutation." >&2
        return 1
    fi
    if [[ -n "$after_container" && ! "$after_container" =~ ^[0-9a-f]{12,64}$ ]]; then
        RELEASE_BACKEND_STOP_PROOF_FAILED=true
        echo "Backend container identity after stop is ambiguous or invalid; refusing state mutation." >&2
        return 1
    fi

    if (( stop_status != 0 )); then
        RELEASE_BACKEND_STOP_PROOF_FAILED=true
        echo "Backend stop failed with status $stop_status; refusing state mutation." >&2
        return 1
    fi
    if [[ -n "$before_container" ]]; then
        if ! state="$(release_docker inspect --format '{{.State.Running}}' "$before_container" 2>/dev/null)"; then
            RELEASE_BACKEND_STOP_PROOF_FAILED=true
            echo "Cannot prove that the exact pre-stop backend container is stopped; refusing state mutation." >&2
            return 1
        fi
        [[ "$state" == "false" ]] || {
            RELEASE_BACKEND_STOP_PROOF_FAILED=true
            echo "The exact backend container is still running after stop; refusing state mutation." >&2
            return 1
        }
    fi
    if [[ -n "$after_container" && "$after_container" != "$before_container" ]]; then
        if ! state="$(release_docker inspect --format '{{.State.Running}}' "$after_container" 2>/dev/null)"; then
            RELEASE_BACKEND_STOP_PROOF_FAILED=true
            echo "Cannot prove that the post-stop backend container is stopped; refusing state mutation." >&2
            return 1
        fi
        [[ "$state" == "false" ]] || {
            RELEASE_BACKEND_STOP_PROOF_FAILED=true
            echo "A replacement backend container is running after stop; refusing state mutation." >&2
            return 1
        }
    fi
}

release_validate_postgres_container() {
    local container_id="$1"
    [[ "$(release_docker inspect --format '{{.Config.Image}}' "$container_id")" == "$INPLACEX_POSTGRES_IMAGE" ]] ||
        release_die 75 "Current PostgreSQL image differs from the configured immutable image"
    [[ "$(release_inspect_environment "$container_id" POSTGRES_DB)" == "$INPLACEX_POSTGRES_DB" ]] ||
        release_die 75 "Current PostgreSQL database name differs from configuration"
    [[ "$(release_inspect_environment "$container_id" POSTGRES_USER)" == "$INPLACEX_POSTGRES_USER" ]] ||
        release_die 75 "Current PostgreSQL user differs from configuration"
    local inspection
    inspection="$(release_docker inspect "$container_id")"
    python3 -I - "$inspection" "$INPLACEX_POSTGRES_VOLUME" <<'PY'
import json
import sys

container = json.loads(sys.argv[1])[0]
expected = sys.argv[2]
matches = [m for m in container.get("Mounts", []) if m.get("Destination") == "/var/lib/postgresql/data"]
if len(matches) != 1 or matches[0].get("Type") != "volume" or matches[0].get("Name") != expected:
    raise SystemExit("Current PostgreSQL data mount does not match the configured volume")
PY
}

release_validate_backend_port() {
    local container_id="$1"
    local inspection
    inspection="$(release_docker inspect "$container_id")"
    python3 -I - "$inspection" "$INPLACEX_BACKEND_LOOPBACK_PORT" <<'PY'
import json
import sys

container = json.loads(sys.argv[1])[0]
bindings = container.get("HostConfig", {}).get("PortBindings", {}).get("8080/tcp")
expected = [{"HostIp": "127.0.0.1", "HostPort": sys.argv[2]}]
if bindings != expected:
    raise SystemExit("Current backend does not publish the exact configured loopback port")
PY
}

release_validate_volume() {
    local inspection
    inspection="$(release_docker volume inspect "$INPLACEX_POSTGRES_VOLUME" 2>/dev/null)" ||
        release_die 75 "The external PostgreSQL volume must be provisioned before deployment"
    python3 -I - "$inspection" "$INPLACEX_POSTGRES_VOLUME" <<'PY'
import json
import sys

volume = json.loads(sys.argv[1])[0]
expected_name = sys.argv[2]
labels = volume.get("Labels") or {}
expected_labels = {
    "com.mirkori.product": "inplacex",
    "com.mirkori.component": "online-postgres",
    "com.mirkori.managed": "true",
}
if volume.get("Name") != expected_name or any(labels.get(k) != v for k, v in expected_labels.items()):
    raise SystemExit("PostgreSQL volume name or ownership labels do not match")
PY
}

release_assert_volume_empty() {
    release_docker run --rm --read-only \
        --entrypoint sh \
        --mount "type=volume,source=$INPLACEX_POSTGRES_VOLUME,target=/data,readonly" \
        "$INPLACEX_POSTGRES_IMAGE" \
        -ec 'test -z "$(find /data -mindepth 1 -maxdepth 1 -print -quit)"' ||
        release_die 75 "Initial deployment requires an empty external PostgreSQL volume"
}

release_validate_runtime_secret_reads() {
    local container_id="$1"
    shift
    local runtime_uid
    runtime_uid="$(release_docker exec "$container_id" sh -ec "awk '\$1 == \"Uid:\" { print \$2; exit }' /proc/1/status")"
    [[ "$runtime_uid" =~ ^[0-9]+$ ]] || release_die 70 "Cannot determine runtime UID"
    local path
    for path in "$@"; do
        release_docker exec --user "$runtime_uid" "$container_id" test -r "$path" ||
            release_die 77 "Runtime UID $runtime_uid cannot read $path"
    done
}

release_database_system_identifier() {
    local compose_name="$1"
    # Expansion is intentionally deferred to the shell inside the container.
    # shellcheck disable=SC2016
    "$compose_name" exec -T postgres sh -ec \
        'export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"; exec psql --tuples-only --no-align --set=ON_ERROR_STOP=1 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --command="SELECT system_identifier FROM pg_control_system();"' |
        tr -d '[:space:]'
}

release_sha256_file() {
    sha256sum -- "$1" | awk '{print $1}'
}

release_runtime_config_fingerprint() {
    printf '%s\n' \
        "COMPOSE_PROJECT_NAME=$COMPOSE_PROJECT_NAME" \
        "INPLACEX_POSTGRES_IMAGE=$INPLACEX_POSTGRES_IMAGE" \
        "INPLACEX_POSTGRES_DB=$INPLACEX_POSTGRES_DB" \
        "INPLACEX_POSTGRES_USER=$INPLACEX_POSTGRES_USER" \
        "INPLACEX_POSTGRES_VOLUME=$INPLACEX_POSTGRES_VOLUME" \
        "INPLACEX_BACKEND_LOOPBACK_PORT=$INPLACEX_BACKEND_LOOPBACK_PORT" \
        "INPLACEX_SECRET_DIRECTORY=$INPLACEX_SECRET_DIRECTORY" \
        "INPLACEX_RUNTIME_SECRET_GID=$INPLACEX_RUNTIME_SECRET_GID" \
        "INPLACEX_GEOIP_DB_PATH=$INPLACEX_GEOIP_DB_PATH" \
        "INPLACEX_ONLINE_TOKEN_ISSUER=$INPLACEX_ONLINE_TOKEN_ISSUER" \
        "INPLACEX_ONLINE_TOKEN_AUDIENCE=$INPLACEX_ONLINE_TOKEN_AUDIENCE" \
        "INPLACEX_MATCHMAKING_BOT_FALLBACK_SECONDS=${INPLACEX_MATCHMAKING_BOT_FALLBACK_SECONDS:-5}" \
        "INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS=$INPLACEX_AD_MARKET_TRUSTED_PROXY_HOSTS" \
        "INPLACEX_AD_MARKET_COUNTRY_HEADER=${INPLACEX_AD_MARKET_COUNTRY_HEADER:-}" \
        "INPLACEX_AD_MARKET_CONTAINER_DB_PATH=${INPLACEX_AD_MARKET_CONTAINER_DB_PATH-/var/lib/inplacex/geoip/dbip-country-lite.mmdb}" |
        sha256sum | awk '{print $1}'
}

release_validate_nginx_gate_installation() {
    local installed source
    local -a exact_files=(
        "$RELEASE_MAINTENANCE_SNIPPET|$RELEASE_PRODUCTION_DIRECTORY/inplacex-online-maintenance-gate.conf"
        "/etc/nginx/snippets/inplacex-online-rest-proxy.conf|$RELEASE_PRODUCTION_DIRECTORY/inplacex-online-rest-proxy.conf"
        "/etc/nginx/snippets/inplacex-online-rest-rate-limit.conf|$RELEASE_PRODUCTION_DIRECTORY/inplacex-online-rest-rate-limit.conf"
        "/etc/nginx/snippets/inplacex-online-websocket-rate-limit.conf|$RELEASE_PRODUCTION_DIRECTORY/inplacex-online-websocket-rate-limit.conf"
        "/etc/nginx/snippets/inplacex-ad-market-proxy.conf|$RELEASE_PRODUCTION_DIRECTORY/../ads/nginx-ad-market-proxy.conf"
        "/etc/nginx/conf.d/inplacex-online-rate-zones.conf|$RELEASE_PRODUCTION_DIRECTORY/inplacex-online-rate-zones.conf"
    )
    for installed_and_source in "${exact_files[@]}"; do
        installed="${installed_and_source%%|*}"
        source="${installed_and_source#*|}"
        release_validate_absolute_parent_chain "$installed"
        [[ -f "$installed" && ! -L "$installed" ]] ||
            release_die 75 "Required nginx production file is not installed: $installed"
        [[ "$(stat -Lc '%F %u %g %a %h' -- "$installed")" == "regular file 0 0 644 1" ]] ||
            release_die 77 "Installed nginx file must be root:root 0644 with one link: $installed"
        cmp -s -- "$source" "$installed" ||
            release_die 75 "Installed nginx file differs from the reviewed production contract: $installed"
    done

    release_validate_absolute_parent_chain "$RELEASE_INSTALLED_LOCATIONS"
    [[ -f "$RELEASE_INSTALLED_LOCATIONS" && ! -L "$RELEASE_INSTALLED_LOCATIONS" ]] ||
        release_die 75 "Rendered InplaceX nginx locations are not installed"
    [[ "$(stat -Lc '%F %u %g %a %h' -- "$RELEASE_INSTALLED_LOCATIONS")" == "regular file 0 0 644 1" ]] ||
        release_die 77 "Rendered nginx locations must be root:root 0644 with one link"
    local expected_locations
    expected_locations="$(mktemp "$RELEASE_RUNTIME_DIRECTORY/.expected-nginx.XXXXXX")"
    "$RELEASE_PRODUCTION_DIRECTORY/render-nginx-config.sh" \
        "$INPLACEX_BACKEND_LOOPBACK_PORT" "$INPLACEX_OPERATOR_NETWORK_CIDR" "$expected_locations" >/dev/null
    cmp -s -- "$expected_locations" "$RELEASE_INSTALLED_LOCATIONS" || {
        rm -f -- "$expected_locations"
        release_die 75 "Installed InplaceX locations do not exactly match the port/operator contract"
    }
    rm -f -- "$expected_locations"

    local nginx_configuration
    nginx_configuration="$(mktemp "$RELEASE_RUNTIME_DIRECTORY/.nginx-T.XXXXXX")"
    if ! nginx -T > "$nginx_configuration" 2>&1; then
        rm -f -- "$nginx_configuration"
        release_die 75 "nginx configuration is invalid"
    fi
    python3 -I - "$nginx_configuration" "$RELEASE_INSTALLED_LOCATIONS" "$INPLACEX_PUBLIC_HOSTNAME" <<'PY'
import pathlib
import re
import sys

configuration = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="strict")
include_line = f"include {sys.argv[2]};"
hostname = sys.argv[3]
if configuration.count(include_line) != 1:
    raise SystemExit("The exact InplaceX locations file must be included exactly once")

servers = []
for match in re.finditer(r"\bserver\s*\{", configuration):
    depth = 1
    position = match.end()
    while position < len(configuration) and depth:
        if configuration[position] == "{":
            depth += 1
        elif configuration[position] == "}":
            depth -= 1
        position += 1
    if depth == 0:
        servers.append(configuration[match.start():position])
matches = [
    block for block in servers
    if include_line in block
    and re.search(r"\blisten\s+[^;]*\b443\b[^;]*\bssl\b[^;]*;", block)
    and re.search(rf"\bserver_name\s+[^;\n]*\b{re.escape(hostname)}\b[^;\n]*;", block)
]
if len(matches) != 1:
    raise SystemExit("InplaceX locations must belong to exactly one HTTPS server for the configured hostname")
PY
    rm -f -- "$nginx_configuration"
}

release_enable_maintenance() {
    local gate_deployment_id="$1"
    local temporary="$RELEASE_RUNTIME_DIRECTORY/.maintenance.$gate_deployment_id"
    printf '%s\n' "$gate_deployment_id" > "$temporary"
    chown root:root "$temporary"
    chmod 0644 "$temporary"
    mv -f -- "$temporary" "$RELEASE_MAINTENANCE_FILE"
}

release_enable_drain() {
    local drain_deployment_id="$1"
    local temporary="$RELEASE_RUNTIME_DIRECTORY/.drain.$drain_deployment_id"
    printf '%s\n' "$drain_deployment_id" > "$temporary"
    chown root:root "$temporary"
    chmod 0644 "$temporary"
    mv -f -- "$temporary" "$RELEASE_DRAIN_FILE"
}

release_wait_for_drain() {
    local base_url="$1"
    local timeout_seconds="$2"
    local deadline=$(( $(date +%s) + 10#$timeout_seconds ))
    local response
    while (( $(date +%s) <= deadline )); do
        response="$(curl --fail --silent --show-error --connect-timeout 2 --max-time 5 \
            "$base_url/admin/drain/status")" || {
            sleep 1
            continue
        }
        if python3 -I - "$response" <<'PY'
import json
import sys

value = json.loads(sys.argv[1])
if set(value) != {"draining", "activeRequests"} or value["draining"] is not True:
    raise SystemExit(1)
active = value["activeRequests"]
if isinstance(active, bool) or not isinstance(active, int) or active < 0:
    raise SystemExit(1)
raise SystemExit(0 if active == 0 else 2)
PY
        then
            return 0
        fi
        sleep 1
    done
    release_die 75 "Online runtime did not drain within ${timeout_seconds}s; backend remains gated and running"
}

release_disable_maintenance() {
    if [[ -e "$RELEASE_MAINTENANCE_FILE" ]]; then
        [[ -f "$RELEASE_MAINTENANCE_FILE" && ! -L "$RELEASE_MAINTENANCE_FILE" ]] ||
            release_die 77 "Maintenance gate path is not a regular file"
        [[ "$(stat -Lc '%u %g %a %h' -- "$RELEASE_MAINTENANCE_FILE")" == "0 0 644 1" ]] ||
            release_die 77 "Maintenance gate file ownership or mode changed"
        rm -f -- "$RELEASE_MAINTENANCE_FILE"
    fi
}

release_disable_drain() {
    if [[ -e "$RELEASE_DRAIN_FILE" ]]; then
        [[ -f "$RELEASE_DRAIN_FILE" && ! -L "$RELEASE_DRAIN_FILE" ]] ||
            release_die 77 "Drain gate path is not a regular file"
        [[ "$(stat -Lc '%u %g %a %h' -- "$RELEASE_DRAIN_FILE")" == "0 0 644 1" ]] ||
            release_die 77 "Drain gate file ownership or mode changed"
        rm -f -- "$RELEASE_DRAIN_FILE"
    fi
}

release_sync_file_and_parent() {
    local path="$1"
    sync -f "$path" || release_die 74 "Could not durably flush $path"
    release_sync_directory "$(dirname -- "$path")"
}

release_sync_directory() {
    sync -f "$1" || release_die 74 "Could not durably flush directory $1"
}

release_publish_new_file_no_replace() {
    local source="$1"
    local destination="$2"
    python3 -I - "$source" "$destination" <<'PY'
import ctypes
import errno
import os
import pathlib
import stat
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
if not source.is_absolute() or not destination.is_absolute():
    raise SystemExit("Atomic publication requires absolute source and destination paths")
source_parent = source.parent.resolve(strict=True)
destination_parent = destination.parent.resolve(strict=True)
if source_parent != destination_parent or source.name == destination.name:
    raise SystemExit("Atomic publication requires distinct names in one existing directory")

directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC
if hasattr(os, "O_NOFOLLOW"):
    directory_flags |= os.O_NOFOLLOW
file_flags = os.O_RDONLY | os.O_CLOEXEC
if hasattr(os, "O_NOFOLLOW"):
    file_flags |= os.O_NOFOLLOW

directory_fd = os.open(source_parent, directory_flags)
source_fd = -1
try:
    source_fd = os.open(source.name, file_flags, dir_fd=directory_fd)
    source_stat = os.fstat(source_fd)
    if not stat.S_ISREG(source_stat.st_mode) or source_stat.st_nlink != 1:
        raise SystemExit("Atomic publication source must be a single-link regular file")

    libc = ctypes.CDLL(None, use_errno=True)
    try:
        renameat2 = libc.renameat2
    except AttributeError:
        raise SystemExit("renameat2 is required for atomic no-replace publication")
    renameat2.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    ]
    renameat2.restype = ctypes.c_int
    result = renameat2(
        directory_fd,
        os.fsencode(source.name),
        directory_fd,
        os.fsencode(destination.name),
        1,  # RENAME_NOREPLACE
    )
    if result != 0:
        error_number = ctypes.get_errno()
        if error_number == errno.EEXIST:
            print("Publication destination already exists", file=sys.stderr)
            raise SystemExit(75)
        if error_number in {errno.ENOSYS, errno.EINVAL, errno.ENOTSUP}:
            print("Atomic no-replace publication is unavailable", file=sys.stderr)
            raise SystemExit(69)
        raise OSError(error_number, os.strerror(error_number), str(destination))

    destination_stat = os.stat(destination.name, dir_fd=directory_fd, follow_symlinks=False)
    if (
        not stat.S_ISREG(destination_stat.st_mode)
        or destination_stat.st_dev != source_stat.st_dev
        or destination_stat.st_ino != source_stat.st_ino
        or destination_stat.st_nlink != 1
    ):
        raise SystemExit("Published destination identity differs from the staged file")
    os.fsync(source_fd)
    os.fsync(directory_fd)
finally:
    if source_fd >= 0:
        os.close(source_fd)
    os.close(directory_fd)
PY
}

release_remove_durable_file() {
    local path="$1"
    if [[ -L "$path" || ( -e "$path" && ! -f "$path" ) ]]; then
        release_die 66 "Refusing to remove unsafe durable state: $path"
    fi
    rm -f -- "$path"
    release_sync_directory "$(dirname -- "$path")"
}

release_atomic_kv_file() {
    local destination="$1"
    shift
    release_validate_absolute_parent_chain "$destination"
    local temporary
    temporary="$(mktemp "$(dirname -- "$destination")/.release-kv.XXXXXX")"
    chmod 0600 "$temporary"
    printf '%s\n' "$@" > "$temporary"
    mv -f -- "$temporary" "$destination"
    release_sync_file_and_parent "$destination"
}

release_write_activation_record() {
    local destination="$1"
    local activation_release_id="$2"
    local activation_git_sha="$3"
    local activation_image_digest="$4"
    local activation_database_password_sha256="$5"
    local activation_state_key_sha256="$6"
    local activation_public_key_sha256="$7"
    local activation_geoip_sha256="$8"
    local activation_runtime_config_sha256="$9"
    local activation_expires_at="${10:-}"
    local temporary
    temporary="$(mktemp "$(dirname -- "$destination")/.activation.XXXXXX")"
    printf '%s\n' \
        "INPLACEX_ACTIVATION_VERSION=2" \
        "INPLACEX_ACTIVATION_RELEASE_ID=$activation_release_id" \
        "INPLACEX_ACTIVATION_GIT_SHA=$activation_git_sha" \
        "INPLACEX_ACTIVATION_IMAGE_DIGEST=$activation_image_digest" \
        "INPLACEX_ACTIVATION_DATABASE_PASSWORD_SHA256=$activation_database_password_sha256" \
        "INPLACEX_ACTIVATION_STATE_KEY_SHA256=$activation_state_key_sha256" \
        "INPLACEX_ACTIVATION_PUBLIC_KEY_SHA256=$activation_public_key_sha256" \
        "INPLACEX_ACTIVATION_GEOIP_SHA256=$activation_geoip_sha256" \
        "INPLACEX_ACTIVATION_RUNTIME_CONFIG_SHA256=$activation_runtime_config_sha256" > "$temporary"
    if [[ -n "$activation_expires_at" ]]; then
        [[ "$activation_expires_at" =~ ^[0-9]{10,12}$ ]] || release_die 65 "Activation lease expiry is invalid"
        printf '%s\n' "INPLACEX_ACTIVATION_EXPIRES_AT_EPOCH_SECOND=$activation_expires_at" >> "$temporary"
    fi
    chown "root:$INPLACEX_RUNTIME_SECRET_GID" "$temporary"
    chmod 0440 "$temporary"
    mv -f -- "$temporary" "$destination"
    release_sync_file_and_parent "$destination"
}

release_load_verified_activation() {
    local path="$RELEASE_VERIFIED_ACTIVATION_FILE"
    [[ -f "$path" && ! -L "$path" ]] || return 1
    [[ "$(stat -Lc '%F %u %g %a %h' -- "$path")" == "regular file 0 $INPLACEX_RUNTIME_SECRET_GID 440 1" ]] ||
        release_die 77 "Verified activation state ownership or mode is invalid"
    local -a activation_lines activation_keys
    mapfile -t activation_lines < "$path"
    [[ ${#activation_lines[@]} -ge 1 && "${activation_lines[0]}" == INPLACEX_ACTIVATION_VERSION=* ]] ||
        release_die 65 "Verified activation version field is missing or out of order"
    local activation_version="${activation_lines[0]#*=}"
    case "$activation_version" in
        1)
            [[ ${#activation_lines[@]} -eq 8 ]] ||
                release_die 65 "Verified activation v1 state must contain exactly eight fields"
            activation_keys=(
                INPLACEX_ACTIVATION_VERSION
                INPLACEX_ACTIVATION_RELEASE_ID
                INPLACEX_ACTIVATION_GIT_SHA
                INPLACEX_ACTIVATION_IMAGE_DIGEST
                INPLACEX_ACTIVATION_STATE_KEY_SHA256
                INPLACEX_ACTIVATION_PUBLIC_KEY_SHA256
                INPLACEX_ACTIVATION_GEOIP_SHA256
                INPLACEX_ACTIVATION_RUNTIME_CONFIG_SHA256
            )
            ;;
        2)
            [[ ${#activation_lines[@]} -eq 9 ]] ||
                release_die 65 "Verified activation v2 state must contain exactly nine fields"
            activation_keys=(
                INPLACEX_ACTIVATION_VERSION
                INPLACEX_ACTIVATION_RELEASE_ID
                INPLACEX_ACTIVATION_GIT_SHA
                INPLACEX_ACTIVATION_IMAGE_DIGEST
                INPLACEX_ACTIVATION_DATABASE_PASSWORD_SHA256
                INPLACEX_ACTIVATION_STATE_KEY_SHA256
                INPLACEX_ACTIVATION_PUBLIC_KEY_SHA256
                INPLACEX_ACTIVATION_GEOIP_SHA256
                INPLACEX_ACTIVATION_RUNTIME_CONFIG_SHA256
            )
            ;;
        *)
            release_die 65 "Unsupported verified activation version"
            ;;
    esac
    unset VERIFIED_VERSION VERIFIED_RELEASE_ID VERIFIED_GIT_SHA VERIFIED_IMAGE_DIGEST \
        VERIFIED_DATABASE_PASSWORD_SHA256 VERIFIED_STATE_KEY_SHA256 VERIFIED_PUBLIC_KEY_SHA256 \
        VERIFIED_GEOIP_SHA256 VERIFIED_RUNTIME_CONFIG_SHA256
    local index key value
    for index in "${!activation_keys[@]}"; do
        key="${activation_keys[$index]}"
        [[ "${activation_lines[$index]}" == "$key="* ]] ||
            release_die 65 "Verified activation fields are incomplete or out of order"
        value="${activation_lines[$index]#*=}"
        printf -v "VERIFIED_${key#INPLACEX_ACTIVATION_}" '%s' "$value"
    done
    [[ "$VERIFIED_RELEASE_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || release_die 65 "Verified release ID is invalid"
    [[ "$VERIFIED_GIT_SHA" =~ ^[0-9a-f]{40}$ ]] || release_die 65 "Verified Git SHA is invalid"
    [[ "$VERIFIED_IMAGE_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || release_die 65 "Verified image digest is invalid"
    local -a activation_fingerprints=(
        "$VERIFIED_STATE_KEY_SHA256"
        "$VERIFIED_PUBLIC_KEY_SHA256"
        "$VERIFIED_GEOIP_SHA256"
        "$VERIFIED_RUNTIME_CONFIG_SHA256"
    )
    if [[ "$VERIFIED_VERSION" == "2" ]]; then
        activation_fingerprints=("$VERIFIED_DATABASE_PASSWORD_SHA256" "${activation_fingerprints[@]}")
    else
        VERIFIED_DATABASE_PASSWORD_SHA256=""
    fi
    for value in "${activation_fingerprints[@]}"; do
        [[ "$value" =~ ^[0-9a-f]{64}$ ]] || release_die 65 "Verified activation fingerprint is invalid"
    done
}

release_assert_secret_continuity() {
    local current_database_password_sha256="$1"
    local current_state_key_sha256="$2"
    local current_public_key_sha256="$3"
    local v1_policy="${4:-reject-v1}"
    RELEASE_ACTIVATION_V1_MIGRATION_REQUIRED=false
    release_load_verified_activation || return 0
    [[ "$current_state_key_sha256" == "$VERIFIED_STATE_KEY_SHA256" ]] ||
        release_die 75 "Online state key differs from the durable verified activation; an explicit data-key migration is required"
    if [[ "$VERIFIED_VERSION" == "1" ]]; then
        [[ "$v1_policy" == "allow-deploy-migration" ]] ||
            release_die 75 "Verified activation v1 must first be migrated by deploy-backend.sh with INPLACEX_ACTIVATION_V1_MIGRATION_ACK=$RELEASE_ACTIVATION_V1_MIGRATION_ACK"
        [[ "${INPLACEX_ACTIVATION_V1_MIGRATION_ACK:-}" == "$RELEASE_ACTIVATION_V1_MIGRATION_ACK" ]] ||
            release_die 75 "Verified activation v1 migration requires INPLACEX_ACTIVATION_V1_MIGRATION_ACK=$RELEASE_ACTIVATION_V1_MIGRATION_ACK"
        [[ "$current_public_key_sha256" == "$VERIFIED_PUBLIC_KEY_SHA256" ]] ||
            release_die 75 "Activation v1 migration must use the exact verified platform public key"
        [[ -z "${INPLACEX_ALLOW_PUBLIC_KEY_ROTATION_FROM_SHA256:-}" ]] ||
            release_die 75 "Activation v1 migration cannot be combined with platform public-key rotation"
        RELEASE_ACTIVATION_V1_MIGRATION_REQUIRED=true
        export RELEASE_ACTIVATION_V1_MIGRATION_REQUIRED
        return 0
    fi
    [[ "$current_database_password_sha256" == "$VERIFIED_DATABASE_PASSWORD_SHA256" ]] ||
        release_die 75 "Database password differs from the durable verified activation; use an explicit password-rotation package"
    if [[ "$current_public_key_sha256" != "$VERIFIED_PUBLIC_KEY_SHA256" ]]; then
        [[ "${INPLACEX_ALLOW_PUBLIC_KEY_ROTATION_FROM_SHA256:-}" == "$VERIFIED_PUBLIC_KEY_SHA256" ]] ||
            release_die 75 "Platform public key rotation requires the exact previous SHA-256 acknowledgement"
    fi
}

release_verify_v1_activation_migration_source() {
    local container_id="$1"
    local expected_image="$2"
    local expected_database_password_sha256="$3"
    local expected_state_key_sha256="$4"
    local expected_public_key_sha256="$5"
    local expected_geoip_sha256="$6"
    local expected_runtime_config_sha256="$7"

    release_load_verified_activation ||
        release_die 75 "Activation v1 migration requires the durable verified activation"
    [[ "$VERIFIED_VERSION" == "1" ]] ||
        release_die 75 "Activation v1 migration source changed before it was verified"
    [[ -n "$container_id" ]] ||
        release_die 75 "Activation v1 migration requires the exact verified backend to be running"
    release_validate_container_id "$container_id" backend
    [[ "$(release_docker inspect --format '{{.State.Running}}' "$container_id")" == "true" &&
        "$(release_docker inspect --format '{{.Config.Image}}' "$container_id")" == "$expected_image" &&
        "${expected_image##*@}" == "$VERIFIED_IMAGE_DIGEST" &&
        "$(release_inspect_environment "$container_id" INPLACEX_RELEASE_ID)" == "$VERIFIED_RELEASE_ID" &&
        "$(release_inspect_environment "$container_id" INPLACEX_GIT_SHA)" == "$VERIFIED_GIT_SHA" &&
        "$(release_inspect_environment "$container_id" INPLACEX_IMAGE_DIGEST)" == "$VERIFIED_IMAGE_DIGEST" &&
        "$(release_inspect_environment "$container_id" INPLACEX_RUNTIME_CONFIG_SHA256)" == "$VERIFIED_RUNTIME_CONFIG_SHA256" ]] ||
        release_die 75 "Running backend is not the exact durable activation v1 migration source"
    [[ "$VERIFIED_STATE_KEY_SHA256" == "$expected_state_key_sha256" &&
        "$VERIFIED_PUBLIC_KEY_SHA256" == "$expected_public_key_sha256" &&
        "$VERIFIED_GEOIP_SHA256" == "$expected_geoip_sha256" &&
        "$VERIFIED_RUNTIME_CONFIG_SHA256" == "$expected_runtime_config_sha256" ]] ||
        release_die 75 "Activation v1 fingerprints differ from the requested migration environment"
    [[ "$(release_inspect_environment "$container_id" INPLACEX_DATABASE_PASSWORD_PATH)" == "/run/secrets/inplacex_database_password" &&
        "$(release_inspect_environment "$container_id" INPLACEX_ONLINE_STATE_KEY_BASE64_PATH)" == "/run/secrets/inplacex_online_state_key" &&
        "$(release_inspect_environment "$container_id" INPLACEX_ONLINE_PUBLIC_KEY_X509_BASE64_PATH)" == "/run/secrets/inplacex_online_public_key" &&
        "$(release_inspect_environment "$container_id" INPLACEX_ACTIVATION_GEOIP_FINGERPRINT_PATH)" == "/var/lib/inplacex/geoip/dbip-country-lite.mmdb" ]] ||
        release_die 75 "Activation v1 migration source uses unexpected fingerprint paths"

    local path expected_hash actual_hash
    while IFS='|' read -r path expected_hash; do
        if ! actual_hash="$(release_docker exec "$container_id" sha256sum -- "$path" | awk '{print $1}')"; then
            release_die 75 "Cannot fingerprint $path inside the activation v1 migration source"
        fi
        [[ "$actual_hash" =~ ^[0-9a-f]{64}$ && "$actual_hash" == "$expected_hash" ]] ||
            release_die 75 "Mounted activation v1 fingerprint differs inside the exact running backend: $path"
    done <<EOF
/run/secrets/inplacex_database_password|$expected_database_password_sha256
/run/secrets/inplacex_online_state_key|$expected_state_key_sha256
/run/secrets/inplacex_online_public_key|$expected_public_key_sha256
/var/lib/inplacex/geoip/dbip-country-lite.mmdb|$expected_geoip_sha256
EOF
}

release_running_backend_matches_verified_activation() {
    local container_id="$1"
    local expected_image="$2"
    local expected_release_id="$3"
    local expected_git_sha="$4"
    local expected_image_digest="$5"
    local expected_database_password_sha256="$6"
    local expected_state_key_sha256="$7"
    local expected_public_key_sha256="$8"
    local expected_geoip_sha256="$9"
    local expected_runtime_config_sha256="${10}"

    [[ -n "$container_id" &&
        "$(release_docker inspect --format '{{.State.Running}}' "$container_id" 2>/dev/null || true)" == "true" &&
        "$(release_docker inspect --format '{{.Config.Image}}' "$container_id" 2>/dev/null || true)" == "$expected_image" &&
        "$(release_inspect_environment "$container_id" INPLACEX_RELEASE_ID)" == "$expected_release_id" &&
        "$(release_inspect_environment "$container_id" INPLACEX_GIT_SHA)" == "$expected_git_sha" &&
        "$(release_inspect_environment "$container_id" INPLACEX_IMAGE_DIGEST)" == "$expected_image_digest" ]] ||
        return 1
    release_load_verified_activation || return 1
    [[ "$VERIFIED_RELEASE_ID" == "$expected_release_id" &&
        "$VERIFIED_GIT_SHA" == "$expected_git_sha" &&
        "$VERIFIED_IMAGE_DIGEST" == "$expected_image_digest" &&
        "$VERIFIED_DATABASE_PASSWORD_SHA256" == "$expected_database_password_sha256" &&
        "$VERIFIED_STATE_KEY_SHA256" == "$expected_state_key_sha256" &&
        "$VERIFIED_PUBLIC_KEY_SHA256" == "$expected_public_key_sha256" &&
        "$VERIFIED_GEOIP_SHA256" == "$expected_geoip_sha256" &&
        "$VERIFIED_RUNTIME_CONFIG_SHA256" == "$expected_runtime_config_sha256" ]]
}

release_start_activation_lease() {
    local activation_release_id="$1"
    local activation_git_sha="$2"
    local activation_image_digest="$3"
    local activation_database_password_sha256="$4"
    local activation_state_key_sha256="$5"
    local activation_public_key_sha256="$6"
    local activation_geoip_sha256="$7"
    local activation_runtime_config_sha256="$8"
    release_stop_activation_lease
    local owner_pid=$$
    local owner_start
    owner_start="$(awk '{print $22}' "/proc/$owner_pid/stat")"
    (
        exec {RELEASE_LOCK_FD}>&-
        while kill -0 "$owner_pid" >/dev/null 2>&1 &&
            [[ "$(awk '{print $22}' "/proc/$owner_pid/stat" 2>/dev/null || true)" == "$owner_start" ]]; do
            release_write_activation_record \
                "$RELEASE_PENDING_ACTIVATION_FILE" "$activation_release_id" \
                "$activation_git_sha" "$activation_image_digest" \
                "$activation_database_password_sha256" "$activation_state_key_sha256" "$activation_public_key_sha256" \
                "$activation_geoip_sha256" "$activation_runtime_config_sha256" \
                "$(( $(date +%s) + 8 ))"
            sleep 1
        done
        if [[ -f "$RELEASE_PENDING_ACTIVATION_FILE" && ! -L "$RELEASE_PENDING_ACTIVATION_FILE" ]]; then
            rm -f -- "$RELEASE_PENDING_ACTIVATION_FILE"
            sync -f "$RELEASE_PENDING_ACTIVATION_DIRECTORY" || true
        fi
    ) &
    RELEASE_ACTIVATION_LEASE_PID=$!
    export RELEASE_ACTIVATION_LEASE_PID
    local attempts=0
    while [[ ! -f "$RELEASE_PENDING_ACTIVATION_FILE" && $attempts -lt 20 ]]; do
        attempts=$((attempts + 1))
        sleep 0.25
    done
    [[ -f "$RELEASE_PENDING_ACTIVATION_FILE" ]] ||
        release_die 73 "Candidate activation lease could not be created"
}

release_stop_activation_lease() {
    if [[ -n "${RELEASE_ACTIVATION_LEASE_PID:-}" ]]; then
        kill "$RELEASE_ACTIVATION_LEASE_PID" >/dev/null 2>&1 || true
        wait "$RELEASE_ACTIVATION_LEASE_PID" >/dev/null 2>&1 || true
        RELEASE_ACTIVATION_LEASE_PID=""
    fi
    if [[ -e "$RELEASE_PENDING_ACTIVATION_FILE" ]]; then
        [[ -f "$RELEASE_PENDING_ACTIVATION_FILE" && ! -L "$RELEASE_PENDING_ACTIVATION_FILE" ]] ||
            release_die 66 "Pending activation permit became unsafe"
        rm -f -- "$RELEASE_PENDING_ACTIVATION_FILE"
    fi
}

release_fault_inject() {
    local phase="$1"
    [[ -z "${INPLACEX_RELEASE_FAULT_PHASE:-}" || "$INPLACEX_RELEASE_FAULT_PHASE" != "$phase" ]] && return 0
    [[ "${INPLACEX_RELEASE_FAULT_TEST_ACK:-}" == "isolated-ci-host" ]] ||
        release_die 77 "Release fault injection requires the isolated CI acknowledgement"
    sync
    kill -KILL $$
}

release_validate_backup_file() {
    local path="$1"
    release_validate_absolute_parent_chain "$path"
    [[ -f "$path" && ! -L "$path" ]] || release_die 66 "Backup must be a regular non-symlink file"
    [[ "$(stat -Lc '%F %u %g %a %h' -- "$path")" == "regular file 0 0 600 1" ]] ||
        release_die 77 "Backup must be a root:root 0600 single-link regular file"
}

release_new_deployment_id() {
    local deployment_id
    deployment_id="$(cat /proc/sys/kernel/random/uuid)"
    [[ "$deployment_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] ||
        release_die 70 "Kernel did not provide a valid deployment UUID"
    printf '%s\n' "$deployment_id"
}
