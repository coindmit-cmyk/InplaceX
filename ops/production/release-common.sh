#!/usr/bin/env bash

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
RELEASE_PRODUCTION_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly RELEASE_PRODUCTION_DIRECTORY

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

release_write_sanitized_env() {
    local output_path="$1"
    local allowlist_name="$2"
    local -n allowed_keys="$allowlist_name"
    [[ "$output_path" == "$RELEASE_RUNTIME_DIRECTORY/"* ]] ||
        release_die 66 "Sanitized Compose environment must stay in $RELEASE_RUNTIME_DIRECTORY"
    [[ ! -e "$output_path" ]] || release_die 73 "Sanitized Compose environment already exists"
    : > "$output_path"
    chmod 0600 "$output_path"
    local key
    while IFS= read -r key; do
        [[ -n "${!key+x}" ]] && printf '%s=%s\n' "$key" "${!key}" >> "$output_path"
    done < <(printf '%s\n' "${!allowed_keys[@]}" | sort)
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
    [[ "$INPLACEX_RELEASE_STATE_DIRECTORY" =~ ^/[A-Za-z0-9_./-]+$ &&
        "$INPLACEX_RELEASE_STATE_DIRECTORY" != "/" &&
        "$INPLACEX_RELEASE_STATE_DIRECTORY" != "/run" &&
        "$INPLACEX_RELEASE_STATE_DIRECTORY" != /run/* ]] ||
        release_die 65 "Release state directory must be a durable absolute path outside /run"
    python3 - "$INPLACEX_OPERATOR_NETWORK_CIDR" "$INPLACEX_PUBLIC_HOSTNAME" <<'PY'
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
    python3 - "$INPLACEX_SECRET_DIRECTORY/database-password.txt" \
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
        python3 - "$INPLACEX_SECRET_DIRECTORY/platform-public-key-x509-base64.txt" <<'PY'
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
    python3 - "$INPLACEX_GEOIP_DB_PATH" <<'PY'
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
    docker pull "$image" >/dev/null
    local expected_digest="${image##*@}"
    local repo_digests
    repo_digests="$(docker image inspect --format '{{json .RepoDigests}}' "$image")"
    python3 - "$repo_digests" "$image" "$expected_digest" <<'PY'
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
        actual_release_id="$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.version" }}' "$image")"
        actual_git_sha="$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$image")"
        actual_source_archive_sha256="$(docker image inspect --format '{{ index .Config.Labels "com.mirkori.inplacex.source-archive-sha256" }}' "$image")"
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
    python3 - "$path" "$INPLACEX_RELEASE_ID" "$INPLACEX_GIT_SHA" "$INPLACEX_SOURCE_ARCHIVE_SHA256" \
        "$INPLACEX_BACKEND_IMAGE" "$INPLACEX_IMAGE_DIGEST" <<'PY'
import json
import pathlib
import sys

value = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
required = {
    "schemaVersion", "component", "releaseId", "gitSha", "sourceArchiveSha256",
    "image", "imageDigest", "builderBase", "runtimeBase", "attestations",
}
if set(value) != required:
    raise SystemExit("Release manifest fields differ from the reviewed schema")
expected = {
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
if value != expected:
    raise SystemExit("Release manifest does not match the exact requested artifact")
PY
}

release_inspect_environment() {
    local container_id="$1"
    local key="$2"
    docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$container_id" |
        sed -n "s/^${key}=//p" | head -n 1
}

release_validate_container_id() {
    local container_id="$1"
    local service_name="$2"
    [[ -z "$container_id" || "$container_id" =~ ^[0-9a-f]{12,64}$ ]] ||
        release_die 75 "Expected at most one exact $service_name container"
}

release_validate_postgres_container() {
    local container_id="$1"
    [[ "$(docker inspect --format '{{.Config.Image}}' "$container_id")" == "$INPLACEX_POSTGRES_IMAGE" ]] ||
        release_die 75 "Current PostgreSQL image differs from the configured immutable image"
    [[ "$(release_inspect_environment "$container_id" POSTGRES_DB)" == "$INPLACEX_POSTGRES_DB" ]] ||
        release_die 75 "Current PostgreSQL database name differs from configuration"
    [[ "$(release_inspect_environment "$container_id" POSTGRES_USER)" == "$INPLACEX_POSTGRES_USER" ]] ||
        release_die 75 "Current PostgreSQL user differs from configuration"
    local inspection
    inspection="$(docker inspect "$container_id")"
    python3 - "$inspection" "$INPLACEX_POSTGRES_VOLUME" <<'PY'
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
    inspection="$(docker inspect "$container_id")"
    python3 - "$inspection" "$INPLACEX_BACKEND_LOOPBACK_PORT" <<'PY'
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
    inspection="$(docker volume inspect "$INPLACEX_POSTGRES_VOLUME" 2>/dev/null)" ||
        release_die 75 "The external PostgreSQL volume must be provisioned before deployment"
    python3 - "$inspection" "$INPLACEX_POSTGRES_VOLUME" <<'PY'
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
    docker run --rm --read-only \
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
    runtime_uid="$(docker exec "$container_id" sh -ec "awk '\$1 == \"Uid:\" { print \$2; exit }' /proc/1/status")"
    [[ "$runtime_uid" =~ ^[0-9]+$ ]] || release_die 70 "Cannot determine runtime UID"
    local path
    for path in "$@"; do
        docker exec --user "$runtime_uid" "$container_id" test -r "$path" ||
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
    python3 - "$nginx_configuration" "$RELEASE_INSTALLED_LOCATIONS" "$INPLACEX_PUBLIC_HOSTNAME" <<'PY'
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
        if python3 - "$response" <<'PY'
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
    local activation_state_key_sha256="$5"
    local activation_public_key_sha256="$6"
    local activation_geoip_sha256="$7"
    local activation_runtime_config_sha256="$8"
    local activation_expires_at="${9:-}"
    local temporary
    temporary="$(mktemp "$(dirname -- "$destination")/.activation.XXXXXX")"
    printf '%s\n' \
        "INPLACEX_ACTIVATION_VERSION=1" \
        "INPLACEX_ACTIVATION_RELEASE_ID=$activation_release_id" \
        "INPLACEX_ACTIVATION_GIT_SHA=$activation_git_sha" \
        "INPLACEX_ACTIVATION_IMAGE_DIGEST=$activation_image_digest" \
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
    mapfile -t activation_lines < "$path"
    [[ ${#activation_lines[@]} -eq 8 ]] || release_die 65 "Verified activation state must contain exactly eight fields"
    local -a activation_keys=(
        INPLACEX_ACTIVATION_VERSION
        INPLACEX_ACTIVATION_RELEASE_ID
        INPLACEX_ACTIVATION_GIT_SHA
        INPLACEX_ACTIVATION_IMAGE_DIGEST
        INPLACEX_ACTIVATION_STATE_KEY_SHA256
        INPLACEX_ACTIVATION_PUBLIC_KEY_SHA256
        INPLACEX_ACTIVATION_GEOIP_SHA256
        INPLACEX_ACTIVATION_RUNTIME_CONFIG_SHA256
    )
    local index key value
    for index in "${!activation_keys[@]}"; do
        key="${activation_keys[$index]}"
        [[ "${activation_lines[$index]}" == "$key="* ]] ||
            release_die 65 "Verified activation fields are incomplete or out of order"
        value="${activation_lines[$index]#*=}"
        printf -v "VERIFIED_${key#INPLACEX_ACTIVATION_}" '%s' "$value"
    done
    [[ "$VERIFIED_VERSION" == "1" ]] || release_die 65 "Unsupported verified activation version"
    [[ "$VERIFIED_RELEASE_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || release_die 65 "Verified release ID is invalid"
    [[ "$VERIFIED_GIT_SHA" =~ ^[0-9a-f]{40}$ ]] || release_die 65 "Verified Git SHA is invalid"
    [[ "$VERIFIED_IMAGE_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] || release_die 65 "Verified image digest is invalid"
    for value in "$VERIFIED_STATE_KEY_SHA256" "$VERIFIED_PUBLIC_KEY_SHA256" \
        "$VERIFIED_GEOIP_SHA256" "$VERIFIED_RUNTIME_CONFIG_SHA256"; do
        [[ "$value" =~ ^[0-9a-f]{64}$ ]] || release_die 65 "Verified activation fingerprint is invalid"
    done
}

release_assert_secret_continuity() {
    local current_state_key_sha256="$1"
    local current_public_key_sha256="$2"
    release_load_verified_activation || return 0
    [[ "$current_state_key_sha256" == "$VERIFIED_STATE_KEY_SHA256" ]] ||
        release_die 75 "Online state key differs from the durable verified activation; an explicit data-key migration is required"
    if [[ "$current_public_key_sha256" != "$VERIFIED_PUBLIC_KEY_SHA256" ]]; then
        [[ "${INPLACEX_ALLOW_PUBLIC_KEY_ROTATION_FROM_SHA256:-}" == "$VERIFIED_PUBLIC_KEY_SHA256" ]] ||
            release_die 75 "Platform public key rotation requires the exact previous SHA-256 acknowledgement"
    fi
}

release_start_activation_lease() {
    local activation_release_id="$1"
    local activation_git_sha="$2"
    local activation_image_digest="$3"
    local activation_state_key_sha256="$4"
    local activation_public_key_sha256="$5"
    local activation_geoip_sha256="$6"
    local activation_runtime_config_sha256="$7"
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
                "$activation_state_key_sha256" "$activation_public_key_sha256" \
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
